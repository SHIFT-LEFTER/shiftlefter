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

   ## Exit Codes (the verdict contract, sl-7c7i)

   - 0: Existential pass — >=1 scenario selected, all ran to a tolerated
        outcome (pending only under :allow-pending?), none failed/errored
   - 1: Executed, >=1 scenario failed (or pending under strict mode)
   - 2: Planning/setup failure (parse/config/load/bind errors, empty
        selection) — pre-execution exclusive
   - 3: Harness crash (uncaught exception)
   - 4: Run degraded — >=1 scenario :error (infrastructure-failure family)

   Canonical table + precedence: shiftlefter.runner.verdict."
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
            [shiftlefter.events :as events]
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
            [shiftlefter.runner.verdict :as verdict]
            [shiftlefter.stepengine.compile :as compile]
            [shiftlefter.stepengine.exec :as exec]
            [shiftlefter.stepengine.exec.capture :as capture]
            [shiftlefter.stepengine.exec.provisioning :as prov]
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
  "Compute exit code from execution result (invariant 4: a pure function
   of the execution result). Execution worst-wins 4 > 1 > 0 (sl-7c7i):
   scenario :error (the infrastructure-failure family — hook throw,
   capability provisioning, capture, harness) is exit 4, RUN DEGRADED —
   the harness broke, so the run proves nothing about the app. Exit 1 is
   reserved for scenario :failed (valid tests, app wrong) and for pending
   under strict mode. Never exit 2 from here — that code is pre-execution
   exclusive. See shiftlefter.runner.verdict for the canonical table."
  [exec-result allow-pending?]
  (let [{:keys [failed pending error]} (:counts exec-result)]
    (cond
      (pos? (or error 0)) 4
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

(defn- capture-plan-lint-pass!
  "The plan-time per-group capture capability warn (sl-sehc): a configured
   ACTIVE kind that at least one registry adapter contributes but NONE of
   the adapters this group's plans actually use — the registry-level
   unknown-kind check cannot see per-adapter gaps (etaoin's kinds mask a
   pure-Playwright group's silence, including the mixed etaoin+playwright
   multi-group composition, where the silence is per-group). Runs right
   after compile in BOTH pipelines and BOTH branches — dry-run included:
   preview-honesty is the point (agents live on dry-run), so the gate is
   the policy alone, never group-root. Under dry-run the threaded registry
   is e0ml's declared-preview-registry when the group declares :adapters,
   so declared custom adapters count.

   Same channel discipline as capture-warn-pass!: one stderr line per key
   (suppressed under --edn), lints ride :diagnostics :capture-lints; the
   dedup key includes the group index so precision stays per-group. Never
   an error, never exit-code.

   Returns the NEWLY-warned lint maps for this group's diagnostics."
  [opts {:keys [policy]} plans config]
  (let [state (or (:capture-warn-state opts) (atom #{}))
        edn? (:edn opts)
        registry (or (:adapter-registry opts) adapters/default-registry)
        used (into #{}
                   (keep #(get-in (:interfaces config) [(:interface %) :adapter]))
                   (mapcat prov/collect-provisioning-targets plans))
        label (:group-label opts)
        adapters-str (str/join ", " (map str (sort used)))
        warn! (fn [key msg lint]
                (when-not (contains? @state key)
                  (swap! state conj key)
                  (when-not edn?
                    (binding [*out* *err*] (println msg)))
                  lint))]
    (vec
     (keep (fn [kind]
             (warn! [:adapter-unsupported (:group-index opts) kind]
                    (str "Capture warning: "
                         (when label (str "group " (pr-str label) ": "))
                         "kind " kind " is supported by none of the adapters "
                         (if label "this group" "this run") " uses ("
                         adapters-str ") — it will never capture anything"
                         (when label " in this group"))
                    (cond-> {:type :capture/adapter-unsupported
                             :kind kind
                             :adapters (vec (sort used))
                             :message (str "capture kind " kind
                                           " supported by none of the"
                                           " adapters in use ("
                                           adapters-str ")")}
                      label (assoc :group label))))
           (capture/adapter-unsupported-kinds policy used registry)))))

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

(defn- seed-opt-error
  "nil when the :seed execute! opt is valid (absent or a positive long,
   sl-08f7), else a structured error map (the max-parallel-error pattern)."
  [opts]
  (let [sd (:seed opts)]
    (when (and (some? sd)
               (not (and (int? sd) (pos? sd))))
      {:type :seed/invalid
       :message (str "Invalid :seed " (pr-str sd)
                     " — must be a positive integer")})))

(defn- generate-run-seed
  "A fresh run seed: positive 64-bit long, decimal-printed everywhere
   (sl-08f7 — composes with java.util.Random/SplittableRandom/test.check)."
  []
  (.nextLong (java.util.concurrent.ThreadLocalRandom/current) 1 Long/MAX_VALUE))

(defn- print-run-seed-line!
  "The ONE console line recording the run seed (sl-08f7, Chair-ratified):
   stderr, once per invocation, after final resolution — stderr keeps
   --edn stdout pure data."
  [seed]
  (binding [*out* *err*]
    (println (str "run seed: " seed))))

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
      junit-path (conj (report-junit/make-reporter
                        ;; sl-xjx0 (Warden W7): multi-group runs share one
                        ;; invocation-scoped state so the invocation-fixed
                        ;; XML aggregates across groups instead of
                        ;; last-writer-wins.
                        (cond-> {:path junit-path}
                          (:junit-shared-state opts)
                          (assoc :shared-state (:junit-shared-state opts)))))
      html-path (conj (report-html/make-reporter
                       (let [primary (when group-root
                                       (str (fs/path group-root "report.html")))]
                         ;; sl-n9c6: in a multi-group run the stable path
                         ;; belongs to the invocation TOC — the per-group
                         ;; byte copy is suppressed (path = primary makes
                         ;; the copy a same-file no-op). Without a primary
                         ;; the group write still lands at the stable path;
                         ;; the TOC epilogue overwrites it last.
                         {:path (if (and (:suppress-stable-html-copy opts) primary)
                                  primary
                                  html-path)
                          :primary primary}))))))

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
             ;; sl-08f7: the run seed — always present on the runner path
             ;; (core-record family beside :run-id); spec keeps it opt-un so
             ;; bare/defaults contexts keep their historical shape.
             :seed (:run-seed opts)
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
    (let [run-ctx (run-start-ctx run-id opts config (count all-pickles) nil
                                 (run-start-timestamp))
          summary {:run-id run-id
                   :exit-code 2
                   :status :planning-failed
                   :counts (:counts diagnostics)}]
      (if-let [stash (:html-failure-stash opts)]
        ;; sl-n9c6 half A: in a multi-group run the page is DEFERRED — the
        ;; invocation epilogue writes it durably at <group-slug>/report.html
        ;; (the run dir may not exist yet when this group fails), and the
        ;; stable path belongs to the TOC. Writing here would recreate the
        ;; F3 transient (a later green group's copy destroying the only
        ;; copy of this failure page).
        (swap! stash conj {:group-label (:group-label opts)
                           :group-index (:group-index opts 0)
                           :run-ctx run-ctx
                           :diagnostics diagnostics
                           :summary summary})
        (report-html/write-planning-failure! html-path run-ctx diagnostics summary)))))

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
  "Report a planning error (exit 2) to the appropriate output.
   Stamps the run seed into the record (sl-08f7 core-record family):
   pre-config failures carry the candidate seed already riding opts."
  [run-id opts error-info]
  (let [exit-code 2]
    (when (:edn opts)
      (report-edn/prn-summary
       (report-edn/build-summary run-id exit-code nil
                                 (assoc error-info :seed (:run-seed opts)))))
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
   and the runner's EDN envelope carries them as :config-lints on every
   exit path — sl-lnj1 for the compile-stage and run exits, sl-8cvd P2-5
   for the config-stage exit-2). NOT suppressed in dry-run: unlike the
   tag-filter notice, nothing else carries this signal. Warnings only —
   never affects the exit code."
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
            ;; Config-stage errors, all exit 2 through the same envelope:
            ;; adapter-not-bundled (sl-gcdu) plus EVERY normalize validator
            ;; (sl-74w8 — macro/timing/interfaces/costumes). sl-st01 (Warden
            ;; W2) first wired validate-costumes-config here directly; that
            ;; call is SUBSUMED by normalize now — do not re-add it. normalize
            ;; is identity-on-success (sl-w9wp), so only its :errors are
            ;; consumed and `config` flows on unchanged; any validator added
            ;; to normalize is run-path-reachable by construction.
            config-errs (into (adapter-availability-errors config)
                              (:errors (config/normalize config)))
            lints (config/lint-config config)]
        (print-config-lint-notices! opts (:config-path project-context) lints)
        (if (seq config-errs)
          ;; :diagnostics {:errors ...} is what makes the EDN summary carry
          ;; the error on exit 2 — build-summary ignores :error there.
          ;; :config-lints rides the same map (sl-8cvd, Warden P2-5): the
          ;; stderr notice is suppressed under :edn, so without this the
          ;; machine surface lost the lint signal exactly when a config
          ;; error co-occurred. Scrubbed at attach, like the compile-stage
          ;; twin in compile-suite-stage.
          [:exit (report-planning-error! run-id opts
                   {:error (first config-errs)
                    :diagnostics (cond-> {:errors config-errs}
                                   (seq lints)
                                   (assoc :config-lints (reporter/scrub lints)))
                    :stderr-msg #(doseq [{:keys [message]} config-errs]
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

(defn- empty-selection-error
  "The vacuity witness (sl-7c7i): a selection of zero scenarios is a
   planning error — a run that selects nothing cannot claim green, and
   exit 0 is existential (>=1 scenario selected). No escape knob (Chair
   ruling 08-07): the escape is DATA — the :data block carries what a CI
   wrapper needs to make its own choice upstream (sl-78md item 12,
   our-data-your-choices): what was searched, the active filter, the CLI
   narrowing, and how many scenarios existed before narrowing."
  [{:keys [total searched tag-filter groups cli-paths]}]
  (let [total (or total 0)]
    {:type :selection/empty
     :message (str "Empty selection: 0 of " total " scenario(s) selected"
                   (when (seq searched)
                     (str " (searched: " (str/join ", " searched) ")"))
                   (when tag-filter
                     (str " (tag filter: " (pr-str tag-filter) ")"))
                   (when (seq cli-paths)
                     (str " (CLI paths: " (str/join ", " cli-paths) ")"))
                   (when (seq groups)
                     (str " (groups: " (str/join ", " (map pr-str groups)) ")"))
                   " — nothing would run, so nothing can pass")
     :data (cond-> {:selected 0 :total total}
             (seq searched) (assoc :searched (vec searched))
             tag-filter     (assoc :tag-filter tag-filter)
             (seq cli-paths) (assoc :cli-paths (vec cli-paths))
             (seq groups)   (assoc :groups (vec groups)))}))

(defn- report-empty-selection!
  "Ride the standard planning-error path with an empty-selection error.
   The error also rides the returned result map (the run-group! pattern)
   so programmatic callers can read the selection story without parsing
   output."
  [run-id opts data]
  (let [err (empty-selection-error data)]
    (-> (report-planning-error! run-id opts
                                {:error err
                                 :diagnostics {:errors [err]}
                                 :stderr-msg #(println (:message err))})
        (assoc :error err))))

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
           (report-edn/build-summary run-id exit-code nil
                                     {:diagnostics diagnostics
                                      :seed (:run-seed opts)})))
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
                                             {:diagnostics diagnostics
                                              :seed (:run-seed opts)})))
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
   without a filter the summary is byte-identical to before.
   `seed` (sl-08f7): the run seed — core-record family, always stamped."
  [run-id seed plans diagnostics group-label filtered-count]
  (cond-> {:run/id run-id
           :run/seed seed
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
        ;; sl-d7ks: this run-scope's drop baseline. The bus (and its drop
        ;; counter) is invocation-scoped but summaries are per-group, so each
        ;; group reports the DELTA — a consumer summing group summaries never
        ;; double-counts. The invocation total stays bus-close!'s warn line.
        dropped-at-start (events/dropped-events bus)
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
    (let [;; MIRROR NOTE (sl-xjx0, W8): the ENGINE consumes five of these
          ;; opts — :interfaces :timing :run-seed (mirrored by repl.clj's
          ;; `run`) plus :provisioning (step_loop's :eager gate) and
          ;; :max-parallel (exec's parallel dispatch), which repl omits ON
          ;; PURPOSE: repl/run is always lazy + sequential (sl-vmc5). A
          ;; new engine-consumed exec-opt added here must be hand-mirrored
          ;; there — or its omission recorded there — and vice versa.
          exec-opts (cond-> {:bus bus :run-id run-id :interfaces (:interfaces config)
                             ;; sl-99u2: global :timing rung, stashed into
                             ;; scenario ctx for step/timing resolution.
                             :timing (:timing config)
                             ;; sl-08f7: the run seed, handed down to user
                             ;; code (hook payloads + step ctx) by the
                             ;; scenario wrapper.
                             :run-seed (:run-seed opts)
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
                        ;; the i608 config-lint pattern. Append, not assoc —
                        ;; the sl-sehc plan-time lints may already be here.
                        (seq capture-lints)
                        (update :capture-lints (fnil into []) capture-lints))]
      ;; Warn-level SVO issues didn't block execution but must still reach a
      ;; human (sl-qk8l/sl-6h4r) — EDN output already carries them.
      (emit-lifecycle! ctx :diagnostics (reporter/diagnostics-envelope diagnostics) nil)
      ;; sl-d7ks: :test-run/finished is published BEFORE the reporter
      ;; fan-out (emit-lifecycle! is reporters-first) so the drop counter is
      ;; read AFTER the run-scope's final publish — otherwise a drop of the
      ;; finished event itself would postdate the read and the summary would
      ;; falsely report zero. Cross-plane ordering is explicitly unspecified
      ;; (events.clj delivery contract), so only the counter read moves.
      (events/publish! bus (events/make-event :test-run/finished run-id
                                              {:exit-code exit-code
                                               :counts (:counts exec-result)}))
      (let [dropped (- (events/dropped-events bus) dropped-at-start)
            run-summary (cond-> {:run-id run-id
                                 ;; sl-08f7: core-record family — every
                                 ;; run-scope's summary carries the one
                                 ;; invocation seed.
                                 :seed (:run-seed opts)
                                 :exit-code exit-code
                                 :counts (:counts exec-result)
                                 :status (verdict/exit-code->status exit-code)}
                          ;; Absent-when-zero (anomaly-marker convention,
                          ;; Chair re-ratified 08-10): presence IS the signal.
                          (pos? dropped) (assoc :dropped-events dropped))]
        (emit-lifecycle! ctx :run-end run-summary nil)
        (cond-> {:exit-code exit-code
                 :run-id run-id
                 :status (verdict/exit-code->status exit-code)
                 :counts (:counts exec-result)
                 ;; The RAW exec-result, deliberately: envelopes are a projection
                 ;; for the report/observe planes only; programmatic callers keep
                 ;; full fidelity.
                 :result exec-result}
          (pos? dropped) (assoc :dropped-events dropped)
          ;; sl-y3wd: where this run's outputs live (also in run-ctx since
          ;; sl-l9nj — reporters see it; goldens stay byte-identical because
          ;; console/EDN ignore run-start and the key is absent without a dir).
          results-dir (assoc :results-dir results-dir)
          ;; sl-n9c6: this run-scope's actual output root. The invocation TOC
          ;; links rows by it — re-deriving via ensure-group-dir! would hit
          ;; the duplicate-label fallback and misroute.
          group-root (assoc :group-root group-root))))))

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
   Worst code wins: planning-error (2) > degraded (4) > failed (1) >
   passed (0) — sl-7c7i; a crash (3) never appears here, it throws past
   aggregation to execute!'s handler."
  [exit-codes]
  (cond
    (some #(= 2 %) exit-codes) 2
    (some #(= 4 %) exit-codes) 4
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

(defn- report-undeclared-registry!
  "Migration warning (sl-e0ml part d): a group with no :adapters declaration
   whose :start returned an :adapter-registry still works this release, but
   the preview is blind to that registry — say so, once per group, at the
   moment it becomes knowable (post-:start; plan time cannot know, that IS
   the blindness). Channel mirrors the lkla setup-lints lane: one stderr
   line in console mode; under --edn a supplementary :setup-lints
   diagnostics form (the teardown-failures precedent — supplementary forms
   may precede the group's summary). Never exit-flipping. Returns the entry."
  [run-id opts label]
  (let [entry {:type :setup/undeclared-adapter-registry
               :group label
               :message (str "group " (pr-str label) " returned an adapter"
                             " registry it never declared — dry-run binds"
                             " blind to it; declare :adapters on the group"
                             " (deprecated; becomes an error in a future"
                             " release)")}]
    (if (:edn opts)
      (report-edn/prn-summary {:run/id run-id
                               :group label
                               :diagnostics {:setup-lints [entry]}})
      (binding [*out* *err*]
        (println (str "Setup plan warning: " (:message entry)))))
    entry))

(defn- report-lossy-overrides!
  "sl-xjx0 (Warden W20): surface lossy builtin-override warnings from
   `setup/lossy-override-warnings` on the same non-exit-flipping
   setup-lints channel as the undeclared-registry warning above. Returns
   the entries."
  [run-id opts label entries]
  (when (seq entries)
    (let [stamped (mapv #(assoc % :group label) entries)]
      (if (:edn opts)
        (report-edn/prn-summary {:run/id run-id
                                 :group label
                                 :diagnostics {:setup-lints stamped}})
        (binding [*out* *err*]
          (doseq [e stamped]
            (println (str "Setup plan warning: " (:message e))))))
      stamped)))

(defn- plans-touched-adapters
  "{adapter-name #{interface-instances}} for every adapter the compiled
   plans' bound steps reference (sl-kkoh — the exact-check input; the
   capture-plan-lint pass walks the same targets). Unconfigured/bare-type
   interfaces carry no :adapter and provision nothing, so they drop out."
  [plans interfaces]
  (reduce (fn [acc {:keys [interface]}]
            (if-let [adapter (get-in interfaces [interface :adapter])]
              (update acc adapter (fnil conj #{}) interface)
              acc))
          {}
          (mapcat prov/collect-provisioning-targets plans)))

(defn- run-group!
  "Run one orchestrated group end-to-end.

   Lifecycle: call (:start config) → verify the returned registry against
   the group's :adapters declarations (sl-e0ml) → parse this group's
   features → compile-suite (with the group's adapter-registry) → execute
   → :stop (runs even when the body fails or throws). Under :dry-run,
   :start and :stop are skipped ENTIRELY (sl-ev0b) — no user lifecycle code
   runs; the preview binds against the group's DECLARED adapter shapes
   (setup/declared-preview-registry) when :adapters is present, else the
   default registry.

   Returns a per-group result map shaped like execute-and-report-stage's,
   plus :group-label and :status; a :stop throw adds :teardown-failure
   (surfaced, never exit-flipping — sl-yvaz)."
  [run-id opts config group group-index bus report-opts allow-pending?]
  (let [label    (or (:label group) "<unlabeled>")
        features (setup/resolve-group-features group)
        decls    (:adapters group)
        ;; :start may throw or return malformed shape — both treated as
        ;; planning errors local to this group; other groups still run.
        ;; sl-ev0b: dry-run is a pure plan preview — no user lifecycle code
        ;; runs. nil start-result → no adapter registry threaded, and no
        ;; :stop in the finally (there is nothing to stop).
        start-result (when-not (:dry-run opts)
                       (try
                         ;; sl-08f7: the run seed rides the config argument
                         ;; as an additive :seed key — :start is run-level
                         ;; lifecycle, so it gets the datum too.
                         ((:start group) (assoc config :seed (:run-seed opts)))
                         (catch Throwable t
                           {::start-throw t})))
        ;; sl-wntk: the returned registry is EXTERNAL INPUT (house boundary-
        ;; validation rule) — entry-shape check via the registry's own spec,
        ;; with legible s/explain + unknown-key output. Fires on ANY live
        ;; returned registry (declared or the deprecated undeclared path),
        ;; and deliberately BEFORE verify-declared-registry: entry-data-half
        ;; would throw incidentally on e.g. a non-coll :provides, and the
        ;; legible error must win that race. Never fires on the dry-run
        ;; preview registry (data-only by design).
        registry-shape-problems
        (when (and (not (:dry-run opts))
                   (not (::start-throw start-result))
                   (s/valid? :shiftlefter.runner.setup/start-result start-result))
          (some-> (:adapter-registry start-result)
                  adapters/registry-problems))
        ;; sl-e0ml part c: declared contract, runtime-checked. Only when the
        ;; group declares :adapters and :start produced a spec-valid shape —
        ;; the earlier cond branches own the throw/invalid cases.
        registry-mismatch
        (when (and (seq decls)
                   (not (:dry-run opts))
                   (not (::start-throw start-result))
                   (nil? registry-shape-problems)
                   ;; a nil start-result (:start returned nil — tolerated
                   ;; today) verifies against the default registry
                   (or (nil? start-result)
                       (s/valid? :shiftlefter.runner.setup/start-result start-result)))
          (setup/verify-declared-registry decls (:adapter-registry start-result)))]
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

      ;; sl-wntk: the returned registry has malformed entries — a loud
      ;; planning-class error with the spec's own explain output, per
      ;; group, like its siblings. :stop does not run on this branch
      ;; (invalid-start-result precedent — the group's contract is broken
      ;; before its body ever starts).
      (seq registry-shape-problems)
      (let [err {:type :setup/invalid-adapter-registry
                 :group label
                 :problems (vec registry-shape-problems)
                 :message (str "Setup group " (pr-str label) " returned an"
                               " adapter registry with invalid entries: "
                               (str/join "; " (map :message registry-shape-problems)))}]
        (-> (report-planning-error! run-id opts
              {:error err
               :diagnostics {:errors [err]}
               :stderr-msg #(println (:message err))})
            (assoc :group-label label :error err)))

      ;; sl-e0ml part c: the live registry does not match the group's
      ;; :adapters declarations — a loud planning-class error, per group,
      ;; like every sibling failure path above. NOTE: :stop does not run on
      ;; this branch (matching the invalid-start-result precedent — the
      ;; group's contract is broken before its body ever starts).
      registry-mismatch
      (let [err (assoc registry-mismatch
                       :group label
                       :message (str "Setup group " (pr-str label) ": "
                                     (:message registry-mismatch)))]
        (-> (report-planning-error! run-id opts
              {:error err
               :diagnostics {:errors [err]}
               :stderr-msg #(println (:message err))})
            (assoc :group-label label :error err)))

      :else
      (let [{:keys [adapter-registry stop]} start-result
            ;; sl-e0ml part a: dry-run binds against the DECLARED shapes —
            ;; the preview registry is the default overlaid with the group's
            ;; declarations. Live runs keep threading the live registry
            ;; (factories are needed at provisioning); verify above already
            ;; proved its data half matches the declaration.
            preview-registry (when (and (:dry-run opts) (seq decls))
                               (setup/declared-preview-registry decls))
            effective-registry (or adapter-registry preview-registry)
            ;; sl-e0ml part d: undeclared returned registry = deprecated
            ;; shape; works this release with a preview-blind warning.
            _ (when (and adapter-registry (empty? decls))
                (report-undeclared-registry! run-id opts label))
            ;; sl-xjx0 (Warden W20): builtin-name overrides that silently
            ;; drop the default entry's runtime keys — warn unless the
            ;; declaration records the drop as deliberate. The removal arm
            ;; gets NO referenced adapters here (sl-kkoh): this seam has
            ;; plans by compile time, so the exact bound-adapter check
            ;; below owns removal — config-referenced-but-unbound removed
            ;; defaults are certainly safe for this invocation and silent.
            _ (when adapter-registry
                (report-lossy-overrides!
                 run-id opts label
                 (setup/lossy-override-warnings decls adapter-registry [])))
            inner-opts (cond-> (assoc opts :group-label label
                                      :group-index group-index)
                         effective-registry (assoc :adapter-registry effective-registry))
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
                          (let [{:keys [plans diagnostics]} d-compile
                                ;; sl-sehc: the plan-time per-group capture
                                ;; capability warn — computed here so BOTH
                                ;; branches see it (dry-run included; under
                                ;; dry-run inner-opts carries e0ml's declared
                                ;; preview registry, so it's preview-honest).
                                plan-lints (capture-plan-lint-pass!
                                            inner-opts
                                            (resolve-capture inner-opts config)
                                            plans config)
                                diagnostics (cond-> diagnostics
                                              (seq plan-lints)
                                              (update :capture-lints
                                                      (fnil into []) plan-lints))
                                ;; sl-kkoh: the plan-aware EXACT registry
                                ;; check — a bound, selected scenario whose
                                ;; adapter is missing from the live registry
                                ;; certainly dies at first provisioning.
                                ;; Live runs only (dry-run has no :start and
                                ;; binds the declared preview). NOTE: an
                                ;; error here still runs :stop below —
                                ;; :start ran, the world exists (unlike the
                                ;; pre-compile branches, ruled deliberate).
                                bound-errors
                                (when-not (:dry-run opts)
                                  (setup/bound-adapter-errors
                                   (plans-touched-adapters
                                    plans (:interfaces config))
                                   adapter-registry))]
                            (cond
                              (seq bound-errors)
                              (let [errs (mapv #(assoc % :group label)
                                               bound-errors)
                                    msg (str "Setup group " (pr-str label)
                                             ": "
                                             (str/join "; " (map :message errs)))]
                                (-> (report-planning-error! run-id opts
                                      {:error (first errs)
                                       :diagnostics {:errors errs}
                                       :stderr-msg #(println msg)})
                                    (assoc :group-label label
                                           :error (first errs))))

                              (:dry-run opts)
                              (let [exit-code 0]
                                (when-not (:edn opts)
                                  (console/print-warnings! diagnostics report-opts)
                                  (binding [*out* *err*]
                                    (println (str "[" label "] "
                                                  (dry-run-console-line (count plans) filtered-count))))
                                  (print-dry-run-hooks! (dry-run-hooks-entries plans)))
                                (when (:edn opts)
                                  (report-edn/prn-summary
                                   (dry-run-edn-summary run-id (:run-seed opts)
                                                        plans diagnostics label filtered-count)))
                                {:exit-code exit-code :run-id run-id :status :dry-run
                                 :group-label label :plans plans})

                              :else
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

(defn- preflight-group-selection
  "Count the scenarios one narrowed group would select — PURE planning
   work (feature resolution + parse + tag disposition; no :start runs).
   Returns {:selected n :total m :paths [...]}, or nil when the answer is
   unknowable here (parse error, malformed filter): those surface with
   full reporting inside the group pipeline; the preflight only answers
   'would the whole run select zero?' (sl-7c7i — exit 2 is pre-execution
   exclusive, so the vacuity check must precede every :start)."
  [opts group]
  (let [paths (setup/resolve-group-features group)
        parsed (parse-all-features paths)]
    (when (= :ok (:status parsed))
      (let [pickles (:all-pickles parsed)
            rules (:tag-filter opts)]
        (cond
          (nil? rules)
          {:selected (count pickles) :total (count pickles) :paths paths}

          (tagd/rules-error rules)
          nil

          :else
          {:selected (count (:selected (tagd/apply-dispositions rules pickles)))
           :total (count pickles)
           :paths paths})))))

(defn- write-invocation-report!
  "sl-n9c6: in a multi-group run the stable --html path represents the
   INVOCATION, not whichever group wrote last. Two writes, both here so
   ordering can't race the group loop:

   1. Half A (Chair-ratified 08-05): each stashed planning-failed group's
      page lands durably at its own <group-slug>/report.html beside its
      siblings — the F3 destruction scenario (a later green group's copy
      destroying the only copy) dies. Deferred to this epilogue because
      the run dir may not exist at failure time (failed group ran first).
   2. The invocation TOC at <run-dir>/report.html, byte-copied to the
      stable path. Rows ride in declaration order; the invocation verdict
      is the 7c7i suite aggregate. Links are relative and resolve inside
      the run dir; at the stable copy they degrade to labeled text (the
      renderAttachment pattern — the anchor text IS the path).

   No run dir at all (every group planning-failed — sl-y3wd creates none):
   stable TOC only, group pages skipped; verdicts stay visible, which is
   the honesty that matters."
  [run-id opts config results exit-code]
  (when-let [html-path (resolve-html-path opts config)]
    (let [run-dir (some :results-dir results)
          failures (some-> (:html-failure-stash opts) deref)
          failure-pages
          (when run-dir
            (into {}
                  (for [{:keys [group-label group-index run-ctx diagnostics summary]} failures]
                    (let [group-root (results/ensure-group-dir! run-dir group-label group-index)]
                      (report-html/write-planning-failure!
                       (str (fs/path group-root "report.html")) run-ctx diagnostics summary)
                      [group-index (str (fs/file-name group-root) "/report.html")]))))
          rows (vec (map-indexed
                     (fn [idx {:keys [group-label status counts group-root] :as result}]
                       (cond-> {:label group-label
                                :exit-code (:exit-code result)
                                :status status}
                         counts (assoc :counts counts)
                         group-root
                         (assoc :path (str (fs/file-name group-root) "/report.html"))
                         (and (nil? group-root) (get failure-pages idx))
                         (assoc :path (get failure-pages idx))))
                     results))
          island {:run-ctx (run-start-ctx run-id opts config 0 nil (run-start-timestamp))
                  :toc {:groups rows}
                  :summary {:run-id run-id
                            :exit-code exit-code
                            :status (verdict/exit-code->status exit-code)}}]
      (report-html/write-invocation-toc!
       (when run-dir (str (fs/path run-dir "report.html")))
       html-path island))))

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
            ;; Selection preflight (sl-7c7i): parse + tag-filter every
            ;; narrowed group WITHOUT running any :start. Empty selection
            ;; must be a pre-execution planning error (2 is pre-execution
            ;; exclusive), and the all-groups-narrowed-away case must not
            ;; aggregate [] into a silent green. A nil preflight (parse
            ;; error, malformed filter) means 'unknowable here' — proceed;
            ;; the group pipeline reports it properly. The re-parse inside
            ;; run-group! is deterministic, so preflight and run agree.
            preflights (mapv #(preflight-group-selection opts %) filtered)
            empty-selection? (and (every? some? preflights)
                                  (zero? (reduce + 0 (map :selected preflights))))]
        (if empty-selection?
          (report-empty-selection! run-id opts
                                   {:total (reduce + 0 (map :total preflights))
                                    :searched (vec (mapcat :paths preflights))
                                    :tag-filter (:tag-filter opts)
                                    :cli-paths (:paths opts)
                                    :groups (mapv :label filtered)})
          (let [multi? (> (count filtered) 1)
                ;; sl-n9c6: in a multi-group run the stable --html path is
                ;; the invocation TOC's — per-group stable copies are
                ;; suppressed and planning-failure pages are stashed for the
                ;; epilogue. Single remaining group: byte-identical to today.
                opts (cond-> opts
                       multi? (assoc :suppress-stable-html-copy true
                                     :html-failure-stash (atom [])
                                     ;; sl-xjx0 (Warden W7): one JUnit state
                                     ;; for the whole invocation.
                                     :junit-shared-state
                                     (atom {:scenarios [] :run-ctx nil})))
                results  (vec (map-indexed
                               (fn [idx group]
                                 (run-group! run-id opts config group idx
                                             bus report-opts allow-pending?))
                               filtered))
                exit-code (compute-suite-exit-code (map :exit-code results))]
            (when (and multi? (not (:dry-run opts)))
              (write-invocation-report! run-id opts config results exit-code))
            (cond-> {:exit-code exit-code
                     :run-id    run-id
                     :status    (verdict/exit-code->status exit-code)
                     :groups    results}
              ;; sl-l9nj: the invocation's run dir at the top level too —
              ;; all groups share it (per-group results keep their own copy).
              (some :results-dir results)
              (assoc :results-dir (some :results-dir results)))))))))

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
   - :seed - positive long run seed (sl-08f7); wins over the config mirror
     [:runner :seed]; absent = generated. Recorded (:run/seed, run-ctx
     :seed, HTML header, one stderr line) and handed down (hook payloads,
     step ctx, :start's config arg)
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
        ;; sl-08f7: candidate seed = CLI or generated, resolved BESIDE run-id
        ;; so even pre-config planning failures record one (core-record
        ;; family, always present). An INVALID :seed opt never becomes the
        ;; candidate — its exit-2 record carries a generated one. Config's
        ;; [:runner :seed] folds in after load-config-stage — before
        ;; anything consumes the seed. The atom exists so the crash path
        ;; (outer catch, which only sees THIS let) records the
        ;; post-resolution value, never a stale candidate.
        run-seed-state (atom (or (when-not (seed-opt-error opts) (:seed opts))
                                 (generate-run-seed)))
        opts (assoc opts :run-seed @run-seed-state)
        bus (events/make-memory-bus)
        report-opts (select-keys opts [:verbose :no-color])]
    (binding [wardrobe/*project-context* project-context]
      (try
        ;; sl-yvaz: announce (and, on real runs, clear) a prior run's dirty
        ;; teardown marker before anything else happens.
        (announce-prior-teardown! run-id opts)
        (let [[s1 d1] (if-let [opt-err (or (max-parallel-error opts)
                                           (capture-opt-error opts)
                                           (seed-opt-error opts))]
                        ;; Strict execute!-boundary validation (sl-q9wp,
                        ;; sl-3q9r, sl-08f7): malformed opts ride the
                        ;; planning-error path, like :tag-filter.
                        [:exit (report-planning-error! run-id opts
                                 {:error opt-err
                                  :diagnostics {:errors [opt-err]}
                                  :stderr-msg #(println "Option error:"
                                                        (:message opt-err))})]
                        (load-config-stage run-id opts))]
          (if (= :exit s1) d1
            (let [{:keys [config step-paths allow-pending?]} d1
                  ;; sl-08f7 final seed resolution: CLI > config > candidate.
                  ;; One seed per invocation — threaded via opts to both
                  ;; pipelines, so every group summary records the same one.
                  ;; A malformed config seed inerts with a warning (the
                  ;; bad-config-value posture from resolve-capture), never
                  ;; silently becomes the run's seed.
                  cfg-seed (get-in config [:runner :seed])
                  cfg-seed (if (and (some? cfg-seed)
                                    (not (and (int? cfg-seed) (pos? cfg-seed))))
                             (do (when-not (:edn opts)
                                   (binding [*out* *err*]
                                     (println (str "Config warning: ignoring invalid"
                                                   " [:runner :seed] " (pr-str cfg-seed)
                                                   " — must be a positive integer"))))
                                 nil)
                             cfg-seed)
                  opts (assoc opts :run-seed
                              (or (:seed opts) cfg-seed (:run-seed opts)))
                  _ (reset! run-seed-state (:run-seed opts))
                  _ (print-run-seed-line! (:run-seed opts))
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
                                  pre-filter-total (count all-pickles)
                                  [sf df] (tag-filter-stage run-id opts all-pickles)]
                              (if (= :exit sf)
                                df
                                ;; Empty selection is a planning error
                                ;; (sl-7c7i, vacuity witness): 0-of-N tag
                                ;; narrowing and no-scenario feature files
                                ;; land here; an empty features dir already
                                ;; exits 2 in discovery. Fires in dry-run
                                ;; too (Chair-ratified 08-09): the
                                ;; PLAN-verdict exit 0 requires a nonempty
                                ;; selection.
                                (if (empty? (:all-pickles df))
                                  (report-empty-selection! run-id opts
                                                           {:total pre-filter-total
                                                            :searched feature-paths
                                                            :tag-filter (:tag-filter opts)})
                                  (let [{:keys [all-pickles filtered-count]} df
                                        [s4 d4] (load-steps-stage run-id opts step-paths)]
                                    (if (= :exit s4)
                                      d4
                                      (let [[s5 d5] (compile-suite-stage run-id opts config all-pickles report-opts)]
                                        (if (= :exit s5)
                                          d5
                                          (let [{:keys [plans diagnostics]} d5
                                                ;; sl-sehc: plan-time capture
                                                ;; capability warn, implicit
                                                ;; single group (see
                                                ;; capture-plan-lint-pass!).
                                                plan-lints (capture-plan-lint-pass!
                                                            opts
                                                            (resolve-capture opts config)
                                                            plans config)
                                                diagnostics (cond-> diagnostics
                                                              (seq plan-lints)
                                                              (update :capture-lints
                                                                      (fnil into []) plan-lints))]
                                            (if (:dry-run opts)
                                              (let [exit-code 0]
                                                (when-not (:edn opts)
                                                  (console/print-warnings! diagnostics report-opts)
                                                  (binding [*out* *err*]
                                                    (println (dry-run-console-line (count plans) filtered-count)))
                                                  (print-dry-run-hooks! (dry-run-hooks-entries plans)))
                                                (when (:edn opts)
                                                  (report-edn/prn-summary
                                                   (dry-run-edn-summary run-id (:run-seed opts)
                                                                        plans diagnostics nil filtered-count)))
                                                {:exit-code exit-code
                                                 :run-id run-id
                                                 :status :dry-run
                                                 :plans plans})
                                              (execute-and-report-stage run-id opts bus config features
                                                                        all-pickles filtered-count plans diagnostics
                                                                        allow-pending? report-opts))))))))))))))))))))
        ;; Catch any uncaught exceptions → exit 3
        (catch Throwable t
          (let [exit-code 3
                error {:type :runner/crash
                       :message (ex-message t)
                       :exception-class (.getName (class t))}]
            (when (:edn opts)
              (report-edn/prn-summary
               (report-edn/build-summary run-id exit-code nil
                                         {:error error
                                          :seed @run-seed-state})))
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
