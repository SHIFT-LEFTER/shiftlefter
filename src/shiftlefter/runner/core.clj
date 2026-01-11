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
  (:require [shiftlefter.gherkin.api :as api]
            [shiftlefter.gherkin.io :as io]
            [shiftlefter.runner.config :as config]
            [shiftlefter.runner.discover :as discover]
            [shiftlefter.runner.events :as events]
            [shiftlefter.runner.report.console :as console]
            [shiftlefter.runner.report.edn :as report-edn]
            [shiftlefter.runner.step-loader :as step-loader]
            [shiftlefter.stepengine.compile :as compile]
            [shiftlefter.stepengine.exec :as exec]
            [shiftlefter.stepengine.registry :as registry]))

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
  (let [{:keys [passed failed pending]} (:counts exec-result)]
    (cond
      (pos? (or failed 0)) 1
      (and (pos? (or pending 0)) (not allow-pending?)) 1
      :else 0)))

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
  (let [run-id (str (java.util.UUID/randomUUID))
        bus (events/make-memory-bus)
        report-opts (select-keys opts [:verbose :no-color])]
    (try
      ;; 1. Load config
      (let [config-result (config/load-config-safe {:config-path (:config-path opts)})]
        (if (= :error (:status config-result))
          ;; Config error → exit 2
          (let [exit-code 2]
            (when (:edn opts)
              (report-edn/prn-summary
               (report-edn/build-summary run-id exit-code nil
                                         {:error {:type :config/error
                                                  :message (:message config-result)}})))
            (when-not (:edn opts)
              (binding [*out* *err*]
                (println "Config error:" (:message config-result))))
            {:exit-code exit-code
             :run-id run-id
             :status :planning-failed})

          (let [config (:config config-result)
                step-paths (or (:step-paths opts) (config/get-step-paths config))
                allow-pending? (config/allow-pending? config)]

            ;; 2. Discover feature files
            (let [discover-result (discover/discover-feature-files-or-error (:paths opts))]
              (if (= :error (:status discover-result))
                ;; Discovery error → exit 2
                (let [exit-code 2]
                  (when (:edn opts)
                    (report-edn/prn-summary
                     (report-edn/build-summary run-id exit-code nil
                                               {:error {:type (:type discover-result)
                                                        :message (:message discover-result)}})))
                  (when-not (:edn opts)
                    (binding [*out* *err*]
                      (println "Discovery error:" (:message discover-result))))
                  {:exit-code exit-code
                   :run-id run-id
                   :status :planning-failed})

                (let [feature-paths (:files discover-result)]

                  ;; 3-4. Parse all features
                  (let [parse-result (parse-all-features feature-paths)]
                    (if (= :error (:status parse-result))
                      ;; Parse errors → exit 2
                      (let [exit-code 2]
                        (when (:edn opts)
                          (report-edn/prn-summary
                           (report-edn/build-summary run-id exit-code nil
                                                     {:error {:type :parse/failed
                                                              :errors (:errors parse-result)}})))
                        (when-not (:edn opts)
                          (binding [*out* *err*]
                            (println "Parse errors:")
                            (doseq [err (:errors parse-result)]
                              (println " " (:message err)))))
                        {:exit-code exit-code
                         :run-id run-id
                         :status :planning-failed
                         :errors (:errors parse-result)})

                      (let [all-pickles (:all-pickles parse-result)
                            features (:features parse-result)]

                        ;; 5. Load step definitions
                        (let [load-result (step-loader/load-step-paths! step-paths)]
                          (if (= :error (:status load-result))
                            ;; Step load error → exit 2
                            (let [exit-code 2]
                              (when (:edn opts)
                                (report-edn/prn-summary
                                 (report-edn/build-summary run-id exit-code nil
                                                           {:error {:type :step-load/failed
                                                                    :errors (:errors load-result)}})))
                              (when-not (:edn opts)
                                (binding [*out* *err*]
                                  (println "Step load errors:")
                                  (doseq [err (:errors load-result)]
                                    (println " " (:path err) "-" (-> err :error :message)))))
                              {:exit-code exit-code
                               :run-id run-id
                               :status :planning-failed
                               :errors (:errors load-result)})

                            ;; 6. Compile suite (bind all pickles)
                            (let [stepdefs (registry/all-stepdefs)
                                  {:keys [plans runnable? diagnostics]} (compile/compile-suite (:runner config) all-pickles stepdefs)]

                              (if-not runnable?
                                ;; Binding issues → exit 2
                                (let [exit-code 2]
                                  (when-not (:edn opts)
                                    (console/print-diagnostics! diagnostics report-opts))
                                  (when (:edn opts)
                                    (report-edn/prn-summary
                                     (report-edn/build-summary run-id exit-code nil
                                                               {:diagnostics diagnostics})))
                                  {:exit-code exit-code
                                   :run-id run-id
                                   :status :planning-failed
                                   :diagnostics diagnostics})

                                ;; 7. Dry run check
                                (if (:dry-run opts)
                                  (let [exit-code 0]
                                    (when-not (:edn opts)
                                      (binding [*out* *err*]
                                        (println (str (count plans) " scenario(s) bound successfully (dry run)"))))
                                    (when (:edn opts)
                                      (report-edn/prn-summary
                                       {:run/id run-id
                                        :run/exit-code exit-code
                                        :run/status :dry-run
                                        :counts {:scenarios (count plans)
                                                 :steps (reduce + (map #(count (:plan/steps %)) plans))}}))
                                    {:exit-code exit-code
                                     :run-id run-id
                                     :status :dry-run
                                     :plans plans})

                                  ;; 8. Execute all scenarios
                                  (do
                                    (publish-run-started! bus run-id (count features) (count all-pickles))
                                    (let [exec-opts {:bus bus :run-id run-id}
                                          exec-result (exec/execute-suite plans exec-opts)
                                          exit-code (compute-exit-code exec-result allow-pending?)]

                                      ;; 9. Report results
                                      (when-not (:edn opts)
                                        (when (:verbose opts)
                                          (doseq [scenario (:scenarios exec-result)]
                                            (console/print-scenario! scenario report-opts)))
                                        (console/print-failures! (:scenarios exec-result) report-opts)
                                        (console/print-summary! exec-result report-opts))

                                      (when (:edn opts)
                                        (report-edn/prn-summary
                                         (report-edn/build-summary run-id exit-code exec-result {})))

                                      (publish-run-finished! bus run-id exit-code (:counts exec-result))
                                      (events/bus-close! bus)

                                      ;; 10. Return result
                                      {:exit-code exit-code
                                       :run-id run-id
                                       :status (if (zero? exit-code) :passed :failed)
                                       :counts (:counts exec-result)
                                       :result exec-result}))))))))))))))))

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
           :error error})))))

(defn execute-with-crash!
  "Test helper: force a crash to test exit code 3."
  []
  (execute! {:paths ["nonexistent"]
             :_force-crash true}))
