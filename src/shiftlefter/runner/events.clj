(ns shiftlefter.runner.events
  "Event bus for ShiftLefter runner — stable event envelope with multi-subscriber support.

   ## Event Envelope (BIRDSONG spec)

   All events conform to this shape:
   ```clojure
   {:type :keyword        ;; e.g. :scenario/started, :step/finished
    :ts \"ISO-8601\"       ;; e.g. \"2026-01-04T12:34:56.789Z\"
    :run-id \"uuid-str\"   ;; identifies the test run
    :payload {...}}       ;; event-specific data
   ```

   ## Usage

   ```clojure
   (let [bus (make-memory-bus)
         sub (subscribe! bus (fn [e] (println (:type e))))]
     (publish! bus {:type :test/foo :ts (now-iso) :run-id \"run-1\" :payload {}})
     (unsubscribe! bus sub)
     (close! bus))
   ```"
  (:require [clojure.core.async :as async :refer [chan mult tap untap close! go-loop <!]])
  (:import [java.time Instant ZoneOffset]
           [java.time.format DateTimeFormatter]))

;; -----------------------------------------------------------------------------
;; Timestamp Helper
;; -----------------------------------------------------------------------------

(def ^:private iso-formatter
  "ISO-8601 formatter with milliseconds and Z suffix."
  (-> (DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
      (.withZone ZoneOffset/UTC)))

(defn now-iso
  "Return current UTC time as ISO-8601 string with milliseconds.
   Example: \"2026-01-04T12:34:56.789Z\""
  []
  (.format iso-formatter (Instant/now)))

;; -----------------------------------------------------------------------------
;; Protocol
;; -----------------------------------------------------------------------------

(defprotocol IEventBus
  "Protocol for event bus implementations."
  (publish! [this event]
    "Publish an event to all subscribers. Non-blocking, best-effort delivery.")
  (subscribe! [this handler]
    "Subscribe a handler fn to receive events. Returns subscription token for unsubscribe.")
  (unsubscribe! [this token]
    "Remove a subscription by its token.")
  (bus-close! [this]
    "Close the event bus, stopping all delivery."))

;; -----------------------------------------------------------------------------
;; MemoryEventBus Implementation
;; -----------------------------------------------------------------------------

(defrecord MemoryEventBus [input-chan mult-ref subscriptions-atom]
  IEventBus
  (publish! [_this event]
    ;; Non-blocking put. If buffer full, event is dropped (best-effort).
    (async/put! input-chan event)
    nil)

  (subscribe! [_this handler]
    (let [sub-id (java.util.UUID/randomUUID)
          sub-chan (chan 64)]
      ;; Tap the mult to receive copies of all events
      (tap @mult-ref sub-chan)
      ;; Start go-loop to dispatch events to handler
      (go-loop []
        (when-let [event (<! sub-chan)]
          (try
            (handler event)
            (catch Exception e
              ;; Log but don't crash the loop
              (binding [*out* *err*]
                (println "Event handler error:" (ex-message e)))))
          (recur)))
      ;; Track subscription for cleanup
      (swap! subscriptions-atom assoc sub-id sub-chan)
      sub-id))

  (unsubscribe! [_this token]
    (when-let [sub-chan (get @subscriptions-atom token)]
      (untap @mult-ref sub-chan)
      (close! sub-chan)
      (swap! subscriptions-atom dissoc token))
    nil)

  (bus-close! [_this]
    ;; Close input channel, which will close the mult
    (close! input-chan)
    ;; Close all subscription channels
    (doseq [[_id sub-chan] @subscriptions-atom]
      (close! sub-chan))
    (reset! subscriptions-atom {})
    nil))

(defn make-memory-bus
  "Create a new in-memory event bus with fan-out delivery.

   All published events are delivered to all subscribers.
   Uses core.async mult/tap for efficient fan-out."
  []
  (let [input-chan (chan 256)
        mult-ref (atom (mult input-chan))]
    (->MemoryEventBus input-chan mult-ref (atom {}))))

;; -----------------------------------------------------------------------------
;; Event Envelope Helpers
;; -----------------------------------------------------------------------------

(defn make-event
  "Convenience constructor for event envelopes.

   (make-event :scenario/started \"run-123\" {:name \"Login test\"})
   => {:type :scenario/started
       :ts \"2026-01-04T...\"
       :run-id \"run-123\"
       :payload {:name \"Login test\"}}"
  [type run-id payload]
  {:type type
   :ts (now-iso)
   :run-id run-id
   :payload payload})
