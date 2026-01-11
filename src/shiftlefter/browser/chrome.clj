(ns shiftlefter.browser.chrome
  "Chrome binary discovery and lifecycle helpers.

   This namespace provides Chrome-specific operations for the persistent
   browser mode introduced in STARLINKHORSE (0.2.6).

   ## Chrome Binary Discovery

   `find-binary` locates the Chrome executable with the following precedence:
   1. Config override: `{:chrome-path \"/custom/path\"}`
   2. OS-specific defaults:
      - macOS: `/Applications/Google Chrome.app/Contents/MacOS/Google Chrome`
      - Linux: search PATH for `google-chrome`, `google-chrome-stable`, `chromium`

   ## CDP Endpoint Probe

   `probe-cdp` checks if Chrome is alive by hitting its CDP endpoint:
   `http://127.0.0.1:<port>/json/version`

   ## Chrome Launcher

   `launch!` spawns Chrome with debug port and user-data-dir, waits for CDP ready.

   ## Process Management

   `kill-by-pid!` terminates a Chrome process by PID.

   ## Error Types

   - `:persistent/chrome-not-found` — Chrome binary not found at expected path
   - `:persistent/os-not-supported` — OS not yet supported (Windows)
   - `:persistent/chrome-launch-failed` — Chrome process failed to start
   - `:persistent/cdp-not-ready` — CDP endpoint didn't come up in time
   - `:persistent/no-port-available` — No available port found in search range"
  (:require [babashka.fs :as fs]
            [cheshire.core :as json])
  (:import [java.net HttpURLConnection URL]
           [java.io IOException]))

;; -----------------------------------------------------------------------------
;; OS Detection
;; -----------------------------------------------------------------------------

(defn- get-os
  "Detect the current operating system.
   Returns :macos, :linux, :windows, or :unknown."
  []
  (let [os-name (System/getProperty "os.name")
        os-lower (some-> os-name .toLowerCase)]
    (cond
      (nil? os-lower)                       :unknown
      (.contains os-lower "mac")            :macos
      (.contains os-lower "linux")          :linux
      (.contains os-lower "windows")        :windows
      :else                                 :unknown)))

;; -----------------------------------------------------------------------------
;; OS-Specific Paths
;; -----------------------------------------------------------------------------

(def ^:private macos-chrome-path
  "Default Chrome path on macOS."
  "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome")

(def ^:private linux-chrome-candidates
  "Chrome executable names to search for on Linux, in preference order."
  ["google-chrome" "google-chrome-stable" "chromium-browser" "chromium"])

(defn- find-on-path
  "Search PATH for an executable by name.
   Returns the full path if found, nil otherwise."
  [executable-name]
  (when-let [result (fs/which executable-name)]
    (str result)))

(defn- find-linux-chrome
  "Find Chrome on Linux by searching PATH for known executable names.
   Returns path string or nil."
  []
  (some find-on-path linux-chrome-candidates))

;; -----------------------------------------------------------------------------
;; Errors
;; -----------------------------------------------------------------------------

(defn- chrome-not-found-error
  "Build a :persistent/chrome-not-found error."
  [searched-paths]
  {:errors [{:type :persistent/chrome-not-found
             :message "Chrome binary not found"
             :data {:searched-paths searched-paths}}]})

(defn- os-not-supported-error
  "Build a :persistent/os-not-supported error."
  [os-name]
  {:errors [{:type :persistent/os-not-supported
             :message (str "Operating system not yet supported: " os-name)
             :data {:os os-name
                    :supported-os [:macos :linux]}}]})

;; -----------------------------------------------------------------------------
;; Public API
;; -----------------------------------------------------------------------------

(defn find-binary
  "Find the Chrome executable path.

   Checks for Chrome in the following order:
   1. Config override (if `:chrome-path` provided in opts)
   2. OS-specific default locations

   Options:
   - `:chrome-path` — explicit path to Chrome binary (skips OS detection)

   Returns:
   - Success: path string (e.g., \"/Applications/Google Chrome.app/.../Google Chrome\")
   - Error: `{:errors [{:type :persistent/chrome-not-found ...}]}`
           or `{:errors [{:type :persistent/os-not-supported ...}]}`

   Examples:
   ```clojure
   ;; Use OS defaults
   (find-binary)
   ;; => \"/Applications/Google Chrome.app/Contents/MacOS/Google Chrome\"

   ;; With config override
   (find-binary {:chrome-path \"/custom/path/chrome\"})
   ;; => \"/custom/path/chrome\"

   ;; When not found
   (find-binary {:chrome-path \"/nonexistent\"})
   ;; => {:errors [{:type :persistent/chrome-not-found ...}]}
   ```"
  ([]
   (find-binary {}))
  ([opts]
   (if-let [override-path (:chrome-path opts)]
     ;; Config override — verify it exists
     (if (fs/exists? override-path)
       override-path
       (chrome-not-found-error [override-path]))
     ;; OS-specific detection
     (let [os (get-os)]
       (case os
         :macos
         (if (fs/exists? macos-chrome-path)
           macos-chrome-path
           (chrome-not-found-error [macos-chrome-path]))

         :linux
         (if-let [path (find-linux-chrome)]
           path
           (chrome-not-found-error linux-chrome-candidates))

         :windows
         (os-not-supported-error "Windows")

         ;; :unknown or other
         (os-not-supported-error (System/getProperty "os.name")))))))

(defn binary-exists?
  "Check if a Chrome binary path exists and is executable.
   Returns true/false."
  [path]
  (and (some? path)
       (fs/exists? path)
       (fs/executable? path)))

;; -----------------------------------------------------------------------------
;; CDP Endpoint Probe
;; -----------------------------------------------------------------------------

(def ^:private default-probe-timeout-ms
  "Default timeout for CDP probe requests."
  1000)

(defn- cdp-version-url
  "Build the CDP version endpoint URL for a given port."
  [port]
  (str "http://127.0.0.1:" port "/json/version"))

(defn probe-cdp
  "Check if Chrome is alive by probing its CDP endpoint.

   Makes an HTTP GET request to `http://127.0.0.1:<port>/json/version`.

   Options:
   - `:timeout-ms` — connection/read timeout in milliseconds (default: 1000)

   Returns:
   - Chrome alive: `{:status :alive :version {...}}`
     where version contains Chrome's CDP response (Browser, Protocol-Version, etc.)
   - Chrome dead/not responding: `{:status :dead}`

   Examples:
   ```clojure
   ;; Chrome running with debug port 9222
   (probe-cdp 9222)
   ;; => {:status :alive
   ;;     :version {:Browser \"Chrome/120.0.6099.129\"
   ;;               :Protocol-Version \"1.3\"
   ;;               :User-Agent \"...\"
   ;;               :V8-Version \"...\"
   ;;               :WebKit-Version \"...\"
   ;;               :webSocketDebuggerUrl \"ws://...\"}}

   ;; Nothing on port 9222
   (probe-cdp 9222)
   ;; => {:status :dead}

   ;; With custom timeout
   (probe-cdp 9222 {:timeout-ms 500})
   ```"
  ([port]
   (probe-cdp port {}))
  ([port opts]
   (let [timeout-ms (get opts :timeout-ms default-probe-timeout-ms)
         url-str (cdp-version-url port)]
     (try
       (let [url (URL. url-str)
             conn ^HttpURLConnection (.openConnection url)]
         (try
           (.setRequestMethod conn "GET")
           (.setConnectTimeout conn timeout-ms)
           (.setReadTimeout conn timeout-ms)
           (.setInstanceFollowRedirects conn false)

           (let [response-code (.getResponseCode conn)]
             (if (= 200 response-code)
               (let [body (slurp (.getInputStream conn))
                     version (json/parse-string body true)]
                 {:status :alive
                  :version version})
               {:status :dead}))
           (finally
             (.disconnect conn))))
       (catch IOException _e
         ;; Connection refused, timeout, etc. — Chrome not responding
         {:status :dead})
       (catch Exception _e
         ;; Any other error — treat as dead
         {:status :dead})))))

;; -----------------------------------------------------------------------------
;; Chrome Launcher
;; -----------------------------------------------------------------------------

(def ^:private default-cdp-ready-timeout-ms
  "Default timeout waiting for CDP endpoint to be ready after launch."
  10000)

(def ^:private cdp-poll-interval-ms
  "Interval between CDP probe attempts during startup."
  100)

(defn- chrome-launch-failed-error
  "Build a :persistent/chrome-launch-failed error."
  [message data]
  {:errors [{:type :persistent/chrome-launch-failed
             :message message
             :data data}]})

(defn- cdp-not-ready-error
  "Build a :persistent/cdp-not-ready error."
  [port timeout-ms]
  {:errors [{:type :persistent/cdp-not-ready
             :message (str "CDP endpoint not ready after " timeout-ms "ms")
             :data {:port port :timeout-ms timeout-ms}}]})

(defn build-chrome-args
  "Build Chrome command line arguments.

   Required:
   - :port — debug port
   - :user-data-dir — profile directory path

   Optional:
   - :stealth — if true, add anti-detection flags

   Returns a vector of argument strings."
  [{:keys [port user-data-dir stealth]}]
  (cond-> [(str "--remote-debugging-port=" port)
           (str "--user-data-dir=" user-data-dir)
           "--no-first-run"
           "--no-default-browser-check"]
    stealth (conj "--disable-blink-features=AutomationControlled")))

(defn- get-pid
  "Get the PID of a Process. Works on Java 9+."
  [^Process process]
  (.pid process))

(defn- wait-for-cdp-ready
  "Poll CDP endpoint until it responds or timeout.

   Returns {:status :ready} or {:status :timeout}."
  [port timeout-ms]
  (let [start-time (System/currentTimeMillis)
        deadline (+ start-time timeout-ms)]
    (loop []
      (let [result (probe-cdp port {:timeout-ms 200})]
        (cond
          (= :alive (:status result))
          {:status :ready :version (:version result)}

          (> (System/currentTimeMillis) deadline)
          {:status :timeout}

          :else
          (do
            (Thread/sleep cdp-poll-interval-ms)
            (recur)))))))

(defn launch!
  "Launch Chrome with debug port and user-data-dir.

   Spawns a Chrome process with the specified configuration and waits for
   the CDP endpoint to become ready.

   Required options:
   - `:port` — debug port (e.g., 9222)
   - `:user-data-dir` — path to Chrome profile directory

   Optional:
   - `:chrome-path` — explicit path to Chrome binary (default: auto-detect)
   - `:stealth` — if true, add anti-detection flags (default: false)
   - `:cdp-timeout-ms` — timeout waiting for CDP ready (default: 10000)

   Returns:
   - Success: `{:pid <long> :port <int> :process <Process>}`
   - Error: `{:errors [{:type :persistent/... ...}]}`

   Examples:
   ```clojure
   (launch! {:port 9222
             :user-data-dir \"/tmp/test-profile\"
             :stealth true})
   ;; => {:pid 12345 :port 9222 :process #object[Process ...]}

   ;; CDP endpoint is now reachable
   (probe-cdp 9222)
   ;; => {:status :alive ...}
   ```"
  [{:keys [port user-data-dir chrome-path stealth cdp-timeout-ms]
    :or {cdp-timeout-ms default-cdp-ready-timeout-ms}}]
  (let [;; Find Chrome binary
        binary (if chrome-path
                 (if (fs/exists? chrome-path)
                   chrome-path
                   (chrome-not-found-error [chrome-path]))
                 (find-binary))]
    (if (:errors binary)
      ;; Binary not found
      binary
      ;; Build args and launch
      (let [args (build-chrome-args {:port port
                                     :user-data-dir user-data-dir
                                     :stealth stealth})
            cmd (into [binary] args)]
        (try
          (let [builder (ProcessBuilder. ^java.util.List cmd)
                _ (.redirectErrorStream builder true)
                process (.start builder)
                pid (get-pid process)
                ready-result (wait-for-cdp-ready port cdp-timeout-ms)]
            (if (= :ready (:status ready-result))
              {:pid pid
               :port port
               :process process}
              ;; CDP didn't come up — kill the process and return error
              (do
                (.destroyForcibly process)
                (cdp-not-ready-error port cdp-timeout-ms))))
          (catch Exception e
            (chrome-launch-failed-error
             (str "Failed to start Chrome: " (ex-message e))
             {:cmd cmd})))))))

;; -----------------------------------------------------------------------------
;; Process Management
;; -----------------------------------------------------------------------------

(defn kill-by-pid!
  "Kill a Chrome process by PID.

   Uses `kill -TERM` for graceful shutdown, falls back to `kill -KILL` if needed.

   Returns:
   - Success: `{:killed true :pid <pid>}`
   - Not running: `{:killed false :pid <pid> :reason :not-running}`
   - Error: `{:killed false :pid <pid> :reason :error :message \"...\"}`

   Examples:
   ```clojure
   (kill-by-pid! 12345)
   ;; => {:killed true :pid 12345}
   ```"
  [pid]
  (try
    (let [process-handle (java.lang.ProcessHandle/of pid)]
      (if (.isPresent process-handle)
        (let [handle (.get process-handle)
              destroyed (.destroy handle)]
          (if destroyed
            {:killed true :pid pid}
            ;; Try forcible kill
            (if (.destroyForcibly handle)
              {:killed true :pid pid}
              {:killed false :pid pid :reason :failed})))
        {:killed false :pid pid :reason :not-running}))
    (catch Exception e
      {:killed false :pid pid :reason :error :message (ex-message e)})))

;; -----------------------------------------------------------------------------
;; Port Allocation
;; -----------------------------------------------------------------------------

(def ^:private default-start-port
  "Default starting port for CDP debug port allocation."
  9222)

(def ^:private max-port-search-range
  "Maximum number of ports to try before giving up."
  100)

(defn- no-port-available-error
  "Build a :persistent/no-port-available error."
  [start-port end-port]
  {:errors [{:type :persistent/no-port-available
             :message (str "No available port found in range " start-port "-" end-port)
             :data {:start-port start-port
                    :end-port end-port}}]})

(defn allocate-port
  "Find an available debug port for Chrome.

   Probes ports starting from `:start-port` (default 9222) until finding
   one that doesn't respond to CDP probe (i.e., not in use by Chrome).

   Options:
   - `:start-port` — first port to try (default: 9222)
   - `:max-range` — maximum ports to try (default: 100)

   Returns:
   - Success: port number (integer)
   - Error: `{:errors [{:type :persistent/no-port-available ...}]}`

   Examples:
   ```clojure
   ;; Nothing running on 9222
   (allocate-port)
   ;; => 9222

   ;; 9222 in use by another Chrome
   (allocate-port)
   ;; => 9223

   ;; Custom start port
   (allocate-port {:start-port 9300})
   ;; => 9300
   ```"
  ([]
   (allocate-port {}))
  ([{:keys [start-port max-range]
     :or {start-port default-start-port
          max-range max-port-search-range}}]
   (let [end-port (+ start-port max-range)]
     (loop [port start-port]
       (if (>= port end-port)
         (no-port-available-error start-port (dec end-port))
         (let [result (probe-cdp port {:timeout-ms 100})]
           (if (= :dead (:status result))
             port
             (recur (inc port)))))))))
