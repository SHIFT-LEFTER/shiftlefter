(ns shiftlefter.stepengine.exec.cleanup
  "Capability cleanup and suite-level orchestration. After each scenario
   runs, ephemeral capabilities are closed via their adapter's
   `:cleanup` hook; persistent capabilities are left alone.

   Internal: external callers should go through
   `shiftlefter.stepengine.exec` (the facade) which re-exports
   `execute-suite` from this ns."
  (:require [shiftlefter.adapters.registry :as registry]
            [shiftlefter.capabilities.ctx :as cap]
            [shiftlefter.stepengine.exec.hooks :as hooks]
            [shiftlefter.stepengine.exec.provisioning :as prov]
            [shiftlefter.stepengine.exec.step-loop :as step-loop])
  (:import (java.util IdentityHashMap)
           (java.util.concurrent ExecutorService Executors LinkedBlockingQueue)))

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
  (let [;; Cleanup receives the handle, not the step-facing impl: wrapping
        ;; adapters (browser, sl-091) store a separate :cleanup-handle holding
        ;; the driver the :cleanup fn needs; non-wrapping adapters have none, so
        ;; fall back to :impl.
        handle (or (:cleanup-handle capability-entry) (:impl capability-entry))
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
            ((:cleanup adapter) handle)
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
  "Execute a scenario with its lifecycle hooks (sl-esq), then clean up its
   ephemeral capabilities.

   The weave: Befores -> execute-scenario (provisioning + steps) -> Afters
   -> capability cleanup. A Before failure skips execute-scenario entirely
   (no provisioning cost paid); Afters unwind the succeeded frames LIFO,
   BEFORE cleanup so they can reach live capabilities. Non-runnable plans
   (binding failures) pay no user lifecycle code. Bare/REPL callers going
   through exec/execute-scenario bypass hooks entirely — they have no plan
   scan and no :plan/hooks.

   Returns scenario result with `:capability-cleanup` populated; when hooks
   ran, `:hooks` carries their records and a hook failure sets `:status
   :error` with the (first) failure on `:error`."
  [plan opts]
  (let [start-ns  (System/nanoTime)
        entries   (when (:plan/runnable? plan) (:plan/hooks plan))
        sid       (when (seq entries) (hooks/scenario-identity plan))
        {befores-ctx :ctx :keys [frames] before-records :records before-error :error}
        (hooks/run-befores! entries sid {})

        result    (if before-error
                    (hooks/error-scenario-result plan befores-ctx before-error)
                    (step-loop/execute-scenario plan befores-ctx opts))
        ;; The in-flight result handed to Afters — richer than the envelope
        ;; (hooks are in-process trusted code; invariant 3 governs the
        ;; reporter seam, not hook payloads).
        in-flight (cond-> result
                    (seq before-records) (assoc :hooks before-records))
        {after-records :records after-errors :errors}
        (hooks/run-afters! frames sid (:scenario-ctx result) in-flight)

        hook-records (into (vec before-records) after-records)
        result    (cond-> result
                    (seq hook-records) (assoc :hooks hook-records)
                    ;; A failing :after makes the scenario :error even when
                    ;; all steps passed (round 2: harsh is correct). The
                    ;; first failure carries attribution; a pre-existing
                    ;; before-error keeps precedence.
                    (seq after-errors) (-> (assoc :status :error)
                                           (update :error #(or % (first after-errors)))))
        duration-ms (/ (- (System/nanoTime) start-ns) 1e6)
        scenario-ctx (:scenario-ctx result)
        interfaces  (:interfaces opts)
        registry    (:adapter-registry opts)
        cap-cleanup (cleanup-ephemeral-capabilities! scenario-ctx interfaces registry)]
    (assoc result :capability-cleanup cap-cleanup :duration-ms duration-ms)))

(defn- execute-plans-parallel
  "The N>1 scheduler (sl-q9wp). Two phases:

   1. POOL — every plan without `{:plan/schedule {:serial? true}}` runs on a
      fixed pool of `n` platform threads (browser scenarios are blocking IO;
      n is a resource bound). Tasks are `bound-fn*`-wrapped so workers convey
      the coordinator's dynamic bindings — the warm daemon's *out*/*err*
      capture is thread-local, and a raw executor thread would print to the
      JVM default streams instead of the client (R6).
   2. SERIAL — after the pool FULLY drains, serial plans run one at a time in
      plan order, inline on the coordinator (one lane; @serial = exclusivity,
      nothing more).

   `:on-scenario-complete` fires on the COORDINATOR thread at ACTUAL
   completion order in both phases (invariant 1 for both planes); workers
   only hand completed results back over a queue. The runner's plan-order
   release buffer (sl-dgk seam) reorders the report plane; the bus keeps
   actual order + :seq.

   A Throwable escaping a pool task would otherwise swallow its completion
   and hang the coordinator's countdown — execute-scenario-with-cleanup
   already catches step-level throws, so an escape here is a harness-level
   crash for that scenario and is converted to a failed result (first step
   bears the error, matching the eager-provisioning failure shape).

   Returns the results vector in PLAN order."
  [plans opts n]
  (let [on-complete  (:on-scenario-complete opts)
        indexed      (map-indexed vector plans)
        serial?      (fn [[_idx plan]]
                       (true? (get-in plan [:plan/schedule :serial?])))
        serial-plans (filter serial? indexed)
        pool-plans   (remove serial? indexed)
        completions  (LinkedBlockingQueue.)
        executor     (Executors/newFixedThreadPool n)]
    (try
      (doseq [[idx plan] pool-plans]
        (let [task (bound-fn* ; conveyance: the sl-aa5 future pattern (R6)
                    (fn []
                      (let [result
                            (try
                              (execute-scenario-with-cleanup plan opts)
                              (catch Throwable t
                                (prov/eager-failure-result
                                 plan
                                 {:type :scenario/harness-crash
                                  :message (str "Escaped scenario execution: "
                                                (ex-message t))
                                  :exception-class (.getName (class t))})))]
                        (.put completions [idx result]))))]
          (.submit ^ExecutorService executor ^Runnable task)))
      (let [pool-results
            (loop [acc {} remaining (count pool-plans)]
              (if (zero? remaining)
                acc
                (let [[idx result] (.take completions)]
                  (when on-complete (on-complete result))
                  (recur (assoc acc idx result) (dec remaining)))))
            all-results
            (reduce (fn [acc [idx plan]]
                      (let [result (execute-scenario-with-cleanup plan opts)]
                        (when on-complete (on-complete result))
                        (assoc acc idx result)))
                    pool-results
                    serial-plans)]
        (mapv all-results (range (count plans))))
      (finally
        (.shutdown executor)))))

(defn execute-suite
  "Execute all scenarios in a suite.

   Parameters:
   - plans: seq of run plans from binder
   - opts: options map
     - :interfaces       — interface config
     - :adapter-registry — optional custom adapter registry (see execute-scenario)
     - :bus, :run-id     — event publishing
     - :max-parallel     — pos-int, default 1 (sl-q9wp). 1 = today's
       sequential path, verbatim. N>1 fans non-serial scenarios onto a fixed
       pool of N platform threads; plans carrying
       `{:plan/schedule {:serial? true}}` run one at a time, in plan order,
       after the pool fully drains (see execute-plans-parallel).
     - :on-scenario-complete — optional 1-arg fn, called with each raw scenario
       result as that scenario ACTUALLY completes (sl-21z), always on the
       COORDINATOR thread — under :max-parallel > 1 that means actual
       completion order, not plan order. The engine stays reporter-ignorant:
       this is a plain callback, and the runner uses it to publish
       `:scenario/finished` on the observe plane. User-facing reporters
       are NOT driven from here — they run live but in plan order (sl-dgk
       puts report dispatch behind the runner's plan-order release buffer).

   Returns:
   - {:scenarios [{:status ... :plan ... :steps ... :capability-cleanup ...} ...]
      :counts {:passed N :failed N :pending N :skipped N
               :error N}   ;; only when positive — a lifecycle hook threw (sl-esq)
      :status :passed|:failed}

   `:scenarios` is in PLAN order at every :max-parallel.

   Semantics:
   - Each scenario starts with fresh ctx ({})
   - Suite continues after scenario failure
   - Suite :passed only if all scenarios :passed
   - Auto-provisioning: capabilities created on demand per :interfaces config
   - Ephemeral capabilities cleaned up after each scenario
   - Persistent capabilities survive across scenarios"
  ([plans] (execute-suite plans {}))
  ([plans opts]
   (let [max-parallel (or (:max-parallel opts) 1)
         results
         (if (> max-parallel 1)
           (execute-plans-parallel plans opts max-parallel)
           ;; Sequential path — unchanged (sl-q9wp acceptance 1: N=1 is
           ;; byte-identical to before by construction).
           (let [on-complete (:on-scenario-complete opts)
                 run-one (fn [plan]
                           (let [result (execute-scenario-with-cleanup plan opts)]
                             (when on-complete (on-complete result))
                             result))]
             (mapv run-one plans)))
         counts (frequencies (map :status results))
         suite-passed? (every? #(= :passed (:status %)) results)]
     {:scenarios results
      ;; :error (sl-esq, hook threw) is emitted only when positive so
      ;; hook-less runs keep the historical four-key map byte-identical.
      :counts (cond-> {:passed (get counts :passed 0)
                       :failed (get counts :failed 0)
                       :pending (get counts :pending 0)
                       :skipped (get counts :skipped 0)}
                (pos? (get counts :error 0))
                (assoc :error (get counts :error)))
      :status (if suite-passed? :passed :failed)})))
