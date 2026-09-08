(ns shiftlefter.stepdefs.browser
  "Built-in browser step definitions for ShiftLefter.

   All built-in browser steps are subject-extracting: the first token in
   the step text is a subject (`:alice`, `:user`, `:tester`, etc.) that
   identifies which browser session to use. This is by design — every
   step has a subject (see invariants.md).

   For single-actor scenarios, use any subject name:
   ```gherkin
   When :user opens the browser to 'https://example.com'
   And :user clicks {:id \"login\"}
   ```

   ## Action Steps

   - `:subject opens the browser to '<url>'`
   - `:subject clicks {<locator>}`
   - `:subject double-clicks {<locator>}`
   - `:subject right-clicks {<locator>}`
   - `:subject moves to {<locator>}`
   - `:subject hovers over {<locator>}` (delegates to the same kernel op as moves-to)
   - `:subject drags {<locator>} to {<locator>}`
   - `:subject fills {<locator>} with '<text>'`
   - `:subject goes back`
   - `:subject goes forward`
   - `:subject refreshes the page`
   - `:subject scrolls to {<locator>}`
   - `:subject scrolls to the top|bottom`
   - `:subject clears {<locator>}`
   - `:subject selects '<text>' from {<locator>}`
   - `:subject presses <Key>` (W3C KeyboardEvent.key name, e.g. `Enter`,
     `ArrowUp`; or a quoted single character: `presses 'g'`. Chords were
     removed at sl-2sj6 — parked as sl-eecq)
   - `:subject accepts the alert`
   - `:subject dismisses the alert`
   - `:subject maximizes the window`
   - `:subject resizes the window to <W>x<H>`
   - `:subject switches to the next window`
   - `:subject switches to frame {<locator>}`
   - `:subject switches to the main frame`

   ## Verification Steps

   - `:subject should see '<text>'`
   - `:subject should see <N> {<locator>} elements`
   - `:subject should see {<locator>}`
   - `:subject should not see {<locator>}`
   - `:subject should be on '<url>'`
   - `:subject should see the title '<text>'`
   - `:subject should see {<locator>} with text '<text>'`
   - `:subject should see {<locator>} with value '<text>'`
   - `:subject should see {<locator>} with attribute '<attr>' equal to '<value>'`
   - `:subject should see {<locator>} enabled`
   - `:subject should see {<locator>} disabled`
   - `:subject should see an alert`
   - `:subject should see an alert with '<text>'`
   - `:subject should see {<locator>} matching /<regex>/` (assert-only:
     binds nothing; named groups are a plan error pointing at captures)

   ## Capture Steps (sl-zgna)

   - `:subject captures {<locator>} matching /<regex>/`

   Capture is assert-plus-bind: read the element's text with the should-see
   family's wait-then-assert semantics, match the pattern, and merge its
   `(?<name>...)` named groups into the scenario data plane (`:sl/bindings`).
   No match within the retry budget = step failure — it fails as though the
   subject could not see that text.

   ## Await Steps (sl-jsn)

   - `:subject waits <N> seconds`                       — :wait :duration
       (N may be decimal, e.g. `0.5` for 500ms)
   - `:subject waits for {<locator>}`                   — :wait :for-element
   - `:subject waits for {<locator>} to show '<text>'`  — :wait :for-text
   - `:subject waits for <N> {<locator>}`               — :wait :for-count

   :wait frames are await-style versions of the corresponding :see frames.
   They retry against the live DOM until the condition is met or the await
   deadline elapses, throwing a structured :step/poll-timeout error.

   ## Retry Policy

   All patience rides the deadline substrate (sl-99u2,
   `shiftlefter.step/poll-until`): deadlines resolve instance > interface >
   global from `:timing` config, bottoming out in this ns's class defaults —
   verification steps (`should see`, `should be on`, etc.) at 3000ms,
   await steps (`:wait` frames) at 5000ms, both polling at 100ms unless
   config says otherwise. Retryable errors: stale element references,
   missing elements, assertion failures (`:browser/assertion-failed`), and
   capture misses (`:bindings/capture-failure` — the capture step polls
   like a should-see). Deadline exhaustion throws the kernel's legible
   :step/poll-timeout record (elapsed, deadline, deadline source, last
   assertion error).

   Exceptions:
   - `should not see` does NOT retry (negation is instant — if visible now,
     waiting for disappearance is a different operation)
   - Action steps NEVER retry (mutations are not idempotent)

   Bind `*retry-timeout-ms*` (verify) or `*wait-timeout-ms*` (await) to
   override every rung — root value nil defers to the ladder.

   ## Locator Syntax

   Locators in step text support two formats:

   EDN maps (explicit):
   - `{:css \"#login\"}` — CSS selector
   - `{:xpath \"//button\"}` — XPath
   - `{:id \"submit\"}` — element ID

   Intent references (recommended):
   - `Login.submit` — basic reference
   - `Login.submit[1]` — first match (1-indexed)
   - `Login.submit[-1]` — last match
   - `Login.submit[*]` — all matches (for counting)

   Intent references resolve to concrete locators via the
   `glossary/intents/*.edn` files. This keeps locators out
   of feature files, improving maintainability.

   NOTE: Vector shorthand `[:css \"...\"]` is DEPRECATED
   and no longer supported."
  (:require [shiftlefter.stepengine.registry :refer [defstep]]
            [shiftlefter.stepengine.bindings :as bindings]
            [shiftlefter.capabilities.ctx :as cap]
            [shiftlefter.browser.errors :as browser-errors]
            [shiftlefter.browser.locators :as locators]
            [shiftlefter.browser.protocol :as bp]
            [shiftlefter.browser.target :as target]
            [shiftlefter.browser.intent :as browser-intent]
            [shiftlefter.browser.url-match :as url-match]
            [shiftlefter.intent.state :as intent-state]
            [shiftlefter.intent.resolve :as intent-resolve]
            [shiftlefter.step :as step]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [shiftlefter.svo.keys :as svo-keys]))

;; -----------------------------------------------------------------------------
;; Locator Pattern
;; -----------------------------------------------------------------------------

(def ^:private locator-re
  "Regex pattern string for capturing locators in step text.

   Matches:
   - Flat intent references: Login.submit, Login.submit[1], Login.submit[-1], Login.submit[*]
   - Nested (multi-segment) references: Bookmarks.tweet[2].quoted.author,
     Dashboard.featured[1].rating.stars — each `.segment` may carry an index
     (the resolver walks them; the step layer must capture the whole address).
   - EDN maps: {:css \"#foo\"}, {:id \"bar\"}

   NOTE: Vector shorthand ([...]) is deprecated and no longer matched."
  "[A-Z][A-Za-z0-9_-]*(?:\\.[a-z][a-z0-9_-]*(?:\\[-?\\d+\\]|\\[\\*\\])?)+|\\{[^}]+\\}")

(def ^:private value-re
  "Central value-slot fragment (sl-yh7): quoted literal (captured WITH its
   quotes — the engine strips them by the frame's :arg-kinds at exec) or a
   {binding} token resolved from :sl/bindings. '{code}' stays literal."
  bindings/value-re-fragment)

(def ^:private location-re
  "Regex pattern string for :location-slot captures (sl-iseq/sl-3jr4/sl-yh7):
   quoted literal URL | {binding} token | bare PascalCase named-location ref.
   Tokens resolve at exec (quote-wrapped, so resolve-location-object's
   literal path handles them); bare refs resolve via :base-url.

   TWIN (sl-8bxt): the bare-ref branch (last alternation) must stay
   textually identical to intent.resolve/location-ref-pattern — a regex
   splice can't call the classifier. Change both together; extract if a
   third consumer appears. Drift guard: stepdefs/browser_test.clj."
  (str bindings/literal-re-fragment "|" bindings/token-re-fragment
       "|[A-Z][A-Za-z0-9_-]*(?:\\.[A-Za-z0-9_-]+)*"))

;; Pre-built pattern strings for common step patterns
(def ^:private click-pattern
  (re-pattern (str ":([\\w./-]+) clicks (" locator-re ")")))

(def ^:private doubleclick-pattern
  (re-pattern (str ":([\\w./-]+) double-clicks (" locator-re ")")))

(def ^:private rightclick-pattern
  (re-pattern (str ":([\\w./-]+) right-clicks (" locator-re ")")))

(def ^:private move-to-pattern
  (re-pattern (str ":([\\w./-]+) moves to (" locator-re ")")))

(def ^:private drag-pattern
  (re-pattern (str ":([\\w./-]+) drags (" locator-re ") to (" locator-re ")")))

(def ^:private fill-pattern
  (re-pattern (str ":([\\w./-]+) fills (" locator-re ") with (" value-re ")")))

(def ^:private scroll-to-pattern
  (re-pattern (str ":([\\w./-]+) scrolls to (" locator-re ")")))

(def ^:private clear-pattern
  (re-pattern (str ":([\\w./-]+) clears (" locator-re ")")))

(def ^:private select-from-pattern
  (re-pattern (str ":([\\w./-]+) selects (" value-re ") from (" locator-re ")")))

(def ^:private switch-frame-pattern
  (re-pattern (str ":([\\w./-]+) switches to frame (" locator-re ")")))

(def ^:private should-see-count-pattern
  (re-pattern (str ":([\\w./-]+) should see (\\d+) (" locator-re ") elements")))

(def ^:private should-see-text-pattern
  (re-pattern (str ":([\\w./-]+) should see (" locator-re ") with text (" value-re ")")))

(def ^:private should-see-value-pattern
  (re-pattern (str ":([\\w./-]+) should see (" locator-re ") with value (" value-re ")")))

(def ^:private should-see-attr-pattern
  (re-pattern (str ":([\\w./-]+) should see (" locator-re ") with attribute ("
                   value-re ") equal to (" value-re ")")))

(def ^:private should-see-enabled-pattern
  (re-pattern (str ":([\\w./-]+) should see (" locator-re ") enabled")))

(def ^:private should-see-disabled-pattern
  (re-pattern (str ":([\\w./-]+) should see (" locator-re ") disabled")))

(def ^:private should-see-locator-pattern
  (re-pattern (str ":([\\w./-]+) should see (" locator-re ")")))

(def ^:private should-not-see-pattern
  (re-pattern (str ":([\\w./-]+) should not see (" locator-re ")")))

;; :capture (sl-zgna): the match slot is a :matcher (frame :arg-kinds) — its
;; (?<name>...) named groups produce bindings; the /.../ delimiters follow the
;; SMS receive surface.
(def ^:private capture-pattern
  (re-pattern (str ":([\\w./-]+) captures (" locator-re ") matching /(.+)/")))

;; :see :matching (sl-gwru): assert-only twin of :capture — same matcher
;; slot, same poll semantics, binds nothing. Surface is "should see"
;; (family consistency with every other :see assertion; the bead's
;; "sees ... matching" was discussion shorthand, Chair-confirmed).
(def ^:private should-see-matching-pattern
  (re-pattern (str ":([\\w./-]+) should see (" locator-re ") matching /(.+)/")))

;; --- :hover and :wait patterns (sl-jsn) ---

(def ^:private hover-pattern
  (re-pattern (str ":([\\w./-]+) hovers over (" locator-re ")")))

;; :wait :duration — "S waits N seconds" (no "for"). N may be an integer
;; or decimal (e.g. "0.5", "1.25"). Sub-second precision lets feature
;; authors express small waits without dropping into millisecond units.
(def ^:private wait-duration-pattern
  #":([\w./-]+) waits (\d+(?:\.\d+)?) seconds?")

;; :wait :for-element — "S waits for O" (locator-re prevents free-text greedy match)
(def ^:private wait-for-element-pattern
  (re-pattern (str ":([\\w./-]+) waits for (" locator-re ")")))

;; :wait :for-text — "S waits for O to show 'TEXT'"
(def ^:private wait-for-text-pattern
  (re-pattern (str ":([\\w./-]+) waits for (" locator-re ") to show (" value-re ")")))

;; :wait :for-count — "S waits for N O" (N is digits, distinguishes from :for-element)
(def ^:private wait-for-count-pattern
  (re-pattern (str ":([\\w./-]+) waits for (\\d+) (" locator-re ")")))

;; -----------------------------------------------------------------------------
;; Helpers
;; -----------------------------------------------------------------------------

(defn- parse-locator
  "Parse locator string from step text.

   Supports two formats:
   - EDN locators: `{:css \"#login\"}`, `{:xpath \"//button\"}`
   - Intent references: `Login.submit`, `Login.submit[1]`

   Detection: strings starting with `{` are treated as EDN locators,
   all other strings are treated as intent references.

   When interface is provided and the locator is an intent reference,
   resolves via the intent system. Otherwise, parses as EDN."
  ([locator-str]
   (parse-locator locator-str nil))
  ([locator-str interface]
   (if (str/starts-with? locator-str "{")
     ;; EDN locator
     (try
       (let [token (edn/read-string locator-str)]
         (locators/resolve-locator token))
       (catch Exception e
         {:errors [{:type :browser/locator-parse-error
                    :message (str "Failed to parse locator: " (ex-message e))
                    :data {:input locator-str}}]}))
     ;; Intent reference
     (if interface
       (let [intents (intent-state/get-intents)
             result (intent-resolve/resolve-intent-string intents locator-str interface)]
         (if (:ok result)
           (locators/resolve-locator (:ok result))
           {:errors [{:type :browser/intent-resolve-error
                      :message (-> result :error :message)
                      :data {:input locator-str
                             :interface interface
                             :error (:error result)}}]}))
       ;; No interface — try to parse as vector shorthand or passthrough
       (try
         (let [token (edn/read-string locator-str)]
           (locators/resolve-locator token))
         (catch Exception _
           ;; Not valid EDN and no interface — return error
           {:errors [{:type :browser/locator-parse-error
                      :message (str "Cannot resolve intent reference without interface: "
                                    locator-str ". Add :interface to step metadata.")
                      :data {:input locator-str}}]}))))))

(defn- retryable-error?
  "Check if exception is a transient error worth retrying the step for. Two
   classes (sl-jxi):

   1. WebDriver/Chrome DOM-in-flux errors — delegated to
      `browser-errors/transient-dom-error?`, the single source of truth shared
      with costume/browser.clj's `session-error?` so the two cannot drift. That
      predicate covers the structured W3C codes (stale element reference, no
      such element, detached shadow root) and the legacy message surfaces,
      including the chromedriver inspector error 'Node with given id does not
      belong to the document' surfaced by the sl-bnk stress run.
   2. ShiftLefter verification-step signals that mean 'not present yet, poll':
      a `:browser/assertion-failed` result, an `:intent/index-out-of-range`
      diagnostic (an indexed intent match not yet in the DOM — same class as
      'no such element' for a wait/verify step), and a
      `:bindings/capture-failure` (the capture step's no-match-yet, sl-zgna —
      same wait-then-assert family; only the capture step throws it inside a
      retry, so SMS's poll-timeout machinery is untouched). These are NOT
      WebDriver errors and are intentionally local to this predicate
      (session-error? must not reconnect on them)."
  [e]
  (let [data (ex-data e)
        err-types (set (map :type (:errors data)))]
    (or (browser-errors/transient-dom-error? e)
        (= :browser/assertion-failed (:type data))
        (= :bindings/capture-failure (:type data))
        (contains? err-types :intent/index-out-of-range))))

(def ^:dynamic *retry-timeout-ms*
  "Rebinding override for the verification-step deadline (sl-99u2). Root
   value nil = defer to the `:timing` resolution ladder (instance >
   interface > global > verify default 3000ms). Bind to override every
   rung, e.g. `(binding [*retry-timeout-ms* 5000] ...)`."
  nil)

(def ^:dynamic *wait-timeout-ms*
  "Rebinding override for the `:wait` await-step deadline (sl-99u2). Root
   value nil = defer to the `:timing` resolution ladder (instance >
   interface > global > await default 5000ms — longer than the verify
   default because await semantics imply the caller is explicitly
   tolerating a slower path). Bind to override every rung."
  nil)

(def ^:private verify-timing-defaults
  "Verify-class bottom rung of the :timing ladder: this ns's historical
   retry budget. Any config rung overrides it."
  {:deadline-ms 3000 :interval-ms 100})

(def ^:private await-timing-defaults
  "Await-class bottom rung of the :timing ladder (:wait frames)."
  {:deadline-ms 5000 :interval-ms 100})

(defn- retry-on-substrate
  "Shared body of `with-retry`/`with-await`: run oracle `f` through the
   poll-until kernel (sl-99u2). Success = `f` returned without throwing
   (its return value is passed through, so assertion-style fns returning
   nil succeed); not-yet = a `retryable-error?` throw. On deadline the
   kernel throws the structured :step/poll-timeout record with the last
   assertion error folded in.

   Error class (sl-kowi): the poll is wrapped in
   `browser-errors/classify-observation` — OUTSIDE the kernel, so a
   transport throw (`:etaoin/http-ex`, which `retryable-error?` never
   matches) propagates immediately with `:error/class :observation`.
   Poll timeouts themselves inherit no class (kernel untouched)."
  [ctx f defaults override-ms]
  (browser-errors/classify-observation
   (fn []
     (:value (step/poll-until ctx (fn [] {:value (f)})
                              {:defaults defaults
                               :deadline-ms override-ms
                               :retry-error? retryable-error?
                               :default-type :web})))))

(defn- with-retry
  "Verification-step oracle retry on the deadline substrate: verify-class
   defaults (3000ms/100ms), `*retry-timeout-ms*` as rebinding override."
  [ctx f]
  (retry-on-substrate ctx f verify-timing-defaults *retry-timeout-ms*))

(defn- with-await
  "Await-step (`:wait` frame) oracle retry on the deadline substrate:
   await-class defaults (5000ms/100ms), `*wait-timeout-ms*` as rebinding
   override."
  [ctx f]
  (retry-on-substrate ctx f await-timing-defaults *wait-timeout-ms*))

;; -----------------------------------------------------------------------------
;; Key Resolution (for press-key step)
;; -----------------------------------------------------------------------------

(def ^:private key-re
  "Regex fragment for the :key O slot (sl-2sj6): an unquoted W3C
   KeyboardEvent.key name (PascalCase ref, validated at plan time against
   the closed set in svo.keys) or a quoted capture ('g' presses that
   character; multi-char is admitted by the pattern so plan validation
   can reject it with a pointer at fills, not a bare no-match).
   Doubling applies here too (sl-jlqz): '''' presses the apostrophe key.
   Chords/sequences: removed at sl-2sj6, parked as sl-eecq."
  (str "[A-Z][A-Za-z0-9]*|" bindings/literal-re-fragment))

(defn- resolve-key-capture
  "Resolve a :key-slot capture (raw, WITH quotes — :key slots are not
   engine-normalized) to the key token press-key! takes: quoted single
   char = that character; unquoted = the validated W3C name itself.
   Vendor translation is the adapter's job (sl-52ly). Plan validation
   (validate-key-object, always-on) catches bad shapes first — the
   throws here are the runtime backstop for direct invocation."
  [capture]
  (if (str/starts-with? capture "'")
    (let [inner (bindings/undouble-quotes (subs capture 1 (dec (count capture))))]
      (if (= 1 (count inner))
        inner
        (throw (ex-info (str "presses takes a single key, not text: " capture
                             " — to enter text, use fills")
                        {:type :browser/invalid-key-literal
                         :key capture}))))
    (if (svo-keys/known-key? capture)
      capture
      (throw (ex-info (str "Unknown key name: '" capture
                           "'. Known keys: " (str/join ", " svo-keys/key-names))
                      {:type :browser/unknown-key
                       :key capture
                       :known-keys svo-keys/key-names})))))

;; -----------------------------------------------------------------------------
;; Debug / Utility Steps
;; -----------------------------------------------------------------------------

;; (Removed in sl-jsn: the subject-less `pause for N seconds` debug step.
;; Use the subject-bearing `:S waits N seconds` (`:wait :duration`) instead.)

;; -----------------------------------------------------------------------------
;; Subject Routing Helper
;; -----------------------------------------------------------------------------

(defn- subject-str->key
  "Normalize a subject string from step text to a keyword, stripping any
   namespace. \"user/alice\" → :alice; \"alice\" → :alice.

   Mirrors the normalization `cap/capability-key` does on subjects so the
   key we look up matches the one provisioning stored under
   (`(name :user/alice)` → \"alice\"); a subject keyword in `:cap/web.alice`
   is found regardless of which form the step text used."
  [s]
  (let [kw (keyword s)]
    (if (namespace kw)
      (keyword (name kw))
      kw)))

(defn- web-subjects-in-ctx
  "Return a vector of subject keywords for every web-TYPED capability entry
   in ctx, in stable iteration order (sl-s2oa: the cap key's interface
   segment is an INSTANCE name; its :type comes from the run-interfaces
   stash, unconfigured names reading as their own type). Bare instance keys
   (no subject) show up as nil and are filtered out — callers want the
   named subjects for diagnostics."
  [ctx]
  (->> (cap/all-capabilities ctx)
       keys
       (keep (fn [k]
               (let [{:keys [interface subject]} (cap/parse-capability-name k)]
                 (when (and (= :web (cap/get-run-interface-type ctx interface))
                            subject)
                   subject))))
       vec))

(defn- with-subject-browser
  "Look up the subject's `:cap/web.<subject>` impl, run op-fn on it.

   Subject string from step text (e.g., \"alice\" or \"user/alice\") is
   normalized via `subject-str->key`. Returns ctx unchanged — the unified
   capability shape is stateless across step boundaries.

   Error class (sl-kowi): the op runs under
   `browser-errors/classify-observation`, so a driver transport throw
   (`:etaoin/http-ex` — no response existed) reaches the runner with
   `:error/class :observation`. Together with `retry-on-substrate` this is
   the whole browser boundary: every built-in browser step routes through
   one of the two."
  [ctx subject-str op-fn]
  (let [subject (subject-str->key subject-str)
        instance (step/interface-instance ctx :web)
        browser (cap/get-capability ctx instance subject)]
    (if browser
      (do (browser-errors/classify-observation #(op-fn browser)) ctx)
      (throw (ex-info (str "No browser session for subject: " subject-str
                           " on interface " instance
                           " — nothing has provisioned a browser for this"
                           " subject. In the REPL: (connect-costume! ...)"
                           " attaches an authenticated costume, or load a"
                           " config with :interfaces (shifted!) to provision"
                           " a bare browser on demand. In a runner project:"
                           " check the :interfaces config.")
                      {:type :browser/no-session
                       :subject subject-str
                       :session-key subject
                       :interface instance
                       :available-sessions (web-subjects-in-ctx ctx)})))))

(defn- resolve-target*
  "Browser-aware resolution of a locator string to a resolved target.

   - EDN locators (`{…}`) resolve purely via `locators/resolve-locator`.
   - Intent references resolve via `browser.intent/resolve-target`, which uses
     the live `browser` to index by the Nth *match* (`[n]`/`[-n]`) and to return
     the whole `[*]` collection — fixing the flat `:nth-child` bug.

   Returns a target (`{:q}` / `{:el}` / `[{:el} …]`) or, on a resolution error,
   throws with the resolver's own specific message(s) lifted into `ex-message`
   (sl-h84) — so a feature-writer sees e.g. \"Feed.post[99]: index 99 is out of
   range — 4 matches found\" instead of a generic string. The structured
   `:errors` stays in `ex-data` for the reporters' detail line. Callers needing a
   single element pass the result through `target/ensure-single`."
  [browser locator-str interface]
  (let [result (cond
                 (str/starts-with? locator-str "{")
                 (parse-locator locator-str interface)

                 interface
                 (browser-intent/resolve-target browser (intent-state/get-intents)
                                                 locator-str interface)

                 :else
                 (parse-locator locator-str interface))]
    (if (and (map? result) (:errors result))
      ;; Lift the resolver's specific message(s) into ex-message — the §5/§7.5
      ;; "loud, specific, actionable" contract is invisible to users otherwise.
      ;; Multiple errors (e.g. nested-resolution) join; empty falls back.
      (let [errs (:errors result)
            msg  (->> errs (keep :message) (str/join "; "))]
        (throw (ex-info (if (str/blank? msg) "Invalid locator" msg)
                        {:errors errs})))
      result)))

(defn- with-subject-locator
  "Subject-extracting variant of with-locator.
   Looks up the subject's browser, resolves the locator against the live DOM,
   executes op-fn with a SINGLE resolved target.

   Interface parameter enables intent reference resolution:
   - If interface is nil, only EDN locators work
   - If interface is provided (e.g., :web), intent refs like Login.submit resolve
     (including `[n]`/`[-n]` Nth-match indexing).

   Action steps are single-target: a `[*]` reference is a loud
   `:browser/target-cardinality` error (via `target/ensure-single`)."
  ([ctx subject-str locator-str op-fn]
   (with-subject-locator ctx subject-str locator-str nil op-fn))
  ([ctx subject-str locator-str interface op-fn]
   (with-subject-browser ctx subject-str
     (fn [browser]
       (let [resolved (resolve-target* browser locator-str interface)]
         (op-fn browser (target/ensure-single resolved locator-str)))))))

(defn- with-subject-query
  "Subject-extracting query helper. Returns [ctx browser] for query use.
   Caller uses the browser for queries; ctx is unchanged."
  [ctx subject-str]
  (let [subject (subject-str->key subject-str)
        instance (step/interface-instance ctx :web)
        browser (cap/get-capability ctx instance subject)]
    (if browser
      [ctx browser]
      (throw (ex-info (str "No browser session for subject: " subject-str
                           " on interface " instance
                           " — nothing has provisioned a browser for this"
                           " subject. In the REPL: (connect-costume! ...)"
                           " attaches an authenticated costume, or load a"
                           " config with :interfaces (shifted!) to provision"
                           " a bare browser on demand. In a runner project:"
                           " check the :interfaces config.")
                      {:type :browser/no-session
                       :subject subject-str
                       :session-key subject
                       :interface instance
                       :available-sessions (web-subjects-in-ctx ctx)})))))

(defn- unquote-literal
  "Strip the single quotes off a quoted location-slot capture ('…' → …)
   and collapse SQL-style doubled interior quotes (sl-jlqz) — the exact
   inverse of the engine's token quote-wrap. Non-quoted values pass
   through unchanged."
  [s]
  (if (and (string? s)
           (>= (count s) 2)
           (str/starts-with? s "'")
           (str/ends-with? s "'"))
    (bindings/undouble-quotes (subs s 1 (dec (count s))))
    s))

(defn- resolve-location-object
  "Resolve a :location-slot object under the authored rule (sl-iseq):
   QUOTED = LITERAL, ALWAYS ('…' is stripped); BARE = REF — a bare PascalCase
   token (per `intent-resolve/location-ref?`, the classifier shared with SVO
   validation) resolves via the intent's :location :path + the :web
   interface's :config :base-url through `intent-resolve/resolve-location` —
   the assembly point for REFS (sl-4mv8).

   opts (sl-34ec):
   - :resolve-literals? — when true, a stripped SCHEMELESS literal resolves
     per RFC 3986 against :base-url via `url-match/resolve-literal` (the
     assembly point for LITERALS); absolute literals pass through untouched.
     Navigation sets this (it needs a full URL). The region assertion does
     NOT: region-match discards scheme/host by design, so literals there
     stay verbatim — resolution would be a no-op when it works and a
     breakage when no :base-url is configured (Chair ruling 2026-07-23).

   Throws structured ex-info when a ref or literal doesn't resolve."
  ([ctx object-str]
   (resolve-location-object ctx object-str nil))
  ([ctx object-str {:keys [resolve-literals?]}]
   ;; sl-s2oa: base-url comes from the resolved interface INSTANCE (each
   ;; instance is its own world); the intent :location lookup stays keyed
   ;; by the instance's TYPE (intent artifacts are vocabulary-scoped).
   (let [instance (step/interface-instance ctx :web)]
     (if-not (intent-resolve/location-ref? object-str)
       (let [literal (unquote-literal object-str)]
         (if-not resolve-literals?
           literal
           (let [base-url (:base-url (cap/get-run-interface-config ctx instance))
                 result (url-match/resolve-literal literal base-url)]
             (if (:error result)
               (throw (ex-info (get-in result [:error :message]) (:error result)))
               (:ok result)))))
       (let [base-url (:base-url (cap/get-run-interface-config ctx instance))
             iface-type (cap/get-run-interface-type ctx instance)
             result (intent-resolve/resolve-location (intent-state/get-intents)
                                                     object-str iface-type base-url)]
         (if (:error result)
           (throw (ex-info (get-in result [:error :message]) (:error result)))
           (:ok result)))))))

;; =============================================================================
;; Subject-Extracting Action Steps
;; =============================================================================

;; --- Kernel Actions (0.2.x) ---

(defstep (re-pattern (str ":([\\w./-]+) opens the browser to (" location-re ")"))
  {:interface :web
   :svo {:subject :$1 :verb :navigate :frame :to :object :$2}
   :doc/example ":alice opens the browser to '/login'"}
  [ctx subject-str url]
  ;; Navigation needs a full URL: schemeless literals resolve against
  ;; :base-url (sl-34ec); refs and absolute literals as before.
  ;; sl-kowi rider: THE ONE site where a driver ANSWER is classified — a
  ;; navigation refused by the network (net::ERR_CONNECTION_REFUSED & co.,
  ;; the closed set in browser.errors) means no page existed to observe:
  ;; :observation. Never applied to element lookups.
  (let [url' (resolve-location-object ctx url {:resolve-literals? true})]
    (with-subject-browser ctx subject-str
      (fn [browser]
        (browser-errors/classify-navigation #(bp/open-to! browser url') url')))))

(defstep click-pattern
  {:interface :web
   :svo {:subject :$1 :verb :click :frame :default :object :$2}
   :doc/example ":alice clicks {:id \"login\"}"}
  [ctx subject-str locator-str]
  (with-subject-locator ctx subject-str locator-str (:interface step-meta)
    (fn [browser resolved] (bp/click! browser resolved))))

(defstep doubleclick-pattern
  {:interface :web
   :svo {:subject :$1 :verb :doubleclick :frame :default :object :$2}
   :doc/example ":alice double-clicks {:id \"row\"}"}
  [ctx subject-str locator-str]
  (with-subject-locator ctx subject-str locator-str (:interface step-meta)
    (fn [browser resolved] (bp/doubleclick! browser resolved))))

(defstep rightclick-pattern
  {:interface :web
   :svo {:subject :$1 :verb :rightclick :frame :default :object :$2}
   :doc/example ":alice right-clicks {:id \"file\"}"}
  [ctx subject-str locator-str]
  (with-subject-locator ctx subject-str locator-str (:interface step-meta)
    (fn [browser resolved] (bp/rightclick! browser resolved))))

(defstep move-to-pattern
  {:interface :web
   :svo {:subject :$1 :verb :move :frame :default :object :$2}
   :doc/example ":alice moves to {:id \"menu\"}"}
  [ctx subject-str locator-str]
  (with-subject-locator ctx subject-str locator-str (:interface step-meta)
    (fn [browser resolved] (bp/move-to! browser resolved))))

;; :hover delegates to the same kernel op as :move — webdriver/etaoin have no
;; separate hover primitive (mouse-move-to-element triggers hover behavior).
;; The verb-level distinction is for human-readable feature text.
(defstep hover-pattern
  {:interface :web
   :svo {:subject :$1 :verb :hover :frame :default :object :$2}
   :doc/example ":alice hovers over {:id \"menu\"}"}
  [ctx subject-str locator-str]
  (with-subject-locator ctx subject-str locator-str (:interface step-meta)
    (fn [browser resolved] (bp/move-to! browser resolved))))

(defstep drag-pattern
  {:interface :web
   :svo {:subject :$1 :verb :drag :frame :to :object :$2 :args {:target :$3}}
   :doc/example ":alice drags {:id \"card\"} to {:id \"done-column\"}"}
  [ctx subject-str from-str to-str]
  (let [interface (:interface step-meta)]
    (with-subject-browser ctx subject-str
      (fn [browser]
        (let [from (target/ensure-single (resolve-target* browser from-str interface) from-str)
              to   (target/ensure-single (resolve-target* browser to-str interface) to-str)]
          (bp/drag-to! browser from to))))))

(defstep fill-pattern
  {:interface :web
   :svo {:subject :$1 :verb :fill :frame :with :object :$2 :args {:value :$3}}
   :doc/example ":alice fills {:id \"email\"} with 'alice@example.com'"}
  [ctx subject-str locator-str text]
  (with-subject-locator ctx subject-str locator-str (:interface step-meta)
    (fn [browser resolved] (bp/fill! browser resolved text))))

;; --- Navigation Actions (0.3.6) ---

(defstep #":([\w./-]+) goes back"
  {:interface :web
   :svo {:subject :$1 :verb :navigate-back :frame :default :object nil}
   :doc/example ":alice goes back"}
  [ctx subject-str]
  (with-subject-browser ctx subject-str
    (fn [browser] (bp/go-back! browser))))

(defstep #":([\w./-]+) goes forward"
  {:interface :web
   :svo {:subject :$1 :verb :navigate-forward :frame :default :object nil}
   :doc/example ":alice goes forward"}
  [ctx subject-str]
  (with-subject-browser ctx subject-str
    (fn [browser] (bp/go-forward! browser))))

(defstep #":([\w./-]+) refreshes the page"
  {:interface :web
   :svo {:subject :$1 :verb :refresh :frame :default :object nil}
   :doc/example ":alice refreshes the page"}
  [ctx subject-str]
  (with-subject-browser ctx subject-str
    (fn [browser] (bp/refresh! browser))))

;; --- Scrolling Actions (0.3.6) ---

(defstep scroll-to-pattern
  {:interface :web
   :svo {:subject :$1 :verb :scroll :frame :to-element :object :$2}
   :doc/example ":alice scrolls to {:id \"footer\"}"}
  [ctx subject-str locator-str]
  (with-subject-locator ctx subject-str locator-str (:interface step-meta)
    (fn [browser resolved] (bp/scroll-to! browser resolved))))

(defstep #":([\w./-]+) scrolls to the (top|bottom)"
  {:interface :web
   :svo {:subject :$1 :verb :scroll :frame :to-position
         :object nil :args {:position :$2}}
   :doc/example ":alice scrolls to the bottom"}
  [ctx subject-str position-str]
  (with-subject-browser ctx subject-str
    (fn [browser] (bp/scroll-to-position! browser (keyword position-str)))))

;; --- Form Actions (0.3.6) ---

(defstep clear-pattern
  {:interface :web
   :svo {:subject :$1 :verb :clear :frame :default :object :$2}
   :doc/example ":alice clears {:id \"search\"}"}
  [ctx subject-str locator-str]
  (with-subject-locator ctx subject-str locator-str (:interface step-meta)
    (fn [browser resolved] (bp/clear! browser resolved))))

(defstep select-from-pattern
  {:interface :web
   :svo {:subject :$1 :verb :select :frame :from
         :object :$3 :args {:value :$2}}
   :doc/example ":alice selects 'Blue' from {:id \"color\"}"}
  [ctx subject-str text locator-str]
  (with-subject-locator ctx subject-str locator-str (:interface step-meta)
    (fn [browser resolved] (bp/select! browser resolved text))))

(defstep (re-pattern (str ":([\\w./-]+) presses (" key-re ")"))
  {:interface :web
   :svo {:subject :$1 :verb :press :frame :default :object :$2}
   :doc/example ":alice presses Enter"}
  [ctx subject-str key-capture]
  (let [key-str (resolve-key-capture key-capture)]
    (with-subject-browser ctx subject-str
      (fn [browser] (bp/press-key! browser key-str)))))

;; --- Alert Actions (0.3.6) ---

(defstep #":([\w./-]+) accepts the alert"
  {:interface :web
   :svo {:subject :$1 :verb :accept-alert :frame :default :object nil}
   :doc/example ":alice accepts the alert"}
  [ctx subject-str]
  (with-subject-browser ctx subject-str
    (fn [browser] (bp/accept-alert! browser))))

(defstep #":([\w./-]+) dismisses the alert"
  {:interface :web
   :svo {:subject :$1 :verb :dismiss-alert :frame :default :object nil}
   :doc/example ":alice dismisses the alert"}
  [ctx subject-str]
  (with-subject-browser ctx subject-str
    (fn [browser] (bp/dismiss-alert! browser))))

;; --- Window Management Actions (0.3.6) ---

(defstep #":([\w./-]+) maximizes the window"
  {:interface :web
   :svo {:subject :$1 :verb :maximize :frame :default :object nil}
   :doc/example ":alice maximizes the window"}
  [ctx subject-str]
  (with-subject-browser ctx subject-str
    (fn [browser] (bp/maximize-window! browser))))

(defstep #":([\w./-]+) resizes the window to (\d+)x(\d+)"
  {:interface :web
   :svo {:subject :$1 :verb :resize :frame :dimensions
         :object nil :args {:width :$2 :height :$3}}
   :doc/example ":alice resizes the window to 1280x800"}
  [ctx subject-str width-str height-str]
  (with-subject-browser ctx subject-str
    (fn [browser] (bp/set-window-size! browser
                                        (parse-long width-str)
                                        (parse-long height-str)))))

(defstep #":([\w./-]+) switches to the next window"
  {:interface :web
   :svo {:subject :$1 :verb :switch-window :frame :default :object nil}
   :doc/example ":alice switches to the next window"}
  [ctx subject-str]
  (with-subject-browser ctx subject-str
    (fn [browser] (bp/switch-to-next-window! browser))))

;; --- Frame Actions (0.3.6) ---

(defstep switch-frame-pattern
  {:interface :web
   :svo {:subject :$1 :verb :switch-frame :frame :into :object :$2}
   :doc/example ":alice switches to frame {:id \"payment\"}"}
  [ctx subject-str locator-str]
  (with-subject-locator ctx subject-str locator-str (:interface step-meta)
    (fn [browser resolved] (bp/switch-to-frame! browser resolved))))

(defstep #":([\w./-]+) switches to the main frame"
  {:interface :web
   :svo {:subject :$1 :verb :switch-frame :frame :main :object nil}
   :doc/example ":alice switches to the main frame"}
  [ctx subject-str]
  (with-subject-browser ctx subject-str
    (fn [browser] (bp/switch-to-main-frame! browser))))

;; =============================================================================
;; Subject-Extracting Verification Steps
;; =============================================================================

;; --- Existing Verification Steps (0.3.5) ---

(defstep #":([\w./-]+) should see '((?:[^']|'')+)'"
  {:interface :web
   :svo {:subject :$1 :verb :see :frame :on-page :object :$2}
   :doc/example ":alice should see 'Welcome'"}
  [ctx subject-str expected-text]
  ;; The capture is the literal's INTERIOR (no :object-kind on :see/:on-page,
  ;; so the engine never normalizes it) — undouble here (sl-jlqz).
  (let [expected-text (bindings/undouble-quotes expected-text)
        [ctx' browser] (with-subject-query ctx subject-str)
        body-locator (locators/resolve-locator {:tag "body"})]
    (with-retry ctx'
      (fn []
        (let [actual (bp/get-text browser body-locator)]
          (when-not (.contains (str actual) expected-text)
            (throw (ex-info (str "Expected to see text '" expected-text "' on page but did not find it")
                             {:type :browser/assertion-failed
                              :expected expected-text
                              :actual (if (> (count actual) 200)
                                        (str (subs actual 0 200) "...")
                                        actual)}))))))
    ctx'))

(defstep should-see-count-pattern
  {:interface :web
   :svo {:subject :$1 :verb :see :frame :count
         :object :$3 :args {:count :$2}}
   :doc/example ":alice should see 3 {:css \".row\"} elements"}
  [ctx subject-str count-str locator-str]
  (let [expected (parse-long count-str)
        [ctx' browser] (with-subject-query ctx subject-str)]
    (with-retry ctx'
      (fn []
        (let [resolved (resolve-target* browser locator-str (:interface step-meta))
              actual (if (target/targets? resolved)
                       (count resolved)              ; [*] collection — count the vector
                       (bp/element-count browser resolved))]
          (when-not (= expected actual)
            (throw (ex-info (str "Expected " expected " elements but found " actual)
                             {:type :browser/assertion-failed
                              :expected expected
                              :actual actual
                              :locator locator-str}))))))
    ctx'))

(defstep should-see-text-pattern
  {:interface :web
   :svo {:subject :$1 :verb :see :frame :text
         :object :$2 :args {:text :$3}}
   :doc/example ":alice should see {:id \"banner\"} with text 'Success'"}
  [ctx subject-str locator-str expected-text]
  (let [[ctx' browser] (with-subject-query ctx subject-str)]
    (with-retry ctx'
      (fn []
        (let [resolved (target/ensure-single
                        (resolve-target* browser locator-str (:interface step-meta))
                        locator-str)
              actual (bp/get-text browser resolved)]
          (when-not (.contains (str actual) expected-text)
            (throw (ex-info (str "Expected element text to contain '" expected-text
                                 "' but was '" actual "'")
                             {:type :browser/assertion-failed
                              :expected expected-text
                              :actual actual
                              :locator locator-str}))))))
    ctx'))

;; :capture (sl-zgna) — assert-plus-bind: read the element's text (same
;; wait-then-assert semantics as the should-see family), match the pattern,
;; merge its (?<name>...) named groups into the scenario data plane. The
;; matcher slot reaches this fn untouched (frame :arg-kinds :matcher);
;; embedded {binding} tokens interpolate as regex-quoted literals inside
;; attempt-capture. A no-match throws :bindings/capture-failure — retryable
;; here (the DOM may still be settling), surfacing on exhaustion with the
;; pattern, an excerpt of the text actually seen, and the locator: it fails
;; as though the subject could not see that text. An unresolved embedded
;; token is NOT retryable and fails fast.
(defstep capture-pattern
  {:interface :web
   :svo {:subject :$1 :verb :capture :frame :default
         :object :$2 :args {:match :$3}}
   :doc/example ":alice captures {:id \"total\"} matching /(?<amount>[0-9.]+)/"}
  [ctx subject-str locator-str match-str]
  (let [[ctx' browser] (with-subject-query ctx subject-str)]
    (with-retry ctx'
      (fn []
        (let [resolved (target/ensure-single
                        (resolve-target* browser locator-str (:interface step-meta))
                        locator-str)
              actual (bp/get-text browser resolved)]
          (try
            (bindings/capture! ctx' match-str actual)
            (catch clojure.lang.ExceptionInfo e
              (if (= :bindings/capture-failure (:type (ex-data e)))
                (throw (ex-info (ex-message e)
                                (assoc (ex-data e) :locator locator-str)
                                e))
                (throw e)))))))))

;; :see :matching (sl-gwru) — assert-only: the :capture exec verbatim
;; (same attempt-capture core, so the two frames can never drift into a
;; second matcher path) with the bound ctx DISCARDED — asserts, binds
;; nothing. Format assertions (order-number shapes, prices, dates) get a
;; frame that doesn't pollute the binding namespace. Named groups in
;; this slot are rejected at plan time by the bindings lint (clause 4:
;; a producer marker is never quietly ignored); all-unnamed is the
;; expected shape. Embedded {binding} tokens still interpolate — an
;; unresolved one fails fast, non-retryable, exactly like :capture.
(defstep should-see-matching-pattern
  {:interface :web
   :svo {:subject :$1 :verb :see :frame :matching
         :object :$2 :args {:match :$3}}
   :doc/example ":alice should see {:id \"total\"} matching /\\d+ items/"}
  [ctx subject-str locator-str match-str]
  (let [[ctx' browser] (with-subject-query ctx subject-str)]
    (with-retry ctx'
      (fn []
        (let [resolved (target/ensure-single
                        (resolve-target* browser locator-str (:interface step-meta))
                        locator-str)
              actual (bp/get-text browser resolved)]
          (when-not (bindings/attempt-capture ctx' match-str actual)
            (throw (ex-info (str "Expected element text to match /" match-str
                                 "/ but it did not")
                            {:type :browser/assertion-failed
                             :pattern match-str
                             :actual actual
                             :locator locator-str}))))))
    ctx'))

(defstep should-see-value-pattern
  {:interface :web
   :svo {:subject :$1 :verb :see :frame :value
         :object :$2 :args {:value :$3}}
   :doc/example ":alice should see {:id \"qty\"} with value '3'"}
  [ctx subject-str locator-str expected-value]
  (let [[ctx' browser] (with-subject-query ctx subject-str)]
    (with-retry ctx'
      (fn []
        (let [resolved (target/ensure-single
                        (resolve-target* browser locator-str (:interface step-meta))
                        locator-str)
              actual (bp/get-value browser resolved)]
          (when-not (= expected-value actual)
            (throw (ex-info (str "Expected element value '" expected-value
                                 "' but was '" actual "'")
                             {:type :browser/assertion-failed
                              :expected expected-value
                              :actual actual
                              :locator locator-str}))))))
    ctx'))

(defstep should-see-attr-pattern
  {:interface :web
   :svo {:subject :$1 :verb :see :frame :attribute
         :object :$2 :args {:attribute :$3 :value :$4}}
   :doc/example ":alice should see {:id \"link\"} with attribute 'href' equal to '/home'"}
  [ctx subject-str locator-str attr-name expected-value]
  (let [[ctx' browser] (with-subject-query ctx subject-str)]
    (with-retry ctx'
      (fn []
        (let [resolved (target/ensure-single
                        (resolve-target* browser locator-str (:interface step-meta))
                        locator-str)
              actual (bp/get-attribute browser resolved attr-name)]
          (when-not (= expected-value actual)
            (throw (ex-info (str "Expected attribute '" attr-name "' to be '" expected-value
                                 "' but was '" actual "'")
                             {:type :browser/assertion-failed
                              :expected expected-value
                              :actual actual
                              :attribute attr-name
                              :locator locator-str}))))))
    ctx'))

(defstep should-see-enabled-pattern
  {:interface :web
   :svo {:subject :$1 :verb :see :frame :enabled :object :$2}
   :doc/example ":alice should see {:id \"submit\"} enabled"}
  [ctx subject-str locator-str]
  (let [[ctx' browser] (with-subject-query ctx subject-str)]
    (with-retry ctx'
      (fn []
        (let [resolved (target/ensure-single
                        (resolve-target* browser locator-str (:interface step-meta))
                        locator-str)]
          (when-not (bp/enabled? browser resolved)
            (throw (ex-info (str "Expected element to be enabled: " locator-str)
                             {:type :browser/assertion-failed
                              :locator locator-str}))))))
    ctx'))

(defstep should-see-disabled-pattern
  {:interface :web
   :svo {:subject :$1 :verb :see :frame :disabled :object :$2}
   :doc/example ":alice should see {:id \"submit\"} disabled"}
  [ctx subject-str locator-str]
  (let [[ctx' browser] (with-subject-query ctx subject-str)]
    (with-retry ctx'
      (fn []
        (let [resolved (target/ensure-single
                        (resolve-target* browser locator-str (:interface step-meta))
                        locator-str)]
          (when (bp/enabled? browser resolved)
            (throw (ex-info (str "Expected element to be disabled: " locator-str)
                             {:type :browser/assertion-failed
                              :locator locator-str}))))))
    ctx'))

(defstep should-see-locator-pattern
  {:interface :web
   :svo {:subject :$1 :verb :see :frame :visible :object :$2}
   :doc/example ":alice should see {:id \"profile\"}"}
  [ctx subject-str locator-str]
  (let [[ctx' browser] (with-subject-query ctx subject-str)]
    (with-retry ctx'
      (fn []
        (let [resolved (target/ensure-single
                        (resolve-target* browser locator-str (:interface step-meta))
                        locator-str)]
          (when-not (bp/visible? browser resolved)
            (throw (ex-info (str "Expected element to be visible: " locator-str)
                             {:type :browser/assertion-failed
                              :locator locator-str}))))))
    ctx'))

(defstep should-not-see-pattern
  {:interface :web
   :svo {:subject :$1 :verb :not-see :frame :invisible :object :$2}
   :doc/example ":alice should not see {:id \"error\"}"}
  [ctx subject-str locator-str]
  (let [[ctx' browser] (with-subject-query ctx subject-str)
        resolved (try
                   (target/ensure-single
                    (resolve-target* browser locator-str (:interface step-meta))
                    locator-str)
                   (catch clojure.lang.ExceptionInfo e
                     ;; An out-of-range / absent indexed match means the element
                     ;; isn't present — exactly what "should not see" asserts.
                     (let [errs (:errors (ex-data e))]
                       (if (some #(#{:intent/index-out-of-range :intent/zero-index} (:type %)) errs)
                         ::absent
                         (throw e)))))]
    (when (and (not= ::absent resolved) (bp/visible? browser resolved))
      (throw (ex-info (str "Expected element to NOT be visible: " locator-str)
                       {:type :browser/assertion-failed
                        :locator locator-str})))
    ctx'))

;; Region-level location assertion (sl-q81m ruling): the object is a BARE
;; intent ref (resolved via the intent's :location + :base-url) or a QUOTED
;; literal URL/path (sl-iseq: quoted = literal, always; bare = ref);
;; match = normalized path + fragment, query stripped, host ignored. For
;; structural full-URL equality use "should be on exactly".
(defstep (re-pattern (str ":([\\w./-]+) should be on (" location-re ")"))
  {:interface :web
   :svo {:subject :$1 :verb :be :frame :at :object :$2}
   :doc/example ":alice should be on '/dashboard'"}
  [ctx subject-str expected]
  (let [expected-url (resolve-location-object ctx expected)
        [ctx' browser] (with-subject-query ctx subject-str)]
    (with-retry ctx'
      (fn []
        (let [actual (str (bp/get-url browser))]
          (when-let [mismatch (url-match/region-match expected-url actual)]
            (throw (ex-info (:message mismatch)
                            (assoc mismatch :type :browser/assertion-failed)))))))
    ctx'))

;; Structural full-URL equality (sl-q81m): scheme+host + exact path + exact
;; fragment + query as an ordered multimap (cross-key order insignificant,
;; duplicate-key value order significant). The object is a quoted literal —
;; captured WITH its quotes, sl-yh7/sl-ka80: quoted = literal, always — or a
;; captured {binding} token (magic-link flows; the engine resolves it and
;; quote-wraps, so both arrive quoted and are unquoted here). Named-location
;; refs stay excluded: resolution IS normalization, so a ref is nonsensical
;; under structural equality. Percent-encoding and host-case rules are
;; deferred: exotic matching = custom step assertion.
(defstep (re-pattern (str ":([\\w./-]+) should be on exactly (" value-re ")"))
  {:interface :web
   :svo {:subject :$1 :verb :be :frame :at-exactly :object :$2}
   :doc/example ":alice should be on exactly '/dashboard?tab=1'"}
  [ctx subject-str expected]
  ;; sl-34ec: a schemeless literal resolves against :base-url first, making
  ;; relative exact expectations expressible ('/login?a=1' without hardcoding
  ;; a host). Without a usable :base-url the RAW literal goes to exact-match,
  ;; whose :not-a-full-url mismatch teaches the exits; a configured-but-
  ;; unparseable :base-url is a config error and throws.
  (let [literal (unquote-literal expected)
        base-url (:base-url (cap/get-run-interface-config
                             ctx (step/interface-instance ctx :web)))
        resolved (url-match/resolve-literal literal base-url)
        expected-url (cond
                       (:ok resolved)
                       (:ok resolved)

                       (= :intent/invalid-base-url
                          (get-in resolved [:error :type]))
                       (throw (ex-info (get-in resolved [:error :message])
                                       (:error resolved)))

                       :else literal)
        [ctx' browser] (with-subject-query ctx subject-str)]
    (with-retry ctx'
      (fn []
        (let [actual (str (bp/get-url browser))]
          (when-let [mismatch (url-match/exact-match expected-url actual)]
            (throw (ex-info (:message mismatch)
                            (assoc mismatch :type :browser/assertion-failed)))))))
    ctx'))

;; The title O slot is :object-kind :text (sl-2sj6): captured WITH its
;; quotes via value-re — quoted literal or {binding} token — and
;; normalized by the engine's :text slot-kind (strip quotes / resolve
;; token), so expected-title arrives here as plain text. The old pattern
;; stripped quotes in the capture group, which destroyed the marker
;; before the engine saw it (the clause-4 violation behind the
;; strict-mode false positive).
(defstep (re-pattern (str ":([\\w./-]+) should see the title (" value-re ")"))
  {:interface :web
   :svo {:subject :$1 :verb :see :frame :title :object :$2}
   :doc/example ":alice should see the title 'Home'"}
  [ctx subject-str expected-title]
  (let [[ctx' browser] (with-subject-query ctx subject-str)]
    (with-retry ctx'
      (fn []
        (let [actual (bp/get-title browser)]
          (when-not (= expected-title actual)
            (throw (ex-info (str "Expected page title '" expected-title "' but was '" actual "'")
                             {:type :browser/assertion-failed
                              :expected expected-title
                              :actual actual}))))))
    ctx'))

;; --- Alert Verification Steps (0.3.6) ---

(defstep (re-pattern (str ":([\\w./-]+) should see an alert with (" value-re ")"))
  {:interface :web
   :svo {:subject :$1 :verb :see :frame :alert-with-text
         :object nil :args {:text :$2}}
   :doc/example ":alice should see an alert with 'Are you sure?'"}
  [ctx subject-str expected-text]
  (let [[ctx' browser] (with-subject-query ctx subject-str)]
    (with-retry ctx'
      (fn []
        (let [actual (bp/get-alert-text browser)]
          (when-not (.contains (str actual) expected-text)
            (throw (ex-info (str "Expected alert text to contain '" expected-text
                                 "' but was '" actual "'")
                             {:type :browser/assertion-failed
                              :expected expected-text
                              :actual actual}))))))
    ctx'))

(defstep #":([\w./-]+) should see an alert"
  {:interface :web
   :svo {:subject :$1 :verb :see :frame :alert :object nil}
   :doc/example ":alice should see an alert"}
  [ctx subject-str]
  (let [[ctx' browser] (with-subject-query ctx subject-str)]
    (with-retry ctx'
      (fn []
        (try
          (bp/get-alert-text browser)
          (catch Exception _
            (throw (ex-info "Expected an alert to be present but none was found"
                             {:type :browser/assertion-failed}))))))
    ctx'))

;; =============================================================================
;; Await Steps (sl-jsn) — :wait verb frames
;; =============================================================================

;; :wait :duration — sleep for DURATION seconds (decimal allowed for
;; sub-second precision). Implicit-object :time per glossary; no browser
;; session required (purely time-based).
(defstep wait-duration-pattern
  {:interface :web
   :svo {:subject :$1 :verb :wait :frame :duration
         :object nil :args {:duration :$2}}
   :doc/example ":alice waits 2 seconds"}
  [ctx _subject-str seconds-str]
  (Thread/sleep (long (* 1000 (Double/parseDouble seconds-str))))
  ctx)

;; :wait :for-element — block until element becomes visible (mirror of :see :visible).
(defstep wait-for-element-pattern
  {:interface :web
   :svo {:subject :$1 :verb :wait :frame :for-element :object :$2}
   :doc/example ":alice waits for {:id \"spinner\"}"}
  [ctx subject-str locator-str]
  (let [[ctx' browser] (with-subject-query ctx subject-str)]
    (with-await ctx'
      (fn []
        (let [resolved (target/ensure-single
                        (resolve-target* browser locator-str (:interface step-meta))
                        locator-str)]
          (when-not (bp/visible? browser resolved)
            (throw (ex-info (str "Waited but element never became visible: " locator-str)
                            {:type    :browser/assertion-failed
                             :locator locator-str}))))))
    ctx'))

;; :wait :for-text — block until element contains the expected text
;; (mirror of :see :text).
(defstep wait-for-text-pattern
  {:interface :web
   :svo {:subject :$1 :verb :wait :frame :for-text
         :object :$2 :args {:text :$3}}
   :doc/example ":alice waits for {:id \"status\"} to show 'Done'"}
  [ctx subject-str locator-str expected-text]
  (let [[ctx' browser] (with-subject-query ctx subject-str)]
    (with-await ctx'
      (fn []
        (let [resolved (target/ensure-single
                        (resolve-target* browser locator-str (:interface step-meta))
                        locator-str)
              actual (bp/get-text browser resolved)]
          (when-not (.contains (str actual) expected-text)
            (throw (ex-info (str "Waited but element text never contained '" expected-text
                                 "' (last seen: '" actual "')")
                            {:type     :browser/assertion-failed
                             :expected expected-text
                             :actual   actual
                             :locator  locator-str}))))))
    ctx'))

;; :wait :for-count — block until N matching elements exist (mirror of :see :count).
(defstep wait-for-count-pattern
  {:interface :web
   :svo {:subject :$1 :verb :wait :frame :for-count
         :object :$3 :args {:count :$2}}
   :doc/example ":alice waits for 3 {:css \".result\"}"}
  [ctx subject-str count-str locator-str]
  (let [expected (parse-long count-str)
        [ctx' browser] (with-subject-query ctx subject-str)]
    (with-await ctx'
      (fn []
        (let [resolved (resolve-target* browser locator-str (:interface step-meta))
              actual (if (target/targets? resolved)
                       (count resolved)
                       (bp/element-count browser resolved))]
          (when-not (= expected actual)
            (throw (ex-info (str "Waited but element count never reached " expected
                                 " (last seen: " actual ")")
                            {:type     :browser/assertion-failed
                             :expected expected
                             :actual   actual
                             :locator  locator-str}))))))
    ctx'))
