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
   - 3: Runner crash (uncaught throwable in ShiftLefter's own code)
   - 4: Run degraded — >=1 scenario :error (infrastructure-failure family)

   Canonical table + precedence: shiftlefter.runner.verdict."
  (:require [babashka.fs :as fs]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [shiftlefter.adapters.registry :as adapters]
            [shiftlefter.attachments :as attachments]
            [shiftlefter.gherkin.api :as api]
            [shiftlefter.gherkin.io :as io]
            [shiftlefter.costume.wardrobe :as wardrobe]
            [shiftlefter.project-context :as project-context]
            [shiftlefter.config :as config]
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
            [shiftlefter.stepengine.exec.abort :as abort]
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
   of the execution result). Execution worst-wins 4 > 1 > 0 (sl-7c7i).

   The ladder: 0 and 1 are verdicts about the app; 4 is the run failing to
   be a measurement mid-run (2 = pre-flight, 3 = crash — never from here).
   Two disjoint count buckets reach 4 (sl-kowi): :error (the SL-machinery
   family — hook throw, capability provisioning, capture: fix the
   harness) and :unobserved (a step classified :observation — the
   witness between spec and app went dark: check the environment). Either
   makes the run DEGRADED — it proves nothing about the app for those
   scenarios, so 4 subsumes 1: you must rerun regardless of the visible
   failures. Exit 1 is reserved for scenario :failed (valid tests, app
   wrong) and for pending under strict mode. See
   shiftlefter.runner.verdict for the canonical table.

   An ABORTED run (sl-617t: `:halted {:reason :aborted}`) is 4 too —
   never 130 (outside the contract), never 0 (it is not the measurement
   asked for), not 3 (nothing crashed): the scenarios that ran keep their
   verdicts, the rest were held, so the run as asked was not measured.

   XPASS (sl-190x, Chair 09-17): a @blocked scenario that PASSED is exit
   1 — a stale claim about the app is a wrong test, still a failing
   corpus; the scenario is :failed (counted there) and :xpass marks it.
   Held scenarios (a @blocked failure as claimed) are in no verdict
   bucket and never move the code."
  [exec-result allow-pending?]
  (let [{:keys [failed pending error unobserved xpass]} (:counts exec-result)]
    (cond
      (= :aborted (get-in exec-result [:halted :reason])) 4
      (or (pos? (or error 0)) (pos? (or unobserved 0))) 4
      (pos? (or xpass 0)) 1
      (pos? (or failed 0)) 1
      (and (pos? (or pending 0)) (not allow-pending?)) 1
      :else 0)))


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
    (update :glossaries #(config/resolve-glossary-config-paths project-context %))))

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
               (not (s/valid? :shiftlefter.config/capture c)))
      {:type :capture/invalid
       :message (str "Invalid :capture " (pr-str c) " — "
                     (s/explain-str :shiftlefter.config/capture c))})))

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

(defn- crash-probe-reporter
  "sl-8orx proof instrument (`--sl-internal-crash=SUBSTRING`, undocumented —
   the sl-7wv internal-flag convention): a Reporter whose
   on-scenario-complete THROWS for the first envelope whose printed form
   contains `substring`. Every value crossing the reporter seam is pure EDN
   data (invariant 3), so pr-str matching is shape-agnostic. Appended LAST
   in the reporter list, so JUnit/HTML have already recorded the marker
   scenario when the coordinator crashes — the exact shape of a report-plane
   loud failure mid-group, reachable from a cold `sl run`."
  [substring]
  (reify reporter/Reporter
    (on-run-start [_ _] nil)
    (on-scenario-complete [_ scenario-result]
      (when (str/includes? (pr-str scenario-result) substring)
        (throw (ex-info (str "sl-internal-crash: deliberate coordinator crash"
                             " at scenario matching " (pr-str substring))
                        {:internal-crash-on substring})))
      nil)
    (on-diagnostics [_ _] nil)
    (on-run-end [_ _] nil)))

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
        html-path (resolve-html-path opts config)
        junit-reporter (when junit-path
                         (report-junit/make-reporter
                          ;; sl-xjx0 (Warden W7): multi-group runs share one
                          ;; invocation-scoped state so the invocation-fixed
                          ;; XML aggregates across groups instead of
                          ;; last-writer-wins.
                          (cond-> {:path junit-path}
                            (:junit-shared-state opts)
                            (assoc :shared-state (:junit-shared-state opts)))))
        html-primary (when (and html-path group-root)
                       (str (fs/path group-root "report.html")))
        html-reporter (when html-path
                        (report-html/make-reporter
                         ;; sl-n9c6: in a multi-group run the stable path
                         ;; belongs to the invocation TOC — the per-group
                         ;; byte copy is suppressed (path = primary makes
                         ;; the copy a same-file no-op). Without a primary
                         ;; the group write still lands at the stable path;
                         ;; the TOC epilogue overwrites it last.
                         {:path (if (and (:suppress-stable-html-copy opts) html-primary)
                                  html-primary
                                  html-path)
                          :primary html-primary}))]
    ;; sl-o26v: record the surfaces the crash epilogue writes for THIS
    ;; run-scope — the paths and both reporters' own state atoms (the
    ;; records' fields; under a shared JUnit state that IS the shared
    ;; atom). Under setup.clj each group's call overwrites the html half
    ;; with the running group's; the multi-group path never reads it.
    (when-let [surfaces (:crash-surfaces opts)]
      (swap! surfaces merge
             (cond-> {:opts opts :config config}
               junit-reporter (assoc :junit-path junit-path
                                     :junit-state (:state junit-reporter))
               html-reporter (assoc :html-path html-path
                                    :html-primary html-primary
                                    :html-state (:state html-reporter)))))
    (cond-> base
      junit-reporter (conj junit-reporter)
      html-reporter (conj html-reporter)
      ;; sl-8orx: the internal crash probe rides last (see its docstring).
      (:internal-crash-on opts)
      (conj (crash-probe-reporter (:internal-crash-on opts))))))

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
   the JUnit reporter (sl-40to) reads :version (properties),
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
                                 (cond-> (assoc error-info :seed (:run-seed opts))
                                   ;; sl-pwbp: the ONE stamping seam for every
                                   ;; per-group exit-2 — run-group! hoists
                                   ;; identity onto opts; suite-level planning
                                   ;; errors (pre-group-loop) carry neither
                                   ;; the flag nor the label, so their lone
                                   ;; summary stays identity-free.
                                   (:multi-group? opts)
                                   (assoc :group-label (:group-label opts)
                                          :group-index (:group-index opts))))))
    (when (and (not (:edn opts)) (:stderr-msg error-info))
      (binding [*out* *err*]
        ((:stderr-msg error-info))))
    {:exit-code exit-code
     :run-id run-id
     :status :planning-failed}))

(defn- with-config-def-site
  "sl-u2r0 (Fable 7): every config lint names the file it was found in —
   `:def-site {:file config-path}` — so the warning's did-you-mean and its
   def-site travel together (the lint itself is path-free by design)."
  [lints config-path]
  (if config-path
    (mapv #(assoc % :def-site {:file config-path}) lints)
    lints))

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
   stable output.

   Shape-safe (sl-a7sx, Warden W-B2): a non-map :interfaces or a non-map
   entry is normalize's error to name (:config/invalid-interface(s)); this
   probe returns [] for them instead of throwing on the destructure."
  [config]
  (let [interfaces (config/get-interfaces config)]
    (if-not (map? interfaces)
      []
      (->> interfaces
           (sort-by (comp name key))
           (into []
                 (keep (fn [[iface-name iface-cfg]]
                         (when (map? iface-cfg)
                           (adapters/adapter-unavailable-error
                            iface-name (:adapter iface-cfg))))))))))

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
            ;; normalize's errors come FIRST (sl-a7sx, Warden W-B2): the
            ;; shape errors it names must be reported before anything
            ;; iterates :interfaces — the old eager into-arg order ran the
            ;; adapter probe first, which crashed on a non-map value before
            ;; :config/invalid-interfaces was ever consumed.
            config-errs (into (vec (:errors (config/normalize config)))
                              (adapter-availability-errors config))
            lints (with-config-def-site (config/lint-config config)
                                        (:config-path project-context))]
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

(defn- loaded-intents
  "The intents stage 1b loaded into the registry, or nil (none configured,
   or a load failure already reported) — what the bindings lint resolves
   matcher-slot shape refs against (sl-ectw)."
  []
  (try (intent-state/get-intents) (catch Exception _ nil)))

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
                        ;; sl-mk0t (Warden W2): every sub-error carries the
                        ;; file it came from and, when the index pass vouched
                        ;; for it, the element's line — render the def-site
                        ;; in the sl-u2r0 form instead of dropping it.
                        :stderr-msg #(do (println "Intents load error:" (ex-message e))
                                         (doseq [{:keys [message path line]} (:errors (ex-data e))]
                                           (println " " message)
                                           (when path
                                             (println "    Declared at:"
                                                      (str path (when line (str ":" line)))))))})])))))))

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

(defn- print-tag-lint-notices!
  "One stderr line per tag lint (sl-190x): a bare @skip / @blocked, a
   valued @serial, a malformed tag. The config-lint notice pattern —
   suppressed under --edn (the envelope carries them as :tag-lints),
   warnings only, never the exit code."
  [opts lints]
  (when (and (seq lints) (not (:edn opts)))
    (binding [*out* *err*]
      (doseq [{:keys [message]} lints]
        (println (str "Tag warning: " message))))))

(defn- tag-held-entry
  "The :held record for a scenario a reserved tag held at planning
   (sl-190x): the 6wh9 shape (:scenario :feature :uri :line :reason) with
   the tag's own facts — `:tag`, and `:note` (@wip/@skip) or `:ref`
   (@blocked) when the tag carried a value."
  [{:keys [pickle hold]}]
  (merge {:scenario (:pickle/name pickle)
          :feature (:pickle/feature-name pickle)
          :uri (:pickle/source-file pickle)
          :line (get-in pickle [:pickle/location :line])}
         (dissoc hold :location)))

(defn- tag-filter-stage
  "Stage 4b (sl-i608): planning-time tag filtering through the disposition
   seam. Filtered-out pickles never reach compile-suite-stage — never bound,
   never counted. :filtered-count is nil when no filter is active (so
   dry-run output can distinguish 'filter selected N' from 'project has
   N'), else the count.

   sl-190x: the seam ALWAYS runs — nil :tag-filter filters nothing, but a
   @wip / @skip hold still deselects (unless its tag is named in --tags):
   those pickles ride `:tag-held` as :held entries for every surface, and
   the authored-tag lints ride `:tag-lints`. Without holds or lints the
   output is byte-identical to before.

   sl-mk0t (Warden W7, Chair-ruled 10-19): the lints walk the SELECTION,
   not the corpus — a pickle the rules filtered OUT is not this run's
   business, so its bare @skip no longer warns on every `--tags` run that
   excludes it. Held pickles ARE in the selection (a hold deselects
   inside the run's story — it is counted and recorded), so the
   reason-required lint on a selected-but-held @skip still fires. A
   corpus-wide authored-tag census is an orient/fmt design question
   (0.5.8), not run output. `:tag-lints` on the EDN surface narrows to
   match.
   Returns [:continue {:all-pickles [...] :filtered-count (nilable int)
   :tag-held [...] :tag-lints [...]}] or [:exit error-result]."
  [run-id opts all-pickles]
  (let [rules (:tag-filter opts)
        err (when rules (tagd/rules-error rules))]
    (if err
      [:exit (report-planning-error! run-id opts
               {:error err
                :diagnostics {:errors [err]}
                :stderr-msg #(println "Tag filter error:" (:message err))})]
      (let [{:keys [selected filtered-out held]} (tagd/apply-dispositions rules all-pickles)
            dropped (set filtered-out)
            lints (reporter/scrub (into []
                                        (comp (remove dropped)
                                              (mapcat tagd/tag-lints))
                                        all-pickles))]
        (when rules
          (print-tag-filter-notice! opts (count selected) (count filtered-out)))
        (print-tag-lint-notices! opts lints)
        [:continue {:all-pickles selected
                    :filtered-count (when rules (count filtered-out))
                    :tag-held (mapv tag-held-entry held)
                    :tag-lints lints}]))))

(defn- empty-selection-error
  "The vacuity witness (sl-7c7i): a selection of zero scenarios is a
   planning error — a run that selects nothing cannot claim green, and
   exit 0 is existential (>=1 scenario selected). No escape knob (Chair
   ruling 08-07): the escape is DATA — the :data block carries what a CI
   wrapper needs to make its own choice upstream (sl-78md item 12,
   our-data-your-choices): what was searched, the active filter, the CLI
   narrowing, and how many scenarios existed before narrowing."
  [{:keys [total searched tag-filter groups cli-paths held]}]
  (let [total (or total 0)]
    {:type :selection/empty
     :message (str "Empty selection: 0 of " total " scenario(s) selected"
                   (when (seq searched)
                     (str " (searched: " (str/join ", " searched) ")"))
                   (when tag-filter
                     (str " (tag filter: " (pr-str tag-filter) ")"))
                   ;; sl-190x: holds are part of the selection story —
                   ;; every-scenario-held is this same exit 2 (exit 0 is
                   ;; existential), and the count says so.
                   (when (pos? (or held 0))
                     (str " (" held " held by @wip/@skip tags)"))
                   (when (seq cli-paths)
                     (str " (CLI paths: " (str/join ", " cli-paths) ")"))
                   (when (seq groups)
                     (str " (groups: " (str/join ", " (map pr-str groups)) ")"))
                   " — nothing would run, so nothing can pass")
     :data (cond-> {:selected 0 :total total}
             (seq searched) (assoc :searched (vec searched))
             tag-filter     (assoc :tag-filter tag-filter)
             (pos? (or held 0)) (assoc :held held)
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

(defn- bind-pass
  "One binding pass over `pickles` (sl-6wh9 — the compile half of
   compile-suite-stage, factored so `--hold-unplannable` can run it twice:
   once to classify, once over the kept selection). Returns compile-suite's
   {:plans :runnable? :diagnostics} with the config/glossary lints attached.

   `opts` may carry `:adapter-registry`; it's forwarded to the binder so
   capability gating (sl-ewn) honors a setup.clj-supplied custom registry."
  [opts config pickles]
  (let [stepdefs (registry/all-stepdefs)
        config-path (:config-path (:project-context opts))
        compile-opts (cond-> {}
                       (:adapter-registry opts) (assoc :adapter-registry (:adapter-registry opts))
                       ;; sl-u2r0: the def-site an unknown-interface issue
                       ;; names, and the file every config lint names.
                       config-path (assoc :config-path config-path))
        {:keys [plans runnable? diagnostics]}
        (compile/compile-suite config pickles stepdefs (not-empty compile-opts))
        ;; sl-lnj1: config-lint warnings join the diagnostics map here — the
        ;; one point both pipelines share — so every downstream output (EDN
        ;; summary 0/1/2, dry-run, reporter on-diagnostics channel) carries
        ;; them. Re-linting is a set-membership check over ~7 keys; cheaper
        ;; than threading state from load-config-stage. Scrubbed AT ATTACH:
        ;; a lint's :key holds the offending keyword itself, which can be
        ;; non-round-tripping (sl-27uh class), and the exit-2 path prints
        ;; diagnostics without the reporter-envelope scrub. Absent key when
        ;; clean — all existing output stays byte-identical.
        diagnostics (let [lints (with-config-def-site (config/lint-config config) config-path)]
                      (cond-> diagnostics
                        (seq lints) (assoc :config-lints (reporter/scrub lints))
                        ;; sl-67dj (Warden P3-13): glossary-load warnings
                        ;; arrive from compile-suite already in the map;
                        ;; scrub at attach like the config-lints twin.
                        (seq (:glossary-lints diagnostics))
                        (update :glossary-lints reporter/scrub)))
        ;; sl-74c2: the WHOLE map, once, for the same reason the two lint
        ;; keys were — the exit-2 planning block and the dry-run summary
        ;; read these diagnostics raw (no reporter envelope), and an
        ;; :svo-issues :subject was a user capture keywordized verbatim
        ;; ((keyword ":user"), printed ::user, never re-readable). Now every
        ;; downstream reader gets pure data; the executed path's envelope
        ;; scrub then takes the identity fast path — byte-identical.
        diagnostics (reporter/scrub diagnostics)]
    {:plans plans :runnable? runnable? :diagnostics diagnostics}))

(defn- real-steps
  "A plan's bound steps minus macro wrappers (synthetic steps bind to
   nothing and must not count as undefined-or-not)."
  [plan]
  (remove #(= :synthetic (:status %)) (:plan/steps plan)))

(defn- classify-plans
  "Per-scenario plannability (sl-6wh9), computed from the FIRST bind pass:
   a scenario would exit 2 for a scenario-level reason when its plan is
   not `:plan/runnable?` (an undefined / ambiguous / invalid-arity step or
   an unresolved instance) or the data-plane static check finds a
   blocking issue in it (consumed-without-producer) — hooks are attached
   first because hook :provides declarations count as producers. Warn-
   or error-level enforcement issues (unknown vocabulary) are NOT here:
   those are the vocabulary-contract class, the typo the machine must
   never silence. Returns one map per plan:
   {:plan p :plannable? bool :undefined n :ambiguous n :binding-issues [...]}
   — :binding-issues is the plan's data-plane lint result, kept rather than
   discarded (sl-8ezu, Warden W1): the first planning exit reports it beside
   the undefined/SVO issues, and a --hold-unplannable held entry names it."
  [plans hooks-info]
  (let [hooked (or (:ok (hooks/attach-hooks plans (:registry hooks-info)
                                            (:path hooks-info)))
                   plans)
        intents (loaded-intents)]
    (mapv (fn [plan]
            (let [steps (real-steps plan)
                  status-count (fn [s] (count (filter #(= s (:status %)) steps)))
                  issues (bindings-lint/check-plans [plan] intents)]
              {:plan plan
               :plannable? (and (:plan/runnable? plan)
                                (not (bindings-lint/blocking? issues)))
               :undefined (status-count :undefined)
               :ambiguous (status-count :ambiguous)
               :binding-issues issues}))
          hooked)))

(defn ref-url
  "Render the tracker link for a @blocked ref (sl-190x) from the config's
   `[:runner :ref-url]` template — `{ref}` substituted, nothing fetched.
   nil when no template is configured or the hold carries no ref."
  [config ref]
  (when-let [template (and ref (get-in config [:runner :ref-url]))]
    (str/replace template "{ref}" ref)))

(defn- attach-holds
  "Assoc :plan/hold onto each plan whose pickle carries a hold that RUNS
   (@blocked, sl-190x) — the never-run holds were deselected at stage 4b
   and have no plan. The facet gains :ref-url when the config renders one.
   Plans without a running hold are returned untouched (no nil key)."
  [plans config]
  (mapv (fn [plan]
          (let [hold (:hold (tagd/disposition nil (:plan/pickle plan)))]
            (if (and hold (not (tagd/never-run-hold? hold)))
              (assoc plan :plan/hold
                     (cond-> (dissoc hold :location)
                       (ref-url config (:ref hold))
                       (assoc :ref-url (ref-url config (:ref hold)))))
              plan)))
        plans))

(defn- held-entry
  "The :held record for one scenario --hold-unplannable deselected
   (sl-6wh9). sl-190x's tag holds (`tag-held-entry`) and the engine's
   holds (`engine-held-entry`) share the shape: :scenario :feature :uri
   :line :reason, plus the kind's own facts. :binding-errors (sl-8ezu) names
   the data-plane lint errors that held the scenario — `[{:type :def-site}]`
   in step order, :def-site when the issue carries one — because under the
   flag those issues never reach :svo-issues, so the entry is the only EDN
   surface that can carry their site. Absent when none held it."
  [{:keys [plan undefined ambiguous binding-issues]}]
  (let [pickle (:plan/pickle plan)
        errors (into [] (comp (filter #(= :error (:severity %)))
                              (map #(select-keys % [:type :def-site])))
                     binding-issues)]
    (cond-> {:scenario (:pickle/name pickle)
             :feature (:pickle/feature-name pickle)
             :uri (:pickle/source-file pickle)
             :line (get-in pickle [:pickle/location :line])
             :reason :unplannable
             :undefined undefined
             :ambiguous ambiguous}
      (seq errors) (assoc :binding-errors errors))))

(defn- unshifted-suite-facts
  "sl-6wh9: the degenerate planning result. When EVERY selected step is
   undefined the suite reads as unshifted (nobody writes Cucumber stepdefs
   in Clojure, so a vanilla corpus binds to nothing) — return the
   inventory {:features N :scenarios M :distinct-steps K}; nil otherwise.
   The trigger is exactly all-undefined (Chair ruling 09-11): a glossary's
   existence says nothing about the suite."
  [plans]
  (let [steps (mapcat real-steps plans)]
    (when (and (seq steps) (every? #(= :undefined (:status %)) steps))
      {:features (count (distinct (map #(get-in % [:plan/pickle :pickle/source-file]) plans)))
       :scenarios (count plans)
       :distinct-steps (count (distinct (map #(get-in % [:step :step/text]) steps)))})))

(defn- plannability-facts
  "sl-6wh9: the mixed-state fact for the exit-2 hint —
   {:plannable N :unplannable M :total T} when SOME scenarios plan and
   some do not; nil when all or none do (the all case is a run, the none
   case is the summary line / an ordinary exit 2)."
  [classified]
  (let [plannable (count (filter :plannable? classified))
        total (count classified)]
    (when (< 0 plannable total)
      {:plannable plannable :unplannable (- total plannable) :total total})))

(defn- merge-bindings-issues
  "Join data-plane lint issues onto the diagnostics map (sl-yh7): they ride
   :svo-issues (same shape, severity, reporting) and bump the issue counts.
   Identity on an empty seq, so a clean suite's diagnostics are untouched."
  [diagnostics bindings-issues]
  (cond-> diagnostics
    (seq bindings-issues)
    (-> (update :svo-issues (fnil into []) bindings-issues)
        (update-in [:counts :svo-issue-count] (fnil + 0) (count bindings-issues))
        (update-in [:counts :total-issues] (fnil + 0) (count bindings-issues)))))

(defn- planning-failed-exit!
  "The stage-6 exit-2 envelope (sl-6hsd HTML, console diagnostics + the
   sl-6wh9 summary line / hint, EDN summary) — shared by the binding gate
   and the bindings-lint gate."
  [run-id opts config all-pickles diagnostics report-opts]
  (let [exit-code 2]
    (write-planning-failure-html! run-id opts config all-pickles diagnostics)
    (when-not (:edn opts)
      (console/print-diagnostics! diagnostics report-opts)
      (console/print-unshifted-suite! diagnostics report-opts)
      (console/print-plannability-hint! diagnostics report-opts))
    (when (:edn opts)
      (report-edn/prn-summary
       (report-edn/build-summary run-id exit-code nil
                                 {:diagnostics diagnostics
                                  :seed (:run-seed opts)})))
    [:exit {:exit-code exit-code
            :run-id run-id
            :status :planning-failed
            :diagnostics diagnostics}]))

(defn- compile-suite-stage
  "Stage 6: Compile suite (bind all pickles to stepdefs).
   Returns [:continue {:plans [...] :diagnostics {...}}] or [:exit error-result].

   `opts` may carry `:adapter-registry` (see `bind-pass`) and
   `:hooks-registry` ({:registry [...] :path ...} from load-hooks-stage,
   sl-esq); hook applicability resolves here, riding the plan as the
   additive :plan/hooks key.

   sl-6wh9 — one mode, and the planning RESULT tells the migration story:
   the first bind pass classifies every scenario (`classify-plans`). Then
   - every step undefined → the :unshifted-suite inventory rides the
     diagnostics (the summary line on console, a top-level EDN key);
   - some scenarios plan, some do not → :plannability rides the exit-2
     diagnostics (the console hint naming --hold-unplannable); never on a
     structural exit 2 (config/glossary/hooks/suite-lint errors yield no
     plans, so nothing is classified);
   - `:hold-unplannable` (CLI only, per run) with a mixed selection →
     the plannable pickles are bound AGAIN as the selection (deselected
     at planning like tag narrowing: never run) and the held scenarios
     ride `:held` on the diagnostics for every surface; when nothing
     plans the first pass exits 2 as if the flag were absent.

   sl-8ezu (Warden W1): the first pass's data-plane lint result (computed in
   `classify-plans`, hook-attached) joins the diagnostics on the runnable?
   exit, so an undefined step or an SVO :error no longer hides every
   :bindings/* error behind it. On the runnable path the post-hook lint
   below is still the one that merges (it sees the plans that will run);
   under the flag the held scenarios' errors ride their held entries, never
   the kept run's :svo-issues."
  [run-id opts config all-pickles report-opts {:keys [tag-held tag-lints]}]
  (let [first-pass (bind-pass opts config all-pickles)
        classified (when (seq (:plans first-pass))
                     (classify-plans (:plans first-pass) (:hooks-registry opts)))
        held (filterv (complement :plannable?) classified)
        kept (filterv :plannable? classified)
        hold? (boolean (and (:hold-unplannable opts) (seq held) (seq kept)))
        {:keys [plans runnable? diagnostics]}
        (if hold?
          (bind-pass opts config (mapv (comp :plan/pickle :plan) kept))
          first-pass)
        diagnostics (cond-> diagnostics
                      ;; sl-190x: the tag holds (never bound, held at
                      ;; stage 4b) lead the ONE :held record; the flag's
                      ;; unplannable holds follow; the authored-tag lints
                      ;; ride beside the config lints.
                      (seq tag-held) (assoc :held tag-held)
                      (seq tag-lints) (assoc :tag-lints tag-lints)
                      hold? (update :held (fnil into []) (mapv held-entry held))
                      (not hold?)
                      (as-> d
                            (let [unshifted (unshifted-suite-facts (:plans first-pass))
                                  mixed (plannability-facts classified)]
                              (cond-> d
                                unshifted (assoc :unshifted-suite unshifted)
                                mixed (assoc :plannability mixed)))))]
    ;; Glossary-lint stderr notices (sl-67dj) — the print-config-lint-notices!
    ;; pattern: one line per warning, suppressed under :edn (the EDN envelope
    ;; carries them as :glossary-lints), warnings only, never the exit code.
    (when (and (seq (:glossary-lints diagnostics)) (not (:edn opts)))
      (binding [*out* *err*]
        (doseq [{:keys [message]} (:glossary-lints diagnostics)]
          (println (str "Glossary warning: " message)))))
    (if-not runnable?
      (planning-failed-exit! run-id opts config all-pickles
                             (merge-bindings-issues
                              diagnostics
                              (into [] (mapcat :binding-issues) classified))
                             report-opts)
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
          (let [bindings-issues (bindings-lint/check-plans plans' (loaded-intents))
                diagnostics (merge-bindings-issues diagnostics bindings-issues)]
            (if (bindings-lint/blocking? bindings-issues)
              (planning-failed-exit! run-id opts config all-pickles diagnostics report-opts)
              ;; sl-q9wp: the scheduling facet rides the compiled plan
              ;; (additive :plan/schedule key). Inert at :max-parallel 1 —
              ;; the scheduler runs every plan inline and never reads it.
              ;; sl-190x: the @blocked hold rides the same way
              ;; (additive :plan/hold key) for the engine's inversion.
              [:continue {:plans (-> plans'
                                     (schedule/attach-schedules (:interfaces config))
                                     (attach-holds config))
                          :diagnostics diagnostics}])))))))

(declare dry-run-hooks-entries)

(defn- dry-run-edn-summary
  "Build the EDN summary for a dry-run success, including warn-level SVO
   diagnostics that would otherwise be dropped (sl-qk8l). `group-label`
   is non-nil on the setup-group path only. `filtered-count` is non-nil only
   when a tag filter is active (sl-i608): the additive :filtered-out key makes
   the dry-run preview distinguish 'filter selected N' from 'project has N';
   without a filter the summary is byte-identical to before.
   `seed` (sl-08f7): the run seed — core-record family, always stamped.
   `reports-not-written` (sl-a7sx, Warden W23): the [kind path] pairs from
   dry-run-reports-not-written — when non-empty the summary carries the
   additive :run/reports-not-written vector of KINDS (e.g. [:html
   :junit-xml]); absent otherwise, so report-less dry-run output is
   byte-identical to before."
  [run-id seed plans diagnostics group-label filtered-count reports-not-written]
  (cond-> {:run/id run-id
           :run/seed seed
           :run/exit-code 0
           :run/status :dry-run
           :counts (cond-> {:scenarios (count plans)
                            :steps (reduce + (map #(count (:plan/steps %)) plans))}
                     ;; sl-6wh9: --hold-unplannable's held scenarios
                     (seq (:held diagnostics))
                     (assoc :held (count (:held diagnostics))))}
    (seq (:held diagnostics)) (assoc :held (:held diagnostics))
    group-label (assoc :group group-label)
    filtered-count (assoc :filtered-out filtered-count)
    (seq reports-not-written)
    (assoc :run/reports-not-written (mapv first reports-not-written))
    ;; sl-esq AC9: per-scenario hook firing lists — additive, absent for
    ;; hook-less suites so the summary stays byte-identical.
    (dry-run-hooks-entries plans)
    (assoc :hooks (dry-run-hooks-entries plans))
    (report-edn/execution-diagnostics diagnostics)
    (assoc :diagnostics (report-edn/execution-diagnostics diagnostics))
    ;; sl-xvae: the machine plan — scenario → step → :step/resolved. Always
    ;; present on a dry-run summary (last, so earlier keys keep their order).
    true
    (assoc :plan (report-edn/dry-run-plan plans))))

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

(defn- dry-run-reports-not-written
  "The reports a dry run will NOT write (sl-a7sx, Warden W23 — Chair
   ruling 09-03, reversing sl-7dq5 F8's exit-2 refusal): a vector of
   [kind path] pairs, :html then :junit-xml, resolved flag-OR-config
   exactly as the live run resolves them (resolve-html-path /
   resolve-junit-path), so the config mirror `sl init` scaffolds gets the
   same notice the CLI flags do. nil when no report is declared. Computed
   ONCE per invocation in execute! and threaded via opts as
   :dry-run/reports-not-written."
  [opts config]
  (not-empty
   (cond-> []
     (resolve-html-path opts config)
     (conj [:html (resolve-html-path opts config)])
     (resolve-junit-path opts config)
     (conj [:junit-xml (resolve-junit-path opts config)]))))

(defn- print-dry-run-reports-notice!
  "ONE stderr line naming what the dry run did not write — printed once
   per invocation (after the classic pipeline's dry-run line, or after the
   setup-group loop), console mode only: under --edn the summary's
   :run/reports-not-written key is the signal. Prints nothing when no
   report was declared."
  [reports-not-written]
  (when (seq reports-not-written)
    (binding [*out* *err*]
      (println (str "dry run: report(s) not written — "
                    (str/join ", " (map (fn [[kind path]]
                                          (str (name kind) " " path))
                                        reports-not-written)))))))

(defn- engine-held-entries
  "Run-level :held entries for the scenarios the ENGINE held (sl-617t's
   abort, sl-190x's @blocked-failed-as-claimed): the scenario's own :held
   record with the 6wh9 locator keys, in plan order. Empty when none."
  [scenarios]
  (into []
        (keep (fn [{:keys [held plan]}]
                (when held
                  (let [pickle (:plan/pickle plan)]
                    (merge {:scenario (:pickle/name pickle)
                            :feature (:pickle/feature-name pickle)
                            :uri (:pickle/source-file pickle)
                            :line (get-in pickle [:pickle/location :line])}
                           held)))))
        scenarios))

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
        ;; sl-617t: the abort's immediate line names where reports land.
        _ (when results-dir (abort/register-results-dir! results-dir))
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
          ;; `run`) plus :provisioning (step_loop's :eager gate),
          ;; :max-parallel (exec's parallel dispatch) and
          ;; :halt-on-observation-failure (exec's halt gate, sl-kowi), which
          ;; repl omits ON PURPOSE: repl/run is always lazy + sequential
          ;; (sl-vmc5) and single-scenario (nothing to halt) — and, sixth,
          ;; :halt-gate (sl-78u4: the SHARED gate atom under :run scope,
          ;; threaded from run-with-setups!; repl omits it for the same
          ;; reason). A new engine-consumed exec-opt added here must be
          ;; hand-mirrored there — or its omission recorded there — and
          ;; vice versa.
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
                             ;; sl-q9wp: scenario parallelism bound (1 = every
                             ;; scenario in plan order on this thread, no pool).
                             :max-parallel max-parallel
                             ;; sl-kowi (Ruling 4): run-level halt policy,
                             ;; config-only, default off.
                             :halt-on-observation-failure
                             (config/halt-on-observation-failure? config)
                             ;; sl-dgk: report + observe planes fed per-completion.
                             :on-scenario-complete (on-scenario-complete-callback bus run-id release!)
                             ;; sl-io05: a throw escaping the per-scenario
                             ;; machinery is a runner crash — the engine
                             ;; names the scenario here first, and the one
                             ;; crash epilogue (sl-o26v) reads it back so
                             ;; the crash report can say WHERE.
                             :on-scenario-crash
                             (fn [scenario]
                               (when-let [surfaces (:crash-surfaces opts)]
                                 (swap! surfaces assoc :crashed-scenario scenario)))}
                      (:adapter-registry opts) (assoc :adapter-registry (:adapter-registry opts))
                      ;; sl-io05 proof instrument: the per-scenario escape
                      ;; probe (stepengine/exec/cleanup throws on a match).
                      (:internal-scenario-crash-on opts)
                      (assoc :internal-scenario-crash-on (:internal-scenario-crash-on opts))
                      ;; sl-78u4 (Warden W3): the invocation-wide halt gate,
                      ;; present only under :run scope in a setup.clj run.
                      (:halt-gate opts) (assoc :halt-gate (:halt-gate opts))
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
          ;; sl-617t (fold-in C1, P1): in a SINGLE-SCOPE run (no setup.clj)
          ;; the suite's return is where the final writes begin — a cold
          ;; second strike from here on is a no-op. Under setup.clj the
          ;; invocation surfaces are still live (the JUnit re-materializes
          ;; per group end; the TOC is written after the group loop), so
          ;; run-with-setups! raises the flag before ITS final writes.
          _ (when (and (abort/requested?) (nil? (:group-label opts)))
              (abort/finalizing!))
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
            ;; sl-7dq5 F11b: the group run dir, config-root-relative — the
            ;; envelope's pointer from claim to artifacts (an agent should
            ;; never map them by mtime guessing). Always under config-root
            ;; by construction (results-root resolves against it); nil when
            ;; no results dir exists (:defaults context).
            results-path (when group-root
                           (attachments/relativize-under
                            (:config-root (:project-context opts))
                            (str group-root)))
            ;; The ONE :held record (sl-6wh9, sl-617t, sl-190x): scenarios
            ;; held at planning (a @wip/@skip tag, --hold-unplannable —
            ;; never run) lead, then the scenarios the engine held (the
            ;; abort, a @blocked scenario that failed as claimed) in plan
            ;; order. Absent when nothing was held.
            planning-held (:held diagnostics)
            held (not-empty (into (vec planning-held)
                                  (engine-held-entries (:scenarios exec-result))))
            ;; `update`, not `assoc`: the engine already counts ITS held
            ;; scenarios (sl-617t, sl-190x) — one :held bucket.
            counts (cond-> (:counts exec-result)
                     (seq planning-held)
                     (update :held (fnil + 0) (count planning-held)))
            run-summary (cond-> {:run-id run-id
                                 ;; sl-08f7: core-record family — every
                                 ;; run-scope's summary carries the one
                                 ;; invocation seed.
                                 :seed (:run-seed opts)
                                 :exit-code exit-code
                                 :counts counts
                                 :status (verdict/exit-code->status exit-code)}
                          ;; Absent-when-zero (anomaly-marker convention,
                          ;; Chair re-ratified 08-10): presence IS the signal.
                          (pos? dropped) (assoc :dropped-events dropped)
                          (seq held) (assoc :held held)
                          results-path (assoc :results-path results-path)
                          ;; sl-kowi: the halt record — present only when
                          ;; :halt-on-observation-failure fired.
                          (:halted exec-result) (assoc :halted (:halted exec-result))
                          ;; sl-pwbp: group identity — multi-group runs
                          ;; only (absent-unless-multi keeps single-group
                          ;; summaries byte-identical). The EDN reporter
                          ;; threads these into the summary's :group/label
                          ;; + :group/index; other reporters ignore them.
                          (:multi-group? opts)
                          (assoc :group-label (:group-label opts)
                                 :group-index (:group-index opts)))]
        (emit-lifecycle! ctx :run-end run-summary nil)
        (cond-> {:exit-code exit-code
                 :run-id run-id
                 :status (verdict/exit-code->status exit-code)
                 :counts counts
                 ;; The RAW exec-result, deliberately: envelopes are a projection
                 ;; for the report/observe planes only; programmatic callers keep
                 ;; full fidelity.
                 :result exec-result}
          (pos? dropped) (assoc :dropped-events dropped)
          (seq held) (assoc :held held)
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

(defn- skipped-group-result
  "The result for a setup group that never STARTED because the invocation
   halted under `:halt-on-observation-failure :run` (sl-78u4, Warden W3)
   or was aborted (sl-617t, `{:reason :aborted ...}`): `:status :skipped`
   — the same word with the same meaning as a skipped scenario or step
   (did not run) — no `:exit-code` (no verdict exists), and the gate's
   record as `:halted-by`. Its `:start` never ran: a dark environment
   gets no more setups, and neither does an aborted run."
  [group halt-record]
  {:group-label (or (:label group) "<unlabeled>")
   :status :skipped
   :halted-by halt-record})

(defn- ran-count
  "How many of a group's scenarios reached a verdict."
  [counts]
  (reduce + (map #(get counts % 0) [:passed :failed :pending :error])))

(defn- invocation-abort-record
  "sl-617t: the invocation-level `:halted` record while an abort is
   requested, from the group results landed so far — the aborted group's
   own record plus its label when a suite was cut mid-way; otherwise (the
   abort landed between groups: during a `:stop`, a `:start`, or before
   any group ran) `{:reason :aborted :after-scenario N}` with N = the
   last-ran group's verdict count and that group's label. nil when no
   abort is requested."
  [results]
  (when (abort/requested?)
    (or (some (fn [r]
                (let [h (-> r :result :halted)]
                  (when (= :aborted (:reason h))
                    (assoc h :group/label (:group-label r)))))
              results)
        (let [last-ran (last (filter :counts results))]
          (cond-> (abort/halt-record (ran-count (:counts last-ran)))
            last-ran (assoc :group/label (:group-label last-ran)))))))

(defn- compute-suite-exit-code
  "Aggregate per-group exit codes into a suite exit code.
   Worst code wins: planning-error (2) > degraded (4) > failed (1) >
   passed (0) — sl-7c7i; a crash (3) never appears here, it throws past
   aggregation to execute!'s handler. Groups a :run-scope halt SKIPPED
   (sl-78u4) have no exit code and are not in `exit-codes` — the halted
   group's own 4 is the invocation's verdict."
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
        ;; sl-pwbp: group identity rides opts for the WHOLE group scope —
        ;; the pre-body failure branches below report through
        ;; report-planning-error!, which stamps :group/label + :group/index
        ;; onto the EDN summary in multi-group runs; before the hoist only
        ;; the :else branch's inner-opts carried it.
        opts     (assoc opts :group-label label :group-index group-index)
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
            ;; declaration records the drop as deliberate. Registry
            ;; MEMBERSHIP (removed defaults included) is owned by the exact
            ;; bound-adapter check below (sl-kkoh; the removal warn arm was
            ;; deleted caller-less at sl-k1pv).
            _ (when adapter-registry
                (report-lossy-overrides!
                 run-id opts label
                 (setup/lossy-override-warnings decls adapter-registry)))
            ;; Group identity already rides opts (hoisted above, sl-pwbp).
            inner-opts (cond-> opts
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
                            (compile-suite-stage run-id inner-opts config all-pickles
                                                 report-opts (select-keys d-filter [:tag-held :tag-lints]))]
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
                                  (console/print-held-notice! diagnostics report-opts)
                                  (console/print-dry-run-plan! plans report-opts)
                                  (print-dry-run-hooks! (dry-run-hooks-entries plans)))
                                (when (:edn opts)
                                  (report-edn/prn-summary
                                   ;; sl-pwbp: dry-run summaries gain the
                                   ;; namespaced identity pair in multi-
                                   ;; group runs; the legacy bare :group
                                   ;; key stays (retirement is a contract
                                   ;; scrub, not this bead).
                                   (cond-> (dry-run-edn-summary
                                            run-id (:run-seed opts)
                                            plans diagnostics label filtered-count
                                            (:dry-run/reports-not-written opts))
                                     (:multi-group? opts)
                                     (assoc :group/label label
                                            :group/index group-index))))
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
   the honesty that matters.

   `crash-info` (sl-8orx, optional) turns the epilogue into the CRASHED
   TOC: `{:record {:exception-class :message :run-id}
   :crashed-group {:label :index} | nil :never-started [{:label :index}]}`.
   `results` are then the groups that COMPLETED before the crash; the
   crashing group renders one `:crashed` row (exit 3), every declared group
   after it a `:skipped` row (the sl-78u4 did-not-run word) carrying
   `:crashed-by`, and the summary is `:status :crashed` with the `:crash`
   record — the same three fields the JUnit sl-crash body carries (plus
   `:scenario`, which both markers render since sl-n6io)."
  ([run-id opts config results exit-code halted]
   (write-invocation-report! run-id opts config results exit-code halted nil))
  ([run-id opts config results exit-code halted crash-info]
   (when-let [html-path (resolve-html-path opts config)]
     (let [run-dir (some :results-dir results)
           {:keys [record crashed-group never-started]} crash-info
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
                      (fn [position {:keys [group-label status counts group-root] :as result}]
                        ;; sl-baup: the declared index names the group (and
                        ;; keys the stashed planning-failure page).
                        (cond-> {:label group-label
                                 :index (:group-index result position)
                                 :status status}
                          ;; sl-78u4: a :run-scope-halt-skipped group never
                          ;; started — no exit code to show.
                          (contains? result :exit-code)
                          (assoc :exit-code (:exit-code result))
                          counts (assoc :counts counts)
                          group-root
                          (assoc :path (str (fs/file-name group-root) "/report.html"))
                          (and (nil? group-root)
                               (get failure-pages (:group-index result position)))
                          (assoc :path (get failure-pages (:group-index result position)))))
                      results))
           ;; sl-8orx: the crash rows — the group in flight, then the ones
           ;; whose :start never ran.
           rows (cond-> rows
                  crashed-group
                  (conj (assoc crashed-group :status :crashed :exit-code 3))
                  (seq never-started)
                  (into (map #(assoc % :status :skipped :crashed-by record)
                             never-started)))
           island {:run-ctx (run-start-ctx run-id opts config 0 nil (run-start-timestamp))
                   :toc {:groups rows}
                   :summary (cond-> {:run-id run-id
                                     :exit-code exit-code
                                     :status (verdict/exit-code->status exit-code)}
                              ;; sl-78u4 / W10: the TOC header renders the
                              ;; invocation-level halt line too.
                              halted (assoc :halted halted)
                              ;; sl-8orx: the crash line, same idiom.
                              record (assoc :crash record))}]
       (report-html/write-invocation-toc!
        (when run-dir (str (fs/path run-dir "report.html")))
        html-path island)))))

(defn- run-with-setups!
  "Drive the suite through all setup groups in declared order.
   Aggregates per-group exit codes into a single suite result.

   `setup-base-dir`: directory containing setup.clj — relative paths in
   each group's :features resolve against it.

   Crash context (sl-8orx, one seam since sl-o26v): the invocation's
   `:crash-surfaces` atom (on opts, created by execute!) receives the
   context the crash epilogue needs — the rebound opts with the shared
   JUnit state, the filtered groups and the multi? gate once they exist,
   and every group result as it lands (`:completed`)."
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
          (let [;; Two gates, two questions (sl-baup, Warden W5). `multi?`
                ;; = more than one group RAN: the HTML/JUnit invocation
                ;; machinery below (a TOC page over one row would replace
                ;; that group's own report at the stable --html path).
                ;; `declared-multi?` = the project DECLARES more than one
                ;; group: group identity keys and the invocation aggregate
                ;; — the durable join key must survive narrowing to a
                ;; single group (charlie alone is still :group/index 2),
                ;; so the sl-pwbp absent-unless-multi gate keys on the
                ;; declaration. Single-DECLARED-group projects stay
                ;; byte-identical.
                multi? (> (count filtered) 1)
                declared-multi? (> (count setups) 1)
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
                                     (atom {:scenarios [] :run-ctx nil}))
                       ;; sl-pwbp: the absent-unless-multi gate — group
                       ;; identity keys and the invocation aggregate form
                       ;; exist only when this flag does.
                       declared-multi? (assoc :multi-group? true))
                ;; sl-78u4 (Warden W3): under :run scope the halt gate is
                ;; ONE atom for the whole invocation — created here,
                ;; threaded via opts → run-group! → execute-and-report-
                ;; stage's exec-opts → exec/execute-suite, which uses it
                ;; instead of its own per-suite atom. :group (and false)
                ;; thread nothing: every group gets its own gate, as
                ;; before. Never under dry-run (nothing executes).
                halt-gate (when (and (= :run (config/halt-policy config))
                                     (not (:dry-run opts)))
                            (atom nil))
                opts (cond-> opts halt-gate (assoc :halt-gate halt-gate))
                ;; sl-8orx: from here on a crash has invocation context to
                ;; write its markers with (the one seam, sl-o26v).
                _ (when-let [surfaces (:crash-surfaces opts)]
                    (swap! surfaces merge {:opts opts :filtered filtered
                                           :multi? multi? :completed []}))
                results  (vec (map-indexed
                               (fn [position group]
                                 ;; sl-baup (Warden W5): the index is the
                                 ;; group's DECLARED position, stamped by
                                 ;; filter-setups-by-cli-paths before
                                 ;; narrowing dropped anything — an
                                 ;; index-name with gaps under narrowing,
                                 ;; never the survivors' list position.
                                 (let [idx (setup/declared-index group position)
                                       ;; sl-617t: the abort is read HERE
                                       ;; beside the gate — the same check
                                       ;; site — and wins over its record.
                                       abort-record (invocation-abort-record
                                                     (:completed @(:crash-surfaces opts)))
                                       r (if-let [record (or abort-record
                                                             (some-> halt-gate deref))]
                                           ;; An earlier group went dark (or
                                           ;; the run was aborted): no more
                                           ;; setups — this group's :start
                                           ;; never runs.
                                           (skipped-group-result group record)
                                           (run-group! run-id opts config group idx
                                                       bus report-opts allow-pending?))
                                       r (assoc r :group-index idx)
                                       ;; sl-8orx: landed = pre-crash evidence.
                                       _ (when-let [surfaces (:crash-surfaces opts)]
                                           (swap! surfaces update :completed (fnil conj []) r))]
                                   ;; sl-kvn6 (rides sl-pwbp): per-group
                                   ;; verdict line — the console analog of
                                   ;; the HTML TOC row; this loop is the one
                                   ;; seam that also sees exit-2 groups,
                                   ;; which never reach a reporter's
                                   ;; on-run-end. Live console runs only
                                   ;; (dry-run has its own per-group line).
                                   (when (and declared-multi?
                                              (not (:edn opts))
                                              (not (:dry-run opts)))
                                     (console/print-group-verdict! r report-opts))
                                   r))
                               filtered))
                ;; Skipped groups carry no :exit-code — worst-wins over the
                ;; groups that ran; the halted group's 4 is the verdict.
                ;; sl-617t: an abort is 4 wherever it landed — a group's
                ;; own 4 when its suite was cut, and this conj when it
                ;; landed between groups (a :stop, a :start), where no
                ;; group's counts can carry it. 2 still outranks it.
                exit-code (compute-suite-exit-code
                           (cond-> (keep :exit-code results)
                             (abort/requested?) (conj 4)))
                dry-run? (boolean (:dry-run opts))
                ;; The aggregate's :halted record: the abort's (sl-617t,
                ;; wins) or the gate's record plus WHICH group went dark
                ;; (the group whose own summary carries the record).
                halted (or (invocation-abort-record results)
                           (when-let [record (some-> halt-gate deref)]
                             (assoc record :group/label
                                    (some #(when (-> % :result :halted) (:group-label %))
                                          results))))]
            ;; sl-617t (fold-in C1, fit-pass P1): the FINAL invocation-level
            ;; writes begin here — every group has returned and its own
            ;; reports (and the per-group-end JUnit re-materialization) are
            ;; done. From this point a cold second strike is a no-op: a
            ;; crash marker written now would race these writers and leave
            ;; the 8orx shape (a current, partial file beside exit 3).
            (when (abort/requested?) (abort/finalizing!))
            (when (and multi? (not dry-run?))
              (write-invocation-report! run-id opts config results exit-code halted))
            ;; sl-a7sx W23: the setup path is one invocation = one notice,
            ;; after every group's dry-run line (console mode only).
            (when (and dry-run? (not (:edn opts)))
              (print-dry-run-reports-notice! (:dry-run/reports-not-written opts)))
            ;; sl-pwbp: the invocation aggregate form — ONE final form so a
            ;; machine consumer reads the suite verdict from the stream.
            ;; Absent on crash (the throw above unwinds past this) and in
            ;; single-group streams (absent-unless-multi). EMITTED under
            ;; dry-run too, saying :dry-run (sl-a7sx, Warden W6 — Chair
            ;; ruling 09-03): a CI plan gate reading the aggregate must find
            ;; it, and "dry-run" is true where "passed" was a lie.
            (when (and declared-multi? (:edn opts))
              (report-edn/prn-summary
               (report-edn/invocation-summary
                run-id (:run-seed opts) exit-code results
                {:dry-run? dry-run?
                 :halted halted
                 ;; sl-alha (Warden W32): the invocation's run dir,
                 ;; config-root-relative like the group rows' own
                 ;; :run/results-path; nil (absent) when no group made one.
                 :results-path (when-let [run-dir (some :results-dir results)]
                                 (attachments/relativize-under
                                  (:config-root (:project-context opts))
                                  (str run-dir)))})))
            (cond-> {:exit-code exit-code
                     :run-id    run-id
                     ;; Dry-run is invocation-wide: the in-process status
                     ;; says so too, not the exit-code-derived :passed —
                     ;; for a CLEAN dry run. A nonzero code (a group
                     ;; failed planning) keeps its exit-derived status,
                     ;; matching invocation-summary and the group rows.
                     :status    (if (and dry-run? (zero? exit-code))
                                  :dry-run
                                  (verdict/exit-code->status exit-code))
                     :groups    results}
              ;; sl-78u4: the :run-scope halt record, invocation-level.
              halted (assoc :halted halted)
              ;; sl-l9nj: the invocation's run dir at the top level too —
              ;; all groups share it (per-group results keep their own copy).
              (some :results-dir results)
              (assoc :results-dir (some :results-dir results)))))))))

(defn- crash-record
  "sl-8orx: the three fields both crash markers carry (JUnit sl-crash body,
   HTML TOC :crash record) so a reader can match the artifacts; the EDN
   crash form carries the same run-id. sl-io05: plus `:scenario`
   `{:name :source-file :line}` when the crash was a throw escaping ONE
   scenario's execution — absent for every other crash (a reporter
   callback, a stage, the epilogue), which has no scenario to name. Both
   markers render it (sl-n6io): the JUnit body's `scenario:` line and the
   HTML crash line's 'in scenario' clause."
  [run-id ^Throwable t scenario]
  (cond-> {:exception-class (.getName (class t))
           :message (ex-message t)
           :run-id run-id}
    scenario (assoc :scenario scenario)))

(defn- guarded-marker-write!
  "Run one crash-marker write. A marker write can itself fail (reporter
   invariant 4: a write failure throws) — it is reported on stderr and
   swallowed HERE so the ORIGINAL crash keeps its exit 3 and its 'Runner
   crash:' line; never recursed into, never masking."
  [surface write-fn]
  (try
    (write-fn)
    (catch Throwable t
      (binding [*out* *err*]
        (println (str "Crash marker not written (" surface "): "
                      (.getName (class t)) ": " (ex-message t)))))))

(defn- write-crash-artifacts!
  "sl-8orx: on the crash path of a multi-group run, make both invocation
   report surfaces tell the truth beside exit 3 — the JUnit gets the
   `sl-crash` suite appended to the shared state and re-materialized (it
   was a CURRENT, all-green, partial file), the stable --html path gets a
   CRASHED TOC over the groups that completed (it was the previous run's
   stale bytes). `completed` = the group results that landed before the
   throw; the crashing group is the next filtered group in declared order
   (nil when the throw came from the epilogue after every group ran), and
   every group after it never started. Single-scope runs (one filtered
   group, or no setup.clj) have no invocation surfaces — their markers
   are write-crash-markers!'s other branch (sl-o26v)."
  [run-id config {:keys [opts filtered multi? crashed-scenario]} completed t]
  (when (and multi? (not (:dry-run opts)))
    (let [record (crash-record run-id t crashed-scenario)
          position (count completed)
          crashing (nth filtered position nil)
          group-row (fn [group pos]
                      {:label (or (:label group) "<unlabeled>")
                       :index (setup/declared-index group pos)})
          crashed-group (when crashing (group-row crashing position))
          never-started (vec (map-indexed
                              (fn [i group] (group-row group (+ position 1 i)))
                              (drop (inc position) filtered)))
          fallback-ctx (run-start-ctx run-id opts config 0 nil (run-start-timestamp))
          toc-record (cond-> record
                       crashed-group (assoc :group/label (:label crashed-group)
                                            :group/index (:index crashed-group)))]
      (when-let [junit-path (resolve-junit-path opts config)]
        (when-let [state (:junit-shared-state opts)]
          (guarded-marker-write!
           "junit"
           #(report-junit/write-crash-marker! junit-path state record fallback-ctx))))
      (guarded-marker-write!
       "html"
       #(write-invocation-report! run-id opts config completed 3 nil
                                  {:record toc-record
                                   :crashed-group crashed-group
                                   :never-started never-started})))))

(defn- write-crash-markers!
  "sl-o26v: the ONE crash seam's marker writer — make the report surfaces
   of WHATEVER pipeline ran tell the truth beside exit 3. `surfaces` is
   the invocation's :crash-surfaces value:

   - nil (the throw came before any reporter existed — a preflight, config
     or planning stage) or a dry run (nothing executes, D3): nothing.
   - multi-group (`:multi?`): write-crash-artifacts! exactly as sl-8orx
     built it — the sl-crash suite appended to the shared JUnit state and
     the CRASHED invocation TOC — byte-identical.
   - single-scope (the classic pipeline, or one filtered setup.clj group):
     the SAME junit/write-crash-marker! over the JUnit reporter's private
     state (it holds every scenario it saw and its :run-ctx), and the
     CRASHED run page over the HtmlReporter's private state at the stable
     --html path and the run-dir primary when one exists (D2; the crash
     record names the group under setup.clj). Only configured surfaces
     are written. Each write rides guarded-marker-write!: a marker failure
     is one stderr line, never masking exit 3."
  [run-id {:keys [opts config multi? filtered completed crashed-scenario
                  junit-path junit-state html-path html-primary html-state]
           :as surfaces}
   ^Throwable t]
  (when (and surfaces (not (:dry-run opts)))
    (if multi?
      (write-crash-artifacts! run-id config {:opts opts :filtered filtered :multi? true
                                             :crashed-scenario crashed-scenario}
                              (or completed []) t)
      (let [record (cond-> (crash-record run-id t crashed-scenario)
                     (:group-label opts) (assoc :group/label (:group-label opts)))
            fallback-ctx (run-start-ctx run-id opts config 0 nil (run-start-timestamp))]
        (when (and junit-path junit-state)
          (guarded-marker-write!
           "junit"
           #(report-junit/write-crash-marker! junit-path junit-state record fallback-ctx)))
        (when (and html-path html-state)
          (guarded-marker-write!
           "html"
           #(let [state @html-state
                  scenarios (:scenarios state)
                  counts (frequencies (map :status scenarios))]
              (report-html/write-crash-page!
               html-primary html-path
               (update state :run-ctx (fn [ctx] (or ctx fallback-ctx)))
               {:run-id run-id
                :exit-code 3
                :status :crashed
                :crash record
                :counts (merge {:passed 0 :failed 0 :pending 0 :skipped 0}
                               (select-keys counts [:passed :failed :pending :skipped :error]))}))))))))

(defn- report-crash!
  "execute!'s crash surface: the EDN crash form (under --edn) or the
   'Runner crash:' stderr line (stack under :verbose), and the in-process
   `{:exit-code 3 :status :crashed}` result. Also the base of the cold
   second-strike epilogue (sl-617t), which runs it from the SIGINT handler
   thread before `System/exit 3`. sl-io05: when the crash was a throw
   escaping one scenario's execution, the crash says WHERE — `:scenario`
   on the error (in-process result and EDN crash form alike) and one added
   stderr line under 'Runner crash:'."
  [run-id opts run-seed ^Throwable t]
  (let [exit-code 3
        scenario (some-> opts :crash-surfaces deref :crashed-scenario)
        error (cond-> {:type :runner/crash
                       :message (ex-message t)
                       :exception-class (.getName (class t))}
                scenario (assoc :scenario scenario))]
    (when (:edn opts)
      (report-edn/prn-summary
       (report-edn/build-summary run-id exit-code nil
                                 {:error error
                                  :seed run-seed})))
    (when-not (:edn opts)
      (binding [*out* *err*]
        (println "Runner crash:" (ex-message t))
        (when scenario
          (println (str "  in scenario: " (:name scenario)
                        " (" (:source-file scenario) ":" (:line scenario) ")")))
        (when (:verbose opts)
          (.printStackTrace t *err*))))
    {:exit-code exit-code
     :run-id run-id
     :status :crashed
     :error error}))

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
   - :hold-unplannable - (sl-6wh9, CLI ONLY — never a config mirror)
     deselect at planning every scenario that would exit 2 for a
     scenario-level reason (undefined/ambiguous step, consumed-without-
     producer); the rest runs with its ordinary verdict; held scenarios
     ride :held + :counts :held on every surface
   - :edn - output EDN summary to stdout
   - :verbose - verbose console output
   - :no-color - disable ANSI colors

   Returns:
   {:exit-code 0|1|2|3
    :run-id \"uuid\"
    :status :passed|:failed|:planning-failed|:crashed|:dry-run
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
               (assoc :capture-warn-state (atom #{}))
               ;; sl-o26v: the ONE crash seam's context — filled by
               ;; make-reporters (paths + both reporters' state) and the
               ;; group driver (filtered groups, landed results, multi?);
               ;; read by the crash epilogue, whichever pipeline ran.
               :always (assoc :crash-surfaces (atom nil)))
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
        report-opts (select-keys opts [:verbose :no-color])
        ;; sl-o26v: the one crash epilogue (see its registration below).
        markers-written (atom false)
        crash-epilogue! (fn [^Throwable t]
                          (when (compare-and-set! markers-written false true)
                            (write-crash-markers! run-id @(:crash-surfaces opts) t))
                          (report-crash! run-id opts @run-seed-state t))]
    (binding [wardrobe/*project-context* project-context]
      (try
        ;; sl-617t: register the run for the abort paths (cold SIGINT, the
        ;; daemon's abort op) — an abort landing anywhere from here to the
        ;; finally is honored at the next gate check / step boundary. The
        ;; base crash epilogue is what a cold SECOND strike prints before
        ;; exit 3 (the invocation wrapper layers the 8orx markers on it).
        (abort/begin-run!)
        ;; sl-o26v: ONE crash epilogue for every pipeline and every crash
        ;; entry — the outer catch below and 617t's cold second strike
        ;; both run it. The markers are written once (a strike two that
        ;; is then followed by a throw must not write twice); the crash
        ;; report itself runs on every call because the outer catch must
        ;; return its exit-3 map (D5).
        (abort/register-crash-epilogue! crash-epilogue!)
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
                  ;; sl-a7sx (Warden W23): resolve the declared reports
                  ;; (flag OR config) once, here, where the resolved config
                  ;; is first in hand — both pipelines' dry-run branches
                  ;; read the stash for the notice and the EDN key.
                  opts (cond-> opts
                         (:dry-run opts)
                         (assoc :dry-run/reports-not-written
                                (dry-run-reports-not-written opts config)))
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
                                                            :tag-filter (:tag-filter opts)
                                                            :held (count (:tag-held df))})
                                  (let [{:keys [all-pickles filtered-count]} df
                                        [s4 d4] (load-steps-stage run-id opts step-paths)]
                                    (if (= :exit s4)
                                      d4
                                      (let [[s5 d5] (compile-suite-stage run-id opts config all-pickles
                                                                         report-opts (select-keys df [:tag-held :tag-lints]))]
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
                                                                      (fnil into []) plan-lints))
                                                ;; sl-k1pv: the plan-aware EXACT
                                                ;; registry check on the
                                                ;; single-suite pipeline. One law
                                                ;; governs both pipelines'
                                                ;; postures: check when knowable,
                                                ;; defer when not. The setup
                                                ;; pipeline defers to live runs
                                                ;; because :start builds the
                                                ;; registry — its dry-run cannot
                                                ;; know it without running user
                                                ;; code. This path has no :start:
                                                ;; the registry is the same in
                                                ;; both modes, so plan-time
                                                ;; checking is the honest arm and
                                                ;; the check fires under dry-run
                                                ;; too (the 7c7i precedent — a
                                                ;; PLAN-verdict exit 0 must mean
                                                ;; a runnable plan).
                                                bound-errors
                                                (setup/bound-adapter-errors
                                                 (plans-touched-adapters
                                                  plans (:interfaces config))
                                                 (:adapter-registry opts))]
                                            (cond
                                              (seq bound-errors)
                                              (let [msg (str/join "; " (map :message bound-errors))]
                                                (report-planning-error! run-id opts
                                                  {:error (first bound-errors)
                                                   :diagnostics {:errors bound-errors}
                                                   :stderr-msg #(println msg)}))

                                              (:dry-run opts)
                                              (let [exit-code 0]
                                                (when-not (:edn opts)
                                                  (console/print-warnings! diagnostics report-opts)
                                                  (binding [*out* *err*]
                                                    (println (dry-run-console-line (count plans) filtered-count)))
                                                  (console/print-held-notice! diagnostics report-opts)
                                                  (console/print-dry-run-plan! plans report-opts)
                                                  (print-dry-run-hooks! (dry-run-hooks-entries plans))
                                                  ;; sl-a7sx W23: the classic pipeline
                                                  ;; is one invocation = one notice.
                                                  (print-dry-run-reports-notice!
                                                   (:dry-run/reports-not-written opts)))
                                                (when (:edn opts)
                                                  (report-edn/prn-summary
                                                   (dry-run-edn-summary run-id (:run-seed opts)
                                                                        plans diagnostics nil filtered-count
                                                                        (:dry-run/reports-not-written opts))))
                                                {:exit-code exit-code
                                                 :run-id run-id
                                                 :status :dry-run
                                                 :plans plans})

                                              :else
                                              (execute-and-report-stage run-id opts bus config features
                                                                        all-pickles filtered-count plans diagnostics
                                                                        allow-pending? report-opts))))))))))))))))))))
        ;; Catch any uncaught exceptions → exit 3, through the one seam.
        (catch Throwable t
          (crash-epilogue! t))
        ;; sl-4tsi lifecycle half: the bus lives for the whole invocation and
        ;; is closed HERE, once, on every exit path — normal, dry-run, every
        ;; planning-failure early return, setup-branch exits, and crash. A
        ;; finally (not per-path closes) so unenumerated future exits are
        ;; covered too. bus-close! drains subscribers before returning (q9wp).
        (finally
          (abort/end-run!)
          (events/bus-close! bus))))))
