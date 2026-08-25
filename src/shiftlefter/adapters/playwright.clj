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

   `close-browser` closes the browser and releases Playwright resources.

   ## Capture (sl-sehc)

   `capture-screenshot` and `capture-page-source` are the adapter's
   registry-contributed capture kinds, resolved through the same lazy
   boundary. No `:console` — impossible in the drain shape (no browser-side
   buffer; Playwright console messages are listener events); the
   subscription-shape design is sl-lam0.")

;; -----------------------------------------------------------------------------
;; Factory
;; -----------------------------------------------------------------------------

;; DECISION (sl-x7ec, 2026-08-01): no automation-hygiene prefs here — parity
;; with the etaoin defaults (sl-0fco: breach popup, save-password bubble,
;; autofill offers) holds BY CONSTRUCTION. Every session this adapter creates
;; goes launch() -> newContext() -> newPage(): Playwright browser contexts on
;; Chromium are off-the-record profiles, and OTR profiles have no password
;; store — the popup machinery isn't suppressed, the profile it needs doesn't
;; exist. This covers the bundled Chromium AND :channel-driven branded
;; Chrome/Edge (the context model, not the binary, is what matters; the
;; unbranded build additionally lacks the leak-detection API keys). Playwright's
;; own default switches do NOT disable this machinery — verified against the
;; driver-bundle's chromiumSwitches.js — so porting the prefs as launch args
;; would be dead code teaching a false load-bearing story, and accepting
;; :adapter-opts :prefs here would be a silently-ignored lie. Neither ships.
;;
;; REOPEN IF this adapter ever gets a non-OTR route to a real profile —
;; both shapes live in sl-3j7c (playwright-costume spike): (a) connectOverCDP
;; to a costume's already-running Chrome — hygiene responsibility then sits
;; with whoever SPAWNED the profile (the wardrobe machinery, where the sl-0fco
;; prefs already live on the etaoin side); the Playwright attacher inherits,
;; it does not configure; (b) launchPersistentContext(userDataDir) — a real
;; profile with the machinery live, prefs become this adapter's problem.
;; User-facing story: docs/INSTALL-TIERS.md § Backend differences.

(defn create-browser
  "Create a new Playwright browser instance from configuration.

   Config options:
   - :headless — if true, run headless (default: true)
   - :pruning — nested-pruning strategy (sl-tnx): :fast (default) or :safe;
     see docs/INSTALL-TIERS.md § Backend differences. Playwright-meaning
     only — the etaoin backend is always fused/fast.
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
    (let [pruning (get config :pruning :fast)]
      (when-not (contains? #{:fast :safe} pruning)
        (throw (ex-info (str "Unknown :pruning strategy " (pr-str pruning)
                             " — must be :fast (default) or :safe")
                        {:type :adapter/invalid-config
                         :adapter :playwright
                         :key :pruning
                         :value pruning})))
      (let [launch-fn (resolve 'shiftlefter.webdriver.playwright.browser/launch-playwright-browser)
            headless? (get config :headless true)
            adapter-opts (get config :adapter-opts {})
            opts (merge {:headless headless? :pruning pruning} adapter-opts)
            browser (launch-fn opts)]
        {:ok {:browser browser}}))
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

   Takes the UNWRAPPED capability map — the value under `:ok` in
   `create-browser`'s return, NOT the wrapped `{:ok {...}}` result itself.
   A capability without `:browser` is an error, not a silent success —
   same wrong-shape hardening as the etaoin adapter (sl-9vag).

   Returns:
   - Success: {:ok :closed}
   - Error: {:error {:type :adapter/cleanup-failed ...}}

   Examples:
   ```clojure
   (close-browser (:ok (create-browser {:headless true})))
   ;; => {:ok :closed}
   ```"
  [capability]
  (if-let [browser (:browser capability)]
    (try
      (require 'shiftlefter.webdriver.playwright.browser)
      (let [close-fn (resolve 'shiftlefter.webdriver.playwright.browser/close-playwright-browser)]
        (close-fn browser)
        {:ok :closed})
      (catch Exception e
        {:error {:type :adapter/cleanup-failed
                 :adapter :playwright
                 :message (ex-message e)}}))
    {:error {:type :adapter/cleanup-failed
             :adapter :playwright
             :message (str "No :browser in capability — nothing was closed. "
                           "Pass the unwrapped map (the value under :ok of "
                           "create-browser's return), not the wrapped {:ok {...}} result.")}}))

;; -----------------------------------------------------------------------------
;; Capture (sl-sehc)
;; -----------------------------------------------------------------------------

(defn- require-browser
  "The PlaywrightBrowser from a capture request's handle, or throw (a
   handle without the browser is a capture infrastructure failure — D4
   converts the throw to a capture-attributed error)."
  [{:keys [handle]}]
  (or (:browser handle)
      (throw (ex-info "no :browser in capture handle"
                      {:type :capture/no-browser}))))

(defn capture-screenshot
  "PNG screenshot of the current page, as bytes. Resolved through the
   lazy-load boundary like create-browser — capture only ever runs after
   the factory succeeded, so the require is a cache hit."
  [req]
  (let [browser (require-browser req)
        _ (require 'shiftlefter.webdriver.playwright.browser)
        shot-fn (resolve 'shiftlefter.webdriver.playwright.browser/screenshot-bytes)
        data ^bytes (shot-fn browser)]
    (when-not (and data (pos? (alength data)))
      (throw (ex-info "empty screenshot response" {:type :capture/empty-screenshot})))
    {:data data
     :media-type "image/png"
     :ext "png"}))

(defn capture-page-source
  "The current page's full HTML source."
  [req]
  (let [browser (require-browser req)
        _ (require 'shiftlefter.webdriver.playwright.browser)
        content-fn (resolve 'shiftlefter.webdriver.playwright.browser/page-content)]
    {:data (content-fn browser)
     :media-type "text/html"
     :ext "html"}))
