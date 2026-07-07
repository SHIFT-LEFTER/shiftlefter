(ns shiftlefter.test-helpers.adapter-registry
  "Test helper for building mock adapter registries and interfaces configs.

   ## When to use

   Any test that needs to inject fake adapters into the framework's
   provisioning flow — instead of hand-writing maps or hijacking
   `registry/create-capability` with `with-redefs`. Replaces:

   - the ad-hoc `hook-test-registry` / `order-tracking-registry` builders
     historically inlined in `stepengine/exec_test.clj`
   - the dozens of `(with-redefs [registry/create-capability ...] ...)`
     sites that bypass the real adapter lookup. Those silently miss
     contract changes in `registry/create-capability`; this helper
     exercises the real lookup with mock specs.

   ## When NOT to use

   - Production code wanting a custom registry: merge into
     `default-registry` directly. This helper is test-only.
   - Tests of the registry itself (`adapters/registry_test.clj`): test
     the real thing, not a mock of it.

   ## Concepts

   The framework has three layers: Interface (declarative slot in user
   config), Adapter (factory + lifecycle hooks in the registry), and
   Capability (the runtime impl in scenario ctx). See `ARCHITECTURE.md`
   § The Capability Model for the full framing.

   This helper builds the second layer (`registry`) and the first
   (`interfaces`). The third — capabilities — is what the framework
   produces from the first two; tests assert against ctx to verify it.

   ## Quick start

   ```clojure
   (require '[shiftlefter.test-helpers.adapter-registry :as mock])

   (let [events     (atom [])
         registry   (mock/registry
                     {:web {:impl   {:browser :fake-driver}
                            :events events}})
         interfaces (mock/interfaces
                     {:web {:adapter :web}})]
     (exec/execute-scenario plan {} {:interfaces       interfaces
                                     :adapter-registry registry}))
   ```

   ## Adapter spec keys

   `registry` accepts a map of adapter-name → spec. Each spec may include:

   | Key             | Default                            | What it does |
   |-----------------|------------------------------------|--------------|
   | `:impl`         | `{:type :test-impl}`               | Value returned in `{:ok ...}` from factory |
   | `:impl-key`     | absent                             | Mirrors the production `:impl-key` registry contract — when set, provisioning extracts `(get factory-result impl-key)` as the step-facing impl and keeps the whole result as the cleanup handle. Set `:impl` to a wrapping map (e.g. `{:browser fake :driver d}`) to exercise the unwrap. |
   | `:factory`      | derived from `:impl` / `:fail?`    | Full factory override — `(fn [config] -> {:ok ...} \\| {:error ...})`. Bypasses `:impl`/`:fail?`/`:error`. Use when the test needs per-call impl variation (e.g., minted ids). |
   | `:on-provision` | absent                             | `(fn [ctx impl] -> ctx)` hook |
   | `:provides`     | `[]`                               | Qualified protocol keywords |
   | `:cleanup`      | `(constantly {:ok :closed})`       | Cleanup function for impls |
   | `:fail?`        | `false`                            | Factory returns `:error` instead of `:ok` |
   | `:error`        | `{:type :adapter/test-failure ...}` | Error map when `:fail? true` |
   | `:events`       | nil (no recording)                 | Atom that receives event tuples |

   ## Interface spec keys

   `interfaces` accepts a map of interface-name → spec. Each spec may include:

   | Key             | Default                            | What it does |
   |-----------------|------------------------------------|--------------|
   | `:adapter`      | (required)                         | Adapter name keyword |
   | `:type`         | interface name                     | Verb vocabulary type |
   | `:shared-impl?` | `false`                            | Cross-subject impl sharing |
   | `:config`       | `{}`                               | Config passed to factory |

   ## Events convention

   When `:events` atom is supplied on an adapter spec, the following
   tuples are conj'd at the relevant lifecycle moments:

   ```clojure
   [:provision <adapter-name>]        ;; factory called successfully
   [:on-provision <adapter-name>]     ;; :on-provision hook fired
   [:cleanup <adapter-name>]          ;; cleanup called
   [:provision-failed <adapter-name>] ;; factory returned :error (fail? true)
   ```

   Same atom can be shared across adapters to track ordering between them.
   Recording is keyed by adapter name (the registry's primary key); tests
   that want interface-level events should use adapter-name = interface-name.

   ## Future hook slots

   Named here as direction-of-travel; not yet implemented in production
   code or this helper:

     :on-scenario-start / :on-scenario-end
     :on-step-start     / :on-step-end
     :delay-ms          — for parallel-provision race testing
     :subject-keyed?    — events include subject, not just adapter name

   When a production hook lands, add the matching spec-map key here. The
   open-spec-map design preserves caller stability — existing tests don't
   change shape."
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]))

;; -----------------------------------------------------------------------------
;; Specs — Adapter & Interface Spec Shapes
;; -----------------------------------------------------------------------------

(s/def ::impl any?)
(s/def ::impl-key keyword?)
;; fn-valued specs use the project's standard with-gen idiom; ifn? alone
;; can't be sampled by test.check (see shiftlefter.stepengine.bind/::fn).
(s/def ::factory (s/with-gen ifn? #(gen/return (constantly {:ok {:type :test-impl}}))))
(s/def ::on-provision (s/with-gen ifn? #(gen/return (fn [ctx _] ctx))))
(s/def ::provides (s/coll-of qualified-keyword? :kind vector?))
(s/def ::cleanup (s/with-gen ifn? #(gen/return (constantly {:ok :closed}))))
(s/def ::fail? boolean?)
(s/def ::error map?)
;; :events is intentionally unspec'd at the boundary — swap! will error
;; loudly if a non-atom slips through. Overly tight predicates (IAtom
;; instance checks etc.) make for noisier failures than the runtime one.

(s/def ::adapter-spec
  (s/keys :opt-un [::impl ::impl-key ::factory ::on-provision ::provides ::cleanup ::fail? ::error]))

(s/def ::adapter-specs
  (s/map-of keyword? ::adapter-spec))

(s/def ::adapter keyword?)
(s/def ::type keyword?)
(s/def ::shared-impl? boolean?)
(s/def ::config map?)

(s/def ::interface-spec
  (s/keys :req-un [::adapter]
          :opt-un [::type ::shared-impl? ::config]))

(s/def ::interface-specs
  (s/map-of keyword? ::interface-spec))

;; -----------------------------------------------------------------------------
;; Defaults
;; -----------------------------------------------------------------------------

(def ^:private default-impl {:type :test-impl})

(def ^:private default-error
  {:type    :adapter/test-failure
   :message "mock adapter configured with :fail? true"})

(def ^:private default-cleanup (constantly {:ok :closed}))

;; -----------------------------------------------------------------------------
;; Internal Builders
;; -----------------------------------------------------------------------------

(defn- record-event!
  "Conj `event-tuple` onto `events-atom` if it's non-nil."
  [events-atom event-tuple]
  (when events-atom
    (swap! events-atom conj event-tuple)))

(defn- build-factory
  "Build a factory fn for one adapter spec. Records :provision or
   :provision-failed on the spec's :events atom.

   If the spec has a `:factory` override, wraps it (still records events
   based on what the override returns). Otherwise derives from `:impl` /
   `:fail?` / `:error`."
  [adapter-name {:keys [factory impl events fail? error]
                 :or   {impl default-impl}}]
  (if factory
    (fn [config]
      (let [result (factory config)]
        (record-event! events
                       [(if (:error result) :provision-failed :provision)
                        adapter-name])
        result))
    (fn [_config]
      (if fail?
        (do (record-event! events [:provision-failed adapter-name])
            {:error (or error default-error)})
        (do (record-event! events [:provision adapter-name])
            {:ok impl})))))

(defn- build-cleanup
  "Wrap the spec's :cleanup fn (or the default) to record :cleanup on
   :events before delegating."
  [adapter-name {:keys [cleanup events] :or {cleanup default-cleanup}}]
  (fn [impl]
    (record-event! events [:cleanup adapter-name])
    (cleanup impl)))

(defn- build-on-provision
  "Wrap the spec's :on-provision hook to record :on-provision on :events
   before delegating. Returns nil if no hook configured — caller should
   omit the key entirely rather than store nil (matches production)."
  [adapter-name {:keys [on-provision events]}]
  (when on-provision
    (fn [ctx impl]
      (record-event! events [:on-provision adapter-name])
      (on-provision ctx impl))))

(defn- build-adapter-entry
  "Build one adapter registry entry from a spec.

   Omits :on-provision from the entry when the spec has no hook —
   matches the production registry's convention of absence-as-default."
  [adapter-name spec]
  (cond-> {:factory  (build-factory adapter-name spec)
           :cleanup  (build-cleanup adapter-name spec)
           :provides (or (:provides spec) [])}
    (some? (:on-provision spec))
    (assoc :on-provision (build-on-provision adapter-name spec))
    (some? (:impl-key spec))
    (assoc :impl-key (:impl-key spec))))

(defn- build-interface-entry
  "Build one interfaces-config entry from a spec.

   :type defaults to the interface name (the common case where a project
   names its slots by purpose, e.g. :web/:sms/:api)."
  [iface-name {:keys [adapter type shared-impl? config]}]
  (cond-> {:type    (or type iface-name)
           :adapter adapter
           :config  (or config {})}
    shared-impl? (assoc :shared-impl? true)))

;; -----------------------------------------------------------------------------
;; Public API
;; -----------------------------------------------------------------------------

(defn registry
  "Build a mock adapter registry from a map of adapter-name → spec.

   See ns docstring for spec keys, defaults, and the events convention.

   Returns a map suitable for the framework's `:adapter-registry` opt
   on `compile-suite`, `execute-scenario`, or `execute-suite`."
  [adapter-specs]
  (into {}
        (map (fn [[n s]] [n (build-adapter-entry n s)]))
        adapter-specs))

(defn interfaces
  "Build an interfaces config from a map of interface-name → spec.

   See ns docstring for spec keys.

   Returns a map suitable for the framework's `:interfaces` opt."
  [interface-specs]
  (into {}
        (map (fn [[n s]] [n (build-interface-entry n s)]))
        interface-specs))

(s/fdef registry
  :args (s/cat :adapter-specs ::adapter-specs)
  :ret  map?)

(s/fdef interfaces
  :args (s/cat :interface-specs ::interface-specs)
  :ret  map?)
