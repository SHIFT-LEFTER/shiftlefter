(ns shiftlefter.sms.twilio-integration-test
  "Integration test against Twilio's real REST endpoint using the
   account's test credentials. Exercises TLS, Basic auth, request
   encoding, and JSON response parsing end-to-end without sending an
   actual SMS.

   To run:
     SHIFTLEFTER_LIVE_TWILIO=1 \\
     TWILIO_TEST_SID=AC... \\
     TWILIO_TEST_TOKEN=... \\
     ./bin/kaocha

   Test credentials are listed in the Twilio console under
   Account → API keys & tokens → Test Credentials. The 'magic'
   from-number `+15005550006` is the documented success recipient — Twilio
   accepts the request and returns a valid SID without actually sending."
  (:require [clojure.test :refer [deftest is testing]]
            [shiftlefter.sms.protocol :as sms]
            [shiftlefter.sms.twilio :as twilio]))

(def ^:private live-twilio?
  (some? (System/getenv "SHIFTLEFTER_LIVE_TWILIO")))

(def ^:private test-sid   (System/getenv "TWILIO_TEST_SID"))
(def ^:private test-token (System/getenv "TWILIO_TEST_TOKEN"))

(def ^:private magic-from "+15005550006")
(def ^:private magic-to   "+15005550006")

(deftest ^:integration twilio-magic-creds-send-test
  (if (and live-twilio? test-sid test-token)
    (testing "send! against Twilio test creds returns {:ok message-map}"
      (let [adapter (twilio/make-twilio-sms
                     {:account-sid test-sid :auth-token test-token})
            result  (sms/send! adapter {:from magic-from
                                         :to   magic-to
                                         :body "shiftlefter integration test"})]
        (is (contains? result :ok) (str "Got error instead: " result))
        (is (string? (get-in result [:ok :sid])))
        (is (= :outbound (get-in result [:ok :direction])))))
    (testing "skipped — set SHIFTLEFTER_LIVE_TWILIO + TWILIO_TEST_{SID,TOKEN} to run"
      (is true "Integration test skipped"))))
