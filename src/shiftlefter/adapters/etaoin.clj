(ns shiftlefter.adapters.etaoin
  "Etaoin adapter for the adapter registry.

   Provides factory and cleanup functions for browser capabilities
   using the Etaoin WebDriver library.

   ## Factory

   `create-browser` creates a new browser instance from config.
   Returns the EtaoinBrowser implementing IBrowser protocol.

   ## Cleanup

   `close-browser` closes the browser session and releases resources.

   ## Capture (sl-3q9r)

   `capture-screenshot` / `capture-console` / `capture-page-source` — the
   adapter-contributed capture kinds behind the [:runner :capture] policy.
   Each takes the capture request map ({:handle <cleanup-handle>}) and
   returns nil (nothing to capture) or {:data ... :media-type ... :ext ...};
   the framework owns timing, file writes, and ref construction. The raw
   etaoin driver lives ONLY in the cleanup handle (`:impl-key` adapter), so
   capture fns read (:etaoin-driver handle), same as close-browser."
  (:require [clojure.string :as str]
            [etaoin.api :as eta]
            [shiftlefter.config.user :as user-config]
            [shiftlefter.webdriver.etaoin.browser :as browser])
  (:import [java.util Base64]))

;; -----------------------------------------------------------------------------
;; Factory
;; -----------------------------------------------------------------------------

(def automation-hygiene-prefs
  "Chrome profile prefs every automation session wants off by default
   (sl-0fco): the password trio. Chrome's native popups are invisible to
   WebDriver (not DOM) but scary/noisy in demos — the breach popup fires
   on any credential in public breach lists (Chair saw it live in 02b).

   Merged UNDER user :adapter-opts :prefs in `build-driver-opts`, so any
   user-provided pref wins per-key (set one `true` to opt back in).
   Deliberately ONLY password-popup prefs — nothing that changes page
   *feature* behavior (notifications, autofill, translate); ratified by
   Chair 2026-07-21. SIEVE launch parity: sl-u85t; playwright: sl-x7ec."
  {"profile.password_manager_leak_detection" false
   "credentials_enable_service" false
   "profile.password_manager_enabled" false})

(defn build-driver-opts
  "Build the Etaoin driver options map from adapter config — pure, so the
   prefs merge is testable without launching Chrome.

   Merge layers, later wins: base {:type :chrome :headless} < user
   :adapter-opts; then `automation-hygiene-prefs` merges UNDER the result's
   :prefs (per-key — user prefs override defaults key-by-key, and defaults
   survive when the user supplies unrelated prefs).

   `:path-driver` (chromedriver discovery) is resolved by the caller and
   passed in — it reads ~/.shiftlefter/config.edn, so it stays out of the
   pure path."
  [config path-driver]
  (let [headless? (get config :headless false)
        adapter-opts (get config :adapter-opts {})
        adapter-opts (if path-driver
                       (assoc adapter-opts :path-driver path-driver)
                       adapter-opts)]
    (-> (merge {:type :chrome
                :headless headless?}
               adapter-opts)
        (update :prefs #(merge automation-hygiene-prefs %)))))

(defn create-browser
  "Create a new browser instance from configuration.

   Config options:
   - :headless — if true, run headless (default: false)
   - :adapter-opts — map merged into Etaoin options (e.g., :size, :prefs, :user-agent).
     `automation-hygiene-prefs` merges under :prefs; user prefs win per-key.
   - :webdriver-url — URL of WebDriver server (optional, uses default if not provided)
   - :page-load-timeout-ms — positive integer; the WebDriver page-load
     timeout set on the session at creation (sl-od2v). Absent = Chrome's
     default (300s). A server that accepts and never answers makes the
     navigation step throw the driver's `timeout` answer when it elapses,
     which the navigation site classifies `:observation` (exit 4) — with
     this set, that verdict arrives in seconds instead of minutes.

   Returns:
   - Success: {:ok {:browser EtaoinBrowser :etaoin-driver raw-driver}}
   - Error: {:error {:type :adapter/create-failed ...}}

   Examples:
   ```clojure
   (create-browser {:headless true})
   ;; => {:ok {:browser #EtaoinBrowser{...} :etaoin-driver {...}}}

   (create-browser {:headless true
                    :adapter-opts {:size {:width 1280 :height 720}}})
   ;; => {:ok {:browser #EtaoinBrowser{...} :etaoin-driver {...}}}
   ```"
  [config]
  (try
    (let [;; Chromedriver discovery: adapter-opts :path-driver > config.edn > PATH.
          ;; Shared resolver — single source of truth with the costume path.
          path-driver (user-config/resolve-chromedriver-path (get config :adapter-opts {}))
          opts (build-driver-opts config path-driver)
          ;; Create session via Etaoin
          eta-driver (eta/chrome opts)
          ;; sl-od2v: the page-load timeout, when configured (etaoin takes
          ;; seconds). Absent = the driver's default, unchanged.
          _ (when-let [ms (:page-load-timeout-ms config)]
              (eta/set-page-load-timeout eta-driver (/ ms 1000.0)))
          ;; Create EtaoinBrowser for protocol operations
          etaoin-browser (browser/make-etaoin-browser eta-driver)]
      {:ok {:browser etaoin-browser
            :etaoin-driver eta-driver}})
    (catch Exception e
      {:error {:type :adapter/create-failed
               :adapter :etaoin
               :message (ex-message e)
               :config config}})))

;; -----------------------------------------------------------------------------
;; Cleanup
;; -----------------------------------------------------------------------------

(defn close-browser
  "Close the browser and release resources.

   Takes the UNWRAPPED capability map — the value under `:ok` in
   `create-browser`'s return, NOT the wrapped `{:ok {...}}` result itself.
   Passing the wrapped map is an error: a capability without `:etaoin-driver`
   means nothing gets quit, so returning success would silently leak the
   chromedriver + Chrome process tree (sl-9vag; orphans reparent to PID 1 and
   live forever — killing chromedriver does NOT reap its Chrome children).

   Returns:
   - Success: {:ok :closed}
   - Error: {:error {:type :adapter/cleanup-failed ...}}

   Examples:
   ```clojure
   (close-browser (:ok (create-browser {:headless true})))
   ;; => {:ok :closed}

   (close-browser (create-browser {:headless true}))   ; wrapped — WRONG
   ;; => {:error {:type :adapter/cleanup-failed ...}}
   ```"
  [capability]
  (if-let [eta-driver (:etaoin-driver capability)]
    (try
      (eta/quit eta-driver)
      {:ok :closed}
      (catch Exception e
        {:error {:type :adapter/cleanup-failed
                 :adapter :etaoin
                 :message (ex-message e)}}))
    {:error {:type :adapter/cleanup-failed
             :adapter :etaoin
             :message (str "No :etaoin-driver in capability — nothing was closed. "
                           "Pass the unwrapped map (the value under :ok of "
                           "create-browser's return), not the wrapped {:ok {...}} result.")}}))

;; -----------------------------------------------------------------------------
;; Capture (sl-3q9r)
;; -----------------------------------------------------------------------------

(defn- require-driver
  "The raw etaoin driver from a capture request's handle, or throw (a
   handle without the driver is a capture infrastructure failure — D4
   converts the throw to a capture-attributed error)."
  [{:keys [handle]}]
  (or (:etaoin-driver handle)
      (throw (ex-info "no :etaoin-driver in capture handle"
                      {:type :capture/no-driver}))))

(defn capture-screenshot
  "PNG screenshot of the current page, as bytes. The raw-execute Base64
   route (the sieve take-screenshot precedent) — no temp file, unlike
   eta/screenshot which insists on writing one."
  [req]
  (let [driver (require-driver req)
        resp (eta/execute {:driver driver
                           :method :get
                           :path [:session (:session driver) :screenshot]})
        b64 (:value resp)]
    (when-not (seq b64)
      (throw (ex-info "empty screenshot response" {:type :capture/empty-screenshot})))
    {:data (.decode (Base64/getDecoder) ^String b64)
     :media-type "image/png"
     :ext "png"}))

(defn format-console-lines
  "One plain line per browser console entry: `LEVEL source message`.
   Pure — unit-testable without a driver."
  [entries]
  (str/join "\n" (map (fn [{:keys [level source message]}]
                        (str (str/upper-case (name (or level :info)))
                             " " (some-> source name) " " message))
                      entries)))

(defn capture-console
  "Drain the browser console log. DESTRUCTIVE: WebDriver returns-and-clears,
   so each call captures exactly the entries since the last read (the
   step-grain provenance the per-step drain policy is built on). Chrome/
   Edge only — drivers without log support return nil (legible no-op, never
   an error); an empty drain returns nil (no file, no ref)."
  [req]
  (let [driver (require-driver req)]
    (when (eta/supports-logs? driver)
      (let [entries (eta/get-logs driver)]
        (when (seq entries)
          {:data (format-console-lines entries)
           :media-type "text/plain"
           :ext "log"})))))

(defn capture-page-source
  "The current page's full HTML source."
  [req]
  {:data (eta/get-source (require-driver req))
   :media-type "text/html"
   :ext "html"})
