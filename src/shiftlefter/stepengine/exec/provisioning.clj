(ns shiftlefter.stepengine.exec.provisioning
  "Capability provisioning for the step engine — both the per-step lazy
   primitive (`ensure-capability`) and the scenario-start scoped-eager
   phase (sl-aa5).

   Internal: callers are siblings in `shiftlefter.stepengine.exec.*`.
   No external code should require this namespace directly.

   Two small step-shape helpers (`synthetic-step?`, `make-step-result`)
   live here too because the eager phase reaches for them — keeping
   them in the same ns as `eager-failure-result` and
   `collect-provisioning-targets` is what lets the step-loop /
   provisioning split avoid a cross-feature forward-declare."
  (:require [shiftlefter.adapters.registry :as registry]
            [shiftlefter.capabilities.ctx :as cap]
            [shiftlefter.costume :as costume]))

;; -----------------------------------------------------------------------------
;; Step-shape helpers (shared with step_loop)
;; -----------------------------------------------------------------------------

(defn synthetic-step?
  "Check if a bound step or step result is synthetic (macro wrapper).
   Works with both bound-step (has :step key) and step-result (has :step key)."
  [step-or-result]
  (true? (-> step-or-result :step :step/synthetic?)))

(defn make-step-result
  "Create a step result map."
  [bound-step status _scenario-ctx error]
  (cond-> {:step (:step bound-step)
           :binding (dissoc (:binding bound-step) :fn)  ;; Don't include fn in result
           :status status}
    error (assoc :error error)))

;; -----------------------------------------------------------------------------
;; Auto-Provisioning
;; -----------------------------------------------------------------------------

(defn- provision-capability
  "Provision a capability for an interface.

   Parameters:
   - interface-name: keyword like :web, :api
   - interfaces: interface config map from opts {:web {:type :web :adapter :etaoin ...}}
   - registry: adapter registry map (defaults to registry/default-registry when nil).
     A custom registry lets test orchestration (e.g. setup.clj) hand the
     scenario a pre-built capability instance — see runner/setup.clj.

   The factory result is split into the step-facing `:impl` and the
   `:cleanup-handle` (sl-091): when the adapter declares `:impl-key`, the
   impl is `(get factory-result impl-key)` (e.g. the bare IBrowser) while the
   whole result is retained as the cleanup handle (it carries the driver the
   `:cleanup` fn needs). Adapters without `:impl-key` keep the result verbatim
   as the impl. A declared `:impl-key` must yield an impl satisfying the
   adapter's `:provides`; a mismatch fails provisioning here rather than at the
   first step.

   Returns:
   - {:ok {:impl <instance> :mode :ephemeral :cleanup-handle <result>}} on success
   - {:error {:type :svo/provisioning-failed ...}} on failure"
  [interface-name interfaces registry]
  (let [interface-config (get interfaces interface-name)]
    (if-not interface-config
      {:error {:type :svo/provisioning-failed
               :interface interface-name
               :message (str "No interface config for " interface-name)
               :known (vec (keys interfaces))}}
      (let [adapter-name   (:adapter interface-config)
            adapter-config (:config interface-config {})
            ;; Use 3-arity only when a custom registry was supplied. Keeps
            ;; pre-existing test stubs (2-arg with-redefs) working when no
            ;; custom registry flows through opts.
            result         (if registry
                             (registry/create-capability adapter-name adapter-config registry)
                             (registry/create-capability adapter-name adapter-config))]
        (if (:error result)
          (let [adapter-msg (get-in result [:error :message])]
            {:error {:type :svo/provisioning-failed
                     :interface interface-name
                     :adapter adapter-name
                     :message (str "Browser provisioning failed for :"
                                   (name interface-name) " (" (name adapter-name) ")"
                                   (when adapter-msg (str ": " adapter-msg)))
                     :adapter-error adapter-msg}})
          ;; Split factory result into step-facing impl + cleanup handle.
          (let [raw      (:ok result)
                ikey     (if registry
                           (registry/impl-key adapter-name registry)
                           (registry/impl-key adapter-name))
                impl     (if ikey (get raw ikey) raw)
                mismatch (if registry
                           (registry/check-extracted-impl adapter-name impl registry)
                           (registry/check-extracted-impl adapter-name impl))]
            (if mismatch
              {:error {:type :svo/provisioning-failed
                       :interface interface-name
                       :adapter adapter-name
                       :reason :adapter/impl-protocol-mismatch
                       :missing-protocols mismatch
                       :message (str "Adapter :" (name adapter-name) " for :"
                                     (name interface-name)
                                     " produced an impl that does not satisfy "
                                     (pr-str mismatch)
                                     " — check its :impl-key in the registry")}}
              {:ok {:impl impl :mode :ephemeral :cleanup-handle raw}})))))))

(defn- provision-costume
  "Provision a capability by attaching to the costume a subject `:wears` (sl-rnm).

   Launch-or-attach to the earmarked Chrome — the session the human launched
   and logged into once — instead of fresh-spawning. The capability is
   `:persistent` so scenario-end cleanup never tears the session down (that
   is the whole point: the authed session survives).

   One live wearer per costume per scenario: two WebDriver sessions cannot
   share one Chrome (etaoin limitation). The runner provisions one capability
   per (interface, subject), so a single wearer is the norm; a second wearer is
   rejected upstream in `ensure-capability-for-svo` (see
   `costume-already-worn-error`) before this fn is reached.

   Returns:
   - {:ok {:impl <CostumeBrowser> :mode :persistent}} on success
   - {:error {:type :svo/provisioning-failed ...}} on failure (missing/locked
     costume → the scenario fails cleanly via the normal provisioning error path)"
  [interface-name costume-name]
  (let [result (costume/connect-costume! costume-name)]
    (if (:error result)
      (let [costume-msg (get-in result [:error :message])]
        {:error {:type :svo/provisioning-failed
                 :interface interface-name
                 :costume costume-name
                 :message (str "Costume attach failed for :" (name interface-name)
                               " wearing :" (name costume-name)
                               (when costume-msg (str ": " costume-msg)))
                 :costume-error (:error result)}})
      {:ok {:impl (:browser result) :mode :persistent}})))

(defn- run-on-provision-hook
  "Invoke the adapter's `:on-provision` hook (if any) and wrap the result.

   Hook signature: `(fn [ctx impl] -> ctx)`. Adapters use this to seed
   per-interface scenario state right after `cap/assoc-capability`,
   without the engine special-casing each interface type.

   Parameters:
   - ctx:          ctx after `assoc-capability` and any browser bridge
   - impl:         the provisioned (or shared-impl reused) capability impl
   - adapter-name: keyword for registry lookup
   - registry:     custom registry map, or nil to use default

   Returns:
   - {:ok <ctx>}        — hook absent, or hook returned a new ctx
   - {:error {...}}     — hook threw; surfaces as
                          `:adapter/on-provision-failed` with adapter name
                          and message so the scenario fails cleanly."
  [ctx impl adapter-name registry]
  (let [hook (if registry
               (registry/on-provision adapter-name registry)
               (registry/on-provision adapter-name))]
    (if (nil? hook)
      {:ok ctx}
      (try
        {:ok (hook ctx impl)}
        (catch Throwable t
          {:error {:type    :adapter/on-provision-failed
                   :adapter adapter-name
                   :message (str "on-provision hook failed for adapter "
                                 adapter-name ": " (.getMessage t))
                   :cause   (.getMessage t)}})))))

(def ^:private worn-costumes-key
  "ctx key holding {costume-name wearer} for costumes provisioned this
   scenario. Internal bookkeeping — NOT under the `:cap/` namespace, so it
   never shows up in `cap/all-capabilities` or cleanup enumeration."
  ::worn-costumes)

(defn- costume-already-worn-error
  "One-wearer-per-costume guard (sl-s7t).

   Two subjects resolving to the same costume would attach two WebDriver
   sessions to one Chrome (etaoin can't share a session) — a silent
   double-attach that breaks the sessions. Fail cleanly instead.

   Returns an `:svo/provisioning-failed` error (so it flows through the
   existing provisioning error path and `format-provisioning-failed`), tagged
   `:reason :costume/already-worn` for precise matching."
  [interface-name costume-name subject existing-wearer]
  {:error {:type :svo/provisioning-failed
           :interface interface-name
           :costume costume-name
           :reason :costume/already-worn
           :message (str "subject " (pr-str subject) " wears costume "
                         (pr-str costume-name) ", already worn by "
                         (pr-str existing-wearer)
                         " this scenario; one wearer per costume")}})

(defn- ensure-capability-for-svo
  "Provision (interface, subject) into ctx if not already present.

   Pure function over an SVO map — used by both the per-step lazy path
   (`ensure-capability`) and the scenario-start scoped-eager phase
   (`eager-provision-scenario`). One mechanism, two strategies.

   Honors `:shared-impl?` via `find-existing-shared-impl` and runs the
   adapter's `:on-provision` hook (sl-yzu) on the freshly provisioned impl.

   Enforces one live wearer per costume per scenario (sl-s7t): a second
   subject resolving to an already-worn costume fails cleanly via
   `costume-already-worn-error` rather than double-attaching.

   Parameters:
   - scenario-ctx: current scenario context
   - svo:         {:interface <kw> :subject <kw or nil>}
   - interfaces:   interface config map from opts
   - registry:     adapter registry (nil → default); see provision-capability

   Returns:
   - {:ok <updated-ctx>} with capability provisioned (or already present)
   - {:error {...}} if provisioning fails"
  [scenario-ctx svo interfaces registry]
  (let [interface-name (:interface svo)
        subject (:subject svo)]
    (cond
      ;; No SVO or no interface → no provisioning needed
      (or (nil? svo) (nil? interface-name))
      {:ok scenario-ctx}

      ;; Capability already present for this subject → use existing
      ;; (subject-keyed check if subject present, else interface-only check)
      (if subject
        (cap/capability-present? scenario-ctx interface-name subject)
        (cap/capability-present? scenario-ctx interface-name))
      {:ok scenario-ctx}

      ;; No interfaces config → skip provisioning (step may still work
      ;; if capability was set up elsewhere, e.g., REPL or setup step)
      (nil? interfaces)
      {:ok scenario-ctx}

      ;; Provision capability — or reuse an already-provisioned shared impl
      ;; (`:shared-impl?` interfaces share one impl across subject-keyed
      ;; entries; the second subject's provision finds the first's impl).
      :else
      (let [interface-config (get interfaces interface-name)
            adapter-name     (:adapter interface-config)
            shared?          (:shared-impl? interface-config)
            costume          (:wears svo)
            ;; One-wearer-per-costume (sl-s7t): if this costume is already worn
            ;; by another (interface, subject) this scenario, refuse to attach
            ;; a second WebDriver session to the same Chrome.
            existing-wearer  (when costume
                               (get-in scenario-ctx [worn-costumes-key costume]))
            existing-impl    (when (and shared? (not costume))
                               (cap/find-existing-shared-impl scenario-ctx interface-name))
            ;; A subject that :wears a costume attaches to that authed session
            ;; (:persistent) — this wins over fresh-spawn and shared-impl.
            result           (cond
                               existing-wearer (costume-already-worn-error
                                                interface-name costume subject existing-wearer)
                               costume       (provision-costume interface-name costume)
                               existing-impl {:ok {:impl existing-impl :mode :ephemeral}}
                               :else         (provision-capability interface-name interfaces registry))]
        (if (:error result)
          result
          (let [{:keys [impl mode cleanup-handle]} (:ok result)
                cap-k (cap/capability-key interface-name subject)
                ctx' (-> scenario-ctx
                         (cap/assoc-capability interface-name impl mode subject)
                         ;; Step-facing impl and cleanup handle diverge for
                         ;; wrapping adapters (browser: IBrowser vs driver,
                         ;; sl-091). Stash the handle only when it differs, so
                         ;; cleanup can reach the driver while steps see the
                         ;; bare impl. Adapters where they coincide (SMS) stay
                         ;; as {:impl :mode}.
                         (cond-> (and cleanup-handle
                                      (not (identical? cleanup-handle impl)))
                           (assoc-in [cap-k :cleanup-handle] cleanup-handle))
                         ;; Record the costume as worn so a later wearer is caught.
                         (cond-> costume
                           (assoc-in [worn-costumes-key costume] (or subject interface-name))))]
            ;; Generic adapter `:on-provision` hook — adapters seed per-
            ;; interface scenario state (e.g., :sms/scenario-start-ts) here
            ;; rather than the engine special-casing each interface type.
            (run-on-provision-hook ctx' impl adapter-name registry)))))))

(defn ensure-capability
  "Ensure capability is available for step's interface and subject.

   Per-step lazy primitive: thin wrapper over `ensure-capability-for-svo`
   that pulls the SVO map out of the bound-step. No-ops when the cap is
   already in ctx (scoped-eager will have provisioned it at scenario
   start)."
  [scenario-ctx bound-step interfaces registry]
  (let [svo (get-in bound-step [:binding :svo])]
    (ensure-capability-for-svo scenario-ctx svo interfaces registry)))

;; -----------------------------------------------------------------------------
;; Scoped-Eager Provisioning (sl-aa5)
;;
;; At scenario start, walk the bound steps and provision every distinct
;; (interface, subject) pair before the first step runs. Lets bad creds
;; / config fail fast instead of mid-scenario, and makes per-interface
;; timing markers (e.g. :sms/scenario-start-ts) honest.
;;
;; Layered on top of the lazy primitive: per-step `ensure-capability`
;; calls keep firing exactly as today and no-op when the cap is already
;; present in ctx. One mechanism (`ensure-capability-for-svo`), two
;; strategies. REPL paths (no `:provisioning` opt) bypass this phase.
;; -----------------------------------------------------------------------------

(defn collect-provisioning-targets
  "Walk plan's bound steps and return a deduplicated vector of SVO
   targets `[{:interface :web :subject :alice} ...]`, in first-seen
   order. Steps with no `:svo` or no `:interface`, and synthetic macro
   wrappers, are skipped.

   Public since sl-q9wp: the scheduling gate (runner/schedule.clj) inspects
   the same targets to auto-serialize costume-provisioning and shared-impl
   plans — one walk, two consumers, identical semantics."
  [plan]
  (let [seen   (volatile! #{})
        result (volatile! (transient []))]
    (doseq [bs (:plan/steps plan)
            :when (not (synthetic-step? bs))
            :let  [svo (get-in bs [:binding :svo])
                   iface (:interface svo)
                   subj  (:subject svo)
                   key   [iface subj]]
            :when (and iface (not (contains? @seen key)))]
      (vswap! seen conj key)
      ;; Preserve :wears so the eager phase attaches to costumes too (sl-rnm);
      ;; the lazy path reads the full bound svo, so only this rebuild needs it.
      (vswap! result conj! (cond-> {:interface iface :subject subj}
                             (:wears svo) (assoc :wears (:wears svo)))))
    (persistent! @result)))

(defn- provision-group-sequential
  "Provision every target for one interface, sequentially threading ctx.

   Sequential within an interface so `:shared-impl?` handoff works:
   Alice's call constructs the impl, Bob's call finds it via
   `find-existing-shared-impl`. Returns {:ok ctx'} or {:error {...}};
   stops on first error (fail-fast within the group)."
  [initial-ctx targets interfaces registry]
  (reduce (fn [acc target]
            (let [ctx (:ok acc)
                  r (ensure-capability-for-svo ctx target interfaces registry)]
              (if (:error r)
                (reduced r)
                r)))
          {:ok initial-ctx}
          targets))

(defn- aggregate-eager-errors
  "Combine per-group provisioning errors. Single error passes through
   verbatim; multiple wrap as `:scenario/eager-provisioning-failed`
   with each original error preserved under `:errors`."
  [errors]
  (if (= 1 (count errors))
    (first errors)
    {:type :scenario/eager-provisioning-failed
     :message (str (count errors) " interfaces failed scoped-eager provisioning")
     :errors (vec errors)}))

(defn- merge-group-deltas
  "Merge per-group ctx results into `initial-ctx`.

   Each group ran from `initial-ctx` independently (different :cap/iface
   namespaces don't collide), so the union of their additions is the
   final ctx. We compute each group's delta (keys not in initial-ctx)
   and apply them — preserves any keys initial-ctx already had."
  [initial-ctx group-ctxs]
  (reduce (fn [acc group-ctx]
            (let [delta (apply dissoc group-ctx (keys initial-ctx))]
              (merge acc delta)))
          initial-ctx
          group-ctxs))

(defn eager-provision-scenario
  "Scoped-eager provisioning phase. Returns {:ok ctx'} or {:error {...}}.

   Targets are grouped by interface. Different interfaces are provisioned
   in parallel (futures); within an interface, sequentially so
   `:shared-impl?` reuse works. Errors from all groups are collected
   before returning so callers see every failure, not just the first."
  [plan initial-ctx interfaces registry]
  (let [targets (collect-provisioning-targets plan)
        ;; group-by preserves group order via the reducing function;
        ;; we don't depend on ordering across groups (parallel anyway).
        groups  (vals (group-by :interface targets))]
    (cond
      (empty? groups)
      {:ok initial-ctx}

      (= 1 (count groups))
      (provision-group-sequential initial-ctx (first groups) interfaces registry)

      :else
      (let [results (->> groups
                         (mapv (fn [group]
                                 (future
                                   (provision-group-sequential
                                    initial-ctx group interfaces registry))))
                         (mapv deref))
            errors  (->> results (keep :error))]
        (if (seq errors)
          {:error (aggregate-eager-errors errors)}
          {:ok (merge-group-deltas initial-ctx (mapv :ok results))})))))

(defn eager-failure-result
  "Build a scenario result for an eager-provisioning failure: scenario
   :failed, first step bears the error (matches today's lazy first-step
   semantics), remaining steps :skipped."
  [plan error]
  (let [steps (:plan/steps plan)
        [first-step & rest-steps] steps
        step-results (cons (make-step-result first-step :failed nil error)
                           (mapv #(make-step-result % :skipped nil nil) rest-steps))]
    {:status :failed
     :plan plan
     :steps (vec step-results)
     :scenario-ctx {}}))
