(ns shiftlefter.adapters.registry
  "Adapter registry for capability management.

   Maps adapter names (like `:etaoin`) to factory/cleanup functions,
   enabling pluggable capability creation.

   ## Registry Structure

   An adapter entry has:
   - `:factory` — function that takes config, returns capability
   - `:cleanup` — function that takes capability, cleans up resources

   ## Usage

   ```clojure
   ;; Get adapter by name
   (get-adapter :etaoin)
   ;; => {:factory #'shiftlefter.adapters.etaoin/create-browser
   ;;     :cleanup #'shiftlefter.adapters.etaoin/close-browser}

   ;; Create capability
   (create-capability :etaoin {:headless true})
   ;; => {:ok {:browser ... :etaoin-driver ...}}

   ;; Cleanup capability
   (cleanup-capability :etaoin capability)
   ;; => {:ok :closed}
   ```"
  (:require [shiftlefter.adapters.etaoin :as etaoin]))

;; -----------------------------------------------------------------------------
;; Default Registry
;; -----------------------------------------------------------------------------

(def default-registry
  "Built-in adapters shipped with ShiftLefter.

   Currently includes:
   - :etaoin — Etaoin WebDriver for browser automation"
  {:etaoin {:factory etaoin/create-browser
            :cleanup etaoin/close-browser}})

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
   ;; => {:error {:type :adapter/unknown :adapter :unknown :known [:etaoin]}}
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
