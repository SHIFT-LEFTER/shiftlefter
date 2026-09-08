(ns shiftlefter.stepengine.exec.cleanup
  "Capability cleanup and suite-level orchestration. After each scenario
   runs, ephemeral capabilities are closed via their adapter's
   `:cleanup` hook; persistent capabilities are left alone.

   Internal: external callers should go through
   `shiftlefter.stepengine.exec` (the facade) which re-exports
   `execute-suite` from this ns."
  (:require [babashka.fs :as fs]
            [clojure.tools.logging :as log]
            [shiftlefter.adapters.registry :as registry]
            [shiftlefter.attachments :as attachments]
            [shiftlefter.capabilities.ctx :as cap]
            [shiftlefter.stepengine.exec.capture :as capture]
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

   The adapter invocation itself goes through
   `registry/cleanup-capability` — the single low-level cleanup seam
   (sl-kh5r). This fn keeps only run-path enrichment: handle selection,
   subject-key parsing, and mapping the raw result to an action.

   Returns:
   - {:action :closed :interface <name>} on success
   - {:action :close-failed :interface <name> :error <msg>} on failure —
     either the adapter THREW, or it returned a structured
     {:error {:message ...}} (the browser adapters never throw; they catch
     and return, so the return value must be inspected — sl-c6wk)
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
      (let [adapter-name (:adapter interface-config)]
        (try
          ;; 2-arity for default registry, 3-arity when custom — same
          ;; backward-compat reasoning as provision-capability above.
          (let [result (if registry
                         (registry/cleanup-capability adapter-name handle registry)
                         (registry/cleanup-capability adapter-name handle))
                err    (:error result)]
            (cond
              ;; :adapter/unknown comes only from the registry lookup, never
              ;; from an adapter's :cleanup — it means no adapter ran, so it
              ;; reads as :skipped, not :close-failed.
              (= :adapter/unknown (:type err))
              {:action :skipped
               :interface cap-name
               :reason (str "Unknown adapter: " adapter-name)}

              err
              ;; :message is the sl-9vag adapter contract; pr-str is a
              ;; fallback so a message-less error map still reads as text.
              (let [msg (or (:message err) (pr-str err))]
                (log/warn "Capability cleanup failed for" cap-name
                          "(" adapter-name "):" msg)
                {:action :close-failed
                 :interface cap-name
                 :adapter adapter-name
                 :error msg})

              :else
              {:action :closed
               :interface cap-name
               :adapter adapter-name}))
          (catch Exception e
            (log/warn "Capability cleanup threw for" cap-name
                      "(" adapter-name "):" (ex-message e))
            {:action :close-failed
             :interface cap-name
             :adapter adapter-name
             :error (ex-message e)}))))))

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
   :error` with the (first) failure on `:error`. Valid After attachment
   claims (sl-l9nj) land on `:attachments`, finalized against the world
   (missing files marked `:attachment/error` + one stderr warn each —
   outcome untouched: the reference is data, the file is world).

   Capture (sl-3q9r): when a capture policy is active and the run has a
   results dir, per-step capture opts thread into execute-scenario (the
   step_loop firing site), and scenario-scoped drain kinds fire here —
   post-steps, PRE-Afters, so After hooks observe the drain refs and the
   honest pre-cleanup state. Capture refs precede hook-claim refs on the
   scenario's :attachments (chronological)."
  [plan opts]
  (let [start-ns  (System/nanoTime)
        entries   (when (:plan/runnable? plan) (:plan/hooks plan))
        capture?  (and (:plan/runnable? plan)
                       (:artifacts-root opts)
                       (capture/capture-active? (:capture-policy opts)))
        sid       (when (or (seq entries) capture?) (hooks/scenario-identity plan))
        ;; sl-l9nj: the scenario's artifact home, pre-created before any
        ;; hook runs so both phases can write into it. When hooks exist OR
        ;; capture is active (sl-3q9r first widened the gate), and the run
        ;; has a results dir; a creation failure warns and degrades to no
        ;; :artifacts-dir (hooks check, don't assume; capture skips).
        artifacts-dir (when (and (or (seq entries) capture?)
                                 (:artifacts-root opts))
                        (try
                          (attachments/ensure-scenario-dir!
                           (:artifacts-root opts)
                           (:name sid)
                           (-> plan :plan/pickle :pickle/id))
                          (catch java.io.IOException e
                            (binding [*out* *err*]
                              (println (str "WARN: could not create artifacts dir for '"
                                            (:name sid) "': " (ex-message e))))
                            nil)))
        ;; Hook-payload extras (the sl-l9nj single-map extension point);
        ;; sl-08f7 adds :seed — the run seed, handed to every hook payload.
        extras    (cond-> nil
                    artifacts-dir (assoc :artifacts-dir artifacts-dir)
                    (:run-seed opts) (assoc :seed (:run-seed opts)))
        ;; sl-3q9r: the per-scenario capture opts — threaded into the step
        ;; loop for the instant sites, reused below for the drains.
        capture-opts (when (and capture? artifacts-dir)
                       {:policy (:capture-policy opts)
                        :artifacts-dir artifacts-dir
                        :rel-prefix (str "attachments/"
                                         (fs/file-name artifacts-dir))
                        :interfaces (:interfaces opts)
                        :registry (:adapter-registry opts)})
        step-opts (cond-> opts capture-opts (assoc :capture capture-opts))
        ;; sl-08f7: steps read the run seed off ctx — seeded here so it's
        ;; present before any hook contribution merges in. Bare/REPL callers
        ;; (no cleanup wrapper) own their ctx and are unaffected.
        initial-ctx (if-let [sd (:run-seed opts)] {:seed sd} {})
        {befores-ctx :ctx :keys [frames] before-records :records before-error :error}
        (hooks/run-befores! entries sid initial-ctx extras)

        result    (if before-error
                    (hooks/error-scenario-result plan befores-ctx before-error)
                    (step-loop/execute-scenario plan befores-ctx step-opts))
        ;; Scenario-scoped capture drains (sl-3q9r): post-steps, pre-Afters.
        ;; :on-failure drains fire only when the scenario is red; the
        ;; :every-step tail sweep always runs (emitting only non-empty).
        ;; A drain failure on an otherwise-GREEN scenario is D4 material.
        {drain-refs :refs drain-failures :failures}
        (when capture-opts
          (capture/drain-at-scenario-end!
           (:scenario-ctx result) capture-opts
           (contains? #{:failed :error} (:status result))))
        result    (cond-> result
                    (seq drain-refs)
                    (update :attachments (fnil into []) drain-refs)
                    (and (seq drain-failures) (= :passed (:status result)))
                    (-> (assoc :status :error)
                        (assoc :error (capture/capture-error drain-failures))))
        ;; The in-flight result handed to Afters — richer than the envelope
        ;; (hooks are in-process trusted code; invariant 3 governs the
        ;; reporter seam, not hook payloads).
        in-flight (cond-> result
                    (seq before-records) (assoc :hooks before-records))
        {after-records :records after-errors :errors claimed :attachments}
        (hooks/run-afters! frames sid (:scenario-ctx result) in-flight extras)

        ;; Finalize claims against the filesystem before capability
        ;; cleanup — the earliest instant the claim set is complete.
        {:keys [refs missing]} (when (seq claimed)
                                 (attachments/finalize-refs!
                                  (:artifacts-root opts) claimed))
        _ (when (seq missing)
            (binding [*out* *err*]
              (doseq [path missing]
                (println (str "WARN: attachment file not found: " path
                              " (claimed by an After hook of '"
                              (:name sid) "')")))))
        hook-records (into (vec before-records) after-records)
        result    (cond-> result
                    (seq hook-records) (assoc :hooks hook-records)
                    ;; Hook-claim refs APPEND after capture refs (sl-3q9r
                    ;; — chronological: drains fire pre-Afters).
                    (seq refs) (update :attachments (fnil into []) refs)
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

;; -----------------------------------------------------------------------------
;; Halt on observation failure (sl-kowi, Ruling 4)
;; -----------------------------------------------------------------------------

(defn- observation-failure?
  "True when a finalized scenario result is observation-class: status
   :error with the attribution carrying `:error/class :observation`."
  [result]
  (and (= :error (:status result))
       (step-loop/observation-error? (:error result))))

(defn- halted-scenario-result
  "The result for a scenario that was never STARTED because the run halted
   (`:halt-on-observation-failure`): plain :skipped, every step :skipped —
   the non-runnable-plan shape (step_loop execute-scenario). No per-
   scenario marker; the suite result's `:halted` record carries the why."
  [plan]
  {:status :skipped
   :plan plan
   :steps (mapv #(prov/make-step-result % :skipped nil nil) (:plan/steps plan))
   :scenario-ctx {}})

(defn- halt-record
  "The suite-level `:halted` record for the FIRST observation-class
   finalization: `{:after-scenario N :scenario <name>}`, N = 1-based plan
   index of the triggering scenario."
  [idx plan]
  {:after-scenario (inc idx)
   :scenario (-> plan :plan/pickle :pickle/name)})

(defn- run-or-skip
  "Run `plan` (plan index `idx`) unless the halt gate is already set, in
   which case return the skipped result without starting it. After a REAL
   run, arm the gate (first trigger wins) when the result is
   observation-class and the policy is on. Both branches fire `on-complete`
   themselves ONLY when `fire?` — pool tasks hand results back over the
   queue and let the coordinator fire it (invariant 1)."
  [halted halt? plan idx opts fire? run-fn]
  (let [result (if @halted
                 (halted-scenario-result plan)
                 (let [r (run-fn plan)]
                   (when (and halt? (observation-failure? r))
                     ;; Armed BEFORE the completion is handed back, so any
                     ;; task that starts after this one returns sees it —
                     ;; stop-starting is exact; nothing in flight is touched.
                     (compare-and-set! halted nil (halt-record idx plan)))
                   r))]
    (when-let [on-complete (and fire? (:on-scenario-complete opts))]
      (on-complete result))
    result))

(defn- execute-plans-sequential
  "The :max-parallel 1 path — plan order, one at a time, on the coordinator.
   Byte-identical to the pre-halt runner when the gate never arms."
  [plans opts halted]
  (let [halt? (true? (:halt-on-observation-failure opts))]
    (vec (map-indexed
          (fn [idx plan]
            (run-or-skip halted halt? plan idx opts true
                         #(execute-scenario-with-cleanup % opts)))
          plans))))

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

   Halt (sl-kowi, Ruling 4): tasks are all pre-submitted; each checks the
   `halted` gate FIRST and, once it is armed, returns a skipped result
   without starting — STOP STARTING, never kill in flight. Scenarios
   already running complete and keep their verdicts. If the gate is armed
   by the time the pool drains, the whole serial tail is skipped.

   Returns the results vector in PLAN order."
  [plans opts n halted]
  (let [on-complete  (:on-scenario-complete opts)
        halt?        (true? (:halt-on-observation-failure opts))
        indexed      (map-indexed vector plans)
        serial?      (fn [[_idx plan]]
                       (true? (get-in plan [:plan/schedule :serial?])))
        serial-plans (filter serial? indexed)
        pool-plans   (remove serial? indexed)
        completions  (LinkedBlockingQueue.)
        executor     (Executors/newFixedThreadPool n)
        guarded-run  (fn [plan]
                       (try
                         (execute-scenario-with-cleanup plan opts)
                         (catch Throwable t
                           (prov/eager-failure-result
                            plan
                            {:type :scenario/harness-crash
                             :message (str "Escaped scenario execution: "
                                           (ex-message t))
                             :exception-class (.getName (class t))}))))]
    (try
      (doseq [[idx plan] pool-plans]
        (let [task (bound-fn* ; conveyance: the sl-aa5 future pattern (R6)
                    (fn []
                      (let [result (run-or-skip halted halt? plan idx opts
                                                false guarded-run)]
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
                      (assoc acc idx
                             (run-or-skip halted halt? plan idx opts true
                                          #(execute-scenario-with-cleanup % opts))))
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
     - :halt-on-observation-failure — boolean, default false (sl-kowi,
       Ruling 4). When true: after any scenario finalizes observation-
       class (:error with `:error/class :observation`), no further
       scenario STARTS — in-flight scenarios (N>1) run to completion and
       keep their verdicts; every unstarted scenario (the rest of the
       queue AND the @serial tail) becomes :skipped, all steps :skipped.
       The exit code is 4 either way; the halt changes wall-clock and
       report noise, never the verdict.
     - :halt-gate — optional shared gate atom (sl-78u4, Warden W3). Under
       `:halt-on-observation-failure :run` the runner creates ONE atom per
       invocation and threads it through every setup group's execute-suite
       call, so a halt in one group is seen by the rest. Absent (single
       suite, or :group scope) a fresh per-suite atom is created here.

   Returns:
   - {:scenarios [{:status ... :plan ... :steps ... :capability-cleanup ...} ...]
      :counts {:passed N :failed N :pending N :skipped N
               :error N        ;; only when positive — infrastructure failure
                               ;; family: hook/provisioning/capture/harness
                               ;; (sl-esq, sl-7c7i)
               :unobserved N}  ;; only when positive — :error scenarios of
                               ;; observation class (sl-kowi); DISJOINT
                               ;; from :error
      :halted {:after-scenario N :scenario name}  ;; only when the halt
                                                  ;; policy fired
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
         ;; The halt gate (sl-kowi): nil until the first observation-class
         ;; finalization arms it with the halt record. Inert unless
         ;; :halt-on-observation-failure is on. The runner hands in a
         ;; SHARED atom under :run scope (sl-78u4) — one gate, many groups.
         halted (or (:halt-gate opts) (atom nil))
         results
         (if (> max-parallel 1)
           (execute-plans-parallel plans opts max-parallel halted)
           ;; Sequential path (sl-q9wp acceptance 1: N=1 is byte-identical
           ;; to before when the halt gate never arms).
           (execute-plans-sequential plans opts halted))
         counts (frequencies (map :status results))
         ;; :unobserved (sl-kowi) is carved OUT of the :error tally: the two
         ;; buckets are disjoint — "error" = harness machinery broke (fix
         ;; harness); "unobserved" = the witness went dark (check
         ;; environment). Both reach exit 4.
         unobserved (count (filter observation-failure? results))
         error (- (get counts :error 0) unobserved)
         suite-passed? (every? #(= :passed (:status %)) results)]
     (cond-> {:scenarios results
              ;; :error (sl-esq; family widened to hook/provisioning/capture/
              ;; harness, sl-7c7i) and :unobserved (sl-kowi) are emitted only
              ;; when positive so error-free runs keep the historical
              ;; four-key map byte-identical.
              :counts (cond-> {:passed (get counts :passed 0)
                               :failed (get counts :failed 0)
                               :pending (get counts :pending 0)
                               :skipped (get counts :skipped 0)}
                        (pos? error) (assoc :error error)
                        (pos? unobserved) (assoc :unobserved unobserved))
              :status (if suite-passed? :passed :failed)}
       @halted (assoc :halted @halted)))))
