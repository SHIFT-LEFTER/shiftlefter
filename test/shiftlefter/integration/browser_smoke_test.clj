(ns shiftlefter.integration.browser-smoke-test
  "Live integration tests for browser functionality.

   These tests require a running ChromeDriver instance and are SKIPPED
   by default. To run them:

     SHIFTLEFTER_LIVE_WEBDRIVER=1 ./bin/kaocha

   Or run just the integration tests:

     SHIFTLEFTER_LIVE_WEBDRIVER=1 ./bin/kaocha --focus :integration"
  (:require [clojure.test :refer [deftest is testing]]
            [shiftlefter.webdriver.etaoin.session :as session]
            [shiftlefter.browser.protocol :as bp]
            [shiftlefter.browser.locators :as locators]
            [shiftlefter.repl :as repl]
            [shiftlefter.stepengine.registry :as registry]))

;; -----------------------------------------------------------------------------
;; Test Gating
;; -----------------------------------------------------------------------------

(def ^:private live-webdriver?
  "True if live webdriver tests should run."
  (some? (System/getenv "SHIFTLEFTER_LIVE_WEBDRIVER")))

(def ^:private webdriver-url
  "WebDriver URL for integration tests."
  (or (System/getenv "SHIFTLEFTER_WEBDRIVER_URL")
      "http://127.0.0.1:9515"))

;; -----------------------------------------------------------------------------
;; Etaoin Browser Implementation (for integration tests)
;; -----------------------------------------------------------------------------

(defrecord EtaoinBrowser [driver]
  bp/IBrowser
  (open-to! [this url]
    (require '[etaoin.api :as eta])
    ((resolve 'etaoin.api/go) driver url)
    this)
  (click! [this locator]
    (require '[etaoin.api :as eta])
    ((resolve 'etaoin.api/click) driver (:q locator))
    this)
  (doubleclick! [this locator]
    (require '[etaoin.api :as eta])
    ((resolve 'etaoin.api/double-click) driver (:q locator))
    this)
  (rightclick! [this _locator]
    ;; Etaoin doesn't have direct right-click, use actions
    this)
  (move-to! [this locator]
    (require '[etaoin.api :as eta])
    ((resolve 'etaoin.api/mouse-move-to) driver (:q locator))
    this)
  (drag-to! [this _from _to]
    ;; Simplified - real impl would use actions API
    this)
  (fill! [this locator text]
    (require '[etaoin.api :as eta])
    ((resolve 'etaoin.api/fill) driver (:q locator) text)
    this)
  (element-count [_this locator]
    (require '[etaoin.api :as eta])
    (count ((resolve 'etaoin.api/query-all) driver (:q locator))))

  (get-text [_this locator]
    (require '[etaoin.api :as eta])
    ((resolve 'etaoin.api/get-element-text) driver (:q locator)))

  (get-url [_this]
    (require '[etaoin.api :as eta])
    ((resolve 'etaoin.api/get-url) driver))

  (get-title [_this]
    (require '[etaoin.api :as eta])
    ((resolve 'etaoin.api/get-title) driver))

  (visible? [_this locator]
    (require '[etaoin.api :as eta])
    (try
      ((resolve 'etaoin.api/displayed?) driver (:q locator))
      (catch Exception _ false))))

;; -----------------------------------------------------------------------------
;; Integration Tests
;; -----------------------------------------------------------------------------

(deftest ^:integration full-browser-workflow-test
  (if live-webdriver?
    (testing "Full browser workflow: create → navigate → query → close"
      (let [driver (session/make-driver webdriver-url)
            create-result (session/create-session! driver)]
        (is (:ok create-result) "Session creation should succeed")

        (when (:ok create-result)
          (let [attached-driver (:ok create-result)
                etaoin-driver (:etaoin-driver create-result)
                browser (->EtaoinBrowser etaoin-driver)]
            (try
              ;; Navigate
              (bp/open-to! browser "https://example.com")

              ;; Query - example.com has exactly 1 h1
              (let [h1-count (bp/element-count browser (locators/resolve-locator {:css "h1"}))]
                (is (= 1 h1-count) "example.com should have exactly 1 h1"))

              ;; Query links
              (let [link-count (bp/element-count browser (locators/resolve-locator {:css "a"}))]
                (is (pos? link-count) "example.com should have at least 1 link"))

              (finally
                ;; Always cleanup
                (session/close-session! attached-driver)))))))
    (testing "skipped - no live webdriver"
      (is true "Integration test skipped"))))

(deftest ^:integration session-persistence-test
  (if live-webdriver?
    (testing "Session can be detached and reattached"
      (let [driver (session/make-driver webdriver-url)
            {:keys [ok]} (session/create-session! driver)]
        (when ok
          (try
            (let [session-id (:session ok)
                  ;; Detach (pure operation)
                  detached (session/detach ok)]
              (is (nil? (:session detached)) "Detached driver has no session")

              ;; Reattach
              (let [reattached (session/attach detached session-id)]
                (is (= session-id (:session reattached)) "Reattached has same session")

                ;; Probe should succeed
                (let [probe-result (session/probe-session! reattached)]
                  (is (= :alive (:ok probe-result)) "Reattached session is alive"))))
            (finally
              (session/close-session! ok))))))
    (testing "skipped - no live webdriver"
      (is true "Integration test skipped"))))

(deftest ^:integration repl-browser-workflow-test
  (if live-webdriver?
    (testing "REPL workflow with browser stepdefs"
      ;; Load stepdefs
      (require 'shiftlefter.stepdefs.browser :reload)

      (let [driver (session/make-driver webdriver-url)
            result (session/create-session! driver)
            ok (:ok result)]
        (when ok
          (try
            ;; This test validates the REPL integration but doesn't
            ;; actually use the REPL free mode because that requires
            ;; browser capability to already be in context
            (is (some? ok) "Driver created successfully")
            (is (some? (:etaoin-driver result)) "Etaoin driver available")
            (finally
              (registry/clear-registry!)
              (session/close-session! ok))))))
    (testing "skipped - no live webdriver"
      (is true "Integration test skipped"))))

;; -----------------------------------------------------------------------------
;; Unit Test Coverage Verification
;; -----------------------------------------------------------------------------

(deftest locator-resolution-coverage-test
  (testing "Locator resolution is comprehensively tested"
    ;; Map forms
    (is (= {:q {:css "#x"}} (locators/resolve-locator {:css "#x"})))
    (is (= {:q {:xpath "//div"}} (locators/resolve-locator {:xpath "//div"})))
    (is (= {:q {:id "foo"}} (locators/resolve-locator {:id "foo"})))

    ;; Vector forms
    (is (= {:q {:css "#x"}} (locators/resolve-locator [:css "#x"])))

    ;; Passthrough
    (is (= {:q "#x"} (locators/resolve-locator "#x")))
    (is (= {:q :foo} (locators/resolve-locator :foo)))

    ;; Invalid
    (is (:errors (locators/resolve-locator {:invalid "x"})))
    (is (:errors (locators/resolve-locator [:invalid "x"])))
    (is (:errors (locators/resolve-locator 123)))))

(deftest session-pure-semantics-test
  (testing "attach/detach are pure operations"
    (let [driver {:webdriver-url "http://localhost:9515"}
          attached (session/attach driver "abc123")
          detached (session/detach attached)]
      ;; Attach adds session
      (is (= "abc123" (:session attached)))
      (is (= "http://localhost:9515" (:webdriver-url attached)))

      ;; Detach removes session
      (is (nil? (:session detached)))
      (is (= "http://localhost:9515" (:webdriver-url detached)))

      ;; Original unchanged
      (is (nil? (:session driver))))))

(deftest repl-lifecycle-coverage-test
  (testing "REPL lifecycle semantics verified"
    ;; These tests verify the mechanics work; actual browser interaction
    ;; is tested in the integration tests above
    (repl/clear!)

    ;; Surface marking
    (repl/mark-surface! :alice)
    (is (true? (repl/surface? :alice)))

    ;; reset-ctxs! returns actions
    (let [actions (repl/reset-ctxs!)]
      (is (vector? actions)))

    ;; Surfaces cleared by clear!
    (repl/clear!)
    (is (false? (repl/surface? :alice)))))

;; -----------------------------------------------------------------------------
;; Persistent Subject Integration Tests
;; -----------------------------------------------------------------------------
;; These require SHIFTLEFTER_LIVE_CHROME=1 (Chrome installed, no ChromeDriver)

(def ^:private live-chrome?
  "True if live Chrome tests should run."
  (some? (System/getenv "SHIFTLEFTER_LIVE_CHROME")))

(deftest ^:integration persistent-subject-lifecycle-test
  (if live-chrome?
    (let [subject-name (keyword (str "test-" (System/currentTimeMillis)))]
      (require '[shiftlefter.subjects :as subjects])
      (try
        (testing "init-persistent! creates subject and launches Chrome"
          (let [result ((resolve 'shiftlefter.subjects/init-persistent!) subject-name {:stealth true})]
            (is (= :connected (:status result)) (str "Failed: " result))
            (is (integer? (:port result)))
            (is (integer? (:pid result)))
            (is (some? (:browser result)))))

        (testing "list-persistent shows the subject as alive"
          (let [subjects ((resolve 'shiftlefter.subjects/list-persistent))
                our-subject (first (filter #(= (name subject-name) (:name %)) subjects))]
            (is (some? our-subject))
            (is (= :alive (:status our-subject)))))

        (testing "destroy-persistent! kills Chrome and cleans up"
          (let [result ((resolve 'shiftlefter.subjects/destroy-persistent!) subject-name)]
            (is (= :destroyed (:status result)))))

        (testing "subject no longer listed after destroy"
          (let [subjects ((resolve 'shiftlefter.subjects/list-persistent))
                our-subject (first (filter #(= (name subject-name) (:name %)) subjects))]
            (is (nil? our-subject))))

        (finally
          ;; Cleanup in case test failed mid-way
          (try
            ((resolve 'shiftlefter.subjects/destroy-persistent!) subject-name)
            (catch Exception _)))))
    (testing "skipped - SHIFTLEFTER_LIVE_CHROME not set"
      (is true "Integration test skipped"))))
