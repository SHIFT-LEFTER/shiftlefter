(ns shiftlefter.costume
  "Costume lifecycle management.

   A costume is a named, durable, authenticated browser context that survives
   JVM restarts. Each costume has its own Chrome profile and connection metadata
   stored in the project-scoped wardrobe at `.shiftlefter/wardrobe/<name>/`.

   ## Lifecycle Functions

   - `init-costume!` — Create new costume: allocate port, launch Chrome, connect
   - `connect-costume!` — Reconnect to existing costume (after JVM restart)
   - `destroy-costume!` — Kill Chrome and delete costume directory
   - `list-costumes` — List all costumes with their status

   ## Costume State

   Costumes transition through states:
   - `:initializing` — Being created
   - `:connected` — Chrome running, WebDriver session active
   - `:disconnected` — Chrome running, but no active session (needs reconnect)
   - `:dead` — Chrome not running (needs relaunch or destroy)

   ## Error Types

   - `:costume/already-exists` — init called for existing costume
   - `:costume/not-found` — connect/destroy called for nonexistent costume
   - `:costume/init-failed` — initialization failed (port/launch/connect)
   - `:costume/connect-failed` — connection to existing Chrome failed
   - `:costume/destroy-failed` — cleanup failed
   - `:costume/git-tracked` — costume dir is git-tracked (refuses to operate)"
  (:require [shiftlefter.browser.chrome :as chrome]
            [shiftlefter.config.user :as user-config]
            [shiftlefter.costume.wardrobe :as wardrobe]
            [shiftlefter.costume.browser :as cbrowser]
            [shiftlefter.webdriver.etaoin.session :as session]))

;; -----------------------------------------------------------------------------
;; Error Helpers
;; -----------------------------------------------------------------------------

(defn- already-exists-error
  [costume-name]
  {:error {:type :costume/already-exists
           :message (str "Costume already exists: " (name costume-name))
           :data {:costume costume-name}}})

(defn- not-found-error
  [costume-name]
  ;; Surface the resolved wardrobe path that was searched — a misplaced costume
  ;; (e.g. created under a different SL_USER_CWD) is then immediately visible
  ;; instead of failing mysteriously.
  (let [searched (wardrobe/costume-dir costume-name)]
    {:error {:type :costume/not-found
             :message (str "Costume not found: " (name costume-name)
                           " (searched: " searched ")")
             :data {:costume costume-name :searched-path searched}}}))

(defn- init-failed-error
  [costume-name reason data]
  {:error {:type :costume/init-failed
           :message (str "Failed to initialize costume " (name costume-name) ": " reason)
           :data (merge {:costume costume-name} data)}})

(defn- connect-failed-error
  [costume-name reason data]
  {:error {:type :costume/connect-failed
           :message (str "Failed to connect to costume " (name costume-name) ": " reason)
           :data (merge {:costume costume-name} data)}})

(defn- git-tracked-error
  [costume-name path]
  {:error {:type :costume/git-tracked
           :message (str "refusing to use a git-tracked costume dir `" path
                         "` — costumes hold live credentials; add "
                         "`.shiftlefter/wardrobe/` to .gitignore")
           :data {:costume costume-name :path path}}})

;; -----------------------------------------------------------------------------
;; Reconnect Helper (used by CostumeBrowser)
;; -----------------------------------------------------------------------------

(defn- do-reconnect
  "Internal reconnect function for CostumeBrowser.

   Attempts to reconnect to a costume's Chrome instance.
   Returns the raw EtaoinBrowser on success, nil on failure."
  [costume-name]
  (when (wardrobe/costume-exists? costume-name)
    (let [meta (wardrobe/load-costume-meta costume-name)]
      (when meta
        (let [{:keys [debug-port user-data-dir]} meta
              ;; Resolve configured chromedriver (shared with fresh-spawn path)
              path-driver (user-config/resolve-chromedriver-path {})
              probe-result (chrome/probe-cdp debug-port)]
          (if (= :alive (:status probe-result))
            ;; Chrome alive — ensure window exists, then reconnect
            (do
              (chrome/ensure-window! debug-port)
              (let [connect-result (session/connect-to-existing!
                                    {:port debug-port :path-driver path-driver})]
                (when-not (:error connect-result)
                  (wardrobe/save-costume-meta! costume-name meta)
                  (:browser connect-result))))
            ;; Chrome dead — relaunch
            (let [launch-result (chrome/launch!
                                 {:port debug-port
                                  :user-data-dir user-data-dir})]
              (when-not (:errors launch-result)
                (let [{:keys [pid port]} launch-result
                      ;; Ensure window exists after launch
                      _ (chrome/ensure-window! port)
                      connect-result (session/connect-to-existing!
                                      {:port port :path-driver path-driver})]
                  (if (:error connect-result)
                    (do
                      (chrome/kill-by-pid! pid)
                      nil)
                    (do
                      (wardrobe/save-costume-meta! costume-name
                                                   (assoc meta
                                                          :debug-port port
                                                          :chrome-pid pid))
                      (:browser connect-result))))))))))))

;; -----------------------------------------------------------------------------
;; Lifecycle: Init
;; -----------------------------------------------------------------------------

(defn init-costume!
  "Initialize a new costume (launch-only).

   Creates the costume directory, allocates a debug port, launches a PRISTINE
   Chrome for the human to log into, and saves metadata. It does NOT attach a
   WebDriver session — the contract is: you log into a clean browser; the tool
   attaches afterward, on the first `connect-costume!`. Refuses if the costume
   dir is git-tracked, and ensures `.shiftlefter/wardrobe/` is gitignored.

   Options:
   - `:chrome-path` — explicit Chrome binary path (default: auto-detect)

   Returns:
   - Success: `{:status :launched
                :costume <name>
                :port <int>
                :pid <long>}`
   - Error: `{:error {:type :costume/... ...}}`

   Examples:
   ```clojure
   (init-costume! :finance)
   ;; => {:status :launched :costume :finance :port 9222 :pid 12345}
   ;; Log in, then call (connect-costume! :finance) to attach.
   ```"
  ([costume-name]
   (init-costume! costume-name {}))
  ([costume-name opts]
   ;; Merge with user config (user config is lower priority)
   (let [opts (user-config/merge-with-user-config opts)
         dir (wardrobe/costume-dir costume-name)]
     (cond
       ;; Refuse to operate on a git-tracked costume dir (holds live credentials)
       (wardrobe/git-tracked? dir)
       (git-tracked-error costume-name dir)

       ;; Check if costume already exists
       (wardrobe/costume-exists? costume-name)
       (already-exists-error costume-name)

       :else
       (do
         ;; Convenience: ensure the wardrobe is gitignored in this project
         (wardrobe/ensure-gitignored!)
         ;; Allocate port
         (let [port-result (chrome/allocate-port)]
           (if (:errors port-result)
             (init-failed-error costume-name "No port available" port-result)
             ;; Create costume directories
             (let [_costume-dir (wardrobe/ensure-dirs! costume-name)
                   user-data-dir (wardrobe/chrome-profile-dir costume-name)
                   chrome-path (:chrome-path opts)
                   ;; Launch Chrome
                   launch-result (chrome/launch!
                                  (cond-> {:port port-result
                                           :user-data-dir user-data-dir}
                                    chrome-path (assoc :chrome-path chrome-path)))]
               (if (:errors launch-result)
                 ;; Launch failed — clean up costume
                 (do
                   (wardrobe/delete-costume! costume-name)
                   (init-failed-error costume-name "Chrome launch failed" launch-result))
                 ;; Launched — ensure a window exists, save metadata. NO attach:
                 ;; the human logs into this clean browser; the first
                 ;; connect-costume! attaches afterward.
                 (let [{:keys [pid port]} launch-result]
                   (chrome/ensure-window! port)
                   (wardrobe/save-costume-meta! costume-name
                                                {:debug-port port
                                                 :chrome-pid pid
                                                 :user-data-dir user-data-dir})
                   {:status :launched
                    :costume costume-name
                    :port port
                    :pid pid}))))))))))

;; -----------------------------------------------------------------------------
;; Lifecycle: Connect
;; -----------------------------------------------------------------------------

(defn connect-costume!
  "Connect to an existing costume.

   Loads saved metadata, checks if Chrome is alive, and establishes
   a new WebDriver session. If Chrome is dead, relaunches it. Refuses if the
   costume dir is git-tracked.

   Returns:
   - Success: `{:status :connected
                :costume <name>
                :port <int>
                :pid <long>
                :browser <CostumeBrowser>}`
   - Error: `{:error {:type :costume/... ...}}`

   Examples:
   ```clojure
   ;; After JVM restart
   (connect-costume! :finance)
   ;; => {:status :connected :costume :finance :port 9222 ...}
   ```"
  [costume-name]
  (let [dir (wardrobe/costume-dir costume-name)
        ;; Resolve configured chromedriver from config (no opts here); shared resolver
        path-driver (user-config/resolve-chromedriver-path {})]
    (cond
      ;; Refuse to operate on a git-tracked costume dir
      (wardrobe/git-tracked? dir)
      (git-tracked-error costume-name dir)

      ;; Check if costume exists
      (not (wardrobe/costume-exists? costume-name))
      (not-found-error costume-name)

      :else
      ;; Load metadata
      (let [meta (wardrobe/load-costume-meta costume-name)]
        (if-not meta
          (not-found-error costume-name)
          ;; Check if Chrome is alive
          (let [{:keys [debug-port chrome-pid user-data-dir]} meta
                probe-result (chrome/probe-cdp debug-port)]
            (if (= :alive (:status probe-result))
              ;; Chrome alive — ensure window exists, then reconnect
              (do
                (chrome/ensure-window! debug-port)
                (let [connect-result (session/connect-to-existing!
                                      {:port debug-port :path-driver path-driver})]
                  (if (:error connect-result)
                    (connect-failed-error costume-name "WebDriver connection failed" connect-result)
                    ;; Update last-connected timestamp and wrap with CostumeBrowser
                    (let [costume-browser (cbrowser/make-costume-browser
                                           (:browser connect-result) costume-name do-reconnect)]
                      (wardrobe/save-costume-meta! costume-name meta)
                      {:status :connected
                       :costume costume-name
                       :port debug-port
                       :pid chrome-pid
                       :browser costume-browser}))))
              ;; Chrome dead — relaunch
              (let [launch-result (chrome/launch!
                                   {:port debug-port
                                    :user-data-dir user-data-dir})]
                (if (:errors launch-result)
                  (connect-failed-error costume-name "Chrome relaunch failed" launch-result)
                  ;; Ensure window exists, then connect to relaunched Chrome
                  (let [{:keys [pid port]} launch-result
                        _ (chrome/ensure-window! port)
                        connect-result (session/connect-to-existing!
                                        {:port port :path-driver path-driver})]
                    (if (:error connect-result)
                      ;; Connect failed — kill Chrome but keep costume
                      (do
                        (chrome/kill-by-pid! pid)
                        (connect-failed-error costume-name "WebDriver connection failed" connect-result))
                      ;; Update metadata with new PID and wrap with CostumeBrowser
                      (let [costume-browser (cbrowser/make-costume-browser
                                             (:browser connect-result) costume-name do-reconnect)]
                        (wardrobe/save-costume-meta! costume-name
                                                     (assoc meta
                                                            :debug-port port
                                                            :chrome-pid pid))
                        {:status :connected
                         :costume costume-name
                         :port port
                         :pid pid
                         :browser costume-browser}))))))))))))

;; -----------------------------------------------------------------------------
;; Lifecycle: Destroy
;; -----------------------------------------------------------------------------

(defn destroy-costume!
  "Destroy a costume.

   Kills the Chrome process and deletes the entire costume directory
   (including Chrome user data like cookies, history, etc.).

   Returns:
   - Success: `{:status :destroyed :costume <name>}`
   - Not found: `{:error {:type :costume/not-found ...}}`

   Examples:
   ```clojure
   (destroy-costume! :finance)
   ;; => {:status :destroyed :costume :finance}
   ```"
  [costume-name]
  (if-not (wardrobe/costume-exists? costume-name)
    (not-found-error costume-name)
    ;; Load metadata to get PID
    (let [meta (wardrobe/load-costume-meta costume-name)
          pid (:chrome-pid meta)]
      ;; Kill Chrome if we have a PID
      (when pid
        (chrome/kill-by-pid! pid))
      ;; Delete costume directory
      (wardrobe/delete-costume! costume-name)
      {:status :destroyed
       :costume costume-name})))

;; -----------------------------------------------------------------------------
;; Listing
;; -----------------------------------------------------------------------------

(defn- costume-status
  "Check the status of a single costume."
  [costume-name]
  (let [meta (wardrobe/load-costume-meta costume-name)]
    (if-not meta
      {:name costume-name
       :status :unknown
       :meta nil}
      (let [{:keys [debug-port chrome-pid]} meta
            probe-result (chrome/probe-cdp debug-port {:timeout-ms 200})]
        {:name costume-name
         :status (if (= :alive (:status probe-result))
                   :alive
                   :dead)
         :port debug-port
         :pid chrome-pid
         :meta meta}))))

(defn list-costumes
  "List all costumes with their status.

   Returns a vector of costume info maps:
   ```clojure
   [{:name \"finance\"
     :status :alive      ; or :dead, :unknown
     :port 9222
     :pid 12345
     :meta {...}}]
   ```

   Examples:
   ```clojure
   (list-costumes)
   ;; => [{:name \"finance\" :status :alive :port 9222 ...}
   ;;     {:name \"work\" :status :dead :port 9223 ...}]
   ```"
  []
  (let [costumes (wardrobe/list-costumes)]
    (mapv costume-status costumes)))
