(ns shiftlefter.stepdefs.browser
  "Browser step definitions for ShiftLefter.

   Provides stepdefs for kernel browser operations using boring, explicit text.
   Each stepdef:
   1. Gets browser from ctx via `browser.ctx/get-active-browser`
   2. Resolves locator via `locators/resolve-locator`
   3. Calls protocol method on browser
   4. Returns updated ctx

   ## Step Patterns

   ### \"I\" steps (vanilla, single-actor)

   Navigation:
   - `I open the browser to '<url>'`

   Click operations:
   - `I click {<locator>}`
   - `I double-click {<locator>}`
   - `I right-click {<locator>}`

   Mouse operations:
   - `I move to {<locator>}`
   - `I drag {<locator>} to {<locator>}`

   Input operations:
   - `I fill {<locator>} with '<text>'`

   Query operations:
   - `I count {<locator>} elements`

   ### Subject-extracting steps (multi-actor)

   Action steps (subject is `:alice`, `:bob`, etc.):
   - `:alice opens the browser to '<url>'`
   - `:alice clicks {<locator>}`
   - `:alice double-clicks {<locator>}`
   - `:alice right-clicks {<locator>}`
   - `:alice moves to {<locator>}`
   - `:alice drags {<locator>} to {<locator>}`
   - `:alice fills {<locator>} with '<text>'`

   Verification steps:
   - `:alice should see '<text>'`
   - `:alice should see <N> {<locator>} elements`
   - `:alice should see {<locator>}`
   - `:alice should not see {<locator>}`
   - `:alice should be on '<url>'`
   - `:alice should see the title '<text>'`

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
            [clojure.edn :as edn]))

;; -----------------------------------------------------------------------------
;; Helpers
;; -----------------------------------------------------------------------------

(defn- get-browser
  "Get browser from ctx, returning error if not configured.
   Note: ctx is flat scenario state (ctx-first convention)."
  [ctx]
  (if-let [browser (browser.ctx/get-active-browser ctx)]
    {:ok browser}
    {:error {:type :browser/not-configured
             :message "No browser configured in context"
             :data {}}}))

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

(defn- with-browser
  "Execute a browser operation, handling errors.
   Returns updated ctx on success, or throws on error.
   Note: ctx is flat scenario state (ctx-first convention)."
  [ctx op-fn]
  (let [{:keys [ok error]} (get-browser ctx)]
    (if error
      (throw (ex-info (:message error) error))
      (do
        (op-fn ok)
        ctx))))

(defn- with-locator
  "Execute a browser operation with a resolved locator.
   Returns updated scenario ctx on success, or throws on error."
  [ctx locator-str op-fn]
  (let [resolved (parse-locator locator-str)]
    (if (locators/valid? resolved)
      (with-browser ctx (fn [browser] (op-fn browser resolved)))
      (throw (ex-info "Invalid locator" {:errors (:errors resolved)})))))

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

(defn- with-retry
  "Retry a function until it succeeds or timeout (3s default).
   Catches transient browser errors and retries with 100ms backoff.
   Non-retryable exceptions are rethrown immediately."
  ([f] (with-retry f 3000))
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
;; Debug / Utility Steps
;; -----------------------------------------------------------------------------

(defstep #"pause for (\d+) seconds?"
  [ctx seconds-str]
  (Thread/sleep (* 1000 (parse-long seconds-str)))
  ctx)

;; -----------------------------------------------------------------------------
;; Navigation
;; -----------------------------------------------------------------------------

(defstep #"I open the browser to '([^']+)'"
  [ctx url]
  (with-browser ctx
    (fn [browser]
      (bp/open-to! browser url))))

;; -----------------------------------------------------------------------------
;; Click Operations
;; -----------------------------------------------------------------------------

(defstep #"I click (\{[^}]+\}|\[[^\]]+\])"
  [ctx locator-str]
  (with-locator ctx locator-str
    (fn [browser resolved]
      (bp/click! browser resolved))))

(defstep #"I double-click (\{[^}]+\}|\[[^\]]+\])"
  [ctx locator-str]
  (with-locator ctx locator-str
    (fn [browser resolved]
      (bp/doubleclick! browser resolved))))

(defstep #"I right-click (\{[^}]+\}|\[[^\]]+\])"
  [ctx locator-str]
  (with-locator ctx locator-str
    (fn [browser resolved]
      (bp/rightclick! browser resolved))))

;; -----------------------------------------------------------------------------
;; Mouse Operations
;; -----------------------------------------------------------------------------

(defstep #"I move to (\{[^}]+\}|\[[^\]]+\])"
  [ctx locator-str]
  (with-locator ctx locator-str
    (fn [browser resolved]
      (bp/move-to! browser resolved))))

(defstep #"I drag (\{[^}]+\}|\[[^\]]+\]) to (\{[^}]+\}|\[[^\]]+\])"
  [ctx from-str to-str]
  (let [from-resolved (parse-locator from-str)
        to-resolved (parse-locator to-str)]
    (cond
      (not (locators/valid? from-resolved))
      (throw (ex-info "Invalid from locator" {:errors (:errors from-resolved)}))

      (not (locators/valid? to-resolved))
      (throw (ex-info "Invalid to locator" {:errors (:errors to-resolved)}))

      :else
      (with-browser ctx
        (fn [browser]
          (bp/drag-to! browser from-resolved to-resolved))))))

;; -----------------------------------------------------------------------------
;; Input Operations
;; -----------------------------------------------------------------------------

(defstep #"I fill (\{[^}]+\}|\[[^\]]+\]) with '([^']+)'"
  [ctx locator-str text]
  (with-locator ctx locator-str
    (fn [browser resolved]
      (bp/fill! browser resolved text))))

;; -----------------------------------------------------------------------------
;; Query Operations
;; -----------------------------------------------------------------------------

(defstep #"I count (\{[^}]+\}|\[[^\]]+\]) elements"
  [ctx locator-str]
  (let [resolved (parse-locator locator-str)]
    (if-not (locators/valid? resolved)
      (throw (ex-info "Invalid locator" {:errors (:errors resolved)}))
      (let [{:keys [ok error]} (get-browser ctx)]
        (if error
          (throw (ex-info (:message error) error))
          (let [cnt (bp/element-count ok resolved)]
            (assoc ctx :element-count cnt)))))))

;; =============================================================================
;; Subject-Extracting Steps (Multi-Actor)
;; =============================================================================

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

;; -----------------------------------------------------------------------------
;; Subject-Extracting Action Steps
;; -----------------------------------------------------------------------------

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

;; -----------------------------------------------------------------------------
;; Subject-Extracting Verification Steps
;; -----------------------------------------------------------------------------

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
    (let [[ctx' browser] (with-subject-query ctx subject-str)
          actual (bp/element-count browser resolved)]
      (when-not (= expected actual)
        (throw (ex-info (str "Expected " expected " elements but found " actual)
                         {:type :browser/assertion-failed
                          :expected expected
                          :actual actual
                          :locator locator-str})))
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
