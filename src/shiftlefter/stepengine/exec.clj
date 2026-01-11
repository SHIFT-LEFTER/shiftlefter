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
   - Suite: continue — after scenario fails, proceed to next scenario

   ## Browser Lifecycle (CLI)

   - CLI runs are safe-by-default: sessions always close after scenario
   - No surface persistence in CLI mode (0.2.5)
   - Cleanup runs even on scenario failure"
  (:require [shiftlefter.adapters.registry :as registry]
            [shiftlefter.browser.ctx :as browser.ctx]
            [shiftlefter.capabilities.ctx :as cap]
            [shiftlefter.runner.events :as events]
            [shiftlefter.webdriver.etaoin.session :as session]))

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
;; Auto-Provisioning
;; -----------------------------------------------------------------------------

(defn- provision-capability
  "Provision a capability for an interface.

   Parameters:
   - interface-name: keyword like :web, :api
   - interfaces: interface config map from opts {:web {:type :web :adapter :etaoin ...}}

   Returns:
   - {:ok {:impl <instance> :mode :ephemeral}} on success
   - {:error {:type :svo/provisioning-failed ...}} on failure"
  [interface-name interfaces]
  (let [interface-config (get interfaces interface-name)]
    (if-not interface-config
      {:error {:type :svo/provisioning-failed
               :interface interface-name
               :message (str "No interface config for " interface-name)
               :known (vec (keys interfaces))}}
      (let [adapter-name (:adapter interface-config)
            adapter-config (:config interface-config {})
            result (registry/create-capability adapter-name adapter-config)]
        (if (:error result)
          {:error {:type :svo/provisioning-failed
                   :interface interface-name
                   :adapter adapter-name
                   :adapter-error (get-in result [:error :message])}}
          {:ok {:impl (:ok result) :mode :ephemeral}})))))

(defn- ensure-capability
  "Ensure capability is available for step's interface.

   If step has :svoi with :interface, and capability not yet provisioned,
   provisions it automatically.

   Parameters:
   - scenario-ctx: current scenario context
   - bound-step: bound step with binding containing :svoi
   - interfaces: interface config map from opts

   Returns:
   - {:ok <updated-ctx>} with capability provisioned
   - {:error {...}} if provisioning fails"
  [scenario-ctx bound-step interfaces]
  (let [svoi (get-in bound-step [:binding :svoi])
        interface-name (:interface svoi)]
    (cond
      ;; No SVOI or no interface → no provisioning needed
      (or (nil? svoi) (nil? interface-name))
      {:ok scenario-ctx}

      ;; Capability already present → use existing
      (cap/capability-present? scenario-ctx interface-name)
      {:ok scenario-ctx}

      ;; No interfaces config → can't provision
      (nil? interfaces)
      {:error {:type :svo/provisioning-failed
               :interface interface-name
               :message "No interfaces config provided"}}

      ;; Provision capability
      :else
      (let [result (provision-capability interface-name interfaces)]
        (if (:error result)
          result
          (let [{:keys [impl mode]} (:ok result)]
            {:ok (cap/assoc-capability scenario-ctx interface-name impl mode)}))))))

;; -----------------------------------------------------------------------------
;; SVOI Event Emission
;; -----------------------------------------------------------------------------

(defn- emit-svoi-event!
  "Emit :step/svoi event if step has SVOI metadata.

   Only emits if:
   - bound-step has :svoi in binding
   - opts contains :bus and :run-id

   Event payload includes:
   - :subject, :verb, :object, :interface from SVOI
   - :interface-type looked up from interfaces config
   - :step-text, :location from step"
  [bound-step opts]
  (let [svoi (get-in bound-step [:binding :svoi])
        bus (:bus opts)
        run-id (:run-id opts)]
    (when (and svoi bus run-id)
      (let [step (:step bound-step)
            interface-name (:interface svoi)
            interfaces (:interfaces opts)
            interface-type (get-in interfaces [interface-name :type])
            payload {:subject (:subject svoi)
                     :verb (:verb svoi)
                     :object (:object svoi)
                     :interface interface-name
                     :interface-type interface-type
                     :step-text (:step/text step)
                     :location (select-keys (:step/location step) [:uri :line])}]
        (events/publish! bus (events/make-event :step/svoi run-id payload))))))

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
   - opts: optional execution options {:interfaces {...}}

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
   - Auto-provisioning: if step has :svoi with :interface, capability is provisioned"
  ([plan initial-ctx] (execute-scenario plan initial-ctx nil))
  ([plan initial-ctx opts]
   (if-not (:plan/runnable? plan)
     ;; Plan not runnable (binding failures) - mark all steps skipped
     {:status :skipped
      :plan plan
      :steps (mapv #(make-step-result % :skipped nil nil) (:plan/steps plan))
      :scenario-ctx initial-ctx}

     ;; Execute steps with fail-fast
     (let [interfaces (:interfaces opts)
           raw-results
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

                     ;; Regular step - ensure capability then execute
                     (let [provision-result (ensure-capability scenario-ctx bound-step interfaces)]
                       (if (:error provision-result)
                         ;; Provisioning failed - mark step failed
                         (let [step-result (make-step-result bound-step
                                                             :failed
                                                             scenario-ctx
                                                             (:error provision-result))]
                           (recur (rest remaining-steps)
                                  (conj executed-steps step-result)
                                  scenario-ctx
                                  :failed))

                         ;; Provisioning succeeded - emit event and execute step
                         (do
                           ;; Emit SVOI event before execution (if step has SVOI)
                           (emit-svoi-event! bound-step opts)
                           (let [provisioned-ctx (:ok provision-result)
                                 ctx {:step (:step bound-step)
                                      :scenario provisioned-ctx}
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
                                    (:status result)))))))))))

           ;; Roll up wrapper statuses from children
           rolled-steps (rollup-wrapper-statuses (:steps raw-results))
           ;; Recalculate scenario status based on all non-wrapper steps
           final-status (let [non-wrapper-statuses (map :status
                                                         (remove #(wrapper-step? %) rolled-steps))]
                          (rollup-status non-wrapper-statuses))]

       {:status final-status
        :plan plan
        :steps rolled-steps
        :scenario-ctx (:scenario-ctx raw-results)}))))

;; -----------------------------------------------------------------------------
;; Capability Cleanup
;; -----------------------------------------------------------------------------

(defn- cleanup-capability!
  "Clean up a single capability using its adapter.

   Parameters:
   - interface-name: keyword like :web, :api
   - capability-entry: {:impl <instance> :mode :ephemeral/:persistent}
   - interfaces: interface config map

   Returns:
   - {:action :closed :interface <name>} on success
   - {:action :close-failed :interface <name> :error <msg>} on failure
   - {:action :skipped :interface <name> :reason <msg>} if can't clean up"
  [interface-name capability-entry interfaces]
  (let [impl (:impl capability-entry)
        interface-config (get interfaces interface-name)]
    (if-not interface-config
      {:action :skipped
       :interface interface-name
       :reason "No interface config"}
      (let [adapter-name (:adapter interface-config)
            adapter (registry/get-adapter adapter-name)]
        (if (:error adapter)
          {:action :skipped
           :interface interface-name
           :reason (str "Unknown adapter: " adapter-name)}
          (try
            ((:cleanup adapter) impl)
            {:action :closed
             :interface interface-name
             :adapter adapter-name}
            (catch Exception e
              {:action :close-failed
               :interface interface-name
               :adapter adapter-name
               :error (ex-message e)})))))))

(defn- cleanup-ephemeral-capabilities!
  "Clean up all ephemeral capabilities in scenario context.

   Iterates all :cap/* keys with :mode :ephemeral and calls adapter cleanup.
   Persistent capabilities are skipped.

   Returns:
   - {:cleaned [<results>] :skipped [<names>]} for observability"
  [scenario-ctx interfaces]
  (let [ephemeral-names (cap/ephemeral-capabilities scenario-ctx)
        persistent-names (cap/persistent-capabilities scenario-ctx)]
    (if (empty? ephemeral-names)
      {:cleaned []
       :skipped persistent-names}
      {:cleaned (mapv (fn [iface-name]
                        (let [entry (cap/get-capability-entry scenario-ctx iface-name)]
                          (cleanup-capability! iface-name entry interfaces)))
                      ephemeral-names)
       :skipped persistent-names})))

;; Legacy browser cleanup (for backward compatibility with existing tests)
(defn- cleanup-scenario-browser!
  "Close any browser session in scenario context (legacy).

   Deprecated: Use cleanup-ephemeral-capabilities! instead.
   Kept for backward compatibility with browser.ctx-based tests."
  [scenario-ctx]
  (if-not (browser.ctx/browser-present? scenario-ctx)
    {:action :none}
    (let [browser (browser.ctx/get-active-browser scenario-ctx)]
      (try
        (session/close-session! browser)
        {:action :closed
         :session-id (:session browser)}
        (catch Exception e
          {:action :close-failed
           :error (ex-message e)})))))

;; -----------------------------------------------------------------------------
;; Suite Execution
;; -----------------------------------------------------------------------------

(defn- execute-scenario-with-cleanup
  "Execute a scenario and clean up capabilities afterward.

   Cleans up:
   1. All ephemeral capabilities (via adapter registry)
   2. Legacy browser sessions (for backward compatibility)

   Returns scenario result with :capability-cleanup and :browser-cleanup keys."
  [plan opts]
  (let [result (execute-scenario plan {} opts)
        scenario-ctx (:scenario-ctx result)
        interfaces (:interfaces opts)
        ;; New: clean up all ephemeral capabilities
        cap-cleanup (cleanup-ephemeral-capabilities! scenario-ctx interfaces)
        ;; Legacy: clean up browser.ctx-style browsers (for existing tests)
        browser-cleanup (cleanup-scenario-browser! scenario-ctx)]
    (assoc result
           :capability-cleanup cap-cleanup
           :browser-cleanup browser-cleanup)))

(defn execute-suite
  "Execute all scenarios in a suite.

   Parameters:
   - plans: seq of run plans from binder
   - opts: options map {:interfaces {...}}

   Returns:
   - {:scenarios [{:status ... :plan ... :steps ... :capability-cleanup ... :browser-cleanup ...} ...]
      :counts {:passed N :failed N :pending N :skipped N}
      :status :passed|:failed}

   Semantics:
   - Each scenario starts with fresh ctx ({})
   - Suite continues after scenario failure
   - Suite :passed only if all scenarios :passed
   - Auto-provisioning: capabilities created on demand per :interfaces config
   - Ephemeral capabilities cleaned up after each scenario
   - Persistent capabilities survive across scenarios"
  ([plans] (execute-suite plans {}))
  ([plans opts]
   (let [results (mapv #(execute-scenario-with-cleanup % opts) plans)
         counts (frequencies (map :status results))
         suite-passed? (every? #(= :passed (:status %)) results)]
     {:scenarios results
      :counts {:passed (get counts :passed 0)
               :failed (get counts :failed 0)
               :pending (get counts :pending 0)
               :skipped (get counts :skipped 0)}
      :status (if suite-passed? :passed :failed)})))
