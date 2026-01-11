(ns shiftlefter.webdriver.etaoin.session-test
  (:require [clojure.test :refer [deftest is testing]]
            [shiftlefter.webdriver.etaoin.session :as sess]))

;; -----------------------------------------------------------------------------
;; Pure Function Tests (no chromedriver needed)
;; -----------------------------------------------------------------------------

(deftest make-driver-test
  (testing "creates driver map from URL"
    (let [d (sess/make-driver "http://127.0.0.1:9515")]
      (is (= "http://127.0.0.1:9515" (:webdriver-url d)))
      (is (= :chrome (:type d)))
      (is (map? (:capabilities d)))))

  (testing "respects headless option"
    (let [d (sess/make-driver "http://localhost:9515" {:headless true})]
      (is (= ["--headless"] (get-in d [:capabilities :chromeOptions :args])))))

  (testing "default is headed (no headless args)"
    (let [d (sess/make-driver "http://localhost:9515")]
      (is (nil? (get-in d [:capabilities :chromeOptions :args]))))))

(deftest attach-test
  (testing "attaches session-id to driver"
    (let [d (sess/make-driver "http://127.0.0.1:9515")
          d2 (sess/attach d "abc123")]
      (is (= "abc123" (:session d2)))
      (is (= "http://127.0.0.1:9515" (:webdriver-url d2)))))

  (testing "overwrites existing session"
    (let [d (-> (sess/make-driver "http://127.0.0.1:9515")
                (sess/attach "old")
                (sess/attach "new"))]
      (is (= "new" (:session d))))))

(deftest detach-test
  (testing "removes session from driver"
    (let [d (-> (sess/make-driver "http://127.0.0.1:9515")
                (sess/attach "abc123")
                sess/detach)]
      (is (nil? (:session d)))
      (is (= "http://127.0.0.1:9515" (:webdriver-url d)))))

  (testing "works on driver without session"
    (let [d (sess/make-driver "http://127.0.0.1:9515")
          d2 (sess/detach d)]
      (is (nil? (:session d2))))))

(deftest attached?-test
  (testing "returns true when session attached"
    (let [d (sess/attach (sess/make-driver "http://127.0.0.1:9515") "abc")]
      (is (true? (sess/attached? d)))))

  (testing "returns false when no session"
    (let [d (sess/make-driver "http://127.0.0.1:9515")]
      (is (false? (sess/attached? d)))))

  (testing "returns false after detach"
    (let [d (-> (sess/make-driver "http://127.0.0.1:9515")
                (sess/attach "abc")
                sess/detach)]
      (is (false? (sess/attached? d))))))

;; -----------------------------------------------------------------------------
;; Acceptance Criteria (from spec)
;; -----------------------------------------------------------------------------

(deftest acceptance-criteria-test
  (testing "Task 2.5.6 AC: attach/detach pure semantics"
    (let [driver {:webdriver-url "http://127.0.0.1:9515"}
          d2 (sess/attach driver "abc123")
          d3 (sess/detach d2)]
      (is (= "abc123" (:session d2)))
      (is (nil? (:session d3))))))

;; -----------------------------------------------------------------------------
;; Side-Effecting Functions (require chromedriver)
;; These tests are skipped unless SHIFTLEFTER_LIVE_WEBDRIVER env var is set
;; -----------------------------------------------------------------------------

(def ^:private live-webdriver?
  "True if live webdriver tests should run."
  (some? (System/getenv "SHIFTLEFTER_LIVE_WEBDRIVER")))

;; Integration tests only run when chromedriver is available
;; To run: SHIFTLEFTER_LIVE_WEBDRIVER=1 ./bin/kaocha

(deftest ^:integration create-session-test
  (if live-webdriver?
    (testing "creates session with chromedriver"
      (let [d (sess/make-driver "http://127.0.0.1:9515")
            result (sess/create-session! d)]
        (is (:ok result))
        (is (string? (get-in result [:ok :session])))
        ;; Clean up
        (sess/close-session! (:ok result))))
    (testing "skipped - no live webdriver"
      (is true "Integration test skipped"))))

(deftest ^:integration probe-session-test
  (if live-webdriver?
    (testing "probe returns :alive for valid session"
      (let [d (sess/make-driver "http://127.0.0.1:9515")
            {:keys [ok]} (sess/create-session! d)
            probe-result (sess/probe-session! ok)]
        (is (= :alive (:ok probe-result)))
        ;; Clean up
        (sess/close-session! ok)))
    (testing "skipped - no live webdriver"
      (is true "Integration test skipped"))))

(deftest ^:integration close-session-test
  (if live-webdriver?
    (testing "close removes session"
      (let [d (sess/make-driver "http://127.0.0.1:9515")
            {:keys [ok]} (sess/create-session! d)
            close-result (sess/close-session! ok)]
        (is (:ok close-result))
        (is (nil? (get-in close-result [:ok :session])))))
    (testing "skipped - no live webdriver"
      (is true "Integration test skipped"))))

;; Test for session-dead error (without live webdriver)
(deftest probe-session-no-attachment-test
  (testing "probe returns error when no session attached"
    (let [d (sess/make-driver "http://127.0.0.1:9515")
          result (sess/probe-session! d)]
      (is (= :webdriver/session-dead (get-in result [:error :type]))))))

(deftest close-session-no-attachment-test
  (testing "close succeeds when no session attached"
    (let [d (sess/make-driver "http://127.0.0.1:9515")
          result (sess/close-session! d)]
      (is (:ok result)))))

;; -----------------------------------------------------------------------------
;; Connect to Existing Chrome Tests (Task 2.6.6)
;; -----------------------------------------------------------------------------

(deftest build-debugger-capabilities-test
  (testing "builds capabilities with debuggerAddress"
    (let [caps (sess/build-debugger-capabilities {:port 9222})]
      (is (= "127.0.0.1:9222" (get-in caps [:goog:chromeOptions :debuggerAddress])))
      (is (nil? (get-in caps [:goog:chromeOptions :excludeSwitches])))))

  (testing "adds stealth options when stealth true"
    (let [caps (sess/build-debugger-capabilities {:port 9333 :stealth true})]
      (is (= "127.0.0.1:9333" (get-in caps [:goog:chromeOptions :debuggerAddress])))
      (is (= ["enable-automation"] (get-in caps [:goog:chromeOptions :excludeSwitches])))
      (is (false? (get-in caps [:goog:chromeOptions :useAutomationExtension])))))

  (testing "stealth false doesn't add stealth opts"
    (let [caps (sess/build-debugger-capabilities {:port 9222 :stealth false})]
      (is (nil? (get-in caps [:goog:chromeOptions :excludeSwitches]))))))

;; Integration test for connect-to-existing! requires Chrome already running
;; with --remote-debugging-port. This is harder to test automatically because
;; we'd need to launch Chrome first. For now, we just verify error handling.

(deftest connect-to-existing-error-test
  (testing "returns error when Chrome not running on port"
    (let [result (sess/connect-to-existing! {:port 19999 :stealth false})]
      (is (:error result))
      (is (= :webdriver/connect-failed (get-in result [:error :type])))
      (is (= 19999 (get-in result [:error :data :port]))))))
