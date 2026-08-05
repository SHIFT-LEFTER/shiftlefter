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
            [shiftlefter.adapters.registry :as adapters]
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
            [shiftlefter.runner.results :as results]
            [shiftlefter.runner.schedule :as schedule]
            [shiftlefter.runner.setup :as setup]
            [shiftlefter.runner.step-loader :as step-loader]
            [shiftlefter.runner.tag-disposition :as tagd]
            [shiftlefter.runner.teardown-marker :as marker]
            [shiftlefter.stepengine.compile :as compile]
            [shiftlefter.stepengine.exec :as exec]
            [shiftlefter.stepengine.exec.capture :as capture]
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
        ;; Attach each file's :path to its flattened errors (sl-0n6t) —
        ;; the aggregate loses file attribution otherwise.
        errors (mapcat (fn [r] (map #(assoc % :path (:path r)) (:errors r)))
                       (filter #(= :error (:status %)) results))
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

(defn- resolve-capture
  "Resolve the capture policy (sl-3q9r): the --capture flag wins AS A
   WHOLE VALUE, else the config mirror [:runner :capture]. Config-side
   entries with an invalid policy VALUE (e.g. a future :scenario-end read
   by this version) are split out to warn-and-inert — forward compat, the
   lint-config ruling; strictness lives at the CLI (tools.cli) and the
   programmatic boundary (capture-opt-error, exit 2).

   Returns {:policy {kind policy} :invalid {kind bad-value}}."
  [opts config]
  (let [raw (or (:capture opts) (config/capture-policy config))
        valid? #(contains? #{:never :on-failure :every-step} %)]
    {:policy (into {} (filter (comp valid? val)) raw)
     :invalid (into {} (remove (comp valid? val)) raw)}))

(defn- capture-warn-pass!
  "The capture warn channel (sl-3q9r, Chair-ratified): one stderr line per
   condition, deduped ONCE PER RUN per key via the :capture-warn-state
   atom (setup groups run this per group — the atom spans them), stderr
   suppressed under --edn (the warns ride :diagnostics :capture-lints
   instead, the config-lint pattern). Never an error, never exit-code.

   Checks, in order: invalid config policy values (warn + inert),
   capture-active-with-no-results-dir (warn + capture disabled), and
   configured active kinds contributed by ZERO adapters in the group's
   LIVE registry (post-:start, so custom adapters count — a typo'd kind
   must not be a silent no-op, the vacuity-witness class).

   Returns the NEWLY-warned lint maps for this group's diagnostics."
  [opts {:keys [policy invalid]} group-root]
  (let [state (or (:capture-warn-state opts) (atom #{}))
        edn? (:edn opts)
        active? (capture/capture-active? policy)
        registry (or (:adapter-registry opts) adapters/default-registry)
        warn! (fn [key msg lint]
                (when-not (contains? @state key)
                  (swap! state conj key)
                  (when-not edn?
                    (binding [*out* *err*] (println msg)))
                  lint))]
    (vec
     (concat
      (keep (fn [[kind bad]]
              (warn! [:invalid-value kind]
                     (str "Capture warning: policy for " kind " has invalid"
                          " value " (pr-str bad) " — ignoring the kind"
                          " (must be never|on-failure|every-step)")
                     {:type :capture/invalid-policy-value
                      :kind kind
                      :message (str "invalid capture policy value " (pr-str bad))}))
            invalid)
      (when (and active? (nil? group-root))
        (keep identity
              [(warn! [:no-results-dir]
                      (str "Capture warning: capture requested but this run"
                           " has no results directory (no config file) —"
                           " capture disabled")
                      {:type :capture/no-results-dir
                       :message "capture requested but no results directory"})]))
      (when (and active? group-root)
        (keep (fn [kind]
                (warn! [:unknown-kind kind]
                       (str "Capture warning: kind " kind " is not provided"
                            " by any registered adapter — it will never"
                            " capture anything")
                       {:type :capture/unknown-kind
                        :kind kind
                        :message (str "capture kind " kind
                                      " provided by no registered adapter")}))
              (capture/unsupported-kinds policy registry)))))))

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

(defn- capture-opt-error
  "nil when the :capture execute! opt is valid (absent, or a map of
   keyword kind -> #{:never :on-failure :every-step}), else a structured
   error map (sl-3q9r). CLI input is already validated by tools.cli; this
   is the strict programmatic boundary. Config-FILE values are deliberately
   NOT gated here — a bad config policy value warns and inerts at
   resolve-capture (forward compat, the lint-config ruling)."
  [opts]
  (let [c (:capture opts)]
    (when (and (some? c)
               (not (s/valid? :shiftlefter.runner.config/capture c)))
      {:type :capture/invalid
       :message (str "Invalid :capture " (pr-str c) " — "
                     (s/explain-str :shiftlefter.runner.config/capture c))})))

(defn- make-reporters
  "Build the reporter list for this run. Console (stderr) and EDN (stdout) are
   mutually exclusive; the JUnit (sl-40to) and HTML (sl-muq9) reporters are
   ADDITIVE — they ride alongside whichever base reporter is active, writing
   their files at run-end.

   `group-root` (sl-l9nj): this run-scope's output root inside the run dir
   (nil without one). When present the HTML report's PRIMARY lives there as
   report.html — beside attachments/, so the run dir is a self-contained
   zippable artifact — and the configured path becomes the end-of-run copy.
   HTML stays gated on a configured path (no default-on report, design §5
   fence)."
  [opts report-opts config group-root]
  (let [base (if (:edn opts)
               [(report-edn/make-reporter)]
               [(console/make-reporter report-opts)])
        junit-path (resolve-junit-path opts config)
        html-path (resolve-html-path opts config)]
    (cond-> base
      junit-path (conj (report-junit/make-reporter {:path junit-path}))
      html-path (conj (report-html/make-reporter
                       {:path html-path
                        :primary (when group-root
                                   (str (fs/path group-root "report.html")))})))))

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
  [run-id opts config selected-count filtered-count started-at]
  (let [project-root (get-in opts [:project-context :project-root])]
    (cond-> {:run-id run-id
             :version (version/version)
             ;; Presence of :svo in loaded config == Shifted mode (sl-ieie).
             :mode (if (:svo config) :shifted :vanilla)
             :started-at started-at
             :project-root project-root
             :project-name (or (some-> project-root fs/file-name) "shiftlefter")
             ;; D2: the JUnit reporter mirrors exit code — pending is <skipped> when
             ;; allowed (exit 0), <failure> when strict (exit 1).
             :allow-pending? (config/allow-pending? config)}
      filtered-count
      (assoc :selection {:selected selected-count
                         :filtered-out filtered-count
                         :filter (:tag-filter opts)}))))

(defn- write-planning-failure-html!
  "sl-6hsd: when an HTML report was requested, a planning failure (exit 2)
   still materializes it — zero scenarios, the diagnostics island (undefined
   steps with their grammar hints, ambiguous, arity, hooks/binding errors),
   :status :planning-failed. Console and EDN already tell the truth on this
   path; without this, report.html stays silently absent or — worse — a
   previous run's file sits there lying. Scope: the stage-6 exits (binding
   gate, hooks error, bindings-lint gate — Chair-ruled set, 2bcdeff);
   earlier planning exits (parse/config/adapter) stay console/EDN-only."
  [run-id opts config all-pickles diagnostics]
  (when-let [html-path (resolve-html-path opts config)]
    (report-html/write-planning-failure!
     html-path
     (run-start-ctx run-id opts config (count all-pickles) nil
                    (run-start-timestamp))
     diagnostics
     {:run-id run-id
      :exit-code 2
      :status :planning-failed
      :counts (:counts diagnostics)})))

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

(defn- adapter-availability-errors
  "Probe every configured interface's adapter for classpath availability
   (sl-gcdu). An adapter that is supported-but-not-bundled (registry declares
   `:requires-class`, e.g. :playwright) with its dependency absent is a
   dead-end config: it would load clean and only fail per-scenario at
   provisioning. Returns a vector of `:config/adapter-not-bundled` errors
   (empty when all adapters are available), sorted by interface name for
   stable output."
  [config]
  (->> (config/get-interfaces config)
       (sort-by (comp name key))
       (into []
             (keep (fn [[iface-name iface-cfg]]
                     (adapters/adapter-unavailable-error
                      iface-name (:adapter iface-cfg)))))))

(defn- load-config-stage
  "Stage 1: Load configuration.
   Returns [:continue {:config ... :step-paths ... :allow-pending? ...}]
   or [:exit error-result]."
  [run-id opts]
  (let [project-context (:project-context opts)
        config-result (config/load-config-safe {:project-context project-context})]
    (if (= :error (:status config-result))
      (let [err {:type :config/error :message (:message config-result)}]
        [:exit (report-planning-error! run-id opts
                 {:error err
                  :diagnostics {:errors [err]}
                  :stderr-msg #(println "Config error:" (:message config-result))})])
      (let [config (resolve-config-declared-paths project-context (:config config-result))
            adapter-errs (adapter-availability-errors config)]
        (print-config-lint-notices! opts (:config-path project-context)
                                    (config/lint-config config))
        (if (seq adapter-errs)
          ;; :diagnostics {:errors ...} is what makes the EDN summary carry
          ;; the error on exit 2 — build-summary ignores :error there.
          [:exit (report-planning-error! run-id opts
                   {:error (first adapter-errs)
                    :diagnostics {:errors adapter-errs}
                    :stderr-msg #(doseq [{:keys [message]} adapter-errs]
                                   (println (str "Config error: " message)))})]
          [:continue {:config config
                      :step-paths (or (some->> (:step-paths opts)
                                               (project-context/resolve-cli-paths project-context))
                                      (config/get-step-paths config))
                      :allow-pending? (config/allow-pending? config)}])))))

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
            (let [err {:type :glossary/intents-load-failed
                       :message (str "Failed to load intents from " dir)
                       :errors (:errors (ex-data e))}]
              [:exit (report-planning-error! run-id opts
                       {:error err
                        :diagnostics {:errors [err]}
                        :stderr-msg #(do (println "Intents load error:" (ex-message e))
                                         (doseq [sub (:errors (ex-data e))]
                                           (println " " (:message sub))))})])))))))

(defn- discover-features-stage
  "Stage 2: Discover feature files.

   With no CLI paths and a REAL config (discovered/explicit — never the
   defaults context), falls back to the config feature corpus
   [:runner :features], resolved against :config-root (sl-fcjm; default
   sl/features/). CLI paths always win; setup mode never reaches this stage.
   Returns [:continue {:feature-paths [...]}] or [:exit error-result]."
  [run-id opts config]
  (let [project-context (:project-context opts)
        cli-paths (project-context/resolve-cli-paths project-context (:paths opts))
        default? (and (empty? cli-paths)
                      (not= :defaults (:config-source project-context)))
        paths (if default?
                (project-context/resolve-config-paths
                 project-context (config/get-features config))
                cli-paths)
        discover-result (discover/discover-feature-files-or-error paths)]
    (if (= :error (:status discover-result))
      (let [message (if (and default?
                             (= :discover/path-not-found (:type discover-result)))
                      (str "No feature paths given and the default feature corpus "
                           (pr-str (config/get-features config))
                           " does not exist under the config root. "
                           "Create it (sl/features/) or pass feature paths. "
                           "Missing: " (first (:paths discover-result)))
                      (:message discover-result))
            err {:type (:type discover-result) :message message}]
        [:exit (report-planning-error! run-id opts
                 {:error err
                  :diagnostics {:errors [err]}
                  :stderr-msg #(println "Discovery error:" message)})])
      [:continue {:feature-paths (:files discover-result)}])))

(defn- parse-features-stage
  "Stage 3-4: Parse all feature files.
   Returns [:continue {:all-pickles [...] :features [...]}] or [:exit error-result]."
  [run-id opts feature-paths]
  (let [parse-result (parse-all-features feature-paths)]
    (if (= :error (:status parse-result))
      (let [err {:type :parse/failed
                 :message (str (count (:errors parse-result))
                               " parse error(s) in feature file(s)")
                 :errors (:errors parse-result)}]
        [:exit (merge
                (report-planning-error! run-id opts
                  {:error err
                   :diagnostics {:errors [err]}
                   :stderr-msg #(do (println "Parse errors:")
                                    (doseq [sub (:errors parse-result)]
                                      (println " " (:message sub))))})
                {:errors (:errors parse-result)})])
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
                    :diagnostics {:errors [err]}
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
      ;; Step-load sub-errors are {:path ... :error {...}} — flatten each for
      ;; the diagnostics copy so serialize-error's projection (:type :message
      ;; :path) has something to keep. The result-map merge keeps the raw
      ;; shape for programmatic callers.
      (let [flat-errs (mapv (fn [{:keys [path error]}] (assoc error :path path))
                            (:errors load-result))
            err {:type :step-load/failed
                 :message (str (count flat-errs)
                               " step definition file(s) failed to load")
                 :errors flat-errs}]
        [:exit (merge
                (report-planning-error! run-id opts
                  {:error err
                   :diagnostics {:errors [err]}
                   :stderr-msg #(do (println "Step load errors:")
                                    (doseq [sub (:errors load-result)]
                                      (println " " (:path sub) "-"
                                               (-> sub :error :message))))})
                {:errors (:errors load-result)})])
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
        (write-planning-failure-html! run-id opts config all-pickles
                                      diagnostics)
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
          ;; sl-6hsd (Chair condition, 2bcdeff): same stage, same data in
          ;; hand — the HTML report writes here too, or a dangling @hook=
          ;; would leave yesterday's report lying while an undefined step
          ;; wouldn't.
          (do
            (write-planning-failure-html! run-id opts config all-pickles
                                          {:errors [hooks-err]})
            [:exit (report-planning-error! run-id opts
                     {:error hooks-err
                      :diagnostics {:errors [hooks-err]}
                      :stderr-msg #(println "Hooks error:" (:message hooks-err))})])
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
                (write-planning-failure-html! run-id opts config all-pickles
                                              diagnostics)
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
   nil unless a tag filter was active (sl-muq9 DP1 selection story).

   Does NOT close the bus (sl-4tsi lifecycle half): run-scope governs event
   SEMANTICS (each group emits its own :test-run/started/:finished pair), but
   the bus TRANSPORT lives for the whole runner invocation — `execute!` closes
   it in a suite-level finally, after all groups and on every exit path.
   Closing here would kill groups 2..N's events on the shared bus."
  [run-id opts bus config features all-pickles filtered-count plans diagnostics allow-pending? report-opts]
  (let [started-at (run-start-timestamp)
        ;; sl-y3wd: the run-results dir, stamped from this run-scope's
        ;; started-at (first executing group creates; later groups share the
        ;; invocation's cached dir). nil in a no-config (:defaults) context.
        ;; :results-dir? is an internal seam (never CLI-exposed) so the
        ;; framework's own test suite can opt out of littering fixtures.
        results-dir (when (get opts :results-dir? true)
                      (results/ensure-run-dir! (or (:results-state opts) (atom nil))
                                               (:project-context opts)
                                               started-at run-id))
        ;; sl-l9nj: this run-scope's OUTPUT ROOT. Explicit setup.clj groups
        ;; get <run-dir>/<group-slug>/; the implicit single group gets the
        ;; run dir itself (no synthetic subdir — Chair-ratified 08-03).
        ;; Attachment refs in the envelope are group-root-relative, so
        ;; rendering is identical in both worlds.
        group-root (when results-dir
                     (if-let [label (:group-label opts)]
                       (results/ensure-group-dir! results-dir label
                                                  (:group-index opts 0))
                       results-dir))
        ;; sl-3q9r: capture resolution + the warn channel (per group, so
        ;; the unknown-kind check sees the group's LIVE registry — custom
        ;; adapters from setup :start included).
        capture-res (resolve-capture opts config)
        capture-lints (capture-warn-pass! opts capture-res group-root)
        capture-policy (:policy capture-res)
        capture-on? (and (capture/capture-active? capture-policy)
                         (some? group-root))
        ctx {:reporters (make-reporters opts report-opts config group-root)
             :bus bus :run-id run-id}
        ;; sl-lbrc: cleanup failures are read off the RAW exec-result here —
        ;; reporters only ever see post-allowlist envelopes, which drop
        ;; :capability-cleanup — and ride the :diagnostics lifecycle as
        ;; warn-level :cleanup-failures (console print-warnings! stderr block;
        ;; EDN :diagnostics; never the exit code — truth via warning, c6wk).
        ;; SCOPE BOUNDARY: per-scenario cleanup skips :persistent capabilities
        ;; by design and the runner has no suite-end cleanup site — persistent
        ;; capability lifecycle belongs to the REPL/daemon surfaces
        ;; (sl-tss7/sl-1qet). This scan is complete for the runner's surface,
        ;; not a claim about REPL coverage.
        collect-cleanup-failures
        (fn [exec-result]
          (vec (for [scenario (:scenarios exec-result)
                     {:keys [action] :as cleaned} (-> scenario
                                                      :capability-cleanup
                                                      :cleaned)
                     :when (= :close-failed action)]
                 {:scenario (-> scenario :plan :plan/pickle :pickle/name)
                  :capability (:interface cleaned)
                  :adapter (:adapter cleaned)
                  :error (:error cleaned)})))
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
    (emit-lifecycle! ctx :run-start
                     ;; sl-l9nj: run-ctx gains reporter visibility of the
                     ;; run dir + this scope's output root (absent when no
                     ;; results dir — defaults-config run-ctx unchanged).
                     ;; JUnit absolutizes relative attachment refs against
                     ;; :artifacts-root; the HTML island carries both as
                     ;; provenance.
                     (cond-> (run-start-ctx run-id opts config
                                            (count all-pickles) filtered-count
                                            started-at)
                       results-dir (assoc :results-dir results-dir)
                       group-root (assoc :artifacts-root group-root))
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
                      (:adapter-registry opts) (assoc :adapter-registry (:adapter-registry opts))
                      ;; sl-l9nj: where hook artifacts live — a plain path
                      ;; string; the stepengine stays runner-ignorant.
                      group-root (assoc :artifacts-root group-root)
                      ;; sl-3q9r: the resolved capture policy — only when
                      ;; active AND rooted (no results dir = capture
                      ;; disabled, warned above).
                      capture-on? (assoc :capture-policy capture-policy))
          ;; Reporters have already seen every scenario (live, via the callback)
          ;; by the time this returns.
          exec-result (exec/execute-suite plans exec-opts)
          ;; Invariant 4: the exit code is a pure function of the execution
          ;; result; reporting (which already ran) cannot change it.
          exit-code (compute-exit-code exec-result allow-pending?)
          cleanup-failures (collect-cleanup-failures exec-result)
          diagnostics (cond-> diagnostics
                        (seq cleanup-failures)
                        (assoc :cleanup-failures cleanup-failures)
                        ;; sl-3q9r: capture warns ride the diagnostics
                        ;; lifecycle under --edn (stderr suppressed there),
                        ;; the i608 config-lint pattern.
                        (seq capture-lints)
                        (assoc :capture-lints capture-lints))]
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
      (cond-> {:exit-code exit-code
               :run-id run-id
               :status (if (zero? exit-code) :passed :failed)
               :counts (:counts exec-result)
               ;; The RAW exec-result, deliberately: envelopes are a projection
               ;; for the report/observe planes only; programmatic callers keep
               ;; full fidelity.
               :result exec-result}
        ;; sl-y3wd: where this run's outputs live (also in run-ctx since
        ;; sl-l9nj — reporters see it; goldens stay byte-identical because
        ;; console/EDN ignore run-start and the key is absent without a dir).
        results-dir (assoc :results-dir results-dir)))))

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
                  :diagnostics {:errors [error]}
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

(defn- teardown-entry
  "Marker-shaped record of one :stop throw (see teardown-marker ::entry)."
  [run-id label t]
  {:group label
   :message (ex-message t)
   :run-id run-id
   :at (str (java.time.Instant/now))})

(defn- report-teardown-failure!
  "sl-yvaz: a :stop throw SURFACES (group result + EDN + console) but never
   flips the exit code — the tests passed; a leaked resource is truth via
   warning (the lbrc line). Also appends the cross-run dirty marker so the
   NEXT run is warned even if nobody read this one's output.

   EDN mode emits a SUPPLEMENTARY diagnostics form after the group's
   summary (the group summary has already been printed by the time :stop
   runs). NOTE (Chair condition, sl-yvaz plan approval): if the reporting
   lifecycle is ever restructured (the 21z/qwf-era architecture is the
   natural moment), this form folds into the group summary's own
   :diagnostics — that is the eventual shape, deliberately not bought
   today."
  [run-id opts label t]
  (let [entry (teardown-entry run-id label t)]
    (marker/append! (:project-context opts) entry)
    (if (:edn opts)
      (report-edn/prn-summary {:run/id run-id
                               :group label
                               :diagnostics {:teardown-failures [entry]}})
      (binding [*out* *err*]
        (println (str "\nSetup group " (pr-str label)
                      " teardown failed (environment may be dirty):"))
        (println (str "  " (ex-message t)))))
    entry))

(defn- announce-prior-teardown!
  "Startup half of the dirty marker (sl-yvaz): announce entries left by a
   prior run's failed :stop, then clear the marker — EXCEPT under :dry-run,
   which announces but leaves the file (a preview must not consume the
   warning; no lifecycle runs that could clean anything). Runs before
   config LOADING: the marker path needs only the project context (config
   DISCOVERY, already resolved), never config contents — verified against
   the wardrobe's operational-path convention at plan approval. Fail-open
   everywhere: no marker / unreadable marker -> silence."
  [run-id opts]
  (let [entries (marker/read-entries (:project-context opts))]
    (when (seq entries)
      (if (:edn opts)
        (report-edn/prn-summary {:run/id run-id
                                 :diagnostics {:teardown-failures (vec entries)}})
        (binding [*out* *err*]
          (println "\nPrevious run teardown failed — environment may be dirty:")
          (doseq [{:keys [group message at] prior-run :run-id} entries]
            (println (str "  group " (pr-str group)
                          " (run " prior-run " at " at "): " message)))))
      (when-not (:dry-run opts)
        (marker/clear! (:project-context opts))))))

(defn- run-group!
  "Run one orchestrated group end-to-end.

   Lifecycle: call (:start config) → parse this group's features →
   compile-suite (with the group's adapter-registry) → execute → :stop
   (runs even when the body fails or throws). Under :dry-run, :start and
   :stop are skipped ENTIRELY (sl-ev0b) — the preview binds against the
   default adapter registry and no user lifecycle code runs.

   Returns a per-group result map shaped like execute-and-report-stage's,
   plus :group-label and :status; a :stop throw adds :teardown-failure
   (surfaced, never exit-flipping — sl-yvaz)."
  [run-id opts config group group-index bus report-opts allow-pending?]
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
      ;; Both :start failure branches ride report-planning-error! like every
      ;; sibling failure path in this fn (parse/filter/compile) — before
      ;; sl-1h03 they printed a console-only stderr line, so EDN mode emitted
      ;; NOTHING for the group and the suite aggregated its exit-2 silently.
      (::start-throw start-result)
      (let [t (::start-throw start-result)
            ;; Teaching tail (sl-yvaz, the mmfy G1b ruling): partial-start
            ;; cleanup is the USER's — the framework cannot know what
            ;; half-started, and the no-DSL wall means it never will.
            err {:type :setup/start-threw
                 :group label
                 :message (str "Setup group " (pr-str label) " :start threw: "
                               (ex-message t)
                               " — anything :start started before the throw"
                               " is yours to clean up (wrap your own"
                               " try/catch); the framework only promises"
                               " that a throw skips this group honestly.")}]
        (-> (report-planning-error! run-id opts
              {:error err
               :diagnostics {:errors [err]}
               :stderr-msg #(println (:message err))})
            (assoc :group-label label :error err)))

      (and start-result (not (s/valid? :shiftlefter.runner.setup/start-result start-result)))
      (let [err {:type :setup/invalid-start-result
                 :group label
                 :message (str "Setup group " (pr-str label)
                               " :start returned an invalid shape; expected {:adapter-registry? :stop?}")}]
        (-> (report-planning-error! run-id opts
              {:error err
               :diagnostics {:errors [err]}
               :stderr-msg #(println (:message err))})
            (assoc :group-label label :error err)))

      :else
      (let [{:keys [adapter-registry stop]} start-result
            inner-opts (cond-> (assoc opts :group-label label
                                      :group-index group-index)
                         adapter-registry (assoc :adapter-registry adapter-registry))
            ;; Body result is CAPTURED (not try/finally): a :stop throw must
            ;; enrich the returned group result, which a finally cannot do.
            ;; ::crashed preserves crash semantics — :stop still runs, then
            ;; the throw resumes to execute!'s outer handler (exit 3).
            body-result
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
              (catch Throwable t
                {::crashed t}))
            ;; :stop runs even when the body failed or threw — the same
            ;; guarantee the old finally gave.
            stop-error (when stop
                         (try (stop) nil
                              (catch Throwable t t)))
            stop-entry (when stop-error
                         (report-teardown-failure! run-id opts label stop-error))]
        (if-let [t (::crashed body-result)]
          (throw t)
          (cond-> body-result
            stop-entry (assoc :teardown-failure stop-entry)))))))

(defn- run-with-setups!
  "Drive the suite through all setup groups in declared order.
   Aggregates per-group exit codes into a single suite result.

   `setup-base-dir`: directory containing setup.clj — relative paths in
   each group's :features resolve against it."
  [run-id opts config setups setup-base-dir bus report-opts allow-pending?]
  (let [;; Plan-shape lints (sl-lkla): static over the FULL declared plan,
        ;; before CLI narrowing — dry-run and filtered runs see the same
        ;; verdicts. Warnings surface FIRST (a user fixing the error should
        ;; not discover the warning only on the next run): stderr lines in
        ;; console mode (config-lint pattern, not suppressed in dry-run);
        ;; under --edn a supplementary :setup-lints diagnostics form (the
        ;; teardown-failures precedent). Never exit-flipping.
        {lint-errors :errors lint-warnings :warnings}
        (setup/lint-setups setups setup-base-dir)
        _ (when (seq lint-warnings)
            (if (:edn opts)
              (report-edn/prn-summary {:run/id run-id
                                       :diagnostics {:setup-lints (vec lint-warnings)}})
              (binding [*out* *err*]
                (doseq [{:keys [message]} lint-warnings]
                  (println (str "Setup plan warning: " message))))))
        ;; Validate CLI paths against the declared union before any group runs.
        cli-result (setup/validate-cli-paths (:paths opts) setups setup-base-dir)]
    (cond
      (seq lint-errors)
      (report-planning-error! run-id opts
        {:error (first lint-errors)
         :diagnostics {:errors lint-errors}
         :stderr-msg #(doseq [{:keys [message]} lint-errors]
                        (println (str "Setup error: " message)))})

      (:error cli-result)
      (let [err (:error cli-result)]
        (report-planning-error! run-id opts
          {:error err
           :diagnostics {:errors [err]}
           :stderr-msg #(println "Setup error:" (:message err))}))

      :else
      (let [filtered (setup/filter-setups-by-cli-paths setups (:ok cli-result) setup-base-dir)
            results  (vec (map-indexed
                           (fn [idx group]
                             (run-group! run-id opts config group idx
                                         bus report-opts allow-pending?))
                           filtered))
            exit-code (compute-suite-exit-code (map :exit-code results))]
        (cond-> {:exit-code exit-code
                 :run-id    run-id
                 :status    (case exit-code
                              0 :passed
                              2 :planning-failed
                              :failed)
                 :groups    results}
          ;; sl-l9nj: the invocation's run dir at the top level too — all
          ;; groups share it (per-group results keep their own copy).
          (some :results-dir results)
          (assoc :results-dir (some :results-dir results)))))))

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
   - :capture - capture policy map {kind #{:never :on-failure :every-step}}
     (sl-3q9r); wins AS A WHOLE VALUE over the config mirror
     [:runner :capture]; absent/{} = no capture
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
        ;; sl-y3wd: one results dir per INVOCATION — the atom is the
        ;; create-once seam shared by all setup groups.
        opts (cond-> opts
               (nil? (:results-state opts)) (assoc :results-state (atom nil))
               ;; sl-3q9r: capture warns dedup ONCE PER RUN across groups.
               (nil? (:capture-warn-state opts))
               (assoc :capture-warn-state (atom #{})))
        run-id (str (java.util.UUID/randomUUID))
        bus (events/make-memory-bus)
        report-opts (select-keys opts [:verbose :no-color])]
    (binding [wardrobe/*project-context* project-context]
      (try
        ;; sl-yvaz: announce (and, on real runs, clear) a prior run's dirty
        ;; teardown marker before anything else happens.
        (announce-prior-teardown! run-id opts)
        (let [[s1 d1] (if-let [opt-err (or (max-parallel-error opts)
                                           (capture-opt-error opts))]
                        ;; Strict execute!-boundary validation (sl-q9wp,
                        ;; sl-3q9r): malformed opts ride the planning-error
                        ;; path, like :tag-filter.
                        [:exit (report-planning-error! run-id opts
                                 {:error opt-err
                                  :diagnostics {:errors [opt-err]}
                                  :stderr-msg #(println "Option error:"
                                                        (:message opt-err))})]
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
                    (let [[s2 d2] (discover-features-stage run-id opts config)]
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
            {:exit-code exit-code
             :run-id run-id
             :status :crashed
             :error error}))
        ;; sl-4tsi lifecycle half: the bus lives for the whole invocation and
        ;; is closed HERE, once, on every exit path — normal, dry-run, every
        ;; planning-failure early return, setup-branch exits, and crash. A
        ;; finally (not per-path closes) so unenumerated future exits are
        ;; covered too. bus-close! drains subscribers before returning (q9wp).
        (finally
          (events/bus-close! bus))))))
