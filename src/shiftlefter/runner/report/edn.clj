(ns shiftlefter.runner.report.edn
  "EDN reporter for ShiftLefter runner.

   Outputs machine-readable EDN to **stdout** only.
   Human-readable console output goes to stderr via the console reporter.

   ## Summary Shape

   ```clojure
   {:run/id \"uuid-string\"
    :run/exit-code 0|1|2|3
    :run/status :passed|:failed|:planning-failed|:crashed
    :counts {:passed N :failed N :pending N :skipped N
             :scenarios N :steps N}
    :planning {...}     ;; present when exit-code 2
    :failures [{...}]   ;; present when exit-code 1
    :error {...}}       ;; present when exit-code 3
   ```

   ## Usage

   ```clojure
   (prn-summary {:run/id \"abc\" :run/exit-code 0 :counts {...}})
   ;; Prints EDN map to stdout
   ```")

;; -----------------------------------------------------------------------------
;; Error Serialization
;; -----------------------------------------------------------------------------

(defn- serialize-error
  "Serialize an error map, ensuring no Throwable objects.
   Converts any remaining Throwable to a map representation."
  [error]
  (when error
    (cond-> {:type (:type error)
             :message (:message error)}
      (:exception-class error) (assoc :exception-class (:exception-class error))
      (:data error) (assoc :data (:data error))
      (:value error) (assoc :value (pr-str (:value error))))))

;; -----------------------------------------------------------------------------
;; Failure Extraction
;; -----------------------------------------------------------------------------

(defn- extract-step-failure
  "Extract failure info from a failed step result."
  [step-result scenario-result]
  (let [step (:step step-result)
        binding (:binding step-result)
        pickle (-> scenario-result :plan :plan/pickle)]
    {:step/text (:step/text step)
     :step/id (:step/id step)
     :scenario/name (:pickle/name pickle)
     :scenario/id (:pickle/id pickle)
     :location (:source binding)
     :error (serialize-error (:error step-result))}))

(defn- extract-failures
  "Extract all failures from scenario results."
  [scenarios]
  (for [scenario scenarios
        :when (= :failed (:status scenario))
        step (:steps scenario)
        :when (= :failed (:status step))]
    (extract-step-failure step scenario)))

;; -----------------------------------------------------------------------------
;; Planning Diagnostics
;; -----------------------------------------------------------------------------

(defn- extract-planning-issue
  "Extract a planning issue (undefined/ambiguous/invalid-arity) for EDN."
  [bound-step issue-type]
  (let [step (:step bound-step)]
    (cond-> {:type issue-type
             :step/text (:step/text step)
             :step/id (:step/id step)}
      (= :ambiguous issue-type)
      (assoc :alternatives (mapv #(select-keys % [:stepdef/id :pattern-src :source])
                                 (:alternatives bound-step)))

      (= :invalid-arity issue-type)
      (assoc :arity {:expected (:expected (:binding bound-step))
                     :actual (:actual (:binding bound-step))}))))

(defn- extract-planning-diagnostics
  "Extract planning diagnostics for EDN output."
  [diagnostics]
  (when diagnostics
    (let [{:keys [undefined ambiguous invalid-arity counts]} diagnostics
          issues (concat
                  (map #(extract-planning-issue % :undefined) undefined)
                  (map #(extract-planning-issue % :ambiguous) ambiguous)
                  (map #(extract-planning-issue % :invalid-arity) invalid-arity))]
      {:issues (vec issues)
       :counts counts})))

;; -----------------------------------------------------------------------------
;; Summary Building
;; -----------------------------------------------------------------------------

(defn build-summary
  "Build a complete EDN summary map from run results.

   Parameters:
   - run-id: UUID string for this run
   - exit-code: 0, 1, 2, or 3
   - result: execution result from execute-suite (or nil for exit 2/3)
   - opts: {:diagnostics ... :error ...}

   Returns EDN-safe map suitable for prn."
  [run-id exit-code result opts]
  (let [base {:run/id run-id
              :run/exit-code exit-code
              :run/status (case exit-code
                            0 :passed
                            1 :failed
                            2 :planning-failed
                            3 :crashed)}]
    (case exit-code
      ;; Success or failure - include counts and failures
      (0 1) (let [scenarios (:scenarios result)
                  step-count (reduce + (map #(count (:steps %)) scenarios))]
              (cond-> (assoc base
                             :counts (assoc (:counts result)
                                            :scenarios (count scenarios)
                                            :steps step-count))
                (= 1 exit-code)
                (assoc :failures (vec (extract-failures scenarios)))))

      ;; Planning failure - include diagnostics
      2 (assoc base
               :planning (extract-planning-diagnostics (:diagnostics opts)))

      ;; Crash - include error
      3 (assoc base
               :error (serialize-error (:error opts))))))

;; -----------------------------------------------------------------------------
;; Output
;; -----------------------------------------------------------------------------

(defn prn-summary
  "Print EDN summary to stdout.
   This is the only output that should go to stdout."
  [summary]
  (prn summary))

(defn print-edn-summary!
  "Build and print EDN summary to stdout.

   Convenience function that combines build-summary and prn-summary."
  [run-id exit-code result opts]
  (prn-summary (build-summary run-id exit-code result opts)))
