(ns shiftlefter.webdriver.playwright.browser
  "Playwright implementation of IBrowser protocol.

   Wraps a Playwright Page object to provide the standard browser operations
   used by ShiftLefter stepdefs. This is an alternative backend to Etaoin,
   proving the IBrowser abstraction supports multiple browser engines.

   ## Architecture

   PlaywrightBrowser holds:
   - `page`       — Playwright Page instance (the main interaction surface)
   - `pw-context` — Map with :playwright, :browser, :context for lifecycle

   ## Lazy Imports

   Playwright classes are imported at runtime (not compile time) so this
   namespace compiles without Playwright on the classpath. The defrecord
   uses reflection for interop calls — fine for I/O-bound browser ops.
   Only the factory function (`launch-playwright-browser`) triggers imports.

   ## Locator Translation

   ShiftLefter locators arrive as `{:q {:css \"...\"}}` etc. (Etaoin format).
   This adapter translates them to Playwright's string-based selectors:
   - `{:css \".foo\"}`     → `\".foo\"` (CSS is Playwright's default)
   - `{:xpath \"//div\"}`  → `\"xpath=//div\"`
   - `{:id \"login\"}`     → `\"#login\"`
   - `{:tag \"div\"}`      → `\"div\"`
   - `{:class \"btn\"}`    → `\".btn\"`
   - `{:name \"email\"}`   → `\"[name='email']\"`
   - string passthrough    → as-is

   ## Alert Handling

   Playwright uses event-driven dialog handling (not synchronous like WebDriver).
   We pre-register a dialog listener on page creation that stores the latest
   dialog reference. accept-alert!/dismiss-alert!/get-alert-text operate on it."
  (:require [shiftlefter.browser.protocol :as bp]
            [shiftlefter.webdriver.playwright.keys :as pw-keys]))

;; -----------------------------------------------------------------------------
;; Lazy Imports — Playwright classes loaded at runtime only
;; -----------------------------------------------------------------------------

(defonce ^:private imports-loaded? (atom false))

(defn- ensure-playwright-imports!
  "Import Playwright classes at runtime. Called once from factory functions.
   This allows the namespace to compile without Playwright on the classpath."
  []
  (when-not @imports-loaded?
    (import '[com.microsoft.playwright Playwright BrowserType$LaunchOptions
              Locator$ClickOptions]
            '[com.microsoft.playwright.options MouseButton])
    (reset! imports-loaded? true)))

;; -----------------------------------------------------------------------------
;; Locator Translation
;; -----------------------------------------------------------------------------

(defn- resolve-playwright-selector
  "Translate a ShiftLefter resolved locator `{:q ...}` to a Playwright selector string."
  [locator]
  (let [q (:q locator)]
    (cond
      (string? q) q
      (keyword? q) (name q)
      (map? q)
      (cond
        (:css q)   (:css q)
        (:xpath q) (str "xpath=" (:xpath q))
        (:id q)    (str "#" (:id q))
        (:tag q)   (:tag q)
        (:class q) (str "." (:class q))
        (:name q)  (str "[name='" (:name q) "']")
        :else      (throw (ex-info "Unsupported locator type for Playwright"
                                   {:locator locator})))
      :else (throw (ex-info "Cannot resolve locator for Playwright"
                            {:locator locator})))))

;; -----------------------------------------------------------------------------
;; Right-click helper — needs imported classes at runtime
;; -----------------------------------------------------------------------------

(defn- make-right-click-options
  "Create a Locator.ClickOptions with MouseButton.RIGHT.
   Must be called after ensure-playwright-imports!."
  []
  (let [opts-class (resolve 'com.microsoft.playwright.Locator$ClickOptions)
        mb-class   (resolve 'com.microsoft.playwright.options.MouseButton)
        right      (.get (.getField ^Class mb-class "RIGHT") nil)
        opts       (.newInstance ^Class opts-class)]
    (.setButton opts right)))

;; -----------------------------------------------------------------------------
;; PlaywrightBrowser Implementation
;; -----------------------------------------------------------------------------

(defrecord PlaywrightBrowser [page pw-context]
  bp/IBrowser

  ;; --- Kernel Operations ---

  (open-to! [this url]
    (.navigate page ^String url)
    this)

  (click! [this locator]
    (.click (.locator page (resolve-playwright-selector locator)))
    this)

  (doubleclick! [this locator]
    (.dblclick (.locator page (resolve-playwright-selector locator)))
    this)

  (rightclick! [this locator]
    (.click (.locator page (resolve-playwright-selector locator))
            (make-right-click-options))
    this)

  (move-to! [this locator]
    (.hover (.locator page (resolve-playwright-selector locator)))
    this)

  (drag-to! [this from-locator to-locator]
    (.dragTo (.locator page (resolve-playwright-selector from-locator))
             (.locator page (resolve-playwright-selector to-locator)))
    this)

  (fill! [this locator text]
    (.fill (.locator page (resolve-playwright-selector locator)) ^String text)
    this)

  (element-count [_this locator]
    (.count (.locator page (resolve-playwright-selector locator))))

  ;; --- Query Operations ---

  (get-text [_this locator]
    (.textContent (.locator page (resolve-playwright-selector locator))))

  (get-url [_this]
    (.url page))

  (get-title [_this]
    (.title page))

  (visible? [_this locator]
    (.isVisible (.locator page (resolve-playwright-selector locator))))

  ;; --- Navigation ---

  (go-back! [this]
    (.goBack page)
    this)

  (go-forward! [this]
    (.goForward page)
    this)

  (refresh! [this]
    (.reload page)
    this)

  ;; --- Scrolling ---

  (scroll-to! [this locator]
    (.scrollIntoViewIfNeeded (.locator page (resolve-playwright-selector locator)))
    this)

  (scroll-to-position! [this position]
    (case position
      :top (.evaluate page "window.scrollTo(0, 0)")
      :bottom (.evaluate page "window.scrollTo(0, document.body.scrollHeight)"))
    this)

  ;; --- Form Operations ---

  (clear! [this locator]
    (.clear (.locator page (resolve-playwright-selector locator)))
    this)

  (select! [this locator text]
    (.selectOption (.locator page (resolve-playwright-selector locator))
                   ^String text)
    this)

  (press-key! [this key-str]
    (let [pw-key (pw-keys/translate-key-string key-str)]
      (.press (.locator page "*:focus") pw-key))
    this)

  ;; --- Element Queries ---

  (get-attribute [_this locator attribute]
    (.getAttribute (.locator page (resolve-playwright-selector locator))
                   ^String attribute))

  (get-value [_this locator]
    (.inputValue (.locator page (resolve-playwright-selector locator))))

  (enabled? [_this locator]
    (.isEnabled (.locator page (resolve-playwright-selector locator))))

  ;; --- Alerts ---
  ;; Playwright uses event-driven dialogs. We store the last dialog
  ;; in the pw-context atom for synchronous access.

  (accept-alert! [this]
    (when-let [dialog @(:last-dialog pw-context)]
      (.accept dialog)
      (reset! (:last-dialog pw-context) nil))
    this)

  (dismiss-alert! [this]
    (when-let [dialog @(:last-dialog pw-context)]
      (.dismiss dialog)
      (reset! (:last-dialog pw-context) nil))
    this)

  (get-alert-text [_this]
    (when-let [dialog @(:last-dialog pw-context)]
      (.message dialog)))

  ;; --- Window Management ---

  (maximize-window! [this]
    ;; Playwright doesn't have maximize — set large viewport
    (.setViewportSize page 1920 1080)
    this)

  (set-window-size! [this width height]
    (.setViewportSize page (int width) (int height))
    this)

  (switch-to-next-window! [this]
    (let [pages (.pages (.context page))
          current-idx (.indexOf pages page)
          next-idx (mod (inc current-idx) (.size pages))
          next-page (.get pages next-idx)]
      ;; Bring focus to next page
      (.bringToFront next-page))
    this)

  ;; --- Frames ---

  (switch-to-frame! [this locator]
    ;; Playwright uses frameLocator which returns a scoped locator.
    ;; For protocol compatibility, we evaluate in the frame context.
    ;; NOTE: This is a semantic mismatch — Playwright's frame model
    ;; differs from WebDriver's context-switching. For now, we use
    ;; the page's frame() method with the element handle.
    (let [sel (resolve-playwright-selector locator)
          frame-el (.elementHandle (.locator page sel))
          frame (.contentFrame frame-el)]
      ;; Store current page's main frame for switch-to-main-frame!
      ;; We can't truly "switch" in Playwright, so we note the frame
      (reset! (:active-frame pw-context) frame))
    this)

  (switch-to-main-frame! [this]
    (reset! (:active-frame pw-context) nil)
    this))

;; -----------------------------------------------------------------------------
;; Factory
;; -----------------------------------------------------------------------------

(defn launch-playwright-browser
  "Launch a new Playwright browser and return a PlaywrightBrowser.

   Options:
   - :headless — run headless (default true)
   - :browser-type — :chromium (default), :firefox, or :webkit

   Returns a PlaywrightBrowser. Call close-playwright-browser to clean up.

   Requires com.microsoft.playwright/playwright on the classpath.
   Throws with a clear message if it's missing."
  ([] (launch-playwright-browser {}))
  ([{:keys [headless browser-type]
     :or {headless true browser-type :chromium}}]
   (ensure-playwright-imports!)
   (let [pw-class    (resolve 'com.microsoft.playwright.Playwright)
         create-meth (.getMethod ^Class pw-class "create" (into-array Class []))
         pw          (.invoke create-meth nil (object-array 0))
         bt          (case browser-type
                       :chromium (.chromium pw)
                       :firefox (.firefox pw)
                       :webkit (.webkit pw))
         opts-class  (resolve 'com.microsoft.playwright.BrowserType$LaunchOptions)
         launch-opts (-> (.newInstance ^Class opts-class)
                         (.setHeadless (boolean headless)))
         browser     (.launch bt launch-opts)
         context     (.newContext browser)
         page        (.newPage context)
         last-dialog (atom nil)
         active-frame (atom nil)
         pw-context  {:playwright pw
                      :browser browser
                      :context context
                      :last-dialog last-dialog
                      :active-frame active-frame}]
     ;; Register dialog handler to capture alerts
     (.onDialog page
                (reify java.util.function.Consumer
                  (accept [_ dialog]
                    (reset! last-dialog dialog))))
     (->PlaywrightBrowser page pw-context))))

(defn close-playwright-browser
  "Close and clean up a PlaywrightBrowser.
   Closes the browser and releases the Playwright instance."
  [pw-browser]
  (let [{:keys [browser playwright]} (:pw-context pw-browser)]
    (try (.close browser) (catch Exception _))
    (try (.close playwright) (catch Exception _))))

(defn make-playwright-browser
  "Create a PlaywrightBrowser from an existing Playwright Page.
   Used for testing or when lifecycle is managed externally."
  [page pw-context]
  (->PlaywrightBrowser page pw-context))
