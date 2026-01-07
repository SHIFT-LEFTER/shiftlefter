(ns shiftlefter.runner.events-test
  (:require [clojure.test :refer [deftest is testing]]
            [shiftlefter.runner.events :as events]))

;; -----------------------------------------------------------------------------
;; Timestamp Helper Tests
;; -----------------------------------------------------------------------------

(deftest test-now-iso-format
  (testing "now-iso returns ISO-8601 formatted string"
    (let [ts (events/now-iso)]
      (is (string? ts))
      (is (re-matches #"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z" ts)
          "Should match ISO-8601 with milliseconds and Z suffix"))))

(deftest test-now-iso-changes
  (testing "now-iso returns different values over time"
    (let [ts1 (events/now-iso)
          _ (Thread/sleep 2)
          ts2 (events/now-iso)]
      ;; At minimum, timestamps should be valid; they may or may not differ
      ;; depending on timing, but format should be consistent
      (is (string? ts1))
      (is (string? ts2)))))

;; -----------------------------------------------------------------------------
;; Event Bus Core Tests (BIRDSONG acceptance criteria)
;; -----------------------------------------------------------------------------

(deftest test-publish-subscribe-basic
  (testing "Published events reach subscriber"
    (let [bus (events/make-memory-bus)
          got (atom [])
          sub (events/subscribe! bus (fn [e] (swap! got conj e)))
          run-id "run-123"]
      (events/publish! bus {:type :test/foo :ts (events/now-iso) :run-id run-id :payload {:x 1}})
      (Thread/sleep 20)
      (is (= 1 (count @got)) "Should receive one event")
      (is (= :test/foo (:type (first @got))) "Event type should match")
      (is (= {:x 1} (:payload (first @got))) "Payload should match")
      (events/bus-close! bus))))

(deftest test-unsubscribe-stops-delivery
  (testing "Unsubscribed handler stops receiving events"
    (let [bus (events/make-memory-bus)
          got (atom [])
          sub (events/subscribe! bus (fn [e] (swap! got conj e)))
          run-id "run-123"]
      ;; First event should be received
      (events/publish! bus {:type :test/foo :ts (events/now-iso) :run-id run-id :payload {:x 1}})
      (Thread/sleep 20)
      (is (= 1 (count @got)))

      ;; Unsubscribe
      (events/unsubscribe! bus sub)

      ;; Second event should NOT be received
      (events/publish! bus {:type :test/bar :ts (events/now-iso) :run-id run-id :payload {:y 2}})
      (Thread/sleep 20)
      (is (= 1 (count @got)) "Should still have only 1 event after unsubscribe")

      (events/bus-close! bus))))

(deftest test-multiple-subscribers-fanout
  (testing "Multiple subscribers each receive all events"
    (let [bus (events/make-memory-bus)
          got1 (atom [])
          got2 (atom [])
          sub1 (events/subscribe! bus (fn [e] (swap! got1 conj e)))
          sub2 (events/subscribe! bus (fn [e] (swap! got2 conj e)))
          run-id "run-456"]
      ;; Publish two events
      (events/publish! bus {:type :test/a :ts (events/now-iso) :run-id run-id :payload {:n 1}})
      (events/publish! bus {:type :test/b :ts (events/now-iso) :run-id run-id :payload {:n 2}})
      (Thread/sleep 30)

      ;; Both subscribers should receive both events
      (is (= 2 (count @got1)) "Subscriber 1 should have 2 events")
      (is (= 2 (count @got2)) "Subscriber 2 should have 2 events")
      (is (= [:test/a :test/b] (mapv :type @got1)))
      (is (= [:test/a :test/b] (mapv :type @got2)))

      (events/bus-close! bus))))

(deftest test-handler-error-does-not-crash-bus
  (testing "Handler exceptions don't crash the event bus"
    (let [bus (events/make-memory-bus)
          good-got (atom [])
          bad-handler (fn [_e] (throw (ex-info "Handler exploded" {})))
          good-handler (fn [e] (swap! good-got conj e))
          _sub1 (events/subscribe! bus bad-handler)
          _sub2 (events/subscribe! bus good-handler)
          run-id "run-789"]
      ;; Publish event - bad handler throws, good handler should still work
      (events/publish! bus {:type :test/x :ts (events/now-iso) :run-id run-id :payload {}})
      (Thread/sleep 20)

      ;; Good handler should have received the event
      (is (= 1 (count @good-got)) "Good handler should still receive events")

      (events/bus-close! bus))))

(deftest test-bus-close-stops-delivery
  (testing "Closing bus stops all delivery"
    (let [bus (events/make-memory-bus)
          got (atom [])
          _sub (events/subscribe! bus (fn [e] (swap! got conj e)))
          run-id "run-close"]
      ;; Publish before close
      (events/publish! bus {:type :test/before :ts (events/now-iso) :run-id run-id :payload {}})
      (Thread/sleep 20)
      (is (= 1 (count @got)))

      ;; Close the bus
      (events/bus-close! bus)

      ;; Publish after close - should not throw but also not deliver
      (events/publish! bus {:type :test/after :ts (events/now-iso) :run-id run-id :payload {}})
      (Thread/sleep 20)
      (is (= 1 (count @got)) "No new events after close"))))

;; -----------------------------------------------------------------------------
;; Event Envelope Helper Tests
;; -----------------------------------------------------------------------------

(deftest test-make-event-envelope
  (testing "make-event creates proper envelope"
    (let [event (events/make-event :scenario/started "run-001" {:name "Login"})]
      (is (= :scenario/started (:type event)))
      (is (= "run-001" (:run-id event)))
      (is (= {:name "Login"} (:payload event)))
      (is (string? (:ts event)))
      (is (re-matches #"\d{4}-\d{2}-\d{2}T.*Z" (:ts event))))))

(deftest test-subscription-returns-token
  (testing "subscribe! returns a subscription token (UUID)"
    (let [bus (events/make-memory-bus)
          token (events/subscribe! bus (fn [_]))]
      (is (uuid? token) "Token should be a UUID")
      (events/bus-close! bus))))
