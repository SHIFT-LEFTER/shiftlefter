(ns shiftlefter.adapters.etaoin
  "Etaoin adapter for the adapter registry.

   Provides factory and cleanup functions for browser capabilities
   using the Etaoin WebDriver library.

   ## Factory

   `create-browser` creates a new browser instance from config.
   Returns the EtaoinBrowser implementing IBrowser protocol.

   ## Cleanup

   `close-browser` closes the browser session and releases resources."
  (:require [etaoin.api :as eta]
            [shiftlefter.webdriver.etaoin.browser :as browser]))

;; -----------------------------------------------------------------------------
;; Factory
;; -----------------------------------------------------------------------------

(defn create-browser
  "Create a new browser instance from configuration.

   Config options:
   - :headless — if true, run headless (default: false)
   - :webdriver-url — URL of WebDriver server (optional, uses default if not provided)

   Returns:
   - Success: {:ok {:browser EtaoinBrowser :etaoin-driver raw-driver}}
   - Error: {:error {:type :adapter/create-failed ...}}

   Examples:
   ```clojure
   (create-browser {:headless true})
   ;; => {:ok {:browser #EtaoinBrowser{...} :etaoin-driver {...}}}
   ```"
  [config]
  (try
    (let [headless? (get config :headless false)
          opts {:type :chrome
                :headless headless?}
          ;; Create session via Etaoin
          eta-driver (eta/chrome opts)
          ;; Create EtaoinBrowser for protocol operations
          etaoin-browser (browser/make-etaoin-browser eta-driver)]
      {:ok {:browser etaoin-browser
            :etaoin-driver eta-driver}})
    (catch Exception e
      {:error {:type :adapter/create-failed
               :adapter :etaoin
               :message (ex-message e)
               :config config}})))

;; -----------------------------------------------------------------------------
;; Cleanup
;; -----------------------------------------------------------------------------

(defn close-browser
  "Close the browser and release resources.

   Takes the capability map returned by `create-browser`.

   Returns:
   - Success: {:ok :closed}
   - Error: {:error {:type :adapter/cleanup-failed ...}}

   Examples:
   ```clojure
   (close-browser {:browser b :etaoin-driver d})
   ;; => {:ok :closed}
   ```"
  [capability]
  (try
    (when-let [eta-driver (:etaoin-driver capability)]
      (eta/quit eta-driver))
    {:ok :closed}
    (catch Exception e
      {:error {:type :adapter/cleanup-failed
               :adapter :etaoin
               :message (ex-message e)}})))
