(ns shiftlefter.webdriver.etaoin.browser
  "Etaoin implementation of IBrowser protocol.

   Wraps an Etaoin driver to provide the standard browser operations
   used by ShiftLefter stepdefs.

   ## Resolved targets ({:q} | {:el})

   Etaoin splits find-and-act into two function families: the query family
   (`click`, `fill`, …) takes a *selector* and finds the element on use, while
   the `-el` family (`click-el`, `fill-el`, …) acts on an *already-located*
   element-id. So each element-taking op here dispatches on the target shape:
   `{:el <id>}` routes to the `-el` form (or an actions/JS workaround where no
   `-el` twin exists — right-click, drag, scroll, select), and `{:q <query>}`
   keeps the original query-family path byte-for-byte. An element handle is an
   opaque etaoin element-id."
  (:require [shiftlefter.browser.protocol :as bp]
            [shiftlefter.browser.target :as target]
            [shiftlefter.browser.locators :as locators]
            [clojure.string :as str]
            [etaoin.api :as eta]
            [etaoin.keys :as k]))

;; -----------------------------------------------------------------------------
;; Target dispatch helper
;; -----------------------------------------------------------------------------

(defn- ->el-id
  "Resolve a target to an etaoin element-id: the handle for `{:el id}`, else
   find-by-query for `{:q query}` (used where an op needs a concrete element
   for both shapes, e.g. drag/move via the actions API)."
  [driver t]
  (if (:el t) (:el t) (eta/query driver (:q t))))

;; -----------------------------------------------------------------------------
;; Nearest-enclosing-instance pruning (§8.1, sl-h7h)
;; -----------------------------------------------------------------------------

(def ^:private web-element-identifier
  "W3C WebDriver element key (etaoin's own private constant). A DOM node
   returned from `js-execute` carries its element-id under this key; reading
   it back yields exactly the id `query-all` would have produced."
  :element-6066-11e4-a52e-4f735466cecf)

(def ^:private prune-script
  "Scoped querySelectorAll fused with the §8.1 boundary filter, in one round
   trip. args: [scopeEl-or-null, cssSelector, boundaryCss]. Keeps a candidate
   whose nearest boundary ancestor is absent, is the scope, or sits above the
   scope; prunes one strictly inside the scope (it belongs to that nested
   instance). `parentElement` first so a candidate that is itself a boundary
   (every instance in the collection case) never matches itself."
  (str "var scope = arguments[0] || document;"
       "var sel = arguments[1];"
       "var boundary = arguments[2];"
       "var nodes = Array.prototype.slice.call(scope.querySelectorAll(sel));"
       "if (!boundary) { return nodes; }"
       "return nodes.filter(function (c) {"
       "  var p = c.parentElement;"
       "  var b = p ? p.closest(boundary) : null;"
       "  return b === null || b === scope || b.contains(scope);"
       "});"))

(defn- candidate-css
  "CSS string for a resolved locator under active pruning. Throws a structured
   error if the locator is not CSS-expressible — the resolver pre-validates
   element bindings, so this is a backend safety net, never the user path."
  [locator]
  (let [r (locators/locator->css (:q locator))]
    (if (:ok r)
      (:ok r)
      (throw (ex-info (str "query-all-pruned needs a CSS locator under an active "
                           "boundary; got " (pr-str (:q locator)))
                      {:type :browser/locator-not-css
                       :location "EtaoinBrowser/query-all-pruned"
                       :locator locator})))))

;; -----------------------------------------------------------------------------
;; EtaoinBrowser Implementation
;; -----------------------------------------------------------------------------

(defrecord EtaoinBrowser [driver]
  bp/IBrowser

  ;; --- Kernel Operations ---

  (open-to! [this url]
    (eta/go driver url)
    this)

  (click! [this locator]
    (if (:el locator)
      (eta/click-el driver (:el locator))
      (eta/click driver (:q locator)))
    this)

  (doubleclick! [this locator]
    (if (:el locator)
      (eta/double-click-el driver (:el locator))
      (eta/double-click driver (:q locator)))
    this)

  (rightclick! [this locator]
    ;; No `right-click-on-el` twin — for a handle, drive the actions API
    ;; directly (mirrors right-click-on, which is move-to-el + RIGHT click).
    (if (:el locator)
      (eta/perform-actions driver
                           (-> (eta/make-mouse-input)
                               (eta/add-pointer-click-el (:el locator) k/mouse-right)))
      (eta/right-click-on driver (:q locator)))
    this)

  (move-to! [this locator]
    ;; Use the W3C Actions API to teleport the pointer to the element's
    ;; in-view center point. This fires the full hover/mouseenter event
    ;; chain that pages rely on for menus, tooltips, etc.
    ;;
    ;; (Wired in sl-jsn 2026-05-13. The previous impl was a query-only
    ;; stub that resolved the locator but never moved the cursor.)
    ;; Already el-based — ->el-id handles both {:q} (query) and {:el} (direct).
    (let [el    (->el-id driver locator)
          mouse (-> (eta/make-mouse-input)
                    (eta/add-pointer-move-to-el el))]
      (eta/perform-actions driver mouse))
    this)

  (drag-to! [this from-locator to-locator]
    ;; No `drag-and-drop-el` twin. Normalize both ends to element-ids and run
    ;; the same actions sequence drag-and-drop uses internally — equivalent for
    ;; {:q}/{:q}, and the only path that works once an end is an {:el} handle.
    (let [el-from (->el-id driver from-locator)
          el-to   (->el-id driver to-locator)]
      (eta/perform-actions driver
                           (-> (eta/make-mouse-input)
                               (eta/add-pointer-move-to-el el-from)
                               (eta/with-pointer-left-btn-down
                                 (eta/add-pointer-move-to-el el-to)))))
    this)

  (fill! [this locator text]
    (if (:el locator)
      (eta/fill-el driver (:el locator) text)
      (eta/fill driver (:q locator) text))
    this)

  (element-count [_this locator]
    ;; A located element ({:el}) is exactly one element; otherwise count matches.
    (if (:el locator)
      1
      (count (eta/query-all driver (:q locator)))))

  ;; --- Query Operations ---

  (get-text [_this locator]
    (if (:el locator)
      (eta/get-element-text-el driver (:el locator))
      (eta/get-element-text driver (:q locator))))

  (get-url [_this]
    (eta/get-url driver))

  (get-title [_this]
    (eta/get-title driver))

  (visible? [_this locator]
    (try
      (if (:el locator)
        (eta/displayed-el? driver (:el locator))
        (eta/displayed? driver (:q locator)))
      (catch Exception _
        false)))

  ;; --- Navigation ---

  (go-back! [this]
    (eta/back driver)
    this)

  (go-forward! [this]
    (eta/forward driver)
    this)

  (refresh! [this]
    (eta/refresh driver)
    this)

  ;; --- Scrolling ---

  (scroll-to! [this locator]
    ;; No `scroll-query-el` twin — for a handle, scroll it into view via JS.
    (if (:el locator)
      (eta/js-execute driver "arguments[0].scrollIntoView();" (eta/el->ref (:el locator)))
      (eta/scroll-query driver (:q locator)))
    this)

  (scroll-to-position! [this position]
    (case position
      :top (eta/scroll-top driver)
      :bottom (eta/scroll-bottom driver))
    this)

  ;; --- Form Operations ---

  (clear! [this locator]
    (if (:el locator)
      (eta/clear-el driver (:el locator))
      (eta/clear driver (:q locator)))
    this)

  (select! [this locator text]
    ;; No `select-el` twin — mirror etaoin's select for a handle: click the
    ;; <select>, then the matching <option> found scoped within it.
    (if (:el locator)
      (let [select-el (:el locator)]
        (eta/click-el driver select-el)
        (eta/click-el driver (eta/query-from driver select-el
                                             {:tag :option :fn/has-text text})))
      (eta/select driver (:q locator) text))
    this)

  (press-key! [this key-str]
    (eta/fill-active driver key-str)
    this)

  ;; --- Element Queries ---

  (get-attribute [_this locator attribute]
    (if (:el locator)
      (eta/get-element-attr-el driver (:el locator) (keyword attribute))
      (eta/get-element-attr driver (:q locator) (keyword attribute))))

  (get-value [_this locator]
    (if (:el locator)
      (eta/get-element-value-el driver (:el locator))
      (eta/get-element-value driver (:q locator))))

  (enabled? [_this locator]
    (try
      (if (:el locator)
        (eta/enabled-el? driver (:el locator))
        (eta/enabled? driver (:q locator)))
      (catch Exception _
        false)))

  ;; --- Alerts ---

  (accept-alert! [this]
    (eta/accept-alert driver)
    this)

  (dismiss-alert! [this]
    (eta/dismiss-alert driver)
    this)

  (get-alert-text [_this]
    (eta/get-alert-text driver))

  ;; --- Window Management ---

  (maximize-window! [this]
    (eta/maximize driver)
    this)

  (set-window-size! [this width height]
    (eta/set-window-size driver width height)
    this)

  (switch-to-next-window! [this]
    (eta/switch-window-next driver)
    this)

  ;; --- Frames ---

  (switch-to-frame! [this locator]
    (if (:el locator)
      ;; Frame-by-handle is out of scope for 0.4.6 (no etaoin switch-frame-el,
      ;; and no acceptance criterion routes an indexed ref into a frame switch).
      (throw (ex-info "switch-to-frame! does not support {:el} handle targets yet"
                      {:type :browser/handle-target-unsupported
                       :op :switch-to-frame!
                       :location "EtaoinBrowser/switch-to-frame!"}))
      (eta/switch-frame driver (:q locator)))
    this)

  (switch-to-main-frame! [this]
    (eta/switch-frame-top driver)
    this)

  ;; --- Element Handles & Scoped Find (0.4.6) ---

  (query-all [_this scope locator]
    (target/check-query-all-args! scope locator)
    (let [q   (:q locator)
          ids (if (target/el-target? scope)
                (eta/query-all-from driver (:el scope) q)
                (eta/query-all driver q))]
      (mapv (fn [id] {:el id}) ids)))

  (query-all-pruned [this scope locator boundary-css]
    (target/check-query-all-args! scope locator)
    (if (str/blank? boundary-css)
      ;; No boundary → native query-all (keeps XPath anchors working when the
      ;; component declares no collections, so there is nothing to prune).
      (bp/query-all this scope locator)
      ;; One js-execute: scoped querySelectorAll + boundary closest() filter.
      (let [scope-ref (when (target/el-target? scope) (eta/el->ref (:el scope)))
            css       (candidate-css locator)
            result    (eta/js-execute driver prune-script scope-ref css boundary-css)]
        (mapv (fn [el-obj] {:el (get el-obj web-element-identifier)}) result)))))

;; -----------------------------------------------------------------------------
;; Factory
;; -----------------------------------------------------------------------------

(defn make-etaoin-browser
  "Create an EtaoinBrowser from an Etaoin driver.

   The driver should already have a session (created via eta/chrome or similar)."
  [etaoin-driver]
  (->EtaoinBrowser etaoin-driver))

(defn etaoin-driver
  "Extract the underlying Etaoin driver from an EtaoinBrowser."
  [browser]
  (:driver browser))
