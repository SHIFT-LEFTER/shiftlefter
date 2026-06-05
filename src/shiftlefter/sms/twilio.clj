(ns shiftlefter.sms.twilio
  "Twilio-backed implementation of ISMS.

   Wraps the Twilio REST API (https://api.twilio.com/2010-04-01/) via
   clj-http. Stateless record — credentials live on the record itself
   and there's nothing to close.

   ## Endpoints

   All operations hit `/Accounts/{sid}/Messages.json` with HTTP Basic
   auth `{AccountSid}:{AuthToken}`.

   - `send!`           POSTs form params (`From`, `To`, `Body`, `MediaUrl`)
   - `query-messages`  GETs with query params

   ## Server-side vs client-side filtering

   Twilio supports query-param filtering on `From`, `To`, `DateSent>=`,
   and `DateSent<=` — single-valued. We use those server-side when the
   filter is a single string and within Twilio's grammar.

   For coll-valued `:from` / `:to`, and for `:direction` / `:status` /
   `:limit` / `:order`, we filter client-side after fetching the
   server-side-narrowed window.

   ## Normalization

   - Twilio dates are RFC 2822 (e.g., `Thu, 30 Jul 2015 20:12:31 +0000`).
     Normalized to `java.time.Instant`.
   - Twilio status enum (`accepted|queued|sent|delivered|...`) normalized
     to `shiftlefter.sms.protocol/status-set`.
   - Twilio direction enum (`outbound-api|outbound-call|outbound-reply|inbound`)
     normalized to `#{:outbound :inbound}`.

   This adapter does NOT satisfy ISMSInbound. Use `:sms-mock` for
   scenarios that need inbound simulation.

   ## Pagination

   `query-messages` fetches a single Twilio page (max `PageSize` 1000,
   capped by the protocol `:limit`). When the Twilio response carries
   a non-null `next_page_uri`, additional messages exist beyond the
   returned set — the adapter surfaces this as `:at-cap true` on the
   success result and emits a `clojure.tools.logging/warn`. Test
   authors should narrow `:since-ts` (or pass a smaller `:limit`)
   rather than expect transparent pagination. Hard contract: at most
   `min(:limit, 1000)` messages per call. See
   `_docs/canon/core/decisions.md` § Twilio query-messages page cap."
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.tools.logging :as log]
            [shiftlefter.sms.protocol :as sms])
  (:import (java.time Instant ZonedDateTime)
           (java.time.format DateTimeFormatter)))

;; ---------------------------------------------------------------------------
;; HTTP helpers
;; ---------------------------------------------------------------------------

(defn- messages-url [account-sid]
  (str "https://api.twilio.com/2010-04-01/Accounts/" account-sid "/Messages.json"))

(defn- parse-twilio-date
  "Parse a Twilio RFC 2822 date string to an Instant.
   Example input: \"Thu, 30 Jul 2015 20:12:31 +0000\"."
  [s]
  (-> (.parse DateTimeFormatter/RFC_1123_DATE_TIME s)
      (ZonedDateTime/from)
      (.toInstant)))

(defn- iso-instant [^Instant ts]
  (.format DateTimeFormatter/ISO_INSTANT ts))

;; ---------------------------------------------------------------------------
;; Status / direction normalization
;; ---------------------------------------------------------------------------

(def ^:private twilio->status
  {"accepted"           :queued
   "scheduled"          :queued
   "queued"             :queued
   "sending"            :sent
   "sent"               :sent
   "delivered"          :delivered
   "read"               :delivered
   "undelivered"        :failed
   "failed"             :failed
   "canceled"           :failed
   "partially_delivered" :failed
   "receiving"          :queued
   "received"           :received})

(defn- normalize-status [s]
  (or (get twilio->status s) :unknown))

(defn- normalize-direction [d]
  (if (= d "inbound") :inbound :outbound))

;; ---------------------------------------------------------------------------
;; Error mapping
;; ---------------------------------------------------------------------------

(defn- error-type-for-status
  "Map an HTTP status code to a normalized :sms/<...> error type."
  [status]
  (cond
    (= 401 status) :sms/auth-failed
    (= 403 status) :sms/auth-failed
    (= 408 status) :sms/timeout
    (= 429 status) :sms/rate-limited
    (<= 400 status 499) :sms/recipient-invalid
    (<= 500 status 599) :sms/network
    :else                     :sms/unknown))

(defn- error-from-response [resp context]
  {:error {:type    (error-type-for-status (:status resp))
           :message (str "Twilio HTTP " (:status resp))
           :data    (merge {:status (:status resp)
                            :body   (:body resp)}
                           context)}})

(defn- error-from-exception [^Exception e context]
  {:error {:type    :sms/network
           :message (or (.getMessage e) (str e))
           :data    (merge {:exception (.getName (class e))} context)}})

(defn- ok-status? [status]
  (and (integer? status) (<= 200 status 299)))

;; ---------------------------------------------------------------------------
;; Message projection
;; ---------------------------------------------------------------------------

(defn- json-message->message-map
  "Project a Twilio JSON message object onto the protocol message-map.

   Twilio's `messages` array entries have at least:
     sid, from, to, body, date_sent, status, direction,
     num_segments (string), price, error_code, account_sid, etc.

   We surface :provider-data carrying the raw object for non-portable
   tests that need it."
  [m]
  (let [date-sent-str (get m "date_sent")
        date-sent     (when date-sent-str (parse-twilio-date date-sent-str))
        segs-str      (get m "num_segments")
        segs          (when segs-str
                        (try (Long/parseLong segs-str) (catch Exception _ nil)))
        error-code    (get m "error_code")]
    (cond-> {:sid       (get m "sid")
             :from      (get m "from")
             :to        (get m "to")
             :body      (or (get m "body") "")
             :date-sent date-sent
             :direction (normalize-direction (get m "direction"))
             :status    (normalize-status (get m "status"))
             :provider-data m}
      segs       (assoc :segments segs)
      error-code (assoc :error-code (str error-code)))))

(defn- send-response->message-map
  "Twilio's POST response is a single message object — same projection."
  [m]
  (json-message->message-map m))

;; ---------------------------------------------------------------------------
;; HTTP requests
;; ---------------------------------------------------------------------------

(defn- post-message
  "POST to /Messages.json with form-params. Returns ISMS send-result."
  [account-sid auth-token form-params context]
  (try
    (let [resp (http/post (messages-url account-sid)
                          {:basic-auth       [account-sid auth-token]
                           :form-params      form-params
                           :throw-exceptions false})]
      (if (ok-status? (:status resp))
        (let [body (json/parse-string (:body resp))]
          {:ok (send-response->message-map body)})
        (error-from-response resp context)))
    (catch Exception e
      (error-from-exception e context))))

(defn- get-messages
  "GET /Messages.json with provided query-params. Returns parsed messages
   (already projected to the protocol shape) on success, or the error map.

   On success the result also carries `:at-cap` — true when Twilio's
   response had a non-null `next_page_uri` (more messages exist beyond
   the returned page). Caller is expected to surface this to its own
   result; see `query-messages`."
  [account-sid auth-token query-params context]
  (try
    (let [resp (http/get (messages-url account-sid)
                         {:basic-auth       [account-sid auth-token]
                          :query-params     query-params
                          :throw-exceptions false})]
      (if (ok-status? (:status resp))
        (let [body      (json/parse-string (:body resp))
              raw       (get body "messages" [])
              next-page (get body "next_page_uri")
              at-cap?   (some? next-page)
              msgs      (mapv json-message->message-map raw)]
          (when at-cap?
            (log/warnf
             (str "Twilio query-messages at PageSize cap; results "
                  "truncated. Returned %d messages; more exist "
                  "(next_page_uri=%s). Narrow :since-ts or pass a "
                  "smaller :limit. Filters: %s")
             (count raw) next-page (pr-str (:filters context))))
          {:ok msgs :at-cap at-cap?})
        (error-from-response resp context)))
    (catch Exception e
      (error-from-exception e context))))

;; ---------------------------------------------------------------------------
;; Filter construction & client-side filtering
;; ---------------------------------------------------------------------------

(defn- single-value
  "If filter-val is a string, return it; if a 1-element coll, return its
   sole element; otherwise nil — caller falls back to client-side filtering."
  [filter-val]
  (cond
    (nil? filter-val)                              nil
    (string? filter-val)                           filter-val
    (and (sequential? filter-val)
         (= 1 (count filter-val))
         (string? (first filter-val)))             (first filter-val)
    :else                                          nil))

(defn- build-query-params
  "Construct the Twilio query-params map from filters, using server-side
   support where possible. Returns a map suitable for clj-http :query-params."
  [{:keys [since-ts until-ts to from limit]}]
  (cond-> {"DateSent>" (iso-instant since-ts)}
    until-ts          (assoc "DateSent<" (iso-instant until-ts))
    (single-value to) (assoc "To"   (single-value to))
    (single-value from) (assoc "From" (single-value from))
    ;; PageSize: Twilio caps at 1000; protocol limit caps at 1000 too.
    limit             (assoc "PageSize" (str (min 1000 limit)))))

(defn- match-string-or-coll [filter-val v]
  (cond
    (nil? filter-val)    true
    (string? filter-val) (= filter-val v)
    (coll? filter-val)   (boolean (some #(= % v) filter-val))
    :else                false))

(defn- match-keyword-or-coll [filter-val v]
  (cond
    (nil? filter-val)     true
    (keyword? filter-val) (= filter-val v)
    (coll? filter-val)    (boolean (some #(= % v) filter-val))
    :else                 false))

(defn- in-window?
  "Strict-exclusive since-ts; inclusive until-ts."
  [{:keys [since-ts until-ts]} {:keys [date-sent]}]
  (and (or (nil? date-sent) (pos? (compare date-sent since-ts)))
       (or (nil? until-ts)
           (nil? date-sent)
           (not (pos? (compare date-sent until-ts))))))

(defn- client-side-filter
  "Apply every filter client-side. The server may have already narrowed
   the set; this is the authoritative pass — adapters MUST honor every
   filter regardless of which side does the work."
  [filters msgs]
  (filter (fn [m]
            (and (in-window? filters m)
                 (match-string-or-coll  (:to filters)        (:to m))
                 (match-string-or-coll  (:from filters)      (:from m))
                 (match-keyword-or-coll (:direction filters) (:direction m))
                 (match-keyword-or-coll (:status filters)    (:status m))))
          msgs))

(defn- order-by-date [order msgs]
  (if (= :desc order)
    (sort-by :date-sent #(compare %2 %1) msgs)
    (sort-by :date-sent msgs)))

;; ---------------------------------------------------------------------------
;; Record
;; ---------------------------------------------------------------------------

(defrecord TwilioSMS [account-sid auth-token]
  sms/ISMS

  (send! [_this {:keys [from to body media]}]
    (let [form (cond-> {"To" to "Body" (or body "")}
                 from        (assoc "From" from)
                 (seq media) (assoc "MediaUrl" (vec (map :url media))))]
      (post-message account-sid auth-token form
                    {:from from :to to})))

  (query-messages [_this filters]
    (if-not (:since-ts filters)
      {:error {:type    :sms/invalid-filters
               :message "query-messages requires :since-ts"
               :data    {:filters filters}}}
      (let [order   (or (:order filters) :asc)
            limit   (or (:limit filters) 100)
            qp      (build-query-params filters)
            result  (get-messages account-sid auth-token qp
                                  {:filters filters})]
        (if (:error result)
          result
          (let [filtered (client-side-filter filters (:ok result))
                ordered  (order-by-date order filtered)
                taken    (vec (take limit ordered))]
            (cond-> {:ok taken}
              (:at-cap result) (assoc :at-cap true))))))))

(defn make-twilio-sms
  "Construct a TwilioSMS record. Both credentials are required strings."
  [{:keys [account-sid auth-token]}]
  (->TwilioSMS account-sid auth-token))
