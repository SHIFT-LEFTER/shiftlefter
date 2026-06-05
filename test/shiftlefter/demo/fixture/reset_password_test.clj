(ns shiftlefter.demo.fixture.reset-password-test
  "Tests for the reset-password page block.

   Exercises the full 2FA flow end-to-end through the live fixture
   server, using an injected MockSMS adapter to intercept the code
   delivery — no network required."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [shiftlefter.demo.fixture.reset-password]
            [shiftlefter.demo.fixture.server :as server]
            [shiftlefter.sms.mock :as sms-mock]
            [shiftlefter.sms.protocol :as sms])
  (:import (java.time Instant)))

;; ---------------------------------------------------------------------------
;; HTTP helpers
;; ---------------------------------------------------------------------------

(defn- http-get
  ([url] (http-get url nil))
  ([url cookie]
   (let [conn (.openConnection (java.net.URL. url))]
     (.setInstanceFollowRedirects conn false)
     (when cookie
       (.setRequestProperty conn "Cookie" cookie))
     {:status  (.getResponseCode conn)
      :headers (into {} (.getHeaderFields conn))
      :body    (try (slurp (.getInputStream conn))
                    (catch Exception _
                      (try (slurp (.getErrorStream conn)) (catch Exception _ nil))))})))

(defn- http-post
  ([url form-data] (http-post url form-data nil))
  ([url form-data cookie]
   (let [conn     (doto (.openConnection (java.net.URL. url))
                    (.setRequestMethod "POST")
                    (.setDoOutput true)
                    (.setInstanceFollowRedirects false)
                    (.setRequestProperty "Content-Type" "application/x-www-form-urlencoded"))
         _        (when cookie (.setRequestProperty conn "Cookie" cookie))
         body-str (->> form-data
                       (map (fn [[k v]]
                              (str (java.net.URLEncoder/encode (name k) "UTF-8") "="
                                   (java.net.URLEncoder/encode v "UTF-8"))))
                       (str/join "&"))]
     (with-open [out (.getOutputStream conn)]
       (.write out (.getBytes body-str "UTF-8")))
     {:status  (.getResponseCode conn)
      :headers (into {} (.getHeaderFields conn))
      :body    (try (slurp (.getInputStream conn))
                    (catch Exception _
                      (try (slurp (.getErrorStream conn)) (catch Exception _ nil))))})))

(defn- extract-session-cookie [response]
  (some->> response
           :headers
           (some (fn [[k v]] (when (= "Set-Cookie" k) (first v))))
           (re-find #"fixture-session=([^;]+)")
           second))

;; ---------------------------------------------------------------------------
;; Config helpers
;; ---------------------------------------------------------------------------

(defn- fixture-config [sms-instance]
  {:users {"alice" {:password "secret1"
                    :phone    "+15550001111"
                    :email    "alice@example.com"}
           "bob"   {:password "secret2"
                    :phone    "+15550002222"
                    :email    "bob@example.com"}}
   :pages [:reset-password]
   :sms   sms-instance})

(defn- last-message-to [mock phone]
  (let [{:keys [ok]} (sms/query-messages mock {:since-ts Instant/EPOCH :to phone})]
    (last ok)))

(defn- extract-code [body]
  (second (re-find #"(\d{6})" body)))

;; ---------------------------------------------------------------------------
;; Email form
;; ---------------------------------------------------------------------------

(deftest email-form-renders
  (testing "GET /reset-password returns the email entry form"
    (let [mock (sms-mock/make-mock-sms)]
      (server/with-fixture-server (fixture-config mock)
        (fn [{:keys [base-url]}]
          (let [resp (http-get (str base-url "/reset-password"))]
            (is (= 200 (:status resp)))
            (is (str/includes? (:body resp) "name=\"email\""))))))))

;; ---------------------------------------------------------------------------
;; Code delivery
;; ---------------------------------------------------------------------------

(deftest post-email-sends-code-to-user-phone
  (testing "POST valid email sends a 6-digit code via the injected ISMS to the user's phone"
    (let [mock (sms-mock/make-mock-sms)]
      (server/with-fixture-server (fixture-config mock)
        (fn [{:keys [base-url]}]
          (let [resp (http-post (str base-url "/reset-password")
                                {:email "alice@example.com"})]
            (is (= 302 (:status resp)) "should redirect after send")
            (is (str/includes? (get-in resp [:headers "Location"] "")
                               "/reset-password/verify")
                "Location header should point at verify form")

            (let [msg (last-message-to mock "+15550001111")]
              (is (some? msg) "MockSMS should have received a message")
              (is (= "+15550001111" (:to msg)))
              (is (re-find #"\d{6}" (:body msg))
                  "Body should contain a 6-digit code")
              (is (nil? (last-message-to mock "+15550002222"))
                  "Bob should have received nothing"))))))))

(deftest post-email-uses-configured-from-number
  (testing "SMS is sent from :sms-from when configured"
    (let [mock (sms-mock/make-mock-sms)]
      (server/with-fixture-server
        (assoc (fixture-config mock) :sms-from "+15559999999")
        (fn [{:keys [base-url]}]
          (http-post (str base-url "/reset-password") {:email "alice@example.com"})
          (let [msg (last-message-to mock "+15550001111")]
            (is (= "+15559999999" (:from msg)))))))))

(deftest post-unknown-email-does-not-send
  (testing "Unknown email shows error and sends no SMS"
    (let [mock (sms-mock/make-mock-sms)]
      (server/with-fixture-server (fixture-config mock)
        (fn [{:keys [base-url]}]
          (let [resp (http-post (str base-url "/reset-password")
                                {:email "nobody@example.com"})]
            (is (= 200 (:status resp)))
            (is (str/includes? (:body resp) "No user with that email"))
            (is (nil? (last-message-to mock "+15550001111")))))))))

;; ---------------------------------------------------------------------------
;; Verification
;; ---------------------------------------------------------------------------

(deftest full-flow-correct-code-succeeds
  (testing "Submitting the code received via SMS completes verification"
    (let [mock (sms-mock/make-mock-sms)]
      (server/with-fixture-server (fixture-config mock)
        (fn [{:keys [base-url]}]
          (let [send-resp (http-post (str base-url "/reset-password")
                                     {:email "alice@example.com"})
                cookie-value (extract-session-cookie send-resp)
                cookie (str "fixture-session=" cookie-value)
                code   (-> (last-message-to mock "+15550001111") :body extract-code)
                verify-resp (http-post (str base-url "/reset-password/verify")
                                       {:code code}
                                       cookie)]
            (is (= 200 (:status verify-resp)))
            (is (str/includes? (:body verify-resp) "Code verified for alice"))))))))

(deftest full-flow-wrong-code-fails
  (testing "Submitting an incorrect code leaves the reset in place and reports error"
    (let [mock (sms-mock/make-mock-sms)]
      (server/with-fixture-server (fixture-config mock)
        (fn [{:keys [base-url]}]
          (let [send-resp    (http-post (str base-url "/reset-password")
                                        {:email "alice@example.com"})
                cookie       (str "fixture-session="
                                  (extract-session-cookie send-resp))
                verify-resp  (http-post (str base-url "/reset-password/verify")
                                        {:code "000000"}
                                        cookie)]
            (is (= 200 (:status verify-resp)))
            (is (str/includes? (:body verify-resp) "Invalid code"))))))))

(deftest verify-without-prior-reset-rejects
  (testing "POST /verify with no reset state returns 400"
    (let [mock (sms-mock/make-mock-sms)]
      (server/with-fixture-server (fixture-config mock)
        (fn [{:keys [base-url]}]
          (let [resp (http-post (str base-url "/reset-password/verify")
                                {:code "123456"})]
            (is (= 400 (:status resp)))
            (is (str/includes? (:body resp) "No reset in progress"))))))))

;; ---------------------------------------------------------------------------
;; Defaults
;; ---------------------------------------------------------------------------

(deftest sms-defaults-to-mock-when-absent
  (testing "Fixture works without explicit :sms — build-handler supplies a MockSMS"
    (server/with-fixture-server
      (dissoc (fixture-config nil) :sms)
      (fn [{:keys [base-url]}]
        ;; Absent any inspection hook, the best we can do is confirm the
        ;; flow still returns a redirect (no NPE from a missing adapter).
        (let [resp (http-post (str base-url "/reset-password")
                              {:email "alice@example.com"})]
          (is (= 302 (:status resp))))))))

;; ---------------------------------------------------------------------------
;; Backward compatibility
;; ---------------------------------------------------------------------------

(deftest flat-user-format-still-works-for-login
  ;; AC#5: existing handler tests still pass. Covered by handler/login tests
  ;; elsewhere; here we just sanity-check that a reset attempt against a
  ;; flat-format user (no :email) fails gracefully rather than crashing.
  (testing "Flat-format users (password strings) are not eligible for reset-by-email"
    (let [mock (sms-mock/make-mock-sms)]
      (server/with-fixture-server
        {:users {"legacy" "plain-secret"}
         :pages [:reset-password]
         :sms   mock}
        (fn [{:keys [base-url]}]
          (let [resp (http-post (str base-url "/reset-password")
                                {:email "legacy@example.com"})]
            (is (= 200 (:status resp)))
            (is (str/includes? (:body resp) "No user with that email"))))))))
