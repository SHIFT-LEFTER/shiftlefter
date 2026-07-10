(ns shiftlefter.runner.events
  "Event bus for ShiftLefter runner — stable event envelope with multi-subscriber support.

   ## Event Envelope (BIRDSONG spec)

   All events conform to this shape:
   ```clojure
   {:type :keyword        ;; e.g. :scenario/started, :step/finished
    :ts \"ISO-8601\"       ;; e.g. \"2026-01-04T12:34:56.789Z\"
    :run-id \"uuid-str\"   ;; identifies the test run
    :seq 42               ;; per-run monotonic, stamped at publish! (sl-q9wp)
    :payload {...}}       ;; event-specific data
   ```

   Scenario-scoped events additionally carry `:scenario/id` (the pickle
   UUID), so a subscriber can filter per scenario and totally order what it
   received by `:seq`. Ordinal-time stance (sl-q9wp): total order is
   guaranteed WITHIN a scenario; cross-scenario order is explicitly
   unspecified — the bus carries ACTUAL completion order, which under
   :max-parallel > 1 is nondeterministic.

   ## Delivery contract (sl-q9wp, R5 + fit-pass)

   `publish!` is `offer!`-based: a full bus can never throw into or block
   execution. Results are DEFINITELY intact — the report plane never
   touches the bus; observations are possibly gapped, and the dropped-events
   counter (logged at bus close) says so.

   The gap is at ADMISSION only: once the bus accepts an event, `bus-close!`
   drains it to every subscriber before returning (input-channel-only close;
   the mult and subscriber loops finish naturally). The sole exception is a
   wedged handler — after a bounded grace its tap is force-closed so an
   observer can never hang the run.

   ## Usage

   ```clojure
   (let [bus (make-memory-bus)
         sub (subscribe! bus (fn [e] (println (:type e))))]
     (publish! bus {:type :test/foo :ts (now-iso) :run-id \"run-1\" :payload {}})
     (unsubscribe! bus sub)
     (close! bus))
   ```"
  (:require [clojure.core.async :as async :refer [chan mult tap untap close! go-loop <!]]
            [clojure.spec.alpha :as s]
            [clojure.tools.logging :as log])
  (:import [java.time Instant ZoneOffset]
           [java.time.format DateTimeFormatter]
           [java.util.concurrent.atomic AtomicLong]))

;; -----------------------------------------------------------------------------
;; Specs — Event Envelope
;; -----------------------------------------------------------------------------

(s/def ::type keyword?)
(s/def ::ts string?)
(s/def ::run-id string?)
(s/def ::payload map?)
;; Extend-never-break keys (sl-q9wp): :seq is stamped by publish!; scenario-
;; scoped events carry :scenario/id (the pickle UUID, EDN-native).
(s/def ::seq nat-int?)
(s/def :scenario/id uuid?)

(s/def ::event-envelope
  (s/keys :req-un [::type ::ts ::run-id ::payload]
          :opt-un [::seq]
          :opt [:scenario/id]))

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
;; Close semantics
;; -----------------------------------------------------------------------------

(def ^:private drain-grace-ms
  "How long bus-close! waits for subscriber loops to drain accepted events
   before force-closing them. In the normal case draining is near-instant —
   this bound exists only so a wedged handler (one that never returns)
   cannot hang the run (two-plane doctrine: observers never block
   execution)."
  1000)

;; -----------------------------------------------------------------------------
;; Protocol
;; -----------------------------------------------------------------------------

(defprotocol IEventBus
  "Protocol for event bus implementations."
  (publish! [this event]
    "Publish an event to all subscribers. Non-blocking, best-effort delivery:
     stamps a per-run monotonic :seq, then offer!s — a full (or closed) bus
     drops the event and increments the dropped-events counter instead of
     ever throwing into or blocking the publishing thread (sl-q9wp R5).")
  (subscribe! [this handler]
    "Subscribe a handler fn to receive events. Returns subscription token for unsubscribe.")
  (unsubscribe! [this token]
    "Remove a subscription by its token.")
  (bus-close! [this]
    "Close the event bus: stop accepting events, then DRAIN — every event
     the bus already accepted is handed to every subscriber's handler before
     this returns (bounded by a grace period so a wedged handler cannot hang
     the run). Publishes after close are counted drops."))

;; -----------------------------------------------------------------------------
;; MemoryEventBus Implementation
;; -----------------------------------------------------------------------------

(defrecord MemoryEventBus [input-chan mult-ref subscriptions-atom
                           ^AtomicLong seq-counter ^AtomicLong dropped-counter]
  IEventBus
  (publish! [_this event]
    ;; offer! (sl-q9wp R5), NOT put!: put! queues to 1024 pending puts and
    ;; then THROWS on the publishing (execution) thread — a slow subscriber
    ;; crashing the run would invert the two-plane doctrine. offer! never
    ;; blocks and never throws; on refusal (full buffer, or closed channel)
    ;; the event is dropped and counted. :seq is stamped on every publish
    ;; attempt — including drops — so a subscriber can detect gaps.
    (let [stamped (assoc event :seq (.getAndIncrement seq-counter))]
      (when-not (async/offer! input-chan stamped)
        (.incrementAndGet dropped-counter)))
    nil)

  (subscribe! [_this handler]
    (let [sub-id (java.util.UUID/randomUUID)
          sub-chan (chan 64)
          drained (promise)]
      ;; Tap the mult to receive copies of all events
      (tap @mult-ref sub-chan)
      ;; Dispatch loop: runs until sub-chan closes AND its buffer is empty,
      ;; then reports itself drained — bus-close! awaits this, so events the
      ;; bus accepted are handled before close returns (sl-q9wp fit-pass;
      ;; the sl-4tsi drop-at-close fix).
      (go-loop []
        (if-let [event (<! sub-chan)]
          (do
            (try
              (handler event)
              (catch Exception e
                ;; Log but don't crash the loop. log/error keeps the
                ;; throwable so backends can render the stack trace.
                (log/error e "Event handler error")))
            (recur))
          (deliver drained true)))
      ;; Track subscription for cleanup
      (swap! subscriptions-atom assoc sub-id {:chan sub-chan :drained drained})
      sub-id))

  (unsubscribe! [_this token]
    (when-let [{:keys [chan]} (get @subscriptions-atom token)]
      (untap @mult-ref chan)
      ;; Deliberate force-close: the caller asked to stop receiving, so this
      ;; subscriber's pending events are intentionally abandoned.
      (close! chan)
      (swap! subscriptions-atom dissoc token))
    nil)

  (bus-close! [_this]
    ;; The drops contract (sl-q9wp R5): results are definitely intact (the
    ;; report plane never touches the bus); observations are possibly gapped
    ;; and this counter says so.
    (let [dropped (.get dropped-counter)]
      (when (pos? dropped)
        (log/warn (str dropped " observe-plane event(s) dropped (bus full);"
                       " results unaffected"))))
    ;; DRAIN, don't destroy (sl-q9wp fit-pass; kills the sl-4tsi close race):
    ;; close ONLY the input channel and let core.async finish the job — the
    ;; mult distributes its remaining buffer to the taps and then closes
    ;; them, and each subscriber loop drains its queue before reporting
    ;; itself done. Force-closing sub-chans here (the old behavior) made the
    ;; mult's remaining distribution race a closed door: events the bus had
    ;; ACCEPTED vaporized ~1-in-3 under load.
    (close! input-chan)
    (let [subs @subscriptions-atom
          deadline (+ (System/currentTimeMillis) drain-grace-ms)
          await-all (fn []
                      (reduce-kv
                       (fn [pending sub-id {:keys [drained] :as entry}]
                         (let [remaining (- deadline (System/currentTimeMillis))]
                           (if (deref drained (max 0 remaining) false)
                             pending
                             (assoc pending sub-id entry))))
                       {} subs))
          pending (await-all)]
      ;; A handler that never returns must not hang the run (two-plane
      ;; doctrine: observers cannot block execution). After the grace
      ;; period, force-close the wedged taps — this may abandon THEIR
      ;; pending events (logged), and unparks the mult if it was mid-put.
      (when (seq pending)
        (log/warn (str (count pending) " bus subscriber(s) did not drain within "
                       drain-grace-ms "ms at close; force-closing"
                       " (their pending observations may be lost)"))
        (doseq [[_id {:keys [chan]}] pending]
          (close! chan))))
    (reset! subscriptions-atom {})
    nil))

(defn dropped-events
  "How many events this bus has refused (full or closed input buffer)."
  [bus]
  (.get ^AtomicLong (:dropped-counter bus)))

(defn make-memory-bus
  "Create a new in-memory event bus with fan-out delivery.

   All published events are delivered to all subscribers.
   Uses core.async mult/tap for efficient fan-out.

   Input buffer is 1024 (sl-q9wp: 256 -> 1024, absorbs parallel-emit
   bursts); when even that is full, publish! drops and counts (R5)."
  []
  (let [input-chan (chan 1024)
        mult-ref (atom (mult input-chan))]
    (->MemoryEventBus input-chan mult-ref (atom {})
                      (AtomicLong. 0) (AtomicLong. 0))))

;; -----------------------------------------------------------------------------
;; Event Envelope Helpers
;; -----------------------------------------------------------------------------

(defn make-event
  "Convenience constructor for event envelopes.

   (make-event :scenario/started \"run-123\" {:name \"Login test\"})
   => {:type :scenario/started
       :ts \"2026-01-04T...\"
       :run-id \"run-123\"
       :payload {:name \"Login test\"}}

   The 4-arity merges `extras` onto the envelope top level — today's only
   producer passes `{:scenario/id <pickle-uuid>}` so scenario-scoped events
   are filterable per scenario (sl-q9wp R4). Extras must stay within the
   ::event-envelope open-map contract (EDN-native values only)."
  ([type run-id payload]
   {:type type
    :ts (now-iso)
    :run-id run-id
    :payload payload})
  ([type run-id payload extras]
   (merge (make-event type run-id payload) extras)))
