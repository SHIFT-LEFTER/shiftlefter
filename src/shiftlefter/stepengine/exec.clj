(ns shiftlefter.stepengine.exec
  "Step execution engine for ShiftLefter runner.

   Executes bound steps with context threading and fail-fast semantics.

   ## Invocation Dispatch

   Based on step arity (A) vs capture count (C):
   - `A == C` → call with captures only (legacy mode)
   - `A == C+1` → call with captures + ctx as last arg

   ## Context Shape

   ```clojure
   {:step <pickle-step>       ;; current step being executed
    :scenario <scenario-ctx>} ;; accumulated state from prior steps
   ```

   Docstring/DataTable available at `(-> ctx :step :step/arguments)`.

   ## Return Handling

   | Step returns | Result |
   |-------------|--------|
   | map         | New scenario-ctx (replaces previous) |
   | nil         | ctx unchanged, step passed |
   | :pending    | Step pending, scenario fails, remaining skipped |
   | other       | Step failed (invalid return), scenario fails |
   | throws      | Step failed, scenario fails, remaining skipped |

   ## Execution Semantics

   - Scenario: fail-fast — on failure/pending, skip remaining steps
   - Suite: continue — after scenario fails, proceed to next scenario")

;; -----------------------------------------------------------------------------
;; Step Invocation
;; -----------------------------------------------------------------------------

(defn invoke-step
  "Invoke a single step function with appropriate dispatch.

   Parameters:
   - binding: step binding from binder {:fn f :arity A :captures [...] ...}
   - captures: vector of captured groups from regex match
   - ctx: execution context {:step step-map :scenario scenario-ctx}

   Returns:
   - {:status :passed :scenario <new-ctx>}
   - {:status :pending :scenario <ctx>}
   - {:status :failed :scenario <ctx> :error {:type ... :message ...}}"
  [binding captures ctx]
  (let [step-fn (:fn binding)
        arity (or (:arity binding) 0)
        capture-count (count captures)
        ctx-aware? (= arity (inc capture-count))
        args (if ctx-aware?
               (conj (vec captures) ctx)
               (vec captures))]
    (try
      (let [result (apply step-fn args)]
        (cond
          ;; :pending → step pending, scenario fails
          (= :pending result)
          {:status :pending
           :scenario (:scenario ctx)}

          ;; map → new scenario context
          (map? result)
          {:status :passed
           :scenario result}

          ;; nil → ctx unchanged, step passed
          (nil? result)
          {:status :passed
           :scenario (:scenario ctx)}

          ;; anything else → invalid return type
          :else
          {:status :failed
           :scenario (:scenario ctx)
           :error {:type :step/invalid-return
                   :message (str "Step returned invalid type: " (type result)
                                 ". Expected map, nil, or :pending.")
                   :value result}}))

      (catch Throwable t
        {:status :failed
         :scenario (:scenario ctx)
         :error {:type :step/exception
                 :message (ex-message t)
                 :exception-class (.getName (class t))
                 :data (when (instance? clojure.lang.ExceptionInfo t)
                         (ex-data t))}}))))

;; -----------------------------------------------------------------------------
;; Synthetic Step Handling
;; -----------------------------------------------------------------------------

(defn- synthetic-step?
  "Check if a bound step or step result is synthetic (macro wrapper).
   Works with both bound-step (has :step key) and step-result (has :step key)."
  [step-or-result]
  (true? (-> step-or-result :step :step/synthetic?)))

(defn- wrapper-step?
  "Check if a bound step or step result is a macro wrapper (synthetic with :role :call)."
  [step-or-result]
  (and (synthetic-step? step-or-result)
       (= :call (-> step-or-result :step :step/macro :role))))

(defn- get-wrapper-child-count
  "Get the number of children for a wrapper step or step result."
  [step-or-result]
  (-> step-or-result :step :step/macro :step-count))

;; -----------------------------------------------------------------------------
;; Scenario Execution
;; -----------------------------------------------------------------------------

(defn- make-step-result
  "Create a step result map."
  [bound-step status scenario-ctx error]
  (cond-> {:step (:step bound-step)
           :binding (dissoc (:binding bound-step) :fn)  ;; Don't include fn in result
           :status status}
    error (assoc :error error)))

(defn- rollup-status
  "Determine rollup status from child statuses.
   Rules: any failed → failed, any pending (no fail) → pending, else passed."
  [statuses]
  (cond
    (some #(= :failed %) statuses) :failed
    (some #(= :pending %) statuses) :pending
    :else :passed))

(defn- rollup-wrapper-statuses
  "Post-process step results to roll up wrapper statuses from children.
   Wrapper's status is derived from its children's statuses."
  [step-results]
  (let [results-vec (vec step-results)
        n (count results-vec)]
    (loop [i 0
           output []]
      (if (>= i n)
        output
        (let [result (nth results-vec i)]
          (if-not (wrapper-step? result)
            ;; Regular or expanded step - pass through
            (recur (inc i) (conj output result))
            ;; Wrapper step - find children and roll up
            (let [child-count (get-wrapper-child-count result)
                  children (subvec results-vec (inc i) (min n (+ i 1 child-count)))
                  child-statuses (map :status children)
                  rolled-status (rollup-status child-statuses)
                  updated-wrapper (assoc result :status rolled-status)]
              (recur (inc i) (conj output updated-wrapper)))))))))

(defn execute-scenario
  "Execute all steps for a single scenario/pickle plan.

   Parameters:
   - plan: run plan from binder {:plan/id ... :plan/steps [...] ...}
   - initial-ctx: starting scenario context (usually {})

   Returns:
   - {:status :passed|:failed|:pending|:skipped
      :plan plan
      :steps [{:step ... :status ... :error ...} ...]
      :scenario-ctx <final-ctx>}

   Semantics:
   - Steps execute in order, threading scenario-ctx
   - On failure/pending, remaining steps are skipped
   - Synthetic steps (macro wrappers) pass without execution
   - Wrapper status rolls up from children (any fail → fail, any pending → pending)"
  [plan initial-ctx]
  (if-not (:plan/runnable? plan)
    ;; Plan not runnable (binding failures) - mark all steps skipped
    {:status :skipped
     :plan plan
     :steps (mapv #(make-step-result % :skipped nil nil) (:plan/steps plan))
     :scenario-ctx initial-ctx}

    ;; Execute steps with fail-fast
    (let [raw-results
          (loop [remaining-steps (:plan/steps plan)
                 executed-steps []
                 scenario-ctx initial-ctx
                 scenario-status :passed]
            (if (empty? remaining-steps)
              ;; All steps done
              {:status scenario-status
               :steps executed-steps
               :scenario-ctx scenario-ctx}

              (let [bound-step (first remaining-steps)]
                (if (not= :passed scenario-status)
                  ;; Previous step failed/pending - skip remaining
                  (recur (rest remaining-steps)
                         (conj executed-steps (make-step-result bound-step :skipped scenario-ctx nil))
                         scenario-ctx
                         scenario-status)

                  ;; Check if synthetic step (macro wrapper)
                  (if (synthetic-step? bound-step)
                    ;; Synthetic step - mark passed without execution
                    (recur (rest remaining-steps)
                           (conj executed-steps (make-step-result bound-step :passed scenario-ctx nil))
                           scenario-ctx
                           :passed)

                    ;; Regular step - execute
                    (let [ctx {:step (:step bound-step)
                               :scenario scenario-ctx}
                          result (invoke-step (:binding bound-step)
                                              (-> bound-step :binding :captures)
                                              ctx)
                          step-result (make-step-result bound-step
                                                        (:status result)
                                                        (:scenario result)
                                                        (:error result))]
                      (recur (rest remaining-steps)
                             (conj executed-steps step-result)
                             (:scenario result)
                             (:status result))))))))

          ;; Roll up wrapper statuses from children
          rolled-steps (rollup-wrapper-statuses (:steps raw-results))
          ;; Recalculate scenario status based on all non-wrapper steps
          final-status (let [non-wrapper-statuses (map :status
                                                        (remove #(wrapper-step? %) rolled-steps))]
                         (rollup-status non-wrapper-statuses))]

      {:status final-status
       :plan plan
       :steps rolled-steps
       :scenario-ctx (:scenario-ctx raw-results)})))

;; -----------------------------------------------------------------------------
;; Suite Execution
;; -----------------------------------------------------------------------------

(defn execute-suite
  "Execute all scenarios in a suite.

   Parameters:
   - plans: seq of run plans from binder
   - opts: options map (reserved for future use)

   Returns:
   - {:scenarios [{:status ... :plan ... :steps ...} ...]
      :counts {:passed N :failed N :pending N :skipped N}
      :status :passed|:failed}

   Semantics:
   - Each scenario starts with fresh ctx ({})
   - Suite continues after scenario failure
   - Suite :passed only if all scenarios :passed"
  ([plans] (execute-suite plans {}))
  ([plans opts]
   (let [results (mapv #(execute-scenario % {}) plans)
         counts (frequencies (map :status results))
         suite-passed? (every? #(= :passed (:status %)) results)]
     {:scenarios results
      :counts {:passed (get counts :passed 0)
               :failed (get counts :failed 0)
               :pending (get counts :pending 0)
               :skipped (get counts :skipped 0)}
      :status (if suite-passed? :passed :failed)})))
