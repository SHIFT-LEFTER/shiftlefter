(ns shiftlefter.subjects.browser
  "Persistent browser with auto-reconnect on session failure.

   PersistentBrowser wraps an EtaoinBrowser and automatically reconnects
   when the WebDriver session dies (e.g., after laptop sleep/wake).

   ## How It Works

   1. Each browser operation is attempted on the underlying EtaoinBrowser
   2. If a session error occurs (invalid session id, etc.), we:
      a. Call `connect-persistent!` to establish a new session
      b. Retry the operation once with the new browser
   3. If reconnect fails, the original error is surfaced
   4. Before navigation, ensures Chrome has a visible window

   ## Usage

   Users don't create PersistentBrowser directly — it's returned by
   `subjects/init-persistent!` and `subjects/connect-persistent!`."
  (:require [shiftlefter.browser.protocol :as bp]
            [shiftlefter.browser.chrome :as chrome]
            [shiftlefter.subjects.profile :as profile]))

;; Forward declaration - subjects ns will be loaded later to avoid circular dep
;; Note: reconnect-fn is a record field in PersistentBrowser, not called as a standalone function.
;; The declare exists for documentation purposes only.
(def ^{:declared true} reconnect-fn
  "Reconnection function stored in PersistentBrowser records.
   Signature: (fn [subject-name stealth] -> browser-or-nil).
   Called automatically when session errors occur to establish a new WebDriver session.
   Provided at construction time via make-persistent-browser."
  nil)

;; -----------------------------------------------------------------------------
;; Session Error Detection
;; -----------------------------------------------------------------------------

(defn- session-error?
  "Check if an exception indicates a dead WebDriver session or connection.

   Catches:
   - Session errors: 'invalid session id', 'session not found', 'no such window'
   - Connection errors: HTTP failures when ChromeDriver is dead
   - Etaoin error types: :etaoin/http-ex, :etaoin/http-error"
  [ex]
  (let [msg (str (ex-message ex))
        data (ex-data ex)
        error-type (:type data)]
    (or
     ;; Session/window errors
     (.contains msg "invalid session id")
     (.contains msg "session not found")
     (.contains msg "session deleted")
     (.contains msg "no such session")
     (.contains msg "no such window")
     (.contains msg "target window already closed")
     ;; Connection-level errors (ChromeDriver dead/unreachable)
     (.contains msg "Connection refused")
     (.contains msg "connection refused")
     ;; Etaoin error types in message (stringified ex-data)
     (.contains msg ":etaoin/http-ex")
     (.contains msg ":etaoin/http-error")
     ;; Check ex-data error type directly
     (= :etaoin/http-ex error-type)
     (= :etaoin/http-error error-type))))

(defn- ensure-window-for-subject!
  "Ensure Chrome has a window for the given subject.
   Looks up the debug port from profile metadata and calls ensure-window!."
  [subject-name]
  (when-let [meta (profile/load-browser-meta subject-name)]
    (when-let [port (:debug-port meta)]
      (chrome/ensure-window! port))))

;; -----------------------------------------------------------------------------
;; Reconnect Macros
;; -----------------------------------------------------------------------------

(defmacro ^:private with-reconnect-mutating
  "Try body, reconnect on session error, retry once. Returns `this`.
   For mutating IBrowser ops (! suffix)."
  [browser-atom subject-name stealth reconnect-fn this & body]
  `(try
     ~@body
     ~this
     (catch Exception e#
       (if (session-error? e#)
         (let [new-browser# (~reconnect-fn ~subject-name ~stealth)]
           (if new-browser#
             (do
               (reset! ~browser-atom new-browser#)
               ~@body
               ~this)
             (throw e#)))
         (throw e#)))))

(defmacro ^:private with-reconnect-query
  "Try body, reconnect on session error, retry once. Returns result of body.
   For query IBrowser ops."
  [browser-atom subject-name stealth reconnect-fn & body]
  `(try
     ~@body
     (catch Exception e#
       (if (session-error? e#)
         (let [new-browser# (~reconnect-fn ~subject-name ~stealth)]
           (if new-browser#
             (do
               (reset! ~browser-atom new-browser#)
               ~@body)
             (throw e#)))
         (throw e#)))))

;; -----------------------------------------------------------------------------
;; PersistentBrowser
;; -----------------------------------------------------------------------------

(defrecord PersistentBrowser [browser-atom subject-name stealth reconnect-fn]
  bp/IBrowser

  (open-to! [this url]
    ;; Special: ensure window exists before navigating (handles window-closed case)
    (ensure-window-for-subject! subject-name)
    (with-reconnect-mutating browser-atom subject-name stealth reconnect-fn this
      (bp/open-to! @browser-atom url)))

  (click! [this locator]
    (with-reconnect-mutating browser-atom subject-name stealth reconnect-fn this
      (bp/click! @browser-atom locator)))

  (doubleclick! [this locator]
    (with-reconnect-mutating browser-atom subject-name stealth reconnect-fn this
      (bp/doubleclick! @browser-atom locator)))

  (rightclick! [this locator]
    (with-reconnect-mutating browser-atom subject-name stealth reconnect-fn this
      (bp/rightclick! @browser-atom locator)))

  (move-to! [this locator]
    (with-reconnect-mutating browser-atom subject-name stealth reconnect-fn this
      (bp/move-to! @browser-atom locator)))

  (drag-to! [this from-locator to-locator]
    (with-reconnect-mutating browser-atom subject-name stealth reconnect-fn this
      (bp/drag-to! @browser-atom from-locator to-locator)))

  (fill! [this locator text]
    (with-reconnect-mutating browser-atom subject-name stealth reconnect-fn this
      (bp/fill! @browser-atom locator text)))

  (element-count [_this locator]
    (with-reconnect-query browser-atom subject-name stealth reconnect-fn
      (bp/element-count @browser-atom locator)))

  (get-text [_this locator]
    (with-reconnect-query browser-atom subject-name stealth reconnect-fn
      (bp/get-text @browser-atom locator)))

  (get-url [_this]
    (with-reconnect-query browser-atom subject-name stealth reconnect-fn
      (bp/get-url @browser-atom)))

  (get-title [_this]
    (with-reconnect-query browser-atom subject-name stealth reconnect-fn
      (bp/get-title @browser-atom)))

  (visible? [_this locator]
    (with-reconnect-query browser-atom subject-name stealth reconnect-fn
      (bp/visible? @browser-atom locator)))

  ;; --- Navigation (0.3.6) ---

  (go-back! [this]
    (with-reconnect-mutating browser-atom subject-name stealth reconnect-fn this
      (bp/go-back! @browser-atom)))

  (go-forward! [this]
    (with-reconnect-mutating browser-atom subject-name stealth reconnect-fn this
      (bp/go-forward! @browser-atom)))

  (refresh! [this]
    (with-reconnect-mutating browser-atom subject-name stealth reconnect-fn this
      (bp/refresh! @browser-atom)))

  ;; --- Scrolling (0.3.6) ---

  (scroll-to! [this locator]
    (with-reconnect-mutating browser-atom subject-name stealth reconnect-fn this
      (bp/scroll-to! @browser-atom locator)))

  (scroll-to-position! [this position]
    (with-reconnect-mutating browser-atom subject-name stealth reconnect-fn this
      (bp/scroll-to-position! @browser-atom position)))

  ;; --- Form Operations (0.3.6) ---

  (clear! [this locator]
    (with-reconnect-mutating browser-atom subject-name stealth reconnect-fn this
      (bp/clear! @browser-atom locator)))

  (select! [this locator text]
    (with-reconnect-mutating browser-atom subject-name stealth reconnect-fn this
      (bp/select! @browser-atom locator text)))

  (press-key! [this key-str]
    (with-reconnect-mutating browser-atom subject-name stealth reconnect-fn this
      (bp/press-key! @browser-atom key-str)))

  ;; --- Element Queries (0.3.6) ---

  (get-attribute [_this locator attribute]
    (with-reconnect-query browser-atom subject-name stealth reconnect-fn
      (bp/get-attribute @browser-atom locator attribute)))

  (get-value [_this locator]
    (with-reconnect-query browser-atom subject-name stealth reconnect-fn
      (bp/get-value @browser-atom locator)))

  (enabled? [_this locator]
    (with-reconnect-query browser-atom subject-name stealth reconnect-fn
      (bp/enabled? @browser-atom locator)))

  ;; --- Alerts (0.3.6) ---

  (accept-alert! [this]
    (with-reconnect-mutating browser-atom subject-name stealth reconnect-fn this
      (bp/accept-alert! @browser-atom)))

  (dismiss-alert! [this]
    (with-reconnect-mutating browser-atom subject-name stealth reconnect-fn this
      (bp/dismiss-alert! @browser-atom)))

  (get-alert-text [_this]
    (with-reconnect-query browser-atom subject-name stealth reconnect-fn
      (bp/get-alert-text @browser-atom)))

  ;; --- Window Management (0.3.6) ---

  (maximize-window! [this]
    (with-reconnect-mutating browser-atom subject-name stealth reconnect-fn this
      (bp/maximize-window! @browser-atom)))

  (set-window-size! [this width height]
    (with-reconnect-mutating browser-atom subject-name stealth reconnect-fn this
      (bp/set-window-size! @browser-atom width height)))

  (switch-to-next-window! [this]
    (with-reconnect-mutating browser-atom subject-name stealth reconnect-fn this
      (bp/switch-to-next-window! @browser-atom)))

  ;; --- Frames (0.3.6) ---

  (switch-to-frame! [this locator]
    (with-reconnect-mutating browser-atom subject-name stealth reconnect-fn this
      (bp/switch-to-frame! @browser-atom locator)))

  (switch-to-main-frame! [this]
    (with-reconnect-mutating browser-atom subject-name stealth reconnect-fn this
      (bp/switch-to-main-frame! @browser-atom))))

;; -----------------------------------------------------------------------------
;; Factory
;; -----------------------------------------------------------------------------

(defn make-persistent-browser
  "Create a PersistentBrowser that wraps an EtaoinBrowser.

   Arguments:
   - `browser` — the underlying EtaoinBrowser
   - `subject-name` — keyword/string name of the subject (for reconnect)
   - `stealth` — boolean, whether stealth mode is enabled
   - `reconnect-fn` — function `(fn [subject-name stealth] -> browser or nil)`
                      called on session errors to establish new session

   The reconnect-fn should return a new EtaoinBrowser on success, or nil on failure."
  [browser subject-name stealth reconnect-fn]
  (->PersistentBrowser (atom browser) subject-name stealth reconnect-fn))

(defn underlying-browser
  "Get the current underlying browser from a PersistentBrowser."
  [persistent-browser]
  @(:browser-atom persistent-browser))
