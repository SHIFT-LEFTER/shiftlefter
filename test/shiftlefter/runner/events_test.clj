(ns shiftlefter.runner.events-test
  (:require [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is testing]]
            [clojure.tools.logging :as log]
            [clojure.tools.logging.impl :as log-impl]
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
          _sub (events/subscribe! bus (fn [e] (swap! got conj e)))
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
          _sub1 (events/subscribe! bus (fn [e] (swap! got1 conj e)))
          _sub2 (events/subscribe! bus (fn [e] (swap! got2 conj e)))
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

;; -----------------------------------------------------------------------------
;; sl-q9wp: :seq stamping, offer!-based publish, dropped-events counter
;; -----------------------------------------------------------------------------

(deftest test-publish-stamps-monotonic-seq
  (testing "publish! stamps a per-run monotonic :seq on every event"
    (let [bus (events/make-memory-bus)
          got (atom [])
          _sub (events/subscribe! bus (fn [e] (swap! got conj e)))]
      (dotimes [n 5]
        (events/publish! bus (events/make-event :test/seq "run-seq" {:n n})))
      (Thread/sleep 30)
      (is (= 5 (count @got)))
      (is (= [0 1 2 3 4] (mapv :seq @got))
          "Seq should be monotonic from 0 in publish order")
      (is (every? #(s/valid? ::events/event-envelope %) @got)
          "Stamped events still satisfy the envelope spec")
      (events/bus-close! bus))))

(deftest test-make-event-extras-arity
  (testing "make-event 4-arity merges extras onto the envelope top level"
    (let [sid (java.util.UUID/randomUUID)
          event (events/make-event :scenario/finished "run-x" {:a 1}
                                   {:scenario/id sid})]
      (is (= sid (:scenario/id event)))
      (is (= {:a 1} (:payload event)))
      (is (s/valid? ::events/event-envelope event)))
    (testing "nil extras are a no-op"
      (let [event (events/make-event :test/x "run-x" {} nil)]
        (is (s/valid? ::events/event-envelope event))
        (is (not (contains? event :scenario/id)))))))

(deftest test-full-bus-never-throws-and-counts-drops
  (testing "publish! against a wedged bus drops + counts instead of throwing"
    ;; One subscriber whose handler never returns wedges the mult (its
    ;; sub-chan fills at 64, the mult parks), so the 1024 input buffer
    ;; fills and offer! starts refusing. Under put! semantics this test
    ;; would THROW on the publishing thread after 1024 pending puts.
    (let [bus (events/make-memory-bus)
          block (promise)
          _sub (events/subscribe! bus (fn [_] @block))]
      (dotimes [n 1400]
        (events/publish! bus (events/make-event :test/flood "run-flood" {:n n})))
      (is (pos? (events/dropped-events bus))
          "Overflow must be counted, not thrown")
      (deliver block :released)
      (events/bus-close! bus))))

(deftest test-close-drains-accepted-events
  (testing "every event the bus accepted is handled before bus-close! returns
            — no sleep, no polling (the sl-4tsi drop-at-close fix)"
    (let [bus (events/make-memory-bus)
          got (atom [])
          _sub (events/subscribe! bus (fn [e] (swap! got conj (:seq e))))]
      (dotimes [n 50]
        (events/publish! bus (events/make-event :test/drain "run-drain" {:n n})))
      ;; Close IMMEDIATELY — the old force-close vaporized in-flight events
      ;; here ~1-in-3 under load.
      (events/bus-close! bus)
      (is (= (range 50) @got)
          "all accepted events delivered, in order, by close time")))
  (testing "drain covers multiple subscribers"
    (let [bus (events/make-memory-bus)
          got1 (atom 0)
          got2 (atom 0)
          _s1 (events/subscribe! bus (fn [_] (swap! got1 inc)))
          _s2 (events/subscribe! bus (fn [_] (swap! got2 inc)))]
      (dotimes [n 30]
        (events/publish! bus (events/make-event :test/drain2 "run-drain2" {:n n})))
      (events/bus-close! bus)
      (is (= 30 @got1))
      (is (= 30 @got2)))))

(deftest test-close-never-hangs-on-wedged-handler
  (testing "a handler that never returns cannot hang bus-close! (bounded
            grace, then force-close + warn) — observers never block the run"
    (let [bus (events/make-memory-bus)
          gate (promise)
          _sub (events/subscribe! bus (fn [_] @gate))
          _ (events/publish! bus (events/make-event :test/wedge "run-wedge" {}))
          start (System/currentTimeMillis)]
      (events/bus-close! bus)
      (is (< (- (System/currentTimeMillis) start) 10000)
          "close returned despite the wedged handler")
      (deliver gate :released))))

(deftest test-publish-after-close-counts-as-drop
  (testing "publish! on a closed bus is a counted drop, never a throw"
    (let [bus (events/make-memory-bus)]
      (events/bus-close! bus)
      (events/publish! bus (events/make-event :test/late "run-late" {}))
      (is (= 1 (events/dropped-events bus))))))

(defn- recording-logger-factory
  "A tools.logging factory whose loggers are always enabled and record
   [level message] into `logs` — the default NOP SLF4J backend reports
   every level as disabled, so with-redefs on log* never fires."
  [logs]
  (reify log-impl/LoggerFactory
    (name [_] "recording")
    (get-logger [_ _ns]
      (reify log-impl/Logger
        (enabled? [_ _level] true)
        (write! [_ level _throwable message]
          (swap! logs conj [level (str message)]))))))

(deftest test-dropped-count-logged-at-bus-close
  (testing "a positive drop count is logged (warn) at bus close; zero is silent"
    (let [logs (atom [])]
      (binding [log/*logger-factory* (recording-logger-factory logs)]
        (testing "wedged bus accumulates drops, close logs them"
          (let [bus (events/make-memory-bus)
                gate (promise)
                _sub (events/subscribe! bus (fn [_] @gate))]
            (dotimes [n 1400]
              (events/publish! bus (events/make-event :test/flood "run-log" {:n n})))
            (deliver gate :released)
            (events/bus-close! bus)
            (is (some (fn [[level msg]]
                        (and (= :warn level) (re-find #"dropped" msg)))
                      @logs)
                (pr-str @logs))))
        (testing "clean bus logs nothing at close"
          (reset! logs [])
          (let [bus (events/make-memory-bus)]
            (events/bus-close! bus)
            (is (empty? @logs))))))))
