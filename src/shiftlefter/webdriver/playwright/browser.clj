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
            [shiftlefter.browser.target :as target]
            [shiftlefter.webdriver.playwright.keys :as pw-keys]
            [clojure.string :as str]))

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
;; Target dispatch helper
;; -----------------------------------------------------------------------------

(defn- ->loc
  "Resolve a target to a Playwright Locator. Playwright ops are methods on a
   Locator, so a handle ({:el <Locator>}) flows in directly and a query target
   ({:q <query>}) becomes (.locator page sel) exactly as before — one normalizer
   serves every element-taking op."
  [page t]
  (if (:el t)
    (:el t)
    (.locator page (resolve-playwright-selector t))))

;; -----------------------------------------------------------------------------
;; Nearest-enclosing-instance pruning (§8.1, sl-h7h)
;; -----------------------------------------------------------------------------

(def ^:private pw-keep-script
  "Per-candidate §8.1 boundary check, run with the candidate as `element`.
   arg: [scopeHandle-or-null, boundaryCss]. Keep when the nearest boundary
   ancestor is absent, is the scope, or sits above the scope; prune one
   strictly inside the scope. `parentElement` first so a candidate that is
   itself a boundary never matches itself."
  (str "(element, args) => {"
       "  const scope = args[0] || document;"
       "  const boundary = args[1];"
       "  const p = element.parentElement;"
       "  const b = p ? p.closest(boundary) : null;"
       "  return b === null || b === scope || b.contains(scope);"
       "}"))

(defn- scope->element-handle
  "The scope as a Playwright ElementHandle for passing into evaluate, or nil
   for `:document` (the script falls back to document). A scope handle is a
   Locator (from query-all); `.elementHandle` materializes it."
  [scope]
  (when (target/el-target? scope)
    (.elementHandle (:el scope))))

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
    (.click (->loc page locator))
    this)

  (doubleclick! [this locator]
    (.dblclick (->loc page locator))
    this)

  (rightclick! [this locator]
    (.click (->loc page locator)
            (make-right-click-options))
    this)

  (move-to! [this locator]
    (.hover (->loc page locator))
    this)

  (drag-to! [this from-locator to-locator]
    (.dragTo (->loc page from-locator)
             (->loc page to-locator))
    this)

  (fill! [this locator text]
    (.fill (->loc page locator) ^String text)
    this)

  (element-count [_this locator]
    (.count (->loc page locator)))

  ;; --- Query Operations ---

  (get-text [_this locator]
    (.textContent (->loc page locator)))

  (get-url [_this]
    (.url page))

  (get-title [_this]
    (.title page))

  (visible? [_this locator]
    (.isVisible (->loc page locator)))

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
    (.scrollIntoViewIfNeeded (->loc page locator))
    this)

  (scroll-to-position! [this position]
    (case position
      :top (.evaluate page "window.scrollTo(0, 0)")
      :bottom (.evaluate page "window.scrollTo(0, document.body.scrollHeight)"))
    this)

  ;; --- Form Operations ---

  (clear! [this locator]
    (.clear (->loc page locator))
    this)

  (select! [this locator text]
    (.selectOption (->loc page locator)
                   ^String text)
    this)

  (press-key! [this key-str]
    (let [pw-key (pw-keys/translate-key-string key-str)]
      (.press (.locator page "*:focus") pw-key))
    this)

  ;; --- Element Queries ---

  (get-attribute [_this locator attribute]
    (.getAttribute (->loc page locator)
                   ^String attribute))

  (get-value [_this locator]
    (.inputValue (->loc page locator)))

  (enabled? [_this locator]
    (.isEnabled (->loc page locator)))

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
    (let [frame-el (.elementHandle (->loc page locator))
          frame (.contentFrame frame-el)]
      ;; Store current page's main frame for switch-to-main-frame!
      ;; We can't truly "switch" in Playwright, so we note the frame
      (reset! (:active-frame pw-context) frame))
    this)

  (switch-to-main-frame! [this]
    (reset! (:active-frame pw-context) nil)
    this)

  ;; --- Element Handles & Scoped Find (0.4.6) ---

  (query-all [_this scope locator]
    (target/check-query-all-args! scope locator)
    (let [sel  (resolve-playwright-selector locator)
          base (if (target/el-target? scope)
                 (.locator (:el scope) sel)   ;; scoped: parentLocator.locator(childSel)
                 (.locator page sel))         ;; document
          locs (.all base)]
      (mapv (fn [l] {:el l}) locs)))

  (query-all-pruned [this scope locator boundary-css]
    (target/check-query-all-args! scope locator)
    (let [all (bp/query-all this scope locator)]
      (if (str/blank? boundary-css)
        all
        ;; Filter the candidate Locators by the §8.1 boundary check. One
        ;; IBrowser call (acceptance #5); internally a closest() check per
        ;; candidate — Playwright's value-returning evaluate can't round-trip
        ;; element handles cleanly, and this keeps the result type-consistent
        ;; with query-all (Locators). Per-hop round-trip optimization for this
        ;; backend is sl-zi2, not here.
        (let [scope-handle (scope->element-handle scope)
              arg (java.util.Arrays/asList (object-array [scope-handle boundary-css]))]
          (filterv (fn [{loc :el}] (.evaluate loc pw-keep-script arg)) all))))))

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
    ;; Intentional bare catches: cleanup should not throw even if browser already closed
    (try (.close browser) (catch Exception _))
    (try (.close playwright) (catch Exception _))))

(defn make-playwright-browser
  "Create a PlaywrightBrowser from an existing Playwright Page.
   Used for testing or when lifecycle is managed externally."
  [page pw-context]
  (->PlaywrightBrowser page pw-context))
