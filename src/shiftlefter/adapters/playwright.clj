(ns shiftlefter.adapters.playwright
  "Playwright adapter for the adapter registry.

   Provides factory and cleanup functions for browser capabilities
   using the Playwright Java library.

   ## Lazy Loading

   This adapter uses dynamic `require` and `resolve` so it compiles
   without Playwright on the classpath. If Playwright is missing at
   runtime, `create-browser` returns a clear error.

   ## Factory

   `create-browser` creates a new browser instance from config.
   Returns the PlaywrightBrowser implementing IBrowser protocol.

   ## Cleanup

   `close-browser` closes the browser and releases Playwright resources.")

;; -----------------------------------------------------------------------------
;; Factory
;; -----------------------------------------------------------------------------

(defn create-browser
  "Create a new Playwright browser instance from configuration.

   Config options:
   - :headless — if true, run headless (default: true)
   - :adapter-opts — map merged into Playwright launch options
     Common adapter-opts:
     - :browser-type — :chromium (default), :firefox, or :webkit

   Returns:
   - Success: {:ok {:browser PlaywrightBrowser}}
   - Error: {:error {:type :adapter/create-failed ...}}

   Requires `com.microsoft.playwright/playwright` on the classpath.
   Returns a clear error if the dependency is missing.

   Examples:
   ```clojure
   (create-browser {:headless true})
   ;; => {:ok {:browser #PlaywrightBrowser{...}}}

   (create-browser {:headless false
                    :adapter-opts {:browser-type :firefox}})
   ;; => {:ok {:browser #PlaywrightBrowser{...}}}
   ```"
  [config]
  (try
    (require 'shiftlefter.webdriver.playwright.browser)
    (let [launch-fn (resolve 'shiftlefter.webdriver.playwright.browser/launch-playwright-browser)
          headless? (get config :headless true)
          adapter-opts (get config :adapter-opts {})
          opts (merge {:headless headless?} adapter-opts)
          browser (launch-fn opts)]
      {:ok {:browser browser}})
    (catch ClassNotFoundException _
      {:error {:type :adapter/dependency-missing
               :adapter :playwright
               :message "Playwright is not on the classpath. Add com.microsoft.playwright/playwright to your deps.edn."
               :config config}})
    (catch Exception e
      {:error {:type :adapter/create-failed
               :adapter :playwright
               :message (ex-message e)
               :config config}})))

;; -----------------------------------------------------------------------------
;; Cleanup
;; -----------------------------------------------------------------------------

(defn close-browser
  "Close the Playwright browser and release resources.

   Takes the capability map returned by `create-browser`.

   Returns:
   - Success: {:ok :closed}
   - Error: {:error {:type :adapter/cleanup-failed ...}}

   Examples:
   ```clojure
   (close-browser {:browser pw-browser})
   ;; => {:ok :closed}
   ```"
  [capability]
  (try
    (require 'shiftlefter.webdriver.playwright.browser)
    (let [close-fn (resolve 'shiftlefter.webdriver.playwright.browser/close-playwright-browser)]
      (when-let [browser (:browser capability)]
        (close-fn browser))
      {:ok :closed})
    (catch Exception e
      {:error {:type :adapter/cleanup-failed
               :adapter :playwright
               :message (ex-message e)}})))
