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
            [clojure.tools.logging :as log]
            [shiftlefter.gherkin.api :as api]
            [shiftlefter.gherkin.io :as io]
            [shiftlefter.costume.wardrobe :as wardrobe]
            [shiftlefter.project-context :as project-context]
            [shiftlefter.runner.config :as config]
            [shiftlefter.runner.discover :as discover]
            [shiftlefter.runner.events :as events]
            [shiftlefter.intent.state :as intent-state]
            [shiftlefter.runner.report.console :as console]
            [shiftlefter.runner.report.edn :as report-edn]
            [shiftlefter.runner.setup :as setup]
            [shiftlefter.runner.step-loader :as step-loader]
            [shiftlefter.stepengine.compile :as compile]
            [shiftlefter.stepengine.exec :as exec]
            [shiftlefter.stepengine.registry :as registry]
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
  "Compute exit code from execution result."
  [exec-result allow-pending?]
  (let [{:keys [failed pending]} (:counts exec-result)]
    (cond
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

    (:glossaries config)
    (update :glossaries #(resolve-glossary-config project-context %))))

;; -----------------------------------------------------------------------------
;; Event Publishing
;; -----------------------------------------------------------------------------

(defn- publish-run-started!
  "Publish test run started event."
  [bus run-id feature-count pickle-count]
  (events/publish! bus (events/make-event :test-run/started run-id
                                          {:feature-count feature-count
                                           :scenario-count pickle-count})))

(defn- publish-run-finished!
  "Publish test run finished event."
  [bus run-id exit-code counts]
  (events/publish! bus (events/make-event :test-run/finished run-id
                                          {:exit-code exit-code
                                           :counts counts})))

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
   capability gating (sl-ewn) honors a setup.clj-supplied custom registry."
  [run-id opts config all-pickles report-opts]
  (let [stepdefs (registry/all-stepdefs)
        compile-opts (when-let [r (:adapter-registry opts)]
                       {:adapter-registry r})
        {:keys [plans runnable? diagnostics]}
        (compile/compile-suite config all-pickles stepdefs compile-opts)]
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
      [:continue {:plans plans :diagnostics diagnostics}])))

(defn- dry-run-edn-summary
  "Build the EDN summary for a dry-run success, including warn-level SVO
   diagnostics that would otherwise be dropped (sl-qk8l). `group-label`
   is non-nil on the setup-group path only."
  [run-id plans diagnostics group-label]
  (cond-> {:run/id run-id
           :run/exit-code 0
           :run/status :dry-run
           :counts {:scenarios (count plans)
                    :steps (reduce + (map #(count (:plan/steps %)) plans))}}
    group-label (assoc :group group-label)
    (report-edn/execution-diagnostics diagnostics)
    (assoc :diagnostics (report-edn/execution-diagnostics diagnostics))))

(defn- execute-and-report-stage
  "Stage 8-10: Execute scenarios, report results, return exit code."
  [run-id opts bus config features all-pickles plans diagnostics allow-pending? report-opts]
  (publish-run-started! bus run-id (count features) (count all-pickles))
  (let [exec-opts (cond-> {:bus bus :run-id run-id :interfaces (:interfaces config)
                           ;; sl-aa5: scoped-eager provisioning is the default;
                           ;; opt-out via {:runner {:provisioning :lazy}}.
                           :provisioning (config/provisioning-mode config)}
                    (:adapter-registry opts) (assoc :adapter-registry (:adapter-registry opts)))
        exec-result (exec/execute-suite plans exec-opts)
        exit-code (compute-exit-code exec-result allow-pending?)]
    ;; Report
    (when-not (:edn opts)
      (when (:verbose opts)
        (doseq [scenario (:scenarios exec-result)]
          (console/print-scenario! scenario report-opts)))
      (console/print-failures! (:scenarios exec-result) report-opts)
      (console/print-summary! exec-result report-opts)
      ;; Warn-level SVO issues didn't block execution but must still reach a
      ;; human (sl-qk8l/sl-6h4r) — EDN output already carries them.
      (console/print-warnings! diagnostics report-opts))
    (when (:edn opts)
      (report-edn/prn-summary
       (report-edn/build-summary run-id exit-code exec-result {:diagnostics diagnostics})))
    (publish-run-finished! bus run-id exit-code (:counts exec-result))
    (events/bus-close! bus)
    {:exit-code exit-code
     :run-id run-id
     :status (if (zero? exit-code) :passed :failed)
     :counts (:counts exec-result)
     :result exec-result}))

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
   (in finally; runs even on failure).

   Returns a per-group result map shaped like execute-and-report-stage's,
   plus :group-label and :status."
  [run-id opts config group bus report-opts allow-pending?]
  (let [label    (or (:label group) "<unlabeled>")
        features (setup/resolve-group-features group)
        ;; :start may throw or return malformed shape — both treated as
        ;; planning errors local to this group; other groups still run.
        start-result (try
                       ((:start group) config)
                       (catch Throwable t
                         {::start-throw t}))]
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
                            (println (str "[" label "] " (count plans)
                                          " scenario(s) bound successfully (dry run)"))))
                        (when (:edn opts)
                          (report-edn/prn-summary
                           (dry-run-edn-summary run-id plans diagnostics label)))
                        {:exit-code exit-code :run-id run-id :status :dry-run
                         :group-label label :plans plans})
                      (-> (execute-and-report-stage
                           run-id inner-opts bus config features
                           all-pickles plans diagnostics
                           allow-pending? report-opts)
                          (assoc :group-label label))))))))
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
      (let [[s1 d1] (load-config-stage run-id opts)]
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
                    [s-setup d-setup] (load-setup-stage run-id opts config)]
                (cond
                  (= :exit s-setup) d-setup

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
                                            (println (str (count plans) " scenario(s) bound successfully (dry run)"))))
                                        (when (:edn opts)
                                          (report-edn/prn-summary
                                           (dry-run-edn-summary run-id plans diagnostics nil)))
                                        {:exit-code exit-code
                                         :run-id run-id
                                         :status :dry-run
                                         :plans plans})
                                      (execute-and-report-stage run-id opts bus config features
                                                                all-pickles plans diagnostics
                                                                allow-pending? report-opts)))))))))))))))))
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

(defn execute-with-crash!
  "Test helper: force a crash to test exit code 3."
  []
  (execute! {:paths ["nonexistent"]
             :_force-crash true}))
