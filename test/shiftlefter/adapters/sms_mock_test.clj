(ns shiftlefter.adapters.sms-mock-test
  "Tests for the adapter-layer factory/cleanup wrapper around MockSMS."
  (:require [clojure.test :refer [deftest is testing]]
            [shiftlefter.adapters.registry :as registry]
            [shiftlefter.adapters.sms-mock :as adapter]
            [shiftlefter.sms.protocol :as sms])
  (:import (shiftlefter.sms.mock MockSMS)))

(deftest create-sms-empty-config
  (testing "no config → ok with MockSMS and nil :from-number"
    (let [{:keys [ok]} (adapter/create-sms {})]
      (is (instance? MockSMS (:sms ok)))
      (is (satisfies? sms/ISMS (:sms ok)))
      (is (nil? (:from-number ok))))))

(deftest create-sms-with-from-number
  (testing ":from-number is passed through unchanged"
    (let [{:keys [ok]} (adapter/create-sms {:from-number "+15550000000"})]
      (is (= "+15550000000" (:from-number ok)))
      (is (instance? MockSMS (:sms ok))))))

(deftest create-sms-each-call-is-fresh
  (testing "each factory call returns an independent MockSMS"
    (let [a (:ok (adapter/create-sms {}))
          b (:ok (adapter/create-sms {}))]
      (sms/send! (:sms a) {:from "+1" :to "+2" :body "to a"})
      (let [{a-msgs :ok} (sms/query-messages (:sms a) {:since-ts java.time.Instant/EPOCH})
            {b-msgs :ok} (sms/query-messages (:sms b) {:since-ts java.time.Instant/EPOCH})]
        (is (= 1 (count a-msgs)))
        (is (= 0 (count b-msgs))
            "Messages on one mock must not appear on a sibling mock")))))

(deftest close-sms-no-op
  (testing "cleanup returns {:ok :closed} regardless of capability shape"
    (is (= {:ok :closed} (adapter/close-sms {:sms :anything})))
    (is (= {:ok :closed} (adapter/close-sms nil)))))

(deftest registered-as-sms-mock
  (testing ":sms-mock is in the default registry and creates a MockSMS"
    (is (contains? (set (registry/known-adapters)) :sms-mock))
    (let [{:keys [ok]} (registry/create-capability :sms-mock {})]
      (is (instance? MockSMS (:sms ok))))))
