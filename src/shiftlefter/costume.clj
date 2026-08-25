(ns shiftlefter.costume
  "Costume lifecycle management.

   A costume is a named, durable, authenticated browser context. Its Chrome
   is a child of the owning JVM — what survives a JVM restart is the PROFILE
   and connection metadata (sl-t8bj: durability = profile + relaunch, via
   `connect-costume!`/`relaunch-costume!`, never a surviving process),
   stored in the project-scoped wardrobe at `.shiftlefter/wardrobe/<name>/`.

   ## Lifecycle Functions

   - `init-costume!` — Create new costume: allocate port, launch Chrome, connect
   - `connect-costume!` — Reconnect to existing costume (after JVM restart)
   - `destroy-costume!` — Delete costume once its browser is confirmed dead
     (refuses while live unless `{:force true}`)
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
   - `:costume/browser-live` — destroy refused: browser still serving the
     debug port (override with `{:force true}`)
   - `:costume/meta-unreadable` — destroy refused: browser-meta.edn missing or
     corrupt, so browser death cannot be confirmed (override with `{:force true}`)
   - `:costume/destroy-failed` — browser survived a forced kill; profile kept
   - `:costume/git-tracked` — costume dir is git-tracked (refuses to operate)"
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [shiftlefter.browser.chrome :as chrome]
            [shiftlefter.config.user :as user-config]
            [shiftlefter.costume.wardrobe :as wardrobe]
            [shiftlefter.costume.browser :as cbrowser]
            [shiftlefter.webdriver.etaoin.session :as session])
  (:import [java.time Duration Instant]
           [java.time.format DateTimeParseException]))

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

(defn- relaunch-failed-error
  [costume-name reason data]
  {:error {:type :costume/relaunch-failed
           :message (str "Failed to relaunch costume " (name costume-name) ": " reason)
           :data (merge {:costume costume-name} data)}})

(defn- git-tracked-error
  [costume-name path]
  {:error {:type :costume/git-tracked
           :message (str "refusing to use a git-tracked costume dir `" path
                         "` — costumes hold live credentials; add "
                         "`.shiftlefter/wardrobe/` to .gitignore")
           :data {:costume costume-name :path path}}})

(defn- browser-live-error
  [costume-name port live-pid recorded-pid]
  {:error {:type :costume/browser-live
           :message (str "Costume " (name costume-name) "'s browser is still running ("
                         (if live-pid (str "pid " live-pid) "pid unknown")
                         ", port " port ") — not destroying. "
                         "Close it yourself, or destroy with force.")
           :data {:costume costume-name
                  :port port
                  :pid live-pid
                  :recorded-pid recorded-pid}}})

(defn- meta-unreadable-error
  [costume-name]
  {:error {:type :costume/meta-unreadable
           :message (str "Costume " (name costume-name) " has no readable browser-meta.edn"
                         " — cannot confirm its browser is dead; not destroying. "
                         "Close any browser it launched yourself, then destroy with "
                         "force to delete the profile.")
           :data {:costume costume-name}}})

(defn- destroy-failed-error
  [costume-name port still-pids]
  {:error {:type :costume/destroy-failed
           :message (str "Failed to destroy costume " (name costume-name)
                         ": browser still alive on port " port
                         " after kill; profile NOT deleted.")
           :data {:costume costume-name :port port :pids still-pids}}})

;; -----------------------------------------------------------------------------
;; Attach PID Refresh (sl-7q7w)
;; -----------------------------------------------------------------------------

(defn- refresh-attached-pid!
  "Persist `meta` with :chrome-pid refreshed from the process actually
   listening on the costume's debug port.

   The attach-to-already-alive path otherwise carries the last recorded pid
   forever, however Chrome came to be serving the port (sl-7q7w). When the
   port owner can't be resolved (no lsof), the recorded pid is kept — the
   lookup being unavailable is not evidence it's wrong.

   Returns the saved meta."
  [costume-name {:keys [debug-port] :as meta}]
  (let [actual-pid (first (chrome/port-listener-pids debug-port))
        meta' (cond-> meta actual-pid (assoc :chrome-pid actual-pid))]
    (wardrobe/save-costume-meta! costume-name meta')
    meta'))

;; -----------------------------------------------------------------------------
;; Driver Ownership (sl-34fu)
;;
;; Every costume driver spawn (connect-costume!'s two success paths,
;; do-reconnect's two — wear provisioning rides connect-costume!) used to
;; leak its chromedriver: reset-ctxs!/clear! deliberately skip persistent
;; capabilities (sl-tss7) and destroy-costume! only killed Chrome, so a
;; long REPL session accumulated one live WebDriver endpoint per
;; connect/reconnect, all still pointed at the authenticated Chrome. The
;; costume now OWNS its driver handle: each successful spawn adopts the
;; new driver and reaps the one it replaces; destroy reaps the last.
;; -----------------------------------------------------------------------------

(defonce ^:private live-drivers
  ;; costume-name -> the RAW etaoin driver (the :etaoin-driver value of
  ;; the connect result — it holds the spawned chromedriver Process).
  (atom {}))

(defn- adopt-driver!
  "Register `eta-driver` as `costume-name`'s owned driver and reap the one
   it replaces (sl-34fu). Reaping quits the WebDriver session and kills
   the chromedriver process; the costume's Chrome — attached via
   debuggerAddress — is never touched. Safe when the prior driver is
   already dead (`session/quit-driver!` never throws).

   ONE live driver per costume is the contract. A caller holding a stale
   CostumeBrowser after a re-connect self-heals through its own
   auto-reconnect on next use — but two handles to the same costume used
   CONCURRENTLY (e.g. a live REPL browser plus wear provisioning of the
   same costume) will reap each other's drivers alternately: reconnect
   thrash, not breakage. Don't drive one costume through two handles at
   once.

   nil `eta-driver` (a connect path that produced no raw driver) is a
   no-op — the registry only ever holds reapable handles."
  [costume-name eta-driver]
  (when eta-driver
    (let [k (keyword (name costume-name))
          [old _] (swap-vals! live-drivers assoc k eta-driver)
          prior (get old k)]
      (when (and prior (not (identical? prior eta-driver)))
        (session/quit-driver! prior))))
  nil)

(defn- release-driver!
  "Reap and forget `costume-name`'s owned driver, if any (sl-34fu).
   Called from `delete-and-report!` — the single funnel every destroy
   SUCCESS passes through — so a destroyed costume leaves no live
   WebDriver endpoint behind. A destroy that FAILS (browser survived the
   kill) keeps the handle, matching the kept profile."
  [costume-name]
  (let [k (keyword (name costume-name))
        [old _] (swap-vals! live-drivers dissoc k)]
    (when-let [prior (get old k)]
      (session/quit-driver! prior)))
  nil)

(defn- driver-process
  "The java.lang.Process behind a raw etaoin driver, or nil when the shape
   doesn't expose one (etaoin wraps the spawned chromedriver either as the
   Process directly or one map-level down)."
  [eta-driver]
  (let [p (:process eta-driver)]
    (cond
      (instance? java.lang.Process p) p
      (instance? java.lang.Process (:process p)) (:process p)
      :else nil)))

(defn custody-snapshot
  "Read-only view of the live-drivers registry (sl-s35y, Warden W6 ruled:
   custody made VISIBLE — the behavior stands). One entry per costume this
   JVM currently holds a WebDriver for:
   `{:costume kw :driver-alive? true|false|nil}` (nil = the driver shape
   exposed no process handle). Pure read — never reaps, never pings the
   session; safe to call from `sl daemon status` at any time."
  []
  (vec (for [[costume eta-driver] (sort-by key @live-drivers)]
         {:costume costume
          :driver-alive? (some-> (driver-process eta-driver) .isAlive)})))

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
                  (adopt-driver! costume-name (:etaoin-driver connect-result))
                  (refresh-attached-pid! costume-name meta)
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
                      (adopt-driver! costume-name (:etaoin-driver connect-result))
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
          (let [{:keys [debug-port user-data-dir]} meta
                probe-result (chrome/probe-cdp debug-port)]
            (if (= :alive (:status probe-result))
              ;; Chrome alive — ensure window exists, then reconnect
              (do
                (chrome/ensure-window! debug-port)
                (let [connect-result (session/connect-to-existing!
                                      {:port debug-port :path-driver path-driver})]
                  (if (:error connect-result)
                    (connect-failed-error costume-name "WebDriver connection failed" connect-result)
                    ;; Refresh :chrome-pid from the port owner (sl-7q7w), stamp
                    ;; last-connected, and wrap with CostumeBrowser
                    (let [_ (adopt-driver! costume-name (:etaoin-driver connect-result))
                          costume-browser (cbrowser/make-costume-browser
                                           (:browser connect-result) costume-name do-reconnect)
                          meta' (refresh-attached-pid! costume-name meta)]
                      {:status :connected
                       :costume costume-name
                       :port debug-port
                       :pid (:chrome-pid meta')
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
                      (let [_ (adopt-driver! costume-name (:etaoin-driver connect-result))
                            costume-browser (cbrowser/make-costume-browser
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
;; Lifecycle: Relaunch (sl-2de7)
;; -----------------------------------------------------------------------------

(defn relaunch-costume!
  "Launch-only relaunch of an EXISTING costume — the human re-login flow.

   Opens the costume's Chrome (persisted profile, recorded debug port)
   with NO WebDriver session attached, so a human can re-authenticate an
   expired credential; a later `connect-costume!` attaches. This is the
   post-creation counterpart of `init-costume!`'s launch-only contract
   (init refuses an existing costume by design).

   - Chrome already alive: succeeds idempotently as
     `{:status :already-running ...}` — the recorded pid is refreshed
     from the port's actual owner (sl-7q7w) and a window is ensured. A
     live attached driver keeps working untouched; an attached session
     doesn't stop a human logging in in the same window. Relaunch NEVER
     kills a running browser — that compound is `destroy-costume!` +
     `init-costume!`.
   - Chrome dead: relaunches against the persisted profile, ensures a
     window, and records the new pid/port (same save shape as
     `connect-costume!`'s relaunch path). No liveness decision ever
     reads the recorded pid.
   - Spawns NO chromedriver and never touches the costume driver
     registry (sl-34fu) — a stale dead driver from before Chrome died is
     reaped by the next `connect-costume!`'s adopt.

   Note: persisting the new pid bumps `:last-connected-at` (the
   `save-costume-meta!` stamp), so the CLI listing's \"connected\" age
   resets — consistent with its init/connect/reconnect stamp family
   (sl-cdrg).

   Options:
   - `:chrome-path` — explicit Chrome binary path (default: auto-detect)

   Returns:
   - `{:status :launched :costume <name> :port <int> :pid <long>}`
   - `{:status :already-running :costume <name> :port <int> :pid <long>}`
   - Error: `{:error {:type :costume/not-found | :costume/git-tracked
                      | :costume/relaunch-failed ...}}`

   Examples:
   ```clojure
   (relaunch-costume! :finance)
   ;; => {:status :launched :costume :finance :port 9223 :pid 30906}
   ;; Log in by hand, then (connect-costume! :finance) to attach.
   ```"
  ([costume-name]
   (relaunch-costume! costume-name {}))
  ([costume-name opts]
   (let [opts (user-config/merge-with-user-config opts)
         dir (wardrobe/costume-dir costume-name)]
     (cond
       (wardrobe/git-tracked? dir)
       (git-tracked-error costume-name dir)

       (not (wardrobe/costume-exists? costume-name))
       (not-found-error costume-name)

       :else
       (let [meta (wardrobe/load-costume-meta costume-name)]
         (if-not meta
           (not-found-error costume-name)
           (let [{:keys [debug-port user-data-dir]} meta]
             (if (= :alive (:status (chrome/probe-cdp debug-port)))
               ;; Alive — idempotent success; true up the recorded pid
               ;; (sl-7q7w) and make sure the human has a window.
               (do
                 (chrome/ensure-window! debug-port)
                 (let [meta' (refresh-attached-pid! costume-name meta)]
                   {:status :already-running
                    :costume costume-name
                    :port debug-port
                    :pid (:chrome-pid meta')}))
               ;; Dead — relaunch against the persisted profile.
               (let [chrome-path (:chrome-path opts)
                     launch-result (chrome/launch!
                                    (cond-> {:port debug-port
                                             :user-data-dir user-data-dir}
                                      chrome-path (assoc :chrome-path chrome-path)))]
                 (if (:errors launch-result)
                   (relaunch-failed-error costume-name "Chrome relaunch failed"
                                          launch-result)
                   (let [{:keys [pid port]} launch-result]
                     (chrome/ensure-window! port)
                     (wardrobe/save-costume-meta! costume-name
                                                  (assoc meta
                                                         :debug-port port
                                                         :chrome-pid pid))
                     {:status :launched
                      :costume costume-name
                      :port port
                      :pid pid})))))))))))

;; -----------------------------------------------------------------------------
;; Lifecycle: Destroy
;; -----------------------------------------------------------------------------

(defn- delete-and-report!
  "Delete the costume directory and build the success value. Also reaps
   the costume's owned WebDriver driver (sl-34fu) — this is the single
   funnel every destroy success passes through, so no destroyed costume
   leaves a live driver endpoint behind."
  [costume-name]
  (release-driver! costume-name)
  (wardrobe/delete-costume! costume-name)
  {:status :destroyed
   :costume costume-name})

(defn- force-destroy-live!
  "Kill the browser actually serving the costume's port, verify it died, and
   only then delete the profile. Falls back to the recorded pid when the port
   owner couldn't be resolved (CDP alive but no lsof). If the browser survives
   the kill, refuses to delete and reports :costume/destroy-failed."
  [costume-name {:keys [debug-port chrome-pid]} listener-pids]
  (doseq [pid (if (seq listener-pids) listener-pids [chrome-pid])]
    (when pid
      (chrome/kill-by-pid! pid)))
  (let [still-pids (chrome/port-listener-pids debug-port)
        still-alive? (or (seq still-pids)
                         (= :alive (:status (chrome/probe-cdp debug-port))))]
    (if still-alive?
      (destroy-failed-error costume-name debug-port still-pids)
      (delete-and-report! costume-name))))

(defn- destroy-with-meta!
  "Destroy dispatch once metadata loaded: liveness truth is the debug port
   (listener lookup + CDP probe), never the recorded :chrome-pid (sl-7q7w)."
  [costume-name {:keys [debug-port chrome-pid] :as meta} force?]
  (let [listener-pids (chrome/port-listener-pids debug-port)
        live? (or (seq listener-pids)
                  (= :alive (:status (chrome/probe-cdp debug-port))))]
    (cond
      ;; Browser confirmed not-live — delete. No kill: the recorded pid may
      ;; have been reused by an unrelated process.
      (not live?) (delete-and-report! costume-name)
      (not force?) (browser-live-error costume-name debug-port
                                       (first listener-pids) chrome-pid)
      :else (force-destroy-live! costume-name meta listener-pids))))

(defn destroy-costume!
  "Destroy a costume.

   Verifies the costume's browser is actually dead before deleting anything:
   liveness truth is the debug port (who is listening, CDP responding), never
   the recorded `:chrome-pid`, which can go stale (sl-7q7w). The profile
   directory — live credentials — is only deleted once the browser is
   confirmed not-live.

   - Browser dead: deletes the costume directory. No kill is attempted (the
     recorded pid may belong to an unrelated process by now).
   - Browser live: refuses with `:costume/browser-live` unless `{:force true}`,
     reporting the pid actually serving the port. With force, kills the port
     owner, re-verifies death, then deletes; if the browser survives the kill,
     returns `:costume/destroy-failed` and keeps the profile.
   - Metadata unreadable: browser death cannot be confirmed (no port), so
     refuses with `:costume/meta-unreadable` unless forced; force deletes the
     directory without killing anything.

   Options:
   - `:force` — override the live-browser / unreadable-meta refusals

   Returns:
   - Success: `{:status :destroyed :costume <name>}`
   - Refusal/error: `{:error {:type :costume/not-found | :costume/browser-live
                              | :costume/meta-unreadable | :costume/destroy-failed
                              ...}}`

   Examples:
   ```clojure
   (destroy-costume! :finance)
   ;; => {:error {:type :costume/browser-live ...}}   ; still running
   (destroy-costume! :finance {:force true})
   ;; => {:status :destroyed :costume :finance}
   ```"
  ([costume-name]
   (destroy-costume! costume-name {}))
  ([costume-name opts]
   (let [force? (boolean (:force opts))]
     (if-not (wardrobe/costume-exists? costume-name)
       (not-found-error costume-name)
       (if-let [meta (wardrobe/load-costume-meta costume-name)]
         (destroy-with-meta! costume-name meta force?)
         ;; No readable meta — no port, so not-live cannot be confirmed.
         (if force?
           (delete-and-report! costume-name)
           (meta-unreadable-error costume-name)))))))

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

;; with-gen so spec-health can exercise the specs (predicate-only specs have
;; no built-in generator).
(s/def ::iso-instant
  (s/with-gen (s/and string?
                     #(try (Instant/parse %) true
                           (catch DateTimeParseException _ false)))
    #(gen/elements ["2026-07-25T10:00:00Z" "2026-07-28T09:59:01.123456Z"])))
(s/def ::instant
  (s/with-gen #(instance? Instant %)
    #(gen/fmap (fn [s] (Instant/parse s))
               (gen/elements ["2026-07-28T10:00:00Z" "2026-01-01T00:00:00Z"]))))

(defn age-label
  "Coarsest-unit age of an ISO-8601 instant string relative to `now` (an
   Instant): \"<1m\", \"12m\", \"5h\", \"3d\" — days uncapped (\"45d\").
   nil for nil or unparseable input (the CLI renders that as \"-\"); a
   timestamp at-or-after `now` (clock skew) reads \"<1m\". Pure: `now` is
   an argument so tests inject a literal clock."
  [iso-instant now]
  (when iso-instant
    (when-let [then (try (Instant/parse iso-instant)
                         (catch DateTimeParseException _ nil))]
      (let [minutes (.toMinutes (Duration/between ^Instant then ^Instant now))]
        (cond
          (< minutes 1) "<1m"
          (< minutes 60) (str minutes "m")
          (< minutes 1440) (str (quot minutes 60) "h")
          :else (str (quot minutes 1440) "d"))))))

(s/fdef age-label
  :args (s/cat :iso-instant (s/nilable string?) :now ::instant)
  :ret (s/nilable string?))

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
