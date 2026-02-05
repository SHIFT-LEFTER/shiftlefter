(ns shiftlefter.webdriver.etaoin.session
  "Etaoin session management helpers.

   Provides attach/detach/close semantics for WebDriver sessions,
   enabling long-lived 'browser surfaces' and REPL workflows.

   ## Pure Functions (no HTTP)
   - `make-driver` — construct driver map from webdriver-url
   - `attach` — set session-id on driver
   - `detach` — remove session-id from driver

   ## Side-Effecting Functions (WebDriver HTTP)
   - `create-session!` — create new session, returns EtaoinBrowser
   - `connect-to-existing!` — connect to already-running Chrome via debuggerAddress
   - `close-session!` — delete session remotely
   - `probe-session!` — check if session is alive
   - `ensure-session!` — use existing if alive, else create new

   ## Error Types
   - `:webdriver/session-dead` — session no longer exists
   - `:webdriver/attach-failed` — attach operation failed
   - `:webdriver/close-failed` — close operation failed
   - `:webdriver/create-failed` — session creation failed
   - `:webdriver/connect-failed` — connection to existing Chrome failed"
  (:require [etaoin.api :as eta]
            [shiftlefter.webdriver.etaoin.browser :as browser]))

;; -----------------------------------------------------------------------------
;; Pure Functions
;; -----------------------------------------------------------------------------

(defn make-driver
  "Construct a driver map from a webdriver URL.

   The driver map is compatible with Etaoin but not yet connected to a session.

   Options:
   - :capabilities — browser capabilities map (optional)
   - :headless — if true, run headless (default: false, i.e., headed)"
  ([webdriver-url]
   (make-driver webdriver-url {}))
  ([webdriver-url opts]
   (let [headless? (get opts :headless false)
         caps (merge (get opts :capabilities {})
                     (when headless?
                       {:chromeOptions {:args ["--headless"]}}))]
     {:webdriver-url webdriver-url
      :capabilities caps
      :type :chrome})))

(defn attach
  "Attach an existing session ID to a driver (pure operation).

   Returns the driver with :session set. Does not verify the session exists."
  [driver session-id]
  (assoc driver :session session-id))

(defn detach
  "Detach session from driver (pure operation).

   Returns the driver with :session removed. Does NOT close the remote session."
  [driver]
  (dissoc driver :session))

(defn attached?
  "Returns true if driver has a session attached."
  [driver]
  (some? (:session driver)))

;; -----------------------------------------------------------------------------
;; Side-Effecting Functions
;; -----------------------------------------------------------------------------

(defn create-session!
  "Create a new WebDriver session.

   Returns {:ok driver' :browser EtaoinBrowser :etaoin-driver raw-driver}
   or {:error {...}}.

   The :browser key contains an EtaoinBrowser implementing IBrowser protocol,
   ready for use with browser stepdefs.

   The driver must have :webdriver-url set (via make-driver)."
  [driver]
  (try
    (let [;; Etaoin expects specific keys for connection
          host-port (when-let [url (:webdriver-url driver)]
                      (let [[_ host port] (re-matches #"https?://([^:]+):(\d+).*" url)]
                        {:host host :port (parse-long port)}))
          ;; Determine headless from capabilities - check if --headless arg present
          headless? (some #(= "--headless" %)
                          (get-in driver [:capabilities :chromeOptions :args] []))
          opts (merge {:type (:type driver :chrome)
                       :headless headless?}
                      host-port)
          ;; Create session via Etaoin
          eta-driver (eta/chrome opts)
          session-id (:session eta-driver)
          ;; Create EtaoinBrowser for protocol operations
          etaoin-browser (browser/make-etaoin-browser eta-driver)]
      {:ok (attach driver session-id)
       :browser etaoin-browser
       :etaoin-driver eta-driver})
    (catch Exception e
      {:error {:type :webdriver/create-failed
               :message (ex-message e)
               :data {:driver driver}}})))

;; -----------------------------------------------------------------------------
;; Connect to Existing Chrome
;; -----------------------------------------------------------------------------

(defn build-debugger-capabilities
  "Build Chrome capabilities for connecting via debuggerAddress.

   Options:
   - :port — Chrome debug port (required)

   Note: Stealth options (excludeSwitches, useAutomationExtension) are NOT
   included here — they're Chrome launch options, not WebDriver connect options.
   Stealth is applied when Chrome is launched (see browser.chrome/build-chrome-args).

   Returns capabilities map suitable for Etaoin."
  [{:keys [port]}]
  (let [debugger-address (str "127.0.0.1:" port)]
    {:goog:chromeOptions {:debuggerAddress debugger-address}}))

(defn connect-to-existing!
  "Connect to an already-running Chrome via debuggerAddress.

   This creates a WebDriver session that attaches to Chrome running with
   `--remote-debugging-port=<port>`. Does NOT launch Chrome — assumes
   it's already running (e.g., via `chrome/launch!`).

   Options:
   - :port — Chrome debug port (required)
   - :stealth — if true, add anti-automation detection flags (default: false)

   Returns:
   - Success: {:ok driver :browser EtaoinBrowser :etaoin-driver raw-driver}
   - Error: {:error {:type :webdriver/connect-failed ...}}

   Examples:
   ```clojure
   ;; Chrome already running on port 9222
   (connect-to-existing! {:port 9222 :stealth true})
   ;; => {:ok {...} :browser #EtaoinBrowser{...} :etaoin-driver {...}}
   ```"
  [{:keys [port stealth] :as opts}]
  (try
    (let [capabilities (build-debugger-capabilities opts)
          ;; Etaoin chrome with capabilities - it will connect via debuggerAddress
          eta-driver (eta/chrome {:capabilities capabilities})
          session-id (:session eta-driver)
          ;; Build our driver map
          driver {:type :chrome
                  :session session-id
                  :debug-port port
                  :stealth stealth
                  :connected-via :debugger-address}
          ;; Create EtaoinBrowser for protocol operations
          etaoin-browser (browser/make-etaoin-browser eta-driver)]
      {:ok driver
       :browser etaoin-browser
       :etaoin-driver eta-driver})
    (catch Exception e
      {:error {:type :webdriver/connect-failed
               :message (str "Failed to connect to Chrome on port " port ": " (ex-message e))
               :data {:port port :stealth stealth}}})))

(defn close-session!
  "Close/delete the remote WebDriver session.

   Returns {:ok driver'} with session detached, or {:error {...}}."
  [driver]
  (if-not (attached? driver)
    {:ok driver}
    (try
      ;; Build minimal Etaoin driver structure for quit
      (let [url (:webdriver-url driver)
            [_ host port] (re-matches #"https?://([^:]+):(\d+).*" url)
            eta-driver {:host host
                        :port (parse-long port)
                        :session (:session driver)
                        :type (:type driver :chrome)}]
        (eta/quit eta-driver)
        {:ok (detach driver)})
      (catch Exception e
        {:error {:type :webdriver/close-failed
                 :message (ex-message e)
                 :data {:driver driver
                        :session (:session driver)}}}))))

(defn probe-session!
  "Check if the session is still alive.

   Returns {:ok :alive} if session responds, or {:error {:type :webdriver/session-dead ...}}."
  [driver]
  (if-not (attached? driver)
    {:error {:type :webdriver/session-dead
             :message "No session attached"
             :data {:driver driver}}}
    (try
      (let [url (:webdriver-url driver)
            [_ host port] (re-matches #"https?://([^:]+):(\d+).*" url)
            eta-driver {:host host
                        :port (parse-long port)
                        :session (:session driver)
                        :type (:type driver :chrome)}]
        ;; Try to get current URL — simple operation to verify session
        (eta/get-url eta-driver)
        {:ok :alive})
      (catch Exception e
        {:error {:type :webdriver/session-dead
                 :message (str "Session probe failed: " (ex-message e))
                 :data {:driver driver
                        :session (:session driver)}}}))))

(defn ensure-session!
  "Ensure driver has a valid session.

   If driver has a session attached and it's alive, returns {:ok driver}.
   If session is dead or not attached, creates a new session.

   Returns {:ok driver'} or {:error {...}}."
  [driver]
  (if (attached? driver)
    ;; Check if existing session is alive
    (let [probe-result (probe-session! driver)]
      (if (:ok probe-result)
        {:ok driver}
        ;; Session dead, create new one
        (create-session! (detach driver))))
    ;; No session, create one
    (create-session! driver)))
