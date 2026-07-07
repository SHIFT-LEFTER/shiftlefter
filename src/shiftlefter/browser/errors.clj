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
