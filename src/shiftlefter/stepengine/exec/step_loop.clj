(ns shiftlefter.stepengine.exec.step-loop
  "Per-step invocation and per-scenario execution loop. Threads scenario
   ctx through the bound steps, classifies each (skipped / synthetic /
   regular), rolls up wrapper statuses, and computes the final scenario
   result.

   Internal: external callers should go through
   `shiftlefter.stepengine.exec` (the facade) which re-exports
   `invoke-step` and `execute-scenario` from this ns."
  (:require [shiftlefter.capabilities.ctx :as cap]
            [shiftlefter.events :as events]
            [shiftlefter.step :as step]
            [shiftlefter.stepengine.bindings :as bindings]
            [shiftlefter.stepengine.exec.capture :as capture]
            [shiftlefter.stepengine.exec.provisioning :as prov]))

;; -----------------------------------------------------------------------------
;; Step Invocation
;; -----------------------------------------------------------------------------

(declare invoke-step*)

(defn- recognized-error-class
  "The :error/class a thrown ex-info's data carries, when it is one of the
   contract values (`shiftlefter.step/error-classes` — the one literal, sl-kowi);
   nil otherwise (absent, or an unrecognized value — both mean today's
   behavior; the raw ex-data still rides :data)."
  [data]
  (let [c (:error/class data)]
    (when (contains? step/error-classes c) c)))

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
        ;; Data-plane normalization (sl-yh7): strip value-slot quotes,
        ;; resolve {binding} tokens — by bind-time :slot-kinds, before the
        ;; stepdef fn ever sees the captures.
        normalized (bindings/normalize-captures captures (:slot-kinds binding)
                                                scenario-ctx)]
    (if-let [err (:error normalized)]
      {:status :failed
       :scenario scenario-ctx
       :error err}
      (invoke-step* step-fn ctx-aware? (:ok normalized) scenario-ctx step
                    (get-in binding [:svo :interface])))))

(defn- invoke-step*
  "The dispatch/apply half of invoke-step, after capture normalization.
   `svo-interface` is the bound SVO's resolved interface INSTANCE (sl-s2oa),
   stamped into ctx metadata as :step/interface so stepdef bodies can target
   the instance's capability; nil (no svo — REPL free mode, legacy stepdefs)
   leaves the key absent and accessors fall back to the type literal."
  [step-fn ctx-aware? captures scenario-ctx step svo-interface]
  (let [;; Attach step metadata to ctx (not in ctx map, in metadata)
        ctx-with-meta (with-meta (or scenario-ctx {})
                        (cond-> {:step/arguments (:step/arguments step)
                                 :step/text (:step/text step)
                                 :step/keyword (:step/keyword step)}
                          svo-interface
                          (assoc :step/interface svo-interface)))
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
        (let [data (when (instance? clojure.lang.ExceptionInfo t)
                     (ex-data t))
              error-class (recognized-error-class data)]
          {:status :failed
           :scenario scenario-ctx
           :error (cond-> {:type :step/exception
                           :message (ex-message t)
                           :exception-class (.getName (class t))
                           :data data}
                    error-class (assoc :error/class error-class))})))))

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
   - :step-text, :location from step

   The envelope carries :scenario/id (threaded via opts by execute-scenario,
   sl-q9wp R4) so parallel emit stays correlatable per scenario."
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
                     :location (select-keys (:step/location step) [:uri :line])}
            extras (when-let [sid (:scenario-id opts)]
                     {:scenario/id sid})]
        (events/publish! bus (events/make-event :step/svo run-id payload extras))))))

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
                  child-duration (reduce + 0.0 (keep :duration-ms children))
                  updated-wrapper (assoc result :status rolled-status
                                         :duration-ms child-duration)]
              (recur (inc i) (conj output updated-wrapper)))))))))

(defn- classify-and-execute-step
  "Execute a single bound step within scenario context.

   Handles all step types:
   - Skipped (previous step failed/pending)
   - Synthetic (macro wrapper, auto-pass)
   - Regular (provision capability, emit event, invoke)

   Returns {:step-result ... :scenario-ctx ... :status ...} (+
   :capture-failures when capture infrastructure failed, sl-3q9r) for
   loop recur. `step-index` is the step's 1-based position — capture
   filenames key on it."
  [bound-step step-index scenario-ctx scenario-status interfaces registry opts]
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
        ;; Provisioning failed. An :on-provision hook failure carries :ctx
        ;; with the already-live capability — thread it so cleanup can
        ;; close the impl (sl-uu7x). Capture does NOT fire on this path
        ;; (sl-3q9r): the failure is provisioning infrastructure, not a
        ;; step outcome — there is no settled instant to witness. The step
        ;; stays :failed here; finalize-scenario-results flips the SCENARIO
        ;; to :error off the provisioning-family error type (sl-7c7i).
        {:step-result (prov/make-step-result bound-step :failed scenario-ctx
                                             (:error provision-result))
         :scenario-ctx (or (:ctx provision-result) scenario-ctx)
         :status :failed}
        ;; Execute step
        (do
          (emit-svo-event! bound-step opts)
          (let [provisioned-ctx (:ok provision-result)
                ctx {:step (:step bound-step) :scenario provisioned-ctx}
                start-ns (System/nanoTime)
                result (invoke-step (:binding bound-step)
                                    (-> bound-step :binding :captures)
                                    ctx)
                duration-ms (/ (- (System/nanoTime) start-ns) 1e6)
                ;; Capture (sl-3q9r) fires HERE — after the step's clock
                ;; stopped (timing purity by placement: capture cost lands
                ;; on the ref's :attachment/duration-ms, never in step
                ;; durations) and against the step's resulting ctx, whose
                ;; capabilities are still live.
                captured (when-let [copts (:capture opts)]
                           (capture/capture-step!
                            (:scenario result) copts
                            {:step-index step-index
                             :svo (get-in bound-step [:binding :svo])
                             :failed? (= :failed (:status result))}))
                ;; Binding provenance (sl-yh7): names this step produced,
                ;; recorded in run evidence — never in the map itself.
                produced (let [before (get provisioned-ctx bindings/bindings-key)
                               after (get (:scenario result) bindings/bindings-key)]
                           (seq (keep (fn [[k v]]
                                        (when (or (not (contains? before k))
                                                  (not= v (get before k)))
                                          k))
                                      after)))]
            (cond-> {:step-result
                     (cond-> (assoc (prov/make-step-result
                                     bound-step (:status result)
                                     (:scenario result) (:error result))
                                    :duration-ms duration-ms)
                       produced (assoc :bindings/produced (vec produced))
                       (seq (:refs captured))
                       (assoc :attachments (:refs captured)))
                     :scenario-ctx (:scenario result)
                     :status (:status result)}
              (seq (:failures captured))
              (assoc :capture-failures (:failures captured)))))))))

(defn observation-error?
  "True when `error` (a step/scenario error map, or nil) carries the
   :observation class of the sl-kowi contract — the witness between spec
   and app went dark, so the step observed nothing about the app."
  [error]
  (= :observation (:error/class error)))

(defn- finalize-scenario-results
  "Roll up wrapper statuses and compute final scenario status.

   Provisioning flip (sl-7c7i, §937 row 3): a step bearing a
   capability-provisioning error makes the scenario :error, not :failed —
   broken harness is never a broken app. The step keeps :failed (steps
   never carry :error; rollup-status deliberately has no :error arm) and
   the scenario-level :error map carries the attribution, like the hook
   and capture paths.

   Observation flip (sl-kowi, F15): a failing step whose error carries
   `:error/class :observation` makes the scenario :error the same way —
   the step's error map IS the scenario attribution (same envelope as the
   provisioning flip; the class rides it, so counts can bucket the
   scenario as :unobserved). :sut/:assertion classes are recorded on the
   step and change nothing here — the scenario stays :failed.

   D4 (sl-3q9r): capture-infrastructure failures flip an otherwise-GREEN
   scenario to :error, capture-attributed — a green scenario whose
   promised evidence silently failed is green with missing evidence.
   Already-red (and pending) scenarios keep their status; the refs record
   the misses."
  [raw-results plan]
  (let [rolled-steps (rollup-wrapper-statuses (:steps raw-results))
        leaf-steps (remove wrapper-step? rolled-steps)
        final-status (rollup-status (map :status leaf-steps))
        capture-failures (:capture-failures raw-results)
        prov-error (some #(let [e (:error %)]
                            (when (prov/provisioning-error? e) e))
                         leaf-steps)
        obs-error (some #(let [e (:error %)]
                           (when (observation-error? e) e))
                        leaf-steps)]
    (cond-> {:status final-status
             :plan plan
             :steps rolled-steps
             :scenario-ctx (:scenario-ctx raw-results)}
      prov-error
      (-> (assoc :status :error)
          (assoc :error prov-error))
      (and obs-error (not prov-error))
      (-> (assoc :status :error)
          (assoc :error obs-error))
      (and (seq capture-failures) (= :passed final-status))
      (-> (assoc :status :error)
          (assoc :error (capture/capture-error capture-failures))))))

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
   - {:status :passed|:failed|:pending|:skipped|:error
      :plan plan
      :steps [{:step ... :status ... :error ...} ...]
      :scenario-ctx <final-ctx>}
   (:error — infrastructure failure family: capability provisioning
   failed, eager or lazy; carries a scenario-level :error map.)

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
     (let [opts       (assoc opts :scenario-id
                             (get-in plan [:plan/pickle :pickle/id]))
           interfaces (:interfaces opts)
           registry   (:adapter-registry opts)
           ;; Stash the run's interfaces config into scenario ctx (sl-3jr4):
           ;; step bodies resolve named locations against the interface's
           ;; :config :base-url. Done before eager provisioning so the stash
           ;; survives parallel-group delta merges.
           initial-ctx (cond-> initial-ctx
                         (seq interfaces) (cap/assoc-run-interfaces interfaces)
                         ;; Global :timing rung (sl-99u2) — same stash
                         ;; pattern; step/timing resolves against it.
                         (seq (:timing opts)) (cap/assoc-run-timing (:timing opts))
                         ;; Reserve the data plane (sl-yh7). `or` — Before
                         ;; hooks may already have mirrored contributions in.
                         :always (update bindings/bindings-key #(or % {})))
           eager?     (and (= :eager (:provisioning opts)) interfaces)
           eager-result (if eager?
                          (prov/eager-provision-scenario plan initial-ctx interfaces registry)
                          {:ok initial-ctx})]
       (if (:error eager-result)
         ;; The error's :ctx (partial provisioning, sl-uu7x) rides into the
         ;; failure result — falling back to initial-ctx so Before-hook
         ;; contributions survive either way — letting the After unwind see
         ;; live capabilities and cleanup close them.
         (prov/eager-failure-result plan (:error eager-result)
                                    (or (:ctx eager-result) initial-ctx))
         ;; Execute steps with fail-fast
         (let [provisioned-ctx (:ok eager-result)
               raw-results
               (loop [remaining-steps (:plan/steps plan)
                      executed-steps []
                      scenario-ctx provisioned-ctx
                      scenario-status :passed
                      capture-failures []]
                 (if (empty? remaining-steps)
                   {:status scenario-status
                    :steps executed-steps
                    :scenario-ctx scenario-ctx
                    :capture-failures capture-failures}
                   (let [{:keys [step-result scenario-ctx status]
                          step-capture-failures :capture-failures}
                         (classify-and-execute-step (first remaining-steps)
                                                    (inc (count executed-steps))
                                                    scenario-ctx scenario-status
                                                    interfaces registry opts)]
                     (recur (rest remaining-steps)
                            (conj executed-steps step-result)
                            scenario-ctx
                            status
                            (into capture-failures step-capture-failures)))))]
           (finalize-scenario-results raw-results plan)))))))
