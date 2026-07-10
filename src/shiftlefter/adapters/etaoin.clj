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
            [shiftlefter.config.user :as user-config]
            [shiftlefter.webdriver.etaoin.browser :as browser]))

;; -----------------------------------------------------------------------------
;; Factory
;; -----------------------------------------------------------------------------

(defn create-browser
  "Create a new browser instance from configuration.

   Config options:
   - :headless — if true, run headless (default: false)
   - :adapter-opts — map merged into Etaoin options (e.g., :size, :prefs, :user-agent)
   - :webdriver-url — URL of WebDriver server (optional, uses default if not provided)

   Returns:
   - Success: {:ok {:browser EtaoinBrowser :etaoin-driver raw-driver}}
   - Error: {:error {:type :adapter/create-failed ...}}

   Examples:
   ```clojure
   (create-browser {:headless true})
   ;; => {:ok {:browser #EtaoinBrowser{...} :etaoin-driver {...}}}

   (create-browser {:headless true
                    :adapter-opts {:size {:width 1280 :height 720}}})
   ;; => {:ok {:browser #EtaoinBrowser{...} :etaoin-driver {...}}}
   ```"
  [config]
  (try
    (let [headless? (get config :headless false)
          adapter-opts (get config :adapter-opts {})
          ;; Chromedriver discovery: adapter-opts :path-driver > config.edn > PATH.
          ;; Shared resolver — single source of truth with the costume path.
          path-driver (user-config/resolve-chromedriver-path adapter-opts)
          adapter-opts (if path-driver
                         (assoc adapter-opts :path-driver path-driver)
                         adapter-opts)
          opts (merge {:type :chrome
                       :headless headless?}
                      adapter-opts)
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

   Takes the UNWRAPPED capability map — the value under `:ok` in
   `create-browser`'s return, NOT the wrapped `{:ok {...}}` result itself.
   Passing the wrapped map is an error: a capability without `:etaoin-driver`
   means nothing gets quit, so returning success would silently leak the
   chromedriver + Chrome process tree (sl-9vag; orphans reparent to PID 1 and
   live forever — killing chromedriver does NOT reap its Chrome children).

   Returns:
   - Success: {:ok :closed}
   - Error: {:error {:type :adapter/cleanup-failed ...}}

   Examples:
   ```clojure
   (close-browser (:ok (create-browser {:headless true})))
   ;; => {:ok :closed}

   (close-browser (create-browser {:headless true}))   ; wrapped — WRONG
   ;; => {:error {:type :adapter/cleanup-failed ...}}
   ```"
  [capability]
  (if-let [eta-driver (:etaoin-driver capability)]
    (try
      (eta/quit eta-driver)
      {:ok :closed}
      (catch Exception e
        {:error {:type :adapter/cleanup-failed
                 :adapter :etaoin
                 :message (ex-message e)}}))
    {:error {:type :adapter/cleanup-failed
             :adapter :etaoin
             :message (str "No :etaoin-driver in capability — nothing was closed. "
                           "Pass the unwrapped map (the value under :ok of "
                           "create-browser's return), not the wrapped {:ok {...}} result.")}}))
