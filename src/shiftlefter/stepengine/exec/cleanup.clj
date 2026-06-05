(ns shiftlefter.stepengine.exec.cleanup
  "Capability cleanup and suite-level orchestration. After each scenario
   runs, ephemeral capabilities are closed via their adapter's
   `:cleanup` hook; persistent capabilities are left alone.

   Internal: external callers should go through
   `shiftlefter.stepengine.exec` (the facade) which re-exports
   `execute-suite` from this ns."
  (:require [shiftlefter.adapters.registry :as registry]
            [shiftlefter.capabilities.ctx :as cap]
            [shiftlefter.stepengine.exec.step-loop :as step-loop])
  (:import (java.util IdentityHashMap)))

;; -----------------------------------------------------------------------------
;; Capability Cleanup
;; -----------------------------------------------------------------------------

(defn- cleanup-capability!
  "Clean up a single capability using its adapter.

   Handles subject-keyed capability names (e.g., :web.alice) by parsing
   to find the base interface name (:web) for adapter config lookup.

   Parameters:
   - cap-name: keyword like :web, :api, :web.alice (from all-capabilities)
   - capability-entry: {:impl <instance> :mode :ephemeral/:persistent}
   - interfaces: interface config map
   - registry: adapter registry (nil → default); same registry used at
     provisioning so a custom-registry impl is closed by its custom cleanup.

   Returns:
   - {:action :closed :interface <name>} on success
   - {:action :close-failed :interface <name> :error <msg>} on failure
   - {:action :skipped :interface <name> :reason <msg>} if can't clean up"
  [cap-name capability-entry interfaces registry]
  (let [impl (:impl capability-entry)
        ;; Parse subject-keyed names: :web.alice → {:interface :web :subject :alice}
        {:keys [interface]} (cap/parse-capability-name cap-name)
        interface-config (get interfaces interface)]
    (if-not interface-config
      {:action :skipped
       :interface cap-name
       :reason "No interface config"}
      (let [adapter-name (:adapter interface-config)
            ;; 2-arity for default registry, 3-arity when custom — same
            ;; backward-compat reasoning as provision-capability above.
            adapter (if registry
                      (registry/get-adapter adapter-name registry)
                      (registry/get-adapter adapter-name))]
        (if (:error adapter)
          {:action :skipped
           :interface cap-name
           :reason (str "Unknown adapter: " adapter-name)}
          (try
            ((:cleanup adapter) impl)
            {:action :closed
             :interface cap-name
             :adapter adapter-name}
            (catch Exception e
              {:action :close-failed
               :interface cap-name
               :adapter adapter-name
               :error (ex-message e)})))))))

(defn- cleanup-ephemeral-capabilities!
  "Clean up all ephemeral capabilities in scenario context.

   Iterates all :cap/* keys with :mode :ephemeral and calls adapter cleanup.
   Handles subject-keyed capabilities (e.g., :web.alice) by parsing to find
   the base interface for adapter lookup. Persistent capabilities are
   skipped.

   For `:shared-impl?` interfaces, multiple subject-keyed entries may
   reference the same impl (e.g. :cap/sms.alice and :cap/sms.bob both
   point at the same Twilio client). To avoid double-closing, we dedup
   by impl identity (`IdentityHashMap`) and run cleanup exactly once
   per unique impl. Subsequent identical-impl entries are reported as
   `:skipped` with reason `\"shared impl already cleaned\"`.

   Returns:
   - {:cleaned [<results>] :skipped [<names-or-results>]} for observability"
  [scenario-ctx interfaces registry]
  (let [ephemeral-names  (cap/ephemeral-capabilities scenario-ctx)
        persistent-names (cap/persistent-capabilities scenario-ctx)
        seen-impls       (IdentityHashMap.)
        ;; Partition ephemerals into (unique-by-impl) and (shared-duplicates).
        {:keys [unique duplicates]}
        (reduce (fn [acc cap-name]
                  (let [impl (:impl (cap/get-capability-entry scenario-ctx cap-name))]
                    (if (.containsKey seen-impls impl)
                      (update acc :duplicates conj cap-name)
                      (do (.put seen-impls impl true)
                          (update acc :unique conj cap-name)))))
                {:unique [] :duplicates []}
                ephemeral-names)
        skipped-shared (mapv (fn [cap-name]
                               {:action :skipped
                                :interface cap-name
                                :reason "shared impl already cleaned"})
                             duplicates)]
    (if (empty? unique)
      {:cleaned []
       :skipped (into persistent-names skipped-shared)}
      {:cleaned (mapv (fn [cap-name]
                        (let [entry (cap/get-capability-entry scenario-ctx cap-name)]
                          (cleanup-capability! cap-name entry interfaces registry)))
                      unique)
       :skipped (into persistent-names skipped-shared)})))

;; -----------------------------------------------------------------------------
;; Suite Execution
;; -----------------------------------------------------------------------------

(defn- execute-scenario-with-cleanup
  "Execute a scenario, then clean up its ephemeral capabilities.

   Returns scenario result with `:capability-cleanup` populated."
  [plan opts]
  (let [result      (step-loop/execute-scenario plan {} opts)
        scenario-ctx (:scenario-ctx result)
        interfaces  (:interfaces opts)
        registry    (:adapter-registry opts)
        cap-cleanup (cleanup-ephemeral-capabilities! scenario-ctx interfaces registry)]
    (assoc result :capability-cleanup cap-cleanup)))

(defn execute-suite
  "Execute all scenarios in a suite.

   Parameters:
   - plans: seq of run plans from binder
   - opts: options map
     - :interfaces       — interface config
     - :adapter-registry — optional custom adapter registry (see execute-scenario)
     - :bus, :run-id     — event publishing

   Returns:
   - {:scenarios [{:status ... :plan ... :steps ... :capability-cleanup ...} ...]
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
