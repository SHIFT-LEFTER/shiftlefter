(ns shiftlefter.sms.protocol
  "Internal SMS protocol — the cross-adapter contract.

   ShiftLefter's SMS interface is split into two protocols:

   - `ISMS` — production-capable operations every adapter implements:
     `send!` and `query-messages`. These map to real provider APIs
     (Twilio, Vonage, MessageBird, AWS End User Messaging, etc.) and
     to the in-memory mock.

   - `ISMSInbound` — capability protocol for adapters that can locally
     originate inbound messages (i.e., simulate a phone texting our
     provider number). Mocks satisfy it; production adapters that
     lack a real test-inbound API do not.

   Test-seam-only step definitions declare `:requires-protocols
   [:shiftlefter.sms.protocol/ISMSInbound]` so the framework gates them
   at suite-load time (sl-unz).

   ## Send

   ```clojure
   (sms/send! impl {:from \"+15550000000\"        ; optional, adapter default
                    :to   \"+15551234567\"        ; required, E.164
                    :body \"Your code is 123456\" ; required, may be \"\"
                    :media [{:url \"...\"
                             :content-type \"image/jpeg\"}]})  ; optional, MMS
   ```

   Returns `{:ok message-map} | {:error error-map}`. The returned
   message-map carries the provider's `:sid`, `:date-sent`, `:status`,
   etc. — single source of truth for what the provider thinks just
   happened. Tests can use the returned `:date-sent` as a tight
   `:since-ts` baseline for subsequent `query-messages` calls.

   ## Query

   ```clojure
   (sms/query-messages impl {:since-ts  some-instant   ; REQUIRED
                              :until-ts  another-instant
                              :to        \"+15551234567\"
                              :from      [\"+1...\" \"+1...\"]
                              :direction :outbound
                              :status    :delivered
                              :limit     100
                              :order     :asc})
   ```

   `:since-ts` is mandatory — no whole-account scans. All other filters
   are optional. `:to` and `:from` accept a string or a coll of strings
   (OR semantics). Adapters do server-side filtering where the provider
   supports it and fall back to client-side for the rest. They MUST
   honor every filter regardless of which side does the work.

   `:since-ts` is a strict-exclusive lower bound (matches > since).
   `:until-ts` is an inclusive upper bound (matches <= until).

   Returns `{:ok [message-map ...]}` ordered per `:order` (default
   `:asc`), or `{:error error-map}`.

   ## Simulate inbound (ISMSInbound)

   ```clojure
   (sms/simulate-inbound! impl {:from \"+15550001111\"
                                 :to   \"+1OurProviderNumber\"
                                 :body \"STOP\"
                                 :media nil
                                 :date-sent (Instant/now)})
   ```

   Mock implements; production adapters do NOT satisfy this protocol.
   Step definitions that need it gate via `:requires-protocols`.

   ## Message-map shape (`:shiftlefter.sms.protocol/message`)

   Required:
   ```clojure
   {:sid       string                   ; provider message ID, opaque
    :from      string                   ; E.164 / short code / alpha
    :to        string                   ; E.164
    :body      string                   ; may be \"\"
    :date-sent java.time.Instant        ; provider-stamped
    :direction #{:outbound :inbound}
    :status    #{:queued :sent :delivered :failed :received :unknown}}
   ```

   Optional (surfaced when adapter has the data):
   ```clojure
   {:segments      pos-int              ; segment count post-encoding
    :encoding      #{:gsm-7 :ucs-2}
    :subject       string               ; MMS only
    :media         [{:url string :content-type string :size int?}]
    :error-code    string               ; provider-specific, when status=:failed
    :provider-data map}                 ; raw adapter blob — non-portable escape hatch
   ```

   ## Error envelope

   ```clojure
   {:error {:type    :sms/<closed-keyword>
            :message string
            :data    map}}
   ```

   Closed `:type` set: `:sms/auth-failed`, `:sms/rate-limited`,
   `:sms/recipient-invalid`, `:sms/recipient-blocked`,
   `:sms/sender-not-registered`, `:sms/network`, `:sms/parse-failed`,
   `:sms/simulate-not-supported`, `:sms/timeout`, `:sms/invalid-filters`,
   `:sms/unknown`. Adapter-specific provider error codes go in `:data`."
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen])
  (:import (java.time Instant)))

(def ^:private instant-gen
  (gen/fmap #(Instant/ofEpochMilli %)
            (gen/large-integer* {:min 0 :max 4102444800000})))

;; ---------------------------------------------------------------------------
;; Cross-adapter message-map contract
;; ---------------------------------------------------------------------------

(s/def ::sid       string?)
(s/def ::from      string?)
(s/def ::to        string?)
(s/def ::body      string?)
(s/def ::date-sent (s/with-gen #(instance? Instant %) (fn [] instant-gen)))
(s/def ::direction #{:outbound :inbound})

(def status-set
  "Closed set of normalized message statuses across adapters.

   :queued      — accepted by provider, not yet sent
   :sent        — handed to the carrier (in-flight or delivered-but-no-DLR)
   :delivered   — confirmed delivered (when DLR returns; also covers :read)
   :failed      — provider/carrier rejected, undelivered, canceled, or partial
   :received    — for inbound: provider received from a phone
   :unknown     — adapter cannot determine"
  #{:queued :sent :delivered :failed :received :unknown})

(s/def ::status status-set)

(s/def ::segments pos-int?)
(s/def ::encoding #{:gsm-7 :ucs-2})
(s/def ::subject  string?)

(s/def :shiftlefter.sms.protocol.media/url          string?)
(s/def :shiftlefter.sms.protocol.media/content-type string?)
(s/def :shiftlefter.sms.protocol.media/size         (s/and int? (complement neg?)))

(s/def ::media-entry
  (s/keys :req-un [:shiftlefter.sms.protocol.media/url
                   :shiftlefter.sms.protocol.media/content-type]
          :opt-un [:shiftlefter.sms.protocol.media/size]))

(s/def ::media (s/coll-of ::media-entry :kind sequential?))

(s/def ::error-code    string?)
(s/def ::provider-data map?)

(s/def ::message
  (s/keys :req-un [::sid ::from ::to ::body ::date-sent ::direction ::status]
          :opt-un [::segments ::encoding ::subject ::media
                   ::error-code ::provider-data]))

(s/def ::messages (s/coll-of ::message :kind sequential?))

;; ---------------------------------------------------------------------------
;; Send params spec
;; ---------------------------------------------------------------------------

(s/def ::send-media
  (s/coll-of (s/keys :req-un [:shiftlefter.sms.protocol.media/url
                              :shiftlefter.sms.protocol.media/content-type])
             :kind sequential?
             :min-count 1))

(s/def ::send-params
  (s/keys :req-un [::to ::body]
          :opt-un [::from ::send-media]))

;; ---------------------------------------------------------------------------
;; Query filters spec
;; ---------------------------------------------------------------------------

(s/def ::since-ts (s/with-gen #(instance? Instant %) (fn [] instant-gen)))
(s/def ::until-ts (s/with-gen #(instance? Instant %) (fn [] instant-gen)))

(s/def ::phone-filter
  (s/or :one  string?
        :many (s/coll-of string? :kind sequential? :min-count 1)))

(s/def ::query-to   ::phone-filter)
(s/def ::query-from ::phone-filter)

(s/def ::query-direction ::direction)

(s/def ::status-filter
  (s/or :one  status-set
        :many (s/coll-of status-set :kind sequential? :min-count 1)))

(s/def ::query-status ::status-filter)

(s/def ::limit (s/and pos-int? #(<= % 1000)))
(s/def ::order #{:asc :desc})

(s/def ::query-filters
  (s/keys :req-un [::since-ts]
          :opt-un [::until-ts ::query-to ::query-from
                   ::query-direction ::query-status ::limit ::order]))

;; ---------------------------------------------------------------------------
;; Result envelope
;; ---------------------------------------------------------------------------

(def error-types
  "Closed set of adapter-normalized error :type values."
  #{:sms/auth-failed :sms/rate-limited :sms/recipient-invalid
    :sms/recipient-blocked :sms/sender-not-registered :sms/network
    :sms/parse-failed :sms/simulate-not-supported :sms/timeout
    :sms/invalid-filters :sms/unknown})

(s/def ::error-type error-types)
(s/def ::error-message string?)
(s/def ::error-data    map?)

(s/def ::error
  (s/keys :req-un [::error-type ::error-message]
          :opt-un [::error-data]))

(s/def ::ok any?)

(s/def ::send-result
  (s/or :ok    (s/keys :req-un [::ok])
        :error (s/keys :req-un [::error])))

(s/def ::query-result
  (s/or :ok    (s/keys :req-un [::ok])
        :error (s/keys :req-un [::error])))

(s/def ::simulate-result ::send-result)

;; ---------------------------------------------------------------------------
;; Protocols
;; ---------------------------------------------------------------------------

(defprotocol ISMS
  "Production-capable SMS operations. Every SMS adapter implements this.
   See namespace docstring for params/result shapes."

  (send!
    [this params]
    "Send a message via the provider.

     `params` keys:
       :from   string?  — optional, adapter default falls back; E.164/short/alpha
       :to     string   — REQUIRED, E.164
       :body   string   — REQUIRED, may be empty for media-only
       :media  coll<{:url :content-type [:size]}>?  — optional, MMS

     Returns `{:ok message-map} | {:error {...}}`. The returned
     message-map is the single source of truth for what the provider
     just recorded — `:sid`, `:date-sent`, `:status`, etc.")

  (query-messages
    [this filters]
    "Query the provider's message log.

     `filters` keys:
       :since-ts  Instant       — REQUIRED, strict-exclusive lower bound
       :until-ts  Instant?      — optional, inclusive upper bound
       :to        string|coll?  — optional, OR within coll
       :from      string|coll?  — optional, OR within coll
       :direction #{:outbound :inbound}?
       :status    keyword|coll? — see status-set
       :limit     pos-int?      — default 100, max 1000
       :order     #{:asc :desc}? — default :asc

     Adapters do server-side filtering where the provider supports it,
     fall back to client-side for the rest. MUST honor every filter
     regardless of which side does the work.

     Returns `{:ok [message-map ...]}` ordered per `:order`, or
     `{:error {...}}`. Missing `:since-ts` returns
     `{:error {:type :sms/invalid-filters ...}}`."))

(defprotocol ISMSInbound
  "Capability: this adapter can originate inbound messages locally
   without a real phone. Mocks implement; production adapters that
   lack a real test-inbound API do not.

   Stepdefs requiring this capability gate via :requires-protocols
   metadata; the suite-load lint (sl-unz) validates against the
   adapter's :provides set and fails with :stepdef/missing-capability
   when unmet — once per stepdef, before any scenario starts."

  (simulate-inbound!
    [this params]
    "Inject a synthetic inbound message into the log.

     `params` keys:
       :from      string  — REQUIRED, E.164/short/alpha
       :to        string  — REQUIRED, E.164 of your provider number
       :body      string  — REQUIRED, may be \"\"
       :media     coll?   — optional
       :date-sent Instant? — optional, defaults to Instant/now

     Returns `{:ok message-map}` with `:direction :inbound`, or
     `{:error {...}}`."))
