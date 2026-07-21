(ns shiftlefter.runner.reporter
  "User-facing reporter contract — the REPORT plane of the sl-21z ruling.

   Two planes, one event taxonomy:

   - REPORT plane (this namespace): synchronous, coordinator-thread,
     plan-ordered, lossless. Console, EDN, and JUnit (sl-40to) implement
     `Reporter` and are invoked directly by the runner at lifecycle
     moments. Reporters are NOT bus subscribers.
   - OBSERVE plane (shiftlefter.runner.events): the async core.async bus —
     fire-and-forget, loss-tolerant, actual completion order. Graph fact
     emission (sl-yia), telemetry, and future out-of-process / remote /
     foreign-language workers live there.

   ## Invariants (load-bearing — do not relax without a Tower ruling)

   1. COORDINATOR THREAD ONLY. Reporter methods are invoked solely on the
      thread that owns the run (the one executing the runner pipeline).
      The warm daemon's *out*/*err* capture rebind is thread-local: output
      printed from any other thread never reaches the warm client. Under
      parallel execution (sl-q9wp) scenarios complete on pool threads, but
      their envelopes are handed back to the coordinator, which is the
      only caller of these methods.

   2. PLAN ORDER. The report plane sees scenario completions in plan
      order: under :max-parallel the coordinator buffers out-of-order
      completions and releases them in plan order (report output must be
      identical in content AND order to a sequential run). The bus, by
      contrast, carries actual completion order plus a per-run monotonic
      :seq — reporters get determinism, observers get truth.

   3. PURE DATA. Every value handed to a reporter is pure, immutable,
      EDN-NATIVE data: it round-trips through `pr-str`/`read-string` with
      the default data readers. Maps, vectors, sets, strings, numbers,
      booleans, nil, and UUIDs (`#uuid` tagged literals) qualify; keywords
      and symbols qualify when their printed form re-reads to an equal
      value — names that break EDN's token grammar (sl-27uh) are
      stringified by `scrub`; timestamps are ISO-8601 strings. What is
      banned is the
      actual danger: functions, channels, atoms/refs, driver and capability
      objects, and arbitrary Java objects. This is what lets a future
      grid-style coordinator feed these same reporters from envelopes that
      crossed a process boundary. `edn-safe?` below is the executable
      check; envelope-producing code must pass it in tests.

      JSON is NOT an envelope constraint. Lowering EDN-native values into a
      JSON-shaped wire (uuid -> string, symbol -> string) belongs in a
      transcoder at the foreign-language-worker boundary (sl-rdiz), applied
      as a pure function over already-pure data. Pushing that wire
      constraint back into native envelopes would degrade the `--edn`
      machine contract that exists today to serve a boundary that does not
      exist yet (Tower ruling R4, 2026-07-08).

   4. EXIT-CODE INDEPENDENCE, LOUD FAILURE. Exit codes are computed from
      the execution result before any reporting; neither reporters nor the
      bus can change them. A reporter that THROWS is a loud runner error —
      the user asked for that artifact (a CI gate reading JUnit XML must
      not get a silent partial file). Observer/bus handler failures, by
      contrast, are log-and-continue (see events.clj subscribe!).

   Implementations may be stateful (e.g. JUnit accumulates results and
   writes its file in on-run-end); methods are called for effect and their
   return values are ignored."
  (:require [clojure.edn :as edn]
            [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]))

;; -----------------------------------------------------------------------------
;; Specs — deliberately open maps; keys added by later beads must extend,
;; never break, these. sl-q9wp's :scenario/id (scenario envelope) and the bus
;; :seq landed exactly this way.
;; -----------------------------------------------------------------------------

(s/def ::run-id string?)
;; :error (sl-esq): a lifecycle hook threw — infrastructure failure, distinct
;; from :failed (a step assertion). Scenario-level only; steps never carry it.
(s/def ::status #{:passed :failed :pending :skipped :error})
(s/def ::exit-code int?)
(s/def ::counts (s/map-of keyword? int?))

;; Run-ctx enrichment (sl-40to, extended sl-muq9 DP1) — all additive,
;; EDN-native, coordinator-known. :selection is present only when a tag
;; filter was active: the selection story a report renders as
;; "ran 12 of 40; filter: @smoke".
(s/def ::started-at string?)          ; ISO-8601, no offset
(s/def ::version string?)
(s/def ::mode #{:shifted :vanilla})
(s/def ::project-name string?)
(s/def ::selected int?)
(s/def ::filtered-out int?)
(s/def ::filter (s/map-of #{:include :exclude} (s/coll-of string? :kind set?)))
(s/def ::selection (s/keys :req-un [::selected ::filtered-out ::filter]))

(s/def ::run-ctx
  (s/keys :req-un [::run-id]
          :opt-un [::started-at ::version ::mode ::project-name ::selection]))

(s/def ::scenario-result
  ;; The per-scenario envelope: today's execute-suite result map for one
  ;; scenario (:status :plan :steps ...). Must satisfy edn-safe? — scrub
  ;; anything capability/driver-shaped before it reaches this seam.
  (s/keys :req-un [::status]))

(s/def ::run-summary
  (s/keys :req-un [::run-id ::exit-code ::counts ::status]))

(def ^:private safe-token-part-re
  ;; Fast-path charset for keyword/symbol name parts (sl-27uh): names made
  ;; only of these characters always survive pr-str/read-string, so they skip
  ;; the definitional read-back in `readable-token?`. Every framework-internal
  ;; keyword/symbol (:mode, :plan/pickle, :step/invalid-return, stepdef :ns
  ;; symbols) matches. The leading class deliberately excludes `+ - .` and
  ;; digits — a leading sign or dot before a digit re-reads as a NUMBER
  ;; ((symbol \"-1\") prints as -1) — so such names pay the slow path, which
  ;; decides definitionally. Being outside this charset never rejects a token;
  ;; it only costs the read-back.
  #"[A-Za-z*!_?=][A-Za-z0-9*+!_?=.-]*")

(defn- readable-token?
  "True when a keyword/symbol's printed form re-reads to an equal value —
   invariant 3's round-trip, checked definitionally rather than by
   re-implementing EDN's (underspecified) token grammar. Names matching the
   conservative charset above skip the read-back (Tower-sanctioned perf
   fast path, 2026-07-10)."
  [x]
  (let [fast? #(and % (re-matches safe-token-part-re %))]
    (if (and (fast? (name x))
             (or (nil? (namespace x)) (fast? (namespace x))))
      true
      (try (= x (edn/read-string (pr-str x)))
           ;; Style exception: broad catch — this is a validity probe; ANY
           ;; reader throw means exactly "not readable", never a bug to surface.
           (catch Exception _ false)))))

(defn edn-safe?
  "True when x is pure, immutable, EDN-native data (invariant 3) — it
   round-trips through `pr-str`/`read-string` with the default data readers.

   UUIDs qualify (`#uuid` is a tagged literal in the EDN spec); keywords and
   symbols qualify only when their printed form re-reads to an equal value
   (sl-27uh) — a token like `(keyword \"a b\")` prints as `:a b`, which
   re-reads as `:a`, so it is NOT admitted; `scrub` stringifies it. Functions,
   channels, atoms/refs, driver and capability objects, and arbitrary Java
   objects do not qualify.

   DEFRECORDS DO NOT QUALIFY, even though they satisfy `map?`: `pr-str` emits
   `#my.ns.Rec{...}`, which needs both a reader tag and the record class on the
   classpath — exactly what a remote coordinator deserializing an envelope does
   not have. `scrub` flattens them to plain maps."
  [x]
  (cond
    (or (nil? x) (string? x) (number? x) (boolean? x) (uuid? x)) true
    (or (keyword? x) (symbol? x)) (readable-token? x)
    (record? x) false
    (map? x) (every? (fn [[k v]] (and (edn-safe? k) (edn-safe? v))) x)
    (or (vector? x) (set? x) (seq? x)) (every? edn-safe? x)
    :else false))

;; -----------------------------------------------------------------------------
;; Envelope construction — the seam where non-data dies (invariant 3)
;; -----------------------------------------------------------------------------

(defn scrub
  "Coerce x to EDN-native data.

   Values that already satisfy `edn-safe?` are returned UNTOUCHED — an
   identity fast path, so map type and key order survive and downstream
   `pr-str` output is unchanged. Defrecords are flattened to plain maps.
   Anything else is rendered with `pr-str`."
  [x]
  (cond
    (edn-safe? x) x
    ;; records included: `into {}` flattens them to plain maps
    (map? x)      (into {} (map (fn [[k v]] [(scrub k) (scrub v)])) x)
    (vector? x)   (mapv scrub x)
    (set? x)      (into #{} (map scrub) x)
    (seq? x)      (mapv scrub x)
    :else         (pr-str x)))

(defn error-envelope
  "Project a raw step error onto the report/observe planes.

   `:value` (the only producer is the :step/invalid-return path) holds an
   arbitrary runtime value, so it is `pr-str`'d here EXACTLY ONCE — downstream
   serializers must pass it through, never re-encode it. `:data` is raw
   `ex-data` and may carry anything, so it is scrubbed. Falsy `:value`/`:data`
   are omitted, preserving the historical cond-> shape."
  [error]
  (when error
    (cond-> {:type (:type error) :message (:message error)}
      (:exception-class error) (assoc :exception-class (:exception-class error))
      (:data error)            (assoc :data (scrub (:data error)))
      (:value error)           (assoc :value (pr-str (:value error)))
      ;; Hook failure attribution (sl-esq): hook name + registration home
      ;; + the @hook= tag's file:line — all EDN-native by construction.
      (:hook error)            (assoc :hook (:hook error))
      (:registration error)    (assoc :registration (:registration error))
      (:tag-source error)      (assoc :tag-source (:tag-source error)))))

(defn scenario-envelope
  "Project a raw execute-suite scenario result onto the report/observe planes.

   ALLOWLIST, not denylist: only what reporters read survives, so a future key
   on the raw result cannot leak a live object through this seam. Dropped:
   `:scenario-ctx` (live drivers/capabilities), `:plan/steps` (its `:binding`
   carries `:fn`), and `:capability-cleanup` (nothing reads it).

   The projection is then scrubbed, which flattens the gherkin Location records
   riding along in `:step/location`.

   `:scenario/id` (the pickle UUID, sl-q9wp R4) is lifted to the top level so
   both planes key scenarios uniformly; UUIDs are EDN-native (invariant 3)."
  [scenario-result]
  (scrub
   (cond-> {:status (:status scenario-result)
            :plan   {:plan/pickle (-> scenario-result :plan :plan/pickle)}
            :steps  (mapv (fn [step-result]
                            (cond-> {:step    (:step step-result)
                                     :binding (:binding step-result)
                                     :status  (:status step-result)}
                              (:duration-ms step-result)
                              (assoc :duration-ms (:duration-ms step-result))
                              (:error step-result)
                              (assoc :error (error-envelope (:error step-result)))))
                          (:steps scenario-result))}
     ;; Per-scenario wall clock (D5, sl-40to). Absent on the REPL path.
     (:duration-ms scenario-result)
     (assoc :duration-ms (:duration-ms scenario-result))
     ;; Scenario-level hook failure (sl-esq): a lifecycle hook threw. Lives
     ;; on the scenario, never on a step; absent everywhere else.
     (:error scenario-result)
     (assoc :error (error-envelope (:error scenario-result)))
     ;; Derived-scheduling surfacing (sl-esq round-5 unification): effective
     ;; non-default scheduling with its reason — {:serial? true :reason
     ;; :costume|:shared-impl|[:hook name]|:tag} — so a report reader can
     ;; see a scenario ran exclusively and WHY. Retrofits the costume/
     ;; shared-impl reasons, which previously surfaced only as a stderr
     ;; notice. Absent for default-scheduled scenarios.
     (-> scenario-result :plan :plan/schedule)
     (assoc :schedule (-> scenario-result :plan :plan/schedule))
     ;; Hook records (sl-esq): every hook that ran, timed and stamped —
     ;; {:name :phase :status :duration-ms :contributed}; failures keep
     ;; their error (re-enveloped). Absent for hook-less scenarios.
     (seq (:hooks scenario-result))
     (assoc :hooks (mapv (fn [record]
                           (cond-> record
                             (:error record)
                             (assoc :error (error-envelope (:error record)))))
                         (:hooks scenario-result)))
     ;; sl-q9wp R4: absent only for hand-built plans with no pickle (tests).
     (-> scenario-result :plan :plan/pickle :pickle/id)
     (assoc :scenario/id (-> scenario-result :plan :plan/pickle :pickle/id)))))

(defn diagnostics-envelope
  "Scrub planning/validation diagnostics before they reach `on-diagnostics`.
   Diagnostics carry bound-steps and issue maps that may hold records; the
   identity fast path means an already-clean diagnostics map passes through
   untouched."
  [diagnostics]
  (scrub diagnostics))

;; -----------------------------------------------------------------------------
;; Protocol
;; -----------------------------------------------------------------------------

(defprotocol Reporter
  "Synchronous user-facing reporter. See ns docstring for the four
   invariants every caller and implementation must uphold."
  (on-run-start [this run-ctx]
    "Called once per run-scope, before the first scenario of that scope
     executes. Under setup.clj orchestration each group is its own run-scope
     (Tower ruling R1, 2026-07-08): a group already owns a full cycle — its
     own bus run-started/finished pair and its own summary — so reporters
     fire once per group. N>1-group semantics and the JUnit
     artifact-per-group question are deferred to sl-oobu.")
  (on-scenario-complete [this scenario-result]
    "Called once per scenario, in plan order, as results become available
     to the coordinator. Under sl-dgk this fires as scenarios finish;
     until then a faithful conversion may fire it post-hoc — the contract
     is ordering, not immediacy.")
  (on-diagnostics [this diagnostics]
    "Called with planning/validation diagnostics that must reach the user
     (warn-level SVO issues etc. — sl-qk8l/sl-6h4r lineage).")
  (on-run-end [this run-summary]
    "Called once per run-scope, after the last scenario of that scope — see
     `on-run-start` for what a run-scope is under setup.clj orchestration.
     Accumulating reporters (JUnit) materialize their artifact here; a throw
     from here is a runner error, not a warning."))

(defrecord NoopReporter []
  Reporter
  (on-run-start [_this _run-ctx] nil)
  (on-scenario-complete [_this _scenario-result] nil)
  (on-diagnostics [_this _diagnostics] nil)
  (on-run-end [_this _run-summary] nil))

(defn noop-reporter
  "A Reporter that does nothing. Useful for tests and for a run configured with
   reporting off; also the generator seed for `::reporter` below."
  []
  (->NoopReporter))

;; `satisfies?` alone yields no generator — spec health (and any generative
;; test reaching these) needs one. Same shape as `::bind/fn`'s gen.
(s/def ::reporter (s/with-gen #(satisfies? Reporter %)
                    #(gen/return (noop-reporter))))
(s/def ::reporters (s/coll-of ::reporter :kind sequential?))
