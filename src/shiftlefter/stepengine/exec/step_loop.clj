(ns shiftlefter.stepengine.exec.step-loop
  "Per-step invocation and per-scenario execution loop. Threads scenario
   ctx through the bound steps, classifies each (skipped / synthetic /
   regular), rolls up wrapper statuses, and computes the final scenario
   result.

   Internal: external callers should go through
   `shiftlefter.stepengine.exec` (the facade) which re-exports
   `invoke-step` and `execute-scenario` from this ns."
  (:require [shiftlefter.runner.events :as events]
            [shiftlefter.stepengine.exec.provisioning :as prov]))

;; -----------------------------------------------------------------------------
;; Step Invocation
;; -----------------------------------------------------------------------------

(defn invoke-step
  "Invoke a single step function with appropriate dispatch.

   Parameters:
   - binding: step binding from binder {:fn f :arity A :captures [...] ...}
   - captures: vector of captured groups from regex match
   - ctx: execution context {:step step-map :scenario scenario-ctx}

   ## Dispatch Rules

   Based on step function arity (A) vs capture count (C):
   - `A == C` → call with captures only (no ctx)
   - `A == C+1` → call with ctx as FIRST arg, then captures

   ## Context Shape

   The ctx passed to step functions is:
   - Flat scenario state (accumulated from previous steps)
   - Step metadata available via `(meta ctx)` with `:step/*` keys

   Returns:
   - {:status :passed :scenario <new-ctx>}
   - {:status :pending :scenario <ctx>}
   - {:status :failed :scenario <ctx> :error {:type ... :message ...}}"
  [binding captures ctx]
  (let [step-fn (:fn binding)
        arity (or (:arity binding) 0)
        capture-count (count captures)
        ctx-aware? (= arity (inc capture-count))
        ;; Extract scenario and step from nested ctx
        scenario-ctx (:scenario ctx)
        step (:step ctx)
        ;; Attach step metadata to ctx (not in ctx map, in metadata)
        ctx-with-meta (with-meta (or scenario-ctx {})
                        {:step/arguments (:step/arguments step)
                         :step/text (:step/text step)
                         :step/keyword (:step/keyword step)})
        ;; ctx-first: [ctx-with-meta & captures]
        args (if ctx-aware?
               (into [ctx-with-meta] captures)
               (vec captures))]
    (try
      (let [result (apply step-fn args)]
        (cond
          ;; :pending → step pending, scenario fails
          (= :pending result)
          {:status :pending
           :scenario scenario-ctx}

          ;; map → new scenario context
          (map? result)
          {:status :passed
           :scenario result}

          ;; nil → ctx unchanged, step passed
          (nil? result)
          {:status :passed
           :scenario scenario-ctx}

          ;; anything else → invalid return type
          :else
          {:status :failed
           :scenario scenario-ctx
           :error {:type :step/invalid-return
                   :message (str "Step returned invalid type: " (type result)
                                 ". Expected map, nil, or :pending.")
                   :value result}}))

      (catch Throwable t
        {:status :failed
         :scenario scenario-ctx
         :error {:type :step/exception
                 :message (ex-message t)
                 :exception-class (.getName (class t))
                 :data (when (instance? clojure.lang.ExceptionInfo t)
                         (ex-data t))}}))))

;; -----------------------------------------------------------------------------
;; SVO Event Emission
;; -----------------------------------------------------------------------------

(defn- emit-svo-event!
  "Emit :step/svo event if step has SVO metadata.

   Only emits if:
   - bound-step has :svo in binding
   - opts contains :bus and :run-id

   Event payload includes:
   - :subject, :verb, :object, :interface from SVO
   - :interface-type looked up from interfaces config
   - :step-text, :location from step"
  [bound-step opts]
  (let [svo (get-in bound-step [:binding :svo])
        bus (:bus opts)
        run-id (:run-id opts)]
    (when (and svo bus run-id)
      (let [step (:step bound-step)
            interface-name (:interface svo)
            interfaces (:interfaces opts)
            interface-type (get-in interfaces [interface-name :type])
            payload {:subject (:subject svo)
                     :verb (:verb svo)
                     :object (:object svo)
                     :interface interface-name
                     :interface-type interface-type
                     :step-text (:step/text step)
                     :location (select-keys (:step/location step) [:uri :line])}]
        (events/publish! bus (events/make-event :step/svo run-id payload))))))

;; -----------------------------------------------------------------------------
;; Synthetic Step Handling (wrapper-only helpers; the base `synthetic-step?`
;; lives in provisioning so the eager phase can see it)
;; -----------------------------------------------------------------------------

(defn- wrapper-step?
  "Check if a bound step or step result is a macro wrapper (synthetic with :role :call)."
  [step-or-result]
  (and (prov/synthetic-step? step-or-result)
       (= :call (-> step-or-result :step :step/macro :role))))

(defn- get-wrapper-child-count
  "Get the number of children for a wrapper step or step result."
  [step-or-result]
  (-> step-or-result :step :step/macro :step-count))

;; -----------------------------------------------------------------------------
;; Scenario Execution
;; -----------------------------------------------------------------------------

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

(defn- classify-and-execute-step
  "Execute a single bound step within scenario context.

   Handles all step types:
   - Skipped (previous step failed/pending)
   - Synthetic (macro wrapper, auto-pass)
   - Regular (provision capability, emit event, invoke)

   Returns {:step-result ... :scenario-ctx ... :status ...} for loop recur."
  [bound-step scenario-ctx scenario-status interfaces registry opts]
  (cond
    ;; Previous step failed/pending → skip
    (not= :passed scenario-status)
    {:step-result (prov/make-step-result bound-step :skipped scenario-ctx nil)
     :scenario-ctx scenario-ctx
     :status scenario-status}

    ;; Synthetic step (macro wrapper) → auto-pass
    (prov/synthetic-step? bound-step)
    {:step-result (prov/make-step-result bound-step :passed scenario-ctx nil)
     :scenario-ctx scenario-ctx
     :status :passed}

    ;; Regular step → provision + execute
    :else
    (let [provision-result (prov/ensure-capability scenario-ctx bound-step interfaces registry)]
      (if (:error provision-result)
        ;; Provisioning failed
        {:step-result (prov/make-step-result bound-step :failed scenario-ctx
                                             (:error provision-result))
         :scenario-ctx scenario-ctx
         :status :failed}
        ;; Execute step
        (do
          (emit-svo-event! bound-step opts)
          (let [provisioned-ctx (:ok provision-result)
                ctx {:step (:step bound-step) :scenario provisioned-ctx}
                result (invoke-step (:binding bound-step)
                                    (-> bound-step :binding :captures)
                                    ctx)]
            {:step-result (prov/make-step-result bound-step (:status result)
                                                 (:scenario result) (:error result))
             :scenario-ctx (:scenario result)
             :status (:status result)}))))))

(defn- finalize-scenario-results
  "Roll up wrapper statuses and compute final scenario status."
  [raw-results plan]
  (let [rolled-steps (rollup-wrapper-statuses (:steps raw-results))
        non-wrapper-statuses (map :status (remove #(wrapper-step? %) rolled-steps))
        final-status (rollup-status non-wrapper-statuses)]
    {:status final-status
     :plan plan
     :steps rolled-steps
     :scenario-ctx (:scenario-ctx raw-results)}))

(defn execute-scenario
  "Execute all steps for a single scenario/pickle plan.

   Parameters:
   - plan: run plan from binder {:plan/id ... :plan/steps [...] ...}
   - initial-ctx: starting scenario context (usually {})
   - opts: optional execution options
     - :interfaces — interface config map
     - :adapter-registry — custom adapter registry (nil → default); lets
       test orchestration inject pre-built capabilities. See runner/setup.clj.
     - :provisioning — `:eager` (sl-aa5 scoped-eager phase) or `:lazy`
       (per-step on first touch). Absent ⇒ lazy (REPL default). The
       runner threads `:eager` from `[:runner :provisioning]` config.

   Returns:
   - {:status :passed|:failed|:pending|:skipped
      :plan plan
      :steps [{:step ... :status ... :error ...} ...]
      :scenario-ctx <final-ctx>}

   Semantics:
   - Steps execute in order, threading scenario-ctx
   - On failure/pending, remaining steps are skipped
   - Synthetic steps (macro wrappers) pass without execution
   - Wrapper status rolls up from children (any fail → fail, any pending → pending)
   - Auto-provisioning: if step has :svo with :interface, capability is provisioned"
  ([plan initial-ctx] (execute-scenario plan initial-ctx nil))
  ([plan initial-ctx opts]
   (if-not (:plan/runnable? plan)
     ;; Plan not runnable (binding failures) - mark all steps skipped
     {:status :skipped
      :plan plan
      :steps (mapv #(prov/make-step-result % :skipped nil nil) (:plan/steps plan))
      :scenario-ctx initial-ctx}

     ;; Scoped-eager phase (sl-aa5): when opts say :eager, provision every
     ;; (interface, subject) the bound steps will touch before the first
     ;; step runs. Lazy ensure-capability calls inside the loop no-op
     ;; afterwards. Bare callers (REPL) get :lazy by default.
     (let [interfaces (:interfaces opts)
           registry   (:adapter-registry opts)
           eager?     (and (= :eager (:provisioning opts)) interfaces)
           eager-result (if eager?
                          (prov/eager-provision-scenario plan initial-ctx interfaces registry)
                          {:ok initial-ctx})]
       (if (:error eager-result)
         (prov/eager-failure-result plan (:error eager-result))
         ;; Execute steps with fail-fast
         (let [provisioned-ctx (:ok eager-result)
               raw-results
               (loop [remaining-steps (:plan/steps plan)
                      executed-steps []
                      scenario-ctx provisioned-ctx
                      scenario-status :passed]
                 (if (empty? remaining-steps)
                   {:status scenario-status
                    :steps executed-steps
                    :scenario-ctx scenario-ctx}
                   (let [{:keys [step-result scenario-ctx status]}
                         (classify-and-execute-step (first remaining-steps)
                                                    scenario-ctx scenario-status
                                                    interfaces registry opts)]
                     (recur (rest remaining-steps)
                            (conj executed-steps step-result)
                            scenario-ctx
                            status))))]
           (finalize-scenario-results raw-results plan)))))))
