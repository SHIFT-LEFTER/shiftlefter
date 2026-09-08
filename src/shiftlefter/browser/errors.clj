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

(defn classify-observation
  "Run `f`; rethrow an `observation-error?` throw as ex-info carrying
   `:error/class :observation` (same message, ex-data merged, original as
   cause) so the runner's contract sees it. Every other throw passes
   through untouched — this wrapper never diagnoses, only labels the
   transport's no-answer edge."
  [f]
  (try
    (f)
    (catch clojure.lang.ExceptionInfo e
      (if (observation-error? e)
        (throw (ex-info (ex-message e)
                        (assoc (ex-data e) :error/class :observation)
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
   `:error/class :observation` (original ex-data merged, original as
   cause). Two arms, one site:

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
                          (assoc (ex-data e)
                                 :error/class :observation
                                 :net-error code
                                 :url url)
                          e)))

        (navigation-timeout? e)
        (throw (ex-info (str "SUT silent: navigation timed out for " url)
                        (assoc (ex-data e)
                               :error/class :observation
                               :w3c-error "timeout"
                               :url url)
                        e))

        :else (throw e)))))
