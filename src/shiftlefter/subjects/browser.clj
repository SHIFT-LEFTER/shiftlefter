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
;; PersistentBrowser
;; -----------------------------------------------------------------------------

(defrecord PersistentBrowser [browser-atom subject-name stealth reconnect-fn]
  bp/IBrowser

  (open-to! [this url]
    ;; Ensure window exists before navigating (handles window-closed case)
    (ensure-window-for-subject! subject-name)
    (try
      (bp/open-to! @browser-atom url)
      this
      (catch Exception e
        (if (session-error? e)
          (let [new-browser (reconnect-fn subject-name stealth)]
            (if new-browser
              (do
                (reset! browser-atom new-browser)
                (bp/open-to! @browser-atom url)
                this)
              (throw e)))
          (throw e)))))

  (click! [this locator]
    (try
      (bp/click! @browser-atom locator)
      this
      (catch Exception e
        (if (session-error? e)
          (let [new-browser (reconnect-fn subject-name stealth)]
            (if new-browser
              (do
                (reset! browser-atom new-browser)
                (bp/click! @browser-atom locator)
                this)
              (throw e)))
          (throw e)))))

  (doubleclick! [this locator]
    (try
      (bp/doubleclick! @browser-atom locator)
      this
      (catch Exception e
        (if (session-error? e)
          (let [new-browser (reconnect-fn subject-name stealth)]
            (if new-browser
              (do
                (reset! browser-atom new-browser)
                (bp/doubleclick! @browser-atom locator)
                this)
              (throw e)))
          (throw e)))))

  (rightclick! [this locator]
    (try
      (bp/rightclick! @browser-atom locator)
      this
      (catch Exception e
        (if (session-error? e)
          (let [new-browser (reconnect-fn subject-name stealth)]
            (if new-browser
              (do
                (reset! browser-atom new-browser)
                (bp/rightclick! @browser-atom locator)
                this)
              (throw e)))
          (throw e)))))

  (move-to! [this locator]
    (try
      (bp/move-to! @browser-atom locator)
      this
      (catch Exception e
        (if (session-error? e)
          (let [new-browser (reconnect-fn subject-name stealth)]
            (if new-browser
              (do
                (reset! browser-atom new-browser)
                (bp/move-to! @browser-atom locator)
                this)
              (throw e)))
          (throw e)))))

  (drag-to! [this from-locator to-locator]
    (try
      (bp/drag-to! @browser-atom from-locator to-locator)
      this
      (catch Exception e
        (if (session-error? e)
          (let [new-browser (reconnect-fn subject-name stealth)]
            (if new-browser
              (do
                (reset! browser-atom new-browser)
                (bp/drag-to! @browser-atom from-locator to-locator)
                this)
              (throw e)))
          (throw e)))))

  (fill! [this locator text]
    (try
      (bp/fill! @browser-atom locator text)
      this
      (catch Exception e
        (if (session-error? e)
          (let [new-browser (reconnect-fn subject-name stealth)]
            (if new-browser
              (do
                (reset! browser-atom new-browser)
                (bp/fill! @browser-atom locator text)
                this)
              (throw e)))
          (throw e)))))

  (element-count [_this locator]
    (try
      (bp/element-count @browser-atom locator)
      (catch Exception e
        (if (session-error? e)
          (let [new-browser (reconnect-fn subject-name stealth)]
            (if new-browser
              (do
                (reset! browser-atom new-browser)
                (bp/element-count @browser-atom locator))
              (throw e)))
          (throw e)))))

  (get-text [_this locator]
    (try
      (bp/get-text @browser-atom locator)
      (catch Exception e
        (if (session-error? e)
          (let [new-browser (reconnect-fn subject-name stealth)]
            (if new-browser
              (do
                (reset! browser-atom new-browser)
                (bp/get-text @browser-atom locator))
              (throw e)))
          (throw e)))))

  (get-url [_this]
    (try
      (bp/get-url @browser-atom)
      (catch Exception e
        (if (session-error? e)
          (let [new-browser (reconnect-fn subject-name stealth)]
            (if new-browser
              (do
                (reset! browser-atom new-browser)
                (bp/get-url @browser-atom))
              (throw e)))
          (throw e)))))

  (get-title [_this]
    (try
      (bp/get-title @browser-atom)
      (catch Exception e
        (if (session-error? e)
          (let [new-browser (reconnect-fn subject-name stealth)]
            (if new-browser
              (do
                (reset! browser-atom new-browser)
                (bp/get-title @browser-atom))
              (throw e)))
          (throw e)))))

  (visible? [_this locator]
    (try
      (bp/visible? @browser-atom locator)
      (catch Exception e
        (if (session-error? e)
          (let [new-browser (reconnect-fn subject-name stealth)]
            (if new-browser
              (do
                (reset! browser-atom new-browser)
                (bp/visible? @browser-atom locator))
              (throw e)))
          (throw e))))))

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
