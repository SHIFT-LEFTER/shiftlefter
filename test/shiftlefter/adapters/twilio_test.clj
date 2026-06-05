(ns shiftlefter.adapters.twilio-test
  "Tests for the adapter-layer factory/cleanup wrapper around the
   Twilio SMS implementation. Covers config resolution and missing-creds
   error reporting."
  (:require [clojure.test :refer [deftest is testing]]
            [shiftlefter.adapters.twilio :as adapter]
            [shiftlefter.sms.protocol :as sms])
  (:import (shiftlefter.sms.twilio TwilioSMS)))

(deftest create-sms-missing-creds
  (testing "no config and no env → :adapter/missing-config error"
    (with-redefs [adapter/resolve-creds (fn [_] {:account-sid nil :auth-token nil})]
      (let [{:keys [error]} (#'adapter/create-sms {})]
        (is (= :adapter/missing-config (:type error)))
        (is (= [:account-sid :auth-token] (:missing error)))))))

(deftest create-sms-partial-config
  (testing "auth-token missing → reports just that key"
    (with-redefs [adapter/resolve-creds (fn [_] {:account-sid "AC" :auth-token nil})]
      (let [{:keys [error]} (adapter/create-sms {:account-sid "AC"})]
        (is (= [:auth-token] (:missing error)))))))

(deftest create-sms-success
  (testing "full config → ok with TwilioSMS and from-number"
    (let [{:keys [ok]} (adapter/create-sms
                       {:account-sid "AC1" :auth-token "tok" :from-number "+15550000000"})]
      (is (instance? TwilioSMS (:sms ok)))
      (is (= "+15550000000" (:from-number ok)))
      (is (satisfies? sms/ISMS (:sms ok))))))

(deftest create-sms-env-fallback
  (testing "env vars supply creds when config keys absent"
    (with-redefs [adapter/resolve-creds (fn [_] {:account-sid "AC-env" :auth-token "tok-env"})]
      (let [{:keys [ok]} (adapter/create-sms {})]
        (is (= "AC-env" (:account-sid (:sms ok))))
        (is (= "tok-env" (:auth-token (:sms ok))))))))

(deftest close-sms-no-op
  (testing "cleanup returns {:ok :closed} regardless of capability shape"
    (is (= {:ok :closed} (adapter/close-sms {:sms :anything})))
    (is (= {:ok :closed} (adapter/close-sms nil)))))
