(ns shiftlefter.sms.mock-test
  "Unit tests for the in-memory MockSMS implementation of ISMS + ISMSInbound."
  (:require [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is testing]]
            [shiftlefter.sms.mock :as mock]
            [shiftlefter.sms.protocol :as sms])
  (:import (java.time Instant)))

(def ^:private alice "+15550000001")
(def ^:private bob   "+15550000002")
(def ^:private our   "+15550000000")

(def ^:private epoch Instant/EPOCH)

;; ---------------------------------------------------------------------------
;; Capability gates
;; ---------------------------------------------------------------------------

(deftest mock-satisfies-both-protocols
  (testing "MockSMS satisfies ISMS and ISMSInbound"
    (let [m (mock/make-mock-sms)]
      (is (satisfies? sms/ISMS m))
      (is (satisfies? sms/ISMSInbound m)))))

;; ---------------------------------------------------------------------------
;; send!
;; ---------------------------------------------------------------------------

(deftest send!-returns-full-message-map
  (testing "send! returns {:ok message-map} with all required fields"
    (let [m (mock/make-mock-sms)
          {msg :ok} (sms/send! m {:from our :to alice :body "hello"})]
      (is (string? (:sid msg)))
      (is (= our (:from msg)))
      (is (= alice (:to msg)))
      (is (= "hello" (:body msg)))
      (is (instance? Instant (:date-sent msg)))
      (is (= :outbound (:direction msg)))
      (is (= :sent (:status msg)))
      (is (s/valid? ::sms/message msg)))))

(deftest send!-with-media-stores-media
  (testing "send! params with :media records media on message-map"
    (let [m (mock/make-mock-sms)
          {msg :ok} (sms/send! m {:from our :to alice :body "pic"
                                  :media [{:url "https://x.com/a.png"
                                           :content-type "image/png"}]})]
      (is (= 1 (count (:media msg))))
      (is (= "https://x.com/a.png" (-> msg :media first :url)))
      (is (s/valid? ::sms/message msg)))))

(deftest send!-without-media-omits-key
  (testing "send! without :media leaves :media absent on the message"
    (let [m (mock/make-mock-sms)
          {msg :ok} (sms/send! m {:from our :to alice :body "plain"})]
      (is (not (contains? msg :media))))))

;; ---------------------------------------------------------------------------
;; query-messages — :since-ts requirement
;; ---------------------------------------------------------------------------

(deftest query-without-since-ts-errors
  (testing "query-messages without :since-ts returns :sms/invalid-filters"
    (let [m (mock/make-mock-sms)
          result (sms/query-messages m {})]
      (is (= :sms/invalid-filters (-> result :error :type)))
      (is (contains? sms/error-types (-> result :error :type))))))

;; ---------------------------------------------------------------------------
;; query-messages — basic filtering
;; ---------------------------------------------------------------------------

(deftest query-filters-by-to-string
  (testing ":to as a single string filters server-side"
    (let [m (mock/make-mock-sms)]
      (sms/send! m {:from our :to alice :body "for alice"})
      (sms/send! m {:from our :to bob   :body "for bob"})
      (let [{msgs :ok} (sms/query-messages m {:since-ts epoch :to alice})]
        (is (= ["for alice"] (mapv :body msgs)))))))

(deftest query-filters-by-to-coll
  (testing ":to as a coll matches any of the listed numbers (OR)"
    (let [m (mock/make-mock-sms)]
      (sms/send! m {:from our :to alice :body "for alice"})
      (sms/send! m {:from our :to bob   :body "for bob"})
      (sms/send! m {:from our :to "+1ZZZ" :body "for zzz"})
      (let [{msgs :ok} (sms/query-messages m {:since-ts epoch :to [alice bob]})]
        (is (= #{"for alice" "for bob"} (set (map :body msgs))))))))

(deftest query-filters-by-from
  (testing ":from filters by sender"
    (let [m (mock/make-mock-sms)]
      (sms/send! m {:from our :to alice :body "from us"})
      (sms/simulate-inbound! m {:from alice :to our :body "from alice"})
      (let [{msgs :ok} (sms/query-messages m {:since-ts epoch :from alice})]
        (is (= ["from alice"] (mapv :body msgs)))))))

(deftest query-filters-by-direction
  (testing ":direction narrows to outbound or inbound"
    (let [m (mock/make-mock-sms)]
      (sms/send! m {:from our :to alice :body "out"})
      (sms/simulate-inbound! m {:from alice :to our :body "in"})
      (let [{outs :ok} (sms/query-messages m {:since-ts epoch :direction :outbound})
            {ins  :ok} (sms/query-messages m {:since-ts epoch :direction :inbound})]
        (is (= ["out"] (mapv :body outs)))
        (is (= ["in"]  (mapv :body ins)))))))

(deftest query-filters-by-status
  (testing ":status as keyword and as coll"
    (let [m (mock/make-mock-sms)]
      (sms/send! m {:from our :to alice :body "out"})
      (sms/simulate-inbound! m {:from alice :to our :body "in"})
      (let [{sents :ok} (sms/query-messages m {:since-ts epoch :status :sent})
            {both  :ok} (sms/query-messages m {:since-ts epoch
                                                :status [:sent :received]})]
        (is (= ["out"] (mapv :body sents)))
        (is (= 2 (count both)))))))

;; ---------------------------------------------------------------------------
;; query-messages — time window semantics
;; ---------------------------------------------------------------------------

(deftest query-since-ts-strict-exclusive
  (testing ":since-ts is strict — messages at exactly that instant are excluded"
    (let [m (mock/make-mock-sms)
          _ (sms/send! m {:from our :to alice :body "first"})
          cursor (Instant/now)
          _ (Thread/sleep 10) ; ensure next is strictly after
          _ (sms/send! m {:from our :to alice :body "second"})
          {msgs :ok} (sms/query-messages m {:since-ts cursor :to alice})]
      (is (= ["second"] (mapv :body msgs))
          "Strict since-ts excludes messages at-or-before the cursor"))))

(deftest query-until-ts-inclusive
  (testing ":until-ts is inclusive — messages at exactly that instant are included"
    (let [m (mock/make-mock-sms)
          past (.minusSeconds (Instant/now) 60)
          _ (sms/simulate-inbound! m {:from alice :to our :body "old"
                                       :date-sent past})
          _ (sms/send! m {:from our :to alice :body "now"})
          {msgs :ok} (sms/query-messages m {:since-ts epoch :until-ts past})]
      (is (= ["old"] (mapv :body msgs))))))

;; ---------------------------------------------------------------------------
;; query-messages — order and limit
;; ---------------------------------------------------------------------------

(deftest query-order-asc-default
  (testing "default :order :asc returns oldest first"
    (let [m (mock/make-mock-sms)]
      (doseq [b ["a" "b" "c"]] (sms/send! m {:from our :to alice :body b}))
      (let [{msgs :ok} (sms/query-messages m {:since-ts epoch :to alice})]
        (is (= ["a" "b" "c"] (mapv :body msgs)))))))

(deftest query-order-desc
  (testing ":order :desc returns newest first"
    (let [m (mock/make-mock-sms)]
      (doseq [b ["a" "b" "c"]]
        (sms/send! m {:from our :to alice :body b})
        (Thread/sleep 1)) ; force monotonically-increasing date-sent
      (let [{msgs :ok} (sms/query-messages m {:since-ts epoch :to alice
                                              :order :desc})]
        (is (= ["c" "b" "a"] (mapv :body msgs)))))))

(deftest query-limit-caps-result
  (testing ":limit caps the result vector"
    (let [m (mock/make-mock-sms)]
      (doseq [b ["a" "b" "c" "d" "e"]] (sms/send! m {:from our :to alice :body b}))
      (let [{msgs :ok} (sms/query-messages m {:since-ts epoch :to alice :limit 2})]
        (is (= 2 (count msgs)))
        (is (= ["a" "b"] (mapv :body msgs)))))))

(deftest query-empty-log
  (testing "query against empty log returns []"
    (let [m (mock/make-mock-sms)
          {msgs :ok} (sms/query-messages m {:since-ts epoch})]
      (is (= [] msgs)))))

;; ---------------------------------------------------------------------------
;; simulate-inbound!
;; ---------------------------------------------------------------------------

(deftest simulate-inbound!-creates-inbound-message
  (testing "simulate-inbound! returns full message-map with :direction :inbound"
    (let [m (mock/make-mock-sms)
          {msg :ok} (sms/simulate-inbound! m {:from alice :to our :body "STOP"})]
      (is (= alice (:from msg)))
      (is (= our (:to msg)))
      (is (= "STOP" (:body msg)))
      (is (= :inbound (:direction msg)))
      (is (= :received (:status msg)))
      (is (s/valid? ::sms/message msg)))))

(deftest simulate-inbound!-respects-explicit-date-sent
  (testing "explicit :date-sent overrides default-now"
    (let [m (mock/make-mock-sms)
          ts (Instant/parse "2026-01-15T12:00:00Z")
          {msg :ok} (sms/simulate-inbound! m {:from alice :to our
                                               :body "back-dated"
                                               :date-sent ts})]
      (is (= ts (:date-sent msg))))))

(deftest simulate-inbound!-and-send!-share-log
  (testing "Outbound and inbound entries appear in one log, distinguishable by :direction"
    (let [m (mock/make-mock-sms)]
      (sms/send! m {:from our :to alice :body "out"})
      (sms/simulate-inbound! m {:from alice :to our :body "in"})
      (let [{msgs :ok} (sms/query-messages m {:since-ts epoch})]
        (is (= 2 (count msgs)))
        (is (= #{:outbound :inbound} (set (map :direction msgs))))))))

;; ---------------------------------------------------------------------------
;; Reset
;; ---------------------------------------------------------------------------

(deftest reset!-clears-log
  (testing "reset! empties the mock log; subsequent reads see [] until new sends"
    (let [m (mock/make-mock-sms)]
      (sms/send! m {:from our :to alice :body "before"})
      (mock/reset! m)
      (let [{msgs :ok} (sms/query-messages m {:since-ts epoch})]
        (is (= [] msgs)))
      (sms/send! m {:from our :to alice :body "after"})
      (let [{msgs :ok} (sms/query-messages m {:since-ts epoch})]
        (is (= ["after"] (mapv :body msgs)))))))

(deftest reset!-is-not-on-protocols
  (testing "reset! must not appear on ISMS or ISMSInbound — mock-only convenience"
    (let [isms-sigs (set (map :name (vals (:sigs sms/ISMS))))
          inbound-sigs (set (map :name (vals (:sigs sms/ISMSInbound))))]
      (is (not (contains? isms-sigs 'reset!)))
      (is (not (contains? inbound-sigs 'reset!))))))
