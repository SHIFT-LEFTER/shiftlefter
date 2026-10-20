(ns shiftlefter.browser.errors
  "Single source of truth for classifying WebDriver/Chrome exceptions, shared by
   the two transient-error predicates so they cannot drift (sl-jxi):

   - stepdefs/browser.clj `retryable-error?` — DOM-in-flux within a LIVE
     session: retry the SAME operation. Consumes `transient-dom-error?`.
   - costume/browser.clj `session-error?` — session/connection DEATH: reconnect
     and retry once. EXCLUDES `transient-dom-error?`, so a stale-element error
     (which etaoin also surfaces as :etaoin/http-error) is retried in place
     rather than mistaken for a dead session — a reconnect would drop the work
     onto a fresh blank session and lose page state.

   etaoin (1.1.42) raises WebDriver protocol failures as ex-info with
   `{:type :etaoin/http-error :status N :response {:value {:error <w3c-code>}}}`
   (etaoin.impl.client). The W3C error code therefore lives at
   `[:response :value :error]`.

   Rationale (why hand-roll this): Playwright auto-waits/retries actionability
   internally; the WebDriver backend doesn't, so this recovers a slice of that
   resilience. See decisions/browser-repl.md `WebDriver resilience parity`."
  (:require [clojure.string :as str]))

(def transient-webdriver-errors
  "W3C WebDriver error codes whose only cause is DOM-in-flux — the element was
   valid at lookup but the document changed before the operation completed.
   Same class as stale-element; safe to retry the SAME operation.

   EXCLUDED by design — these usually mean a genuine bug (an overlay covering
   the target, a hidden or disabled control), and auto-retrying them turns real
   failures into silent retries-until-timeout:
     - `element click intercepted`
     - `element not interactable`
     - `invalid element state`
     - `move target out of bounds`"
  #{"stale element reference"
    "no such element"
    "detached shadow root"})

(defn webdriver-error-code
  "The W3C error code string from an etaoin :etaoin/http-error exception, or nil
   when the exception carries no structured WebDriver response."
  [e]
  (get-in (ex-data e) [:response :value :error]))

(defn transient-dom-error?
  "True when `e` is a transient DOM-in-flux WebDriver error worth retrying the
   SAME operation for. Matches either:

   - the structured W3C code (preferred — `transient-webdriver-errors`), or
   - a legacy message surface that carries no clean code: the stale/no-such
     strings from older etaoin paths, and the chromedriver DevTools inspector
     error `Node with given id does not belong to the document` surfaced by the
     sl-bnk stress run, whose ONLY signal is its message.

   Pure and total: any exception in, boolean out. Deliberately narrow — see the
   EXCLUDED list in `transient-webdriver-errors`."
  [e]
  ;; Scan ex-message AND the stringified ex-data so the legacy substring surface
  ;; is preserved exactly (the pre-jxi predicate matched both); the structured
  ;; code check is the new, robust path on top.
  (let [msg (str (ex-message e) " " (ex-data e))]
    (boolean
     (or (contains? transient-webdriver-errors (webdriver-error-code e))
         (str/includes? msg "stale element")
         (str/includes? msg "StaleElementReferenceException")
         (str/includes? msg "no such element")
         (str/includes? msg "Node with given id does not belong to the document")))))

;; -----------------------------------------------------------------------------
;; Error class (sl-kowi, F15) — the browser transport's observation edge
;; -----------------------------------------------------------------------------

(defn observation-error?
  "True when `e` is an etaoin `:etaoin/http-ex` — the HTTP request to the
   driver THREW (connection refused/reset, transport timeout): no response
   existed, so nothing about the app was observed. This is the one
   decidable edge in the browser transport (etaoin 1.1.42
   etaoin.impl.client/call: the request's own Throwable → :etaoin/http-ex;
   a non-200 driver RESPONSE → :etaoin/http-error). `:etaoin/http-error`
   is an ANSWER from the driver — no such element, invalid session id —
   and stays unclassified here: element-not-found is :assertion territory
   (the spec-as-requirement was observed violated; glossary rot vs app
   regression is triage, never verdict)."
  [e]
  (= :etaoin/http-ex (:type (ex-data e))))

;; -----------------------------------------------------------------------------
;; The driver's answer, not a driver dump (sl-6qf6)
;; -----------------------------------------------------------------------------

(def driver-answer-keys
  "The top-level keys of an etaoin `:etaoin/http-error` / `:etaoin/http-ex`
   ex-data that a classified failure record keeps: what was asked of which
   driver and what it answered. Everything else is dropped BY OMISSION —
   `:driver` (the live driver map: chromedriver args, capabilities, the
   process object), `:payload` (the request body — at the navigation site
   the URL, which the record carries as `:url`; elsewhere whatever was typed),
   `:webdriver-url`, `:process-liveness-ex`, and any key a future etaoin adds."
  [:type :status :method :path :host :port])

(defn sanitize-driver-ex-data
  "ALLOWLIST projection of a driver exception's ex-data for a classified
   failure record: `driver-answer-keys` plus, under `[:response :value]`,
   the W3C `:error` code and `:message` only — never the chromedriver
   `:stacktrace` (native frames). Pure and total: any map in (a non-etaoin
   ex-data projects to whatever of these keys it has), a small map out.

   Why here: the classification site is the one place that KNOWS the failure
   is diagnosed (SUT unreachable / silent / transport threw), so the record
   needs the driver's answer as evidence, not the driver. The original
   exception stays the rethrow's cause, so the full data is one `ex-cause`
   away at the REPL (the recipe is in the exit-codes agent-doc, § Exit-4
   triage)."
  [data]
  (let [answer (select-keys (get-in data [:response :value]) [:error :message])]
    (cond-> (select-keys data driver-answer-keys)
      (seq answer) (assoc :response {:value answer}))))

(defn transport-edge-message
  "The message a classified transport failure carries (sl-6qf6): the edge
   named, then the evidence from the allowlisted keys — which request to
   which driver threw — and the cause's own message (`Connection refused`,
   `Connection reset`, …) when there is one. Never etaoin's message: etaoin
   throws through slingshot, whose message IS the whole ex-data pretty-printed
   (`throw+: {:type :etaoin/http-ex, :driver {…}, :payload {…}}`), i.e. the
   very dump the record must not carry."
  [e]
  (let [{:keys [method path host port]} (ex-data e)
        cause-msg (some-> (ex-cause e) ex-message)]
    (str "Driver unreachable: "
         (when method (str (str/upper-case (name method)) " "))
         (or path "(request)")
         " to " host ":" port " threw"
         (when (seq cause-msg) (str " — " cause-msg)))))

(defn classify-observation
  "Run `f`; rethrow an `observation-error?` throw as ex-info carrying
   `:error/class :observation` so the runner's contract sees it — with its
   OWN message (`transport-edge-message`) and the ex-data PROJECTED through
   `sanitize-driver-ex-data` (sl-6qf6), the original as cause. Until sl-6qf6
   this rethrow kept etaoin's message; that contract changed DELIBERATELY
   (Chair, 2026-09-21): the slingshot message is the dump itself, so the
   `Error:` line and `:message` would otherwise carry what the record
   removes. Every other throw passes through untouched — this wrapper never
   diagnoses, only labels the transport's no-answer edge."
  [f]
  (try
    (f)
    (catch clojure.lang.ExceptionInfo e
      (if (observation-error? e)
        (throw (ex-info (transport-edge-message e)
                        (assoc (sanitize-driver-ex-data (ex-data e))
                               :error/class :observation)
                        e))
        (throw e)))))

;; -----------------------------------------------------------------------------
;; The SUT-unreachable navigation edge (sl-kowi rider, Chair-ratified 08-30)
;; -----------------------------------------------------------------------------

(def unreachable-net-errors
  "The CLOSED set of Chromium `net::ERR_*` codes that mean the navigation's
   target never answered — the SUT was unreachable, no page existed to
   observe. Verified live (Chrome 151 / ChromeDriver 151, 2026-08-30): the
   driver does NOT answer 200 about its own error page; the navigate
   command fails with `:etaoin/http-error` status 500, W3C error `unknown
   error`, message `unknown error: net::ERR_CONNECTION_REFUSED …`.
   Deliberately NOT here: ERR_UNSAFE_PORT / ERR_INVALID_URL and every other
   code — those are request errors, not a dark SUT. Chrome-only by design;
   cross-browser parity of this signal is sl-m6be.

   Observed live: ERR_CONNECTION_REFUSED (08-30 and the 09-03 audit probe);
   the rest are ratified by name from Chromium's net error list. sl-od2v
   (Warden W4) adds ERR_TIMED_OUT (Chromium -7, the generic timeout,
   distinct from ERR_CONNECTION_TIMED_OUT -118) and ERR_EMPTY_RESPONSE —
   the latter for the case where Chrome THROWS it; the observed
   accept-then-close-without-a-byte behavior is Chrome RETURNING its own
   error page (the navigation succeeds, the scenario fails at the first
   element lookup as :assertion). That is a known hole with no
   message-based signal, recorded on the ladder page, not fixed here."
  #{"ERR_CONNECTION_REFUSED"
    "ERR_CONNECTION_RESET"
    "ERR_CONNECTION_TIMED_OUT"
    "ERR_CONNECTION_CLOSED"
    "ERR_NAME_NOT_RESOLVED"
    "ERR_ADDRESS_UNREACHABLE"
    "ERR_INTERNET_DISCONNECTED"
    "ERR_TIMED_OUT"
    "ERR_EMPTY_RESPONSE"})

(defn unreachable-net-error
  "The `ERR_*` code when `e` is an etaoin `:etaoin/http-error` whose W3C
   message names a Chromium net error in `unreachable-net-errors`; nil
   otherwise. Reads `[:response :value :message]`, the shape etaoin 1.1.42
   surfaces (`unknown error: net::ERR_CONNECTION_REFUSED\\n (Session info…)`)."
  [e]
  (when (= :etaoin/http-error (:type (ex-data e)))
    (let [msg (str (get-in (ex-data e) [:response :value :message]))
          code (second (re-find #"net::(ERR_[A-Z_]+)" msg))]
      (when (contains? unreachable-net-errors code) code))))

(defn navigation-timeout?
  "True when `e` is an etaoin `:etaoin/http-error` whose W3C error code is
   `timeout` — the driver's answer when the page-load timeout elapsed with
   the server having accepted and never answered (sl-od2v, Warden W4; kowi
   Ruling 3c: a transport timeout with pure silence is :observation even
   when a perf bug is suspected). The REAL shape from the 09-03 probe:
   status 500, `[:response :value :error] \"timeout\"`, message `timeout:
   Timed out receiving message from renderer: 3.000`. Only meaningful at
   the navigation site — see classify-navigation."
  [e]
  (and (= :etaoin/http-error (:type (ex-data e)))
       (= "timeout" (webdriver-error-code e))))

(defn classify-navigation
  "Run `f` (a navigation to `url`); rethrow a navigation failure whose
   driver answer says no page existed to observe, as ex-info carrying
   `:error/class :observation` (original ex-data PROJECTED through
   `sanitize-driver-ex-data` — the driver's answer, not a driver dump,
   sl-6qf6 — original as cause). Two arms, one site:

   - an `unreachable-net-errors` code in the answer →
     `SUT unreachable: <code> for <url>` (+ `:net-error`, `:url`);
   - a W3C `timeout` answer (`navigation-timeout?` — the page-load timeout
     elapsed on a server that accepted and never answered; sl-od2v) →
     `SUT silent: navigation timed out for <url>` (+ `:w3c-error
     \"timeout\"`, `:url`). The page-load timeout is the adapter's
     `:page-load-timeout-ms` config (absent = Chrome's default).

   Applied at ONE site — the navigation step — never on element lookups:
   after a successful navigation every driver answer, a wait timeout
   included, is product evidence. Every other throw (incl. the transport's
   own `:etaoin/http-ex`, which the enclosing `classify-observation`
   labels) passes through untouched."
  [f url]
  (try
    (f)
    (catch clojure.lang.ExceptionInfo e
      (cond
        (unreachable-net-error e)
        (let [code (unreachable-net-error e)]
          (throw (ex-info (str "SUT unreachable: " code " for " url)
                          (assoc (sanitize-driver-ex-data (ex-data e))
                                 :error/class :observation
                                 :net-error code
                                 :url url)
                          e)))

        (navigation-timeout? e)
        (throw (ex-info (str "SUT silent: navigation timed out for " url)
                        (assoc (sanitize-driver-ex-data (ex-data e))
                               :error/class :observation
                               :w3c-error "timeout"
                               :url url)
                        e))

        :else (throw e)))))
