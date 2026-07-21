(ns shiftlefter.runner.core
  "Runner orchestration for ShiftLefter.

   Implements the full Phase 1 pipeline:
   1. Load config
   2. Discover feature files
   3. Parse features → AST/errors
   4. Pickle → pickles/errors
   5. Load step definitions
   6. Bind all pickles → plans + diagnostics
   7. Gate: if binding issues → report + exit 2
   8. Execute scenarios → results
   9. Report results
   10. Return exit code

   ## Exit Codes

   - 0: All scenarios passed
   - 1: Executed, some not passed (failed/pending when not allowed)
   - 2: Planning/setup failure (parse/config/load/bind errors)
   - 3: Harness crash (uncaught exception)"
  (:require [babashka.fs :as fs]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [shiftlefter.gherkin.api :as api]
            [shiftlefter.gherkin.io :as io]
            [shiftlefter.costume.wardrobe :as wardrobe]
            [shiftlefter.project-context :as project-context]
            [shiftlefter.runner.config :as config]
            [shiftlefter.runner.discover :as discover]
            [shiftlefter.runner.events :as events]
            [shiftlefter.runner.hooks :as hooks]
            [shiftlefter.intent.state :as intent-state]
            [shiftlefter.runner.report.console :as console]
            [shiftlefter.runner.report.edn :as report-edn]
            [shiftlefter.runner.report.html :as report-html]
            [shiftlefter.runner.report.junit :as report-junit]
            [shiftlefter.runner.reporter :as reporter]
            [shiftlefter.runner.schedule :as schedule]
            [shiftlefter.runner.setup :as setup]
            [shiftlefter.runner.step-loader :as step-loader]
            [shiftlefter.runner.tag-disposition :as tagd]
            [shiftlefter.stepengine.compile :as compile]
            [shiftlefter.stepengine.exec :as exec]
            [shiftlefter.stepengine.registry :as registry]
            [shiftlefter.svo.bindings-lint :as bindings-lint]
            [shiftlefter.version :as version]
            ;; NOTE: Built-in step definitions (shiftlefter.stepdefs.browser)
            ;; are NOT required here — they must be loaded AFTER the step-loader
            ;; clears the registry. See `load-built-in-steps!` below.
            ;; TODO(EP-033): make this config-driven via :built-in-steps in
            ;; shiftlefter.edn so users can opt out.
            ))

;; -----------------------------------------------------------------------------
;; Specs — Parse & Pipeline Result Shapes
;; -----------------------------------------------------------------------------

(s/def ::status #{:ok :error})
(s/def ::path string?)
(s/def ::ast vector?)
(s/def ::tokens vector?)
(s/def ::pickles vector?)
(s/def ::errors (s/coll-of map?))

(s/def ::parse-file-ok
  (s/keys :req-un [::status ::path ::ast ::tokens ::pickles]))

(s/def ::parse-file-error
  (s/keys :req-un [::status ::path ::errors]))

(s/def ::parse-file-result
  (s/or :ok ::parse-file-ok :error ::parse-file-error))

(s/def ::features (s/coll-of map?))
(s/def ::all-pickles vector?)

(s/def ::parse-all-result
  (s/keys :req-un [::status ::features ::all-pickles ::errors]))

;; -----------------------------------------------------------------------------
;; Built-in Step Loading
;; -----------------------------------------------------------------------------

(defn- load-built-in-steps!
  "Load built-in step definitions into the registry.
   Must be called AFTER step-loader/load-step-paths! (which clears the
   registry). Uses :reload to force defstep forms to re-execute even if
   the namespace was previously loaded."
  []
  (require 'shiftlefter.stepdefs.browser :reload)
  (require 'shiftlefter.stepdefs.sms :reload))

;; -----------------------------------------------------------------------------
;; Pipeline Helpers
;; -----------------------------------------------------------------------------

(defn- parse-feature-file
  "Parse a single feature file.
   Returns {:status :ok/:error :path :ast :tokens :pickles :errors}"
  [path]
  (let [read-result (io/read-file-utf8 path)]
    (if (= :error (:status read-result))
      {:status :error
       :path path
       :errors [{:type :io/read-failed
                 :message (:message read-result)
                 :path path}]}
      (let [content (:content read-result)
            {:keys [tokens ast errors]} (api/parse-string content)]
        (if (seq errors)
          {:status :error
           :path path
           :errors errors}
          (let [{:keys [pickles errors]} (api/pickles ast path)]
            (if (seq errors)
              {:status :error
               :path path
               :errors errors}
              {:status :ok
               :path path
               :ast ast
               :tokens tokens
               :pickles pickles})))))))

(defn- parse-all-features
  "Parse all feature files.
   Returns {:status :ok/:error :features [...] :all-pickles [...] :errors [...]}"
  [paths]
  (let [results (mapv parse-feature-file paths)
        errors (mapcat :errors (filter #(= :error (:status %)) results))
        features (filter #(= :ok (:status %)) results)
        all-pickles (mapcat :pickles features)]
    (if (seq errors)
      {:status :error
       :features features
       :all-pickles (vec all-pickles)
       :errors (vec errors)}
      {:status :ok
       :features features
       :all-pickles (vec all-pickles)
       :errors []})))

(defn- compute-exit-code
  "Compute exit code from execution result. Scenario :error (a lifecycle
   hook threw, sl-esq) is a red run like :failed — never exit 2, which is
   reserved for planning failures."
  [exec-result allow-pending?]
  (let [{:keys [failed pending error]} (:counts exec-result)]
    (cond
      (pos? (or error 0)) 1
      (pos? (or failed 0)) 1
      (and (pos? (or pending 0)) (not allow-pending?)) 1
      :else 0)))

(defn- resolve-glossary-config [project-context glossary-config]
  (when glossary-config
    (cond-> glossary-config
      (:subjects glossary-config)
      (update :subjects #(project-context/resolve-config-path project-context %))

      (:intents glossary-config)
      (update :intents #(project-context/resolve-config-path project-context %))

      (:verbs glossary-config)
      (update :verbs (fn [verbs]
                       (into {}
                             (map (fn [[interface-type path]]
                                    [interface-type
                                     (project-context/resolve-config-path project-context path)]))
                             verbs))))))

(defn- resolve-config-declared-paths
  "Resolve path-bearing config values against :config-root exactly once for a run."
  [project-context config]
  (cond-> config
    (seq (get-in config [:runner :step-paths]))
    (update-in [:runner :step-paths]
               #(project-context/resolve-config-paths project-context %))

    (seq (get-in config [:runner :macros :registry-paths]))
    (update-in [:runner :macros :registry-paths]
               #(project-context/resolve-config-paths project-context %))

    ;; JUnit-XML output path (sl-40to): config mirror anchors to :config-root.
    ;; The --junit-xml flag (invocation-root) overrides it later in make-reporters.
    (get-in config [:runner :report :junit-xml])
    (update-in [:runner :report :junit-xml]
               #(project-context/resolve-config-path project-context %))

    ;; HTML report output path (sl-muq9): same rules as :junit-xml.
    (get-in config [:runner :report :html])
    (update-in [:runner :report :html]
               #(project-context/resolve-config-path project-context %))

    (:glossaries config)
    (update :glossaries #(resolve-glossary-config project-context %))))

;; -----------------------------------------------------------------------------
;; Lifecycle Fan-Out (sl-21z)
;;
;; ONE call per lifecycle moment, TWO sinks:
;;   * REPORT plane  — the reporter list, invoked synchronously and in order on
;;     the coordinator thread (invariant 1), in plan order (invariant 2).
;;   * OBSERVE plane — the async bus, fire-and-forget, for observers.
;;
;; `:scenario-complete` publishes nothing: the bus already saw that moment as
;; `:scenario/finished` at ACTUAL completion time, via the exec callback wired
;; in `execute-and-report-stage`. Reporters get determinism, observers get truth.
;; -----------------------------------------------------------------------------

(def ^:private reporter-dispatch
  {:run-start         reporter/on-run-start
   :scenario-complete reporter/on-scenario-complete
   :diagnostics       reporter/on-diagnostics
   :run-end           reporter/on-run-end})

(defn- resolve-junit-path
  "Resolve the JUnit-XML output path for this run: the --junit-xml flag wins
   (anchored to invocation-root), else the config mirror [:runner :report
   :junit-xml] (already anchored to config-root by resolve-config-declared-paths).
   Returns an absolute path string, or nil when JUnit output is off."
  [opts config]
  (or (some->> (:junit-xml opts)
               (project-context/resolve-cli-path (:project-context opts)))
      (config/junit-xml-path config)))

(defn- resolve-html-path
  "Resolve the HTML report output path for this run: the --html flag wins
   (anchored to invocation-root), else the config mirror [:runner :report
   :html] (already anchored to config-root by resolve-config-declared-paths).
   Returns an absolute path string, or nil when HTML output is off."
  [opts config]
  (or (some->> (:html opts)
               (project-context/resolve-cli-path (:project-context opts)))
      (config/html-path config)))

(defn- resolve-max-parallel
  "Resolve the scenario parallelism bound (sl-q9wp): the --max-parallel flag
   wins, else the config mirror [:runner :max-parallel], default 1
   (sequential)."
  [opts config]
  (or (:max-parallel opts)
      (config/max-parallel config)))

(defn- max-parallel-error
  "nil when the :max-parallel execute! opt is valid (absent or a positive
   integer), else a structured error map (the tag-filter rules-error
   pattern). CLI input is already validated by tools.cli; this is the strict
   programmatic boundary."
  [opts]
  (let [mp (:max-parallel opts)]
    (when (and (some? mp) (not (pos-int? mp)))
      {:type :max-parallel/invalid
       :message (str "Invalid :max-parallel " (pr-str mp)
                     " — must be a positive integer")})))

(defn- make-reporters
  "Build the reporter list for this run. Console (stderr) and EDN (stdout) are
   mutually exclusive; the JUnit (sl-40to) and HTML (sl-muq9) reporters are
   ADDITIVE — they ride alongside whichever base reporter is active, writing
   their files at run-end."
  [opts report-opts config]
  (let [base (if (:edn opts)
               [(report-edn/make-reporter)]
               [(console/make-reporter report-opts)])
        junit-path (resolve-junit-path opts config)
        html-path (resolve-html-path opts config)]
    (cond-> base
      junit-path (conj (report-junit/make-reporter {:path junit-path}))
      html-path (conj (report-html/make-reporter {:path html-path})))))

(defn- run-start-timestamp
  "ISO-8601 UTC timestamp with NO offset (e.g. 2026-07-09T03:00:00), truncated
   to seconds — the shape JUnit's testsuite@timestamp wants for XSD validity."
  []
  (-> (java.time.Instant/now)
      (.truncatedTo java.time.temporal.ChronoUnit/SECONDS)
      (java.time.LocalDateTime/ofInstant java.time.ZoneOffset/UTC)
      (.format java.time.format.DateTimeFormatter/ISO_LOCAL_DATE_TIME)))

(defn- run-start-ctx
  "Enrich the run-start payload for reporters. Console/EDN ignore run-start;
   the JUnit reporter (sl-40to) reads :version (properties), :mode (properties),
   :started-at (testsuite@timestamp) and :project-name (testsuites@name); the
   HTML reporter (sl-muq9) embeds the whole ctx in its data island. All
   values are EDN-native (invariant 3).

   `selected-count`/`filtered-count` carry the tag-filter selection story
   (sl-muq9 DP1): :selection is present only when a filter was active
   (non-nil filtered-count, the tag-filter-stage convention), so ctx shape
   without a filter is unchanged."
  [run-id opts config selected-count filtered-count]
  (let [project-root (get-in opts [:project-context :project-root])]
    (cond-> {:run-id run-id
             :version (version/version)
             ;; Presence of :svo in loaded config == Shifted mode (sl-ieie).
             :mode (if (:svo config) :shifted :vanilla)
             :started-at (run-start-timestamp)
             :project-root project-root
             :project-name (or (some-> project-root fs/file-name) "shiftlefter")
             ;; D2: the JUnit reporter mirrors exit code — pending is <skipped> when
             ;; allowed (exit 0), <failure> when strict (exit 1).
             :allow-pending? (config/allow-pending? config)}
      filtered-count
      (assoc :selection {:selected selected-count
                         :filtered-out filtered-count
                         :filter (:tag-filter opts)}))))

(defn- emit-lifecycle!
  "Fan one lifecycle `moment` out to both planes.

   A reporter that throws propagates (invariant 4: loud failure) — exit codes
   are already computed before any reporting, so they cannot be changed by it.
   `bus-event` nil => report plane only."
  [{:keys [reporters bus run-id]} moment payload {:keys [bus-event bus-payload]}]
  (let [dispatch (get reporter-dispatch moment)]
    (doseq [r reporters]
      (dispatch r payload)))
  (when bus-event
    (events/publish! bus (events/make-event bus-event run-id bus-payload)))
  nil)

;; -----------------------------------------------------------------------------
;; Pipeline Stages
;;
;; Each stage returns [:continue data] or [:exit result-map].
;; Stages are chained in execute! via early-exit `or` pattern.
;; -----------------------------------------------------------------------------

(defn- report-planning-error!
  "Report a planning error (exit 2) to the appropriate output."
  [run-id opts error-info]
  (let [exit-code 2]
    (when (:edn opts)
      (report-edn/prn-summary
       (report-edn/build-summary run-id exit-code nil error-info)))
    (when (and (not (:edn opts)) (:stderr-msg error-info))
      (binding [*out* *err*]
        ((:stderr-msg error-info))))
    {:exit-code exit-code
     :run-id run-id
     :status :planning-failed}))

(defn- print-config-lint-notices!
  "Config-lint warnings (sl-hlkz) — the i608 notice pattern: one stderr line
   per warning, suppressed in EDN mode (the machine contract stays
   byte-stable; `orient --edn` carries the same lints as :warn diagnostics,
   and sl-lnj1 will add them to the runner's EDN envelope). NOT suppressed
   in dry-run: unlike the tag-filter notice, nothing else carries this
   signal. Warnings only — never affects the exit code."
  [opts config-path lints]
  (when (and (seq lints) (not (:edn opts)))
    (binding [*out* *err*]
      (doseq [{:keys [message]} lints]
        (println (str "Config warning: " message
                      (when config-path (str " [" config-path "]"))))))))

(defn- load-config-stage
  "Stage 1: Load configuration.
   Returns [:continue {:config ... :step-paths ... :allow-pending? ...}]
   or [:exit error-result]."
  [run-id opts]
  (let [project-context (:project-context opts)
        config-result (config/load-config-safe {:project-context project-context})]
    (if (= :error (:status config-result))
      [:exit (report-planning-error! run-id opts
               {:error {:type :config/error :message (:message config-result)}
                :stderr-msg #(println "Config error:" (:message config-result))})]
      (let [config (resolve-config-declared-paths project-context (:config config-result))]
        (print-config-lint-notices! opts (:config-path project-context)
                                    (config/lint-config config))
        [:continue {:config config
                    :step-paths (or (some->> (:step-paths opts)
                                             (project-context/resolve-cli-paths project-context))
                                    (config/get-step-paths config))
                    :allow-pending? (config/allow-pending? config)}]))))

(defn- load-intents-stage
  "Stage 1b: Load the intents glossary into the global intent-state from config.

   Reads `:glossaries {:intents <path>}`, resolves it relative to the config
   file's directory (the same base-dir rule setup.clj uses), and loads it via
   `intent-state/reload-intents!` so the browser step path's `resolve-target`
   sees this project's intents — not a stale hardcoded `glossary/intents`.

   Absent `:intents` → clear state (a project may legitimately have no intents).
   A malformed/invalid intents glossary is a planning failure (exit 2),
   consistent with the other config/setup stages.

   Returns [:continue nil] or [:exit error-result]."
  [run-id opts config]
  (let [intents-path (:intents (config/get-glossary-config config))]
    (if-not intents-path
      (do (intent-state/clear-intents!) [:continue nil])
      (let [project-context (or (:project-context opts)
                                (project-context/resolve
                                 (cond-> {}
                                   (:config-path opts) (assoc :config-path (:config-path opts)))))
            dir (if (fs/absolute? intents-path)
                  intents-path
                  (project-context/resolve-config-path project-context intents-path))]
        (try
          (intent-state/reload-intents! dir)
          [:continue nil]
          (catch clojure.lang.ExceptionInfo e
            [:exit (report-planning-error! run-id opts
                     {:error {:type :glossary/intents-load-failed
                              :message (str "Failed to load intents from " dir)
                              :errors (:errors (ex-data e))}
                      :stderr-msg #(do (println "Intents load error:" (ex-message e))
                                       (doseq [err (:errors (ex-data e))]
                                         (println " " (:message err))))})]))))))

(defn- discover-features-stage
  "Stage 2: Discover feature files.
   Returns [:continue {:feature-paths [...]}] or [:exit error-result]."
  [run-id opts]
  (let [project-context (:project-context opts)
        paths (project-context/resolve-cli-paths project-context (:paths opts))
        discover-result (discover/discover-feature-files-or-error paths)]
    (if (= :error (:status discover-result))
      [:exit (report-planning-error! run-id opts
               {:error {:type (:type discover-result) :message (:message discover-result)}
                :stderr-msg #(println "Discovery error:" (:message discover-result))})]
      [:continue {:feature-paths (:files discover-result)}])))

(defn- parse-features-stage
  "Stage 3-4: Parse all feature files.
   Returns [:continue {:all-pickles [...] :features [...]}] or [:exit error-result]."
  [run-id opts feature-paths]
  (let [parse-result (parse-all-features feature-paths)]
    (if (= :error (:status parse-result))
      [:exit (merge
              (report-planning-error! run-id opts
                {:error {:type :parse/failed :errors (:errors parse-result)}
                 :stderr-msg #(do (println "Parse errors:")
                                  (doseq [err (:errors parse-result)]
                                    (println " " (:message err))))})
              {:errors (:errors parse-result)})]
      [:continue {:all-pickles (:all-pickles parse-result)
                  :features (:features parse-result)}])))

(defn- print-tag-filter-notice!
  "One stderr line making the active filter's effect visible (incl. the
   '0 of N selected' case). Suppressed in EDN mode; suppressed in dry-run
   mode because the dry-run line carries the filtered count itself."
  [opts selected-count filtered-count]
  (when-not (or (:edn opts) (:dry-run opts))
    (binding [*out* *err*]
      (println (str "Tag filter: " selected-count " of "
                    (+ selected-count filtered-count)
                    " scenario(s) selected (" filtered-count " filtered out)")))))

(defn- print-auto-serial-notice!
  "One notice line when the auto-serial safety gates fire under parallel
   execution (sl-q9wp DP1) — the i608 notice pattern: stderr, suppressed in
   EDN mode and dry-run. Only the AUTO gates are reported (@serial is
   deliberate user intent); only under an effective :max-parallel > 1 (at 1
   the facet is inert and acceptance 1 demands byte-identical output)."
  [opts plans]
  (when-not (or (:edn opts) (:dry-run opts))
    (let [counts (schedule/auto-serial-counts plans)
          costume (get counts :costume 0)
          shared-impl (get counts :shared-impl 0)
          hook (get counts :hook 0)
          total (+ costume shared-impl hook)]
      (when (pos? total)
        (binding [*out* *err*]
          (println (str total " scenario(s) auto-serialized: "
                        costume " costume, " shared-impl " shared-impl, "
                        hook " hook")))))))

(defn- tag-filter-stage
  "Stage 4b (sl-i608): planning-time tag filtering through the disposition
   seam. Filtered-out pickles never reach compile-suite-stage — never bound,
   never counted. nil :tag-filter => pass-through identical to today.
   :filtered-count is nil when no filter is active (so dry-run output can
   distinguish 'filter selected N' from 'project has N'), else the count.
   Returns [:continue {:all-pickles [...] :filtered-count (nilable int)}]
   or [:exit error-result]."
  [run-id opts all-pickles]
  (let [rules (:tag-filter opts)
        err (when rules (tagd/rules-error rules))]
    (cond
      (nil? rules) [:continue {:all-pickles all-pickles :filtered-count nil}]
      err [:exit (report-planning-error! run-id opts
                   {:error err
                    :stderr-msg #(println "Tag filter error:" (:message err))})]
      :else
      (let [{:keys [selected filtered-out]} (tagd/apply-dispositions rules all-pickles)]
        (print-tag-filter-notice! opts (count selected) (count filtered-out))
        [:continue {:all-pickles selected :filtered-count (count filtered-out)}]))))

(defn- load-steps-stage
  "Stage 5: Load step definitions + built-ins.
   Returns [:continue nil] or [:exit error-result]."
  [run-id opts step-paths]
  (let [load-result (step-loader/load-step-paths! step-paths)]
    (if (= :error (:status load-result))
      [:exit (merge
              (report-planning-error! run-id opts
                {:error {:type :step-load/failed :errors (:errors load-result)}
                 :stderr-msg #(do (println "Step load errors:")
                                  (doseq [err (:errors load-result)]
                                    (println " " (:path err) "-" (-> err :error :message))))})
              {:errors (:errors load-result)})]
      (do
        ;; Load built-in step definitions AFTER step-loader clears registry
        (load-built-in-steps!)
        [:continue nil]))))

(defn- compile-suite-stage
  "Stage 6: Compile suite (bind all pickles to stepdefs).
   Returns [:continue {:plans [...] :diagnostics {...}}] or [:exit error-result].

   `opts` may carry `:adapter-registry`; it's forwarded to the binder so
   capability gating (sl-ewn) honors a setup.clj-supplied custom registry.
   `opts` may carry `:hooks-registry` ({:registry [...] :path ...} from
   load-hooks-stage, sl-esq); hook applicability resolves here, riding the
   plan as the additive :plan/hooks key."
  [run-id opts config all-pickles report-opts]
  (let [stepdefs (registry/all-stepdefs)
        compile-opts (when-let [r (:adapter-registry opts)]
                       {:adapter-registry r})
        {:keys [plans runnable? diagnostics]}
        (compile/compile-suite config all-pickles stepdefs compile-opts)
        ;; sl-lnj1: config-lint warnings join the diagnostics map here — the
        ;; one point both pipelines share — so every downstream output (EDN
        ;; summary 0/1/2, dry-run, reporter on-diagnostics channel) carries
        ;; them. Re-linting is a set-membership check over ~7 keys; cheaper
        ;; than threading state from load-config-stage. Scrubbed AT ATTACH:
        ;; a lint's :key holds the offending keyword itself, which can be
        ;; non-round-tripping (sl-27uh class), and the exit-2 path prints
        ;; diagnostics without the reporter-envelope scrub. Absent key when
        ;; clean — all existing output stays byte-identical.
        diagnostics (let [lints (config/lint-config config)]
                      (cond-> diagnostics
                        (seq lints) (assoc :config-lints (reporter/scrub lints))))]
    (if-not runnable?
      (let [exit-code 2]
        (when-not (:edn opts)
          (console/print-diagnostics! diagnostics report-opts))
        (when (:edn opts)
          (report-edn/prn-summary
           (report-edn/build-summary run-id exit-code nil {:diagnostics diagnostics})))
        [:exit {:exit-code exit-code
                :run-id run-id
                :status :planning-failed
                :diagnostics diagnostics}])
      ;; sl-esq: resolve @hook= names against the loaded registry; an
      ;; unknown name is a planning error naming the tag and its file:line.
      ;; Runs even with no hooks.clj (nil registry) — a dangling @hook=
      ;; tag must fail loudly, not silently no-op. Hooks attach BEFORE
      ;; schedules: the :requires-serial gate reads :plan/hooks.
      (let [hooks-info (:hooks-registry opts)
            {plans' :ok hooks-err :error}
            (hooks/attach-hooks plans (:registry hooks-info) (:path hooks-info))]
        (if hooks-err
          ;; :diagnostics carries the error into the EDN summary's :planning
          ;; block (build-summary's exit-2 path renders diagnostics only).
          [:exit (report-planning-error! run-id opts
                   {:error hooks-err
                    :diagnostics {:errors [hooks-err]}
                    :stderr-msg #(println "Hooks error:" (:message hooks-err))})]
          ;; sl-yh7: the data-plane static check runs HERE — after hook
          ;; attachment, because hook :provides declarations (riding
          ;; :plan/hooks) count as binding producers. Its issues join
          ;; :svo-issues (same shape, severity, reporting); any :error
          ;; (consumed-without-producer, invalid capture pattern) is a
          ;; planning failure, exit 2 like every other blocking issue.
          (let [bindings-issues (bindings-lint/check-plans plans')
                diagnostics (cond-> diagnostics
                              (seq bindings-issues)
                              (-> (update :svo-issues (fnil into []) bindings-issues)
                                  (update-in [:counts :svo-issue-count]
                                             (fnil + 0) (count bindings-issues))
                                  (update-in [:counts :total-issues]
                                             (fnil + 0) (count bindings-issues))))]
            (if (bindings-lint/blocking? bindings-issues)
              (let [exit-code 2]
                (when-not (:edn opts)
                  (console/print-diagnostics! diagnostics report-opts))
                (when (:edn opts)
                  (report-edn/prn-summary
                   (report-edn/build-summary run-id exit-code nil
                                             {:diagnostics diagnostics})))
                [:exit {:exit-code exit-code
                        :run-id run-id
                        :status :planning-failed
                        :diagnostics diagnostics}])
              ;; sl-q9wp: the scheduling facet rides the compiled plan
              ;; (additive :plan/schedule key). Inert at :max-parallel 1 —
              ;; the sequential execution path never reads it.
              [:continue {:plans (schedule/attach-schedules plans' (:interfaces config))
                          :diagnostics diagnostics}])))))))

(declare dry-run-hooks-entries)

(defn- dry-run-edn-summary
  "Build the EDN summary for a dry-run success, including warn-level SVO
   diagnostics that would otherwise be dropped (sl-qk8l). `group-label`
   is non-nil on the setup-group path only. `filtered-count` is non-nil only
   when a tag filter is active (sl-i608): the additive :filtered-out key makes
   the dry-run preview distinguish 'filter selected N' from 'project has N';
   without a filter the summary is byte-identical to before."
  [run-id plans diagnostics group-label filtered-count]
  (cond-> {:run/id run-id
           :run/exit-code 0
           :run/status :dry-run
           :counts {:scenarios (count plans)
                    :steps (reduce + (map #(count (:plan/steps %)) plans))}}
    group-label (assoc :group group-label)
    filtered-count (assoc :filtered-out filtered-count)
    ;; sl-esq AC9: per-scenario hook firing lists — additive, absent for
    ;; hook-less suites so the summary stays byte-identical.
    (dry-run-hooks-entries plans)
    (assoc :hooks (dry-run-hooks-entries plans))
    (report-edn/execution-diagnostics diagnostics)
    (assoc :diagnostics (report-edn/execution-diagnostics diagnostics))))

(defn- dry-run-hooks-entries
  "Per-scenario hook firing lists for the dry-run preview (sl-esq AC9):
   [{:scenario/name .. :hooks [name ..]} ..] in plan order, EXECUTION order
   within each scenario (globals outermost, then tag order — resolved at
   planning, riding :plan/hooks). nil when no selected plan carries hooks,
   keeping hook-less dry-run output byte-unchanged."
  [plans]
  (not-empty
   (into []
         (keep (fn [plan]
                 (when-let [hooks (seq (:plan/hooks plan))]
                   {:scenario/name (get-in plan [:plan/pickle :pickle/name])
                    :hooks (mapv :name hooks)})))
         plans)))

(defn- print-dry-run-hooks!
  "stderr block under the dry-run summary line: which hooks fire for each
   scenario, in execution order — the legibility requirement's cheap
   mechanical query. Prints nothing when `entries` is nil."
  [entries]
  (when entries
    (binding [*out* *err*]
      (println "hooks:")
      (doseq [{nm :scenario/name hooks :hooks} entries]
        (println (str "  " nm ": " (str/join ", " hooks)))))))

(defn- dry-run-console-line
  "The dry-run success line. With a tag filter active (non-nil
   `filtered-count`, sl-i608) it carries the filtered count so the preview
   shows the selection; without one it is byte-identical to before."
  [plan-count filtered-count]
  (str plan-count " scenario(s) bound successfully (dry run"
       (when filtered-count (str "; " filtered-count " filtered out by tags"))
       ")"))

(defn- make-scenario-release
  "The SINGLE release point through which per-scenario REPORT-plane dispatch
   flows (sl-dgk). Returns a 1-arg fn taking an already-built scenario envelope.

   Under serial execution scenario completions arrive in plan order on the
   coordinator thread, so this is a degenerate pass-through — it dispatches
   immediately. sl-q9wp inserts a plan-order buffer BEHIND this seam: it feeds
   this fn, in plan order, from the coordinator thread as out-of-order
   pool-thread completions arrive. It changes who FEEDS the release point, never
   what the release point CALLS. Invariants 1 (coordinator thread) and 2 (plan
   order) are properties of the feed; keeping dispatch behind one function is
   what lets q9wp add the buffer without touching reporters."
  [ctx]
  (fn release-scenario! [scenario-envelope]
    (emit-lifecycle! ctx :scenario-complete scenario-envelope nil)))

(defn make-ordered-release
  "The plan-order release buffer (sl-q9wp R1) that feeds `make-scenario-release`
   under :max-parallel > 1. Takes the plans (defining plan order) and the
   release fn; returns a 1-arg fn accepting scenario envelopes in ANY order
   and calling `release!` in plan order, buffering out-of-order arrivals.

   - Envelopes are keyed by :scenario/id (the pickle UUID).
   - The buffer is UNBOUNDED BY DESIGN (Chair memory ruling): envelopes are
     scrubbed pure data without stack traces (~2-10KB typical), and
     execute-suite already holds all raw results in heap — worst case is ~2x
     tens-of-MB at 10k scenarios. It must tolerate deep holds: a plan-early
     @serial scenario completes in phase 2, so everything after it buffers
     until then.
   - Coordinator-thread-only by contract (invariant 1): callers feed it from
     the same thread that owns the run. The atom is belt-and-braces, not a
     concurrency mechanism.
   - An envelope without a :scenario/id (hand-built plans in tests; never the
     real pipeline) is released immediately — it cannot be ordered.
   - Callers wire this ONLY when parallel execution is active: at
     :max-parallel 1 completions already arrive in plan order and the direct
     release keeps that path byte-identical to before."
  [plans release!]
  (let [index-of (into {}
                       (map-indexed
                        (fn [i plan]
                          [(get-in plan [:plan/pickle :pickle/id]) i]))
                       plans)
        state (atom {:next 0 :pending {}})]
    (fn buffered-release! [env]
      (if-let [idx (index-of (:scenario/id env))]
        (do
          (swap! state assoc-in [:pending idx] env)
          (loop []
            (let [{:keys [next pending]} @state]
              (when-let [ready (get pending next)]
                (swap! state (fn [s]
                               (-> s
                                   (update :pending dissoc next)
                                   (update :next inc))))
                (release! ready)
                (recur)))))
        (release! env)))))

(defn- on-scenario-complete-callback
  "The exec layer's :on-scenario-complete. Fires synchronously as each scenario
   completes, always on the coordinator thread — in plan order under serial
   execution, in ACTUAL completion order under :max-parallel > 1 (the bead's
   load-bearing constraint: console output must NOT go through the async bus).

   Builds the envelope ONCE, then feeds both planes: the bus at ACTUAL
   completion order (observe), and the release point in PLAN order (report —
   `release!` is the ordered buffer from `make-ordered-release` when parallel
   execution is active, the direct release otherwise)."
  [bus run-id release!]
  (fn [raw-scenario-result]
    (let [env (reporter/scenario-envelope raw-scenario-result)
          extras (when-let [sid (:scenario/id env)]
                   {:scenario/id sid})]
      (events/publish! bus (events/make-event :scenario/finished run-id
                                              {:scenario env} extras))
      (release! env))))

(defn- execute-and-report-stage
  "Stage 8-10: Execute scenarios, report results, return exit code.

   Per-scenario console output is emitted LIVE as each scenario completes
   (sl-dgk), so a minutes-to-hours e2e suite shows progress instead of a flood
   at the end. The aggregate passes (failures, summary) stay at run end.

   One run-scope. Under setup.clj orchestration this is called once per group,
   so each group is its own run-scope (Tower ruling R1). `filtered-count` is
   nil unless a tag filter was active (sl-muq9 DP1 selection story)."
  [run-id opts bus config features all-pickles filtered-count plans diagnostics allow-pending? report-opts]
  (let [ctx {:reporters (make-reporters opts report-opts config) :bus bus :run-id run-id}
        max-parallel (resolve-max-parallel opts config)
        parallel? (> max-parallel 1)
        ;; sl-q9wp R1: under parallel execution completions arrive out of
        ;; plan order, so the release point is fed through the plan-order
        ;; buffer. At :max-parallel 1 the direct release keeps this path
        ;; byte-identical to before.
        release! (let [direct (make-scenario-release ctx)]
                   (if parallel?
                     (make-ordered-release plans direct)
                     direct))]
    (when parallel?
      (print-auto-serial-notice! opts plans))
    (emit-lifecycle! ctx :run-start (run-start-ctx run-id opts config
                                                   (count all-pickles) filtered-count)
                     {:bus-event :test-run/started
                      :bus-payload {:feature-count (count features)
                                    :scenario-count (count all-pickles)}})
    (let [exec-opts (cond-> {:bus bus :run-id run-id :interfaces (:interfaces config)
                             ;; sl-aa5: scoped-eager provisioning is the default;
                             ;; opt-out via {:runner {:provisioning :lazy}}.
                             :provisioning (config/provisioning-mode config)
                             ;; sl-q9wp: scenario parallelism bound (1 = today's
                             ;; sequential path).
                             :max-parallel max-parallel
                             ;; sl-dgk: report + observe planes fed per-completion.
                             :on-scenario-complete (on-scenario-complete-callback bus run-id release!)}
                      (:adapter-registry opts) (assoc :adapter-registry (:adapter-registry opts)))
          ;; Reporters have already seen every scenario (live, via the callback)
          ;; by the time this returns.
          exec-result (exec/execute-suite plans exec-opts)
          ;; Invariant 4: the exit code is a pure function of the execution
          ;; result; reporting (which already ran) cannot change it.
          exit-code (compute-exit-code exec-result allow-pending?)]
      ;; Warn-level SVO issues didn't block execution but must still reach a
      ;; human (sl-qk8l/sl-6h4r) — EDN output already carries them.
      (emit-lifecycle! ctx :diagnostics (reporter/diagnostics-envelope diagnostics) nil)
      (emit-lifecycle! ctx :run-end
                       {:run-id run-id
                        :exit-code exit-code
                        :counts (:counts exec-result)
                        :status (if (zero? exit-code) :passed :failed)}
                       {:bus-event :test-run/finished
                        :bus-payload {:exit-code exit-code :counts (:counts exec-result)}})
      (events/bus-close! bus)
      {:exit-code exit-code
       :run-id run-id
       :status (if (zero? exit-code) :passed :failed)
       :counts (:counts exec-result)
       ;; The RAW exec-result, deliberately: envelopes are a projection for the
       ;; report/observe planes only; programmatic callers keep full fidelity.
       :result exec-result})))

;; -----------------------------------------------------------------------------
;; Setup-Aware Execution (sl-dbu)
;;
;; When a setup.clj sits next to the active shiftlefter.edn, it owns the
;; feature plan: each group declares :features (paths/globs) and a :start
;; lifecycle that may return a custom :adapter-registry. The runner threads
;; that registry through this group's compile + execute, then calls :stop.
;;
;; See runner/setup.clj for the contract and pure-mode invariant.
;; -----------------------------------------------------------------------------

(defn- load-setup-stage
  "Locate and load setup.clj sibling-of-config. Returns one of:
   - [:none nil]                    — no setup.clj present, classic pipeline
   - [:loaded {:setups [...] :path}] — loaded successfully
   - [:exit error-result]           — load failed, treat as planning error"
  [run-id opts config]
  (if-let [setup-path (setup/find-setup-file (:project-context opts))]
    (let [{:keys [ok error]} (setup/load-setup setup-path config)]
      (if error
        [:exit (report-planning-error! run-id opts
                 {:error error
                  :stderr-msg #(println "Setup error:" (:message error))})]
        [:loaded {:setups ok :path setup-path}]))
    [:none nil]))

(defn- load-hooks-stage
  "Locate and load hooks.clj sibling-of-config (sl-esq). Returns one of:
   - [:continue nil]                       — no hooks.clj present, no hooks
   - [:continue {:registry [...] :path}]   — loaded successfully
   - [:exit error-result]                  — malformed/duplicate-name, planning error"
  [run-id opts config]
  (if-let [hooks-path (hooks/find-hooks-file (:project-context opts))]
    (let [{:keys [ok error path]} (hooks/load-hooks hooks-path config)]
      (if error
        [:exit (report-planning-error! run-id opts
                 {:error error
                  :diagnostics {:errors [error]}
                  :stderr-msg #(println "Hooks error:" (:message error))})]
        [:continue {:registry ok :path path}]))
    [:continue nil]))

(defn- compute-suite-exit-code
  "Aggregate per-group exit codes into a suite exit code.
   Worst code wins: planning-error (2) > failed (1) > passed (0)."
  [exit-codes]
  (cond
    (some #(= 2 %) exit-codes) 2
    (some #(not (zero? %)) exit-codes) 1
    :else 0))

(defn- run-group!
  "Run one orchestrated group end-to-end.

   Lifecycle: call (:start config) → parse this group's features →
   compile-suite (with the group's adapter-registry) → execute → :stop
   (in finally; runs even on failure). Under :dry-run, :start and :stop are
   skipped ENTIRELY (sl-ev0b) — the preview binds against the default
   adapter registry and no user lifecycle code runs.

   Returns a per-group result map shaped like execute-and-report-stage's,
   plus :group-label and :status."
  [run-id opts config group bus report-opts allow-pending?]
  (let [label    (or (:label group) "<unlabeled>")
        features (setup/resolve-group-features group)
        ;; :start may throw or return malformed shape — both treated as
        ;; planning errors local to this group; other groups still run.
        ;; sl-ev0b: dry-run is a pure plan preview — no user lifecycle code
        ;; runs. nil start-result → no adapter registry threaded, and no
        ;; :stop in the finally (there is nothing to stop).
        start-result (when-not (:dry-run opts)
                       (try
                         ((:start group) config)
                         (catch Throwable t
                           {::start-throw t})))]
    (cond
      (::start-throw start-result)
      (let [t (::start-throw start-result)]
        (when-not (:edn opts)
          (binding [*out* *err*]
            (println (str "Setup group " (pr-str label) " :start threw: "
                          (ex-message t)))))
        {:exit-code 2 :run-id run-id :status :planning-failed
         :group-label label
         :error {:type :setup/start-threw :message (ex-message t)}})

      (and start-result (not (s/valid? :shiftlefter.runner.setup/start-result start-result)))
      (do (when-not (:edn opts)
            (binding [*out* *err*]
              (println (str "Setup group " (pr-str label)
                            " :start returned an invalid shape; expected {:adapter-registry? :stop?}"))))
          {:exit-code 2 :run-id run-id :status :planning-failed
           :group-label label
           :error {:type :setup/invalid-start-result}})

      :else
      (let [{:keys [adapter-registry stop]} start-result
            inner-opts (cond-> opts
                         adapter-registry (assoc :adapter-registry adapter-registry))]
        (try
          (let [[s-parse d-parse] (parse-features-stage run-id inner-opts features)]
            (if (= :exit s-parse)
              (assoc d-parse :group-label label)
              (let [{:keys [all-pickles features]} d-parse
                    [s-filter d-filter] (tag-filter-stage run-id inner-opts all-pickles)]
                (if (= :exit s-filter)
                  (assoc d-filter :group-label label)
                  (let [{:keys [all-pickles filtered-count]} d-filter
                        [s-compile d-compile]
                        (compile-suite-stage run-id inner-opts config all-pickles report-opts)]
                    (if (= :exit s-compile)
                      (assoc d-compile :group-label label)
                      (let [{:keys [plans diagnostics]} d-compile]
                        (if (:dry-run opts)
                          (let [exit-code 0]
                            (when-not (:edn opts)
                              (console/print-warnings! diagnostics report-opts)
                              (binding [*out* *err*]
                                (println (str "[" label "] "
                                              (dry-run-console-line (count plans) filtered-count))))
                              (print-dry-run-hooks! (dry-run-hooks-entries plans)))
                            (when (:edn opts)
                              (report-edn/prn-summary
                               (dry-run-edn-summary run-id plans diagnostics label filtered-count)))
                            {:exit-code exit-code :run-id run-id :status :dry-run
                             :group-label label :plans plans})
                          (-> (execute-and-report-stage
                               run-id inner-opts bus config features
                               all-pickles filtered-count plans diagnostics
                               allow-pending? report-opts)
                              (assoc :group-label label))))))))))
          (finally
            (when stop
              (try (stop)
                   (catch Throwable t
                     ;; :stop failures are operational; surface via the
                     ;; logging facade rather than the user-facing
                     ;; stderr stream so they don't interleave with EDN
                     ;; output and can be filtered by log config.
                     (log/warnf t "Setup group %s :stop threw (ignored): %s"
                                (pr-str label) (ex-message t)))))))))))

(defn- run-with-setups!
  "Drive the suite through all setup groups in declared order.
   Aggregates per-group exit codes into a single suite result.

   `setup-base-dir`: directory containing setup.clj — relative paths in
   each group's :features resolve against it."
  [run-id opts config setups setup-base-dir bus report-opts allow-pending?]
  (let [;; Validate CLI paths against the declared union before any group runs.
        cli-result (setup/validate-cli-paths (:paths opts) setups setup-base-dir)]
    (if-let [err (:error cli-result)]
      (report-planning-error! run-id opts
        {:error err
         :stderr-msg #(println "Setup error:" (:message err))})
      (let [filtered (setup/filter-setups-by-cli-paths setups (:ok cli-result) setup-base-dir)
            results  (mapv #(run-group! run-id opts config % bus report-opts allow-pending?)
                           filtered)
            exit-code (compute-suite-exit-code (map :exit-code results))]
        {:exit-code exit-code
         :run-id    run-id
         :status    (case exit-code
                      0 :passed
                      2 :planning-failed
                      :failed)
         :groups    results}))))

;; -----------------------------------------------------------------------------
;; Main Pipeline
;; -----------------------------------------------------------------------------

(defn execute!
  "Execute the full runner pipeline.

   Options:
   - :paths - feature file paths/dirs/globs (required)
   - :config-path - explicit config file path
   - :step-paths - override step definition paths
   - :tag-filter - {:include #{tag} :exclude #{tag}} planning-time tag
     filter (sl-i608); tags are normalized \"@name\" strings
   - :max-parallel - pos-int scenario parallelism bound (sl-q9wp); wins over
     the config mirror [:runner :max-parallel]; absent/1 = sequential
   - :junit-xml - JUnit-XML output path (sl-40to); wins over the config
     mirror [:runner :report :junit-xml]
   - :html - HTML report output path (sl-muq9); wins over the config
     mirror [:runner :report :html]
   - :dry-run - stop after binding, don't execute
   - :edn - output EDN summary to stdout
   - :verbose - verbose console output
   - :no-color - disable ANSI colors

   Returns:
   {:exit-code 0|1|2|3
    :run-id \"uuid\"
    :status :passed|:failed|:planning-failed|:crashed
    :counts {...}
    :result <exec-result>
    :diagnostics <bind-diagnostics>}"
  [opts]
  (let [project-context (or (:project-context opts)
                            (project-context/resolve
                             (cond-> {}
                               (:config-path opts) (assoc :config-path (:config-path opts)))))
        opts (assoc opts :project-context project-context)
        run-id (str (java.util.UUID/randomUUID))
        bus (events/make-memory-bus)
        report-opts (select-keys opts [:verbose :no-color])]
    (binding [wardrobe/*project-context* project-context]
      (try
        (let [[s1 d1] (if-let [mp-err (max-parallel-error opts)]
                        ;; Strict execute!-boundary validation (sl-q9wp):
                        ;; malformed :max-parallel rides the planning-error
                        ;; path, like :tag-filter.
                        [:exit (report-planning-error! run-id opts
                                 {:error mp-err
                                  :stderr-msg #(println "Max-parallel error:"
                                                        (:message mp-err))})]
                        (load-config-stage run-id opts))]
          (if (= :exit s1) d1
            (let [{:keys [config step-paths allow-pending?]} d1
                  ;; Stage 1b: load this project's intents glossary into
                  ;; intent-state before any features run, so the browser step
                  ;; path resolves nested addresses against the right glossary.
                  [s-int d-int] (load-intents-stage run-id opts config)]
              (if (= :exit s-int) d-int
                (let [;; Setup-aware branch (sl-dbu): load setup.clj if present.
                      ;; If found, it owns the feature plan — bypass discovery and
                      ;; the single-suite pipeline; loop over groups instead.
                      [s-setup d-setup] (load-setup-stage run-id opts config)
                      ;; Hooks registry (sl-esq): loaded once, threaded via opts
                      ;; into BOTH pipelines' compile-suite-stage.
                      [s-hooks d-hooks] (when-not (= :exit s-setup)
                                          (load-hooks-stage run-id opts config))
                      opts (cond-> opts d-hooks (assoc :hooks-registry d-hooks))]
                  (cond
                    (= :exit s-setup) d-setup

                    (= :exit s-hooks) d-hooks

                    (= :loaded s-setup)
                    (let [[s4 d4] (load-steps-stage run-id opts step-paths)
                          setup-base-dir (str (fs/parent (:path d-setup)))]
                      (if (= :exit s4) d4
                        (run-with-setups! run-id opts config (:setups d-setup)
                                          setup-base-dir bus report-opts allow-pending?)))

                    :else
                    (let [[s2 d2] (discover-features-stage run-id opts)]
                      (if (= :exit s2)
                        d2
                        (let [{:keys [feature-paths]} d2
                              [s3 d3] (parse-features-stage run-id opts feature-paths)]
                          (if (= :exit s3)
                            d3
                            (let [{:keys [all-pickles features]} d3
                                  [sf df] (tag-filter-stage run-id opts all-pickles)]
                              (if (= :exit sf)
                                df
                                (let [{:keys [all-pickles filtered-count]} df
                                      [s4 d4] (load-steps-stage run-id opts step-paths)]
                                  (if (= :exit s4)
                                    d4
                                    (let [[s5 d5] (compile-suite-stage run-id opts config all-pickles report-opts)]
                                      (if (= :exit s5)
                                        d5
                                        (let [{:keys [plans diagnostics]} d5]
                                          (if (:dry-run opts)
                                            (let [exit-code 0]
                                              (when-not (:edn opts)
                                                (console/print-warnings! diagnostics report-opts)
                                                (binding [*out* *err*]
                                                  (println (dry-run-console-line (count plans) filtered-count)))
                                                (print-dry-run-hooks! (dry-run-hooks-entries plans)))
                                              (when (:edn opts)
                                                (report-edn/prn-summary
                                                 (dry-run-edn-summary run-id plans diagnostics nil filtered-count)))
                                              {:exit-code exit-code
                                               :run-id run-id
                                               :status :dry-run
                                               :plans plans})
                                            (execute-and-report-stage run-id opts bus config features
                                                                      all-pickles filtered-count plans diagnostics
                                                                      allow-pending? report-opts)))))))))))))))))))
        ;; Catch any uncaught exceptions → exit 3
        (catch Throwable t
          (let [exit-code 3
                error {:type :runner/crash
                       :message (ex-message t)
                       :exception-class (.getName (class t))}]
            (when (:edn opts)
              (report-edn/prn-summary
               (report-edn/build-summary run-id exit-code nil {:error error})))
            (when-not (:edn opts)
              (binding [*out* *err*]
                (println "Runner crash:" (ex-message t))
                (when (:verbose opts)
                  (.printStackTrace t *err*))))
            (events/bus-close! bus)
            {:exit-code exit-code
             :run-id run-id
             :status :crashed
             :error error}))))))
