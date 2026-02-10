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

   ## Utility Steps

   - `pause for <N> seconds` (no subject — debug only)

   ## Retry Policy

   All verification steps (`should see`, `should be on`, etc.) retry on
   transient browser errors for up to `*retry-timeout-ms*` (default 3000ms)
   with 100ms backoff. Retryable errors: stale element references, missing
   elements, and assertion failures (`:browser/assertion-failed`).

   Exceptions:
   - `should not see` does NOT retry (negation is instant — if visible now,
     waiting for disappearance is a different operation)
   - Action steps NEVER retry (mutations are not idempotent)

   Bind `*retry-timeout-ms*` to override the default timeout.

   ## Locator Syntax

   Locators in step text use EDN syntax:
   - `{:css \"#login\"}` — CSS selector
   - `{:xpath \"//button\"}` — XPath
   - `{:id \"submit\"}` — element ID
   - `[:css \"#login\"]` — vector shorthand"
  (:require [shiftlefter.stepengine.registry :refer [defstep]]
            [shiftlefter.browser.ctx :as browser.ctx]
            [shiftlefter.browser.locators :as locators]
            [shiftlefter.browser.protocol :as bp]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [etaoin.keys :as k]))

;; -----------------------------------------------------------------------------
;; Helpers
;; -----------------------------------------------------------------------------

(defn- parse-locator
  "Parse locator string from step text (EDN format)."
  [locator-str]
  (try
    (let [token (edn/read-string locator-str)]
      (locators/resolve-locator token))
    (catch Exception e
      {:errors [{:type :browser/locator-parse-error
                 :message (str "Failed to parse locator: " (ex-message e))
                 :data {:input locator-str}}]})))

(defn- retryable-error?
  "Check if exception is a transient browser error worth retrying.
   Includes: stale element, no such element, assertion failures."
  [e]
  (let [msg (str (ex-message e) " " (ex-data e))
        data (ex-data e)]
    (or (.contains msg "stale element")
        (.contains msg "StaleElementReferenceException")
        (.contains msg "no such element")
        (= :browser/assertion-failed (:type data)))))

(def ^:dynamic *retry-timeout-ms*
  "Default timeout in milliseconds for verification step retries.
   Bind to override, e.g. `(binding [*retry-timeout-ms* 5000] ...)`."
  3000)

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

(defstep #"pause for (\d+) seconds?"
  [ctx seconds-str]
  (Thread/sleep (* 1000 (parse-long seconds-str)))
  ctx)

;; -----------------------------------------------------------------------------
;; Subject Routing Helper
;; -----------------------------------------------------------------------------

(defn- with-subject-browser
  "Set active session to the named subject, get browser, execute op-fn.
   Subject string from step text (e.g., \"alice\") becomes session keyword.
   Returns updated ctx."
  [ctx subject-str op-fn]
  (let [session-key (keyword subject-str)
        browser (browser.ctx/get-session ctx session-key)]
    (if browser
      (let [ctx' (browser.ctx/set-active-session ctx session-key)]
        (op-fn browser)
        ctx')
      (throw (ex-info (str "No browser session for subject: " subject-str)
                       {:type :browser/no-session
                        :subject subject-str
                        :available-sessions (vec (keys (get-in ctx [:cap/browser :sessions])))})))))

(defn- with-subject-locator
  "Subject-extracting variant of with-locator.
   Sets active session, resolves locator, executes op-fn."
  [ctx subject-str locator-str op-fn]
  (let [resolved (parse-locator locator-str)]
    (if (locators/valid? resolved)
      (with-subject-browser ctx subject-str
        (fn [browser] (op-fn browser resolved)))
      (throw (ex-info "Invalid locator" {:errors (:errors resolved)})))))

(defn- with-subject-query
  "Subject-extracting query helper. Returns [ctx' browser] for query use.
   Does NOT return ctx — caller uses the browser for queries."
  [ctx subject-str]
  (let [session-key (keyword subject-str)
        browser (browser.ctx/get-session ctx session-key)]
    (if browser
      (let [ctx' (browser.ctx/set-active-session ctx session-key)]
        [ctx' browser])
      (throw (ex-info (str "No browser session for subject: " subject-str)
                       {:type :browser/no-session
                        :subject subject-str
                        :available-sessions (vec (keys (get-in ctx [:cap/browser :sessions])))})))))

;; =============================================================================
;; Subject-Extracting Action Steps
;; =============================================================================

;; --- Kernel Actions (0.2.x) ---

(defstep #":(\w+) opens the browser to '([^']+)'"
  {:interface :web
   :svo {:subject :$1 :verb :navigate :object :$2}}
  [ctx subject-str url]
  (with-subject-browser ctx subject-str
    (fn [browser] (bp/open-to! browser url))))

(defstep #":(\w+) clicks (\{[^}]+\}|\[[^\]]+\])"
  {:interface :web
   :svo {:subject :$1 :verb :click :object :$2}}
  [ctx subject-str locator-str]
  (with-subject-locator ctx subject-str locator-str
    (fn [browser resolved] (bp/click! browser resolved))))

(defstep #":(\w+) double-clicks (\{[^}]+\}|\[[^\]]+\])"
  {:interface :web
   :svo {:subject :$1 :verb :doubleclick :object :$2}}
  [ctx subject-str locator-str]
  (with-subject-locator ctx subject-str locator-str
    (fn [browser resolved] (bp/doubleclick! browser resolved))))

(defstep #":(\w+) right-clicks (\{[^}]+\}|\[[^\]]+\])"
  {:interface :web
   :svo {:subject :$1 :verb :rightclick :object :$2}}
  [ctx subject-str locator-str]
  (with-subject-locator ctx subject-str locator-str
    (fn [browser resolved] (bp/rightclick! browser resolved))))

(defstep #":(\w+) moves to (\{[^}]+\}|\[[^\]]+\])"
  {:interface :web
   :svo {:subject :$1 :verb :move :object :$2}}
  [ctx subject-str locator-str]
  (with-subject-locator ctx subject-str locator-str
    (fn [browser resolved] (bp/move-to! browser resolved))))

(defstep #":(\w+) drags (\{[^}]+\}|\[[^\]]+\]) to (\{[^}]+\}|\[[^\]]+\])"
  {:interface :web
   :svo {:subject :$1 :verb :drag :object :$2}}
  [ctx subject-str from-str to-str]
  (let [from-resolved (parse-locator from-str)
        to-resolved (parse-locator to-str)]
    (cond
      (not (locators/valid? from-resolved))
      (throw (ex-info "Invalid from locator" {:errors (:errors from-resolved)}))

      (not (locators/valid? to-resolved))
      (throw (ex-info "Invalid to locator" {:errors (:errors to-resolved)}))

      :else
      (with-subject-browser ctx subject-str
        (fn [browser]
          (bp/drag-to! browser from-resolved to-resolved))))))

(defstep #":(\w+) fills (\{[^}]+\}|\[[^\]]+\]) with '([^']+)'"
  {:interface :web
   :svo {:subject :$1 :verb :fill :object :$2}}
  [ctx subject-str locator-str text]
  (with-subject-locator ctx subject-str locator-str
    (fn [browser resolved] (bp/fill! browser resolved text))))

;; --- Navigation Actions (0.3.6) ---

(defstep #":(\w+) goes back"
  {:interface :web
   :svo {:subject :$1 :verb :navigate-back :object 1}}
  [ctx subject-str]
  (with-subject-browser ctx subject-str
    (fn [browser] (bp/go-back! browser))))

(defstep #":(\w+) goes forward"
  {:interface :web
   :svo {:subject :$1 :verb :navigate-forward :object 1}}
  [ctx subject-str]
  (with-subject-browser ctx subject-str
    (fn [browser] (bp/go-forward! browser))))

(defstep #":(\w+) refreshes the page"
  {:interface :web
   :svo {:subject :$1 :verb :refresh :object nil}}
  [ctx subject-str]
  (with-subject-browser ctx subject-str
    (fn [browser] (bp/refresh! browser))))

;; --- Scrolling Actions (0.3.6) ---

(defstep #":(\w+) scrolls to (\{[^}]+\}|\[[^\]]+\])"
  {:interface :web
   :svo {:subject :$1 :verb :scroll :object :$2}}
  [ctx subject-str locator-str]
  (with-subject-locator ctx subject-str locator-str
    (fn [browser resolved] (bp/scroll-to! browser resolved))))

(defstep #":(\w+) scrolls to the (top|bottom)"
  {:interface :web
   :svo {:subject :$1 :verb :scroll :object :$2}}
  [ctx subject-str position-str]
  (with-subject-browser ctx subject-str
    (fn [browser] (bp/scroll-to-position! browser (keyword position-str)))))

;; --- Form Actions (0.3.6) ---

(defstep #":(\w+) clears (\{[^}]+\}|\[[^\]]+\])"
  {:interface :web
   :svo {:subject :$1 :verb :clear :object :$2}}
  [ctx subject-str locator-str]
  (with-subject-locator ctx subject-str locator-str
    (fn [browser resolved] (bp/clear! browser resolved))))

(defstep #":(\w+) selects '([^']+)' from (\{[^}]+\}|\[[^\]]+\])"
  {:interface :web
   :svo {:subject :$1 :verb :select :object :$3}}
  [ctx subject-str text locator-str]
  (with-subject-locator ctx subject-str locator-str
    (fn [browser resolved] (bp/select! browser resolved text))))

(defstep #":(\w+) presses (.+)"
  {:interface :web
   :svo {:subject :$1 :verb :press :object :$2}}
  [ctx subject-str key-expr]
  (let [key-str (resolve-key-expression key-expr)]
    (with-subject-browser ctx subject-str
      (fn [browser] (bp/press-key! browser key-str)))))

;; --- Alert Actions (0.3.6) ---

(defstep #":(\w+) accepts the alert"
  {:interface :web
   :svo {:subject :$1 :verb :accept-alert :object nil}}
  [ctx subject-str]
  (with-subject-browser ctx subject-str
    (fn [browser] (bp/accept-alert! browser))))

(defstep #":(\w+) dismisses the alert"
  {:interface :web
   :svo {:subject :$1 :verb :dismiss-alert :object nil}}
  [ctx subject-str]
  (with-subject-browser ctx subject-str
    (fn [browser] (bp/dismiss-alert! browser))))

;; --- Window Management Actions (0.3.6) ---

(defstep #":(\w+) maximizes the window"
  {:interface :web
   :svo {:subject :$1 :verb :maximize :object nil}}
  [ctx subject-str]
  (with-subject-browser ctx subject-str
    (fn [browser] (bp/maximize-window! browser))))

(defstep #":(\w+) resizes the window to (\d+)x(\d+)"
  {:interface :web
   :svo {:subject :$1 :verb :resize :object :$2}}
  [ctx subject-str width-str height-str]
  (with-subject-browser ctx subject-str
    (fn [browser] (bp/set-window-size! browser
                                        (parse-long width-str)
                                        (parse-long height-str)))))

(defstep #":(\w+) switches to the next window"
  {:interface :web
   :svo {:subject :$1 :verb :switch-window :object nil}}
  [ctx subject-str]
  (with-subject-browser ctx subject-str
    (fn [browser] (bp/switch-to-next-window! browser))))

;; --- Frame Actions (0.3.6) ---

(defstep #":(\w+) switches to frame (\{[^}]+\}|\[[^\]]+\])"
  {:interface :web
   :svo {:subject :$1 :verb :switch-frame :object :$2}}
  [ctx subject-str locator-str]
  (with-subject-locator ctx subject-str locator-str
    (fn [browser resolved] (bp/switch-to-frame! browser resolved))))

(defstep #":(\w+) switches to the main frame"
  {:interface :web
   :svo {:subject :$1 :verb :switch-frame :object nil}}
  [ctx subject-str]
  (with-subject-browser ctx subject-str
    (fn [browser] (bp/switch-to-main-frame! browser))))

;; =============================================================================
;; Subject-Extracting Verification Steps
;; =============================================================================

;; --- Existing Verification Steps (0.3.5) ---

(defstep #":(\w+) should see '([^']+)'"
  {:interface :web
   :svo {:subject :$1 :verb :see :object :$2}}
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

(defstep #":(\w+) should see (\d+) (\{[^}]+\}|\[[^\]]+\]) elements"
  {:interface :web
   :svo {:subject :$1 :verb :count :object :$3}}
  [ctx subject-str count-str locator-str]
  (let [expected (parse-long count-str)
        resolved (parse-locator locator-str)]
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

(defstep #":(\w+) should see (\{[^}]+\}|\[[^\]]+\]) with text '([^']+)'"
  {:interface :web
   :svo {:subject :$1 :verb :see :object :$2}}
  [ctx subject-str locator-str expected-text]
  (let [resolved (parse-locator locator-str)]
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

(defstep #":(\w+) should see (\{[^}]+\}|\[[^\]]+\]) with value '([^']+)'"
  {:interface :web
   :svo {:subject :$1 :verb :see :object :$2}}
  [ctx subject-str locator-str expected-value]
  (let [resolved (parse-locator locator-str)]
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

(defstep #":(\w+) should see (\{[^}]+\}|\[[^\]]+\]) with attribute '([^']+)' equal to '([^']+)'"
  {:interface :web
   :svo {:subject :$1 :verb :see :object :$2}}
  [ctx subject-str locator-str attr-name expected-value]
  (let [resolved (parse-locator locator-str)]
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

(defstep #":(\w+) should see (\{[^}]+\}|\[[^\]]+\]) enabled"
  {:interface :web
   :svo {:subject :$1 :verb :see :object :$2}}
  [ctx subject-str locator-str]
  (let [resolved (parse-locator locator-str)]
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

(defstep #":(\w+) should see (\{[^}]+\}|\[[^\]]+\]) disabled"
  {:interface :web
   :svo {:subject :$1 :verb :see :object :$2}}
  [ctx subject-str locator-str]
  (let [resolved (parse-locator locator-str)]
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

(defstep #":(\w+) should see (\{[^}]+\}|\[[^\]]+\])"
  {:interface :web
   :svo {:subject :$1 :verb :see :object :$2}}
  [ctx subject-str locator-str]
  (let [resolved (parse-locator locator-str)]
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

(defstep #":(\w+) should not see (\{[^}]+\}|\[[^\]]+\])"
  {:interface :web
   :svo {:subject :$1 :verb :see :object :$2}}
  [ctx subject-str locator-str]
  (let [resolved (parse-locator locator-str)]
    (when-not (locators/valid? resolved)
      (throw (ex-info "Invalid locator" {:errors (:errors resolved)})))
    (let [[ctx' browser] (with-subject-query ctx subject-str)]
      (when (bp/visible? browser resolved)
        (throw (ex-info (str "Expected element to NOT be visible: " locator-str)
                         {:type :browser/assertion-failed
                          :locator locator-str})))
      ctx')))

(defstep #":(\w+) should be on '([^']+)'"
  {:interface :web
   :svo {:subject :$1 :verb :see :object :$2}}
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

(defstep #":(\w+) should see the title '([^']+)'"
  {:interface :web
   :svo {:subject :$1 :verb :see :object :$2}}
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

(defstep #":(\w+) should see an alert with '([^']+)'"
  {:interface :web
   :svo {:subject :$1 :verb :see :object :$2}}
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

(defstep #":(\w+) should see an alert"
  {:interface :web
   :svo {:subject :$1 :verb :see :object nil}}
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
