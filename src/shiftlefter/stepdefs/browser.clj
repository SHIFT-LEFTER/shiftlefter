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
   - `:subject presses <key>` (supports chords: `shift+control+t`)
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

   ## Await Steps (sl-jsn)

   - `:subject waits <N> seconds`                       — :wait :duration
       (N may be decimal, e.g. `0.5` for 500ms)
   - `:subject waits for {<locator>}`                   — :wait :for-element
   - `:subject waits for {<locator>} to show '<text>'`  — :wait :for-text
   - `:subject waits for <N> {<locator>}`               — :wait :for-count

   :wait frames are await-style versions of the corresponding :see frames.
   They retry against the live DOM until the condition is met or
   `*wait-timeout-ms*` (default 5000ms) elapses, throwing a timeout error.

   ## Retry Policy

   Verification steps (`should see`, `should be on`, etc.) retry on
   transient browser errors for up to `*retry-timeout-ms*` (default 3000ms).
   Await steps (`:wait` frames) retry against `*wait-timeout-ms*` (default
   5000ms). Both use 100ms backoff. Retryable errors: stale element
   references, missing elements, and assertion failures
   (`:browser/assertion-failed`).

   Exceptions:
   - `should not see` does NOT retry (negation is instant — if visible now,
     waiting for disappearance is a different operation)
   - Action steps NEVER retry (mutations are not idempotent)

   Bind `*retry-timeout-ms*` or `*wait-timeout-ms*` to override the defaults.

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
            [shiftlefter.capabilities.ctx :as cap]
            [shiftlefter.browser.locators :as locators]
            [shiftlefter.browser.protocol :as bp]
            [shiftlefter.intent.state :as intent-state]
            [shiftlefter.intent.resolve :as intent-resolve]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [etaoin.keys :as k]))

;; -----------------------------------------------------------------------------
;; Locator Pattern
;; -----------------------------------------------------------------------------

(def ^:private locator-re
  "Regex pattern string for capturing locators in step text.

   Matches:
   - Intent references: Login.submit, Login.submit[1], Login.submit[-1], Login.submit[*]
   - EDN maps: {:css \"#foo\"}, {:id \"bar\"}

   NOTE: Vector shorthand ([...]) is deprecated and no longer matched."
  "[A-Z][A-Za-z0-9_-]*\\.[a-z][a-z0-9_-]*(?:\\[-?\\d+\\]|\\[\\*\\])?|\\{[^}]+\\}")

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
  (re-pattern (str ":([\\w./-]+) fills (" locator-re ") with '([^']+)'")))

(def ^:private scroll-to-pattern
  (re-pattern (str ":([\\w./-]+) scrolls to (" locator-re ")")))

(def ^:private clear-pattern
  (re-pattern (str ":([\\w./-]+) clears (" locator-re ")")))

(def ^:private select-from-pattern
  (re-pattern (str ":([\\w./-]+) selects '([^']+)' from (" locator-re ")")))

(def ^:private switch-frame-pattern
  (re-pattern (str ":([\\w./-]+) switches to frame (" locator-re ")")))

(def ^:private should-see-count-pattern
  (re-pattern (str ":([\\w./-]+) should see (\\d+) (" locator-re ") elements")))

(def ^:private should-see-text-pattern
  (re-pattern (str ":([\\w./-]+) should see (" locator-re ") with text '([^']+)'")))

(def ^:private should-see-value-pattern
  (re-pattern (str ":([\\w./-]+) should see (" locator-re ") with value '([^']+)'")))

(def ^:private should-see-attr-pattern
  (re-pattern (str ":([\\w./-]+) should see (" locator-re ") with attribute '([^']+)' equal to '([^']+)'")))

(def ^:private should-see-enabled-pattern
  (re-pattern (str ":([\\w./-]+) should see (" locator-re ") enabled")))

(def ^:private should-see-disabled-pattern
  (re-pattern (str ":([\\w./-]+) should see (" locator-re ") disabled")))

(def ^:private should-see-locator-pattern
  (re-pattern (str ":([\\w./-]+) should see (" locator-re ")")))

(def ^:private should-not-see-pattern
  (re-pattern (str ":([\\w./-]+) should not see (" locator-re ")")))

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
  (re-pattern (str ":([\\w./-]+) waits for (" locator-re ") to show '([^']+)'")))

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
  "Check if exception is a transient browser error worth retrying.
   Includes: stale element, no such element, assertion failures, and
   the Chrome navigation-race inspector error.

   The 'Node with given id does not belong to the document' string is
   chromedriver's surface for an element handle being resolved against
   one DOM and queried after the DOM has been swapped (e.g. the click
   that triggers navigation has fired but the element was looked up on
   the previous page). Conceptually the same as a stale-element
   reference — Chrome just surfaces it via a different error path.
   Surfaced by sl-bnk stress run; deeper audit tracked separately."
  [e]
  (let [msg (str (ex-message e) " " (ex-data e))
        data (ex-data e)]
    (or (.contains msg "stale element")
        (.contains msg "StaleElementReferenceException")
        (.contains msg "no such element")
        (.contains msg "Node with given id does not belong to the document")
        (= :browser/assertion-failed (:type data)))))

(def ^:dynamic *retry-timeout-ms*
  "Default timeout in milliseconds for verification step retries.
   Bind to override, e.g. `(binding [*retry-timeout-ms* 5000] ...)`."
  3000)

(def ^:dynamic *wait-timeout-ms*
  "Default timeout in milliseconds for `:wait` await-step polling.
   Longer than `*retry-timeout-ms*` because await semantics imply
   the caller is explicitly tolerating a slower path. Bind to override."
  5000)

(defn- with-retry
  "Retry a function until it succeeds or timeout.
   Catches transient browser errors and retries with 100ms backoff.
   Non-retryable exceptions are rethrown immediately.
   Default timeout is *retry-timeout-ms* (3000ms)."
  ([f] (with-retry f *retry-timeout-ms*))
  ([f timeout-ms]
   (let [start (System/currentTimeMillis)
         deadline (+ start timeout-ms)]
     (loop [last-error nil]
       (if (> (System/currentTimeMillis) deadline)
         (throw (or last-error (ex-info "Retry timeout" {:timeout-ms timeout-ms})))
         (let [result (try
                        {:success true :value (f)}
                        (catch Exception e
                          (if (retryable-error? e)
                            {:success false :error e}
                            (throw e))))]
           (if (:success result)
             (:value result)
             (do
               (Thread/sleep 100)
               (recur (:error result))))))))))

;; -----------------------------------------------------------------------------
;; Key Name Resolution (for press-key step)
;; -----------------------------------------------------------------------------

(def ^:private key-map
  "Map of human-readable key names to etaoin key constants.
   Used by the 'presses' step to resolve key names like 'enter', 'tab', etc."
  {"enter"     k/enter
   "return"    k/return
   "tab"       k/tab
   "escape"    k/escape
   "backspace" k/backspace
   "delete"    k/delete
   "space"     k/space
   "up"        k/arrow-up
   "down"      k/arrow-down
   "left"      k/arrow-left
   "right"     k/arrow-right
   "home"      k/home
   "end"       k/end
   "pageup"    k/pageup
   "pagedown"  k/pagedown
   "f1"        k/f1
   "f2"        k/f2
   "f3"        k/f3
   "f4"        k/f4
   "f5"        k/f5
   "f6"        k/f6
   "f7"        k/f7
   "f8"        k/f8
   "f9"        k/f9
   "f10"       k/f10
   "f11"       k/f11
   "f12"       k/f12
   ;; Modifiers (used in chords as prefixes)
   "shift"     k/shift-left
   "control"   k/control-left
   "ctrl"      k/control-left
   "alt"       k/alt-left
   "command"   k/command
   "cmd"       k/command
   "meta"      k/meta-left})

(defn- resolve-key-name
  "Resolve a single key name string to its etaoin key constant.
   Throws on unknown key names."
  [name-str]
  (let [lower (str/lower-case (str/trim name-str))]
    (if-let [key-val (get key-map lower)]
      key-val
      ;; Single character keys (a-z, 0-9, etc.) pass through as-is
      (if (= 1 (count lower))
        lower
        (throw (ex-info (str "Unknown key name: '" name-str
                             "'. Known keys: " (str/join ", " (sort (keys key-map))))
                        {:type :browser/unknown-key
                         :key name-str
                         :known-keys (sort (keys key-map))}))))))

(defn- resolve-key-expression
  "Resolve a key expression like 'enter', 'shift+control+t', or 'a' to an
   etaoin key string suitable for fill-active.

   For single keys: returns the key character.
   For chords (modifier+key): uses etaoin.keys/chord to produce a modifier sequence."
  [expr]
  (let [parts (str/split expr #"\+")
        resolved (mapv #(resolve-key-name (str/trim %)) parts)]
    (if (= 1 (count resolved))
      ;; Single key — just the character
      (str (first resolved))
      ;; Chord — apply k/chord with modifiers + final key
      (apply k/chord resolved))))

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
  "Return a vector of subject keywords for every `:cap/web*` entry in ctx,
   in stable iteration order. Bare `:cap/web` (no subject) shows up as nil
   and is filtered out — callers want the named subjects for diagnostics."
  [ctx]
  (->> (cap/all-capabilities ctx)
       keys
       (keep (fn [k]
               (let [{:keys [interface subject]} (cap/parse-capability-name k)]
                 (when (and (= :web interface) subject)
                   subject))))
       vec))

(defn- with-subject-browser
  "Look up the subject's `:cap/web.<subject>` impl, run op-fn on it.

   Subject string from step text (e.g., \"alice\" or \"user/alice\") is
   normalized via `subject-str->key`. Returns ctx unchanged — the unified
   capability shape is stateless across step boundaries."
  [ctx subject-str op-fn]
  (let [subject (subject-str->key subject-str)
        browser (cap/get-capability ctx :web subject)]
    (if browser
      (do (op-fn browser) ctx)
      (throw (ex-info (str "No browser session for subject: " subject-str)
                      {:type :browser/no-session
                       :subject subject-str
                       :session-key subject
                       :available-sessions (web-subjects-in-ctx ctx)})))))

(defn- with-subject-locator
  "Subject-extracting variant of with-locator.
   Resolves locator, looks up the subject's browser, executes op-fn.

   Interface parameter enables intent reference resolution:
   - If interface is nil, only EDN locators work
   - If interface is provided (e.g., :web), intent refs like Login.submit resolve"
  ([ctx subject-str locator-str op-fn]
   (with-subject-locator ctx subject-str locator-str nil op-fn))
  ([ctx subject-str locator-str interface op-fn]
   (let [resolved (parse-locator locator-str interface)]
     (if (locators/valid? resolved)
       (with-subject-browser ctx subject-str
         (fn [browser] (op-fn browser resolved)))
       (throw (ex-info "Invalid locator" {:errors (:errors resolved)}))))))

(defn- with-subject-query
  "Subject-extracting query helper. Returns [ctx browser] for query use.
   Caller uses the browser for queries; ctx is unchanged."
  [ctx subject-str]
  (let [subject (subject-str->key subject-str)
        browser (cap/get-capability ctx :web subject)]
    (if browser
      [ctx browser]
      (throw (ex-info (str "No browser session for subject: " subject-str)
                      {:type :browser/no-session
                       :subject subject-str
                       :session-key subject
                       :available-sessions (web-subjects-in-ctx ctx)})))))

;; =============================================================================
;; Subject-Extracting Action Steps
;; =============================================================================

;; --- Kernel Actions (0.2.x) ---

(defstep #":([\w./-]+) opens the browser to '([^']+)'"
  {:interface :web
   :svo {:subject :$1 :verb :navigate :frame :to :object :$2}}
  [ctx subject-str url]
  (with-subject-browser ctx subject-str
    (fn [browser] (bp/open-to! browser url))))

(defstep click-pattern
  {:interface :web
   :svo {:subject :$1 :verb :click :frame :default :object :$2}}
  [ctx subject-str locator-str]
  (with-subject-locator ctx subject-str locator-str (:interface step-meta)
    (fn [browser resolved] (bp/click! browser resolved))))

(defstep doubleclick-pattern
  {:interface :web
   :svo {:subject :$1 :verb :doubleclick :frame :default :object :$2}}
  [ctx subject-str locator-str]
  (with-subject-locator ctx subject-str locator-str (:interface step-meta)
    (fn [browser resolved] (bp/doubleclick! browser resolved))))

(defstep rightclick-pattern
  {:interface :web
   :svo {:subject :$1 :verb :rightclick :frame :default :object :$2}}
  [ctx subject-str locator-str]
  (with-subject-locator ctx subject-str locator-str (:interface step-meta)
    (fn [browser resolved] (bp/rightclick! browser resolved))))

(defstep move-to-pattern
  {:interface :web
   :svo {:subject :$1 :verb :move :frame :default :object :$2}}
  [ctx subject-str locator-str]
  (with-subject-locator ctx subject-str locator-str (:interface step-meta)
    (fn [browser resolved] (bp/move-to! browser resolved))))

;; :hover delegates to the same kernel op as :move — webdriver/etaoin have no
;; separate hover primitive (mouse-move-to-element triggers hover behavior).
;; The verb-level distinction is for human-readable feature text.
(defstep hover-pattern
  {:interface :web
   :svo {:subject :$1 :verb :hover :frame :default :object :$2}}
  [ctx subject-str locator-str]
  (with-subject-locator ctx subject-str locator-str (:interface step-meta)
    (fn [browser resolved] (bp/move-to! browser resolved))))

(defstep drag-pattern
  {:interface :web
   :svo {:subject :$1 :verb :drag :frame :to :object :$2 :args {:target :$3}}}
  [ctx subject-str from-str to-str]
  (let [interface (:interface step-meta)
        from-resolved (parse-locator from-str interface)
        to-resolved (parse-locator to-str interface)]
    (cond
      (not (locators/valid? from-resolved))
      (throw (ex-info "Invalid from locator" {:errors (:errors from-resolved)}))

      (not (locators/valid? to-resolved))
      (throw (ex-info "Invalid to locator" {:errors (:errors to-resolved)}))

      :else
      (with-subject-browser ctx subject-str
        (fn [browser]
          (bp/drag-to! browser from-resolved to-resolved))))))

(defstep fill-pattern
  {:interface :web
   :svo {:subject :$1 :verb :fill :frame :with :object :$2 :args {:value :$3}}}
  [ctx subject-str locator-str text]
  (with-subject-locator ctx subject-str locator-str (:interface step-meta)
    (fn [browser resolved] (bp/fill! browser resolved text))))

;; --- Navigation Actions (0.3.6) ---

(defstep #":([\w./-]+) goes back"
  {:interface :web
   :svo {:subject :$1 :verb :navigate-back :frame :default :object nil}}
  [ctx subject-str]
  (with-subject-browser ctx subject-str
    (fn [browser] (bp/go-back! browser))))

(defstep #":([\w./-]+) goes forward"
  {:interface :web
   :svo {:subject :$1 :verb :navigate-forward :frame :default :object nil}}
  [ctx subject-str]
  (with-subject-browser ctx subject-str
    (fn [browser] (bp/go-forward! browser))))

(defstep #":([\w./-]+) refreshes the page"
  {:interface :web
   :svo {:subject :$1 :verb :refresh :frame :default :object nil}}
  [ctx subject-str]
  (with-subject-browser ctx subject-str
    (fn [browser] (bp/refresh! browser))))

;; --- Scrolling Actions (0.3.6) ---

(defstep scroll-to-pattern
  {:interface :web
   :svo {:subject :$1 :verb :scroll :frame :to-element :object :$2}}
  [ctx subject-str locator-str]
  (with-subject-locator ctx subject-str locator-str (:interface step-meta)
    (fn [browser resolved] (bp/scroll-to! browser resolved))))

(defstep #":([\w./-]+) scrolls to the (top|bottom)"
  {:interface :web
   :svo {:subject :$1 :verb :scroll :frame :to-position
         :object nil :args {:position :$2}}}
  [ctx subject-str position-str]
  (with-subject-browser ctx subject-str
    (fn [browser] (bp/scroll-to-position! browser (keyword position-str)))))

;; --- Form Actions (0.3.6) ---

(defstep clear-pattern
  {:interface :web
   :svo {:subject :$1 :verb :clear :frame :default :object :$2}}
  [ctx subject-str locator-str]
  (with-subject-locator ctx subject-str locator-str (:interface step-meta)
    (fn [browser resolved] (bp/clear! browser resolved))))

(defstep select-from-pattern
  {:interface :web
   :svo {:subject :$1 :verb :select :frame :from
         :object :$3 :args {:value :$2}}}
  [ctx subject-str text locator-str]
  (with-subject-locator ctx subject-str locator-str (:interface step-meta)
    (fn [browser resolved] (bp/select! browser resolved text))))

(defstep #":([\w./-]+) presses (.+)"
  {:interface :web
   :svo {:subject :$1 :verb :press :frame :default :object :$2}}
  [ctx subject-str key-expr]
  (let [key-str (resolve-key-expression key-expr)]
    (with-subject-browser ctx subject-str
      (fn [browser] (bp/press-key! browser key-str)))))

;; --- Alert Actions (0.3.6) ---

(defstep #":([\w./-]+) accepts the alert"
  {:interface :web
   :svo {:subject :$1 :verb :accept-alert :frame :default :object nil}}
  [ctx subject-str]
  (with-subject-browser ctx subject-str
    (fn [browser] (bp/accept-alert! browser))))

(defstep #":([\w./-]+) dismisses the alert"
  {:interface :web
   :svo {:subject :$1 :verb :dismiss-alert :frame :default :object nil}}
  [ctx subject-str]
  (with-subject-browser ctx subject-str
    (fn [browser] (bp/dismiss-alert! browser))))

;; --- Window Management Actions (0.3.6) ---

(defstep #":([\w./-]+) maximizes the window"
  {:interface :web
   :svo {:subject :$1 :verb :maximize :frame :default :object nil}}
  [ctx subject-str]
  (with-subject-browser ctx subject-str
    (fn [browser] (bp/maximize-window! browser))))

(defstep #":([\w./-]+) resizes the window to (\d+)x(\d+)"
  {:interface :web
   :svo {:subject :$1 :verb :resize :frame :dimensions
         :object nil :args {:width :$2 :height :$3}}}
  [ctx subject-str width-str height-str]
  (with-subject-browser ctx subject-str
    (fn [browser] (bp/set-window-size! browser
                                        (parse-long width-str)
                                        (parse-long height-str)))))

(defstep #":([\w./-]+) switches to the next window"
  {:interface :web
   :svo {:subject :$1 :verb :switch-window :frame :default :object nil}}
  [ctx subject-str]
  (with-subject-browser ctx subject-str
    (fn [browser] (bp/switch-to-next-window! browser))))

;; --- Frame Actions (0.3.6) ---

(defstep switch-frame-pattern
  {:interface :web
   :svo {:subject :$1 :verb :switch-frame :frame :into :object :$2}}
  [ctx subject-str locator-str]
  (with-subject-locator ctx subject-str locator-str (:interface step-meta)
    (fn [browser resolved] (bp/switch-to-frame! browser resolved))))

(defstep #":([\w./-]+) switches to the main frame"
  {:interface :web
   :svo {:subject :$1 :verb :switch-frame :frame :main :object nil}}
  [ctx subject-str]
  (with-subject-browser ctx subject-str
    (fn [browser] (bp/switch-to-main-frame! browser))))

;; =============================================================================
;; Subject-Extracting Verification Steps
;; =============================================================================

;; --- Existing Verification Steps (0.3.5) ---

(defstep #":([\w./-]+) should see '([^']+)'"
  {:interface :web
   :svo {:subject :$1 :verb :see :frame :on-page :object :$2}}
  [ctx subject-str expected-text]
  (let [[ctx' browser] (with-subject-query ctx subject-str)
        body-locator (locators/resolve-locator {:tag "body"})]
    (with-retry
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
         :object :$3 :args {:count :$2}}}
  [ctx subject-str count-str locator-str]
  (let [expected (parse-long count-str)
        resolved (parse-locator locator-str (:interface step-meta))]
    (when-not (locators/valid? resolved)
      (throw (ex-info "Invalid locator" {:errors (:errors resolved)})))
    (let [[ctx' browser] (with-subject-query ctx subject-str)]
      (with-retry
        (fn []
          (let [actual (bp/element-count browser resolved)]
            (when-not (= expected actual)
              (throw (ex-info (str "Expected " expected " elements but found " actual)
                               {:type :browser/assertion-failed
                                :expected expected
                                :actual actual
                                :locator locator-str}))))))
      ctx')))

(defstep should-see-text-pattern
  {:interface :web
   :svo {:subject :$1 :verb :see :frame :text
         :object :$2 :args {:text :$3}}}
  [ctx subject-str locator-str expected-text]
  (let [resolved (parse-locator locator-str (:interface step-meta))]
    (when-not (locators/valid? resolved)
      (throw (ex-info "Invalid locator" {:errors (:errors resolved)})))
    (let [[ctx' browser] (with-subject-query ctx subject-str)]
      (with-retry
        (fn []
          (let [actual (bp/get-text browser resolved)]
            (when-not (.contains (str actual) expected-text)
              (throw (ex-info (str "Expected element text to contain '" expected-text
                                   "' but was '" actual "'")
                               {:type :browser/assertion-failed
                                :expected expected-text
                                :actual actual
                                :locator locator-str}))))))
      ctx')))

(defstep should-see-value-pattern
  {:interface :web
   :svo {:subject :$1 :verb :see :frame :value
         :object :$2 :args {:value :$3}}}
  [ctx subject-str locator-str expected-value]
  (let [resolved (parse-locator locator-str (:interface step-meta))]
    (when-not (locators/valid? resolved)
      (throw (ex-info "Invalid locator" {:errors (:errors resolved)})))
    (let [[ctx' browser] (with-subject-query ctx subject-str)]
      (with-retry
        (fn []
          (let [actual (bp/get-value browser resolved)]
            (when-not (= expected-value actual)
              (throw (ex-info (str "Expected element value '" expected-value
                                   "' but was '" actual "'")
                               {:type :browser/assertion-failed
                                :expected expected-value
                                :actual actual
                                :locator locator-str}))))))
      ctx')))

(defstep should-see-attr-pattern
  {:interface :web
   :svo {:subject :$1 :verb :see :frame :attribute
         :object :$2 :args {:attribute :$3 :value :$4}}}
  [ctx subject-str locator-str attr-name expected-value]
  (let [resolved (parse-locator locator-str (:interface step-meta))]
    (when-not (locators/valid? resolved)
      (throw (ex-info "Invalid locator" {:errors (:errors resolved)})))
    (let [[ctx' browser] (with-subject-query ctx subject-str)]
      (with-retry
        (fn []
          (let [actual (bp/get-attribute browser resolved attr-name)]
            (when-not (= expected-value actual)
              (throw (ex-info (str "Expected attribute '" attr-name "' to be '" expected-value
                                   "' but was '" actual "'")
                               {:type :browser/assertion-failed
                                :expected expected-value
                                :actual actual
                                :attribute attr-name
                                :locator locator-str}))))))
      ctx')))

(defstep should-see-enabled-pattern
  {:interface :web
   :svo {:subject :$1 :verb :see :frame :enabled :object :$2}}
  [ctx subject-str locator-str]
  (let [resolved (parse-locator locator-str (:interface step-meta))]
    (when-not (locators/valid? resolved)
      (throw (ex-info "Invalid locator" {:errors (:errors resolved)})))
    (let [[ctx' browser] (with-subject-query ctx subject-str)]
      (with-retry
        (fn []
          (when-not (bp/enabled? browser resolved)
            (throw (ex-info (str "Expected element to be enabled: " locator-str)
                             {:type :browser/assertion-failed
                              :locator locator-str})))))
      ctx')))

(defstep should-see-disabled-pattern
  {:interface :web
   :svo {:subject :$1 :verb :see :frame :disabled :object :$2}}
  [ctx subject-str locator-str]
  (let [resolved (parse-locator locator-str (:interface step-meta))]
    (when-not (locators/valid? resolved)
      (throw (ex-info "Invalid locator" {:errors (:errors resolved)})))
    (let [[ctx' browser] (with-subject-query ctx subject-str)]
      (with-retry
        (fn []
          (when (bp/enabled? browser resolved)
            (throw (ex-info (str "Expected element to be disabled: " locator-str)
                             {:type :browser/assertion-failed
                              :locator locator-str})))))
      ctx')))

(defstep should-see-locator-pattern
  {:interface :web
   :svo {:subject :$1 :verb :see :frame :visible :object :$2}}
  [ctx subject-str locator-str]
  (let [resolved (parse-locator locator-str (:interface step-meta))]
    (when-not (locators/valid? resolved)
      (throw (ex-info "Invalid locator" {:errors (:errors resolved)})))
    (let [[ctx' browser] (with-subject-query ctx subject-str)]
      (with-retry
        (fn []
          (when-not (bp/visible? browser resolved)
            (throw (ex-info (str "Expected element to be visible: " locator-str)
                             {:type :browser/assertion-failed
                              :locator locator-str})))))
      ctx')))

(defstep should-not-see-pattern
  {:interface :web
   :svo {:subject :$1 :verb :not-see :frame :invisible :object :$2}}
  [ctx subject-str locator-str]
  (let [resolved (parse-locator locator-str (:interface step-meta))]
    (when-not (locators/valid? resolved)
      (throw (ex-info "Invalid locator" {:errors (:errors resolved)})))
    (let [[ctx' browser] (with-subject-query ctx subject-str)]
      (when (bp/visible? browser resolved)
        (throw (ex-info (str "Expected element to NOT be visible: " locator-str)
                         {:type :browser/assertion-failed
                          :locator locator-str})))
      ctx')))

(defstep #":([\w./-]+) should be on '([^']+)'"
  {:interface :web
   :svo {:subject :$1 :verb :be :frame :at :object :$2}}
  [ctx subject-str expected-url]
  (let [[ctx' browser] (with-subject-query ctx subject-str)]
    (with-retry
      (fn []
        (let [actual (bp/get-url browser)]
          (when-not (.contains (str actual) expected-url)
            (throw (ex-info (str "Expected URL to contain '" expected-url "' but was '" actual "'")
                             {:type :browser/assertion-failed
                              :expected expected-url
                              :actual actual}))))))
    ctx'))

(defstep #":([\w./-]+) should see the title '([^']+)'"
  {:interface :web
   :svo {:subject :$1 :verb :see :frame :title :object :$2}}
  [ctx subject-str expected-title]
  (let [[ctx' browser] (with-subject-query ctx subject-str)]
    (with-retry
      (fn []
        (let [actual (bp/get-title browser)]
          (when-not (= expected-title actual)
            (throw (ex-info (str "Expected page title '" expected-title "' but was '" actual "'")
                             {:type :browser/assertion-failed
                              :expected expected-title
                              :actual actual}))))))
    ctx'))

;; --- Alert Verification Steps (0.3.6) ---

(defstep #":([\w./-]+) should see an alert with '([^']+)'"
  {:interface :web
   :svo {:subject :$1 :verb :see :frame :alert-with-text
         :object nil :args {:text :$2}}}
  [ctx subject-str expected-text]
  (let [[ctx' browser] (with-subject-query ctx subject-str)]
    (with-retry
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
   :svo {:subject :$1 :verb :see :frame :alert :object nil}}
  [ctx subject-str]
  (let [[ctx' browser] (with-subject-query ctx subject-str)]
    (with-retry
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
         :object nil :args {:duration :$2}}}
  [ctx _subject-str seconds-str]
  (Thread/sleep (long (* 1000 (Double/parseDouble seconds-str))))
  ctx)

;; :wait :for-element — block until element becomes visible (mirror of :see :visible).
(defstep wait-for-element-pattern
  {:interface :web
   :svo {:subject :$1 :verb :wait :frame :for-element :object :$2}}
  [ctx subject-str locator-str]
  (let [resolved (parse-locator locator-str (:interface step-meta))]
    (when-not (locators/valid? resolved)
      (throw (ex-info "Invalid locator" {:errors (:errors resolved)})))
    (let [[ctx' browser] (with-subject-query ctx subject-str)]
      (with-retry
        (fn []
          (when-not (bp/visible? browser resolved)
            (throw (ex-info (str "Waited but element never became visible: " locator-str)
                            {:type    :browser/assertion-failed
                             :locator locator-str}))))
        *wait-timeout-ms*)
      ctx')))

;; :wait :for-text — block until element contains the expected text
;; (mirror of :see :text).
(defstep wait-for-text-pattern
  {:interface :web
   :svo {:subject :$1 :verb :wait :frame :for-text
         :object :$2 :args {:text :$3}}}
  [ctx subject-str locator-str expected-text]
  (let [resolved (parse-locator locator-str (:interface step-meta))]
    (when-not (locators/valid? resolved)
      (throw (ex-info "Invalid locator" {:errors (:errors resolved)})))
    (let [[ctx' browser] (with-subject-query ctx subject-str)]
      (with-retry
        (fn []
          (let [actual (bp/get-text browser resolved)]
            (when-not (.contains (str actual) expected-text)
              (throw (ex-info (str "Waited but element text never contained '" expected-text
                                   "' (last seen: '" actual "')")
                              {:type     :browser/assertion-failed
                               :expected expected-text
                               :actual   actual
                               :locator  locator-str})))))
        *wait-timeout-ms*)
      ctx')))

;; :wait :for-count — block until N matching elements exist (mirror of :see :count).
(defstep wait-for-count-pattern
  {:interface :web
   :svo {:subject :$1 :verb :wait :frame :for-count
         :object :$3 :args {:count :$2}}}
  [ctx subject-str count-str locator-str]
  (let [expected (parse-long count-str)
        resolved (parse-locator locator-str (:interface step-meta))]
    (when-not (locators/valid? resolved)
      (throw (ex-info "Invalid locator" {:errors (:errors resolved)})))
    (let [[ctx' browser] (with-subject-query ctx subject-str)]
      (with-retry
        (fn []
          (let [actual (bp/element-count browser resolved)]
            (when-not (= expected actual)
              (throw (ex-info (str "Waited but element count never reached " expected
                                   " (last seen: " actual ")")
                              {:type     :browser/assertion-failed
                               :expected expected
                               :actual   actual
                               :locator  locator-str})))))
        *wait-timeout-ms*)
      ctx')))
