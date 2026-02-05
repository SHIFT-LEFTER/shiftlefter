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

(defn- extract-macro-provenance
  "Extract macro provenance for EDN output.
   Returns nil if step has no macro metadata."
  [step source-file]
  (when-let [macro (:step/macro step)]
    (let [role (:role macro)
          call-site (:call-site macro)
          def-step (:definition-step macro)]
      (cond-> {:key (:key macro)}
        ;; Call-site with assembled file path
        call-site
        (assoc :call-site {:file source-file
                           :line (:line call-site)
                           :column (:column call-site)})
        ;; Definition-step (expanded steps only)
        (and (= :expanded role) def-step)
        (assoc :definition-step def-step)))))

(defn- extract-step-failure
  "Extract failure info from a failed step result."
  [step-result scenario-result]
  (let [step (:step step-result)
        binding (:binding step-result)
        pickle (-> scenario-result :plan :plan/pickle)
        source-file (:pickle/source-file pickle)
        macro-info (extract-macro-provenance step source-file)]
    (cond-> {:step/text (:step/text step)
             :step/id (:step/id step)
             :scenario/name (:pickle/name pickle)
             :scenario/id (:pickle/id pickle)
             :location (:source binding)
             :error (serialize-error (:error step-result))}
      macro-info (assoc :macro macro-info))))

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
  "Extract a planning issue (undefined/ambiguous/invalid-arity) for EDN.
   Includes full location: :uri, :line, :column from enriched bound-step."
  [bound-step issue-type]
  (let [step (:step bound-step)
        step-loc (:step/location step)]
    (cond-> {:type issue-type
             :step/text (:step/text step)
             :step/id (:step/id step)
             :uri (:source-file bound-step)
             :line (:line step-loc)
             :column (:column step-loc)}
      (= :ambiguous issue-type)
      (assoc :alternatives (mapv #(select-keys % [:stepdef/id :pattern-src :source])
                                 (:alternatives bound-step)))

      (= :invalid-arity issue-type)
      (assoc :arity {:expected (:expected (:binding bound-step))
                     :actual (:actual (:binding bound-step))}))))

(defn- extract-planning-diagnostics
  "Extract planning diagnostics for EDN output (exit code 2).
   Includes binding issues and SVO issues."
  [diagnostics]
  (when diagnostics
    (let [{:keys [undefined ambiguous invalid-arity svo-issues counts]} diagnostics
          binding-issues (concat
                          (map #(extract-planning-issue % :undefined) undefined)
                          (map #(extract-planning-issue % :ambiguous) ambiguous)
                          (map #(extract-planning-issue % :invalid-arity) invalid-arity))]
      (cond-> {:issues (vec binding-issues)
               :counts counts}
        (seq svo-issues) (assoc :svo-issues svo-issues)))))

(defn- extract-execution-diagnostics
  "Extract diagnostics for EDN output on successful execution (exit code 0/1).
   Only includes SVO issues (warnings that didn't block execution).
   Returns nil if no diagnostics to report."
  [diagnostics]
  (when diagnostics
    (let [{:keys [svo-issues counts]} diagnostics]
      (when (seq svo-issues)
        {:svo-issues svo-issues
         :counts (select-keys counts [:svo-issue-count])}))))

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
      ;; Success or failure - include counts, failures, and diagnostics if present
      (0 1) (let [scenarios (:scenarios result)
                  step-count (reduce + (map #(count (:steps %)) scenarios))
                  exec-diagnostics (extract-execution-diagnostics (:diagnostics opts))]
              (cond-> (assoc base
                             :counts (assoc (:counts result)
                                            :scenarios (count scenarios)
                                            :steps step-count))
                (= 1 exit-code)
                (assoc :failures (vec (extract-failures scenarios)))

                ;; Include :diagnostics only if non-empty (no noise on clean runs)
                exec-diagnostics
                (assoc :diagnostics exec-diagnostics)))

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
