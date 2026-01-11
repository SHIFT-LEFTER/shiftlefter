(ns shiftlefter.subjects.browser-test
  (:require [clojure.test :refer [deftest is testing]]
            [shiftlefter.subjects.browser :as pbrowser]
            [shiftlefter.browser.protocol :as bp]))

;; -----------------------------------------------------------------------------
;; Mock Browser for Testing
;; -----------------------------------------------------------------------------

(defrecord MockBrowser [call-log-atom fail-atom]
  bp/IBrowser
  (open-to! [this url]
    (when @fail-atom
      (throw (ex-info "invalid session id" {:type :session-error})))
    (swap! call-log-atom conj [:open-to! url])
    this)

  (click! [this locator]
    (when @fail-atom
      (throw (ex-info "invalid session id" {:type :session-error})))
    (swap! call-log-atom conj [:click! locator])
    this)

  (doubleclick! [this locator]
    (when @fail-atom
      (throw (ex-info "invalid session id" {:type :session-error})))
    (swap! call-log-atom conj [:doubleclick! locator])
    this)

  (rightclick! [this locator]
    (when @fail-atom
      (throw (ex-info "invalid session id" {:type :session-error})))
    (swap! call-log-atom conj [:rightclick! locator])
    this)

  (move-to! [this locator]
    (when @fail-atom
      (throw (ex-info "invalid session id" {:type :session-error})))
    (swap! call-log-atom conj [:move-to! locator])
    this)

  (drag-to! [this from to]
    (when @fail-atom
      (throw (ex-info "invalid session id" {:type :session-error})))
    (swap! call-log-atom conj [:drag-to! from to])
    this)

  (fill! [this locator text]
    (when @fail-atom
      (throw (ex-info "invalid session id" {:type :session-error})))
    (swap! call-log-atom conj [:fill! locator text])
    this)

  (element-count [_this locator]
    (when @fail-atom
      (throw (ex-info "invalid session id" {:type :session-error})))
    42))

(defn make-mock-browser
  "Create a mock browser for testing.
   Returns [browser call-log-atom fail-atom]"
  []
  (let [call-log (atom [])
        fail (atom false)
        browser (->MockBrowser call-log fail)]
    [browser call-log fail]))

;; -----------------------------------------------------------------------------
;; Session Error Detection Tests
;; -----------------------------------------------------------------------------

(deftest session-error-detection-test
  (testing "detects 'invalid session id' errors"
    (is (true? (#'pbrowser/session-error? (ex-info "invalid session id" {})))))

  (testing "detects 'session not found' errors"
    (is (true? (#'pbrowser/session-error? (ex-info "session not found" {})))))

  (testing "detects 'no such session' errors"
    (is (true? (#'pbrowser/session-error? (ex-info "no such session" {})))))

  (testing "doesn't match other errors"
    (is (false? (#'pbrowser/session-error? (ex-info "element not found" {}))))
    (is (false? (#'pbrowser/session-error? (ex-info "timeout" {}))))))

;; -----------------------------------------------------------------------------
;; PersistentBrowser Normal Operation Tests
;; -----------------------------------------------------------------------------

(deftest persistent-browser-normal-operation-test
  (testing "operations pass through to underlying browser"
    (let [[mock-browser call-log _fail] (make-mock-browser)
          reconnect-fn (fn [_ _] nil)  ;; Won't be called
          pb (pbrowser/make-persistent-browser mock-browser :test false reconnect-fn)]

      (bp/open-to! pb "https://example.com")
      (bp/click! pb {:q {:css "button"}})
      (bp/fill! pb {:q {:id "input"}} "hello")

      (is (= [[:open-to! "https://example.com"]
              [:click! {:q {:css "button"}}]
              [:fill! {:q {:id "input"}} "hello"]]
             @call-log)))))

(deftest persistent-browser-element-count-test
  (testing "element-count returns result from underlying browser"
    (let [[mock-browser _ _] (make-mock-browser)
          reconnect-fn (fn [_ _] nil)
          pb (pbrowser/make-persistent-browser mock-browser :test false reconnect-fn)]

      (is (= 42 (bp/element-count pb {:q {:css "div"}}))))))

;; -----------------------------------------------------------------------------
;; PersistentBrowser Reconnection Tests
;; -----------------------------------------------------------------------------

(deftest persistent-browser-reconnect-on-session-error-test
  (testing "reconnects and retries on session error"
    (let [[mock-browser-1 call-log-1 fail-1] (make-mock-browser)
          [mock-browser-2 call-log-2 _fail-2] (make-mock-browser)
          reconnect-called (atom false)
          reconnect-fn (fn [subject stealth]
                         (reset! reconnect-called true)
                         (is (= :test-subject subject))
                         (is (true? stealth))
                         mock-browser-2)
          pb (pbrowser/make-persistent-browser mock-browser-1 :test-subject true reconnect-fn)]

      ;; First call succeeds
      (bp/open-to! pb "https://example.com")
      (is (= [[:open-to! "https://example.com"]] @call-log-1))

      ;; Now make the browser fail
      (reset! fail-1 true)

      ;; Next call should trigger reconnect and retry
      (bp/click! pb {:q {:css "button"}})

      (is (true? @reconnect-called))
      ;; The retry happened on the new browser
      (is (= [[:click! {:q {:css "button"}}]] @call-log-2)))))

(deftest persistent-browser-reconnect-failure-test
  (testing "surfaces original error when reconnect fails"
    (let [[mock-browser _ fail] (make-mock-browser)
          reconnect-fn (fn [_ _] nil)  ;; Reconnect fails (returns nil)
          pb (pbrowser/make-persistent-browser mock-browser :test false reconnect-fn)]

      ;; Make browser fail
      (reset! fail true)

      ;; Should throw the original error
      (is (thrown-with-msg? Exception #"invalid session id"
            (bp/open-to! pb "https://example.com"))))))

(deftest persistent-browser-non-session-error-not-retried-test
  (testing "non-session errors are not retried"
    (let [call-log (atom [])
          fail-with-other (atom false)
          mock-browser (reify bp/IBrowser
                         (open-to! [this _url]
                           (when @fail-with-other
                             (throw (ex-info "element not found" {})))
                           (swap! call-log conj :open)
                           this))
          reconnect-called (atom false)
          reconnect-fn (fn [_ _]
                         (reset! reconnect-called true)
                         mock-browser)
          pb (pbrowser/make-persistent-browser mock-browser :test false reconnect-fn)]

      (reset! fail-with-other true)

      ;; Should throw without trying to reconnect
      (is (thrown-with-msg? Exception #"element not found"
            (bp/open-to! pb "https://example.com")))

      ;; Reconnect should NOT have been called
      (is (false? @reconnect-called)))))

;; -----------------------------------------------------------------------------
;; Underlying Browser Access Test
;; -----------------------------------------------------------------------------

(deftest underlying-browser-test
  (testing "can access underlying browser"
    (let [[mock-browser _ _] (make-mock-browser)
          pb (pbrowser/make-persistent-browser mock-browser :test false (fn [_ _] nil))]

      (is (= mock-browser (pbrowser/underlying-browser pb))))))
