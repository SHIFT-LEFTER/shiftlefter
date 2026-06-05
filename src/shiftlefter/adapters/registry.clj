(ns shiftlefter.adapters.registry
  "Adapter registry for capability management.

   Maps adapter names (like `:etaoin`) to factory/cleanup functions
   plus a declared `:provides` list of protocols the produced impl
   satisfies, enabling pluggable capability creation and bind-time
   capability gating.

   ## Registry Structure

   An adapter entry has:
   - `:factory`      — function that takes config, returns capability
   - `:cleanup`      — function that takes capability, cleans up resources
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
  (:require [shiftlefter.adapters.etaoin :as etaoin]
            [shiftlefter.adapters.playwright :as playwright]
            [shiftlefter.adapters.sms-mock :as sms-mock]
            [shiftlefter.adapters.twilio :as twilio]
            [shiftlefter.sms.adapter-hooks :as sms-hooks]))

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
                :provides [:shiftlefter.browser.protocol/IBrowser]}
   :playwright {:factory  playwright/create-browser
                :cleanup  playwright/close-browser
                :provides [:shiftlefter.browser.protocol/IBrowser]}
   :sms-mock   {:factory      sms-mock/create-sms
                :cleanup      sms-mock/close-sms
                :provides     [:shiftlefter.sms.protocol/ISMS
                               :shiftlefter.sms.protocol/ISMSInbound]
                :on-provision sms-hooks/set-scenario-start-ts}
   :sms-twilio {:factory      twilio/create-sms
                :cleanup      twilio/close-sms
                :provides     [:shiftlefter.sms.protocol/ISMS]
                :on-provision sms-hooks/set-scenario-start-ts}})

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
