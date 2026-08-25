(ns shiftlefter.stepengine.exec.capture
  "The declarative capture engine (sl-3q9r, N5 stage B).

   Config-driven artifact capture — screenshot/console/page-source as a
   config line, framework-owned. Kinds are ADAPTER-CONTRIBUTED (the
   registry's :capture entry); policies are #{:never :on-failure
   :every-step}, default :never.

   Firing semantics (Chair-ratified 2026-08-04):
   - INSTANT kinds, :on-failure — at the failing step's instant (the
     step_loop seam, AFTER the step's duration is measured — timing
     purity by placement), capturing ALL live capabilities supporting the
     kind: failure is rare, and Bob's screen may explain Alice's failure.
   - INSTANT kinds, :every-step — after each step, the ACTING SUBJECT's
     capability only (the film strip follows the actor; SVO names the
     actor). A no-SVO step (vanilla mode) captures ALL live — the porting
     promise beats literal actor-following, and single-browser suites
     make it cheap (the docs state the linear cost).
   - SCENARIO-SCOPED kinds (console — a DESTRUCTIVE returns-and-clears
     drain): under :on-failure, drain once post-steps pre-Afters, only
     when the scenario is red. Under :every-step, drain PER STEP like an
     instant kind (each read-and-clear naturally holds exactly the
     entries since the last — step-grain provenance for free) PLUS one
     final TAIL SWEEP post-steps over all live, emitted only when
     non-empty (the film strip must not drop the last frame).
   - PLACEMENT IS THE LABEL: per-step refs ride the step's :attachments,
     drain/tail refs the scenario's.

   D4 (capture failure): a capture fn throw records a ref with
   :attachment/error AND — when the scenario is otherwise green — flips
   it to :error, capture-attributed (a green scenario whose promised
   evidence silently failed is green with missing evidence, the disease
   this framework exists to make visible). Already-red scenarios keep
   their status; the ref records the miss.

   The engine owns timing, file writes, filenames, and ref construction;
   adapter capture fns only produce data ({:data :media-type :ext}, nil =
   nothing to capture). Capture refs are constructed COMPLETE — they skip
   attachments/finalize-refs!, which exists to check untrusted hook
   claims against the world; framework-written refs are true by
   construction (path relative by construction, bytes known, and a
   failed write is already a D4 failure).

   Leaf ns: requires attachments, capabilities.ctx, adapters.registry —
   no runner edge."
  (:require [clojure.spec.alpha :as s]
            [shiftlefter.adapters.registry :as registry]
            [shiftlefter.attachments :as attachments]
            [shiftlefter.capabilities.ctx :as cap])
  (:import [java.nio.file Files Path]
           [java.util IdentityHashMap]))

;; -----------------------------------------------------------------------------
;; Specs

(s/def ::policy (s/map-of keyword? #{:never :on-failure :every-step}))
(s/def ::artifacts-dir (s/and string? seq))
(s/def ::rel-prefix (s/and string? seq))
(s/def ::capture-opts
  (s/keys :req-un [::policy ::artifacts-dir ::rel-prefix]
          :opt-un [::interfaces ::registry]))
(s/def ::interfaces map?)
(s/def ::registry (s/nilable map?))

;; -----------------------------------------------------------------------------
;; Pure policy helpers

(defn capture-active?
  "True when any configured kind's policy is not :never."
  [policy]
  (boolean (some #(not= :never %) (vals policy))))

(s/fdef capture-active? :args (s/cat :policy (s/nilable map?)) :ret boolean?)

(defn unsupported-kinds
  "Configured ACTIVE kinds contributed by zero adapters in `registry`,
   sorted. The once-per-run warn feeds from this (never an error — the
   kind set is open and a custom registry may be in play, which is why
   the check runs against the group's LIVE registry, post-:start)."
  [policy registry]
  (let [contributed (into #{} (mapcat (comp keys :capture val)) registry)]
    (->> policy
         (filter (fn [[_kind pol]] (not= :never pol)))
         (map key)
         (remove contributed)
         sort
         vec)))

(s/fdef unsupported-kinds
  :args (s/cat :policy (s/nilable map?) :registry (s/nilable map?))
  :ret (s/coll-of keyword? :kind vector?))

(defn adapter-unsupported-kinds
  "Configured ACTIVE kinds contributed by at least one adapter in
   `registry` but by NONE of `used-adapters` (the adapters a group's
   plans actually touch), sorted. The per-group plan-time warn feeds
   from this (sl-sehc): registry-wide `unsupported-kinds` cannot see a
   per-adapter gap when another adapter in the same registry contributes
   the kind (the pure-Playwright silent-nothing and the mixed multi-group
   composition). Disjoint from `unsupported-kinds` by construction —
   kinds contributed by nobody stay the unknown-kind (typo) class.
   Empty `used-adapters` (a fully unbound suite) returns [] — nothing
   will be captured from, and unbound suites scream elsewhere."
  [policy used-adapters registry]
  (if (empty? used-adapters)
    []
    (let [contributed (into #{} (mapcat (comp keys :capture val)) registry)
          supported (into #{}
                          (mapcat #(keys (get-in registry [% :capture])))
                          used-adapters)]
      (->> policy
           (filter (fn [[_kind pol]] (not= :never pol)))
           (map key)
           (filter contributed)
           (remove supported)
           sort
           vec))))

(s/fdef adapter-unsupported-kinds
  :args (s/cat :policy (s/nilable map?)
               :used-adapters (s/nilable (s/coll-of keyword? :kind set?))
               :registry (s/nilable map?))
  :ret (s/coll-of keyword? :kind vector?))

(defn capture-error
  "Scenario-level error for D4: the FIRST capture failure carries the
   attribution (kind, capability, cause — same first-error-wins rule as
   hook errors). `failures` are the engine's failure records."
  [failures]
  (let [{:keys [kind cap-key interface subject message]} (first failures)]
    {:type :capture/failed
     :message (str "capture failed: " (name kind) " on " cap-key
                   (when message (str " — " message)))
     :data (cond-> {:kind kind :capability cap-key :interface interface}
             subject (assoc :subject subject))}))

(s/def ::type keyword?)
(s/def ::message string?)

(s/fdef capture-error
  :args (s/cat :failures (s/coll-of map? :min-count 1))
  :ret (s/keys :req-un [::type ::message]))

;; -----------------------------------------------------------------------------
;; Target enumeration

(defn- ->target
  "One capture target: the capability plus everything needed to reach its
   adapter and stamp provenance."
  [cap-name entry interfaces]
  (let [{:keys [interface subject]} (cap/parse-capability-name cap-name)]
    {:cap-key cap-name
     :handle (or (:cleanup-handle entry) (:impl entry))
     :impl (:impl entry)
     :interface interface
     :subject subject
     :adapter (get-in interfaces [interface :adapter])}))

(defn- all-live-targets
  "Every live capability as a target, DEDUPED on impl identity (the
   cleanup-ephemeral-capabilities! IdentityHashMap pattern) — mandatory
   for destructive drain kinds on shared impls, harmless for the rest.
   Sorted by capability name for deterministic filenames and ordering."
  [scenario-ctx interfaces]
  (let [seen (IdentityHashMap.)]
    (->> (cap/all-capabilities scenario-ctx)
         (sort-by (comp name key))
         (keep (fn [[cap-name entry]]
                 (when-not (.containsKey seen (:impl entry))
                   (.put seen (:impl entry) true)
                   (->target cap-name entry interfaces)))))))

(defn- actor-targets
  "The acting subject's capability as a single target — or ALL live when
   the step carries no SVO (vanilla mode) or the actor's capability is
   not provisioned under its keyed name."
  [scenario-ctx interfaces svo]
  (or (when-let [{:keys [interface subject]} svo]
        (let [keyed (cap/get-capability-entry scenario-ctx interface subject)
              plain (when-not keyed (cap/get-capability-entry scenario-ctx interface))
              entry (or keyed plain)
              cap-name (if keyed
                         (keyword (name (cap/capability-key interface subject)))
                         interface)]
          (when entry
            [(->target cap-name entry interfaces)])))
      (all-live-targets scenario-ctx interfaces)))

;; -----------------------------------------------------------------------------
;; Invocation + ref construction

(defn- derive-excerpt
  "The D3 mailed-alone triage courtesy: for text/plain string data, the
   BOUNDED TAIL (a console failure lives at the end; a leading ellipsis
   marks the head cut). Adapter-supplied excerpts are honored but still
   bounded. Other media types get no derived excerpt (the tail of an HTML
   document is noise, not triage)."
  [{:keys [data media-type excerpt]}]
  (let [cap attachments/excerpt-cap]
    (cond
      excerpt (:attachment/excerpt (attachments/truncate-excerpt
                                    {:attachment/excerpt excerpt}))
      (and (string? data) (= "text/plain" media-type))
      (if (> (count data) cap)
        (str "…" (subs data (- (count data) (dec cap))))
        data)
      :else nil)))

(defn- write-data!
  [^String path data]
  (if (bytes? data)
    (Files/write (Path/of path (into-array String [])) ^bytes data
                 (make-array java.nio.file.OpenOption 0))
    (spit path data))
  nil)

(defn- data-bytes [data]
  (if (bytes? data) (alength ^bytes data) (count (.getBytes ^String data "UTF-8"))))

(defn- capture-filename
  "Deterministic, collision-free within the scenario dir. `site` is
   :step (needs step-index) | :failure | :drain | :tail. `capseg` is the
   slugged FULL capability key (web-alice — not just the subject, so
   :web.alice and :api.alice cannot collide)."
  [site step-index kind capseg ext]
  (case site
    :step (str "step-" step-index "-" (name kind) "-" capseg "." ext)
    :failure (str "failure-" (name kind) "-" capseg "." ext)
    :drain (str (name kind) "-" capseg "." ext)
    :tail (str (name kind) "-tail-" capseg "." ext)))

(defn- capture-one!
  "Invoke one (target, kind) capture: time the adapter fn, write the
   file, build the COMPLETE ref. Returns {:ref r} | {:ref r :failure f}
   (D4 — the ref records the miss, and its .err path holds the thrown
   message: the path is evidence, never a dangling pointer — sl-yynw F7)
   | nil (adapter returned nil: nothing to capture, or the adapter
   doesn't contribute the kind)."
  [{:keys [artifacts-dir rel-prefix registry]} site step-index kind
   {:keys [cap-key handle interface subject adapter]}]
  (when-let [spec (registry/capture-spec adapter kind
                                         (or registry registry/default-registry))]
    (let [capseg (attachments/slug (name cap-key) "cap")
          start (System/nanoTime)
          outcome (try {:ret ((:fn spec) {:handle handle})}
                       (catch Throwable t {:threw t}))
          duration (/ (- (System/nanoTime) start) 1e6)
          base-ref (cond-> #:attachment{:kind kind
                                        :source :capture-policy
                                        :interface interface
                                        :duration-ms duration}
                     subject (assoc :attachment/subject subject))]
      (cond
        (:threw outcome)
        (let [msg (ex-message (:threw outcome))
              err-text (str "capture threw: " msg)
              filename (capture-filename site step-index kind capseg "err")]
          (write-data! (str artifacts-dir "/" filename) (str err-text "\n"))
          {:ref (assoc base-ref
                       :attachment/path (str rel-prefix "/" filename)
                       :attachment/error err-text)
           :failure {:kind kind :cap-key cap-key :interface interface
                     :subject subject :message msg}})

        (nil? (:ret outcome)) nil

        :else
        (let [{:keys [data media-type ext]} (:ret outcome)
              filename (capture-filename site step-index kind capseg (or ext "bin"))
              abs (str artifacts-dir "/" filename)
              excerpt (derive-excerpt (:ret outcome))]
          (write-data! abs data)
          {:ref (cond-> (assoc base-ref
                               :attachment/path (str rel-prefix "/" filename)
                               :attachment/media-type media-type
                               :attachment/bytes (data-bytes data))
                  excerpt (assoc :attachment/excerpt excerpt))})))))

(defn- run-captures!
  "Run a (kind × targets) batch, accumulating {:refs [...] :failures
   [...]}. Kinds iterate sorted for deterministic ordering."
  [capture-opts site step-index kind-targets]
  (reduce (fn [acc [kind target]]
            (if-let [{:keys [ref failure]}
                     (capture-one! capture-opts site step-index kind target)]
              (cond-> (update acc :refs conj ref)
                failure (update :failures conj failure))
              acc))
          {:refs [] :failures []}
          kind-targets))

(defn- scenario-scoped-kind?
  "Is `kind` scenario-scoped for this target's adapter?"
  [{:keys [registry]} kind {:keys [adapter]}]
  (boolean (:scenario-scoped?
            (registry/capture-spec adapter kind
                                   (or registry registry/default-registry)))))

;; -----------------------------------------------------------------------------
;; The two firing sites

(defn capture-step!
  "The per-step site (step_loop, AFTER the step's duration is measured).
   Fires :every-step kinds for the acting subject (all live when no SVO)
   — scenario-scoped kinds included (the per-step drain ruling) — and,
   when the step failed, :on-failure INSTANT kinds over all live.
   Returns {:refs [...] :failures [...]}; refs belong on the STEP's
   :attachments."
  [scenario-ctx {:keys [policy interfaces] :as capture-opts}
   {:keys [step-index svo failed?]}]
  (let [every-step-kinds (sort (keep (fn [[k p]] (when (= :every-step p) k)) policy))
        failure-kinds (when failed?
                        (sort (keep (fn [[k p]] (when (= :on-failure p) k)) policy)))
        actor (when (seq every-step-kinds)
                (actor-targets scenario-ctx interfaces svo))
        live (when (seq failure-kinds)
               (all-live-targets scenario-ctx interfaces))
        step-batch (for [kind every-step-kinds, target actor]
                     [kind target])
        ;; Instant kinds only at the failing instant — scenario-scoped
        ;; kinds under :on-failure fire at the post-steps drain instead.
        failure-batch (for [kind failure-kinds
                            target live
                            :when (not (scenario-scoped-kind? capture-opts kind target))]
                        [kind target])
        step-result (run-captures! capture-opts :step step-index step-batch)
        failure-result (run-captures! capture-opts :failure step-index failure-batch)]
    {:refs (into (:refs step-result) (:refs failure-result))
     :failures (into (:failures step-result) (:failures failure-result))}))

(defn drain-at-scenario-end!
  "The post-steps pre-Afters site (cleanup). Scenario-scoped kinds only:
   :on-failure drains all live once when `red?`; :every-step runs the
   TAIL SWEEP over all live (adapter returns nil when there is nothing
   left — no file, no ref). Returns {:refs [...] :failures [...]}; refs
   belong on the SCENARIO's :attachments."
  [scenario-ctx {:keys [policy interfaces] :as capture-opts} red?]
  (let [active (sort (keep (fn [[k p]]
                             (cond
                               (and (= :on-failure p) red?) [k :drain]
                               (= :every-step p) [k :tail]
                               :else nil))
                           policy))
        live (when (seq active) (all-live-targets scenario-ctx interfaces))
        batches (for [[kind site] active
                      target live
                      :when (scenario-scoped-kind? capture-opts kind target)]
                  [site kind target])]
    (reduce (fn [acc [site kind target]]
              (if-let [{:keys [ref failure]}
                       (capture-one! capture-opts site nil kind target)]
                (cond-> (update acc :refs conj ref)
                  failure (update :failures conj failure))
                acc))
            {:refs [] :failures []}
            batches)))
