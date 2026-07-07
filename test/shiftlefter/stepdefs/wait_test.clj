(ns shiftlefter.stepdefs.wait-test
  "Tests for the :wait verb frames added in sl-jsn.

   :duration uses Thread/sleep — we test elapsed time only.
   :for-element / :for-text / :for-count poll the browser until the
   condition is met or the timeout elapses. The fake browser used here
   exposes a counter so tests can simulate eventual consistency
   (condition met after N polls) and timeout (condition never met).

   The final test exercises bind-suite end-to-end against the real
   default :web glossary to satisfy sl-jsn AC #5 (SVO validation
   accepts the new verbs)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [shiftlefter.stepengine.registry :as registry]
            [shiftlefter.stepengine.bind :as bind]
            [shiftlefter.svo.glossary :as glossary]
            [shiftlefter.capabilities.ctx :as cap]
            [shiftlefter.browser.protocol :as bp]
            [shiftlefter.stepdefs.browser :as browser-stepdefs]))

;; -----------------------------------------------------------------------------
;; Stateful Fake Browser — flips the predicate after N polls.
;; -----------------------------------------------------------------------------

(defrecord EventualBrowser
  [visible-after-atom    ; atom: countdown of polls before visible? returns true
   text-after-atom       ; atom: countdown of polls before get-text returns target
   target-text           ; the text get-text eventually returns
   count-after-atom      ; atom: countdown of polls before element-count = target-count
   target-count          ; the count element-count eventually returns
   poll-counts]          ; atom: histogram of {op n} for assertions
  bp/IBrowser

  (visible? [_this _locator]
    (swap! poll-counts update :visible? (fnil inc 0))
    (let [remaining (swap! visible-after-atom dec)]
      (<= remaining 0)))

  (get-text [_this _locator]
    (swap! poll-counts update :get-text (fnil inc 0))
    (let [remaining (swap! text-after-atom dec)]
      (if (<= remaining 0) target-text "")))

  (element-count [_this _locator]
    (swap! poll-counts update :element-count (fnil inc 0))
    (let [remaining (swap! count-after-atom dec)]
      (if (<= remaining 0) target-count 0)))

  ;; Unused operations — defined so the protocol is fully implemented.
  (open-to! [this _]            this)
  (click! [this _]              this)
  (doubleclick! [this _]        this)
  (rightclick! [this _]         this)
  (move-to! [this _]            this)
  (drag-to! [this _ _]          this)
  (fill! [this _ _]             this)
  (get-url [_this]              "https://example.com")
  (get-title [_this]            "")
  (go-back! [this]              this)
  (go-forward! [this]           this)
  (refresh! [this]              this)
  (scroll-to! [this _]          this)
  (scroll-to-position! [this _] this)
  (clear! [this _]              this)
  (select! [this _ _]           this)
  (press-key! [this _]          this)
  (get-attribute [_this _ _]    nil)
  (get-value [_this _]          nil)
  (enabled? [_this _]           true)
  (accept-alert! [this]         this)
  (dismiss-alert! [this]        this)
  (get-alert-text [_this]       "")
  (maximize-window! [this]      this)
  (set-window-size! [this _ _]  this)
  (switch-to-next-window! [this] this)
  (switch-to-frame! [this _]    this)
  (switch-to-main-frame! [this] this)
  (query-all [_this _scope _locator] [])
  (query-all-pruned [_this _scope _locator _boundary] []))

(defn- make-eventual-browser
  "Build a fake browser whose predicates flip after `n` polls.

   Options:
     :visible-after  — visible? returns true after this many polls (default 0 = immediately)
     :text-after     — get-text returns :target-text after this many polls
     :target-text    — what get-text eventually returns
     :count-after    — element-count returns :target-count after this many polls
     :target-count   — what element-count eventually returns"
  [{:keys [visible-after text-after target-text count-after target-count]
    :or   {visible-after 0
           text-after    0
           target-text   ""
           count-after   0
           target-count  0}}]
  (->EventualBrowser (atom (inc visible-after))
                     (atom (inc text-after))
                     target-text
                     (atom (inc count-after))
                     target-count
                     (atom {})))

(defn- never-met-browser
  "Browser whose predicates NEVER flip — for timeout tests.
   visible? always false, get-text always empty, element-count always 0.
   Uses a very large countdown rather than Long/MAX_VALUE to avoid the
   `(inc Long/MAX_VALUE)` overflow inside make-eventual-browser."
  []
  (let [huge Integer/MAX_VALUE]
    (make-eventual-browser {:visible-after huge
                            :text-after    huge
                            :count-after   huge})))

;; -----------------------------------------------------------------------------
;; Test fixtures and helpers (mirror browser_test.clj)
;; -----------------------------------------------------------------------------

(use-fixtures :each
  (fn [f]
    (registry/clear-registry!)
    (require 'shiftlefter.stepdefs.browser :reload)
    (f)))

(defn- make-ctx-with-subject [subject-kw browser]
  (cap/assoc-capability {} :web browser :ephemeral subject-kw))

(defn- find-stepdef [text]
  (first (filter #(re-matches (:pattern %) text) (registry/all-stepdefs))))

(defn- invoke-step [text ctx]
  (let [stepdef  (find-stepdef text)
        matcher  (re-matcher (:pattern stepdef) text)
        _        (.matches matcher)
        captures (mapv #(.group matcher %) (range 1 (inc (.groupCount matcher))))
        arity    (:arity stepdef)
        args     (if (= arity (inc (count captures)))
                   (into [ctx] captures)
                   captures)]
    (apply (:fn stepdef) args)))

;; =============================================================================
;; :wait :duration
;; =============================================================================

(deftest test-wait-duration-integer-seconds
  (testing ":alice waits 1 seconds — Thread/sleep with subject, returns ctx"
    (let [ctx     (make-ctx-with-subject :alice (make-eventual-browser {}))
          start   (System/currentTimeMillis)
          result  (invoke-step ":alice waits 1 seconds" ctx)
          elapsed (- (System/currentTimeMillis) start)]
      (is (map? result))
      (is (>= elapsed 950)
          (str "Should have slept ~1000ms; elapsed=" elapsed))
      (is (< elapsed 1500)
          (str "Should not have slept much past 1000ms; elapsed=" elapsed)))))

(deftest test-wait-duration-decimal-seconds
  (testing ":alice waits 0.1 seconds — sub-second precision via decimal arg"
    (let [ctx     (make-ctx-with-subject :alice (make-eventual-browser {}))
          start   (System/currentTimeMillis)
          result  (invoke-step ":alice waits 0.1 seconds" ctx)
          elapsed (- (System/currentTimeMillis) start)]
      (is (map? result))
      (is (>= elapsed 95)
          (str "Should have slept ~100ms; elapsed=" elapsed))
      (is (< elapsed 250)
          (str "Should not have slept much past 100ms; elapsed=" elapsed)))))

(deftest test-wait-duration-fractional-seconds
  (testing ":alice waits 0.25 seconds — multi-digit decimal arg"
    (let [ctx     (make-ctx-with-subject :alice (make-eventual-browser {}))
          start   (System/currentTimeMillis)
          result  (invoke-step ":alice waits 0.25 seconds" ctx)
          elapsed (- (System/currentTimeMillis) start)]
      (is (map? result))
      (is (>= elapsed 240)
          (str "Should have slept ~250ms; elapsed=" elapsed))
      (is (< elapsed 400)
          (str "Should not have slept much past 250ms; elapsed=" elapsed)))))

;; =============================================================================
;; :wait :for-element  (mirror of :see :visible)
;; =============================================================================

(deftest test-wait-for-element-happy-path
  (testing ":alice waits for {locator} succeeds when element becomes visible"
    (let [browser (make-eventual-browser {:visible-after 3})
          ctx     (make-ctx-with-subject :alice browser)
          result  (invoke-step ":alice waits for {:css \".loaded\"}" ctx)]
      (is (map? result))
      (is (>= (-> browser :poll-counts deref :visible?) 3)
          "Should have polled visible? at least 3 times before passing"))))

(deftest test-wait-for-element-timeout
  (testing ":alice waits for {locator} throws when element never becomes visible"
    (let [browser (never-met-browser)
          ctx     (make-ctx-with-subject :alice browser)]
      (binding [browser-stepdefs/*wait-timeout-ms* 200]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Waited but element never became visible"
             (invoke-step ":alice waits for {:css \".never\"}" ctx))))
      (is (pos? (-> browser :poll-counts deref :visible? (or 0)))
          "Should have polled visible? at least once before timeout"))))

;; =============================================================================
;; :wait :for-text  (mirror of :see :text)
;; =============================================================================

(deftest test-wait-for-text-happy-path
  (testing ":alice waits for {locator} to show 'X' succeeds when text appears"
    (let [browser (make-eventual-browser {:text-after  2
                                          :target-text "Welcome, alice"})
          ctx     (make-ctx-with-subject :alice browser)
          result  (invoke-step ":alice waits for {:id \"greeting\"} to show 'Welcome'" ctx)]
      (is (map? result))
      (is (>= (-> browser :poll-counts deref :get-text) 2)
          "Should have polled get-text at least 2 times before passing"))))

(deftest test-wait-for-text-substring-match
  (testing "happy-path matches as substring (mirrors :see :text contains semantics)"
    (let [browser (make-eventual-browser {:target-text "Welcome, alice — your dashboard"})
          ctx     (make-ctx-with-subject :alice browser)
          result  (invoke-step ":alice waits for {:id \"greeting\"} to show 'dashboard'" ctx)]
      (is (map? result)))))

(deftest test-wait-for-text-timeout
  (testing ":alice waits for {locator} to show 'X' throws when text never appears"
    (let [browser (never-met-browser)
          ctx     (make-ctx-with-subject :alice browser)]
      (binding [browser-stepdefs/*wait-timeout-ms* 200]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Waited but element text never contained 'Hello'"
             (invoke-step ":alice waits for {:id \"x\"} to show 'Hello'" ctx)))))))

;; =============================================================================
;; :wait :for-count  (mirror of :see :count)
;; =============================================================================

(deftest test-wait-for-count-happy-path
  (testing ":alice waits for N {locator} succeeds when count reaches N"
    (let [browser (make-eventual-browser {:count-after 2 :target-count 3})
          ctx     (make-ctx-with-subject :alice browser)
          result  (invoke-step ":alice waits for 3 {:css \".item\"}" ctx)]
      (is (map? result))
      (is (>= (-> browser :poll-counts deref :element-count) 2)
          "Should have polled element-count at least 2 times"))))

(deftest test-wait-for-count-timeout
  (testing ":alice waits for N {locator} throws when count never reaches N"
    (let [browser (never-met-browser)
          ctx     (make-ctx-with-subject :alice browser)]
      (binding [browser-stepdefs/*wait-timeout-ms* 200]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Waited but element count never reached 5"
             (invoke-step ":alice waits for 5 {:css \".item\"}" ctx)))))))

;; =============================================================================
;; Pattern disambiguation — sanity check that all four :wait frames bind to
;; distinct stepdefs (no ambiguous matches).
;; =============================================================================

(deftest test-wait-frames-bind-unambiguously
  (testing "Each :wait frame matches exactly one stepdef"
    (let [matches (fn [text]
                    (filter #(re-matches (:pattern %) text)
                            (registry/all-stepdefs)))]
      (is (= 1 (count (matches ":alice waits 3 seconds")))
          ":duration matches exactly one stepdef")
      (is (= 1 (count (matches ":alice waits for {:id \"x\"}")))
          ":for-element matches exactly one stepdef")
      (is (= 1 (count (matches ":alice waits for {:id \"x\"} to show 'Hello'")))
          ":for-text matches exactly one stepdef")
      (is (= 1 (count (matches ":alice waits for 3 {:id \"x\"}")))
          ":for-count matches exactly one stepdef"))))

;; =============================================================================
;; AC #5 — SVO validation accepts the new verbs/frames
;;
;; Loads the real default :web glossary (verbs-web.edn) and binds a pickle
;; whose steps exercise each new verb/frame combination. Strict-mode opts
;; surface any svo issue.
;; =============================================================================

(defn- make-step [text]
  {:step/id        (java.util.UUID/randomUUID)
   :step/text      text
   :step/arguments nil})

(defn- make-pickle [name step-texts]
  {:pickle/id    (java.util.UUID/randomUUID)
   :pickle/name  name
   :pickle/steps (mapv make-step step-texts)})

(deftest test-svo-validation-accepts-hover-and-wait
  (testing "Default :web glossary + sl-jsn stepdefs bind cleanly under strict SVO"
    (let [pickle  (make-pickle
                   "sl-jsn coverage"
                   [":alice hovers over {:css \".menu-trigger\"}"
                    ":alice waits 1 seconds"
                    ":alice waits for {:id \"loaded\"}"
                    ":alice waits for {:id \"greeting\"} to show 'Hello'"
                    ":alice waits for 3 {:css \".item\"}"])
          ;; Real default :web glossary, plus a subjects map containing :alice.
          web-glossary (glossary/load-default-verbs :web)
          glossary {:subjects {:alice {}}
                    :verbs    {:web (:verbs web-glossary)}}
          opts {:glossary glossary
                :interfaces {:web {:type :web :adapter :etaoin}}
                :svo {:unknown-subject :error
                      :unknown-verb    :error
                      :unknown-interface :error}}
          result (bind/bind-suite [pickle] (registry/all-stepdefs) opts)]
      (is (:runnable? result)
          (str "Suite should be runnable. Diagnostics: "
               (:diagnostics result)))
      (is (empty? (-> result :diagnostics :svo-issues))
          (str "Expected no svo-issues, got: "
               (-> result :diagnostics :svo-issues)))
      (is (empty? (-> result :diagnostics :undefined))
          (str "Expected no undefined steps, got: "
               (-> result :diagnostics :undefined)))
      (is (empty? (-> result :diagnostics :ambiguous))
          (str "Expected no ambiguous matches, got: "
               (-> result :diagnostics :ambiguous))))))
