(ns shiftlefter.adapters.registry
  "Adapter registry for capability management.

   Maps adapter names (like `:etaoin`) to factory/cleanup functions
   plus a declared `:provides` list of protocols the produced impl
   satisfies, enabling pluggable capability creation and bind-time
   capability gating.

   ## Registry Structure

   The entry shape is spec'd — `::adapter-entry` below is the single source
   of truth (sl-wntk), and custom registries installed via setup.clj are
   validated against it at the install boundary (`registry-problems`;
   runner-side error `:setup/invalid-adapter-registry`).

   An adapter entry has:
   - `:factory`      — function that takes config, returns capability
   - `:cleanup`      — function that takes capability, cleans up resources
   - `:impl-key`     — optional. When the factory wraps the protocol-bearing
                        impl inside a map (e.g. browser factories return
                        `{:ok {:browser <IBrowser> :etaoin-driver <driver>}}`),
                        `:impl-key` names the key holding the step-facing impl
                        (`:browser`). Provisioning extracts that value as the
                        capability `:impl` and keeps the whole factory result as
                        the `:cleanup-handle` (so cleanup still sees the driver).
                        Declaring `:impl-key` is also a promise that the extracted
                        value satisfies `:provides` — `check-extracted-impl`
                        enforces it at provision time (sl-091). Adapters whose
                        factory returns the impl directly (or a bundle the
                        interface consumes by convention, like SMS) omit it.
   - `:provides`     — vector of qualified protocol keywords the produced
                        impl satisfies (e.g.,
                        `[:shiftlefter.sms.protocol/ISMS
                          :shiftlefter.sms.protocol/ISMSInbound]`)
   - `:on-provision` — optional `(fn [ctx impl] -> ctx)` invoked after
                        `cap/assoc-capability` so adapters can seed
                        per-interface scenario state without the engine
                        knowing about each interface type. Engine surfaces
                        thrown exceptions as
                        `{:error {:type :adapter/on-provision-failed ...}}`.
   - `:capture`      — optional map of adapter-contributed capture kinds
                        (sl-3q9r): `{kind {:fn f :scenario-scoped? bool}}`.
                        `:fn` is `(fn [{:keys [handle]}])` where handle is
                        the capability's `(or :cleanup-handle :impl)`;
                        returns nil (nothing to capture — no file, no ref)
                        or `{:data <bytes-or-String> :media-type \"...\"
                        :ext \"...\"}` (+ optional `:excerpt`); may throw
                        (the engine converts it to a capture-attributed
                        failure, D4). The framework owns timing, file
                        writes, filenames, and ref construction — capture
                        fns only produce data. `:scenario-scoped?` marks
                        destructive drain kinds (console): under
                        :on-failure they fire once post-steps when red
                        rather than at the failing instant. A kind absent
                        from this map is a no-op for this adapter's
                        capabilities, never an error.

   The suite-load lint (sl-unz) consults `:provides` post-bind when a
   stepdef declares `:requires-protocols`; if any required protocol is
   absent, planning fails with `:stepdef/missing-capability` (one issue
   per stepdef, deduped across uses) so the test author sees the gap
   before any side effects.

   ## Usage

   ```clojure
   ;; Get adapter by name
   (get-adapter :etaoin)
   ;; => {:factory #'shiftlefter.adapters.etaoin/create-browser
   ;;     :cleanup #'shiftlefter.adapters.etaoin/close-browser
   ;;     :provides [:shiftlefter.browser.protocol/IBrowser]}

   ;; Create capability
   (create-capability :etaoin {:headless true})
   ;; => {:ok {:browser ... :etaoin-driver ...}}

   ;; Cleanup capability
   (cleanup-capability :etaoin capability)
   ;; => {:ok :closed}
   ```"
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as sgen]
            [clojure.string :as str]
            [shiftlefter.adapters.etaoin :as etaoin]
            [shiftlefter.adapters.playwright :as playwright]
            [shiftlefter.adapters.sms-mock :as sms-mock]
            [shiftlefter.adapters.twilio :as twilio]
            [shiftlefter.sms.adapter-hooks :as sms-hooks]))

;; -----------------------------------------------------------------------------
;; Specs — the registry-entry contract (sl-wntk)
;;
;; A custom registry arriving through setup.clj is EXTERNAL INPUT (house
;; boundary-validation rule); these specs are the entry contract's single
;; source of truth. The runner validates live custom registries against
;; ::registry at the setup install boundary (runner/core `run-group!`,
;; :setup/invalid-adapter-registry) — dry-run PREVIEW registries
;; (setup/declared-preview-registry) are data-only by design and are never
;; checked against ::adapter-entry.
;;
;; Fn-valued specs are `ifn?` + s/with-gen (the setup ::start idiom): bare
;; ifn? is ungeneratable (spec_health_test generates every :shiftlefter*
;; spec), and s/fspec is banned here — instrumenting an fspec would
;; generatively INVOKE user lifecycle fns at check time (sl-yvaz).
;; -----------------------------------------------------------------------------

(s/def ::factory (s/with-gen ifn? #(sgen/return (fn [_config] {:ok {}}))))
(s/def ::cleanup (s/with-gen ifn? #(sgen/return (fn [_capability] {:ok :closed}))))
(s/def ::impl-key keyword?)
;; Explicitly-empty :provides is legal at THIS layer — "produces an impl
;; satisfying no protocols" is a truthful entry. The setup DECLARATION
;; layer separately requires novel adapters to declare the :provides KEY
;; (:setup/adapter-decl-missing-provides, sl-e0ml) — that lint is about a
;; declaration omitting the key (preview blindness), not about [] as a
;; value; the two layers complement, they don't contradict.
(s/def ::provides (s/coll-of qualified-keyword?))
(s/def ::on-provision (s/with-gen ifn? #(sgen/return (fn [ctx _impl] ctx))))
(s/def :shiftlefter.adapters.registry.capture/fn
  (s/with-gen ifn? #(sgen/return (constantly nil))))
(s/def :shiftlefter.adapters.registry.capture/scenario-scoped? boolean?)
(s/def ::capture-kind-spec
  (s/keys :req-un [:shiftlefter.adapters.registry.capture/fn]
          :opt-un [:shiftlefter.adapters.registry.capture/scenario-scoped?]))
(s/def ::capture (s/map-of keyword? ::capture-kind-spec))
(s/def ::requires-class string?)
(s/def ::requires-dep string?)
(s/def ::not-bundled-note string?)

(s/def ::adapter-entry
  (s/keys :req-un [::factory ::cleanup ::provides]
          :opt-un [::impl-key ::on-provision ::capture
                   ::requires-class ::requires-dep ::not-bundled-note]))

(s/def ::registry (s/map-of keyword? ::adapter-entry))

;; The factory contract (AC4): every factory returns the envelope
;; {:ok <impl-or-bundle>} | {:error {...}} — provisioning branches on
;; :error and unwraps :ok (stepengine/exec/provisioning.clj).
(s/def ::ok any?)
(s/def ::error map?)
(s/def ::factory-result
  (s/or :ok (s/keys :req-un [::ok])
        :error (s/keys :req-un [::error])))

;; -----------------------------------------------------------------------------
;; Default Registry
;; -----------------------------------------------------------------------------

(def default-registry
  "Built-in adapters shipped with ShiftLefter.

   Currently includes:
   - :etaoin     — Etaoin WebDriver for browser automation
   - :playwright — Playwright for browser automation (requires Playwright dep)
   - :sms-mock   — In-memory mock SMS for tests and CI
   - :sms-twilio — Twilio REST API for SMS

   Naming: browser adapters use the unambiguous vendor name (`:etaoin`,
   `:playwright`). SMS adapters take a `:sms-` prefix because mock/stub
   role names would otherwise collide with future capabilities."
  {:etaoin     {:factory  etaoin/create-browser
                :cleanup  etaoin/close-browser
                :impl-key :browser
                :provides [:shiftlefter.browser.protocol/IBrowser]
                ;; sl-3q9r: the browser capture kinds. :console is the
                ;; destructive drain (returns-and-clears, Chrome/Edge only
                ;; — capture-console no-ops legibly elsewhere).
                :capture  {:screenshot  {:fn etaoin/capture-screenshot}
                           :console     {:fn etaoin/capture-console
                                         :scenario-scoped? true}
                           :page-source {:fn etaoin/capture-page-source}}}
   :playwright {:factory  playwright/create-browser
                :cleanup  playwright/close-browser
                :impl-key :browser
                :provides [:shiftlefter.browser.protocol/IBrowser]
                ;; Supported but NOT bundled in the run classpath/uberjar
                ;; (sl-gcdu): config load probes :requires-class and fails
                ;; with a not-bundled error naming :requires-dep. Keep the
                ;; version in :requires-dep in step with the :test alias
                ;; pin in deps.edn AND the Maven Central links in
                ;; docs/INSTALL-TIERS.md § Using Playwright (sl-wgnw) —
                ;; three pin sites, one version.
                :requires-class "com.microsoft.playwright.Playwright"
                :requires-dep "com.microsoft.playwright/playwright {:mvn/version \"1.50.0\"}"
                :not-bundled-note "the driver bundle alone is ~200MB"
                ;; sl-sehc: the pull-at-capture kinds. No :console — impossible
                ;; in the drain shape (no browser-side buffer; Playwright
                ;; console messages are listener events); the subscription-
                ;; shape design is sl-lam0.
                :capture  {:screenshot  {:fn playwright/capture-screenshot}
                           :page-source {:fn playwright/capture-page-source}}}
   ;; sl-uv3r: both SMS factories return the bundle {:sms impl
   ;; :from-number ...}; :impl-key extracts the protocol object for steps
   ;; (the bundle rides :cleanup-handle). Without it, steps received the
   ;; wrapper map and the first send! threw on PersistentArrayMap.
   :sms-mock   {:factory      sms-mock/create-sms
                :cleanup      sms-mock/close-sms
                :impl-key     :sms
                :provides     [:shiftlefter.sms.protocol/ISMS
                               :shiftlefter.sms.protocol/ISMSInbound]
                :on-provision sms-hooks/set-scenario-start-ts}
   :sms-twilio {:factory      twilio/create-sms
                :cleanup      twilio/close-sms
                :impl-key     :sms
                :provides     [:shiftlefter.sms.protocol/ISMS]
                :on-provision sms-hooks/set-scenario-start-ts}})

;; -----------------------------------------------------------------------------
;; Registry Validation (sl-wntk) — legible boundary errors for custom registries
;; -----------------------------------------------------------------------------

(def known-entry-keys
  "Every key the framework reads off a registry entry. `s/keys` maps are
   open, so a typo'd OPTIONAL key (:implkey, :captur) would pass the spec
   silently and the entry would misbehave at use-time — exactly the failure
   the boundary check exists to kill. Unknown-key detection pairs with the
   spec (config-lint / doctor precedent)."
  #{:factory :cleanup :impl-key :provides :on-provision :capture
    :requires-class :requires-dep :not-bundled-note})

(defn- entry-problems
  "Problems with one registry entry: spec violations (legible s/explain-str)
   and unknown keys. Empty seq = conforming entry."
  [entry]
  (let [spec-problem (when-not (s/valid? ::adapter-entry entry)
                       [{:problem :spec-violation
                         :explain-str (s/explain-str ::adapter-entry entry)
                         :explain-data (s/explain-data ::adapter-entry entry)}])
        unknown (when (map? entry)
                  (for [k (keys entry)
                        :when (not (contains? known-entry-keys k))]
                    {:problem :unknown-key
                     :key k
                     :known (vec (sort known-entry-keys))}))]
    (concat spec-problem unknown)))

(defn- describe-entry-problem [{:keys [problem explain-str key known]}]
  (case problem
    :spec-violation (str/trim-newline explain-str)
    :unknown-key (str "unknown entry key " key
                      " (known: " (str/join " " known) ")")))

(defn registry-problems
  "Validate a LIVE adapter registry against the entry contract
   (::adapter-entry + unknown-key detection). This is the setup install
   boundary's validator (runner/core `run-group!`): a malformed custom
   entry fails at installation with a legible message instead of an
   incidental error at first use.

   Returns nil when the registry conforms, else a vector of problem maps:
   - {:problem :not-a-map :message <legible line>}    — registry itself;
     DIRECT-invocation guard only: at the run-group! boundary a non-map
     registry never reaches this fn — the ::start-result spec gate
     (::adapter-registry is (s/nilable map?)) rejects it first as
     :setup/invalid-start-result (sl-t86d, ruled honest-shape: the arm
     stays as this fn's own boundary check for public callers)
   - {:problem :non-keyword-adapter-name :name <...>} — bad key
   - {:adapter <kw> :problems [{:problem :spec-violation :explain-str ...
      :explain-data ...} | {:problem :unknown-key :key ... :known [...]}]
      :message <one legible line per problem>}        — per entry

   Never applied to dry-run PREVIEW registries (data-only by design)."
  [registry]
  (if-not (map? registry)
    [{:problem :not-a-map
      :message (str "adapter registry is not a map: " (pr-str registry))}]
    (let [bad-names (for [k (keys registry)
                          :when (not (keyword? k))]
                      {:problem :non-keyword-adapter-name
                       :name (pr-str k)
                       :message (str "adapter registry key " (pr-str k)
                                     " is not a keyword")})
          bad-entries (for [[adapter-name entry] (sort-by (comp str key) registry)
                            :let [problems (vec (entry-problems entry))]
                            :when (seq problems)]
                        {:adapter adapter-name
                         :problems problems
                         :message (str "adapter " adapter-name ": "
                                       (str/join "; " (map describe-entry-problem
                                                           problems)))})
          all (vec (concat bad-names bad-entries))]
      (when (seq all) all))))

;; -----------------------------------------------------------------------------
;; Registry Operations
;; -----------------------------------------------------------------------------

(defn get-adapter
  "Look up an adapter by name.

   Parameters:
   - adapter-name: Keyword identifying the adapter (e.g., :etaoin)
   - registry: Optional registry map (defaults to default-registry)

   Returns:
   - Success: {:factory fn :cleanup fn}
   - Error: {:error {:type :adapter/unknown :adapter name :known [...]}}

   Examples:
   ```clojure
   (get-adapter :etaoin)
   ;; => {:factory #function :cleanup #function}

   (get-adapter :unknown)
   ;; => {:error {:type :adapter/unknown :adapter :unknown :known [:etaoin :playwright]}}
   ```"
  ([adapter-name]
   (get-adapter adapter-name default-registry))
  ([adapter-name registry]
   (if-let [adapter (get registry adapter-name)]
     adapter
     {:error {:type :adapter/unknown
              :adapter adapter-name
              :known (vec (keys registry))}})))

(defn known-adapters
  "Returns list of known adapter names from the registry."
  ([]
   (known-adapters default-registry))
  ([registry]
   (vec (keys registry))))

(defn provides
  "Return the set of protocol qualified-keywords the named adapter satisfies.

   Returns an empty set when the adapter is unknown or has no `:provides`
   declared. Used by the suite-load lint (sl-unz) for `:requires-protocols`
   capability gating.

   ```clojure
   (provides :sms-mock)
   ;; => #{:shiftlefter.sms.protocol/ISMS
   ;;     :shiftlefter.sms.protocol/ISMSInbound}
   ```"
  ([adapter-name]
   (provides adapter-name default-registry))
  ([adapter-name registry]
   (let [entry (get registry adapter-name)]
     (set (:provides entry)))))

(defn class-available?
  "True when `class-name` (a string) is loadable on the current classpath.

   The probe behind adapter availability checks (sl-gcdu). Tests redef this
   to simulate an adapter's extra dependency being absent — the unit-test
   classpath always has Playwright, so the negative path can't be exercised
   for real."
  [class-name]
  (try
    (Class/forName class-name)
    true
    (catch Throwable _ false)))

(defn- bundled-alternatives
  "Adapter names in `registry` that provide any of the same protocols as
   `entry` without requiring an extra dependency — the 'use X instead'
   remedy for a not-bundled adapter. Sorted for stable messages."
  [registry adapter-name entry]
  (let [wanted (set (:provides entry))]
    (->> registry
         (keep (fn [[k e]]
                 (when (and (not= k adapter-name)
                            (nil? (:requires-class e))
                            (some wanted (:provides e)))
                   k)))
         (sort-by name)
         vec)))

(defn adapter-unavailable-error
  "Config-load availability check for one interface's adapter (sl-gcdu).

   When the adapter's registry entry declares `:requires-class` and that
   class is not on the current classpath, returns a structured
   `:config/adapter-not-bundled` error naming both remedies: switch to a
   bundled alternative providing the same protocols, or add the
   `:requires-dep` coordinates. Returns nil when the adapter is unknown,
   fully bundled, or its dependency is present — callers treat nil as
   'nothing wrong here'.

   Fires at config load (runner's Stage 1) so a dead-end config fails as a
   planning error (exit 2) instead of at first capability provisioning."
  ([interface-name adapter-name]
   (adapter-unavailable-error interface-name adapter-name default-registry))
  ([interface-name adapter-name registry]
   (let [entry (get registry adapter-name)
         cls (:requires-class entry)]
     (when (and cls (not (class-available? cls)))
       (let [alternatives (bundled-alternatives registry adapter-name entry)]
         {:type :config/adapter-not-bundled
          :message (str (str/capitalize (name adapter-name))
                        " is supported but not bundled"
                        (when-let [note (:not-bundled-note entry)]
                          (str " (" note ")"))
                        " — "
                        (when (seq alternatives)
                          (str "use " (str/join " or " alternatives)
                               " (bundled), or "))
                        "add " (:requires-dep entry) " to your deps.edn, "
                        "or drop the resolved jars in sl/lib/ "
                        "(docs/INSTALL-TIERS.md)")
          :data {:interface interface-name
                 :adapter adapter-name
                 :requires-class cls}})))))

(defn on-provision
  "Return the adapter's `:on-provision` hook fn, or nil.

   The hook is `(fn [ctx impl] -> ctx)`, invoked by the engine after
   `cap/assoc-capability` so an adapter can seed scenario state (e.g.,
   `:sms/scenario-start-ts`) without the engine knowing about it.

   Returns nil when the adapter is unknown or declares no hook —
   callers should treat nil as 'skip the hook step'."
  ([adapter-name]
   (on-provision adapter-name default-registry))
  ([adapter-name registry]
   (get-in registry [adapter-name :on-provision])))

(defn capture-kinds
  "Return the adapter's `:capture` map ({kind {:fn ... :scenario-scoped?
   ...}}), or {} when the adapter is unknown or contributes no kinds
   (sl-3q9r)."
  ([adapter-name]
   (capture-kinds adapter-name default-registry))
  ([adapter-name registry]
   (get-in registry [adapter-name :capture] {})))

(defn capture-spec
  "Return the adapter's capture spec for `kind` ({:fn ... :scenario-scoped?
   ...}), or nil — an unsupported kind on this adapter's capabilities is a
   no-op, never an error (sl-3q9r)."
  ([adapter-name kind]
   (capture-spec adapter-name kind default-registry))
  ([adapter-name kind registry]
   (get-in registry [adapter-name :capture kind])))

(defn impl-key
  "Return the adapter's `:impl-key`, or nil.

   When non-nil, the factory wraps the protocol-bearing impl under this key
   and provisioning should extract `(get factory-result impl-key)` as the
   step-facing impl, keeping the full result as the cleanup handle. nil means
   the factory's result is itself the impl (the default / SMS-bundle path)."
  ([adapter-name]
   (impl-key adapter-name default-registry))
  ([adapter-name registry]
   (get-in registry [adapter-name :impl-key])))

(defn check-extracted-impl
  "Verify an `:impl-key` adapter's extracted impl satisfies its `:provides`.

   Guards the sl-091 failure mode: a wrapping factory whose `:impl-key`
   points at the wrong (or no) sub-value, silently storing a non-protocol
   object as the capability impl. The `:impl-key` declaration is a promise
   that the extracted value satisfies every declared protocol; this enforces
   it at provision time so the failure is a clear error rather than a cryptic
   'no protocol impl' at the first step.

   Only adapters that declare `:impl-key` are checked. Adapters without one
   return the factory result verbatim (or a bundle the interface consumes by
   convention, like SMS, whose `:provides` describes a nested object) and are
   exempt.

   Returns:
   - nil                     — satisfied, or adapter has no `:impl-key`/`:provides`
   - vector of keywords      — the `:provides` protocols the impl fails to
                                satisfy (or that could not be resolved)"
  ([adapter-name impl]
   (check-extracted-impl adapter-name impl default-registry))
  ([adapter-name impl registry]
   (when (impl-key adapter-name registry)
     (let [declared (provides adapter-name registry)
           ;; Style exception: requiring-resolve on a :provides keyword is
           ;; resolution-for-validation, not dynamic dispatch — :provides is
           ;; deliberately data (keywords), so calling satisfies? requires
           ;; turning the keyword into its protocol object. No protocol-var
           ;; alternative without coupling the registry to every protocol ns.
           unsatisfied (remove (fn [proto-kw]
                                 (try
                                   (satisfies? @(requiring-resolve (symbol proto-kw))
                                               impl)
                                   (catch Throwable _ false)))
                               declared)]
       (when (seq unsatisfied)
         (vec unsatisfied))))))

;; -----------------------------------------------------------------------------
;; Capability Operations
;; -----------------------------------------------------------------------------

(defn create-capability
  "Create a capability using the named adapter.

   Parameters:
   - adapter-name: Keyword identifying the adapter
   - config: Configuration map passed to the adapter's factory
   - registry: Optional registry map (defaults to default-registry)

   Returns:
   - Success: Result from adapter's factory (typically {:ok {...}})
   - Error: {:error {:type :adapter/unknown ...}} if adapter not found
   - Error: Result from factory on creation failure

   Examples:
   ```clojure
   (create-capability :etaoin {:headless true})
   ;; => {:ok {:browser #EtaoinBrowser{...} :etaoin-driver {...}}}
   ```"
  ([adapter-name config]
   (create-capability adapter-name config default-registry))
  ([adapter-name config registry]
   (let [adapter (get-adapter adapter-name registry)]
     (if (:error adapter)
       adapter
       ((:factory adapter) config)))))

;; The instrumentable face of the factory contract (sl-wntk): under test
;; instrumentation the :args spec shape-checks every custom registry that
;; flows through these fns (stest/instrument checks :args only); the :ret
;; envelope is the registered contract — runtime envelope handling stays in
;; provisioning's :error branch, unchanged.
(s/fdef create-capability
  :args (s/cat :adapter-name keyword?
               :config (s/nilable map?)
               :registry (s/? ::registry))
  :ret ::factory-result)

(defn cleanup-capability
  "Clean up a capability using the named adapter.

   Parameters:
   - adapter-name: Keyword identifying the adapter
   - capability: Capability to clean up (as returned by create-capability)
   - registry: Optional registry map (defaults to default-registry)

   Returns:
   - Success: Result from adapter's cleanup (typically {:ok :closed})
   - Error: {:error {:type :adapter/unknown ...}} if adapter not found
   - Error: Result from cleanup on failure

   Examples:
   ```clojure
   (cleanup-capability :etaoin {:browser b :etaoin-driver d})
   ;; => {:ok :closed}
   ```"
  ([adapter-name capability]
   (cleanup-capability adapter-name capability default-registry))
  ([adapter-name capability registry]
   (let [adapter (get-adapter adapter-name registry)]
     (if (:error adapter)
       adapter
       ((:cleanup adapter) capability)))))

(s/fdef cleanup-capability
  :args (s/cat :adapter-name keyword?
               :capability any?
               :registry (s/? ::registry))
  :ret ::factory-result)
