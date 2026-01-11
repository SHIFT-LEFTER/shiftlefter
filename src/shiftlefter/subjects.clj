(ns shiftlefter.subjects
  "Persistent browser subject lifecycle management.

   Subjects are named browser instances that survive JVM restarts.
   Each subject has its own Chrome profile and connection metadata
   stored at `~/.shiftlefter/subjects/<name>/`.

   ## Lifecycle Functions

   - `init-persistent!` — Create new subject: allocate port, launch Chrome, connect
   - `connect-persistent!` — Reconnect to existing subject (after JVM restart)
   - `destroy-persistent!` — Kill Chrome and delete subject profile
   - `list-persistent` — List all subjects with their status

   ## Subject State

   Subjects transition through states:
   - `:initializing` — Being created
   - `:connected` — Chrome running, WebDriver session active
   - `:disconnected` — Chrome running, but no active session (needs reconnect)
   - `:dead` — Chrome not running (needs relaunch or destroy)

   ## Error Types

   - `:subject/already-exists` — init called for existing subject
   - `:subject/not-found` — connect/destroy called for nonexistent subject
   - `:subject/init-failed` — initialization failed (port/launch/connect)
   - `:subject/connect-failed` — connection to existing Chrome failed
   - `:subject/destroy-failed` — cleanup failed"
  (:require [shiftlefter.browser.chrome :as chrome]
            [shiftlefter.config.user :as user-config]
            [shiftlefter.subjects.profile :as profile]
            [shiftlefter.subjects.browser :as pbrowser]
            [shiftlefter.webdriver.etaoin.session :as session]))

;; -----------------------------------------------------------------------------
;; Error Helpers
;; -----------------------------------------------------------------------------

(defn- already-exists-error
  [subject-name]
  {:error {:type :subject/already-exists
           :message (str "Subject already exists: " (name subject-name))
           :data {:subject subject-name}}})

(defn- not-found-error
  [subject-name]
  {:error {:type :subject/not-found
           :message (str "Subject not found: " (name subject-name))
           :data {:subject subject-name}}})

(defn- init-failed-error
  [subject-name reason data]
  {:error {:type :subject/init-failed
           :message (str "Failed to initialize subject " (name subject-name) ": " reason)
           :data (merge {:subject subject-name} data)}})

(defn- connect-failed-error
  [subject-name reason data]
  {:error {:type :subject/connect-failed
           :message (str "Failed to connect to subject " (name subject-name) ": " reason)
           :data (merge {:subject subject-name} data)}})

;; -----------------------------------------------------------------------------
;; Reconnect Helper (used by PersistentBrowser)
;; -----------------------------------------------------------------------------

(defn- do-reconnect
  "Internal reconnect function for PersistentBrowser.

   Attempts to reconnect to a subject's Chrome instance.
   Returns the raw EtaoinBrowser on success, nil on failure."
  [subject-name stealth]
  (when (profile/profile-exists? subject-name)
    (let [meta (profile/load-browser-meta subject-name)]
      (when meta
        (let [{:keys [debug-port user-data-dir]} meta
              probe-result (chrome/probe-cdp debug-port)]
          (if (= :alive (:status probe-result))
            ;; Chrome alive — just reconnect
            (let [connect-result (session/connect-to-existing!
                                  {:port debug-port :stealth stealth})]
              (when-not (:error connect-result)
                (profile/save-browser-meta! subject-name meta)
                (:browser connect-result)))
            ;; Chrome dead — relaunch
            (let [launch-result (chrome/launch!
                                 {:port debug-port
                                  :user-data-dir user-data-dir
                                  :stealth stealth})]
              (when-not (:errors launch-result)
                (let [{:keys [pid port]} launch-result
                      connect-result (session/connect-to-existing!
                                      {:port port :stealth stealth})]
                  (if (:error connect-result)
                    (do
                      (chrome/kill-by-pid! pid)
                      nil)
                    (do
                      (profile/save-browser-meta! subject-name
                                                  (assoc meta
                                                         :debug-port port
                                                         :chrome-pid pid))
                      (:browser connect-result))))))))))))

;; -----------------------------------------------------------------------------
;; Lifecycle: Init
;; -----------------------------------------------------------------------------

(defn init-persistent!
  "Initialize a new persistent subject.

   Creates profile directory, allocates a debug port, launches Chrome,
   connects via WebDriver, and saves metadata.

   Options:
   - `:stealth` — if true, use anti-detection flags (default: false)
   - `:chrome-path` — explicit Chrome binary path (default: auto-detect)

   Returns:
   - Success: `{:status :connected
                :subject <name>
                :port <int>
                :pid <long>
                :browser <EtaoinBrowser>}`
   - Error: `{:error {:type :subject/... ...}}`

   Examples:
   ```clojure
   (init-persistent! :finance {:stealth true})
   ;; => {:status :connected :subject :finance :port 9222 :pid 12345 ...}
   ```"
  ([subject-name]
   (init-persistent! subject-name {}))
  ([subject-name opts]
   ;; Merge with user config (user config is lower priority)
   (let [opts (user-config/merge-with-user-config opts)]
     ;; Check if subject already exists
     (if (profile/profile-exists? subject-name)
       (already-exists-error subject-name)
       ;; Allocate port
       (let [port-result (chrome/allocate-port)]
       (if (:errors port-result)
         (init-failed-error subject-name "No port available" port-result)
         ;; Create profile directories
         (let [_profile-dir (profile/ensure-dirs! subject-name)
               user-data-dir (profile/chrome-profile-dir subject-name)
               stealth (get opts :stealth false)
               chrome-path (:chrome-path opts)
               ;; Launch Chrome
               launch-result (chrome/launch!
                              (cond-> {:port port-result
                                       :user-data-dir user-data-dir
                                       :stealth stealth}
                                chrome-path (assoc :chrome-path chrome-path)))]
           (if (:errors launch-result)
             ;; Launch failed — clean up profile
             (do
               (profile/delete-profile! subject-name)
               (init-failed-error subject-name "Chrome launch failed" launch-result))
             ;; Connect via WebDriver
             (let [{:keys [pid port]} launch-result
                   connect-result (session/connect-to-existing!
                                   {:port port :stealth stealth})]
               (if (:error connect-result)
                 ;; Connect failed — kill Chrome and clean up
                 (do
                   (chrome/kill-by-pid! pid)
                   (profile/delete-profile! subject-name)
                   (init-failed-error subject-name "WebDriver connection failed" connect-result))
                 ;; Success — save metadata and wrap with PersistentBrowser
                 (let [{:keys [browser]} connect-result
                       persistent-browser (pbrowser/make-persistent-browser
                                           browser subject-name stealth do-reconnect)]
                   (profile/save-browser-meta! subject-name
                                               {:debug-port port
                                                :chrome-pid pid
                                                :user-data-dir user-data-dir
                                                :stealth stealth})
                   {:status :connected
                    :subject subject-name
                    :port port
                    :pid pid
                    :browser persistent-browser})))))))))))

;; -----------------------------------------------------------------------------
;; Lifecycle: Connect
;; -----------------------------------------------------------------------------

(defn connect-persistent!
  "Connect to an existing persistent subject.

   Loads saved metadata, checks if Chrome is alive, and establishes
   a new WebDriver session. If Chrome is dead, relaunches it.

   Returns:
   - Success: `{:status :connected
                :subject <name>
                :port <int>
                :pid <long>
                :browser <EtaoinBrowser>}`
   - Error: `{:error {:type :subject/... ...}}`

   Examples:
   ```clojure
   ;; After JVM restart
   (connect-persistent! :finance)
   ;; => {:status :connected :subject :finance :port 9222 ...}
   ```"
  [subject-name]
  ;; Check if subject exists
  (if-not (profile/profile-exists? subject-name)
    (not-found-error subject-name)
    ;; Load metadata
    (let [meta (profile/load-browser-meta subject-name)]
      (if-not meta
        (not-found-error subject-name)
        ;; Check if Chrome is alive
        (let [{:keys [debug-port chrome-pid stealth user-data-dir]} meta
              probe-result (chrome/probe-cdp debug-port)]
          (if (= :alive (:status probe-result))
            ;; Chrome alive — just reconnect
            (let [connect-result (session/connect-to-existing!
                                  {:port debug-port :stealth stealth})]
              (if (:error connect-result)
                (connect-failed-error subject-name "WebDriver connection failed" connect-result)
                ;; Update last-connected timestamp and wrap with PersistentBrowser
                (let [persistent-browser (pbrowser/make-persistent-browser
                                          (:browser connect-result) subject-name stealth do-reconnect)]
                  (profile/save-browser-meta! subject-name meta)
                  {:status :connected
                   :subject subject-name
                   :port debug-port
                   :pid chrome-pid
                   :browser persistent-browser})))
            ;; Chrome dead — relaunch
            (let [launch-result (chrome/launch!
                                 {:port debug-port
                                  :user-data-dir user-data-dir
                                  :stealth stealth})]
              (if (:errors launch-result)
                (connect-failed-error subject-name "Chrome relaunch failed" launch-result)
                ;; Connect to relaunched Chrome
                (let [{:keys [pid port]} launch-result
                      connect-result (session/connect-to-existing!
                                      {:port port :stealth stealth})]
                  (if (:error connect-result)
                    ;; Connect failed — kill Chrome but keep profile
                    (do
                      (chrome/kill-by-pid! pid)
                      (connect-failed-error subject-name "WebDriver connection failed" connect-result))
                    ;; Update metadata with new PID and wrap with PersistentBrowser
                    (let [persistent-browser (pbrowser/make-persistent-browser
                                              (:browser connect-result) subject-name stealth do-reconnect)]
                      (profile/save-browser-meta! subject-name
                                                  (assoc meta
                                                         :debug-port port
                                                         :chrome-pid pid))
                      {:status :connected
                       :subject subject-name
                       :port port
                       :pid pid
                       :browser persistent-browser})))))))))))

;; -----------------------------------------------------------------------------
;; Lifecycle: Destroy
;; -----------------------------------------------------------------------------

(defn destroy-persistent!
  "Destroy a persistent subject.

   Kills the Chrome process and deletes the entire profile directory
   (including Chrome user data like cookies, history, etc.).

   Returns:
   - Success: `{:status :destroyed :subject <name>}`
   - Not found: `{:error {:type :subject/not-found ...}}`

   Examples:
   ```clojure
   (destroy-persistent! :finance)
   ;; => {:status :destroyed :subject :finance}
   ```"
  [subject-name]
  (if-not (profile/profile-exists? subject-name)
    (not-found-error subject-name)
    ;; Load metadata to get PID
    (let [meta (profile/load-browser-meta subject-name)
          pid (:chrome-pid meta)]
      ;; Kill Chrome if we have a PID
      (when pid
        (chrome/kill-by-pid! pid))
      ;; Delete profile directory
      (profile/delete-profile! subject-name)
      {:status :destroyed
       :subject subject-name})))

;; -----------------------------------------------------------------------------
;; Listing
;; -----------------------------------------------------------------------------

(defn- subject-status
  "Check the status of a single subject."
  [subject-name]
  (let [meta (profile/load-browser-meta subject-name)]
    (if-not meta
      {:name subject-name
       :status :unknown
       :meta nil}
      (let [{:keys [debug-port chrome-pid]} meta
            probe-result (chrome/probe-cdp debug-port {:timeout-ms 200})]
        {:name subject-name
         :status (if (= :alive (:status probe-result))
                   :alive
                   :dead)
         :port debug-port
         :pid chrome-pid
         :meta meta}))))

(defn list-persistent
  "List all persistent subjects with their status.

   Returns a vector of subject info maps:
   ```clojure
   [{:name \"finance\"
     :status :alive      ; or :dead, :unknown
     :port 9222
     :pid 12345
     :meta {...}}]
   ```

   Examples:
   ```clojure
   (list-persistent)
   ;; => [{:name \"finance\" :status :alive :port 9222 ...}
   ;;     {:name \"work\" :status :dead :port 9223 ...}]
   ```"
  []
  (let [subjects (profile/list-subjects)]
    (mapv subject-status subjects)))
