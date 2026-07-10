(ns shiftlefter.adapters.etaoin-test
  "Unit tests for the etaoin adapter's cleanup hardening (sl-9vag).

   The happy path (create → quit) is covered by the gated live e2e suites;
   these tests pin the wrong-shape behavior that used to leak browsers:
   close-browser handed a map without :etaoin-driver must report an error,
   never {:ok :closed} — a silent success here left chromedriver + Chrome
   orphans running for days."
  (:require [clojure.test :refer [deftest is testing]]
            [shiftlefter.adapters.etaoin :as etaoin]))

(deftest close-browser-rejects-the-wrapped-map
  (testing "create-browser's wrapped {:ok {...}} result is the wrong shape"
    (let [wrapped {:ok {:browser :fake-browser :etaoin-driver :fake-driver}}
          result  (etaoin/close-browser wrapped)]
      (is (:error result) "wrapped map must NOT report success — nothing was quit")
      (is (= :adapter/cleanup-failed (-> result :error :type)))
      (is (= :etaoin (-> result :error :adapter))))))

(deftest close-browser-rejects-a-driverless-capability
  (testing "any capability without :etaoin-driver is an error, not silent green"
    (doseq [bad-shape [{} {:browser :fake-browser} nil]]
      (let [result (etaoin/close-browser bad-shape)]
        (is (= :adapter/cleanup-failed (-> result :error :type))
            (str "shape: " (pr-str bad-shape)))))))
