(ns shiftlefter.sms.mock
  "In-memory implementation of ISMS + ISMSInbound for tests and CI.

   Atom-backed flat log. Each entry is a complete message-map carrying
   `:direction` (`:outbound` for sends, `:inbound` for simulated incoming),
   `:status` (`:sent` for outbound, `:received` for inbound), and provider-
   stamped `:date-sent` (Instant/now at call time, or caller-supplied for
   `simulate-inbound!`).

   No network, no credentials. The mock satisfies both ISMS (production
   surface) and ISMSInbound (test seam capability).

   ## Reset

   `(reset! mock)` clears the log. NOT part of either protocol — production
   adapters scope reads via `:since-ts`, so a cross-cutting reset is a
   mock-only convenience. Test fixtures that want a clean slate between
   scenarios call `reset!` directly on the concrete record."
  (:refer-clojure :exclude [reset!])
  (:require [shiftlefter.sms.protocol :as sms])
  (:import (java.time Instant)
           (java.util UUID)))

;; ---------------------------------------------------------------------------
;; Message construction
;; ---------------------------------------------------------------------------

(defn- mock-sid []
  (str "mock-" (UUID/randomUUID)))

(defn- ->outbound [{:keys [from to body media]}]
  (cond-> {:sid       (mock-sid)
           :from      from
           :to        to
           :body      body
           :date-sent (Instant/now)
           :direction :outbound
           :status    :sent}
    (seq media) (assoc :media (vec media))))

(defn- ->inbound [{:keys [from to body media date-sent]}]
  (cond-> {:sid       (mock-sid)
           :from      from
           :to        to
           :body      body
           :date-sent (or date-sent (Instant/now))
           :direction :inbound
           :status    :received}
    (seq media) (assoc :media (vec media))))

;; ---------------------------------------------------------------------------
;; Filter helpers — coerce string-or-coll, match status singleton-or-coll, etc.
;; ---------------------------------------------------------------------------

(defn- match-string-or-coll
  "True iff filter-val (nil | string | coll) admits msg-val."
  [filter-val msg-val]
  (cond
    (nil? filter-val)    true
    (string? filter-val) (= filter-val msg-val)
    (coll? filter-val)   (boolean (some #(= % msg-val) filter-val))
    :else                false))

(defn- match-keyword-or-coll
  [filter-val msg-val]
  (cond
    (nil? filter-val)     true
    (keyword? filter-val) (= filter-val msg-val)
    (coll? filter-val)    (boolean (some #(= % msg-val) filter-val))
    :else                 false))

(defn- in-window?
  "Strict-exclusive since-ts; inclusive until-ts."
  [{:keys [since-ts until-ts]} {:keys [date-sent]}]
  (and (pos? (compare date-sent since-ts))
       (or (nil? until-ts)
           (not (pos? (compare date-sent until-ts))))))

(defn- match-filters? [filters msg]
  (and (in-window? filters msg)
       (match-string-or-coll  (:to filters)        (:to msg))
       (match-string-or-coll  (:from filters)      (:from msg))
       (match-keyword-or-coll (:direction filters) (:direction msg))
       (match-keyword-or-coll (:status filters)    (:status msg))))

(defn- order-by-date [order msgs]
  (if (= :desc order)
    (sort-by :date-sent #(compare %2 %1) msgs)
    (sort-by :date-sent msgs)))

;; ---------------------------------------------------------------------------
;; Record
;; ---------------------------------------------------------------------------

(defrecord MockSMS [log]
  sms/ISMS

  (send! [_this params]
    (let [msg (->outbound params)]
      (swap! log conj msg)
      {:ok msg}))

  (query-messages [_this filters]
    (if-not (:since-ts filters)
      {:error {:type    :sms/invalid-filters
               :message "query-messages requires :since-ts"
               :data    {:filters filters}}}
      (let [order   (or (:order filters) :asc)
            limit   (or (:limit filters) 100)
            matched (filter #(match-filters? filters %) @log)
            ordered (order-by-date order matched)]
        {:ok (vec (take limit ordered))})))

  sms/ISMSInbound

  (simulate-inbound! [_this params]
    (let [msg (->inbound params)]
      (swap! log conj msg)
      {:ok msg})))

(defn make-mock-sms
  "Construct a MockSMS with an empty log."
  []
  (->MockSMS (atom [])))

(defn reset!
  "Clear the mock log. NOT part of ISMS / ISMSInbound — direct mock access only."
  [mock-sms]
  (clojure.core/reset! (:log mock-sms) []))
