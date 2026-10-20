(ns shiftlefter.stepengine.exec.cleanup
  "Capability cleanup and suite-level orchestration. After each scenario
   runs, ephemeral capabilities are closed via their adapter's
   `:cleanup` hook; persistent capabilities are left alone.

   Internal: external callers should go through
   `shiftlefter.stepengine.exec` (the facade) which re-exports
   `execute-suite` from this ns."
  (:require [babashka.fs :as fs]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [shiftlefter.adapters.registry :as registry]
            [shiftlefter.attachments :as attachments]
            [shiftlefter.capabilities.ctx :as cap]
            [shiftlefter.stepengine.exec.abort :as abort]
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

(defn- internal-scenario-crash!
  "sl-io05 proof instrument (`--sl-internal-scenario-crash=SUBSTRING`,
   undocumented — the sl-7wv internal-flag convention): THROW from inside
   the per-scenario weave, outside every user-code catch, when the pickle
   name contains the substring. The defect class it stands in for — a bug
   in ShiftLefter's own weave code — has no natural repro; this makes the
   runner-crash route reachable on every scheduler path from a cold
   `sl run`."
  [plan opts]
  (when-let [substring (:internal-scenario-crash-on opts)]
    (let [scenario-name (str (-> plan :plan/pickle :pickle/name))]
      (when (str/includes? scenario-name substring)
        (throw (ex-info (str "sl-internal-crash: deliberate per-scenario escape at "
                             (pr-str scenario-name))
                        {:internal-scenario-crash-on substring}))))))

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
  (internal-scenario-crash! plan opts)
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

(defn- measured-outcome
  "What a @blocked scenario's run actually measured (sl-190x C2):
   `{:status :failed|:unobserved :at-step k :message …}` — the first
   failed leaf step's 1-based position in :steps and its message, or the
   scenario-level attribution when no step carries the error. A second
   regression behind the tracked one stays visible on every surface."
  [result]
  (let [steps (:steps result)
        idx (first (keep-indexed (fn [i s] (when (= :failed (:status s)) i)) steps))
        err (or (:error result) (when idx (:error (nth steps idx))))]
    (cond-> {:status (if (observation-failure? result) :unobserved (:status result))}
      idx (assoc :at-step (inc idx))
      (:message err) (assoc :message (:message err)))))

(defn xpass-message
  "The imperative an XPASS carries (sl-190x): the tag's claim about the
   app is what is wrong."
  [{:keys [tag ref]}]
  (str "blocked scenario passed: remove " tag
       (when ref (str " or reopen " ref))))

(defn- expected-failure
  "Apply a plan's @blocked hold (sl-190x) to its measured result. The cut
   is by WHO FAILED: the tag excuses the SUT's behavior at the boundary —
   a :failed verdict (incl. :sut-classed) or an observation-class :error
   (an endpoint that never answers IS the tracked bug) is HELD, with the
   measured outcome on the record and the status left as measured. It
   never excuses the harness: a hook/provisioning/capture :error
   stays :error (exit 4, tag visible); :pending stays pending. A :passed
   verdict is XPASS — the claim is false — rendered as :failed with the
   :xpass record carrying the imperative. No :plan/hold → identity."
  [result plan]
  (if-let [hold (:plan/hold plan)]
    (let [hold (dissoc hold :location)
          status (:status result)]
      (cond
        (= :passed status)
        (assoc result
               :status :failed
               :xpass (assoc hold :message (xpass-message hold)))

        (or (= :failed status) (observation-failure? result))
        (assoc result :held (assoc hold :outcome (measured-outcome result)))

        :else result))
    result))

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
  "Run `plan` (plan index `idx`) unless the halt gate is already set — or
   the run abort has been requested (sl-617t; read beside the atom at this
   same site, one check) — in which case return the skipped result without
   starting it. An abort-skipped scenario is additionally held
   (`:held {:reason :aborted}`); the abort wins when both are set. After a
   REAL run, arm the gate (first trigger wins) when the result is
   observation-class and the policy is on. Never fires `on-complete` — the
   scheduler does, on the coordinator thread, for every route (invariant
   1): pool tasks hand results back over the queue, the inline phase gets
   its result as the return value."
  [halted halt? plan idx opts]
  (cond
    (abort/requested?)
    (assoc (halted-scenario-result plan) :held {:reason :aborted})

    @halted
    (halted-scenario-result plan)

    :else
    (let [r (-> (do (abort/scenario-started!)
                    (try (execute-scenario-with-cleanup plan opts)
                         (finally (abort/scenario-finished!))))
                ;; sl-190x: the @blocked inversion, at the one
                ;; site every path's measured result passes.
                (expected-failure plan))]
      (when (and halt? (observation-failure? r))
        ;; Armed BEFORE the completion is handed back, so any
        ;; task that starts after this one returns sees it —
        ;; stop-starting is exact; nothing in flight is touched.
        (compare-and-set! halted nil (halt-record idx plan)))
      r)))

;; sl-io05: the one map that crosses the engine → runner boundary on the
;; crash route (`:on-scenario-crash`'s argument; the crash report's
;; `:scenario`).
(s/def :scenario-crash/name string?)
(s/def :scenario-crash/source-file (s/nilable string?))
(s/def :scenario-crash/line (s/nilable pos-int?))
(s/def ::crashed-scenario
  (s/keys :req-un [:scenario-crash/name]
          :opt-un [:scenario-crash/source-file :scenario-crash/line]))

(defn- crashed-scenario
  "The identity of the scenario whose execution crashed the runner
   (sl-io05): what `:on-scenario-crash` is called with — name, feature
   file, line. Pure data off the plan; no pickle id (random per run, so it
   would make the same defect read differently run to run)."
  [plan]
  (select-keys (hooks/scenario-identity plan) [:name :source-file :line]))

(defn- execute-plans
  "The one scheduler (sl-q9wp; the separate :max-parallel 1 path collapsed
   into it at sl-io05). Two phases:

   1. POOL — every plan without `{:plan/schedule {:serial? true}}` runs on a
      fixed pool of `n` platform threads (browser scenarios are blocking IO;
      n is a resource bound). Tasks are `bound-fn*`-wrapped so workers convey
      the coordinator's dynamic bindings — the warm daemon's *out*/*err*
      capture is thread-local, and a raw executor thread would print to the
      JVM default streams instead of the client (R6).
   2. INLINE — after the pool FULLY drains, serial plans run one at a time in
      plan order, inline on the coordinator (one lane; @serial = exclusivity,
      nothing more).

   At `n` = 1 EVERY plan is an inline plan: the pool set is empty, no
   executor is created, and scenarios run in plan order on the coordinator
   thread — not a one-thread pool. The same holds at any `n` when every
   plan is serial.

   `:on-scenario-complete` fires on the COORDINATOR thread at ACTUAL
   completion order in both phases (invariant 1 for both planes); workers
   only hand completed results back over a queue. The runner's plan-order
   release buffer (sl-dgk seam) reorders the report plane; the bus keeps
   actual order + :seq.

   Runner crash (sl-io05): a Throwable ESCAPING the per-scenario machinery
   is a ShiftLefter defect — every piece of user code (steps, hooks,
   adapter factories) is caught inside execute-scenario-with-cleanup, so
   only our own weave can throw out of it. It is never a scenario verdict:
   it crashes the run, NOW, identically on every route. A pool task cannot
   simply throw (its completion would be swallowed and the coordinator's
   countdown would hang), so the task's WHOLE body is guarded — the
   scenario run and run-or-skip's own code alike (a throw in the latter
   was a latent hang before the guard moved out here) — and hands back a
   `{::crashed t}` sentinel in the result slot; the coordinator rethrows
   the ORIGINAL throwable on take. The inline phase catches and rethrows
   the same way. Both call `:on-scenario-crash` with the crashed scenario's
   identity first, so the crash report can say where. The rethrow then
   reaches the crash path below like any other coordinator throw.

   Halt (sl-kowi, Ruling 4): tasks are all pre-submitted; each checks the
   `halted` gate FIRST and, once it is armed, returns a skipped result
   without starting — STOP STARTING, never kill in flight. Scenarios
   already running complete and keep their verdicts. If the gate is armed
   by the time the pool drains, the whole serial tail is skipped.

   Crash (sl-8orx): a Throwable escaping the COORDINATOR's body here (an
   on-complete callback throwing — the report plane's loud-failure
   contract — or an interrupt on `.take`) means nobody will ever collect
   the pool's remaining completions. The finally then arms the same
   `halted` gate with `{:reason :crash}` FIRST — a worker that has already
   dequeued a task but not yet passed run-or-skip's gate check no-ops — and
   calls `shutdownNow` instead of the graceful `shutdown`: queued tasks are
   dropped and in-flight workers are interrupted. Best effort, by design:
   an interruptible wait (Thread/sleep, poll-until, a promise deref) is cut
   short and the step fails; blocking socket IO finishes on its own; the
   scenario's cleanup still runs on its worker thread; and no in-flight
   result reaches a reporter (the coordinator is gone). Normal completion
   keeps today's graceful `shutdown`.

   Abort (sl-617t): the run abort is READ at run-or-skip's gate check
   beside this same atom (`abort/requested?`) and inside the step loop —
   one mechanism, two arming sources — and it NEVER reaches this finally:
   an aborted coordinator body runs to its end (every pre-submitted task
   returns a held result the instant it dequeues), so the pool drains on
   its own through the normal `shutdown`. No `shutdownNow` and no crash
   arming on abort — in-flight workers finish their current step, their
   Afters and their cleanup untouched.

   Returns the results vector in PLAN order."
  [plans opts n halted]
  (let [plans        (vec plans)
        on-complete  (:on-scenario-complete opts)
        on-crash     (:on-scenario-crash opts)
        ;; sl-8orx: flipped only when the coordinator's body ran to the end;
        ;; the finally reads it to tell a crash from a normal drain.
        completed?   (volatile! false)
        halt?        (true? (:halt-on-observation-failure opts))
        indexed      (map-indexed vector plans)
        serial?      (fn [[_idx plan]]
                       (or (= 1 n)
                           (true? (get-in plan [:plan/schedule :serial?]))))
        serial-plans (filter serial? indexed)
        pool-plans   (remove serial? indexed)
        completions  (LinkedBlockingQueue.)
        executor     (when (seq pool-plans) (Executors/newFixedThreadPool n))
        ;; sl-io05: name the crashed scenario, then rethrow the ORIGINAL
        ;; throwable. A throwing callback must not replace the crash it is
        ;; describing — it rides along as a suppressed exception.
        crash!       (fn [plan ^Throwable t]
                       (when on-crash
                         (try (on-crash (crashed-scenario plan))
                              (catch Throwable cb-err (.addSuppressed t cb-err))))
                       (throw t))
        finished     (fn [result]
                       (when on-complete (on-complete result))
                       result)
        run-inline   (fn [idx plan]
                       (try (run-or-skip halted halt? plan idx opts)
                            (catch Throwable t (crash! plan t))))]
    (try
      (doseq [[idx plan] pool-plans]
        (let [task (bound-fn* ; conveyance: the sl-aa5 future pattern (R6)
                    (fn []
                      (.put completions
                            [idx (try (run-or-skip halted halt? plan idx opts)
                                      (catch Throwable t {::crashed t}))])))]
          (.submit ^ExecutorService executor ^Runnable task)))
      (let [pool-results
            (loop [acc {} remaining (count pool-plans)]
              (if (zero? remaining)
                acc
                (let [[idx result] (.take completions)]
                  (when-let [t (::crashed result)]
                    (crash! (plans idx) t))
                  (recur (assoc acc idx (finished result)) (dec remaining)))))
            all-results
            (reduce (fn [acc [idx plan]]
                      (assoc acc idx (finished (run-inline idx plan))))
                    pool-results
                    serial-plans)
            results (mapv all-results (range (count plans)))]
        (vreset! completed? true)
        results)
      (finally
        (if @completed?
          (when executor (.shutdown ^ExecutorService executor))
          ;; Crash path (sl-8orx): gate first, then drop the queue and
          ;; interrupt in-flight workers — see the docstring. Armed on every
          ;; route, executor or not (sl-io05: one crash shape).
          (do (compare-and-set! halted nil {:reason :crash})
              (when executor (.shutdownNow ^ExecutorService executor))))))))

(defn execute-suite
  "Execute all scenarios in a suite.

   Parameters:
   - plans: seq of run plans from binder
   - opts: options map
     - :interfaces       — interface config
     - :adapter-registry — optional custom adapter registry (see execute-scenario)
     - :bus, :run-id     — event publishing
     - :max-parallel     — pos-int, default 1 (sl-q9wp). 1 = every
       scenario in plan order on the calling thread; no pool exists. N>1
       fans non-serial scenarios onto a fixed pool of N platform threads;
       plans carrying `{:plan/schedule {:serial? true}}` run one at a time,
       in plan order, after the pool fully drains (see execute-plans).
     - :on-scenario-crash — optional 1-arg fn (sl-io05). When a Throwable
       ESCAPES the per-scenario machinery — a ShiftLefter defect, never a
       scenario verdict — it is called ONCE on the coordinator thread with
       the crashed scenario's identity `{:name :source-file :line}`, and
       then the ORIGINAL throwable propagates out of execute-suite. Same
       behavior at every :max-parallel and for @serial plans. Not called
       when the throw came from `:on-scenario-complete` itself.
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
     - Abort (sl-617t) needs no opt: the process-level request
       (shiftlefter.stepengine.exec.abort) is read at the gate check and
       at every step boundary. Scenarios it kept from running (or stopped
       mid-way) are :skipped AND carry `:held {:reason :aborted}`; the
       suite's :halted record is `{:reason :aborted :after-scenario N}`
       (N = scenarios that reached a verdict), winning over an observation
       record when both fired.

   Returns:
   - {:scenarios [{:status ... :plan ... :steps ... :capability-cleanup ...} ...]
      :counts {:passed N :failed N :pending N :skipped N
               :error N        ;; only when positive — infrastructure failure
                               ;; family: hook/provisioning/capture
                               ;; (sl-esq, sl-7c7i)
               :unobserved N   ;; only when positive — :error scenarios of
                               ;; observation class (sl-kowi); DISJOINT
                               ;; from :error
               :held N         ;; only when positive — scenarios carrying a
                               ;; :held record: the run abort's (sl-617t,
                               ;; :skipped) and @blocked scenarios that
                               ;; failed as claimed (sl-190x, :failed or
                               ;; observation :error, status as measured);
                               ;; carved OUT of every verdict bucket the way
                               ;; :unobserved is out of :error
               :xpass N}       ;; only when positive — @blocked scenarios
                               ;; that PASSED (sl-190x): the claim is false;
                               ;; they are :failed and counted there too
      :halted {:after-scenario N :scenario name}  ;; only when the halt
                                                  ;; policy fired, or
                                                  ;; {:reason :aborted
                                                  ;;  :after-scenario N}
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
         results (execute-plans plans opts max-parallel halted)
         ;; :held is carved OUT of every verdict bucket: a scenario the
         ;; abort kept from running (sl-617t, :skipped) or a @blocked
         ;; scenario that failed as its tag claims (sl-190x, :failed /
         ;; observation :error, status kept as measured) counts under
         ;; :held only — the verdict tallies are over the rest.
         verdicts (remove :held results)
         counts (frequencies (map :status verdicts))
         ;; :unobserved (sl-kowi) is carved OUT of the :error tally: the two
         ;; buckets are disjoint — "error" = harness machinery broke (fix
         ;; harness); "unobserved" = the witness went dark (check
         ;; environment). Both reach exit 4.
         unobserved (count (filter observation-failure? verdicts))
         error (- (get counts :error 0) unobserved)
         held (count (filter :held results))
         ;; :xpass (sl-190x): @blocked scenarios that PASSED — the tag's
         ;; claim is false. They are :failed (and counted there); this is
         ;; the extra marker, absent-when-zero.
         xpass (count (filter :xpass results))
         skipped (get counts :skipped 0)
         ;; The abort's :halted record wins over an observation halt's
         ;; when both fired: it is the reason the REST of the invocation
         ;; stopped. N = scenarios that reached a verdict.
         halt (or (when (abort/requested?)
                    (abort/halt-record
                     (count (filter #(#{:passed :failed :pending :error} (:status %))
                                    results))))
                  @halted)
         suite-passed? (every? #(= :passed (:status %)) results)]
     (cond-> {:scenarios results
              ;; :error (sl-esq; family widened to hook/provisioning/capture,
              ;; sl-7c7i), :unobserved (sl-kowi) and :held
              ;; (sl-617t) are emitted only when positive so error-free
              ;; runs keep the historical four-key map byte-identical.
              :counts (cond-> {:passed (get counts :passed 0)
                               :failed (get counts :failed 0)
                               :pending (get counts :pending 0)
                               :skipped skipped}
                        (pos? error) (assoc :error error)
                        (pos? unobserved) (assoc :unobserved unobserved)
                        (pos? held) (assoc :held held)
                        (pos? xpass) (assoc :xpass xpass))
              :status (if suite-passed? :passed :failed)}
       halt (assoc :halted halt)))))
