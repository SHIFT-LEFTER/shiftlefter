(ns shiftlefter.webdriver.etaoin.browser-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [shiftlefter.webdriver.etaoin.browser :as browser]
            [shiftlefter.adapters.etaoin :as eta-adapter]
            [shiftlefter.browser.locators :as locators]
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

;; -----------------------------------------------------------------------------
;; query-all + handle round-trip (live; SHIFTLEFTER_LIVE_CHROME=1)
;; -----------------------------------------------------------------------------
;;
;; Heterogeneous siblings: #root holds [ad tweet ad tweet tweet]. The 2nd
;; element MATCHING `.tweet` is "T2"; the element that is #root's 2nd *child*
;; (what the old `:nth-child(2)` selected) is "T1". query-all + index must pick
;; the match. This is the parity counterpart of the playwright test below.

(def ^:private live-chrome?
  (some? (System/getenv "SHIFTLEFTER_LIVE_CHROME")))

(def ^:private fixture-html
  (str "data:text/html,"
       "<div id='root'>"
       "<span class='ad'>A1</span>"
       "<span class='tweet'>T1</span>"
       "<span class='ad'>A2</span>"
       "<span class='tweet'>T2</span>"
       "<span class='tweet'>T3</span>"
       "</div>"))

(def ^:private live-browser (atom nil))

(defn- live-fixture [f]
  (if live-chrome?
    (let [{:keys [ok]} (eta-adapter/create-browser {:headless true})]
      (try
        (reset! live-browser (:browser ok))
        (f)
        (finally
          (eta-adapter/close-browser ok)
          (reset! live-browser nil))))
    (f)))

(use-fixtures :once live-fixture)

(deftest ^:integration query-all-live-test
  (if live-chrome?
    (let [b @live-browser
          tweet (locators/resolve-locator {:css ".tweet"})]
      (bp/open-to! b fixture-html)
      (testing "document scope finds all matches in order"
        (let [matches (bp/query-all b :document tweet)]
          (is (= 3 (count matches)))
          (is (every? :el matches))
          (testing "handles round-trip: act on the located element with no re-find"
            (is (= "T1" (bp/get-text b (nth matches 0))))
            (is (= "T2" (bp/get-text b (nth matches 1)))
                "2nd MATCH is T2 — NOT T1, which :nth-child(2) would have wrongly picked"))))
      (testing "scoped query within #root"
        (let [root (first (bp/query-all b :document (locators/resolve-locator {:id "root"})))
              scoped (bp/query-all b root (locators/resolve-locator {:css ".tweet"}))]
          (is (= 3 (count scoped)))
          (is (= "T3" (bp/get-text b (last scoped)))))))
    (testing "skipped - no SHIFTLEFTER_LIVE_CHROME=1"
      (is true))))
