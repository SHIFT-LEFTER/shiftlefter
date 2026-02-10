(ns shiftlefter.webdriver.etaoin.browser-test
  (:require [clojure.test :refer [deftest is testing]]
            [shiftlefter.webdriver.etaoin.browser :as browser]
            [shiftlefter.browser.protocol :as bp]))

;; -----------------------------------------------------------------------------
;; Factory Tests
;; -----------------------------------------------------------------------------

(deftest make-etaoin-browser-test
  (testing "creates EtaoinBrowser from driver"
    (let [fake-driver {:session "abc" :host "localhost" :port 9515}
          browser (browser/make-etaoin-browser fake-driver)]
      (is (instance? shiftlefter.webdriver.etaoin.browser.EtaoinBrowser browser))
      (is (= fake-driver (:driver browser))))))

(deftest etaoin-driver-test
  (testing "extracts driver from browser"
    (let [fake-driver {:session "abc"}
          browser (browser/make-etaoin-browser fake-driver)]
      (is (= fake-driver (browser/etaoin-driver browser))))))

;; -----------------------------------------------------------------------------
;; Protocol Implementation Tests (without live WebDriver)
;; -----------------------------------------------------------------------------

;; Note: Full protocol tests require a live WebDriver.
;; These tests verify the record satisfies the protocol.

(deftest protocol-satisfaction-test
  (testing "EtaoinBrowser satisfies IBrowser protocol"
    (let [browser (browser/make-etaoin-browser {})]
      (is (satisfies? bp/IBrowser browser)))))
