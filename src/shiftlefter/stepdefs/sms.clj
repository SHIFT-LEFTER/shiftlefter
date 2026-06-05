(ns shiftlefter.stepdefs.sms
  "Built-in SMS step definitions for ShiftLefter.

   All SMS step verbs use 'to PHONE' addressing — phone is the *recipient*
   (where messages are sent or polled-for). Phone literals are temporary;
   a follow-up bead introduces subject→phone resolution and the literal
   slot will become a subject reference instead.

   ## Action steps

   - `:subject sends an SMS to '<phone>' saying '<body>'`
   - `:subject sends an MMS to '<phone>' with caption '<body>' and media '<url>'`

   ## Polling steps (poll-with-timeout)

   - `:subject receives an SMS to '<phone>' matching /<regex>/`
   - `:subject receives an SMS to '<phone>' since '<iso-timestamp>' matching /<regex>/`
   - `:subject receives an SMS to '<phone>' within the last <N> <unit> matching /<regex>/`

   ## Verification step (single-shot)

   - `:subject should see <N> messages to '<phone>'`

   ## Polling policy

   `:receive` polls the configured ISMS adapter with a smart-default
   `:since-ts` ladder:

       (or :sms/last-receive-ts        ; chained receives don't double-match
           :sms/scenario-start-ts      ; first receive in scenario
           (now - default-window))     ; fallback (1 hour)

   Default poll timeout 30s, interval 500ms — bind `*receive-timeout-ms*`
   or `*poll-interval-ms*` to override. Per-interface overrides via the
   `:config` slot of `:interfaces.<key>` aren't wired in v1; future bead
   plumbs them through.

   `:see :count` is single-shot (no polling) and always uses
   `:sms/scenario-start-ts` as the `:since-ts` floor — assertion on a
   stable scenario-scoped count, not on whatever `:receive` left in
   `:sms/last-receive-ts`.

   ## Capture storage

   Successful `:receive` writes:

       :sms/captures      {:full <match-string> :groups [g1 g2 ...]}
       :sms/last-message  <full message-map>
       :sms/last-receive-ts  <matched-message :date-sent>

   to ctx. The advance of `:sms/last-receive-ts` plus the protocol's
   strict-exclusive `:since-ts` semantics is what prevents a chained
   `:receive` from double-matching the message just consumed.

   ## Capability gating

   All four verbs declare `:requires-protocols
   [:shiftlefter.sms.protocol/ISMS]`. Bind-time gating (sl-ewn) ensures
   the configured adapter provides ISMS before the scenario runs."
  (:require [shiftlefter.capabilities.ctx :as cap]
            [shiftlefter.sms.protocol :as sms-proto]
            [shiftlefter.stepengine.registry :refer [defstep]])
  (:import (java.time Duration Instant)
           (java.util.regex Pattern)))

;; -----------------------------------------------------------------------------
;; Polling configuration
;; -----------------------------------------------------------------------------

(def ^:dynamic *receive-timeout-ms*
  "Wall-clock budget for `:receive` to find a matching inbound message.
   Default 30s — comfortable headroom for typical 2FA SMS arrival latency."
  30000)

(def ^:dynamic *poll-interval-ms*
  "Sleep between adapter polls during `:receive`. Default 500ms — tight
   enough for 2FA latency, loose enough to avoid hammering provider APIs."
  500)

(def ^:private default-window-ms
  "Fallback :since-ts window when no explicit baseline is available."
  (* 60 60 1000))

;; -----------------------------------------------------------------------------
;; Subject normalization
;; -----------------------------------------------------------------------------

(defn- subject->keyword
  "Normalize a captured subject string to its instance keyword.

   `:user/alice` → `:alice`, `:alice` → `:alice`, `\":alice\"` → `:alice`,
   `\"alice\"` → `:alice`. Glossary-free; matches the convention used by
   browser stepdefs."
  [s]
  (when s
    (let [trimmed (if (and (string? s) (.startsWith ^String s ":"))
                    (subs s 1)
                    s)
          kw (if (keyword? trimmed) trimmed (keyword trimmed))]
      (if (namespace kw)
        (keyword (name kw))
        kw))))

(defn- get-sms-impl
  "Resolve the SMS impl for `subject-str` from ctx.

   Under `:shared-impl?` (the SMS norm), every subject-keyed entry
   references the same impl, so any present subject finds the shared
   client. Falls back to the bare `:cap/sms` key if the capability
   wasn't subject-keyed."
  [ctx subject-str]
  (let [subj (subject->keyword subject-str)]
    (or (cap/get-capability ctx :sms subj)
        (cap/get-capability ctx :sms))))

;; -----------------------------------------------------------------------------
;; Time-baseline helpers
;; -----------------------------------------------------------------------------

(defn- smart-baseline-ts
  "Smart-default :since-ts for `:receive :default`.

   Order: `:sms/last-receive-ts` (a previous receive's anchor) →
   `:sms/scenario-start-ts` (set when SMS capability provisioned) →
   (now − default-window) as a last-resort floor."
  [ctx]
  (or (:sms/last-receive-ts ctx)
      (:sms/scenario-start-ts ctx)
      (.minus (Instant/now) (Duration/ofMillis default-window-ms))))

(defn- parse-iso-instant
  "Parse a step-text ISO-8601 timestamp into an Instant. Throws a
   structured error on malformed input rather than the bare DateTimeParseException."
  [s]
  (try
    (Instant/parse s)
    (catch Exception e
      (throw (ex-info (str "Invalid ISO-8601 timestamp: " (pr-str s))
                      {:type   :sms/invalid-since-iso
                       :input  s
                       :reason (ex-message e)})))))

(defn- parse-duration
  "Parse a step-text duration like `5 minutes` / `30 seconds` / `2 hours`
   into a `java.time.Duration`. Plural-tolerant (`1 second` and `1 seconds`
   both parse). Throws on unknown units."
  [s]
  (let [[_ n unit] (re-matches #"(\d+)\s+(seconds?|minutes?|hours?)" s)]
    (when-not n
      (throw (ex-info (str "Invalid :within-last duration: " (pr-str s))
                      {:type :sms/invalid-duration :input s})))
    (let [v (Long/parseLong n)]
      (case unit
        ("second" "seconds") (Duration/ofSeconds v)
        ("minute" "minutes") (Duration/ofMinutes v)
        ("hour"   "hours")   (Duration/ofHours v)))))

;; -----------------------------------------------------------------------------
;; Polling wrapper
;; -----------------------------------------------------------------------------

(defn- with-poll-timeout
  "Repeatedly call `f` every `*poll-interval-ms*` until it returns a
   truthy value or `*receive-timeout-ms*` elapses. Returns f's truthy
   result, or throws `:sms/receive-timeout`."
  [f context]
  (let [deadline (+ (System/currentTimeMillis) *receive-timeout-ms*)]
    (loop []
      (or (f)
          (if (< (System/currentTimeMillis) deadline)
            (do (Thread/sleep *poll-interval-ms*) (recur))
            (throw (ex-info "Timed out waiting for inbound SMS"
                            (merge {:type :sms/receive-timeout
                                    :timeout-ms *receive-timeout-ms*}
                                   context))))))))

;; -----------------------------------------------------------------------------
;; Receive: shared core
;; -----------------------------------------------------------------------------

(defn- compile-match
  "Compile the user-supplied match expression to a `Pattern`. Throws a
   structured error if the regex is invalid."
  [s]
  (try
    (Pattern/compile s)
    (catch Exception e
      (throw (ex-info (str "Invalid match regex: " (pr-str s))
                      {:type   :sms/invalid-match-regex
                       :input  s
                       :reason (ex-message e)})))))

(defn- match-message
  "Run `pattern` against `msg`'s :body. Returns
   `{:msg msg :full <whole-match> :groups [g1 ...]}` on hit, nil on miss."
  [^Pattern pattern msg]
  (let [m (.matcher pattern (str (:body msg)))]
    (when (.find m)
      {:msg    msg
       :full   (.group m 0)
       :groups (mapv #(.group m (int %))
                     (range 1 (inc (.groupCount m))))})))

(defn- attempt-receive
  "One non-blocking pass: query the adapter once and try to match.
   Returns the same hit-or-nil shape as `match-message`."
  [sms pattern filters]
  (let [{:keys [ok error]} (sms-proto/query-messages sms filters)]
    (when error
      (throw (ex-info (str "ISMS query-messages failed: " (:message error))
                      {:type :sms/query-failed :error error})))
    (some #(match-message pattern %) ok)))

(defn- update-receive-state
  "Stash a successful match into ctx: `:sms/captures`, `:sms/last-message`,
   `:sms/last-receive-ts`. The last-receive-ts advance is what makes
   chained `:receive` calls non-double-matching under strict-exclusive
   `:since-ts`."
  [ctx {:keys [msg full groups]}]
  (-> ctx
      (assoc :sms/captures      {:full full :groups groups})
      (assoc :sms/last-message  msg)
      (assoc :sms/last-receive-ts (:date-sent msg))))

(defn- do-receive
  "Common :receive body — resolve impl, build filters with the given
   :since-ts, poll-with-timeout, stash on hit."
  [ctx subject-str to-phone match-str since-ts]
  (let [sms     (get-sms-impl ctx subject-str)
        pattern (compile-match match-str)
        filters {:since-ts  since-ts
                 :to        to-phone
                 :direction :inbound}
        hit     (with-poll-timeout
                  #(attempt-receive sms pattern filters)
                  {:to-phone to-phone :match match-str :since-ts since-ts})]
    (update-receive-state ctx hit)))

;; -----------------------------------------------------------------------------
;; Patterns
;; -----------------------------------------------------------------------------

(def ^:private send-pattern
  (re-pattern ":([\\w./-]+) sends an SMS to '([^']+)' saying '([^']*)'"))

(def ^:private send-media-pattern
  (re-pattern (str ":([\\w./-]+) sends an MMS to '([^']+)'"
                   " with caption '([^']*)' and media '([^']+)'")))

(def ^:private receive-default-pattern
  (re-pattern ":([\\w./-]+) receives an SMS to '([^']+)' matching /(.+)/"))

(def ^:private receive-since-pattern
  (re-pattern (str ":([\\w./-]+) receives an SMS to '([^']+)'"
                   " since '([^']+)' matching /(.+)/")))

(def ^:private receive-within-pattern
  (re-pattern (str ":([\\w./-]+) receives an SMS to '([^']+)'"
                   " within the last (\\d+ (?:seconds?|minutes?|hours?))"
                   " matching /(.+)/")))

(def ^:private see-count-pattern
  (re-pattern ":([\\w./-]+) should see (\\d+) messages to '([^']+)'"))

;; -----------------------------------------------------------------------------
;; Stepdefs
;; -----------------------------------------------------------------------------

(defstep send-pattern
  {:interface :sms
   :requires-protocols [:shiftlefter.sms.protocol/ISMS]
   :svo {:subject :$1 :verb :send :frame :to
         :object nil
         :args {:to-phone :$2 :body :$3}}}
  [ctx subject-str to-phone body]
  (let [sms (get-sms-impl ctx subject-str)
        {:keys [error]} (sms-proto/send! sms {:to to-phone :body body})]
    (when error
      (throw (ex-info (str "SMS send failed: " (:message error))
                      {:type :sms/send-failed :error error})))
    ctx))

(defstep send-media-pattern
  {:interface :sms
   :requires-protocols [:shiftlefter.sms.protocol/ISMS]
   :svo {:subject :$1 :verb :send-media :frame :to
         :object nil
         :args {:to-phone :$2 :body :$3 :media-url :$4}}}
  [ctx subject-str to-phone body media-url]
  (let [sms (get-sms-impl ctx subject-str)
        ;; v1: generic content-type — sl-bnk introduces extension-based
        ;; inference / explicit content-type slot if MMS testing expands.
        media [{:url media-url :content-type "application/octet-stream"}]
        {:keys [error]} (sms-proto/send! sms {:to to-phone :body body :media media})]
    (when error
      (throw (ex-info (str "MMS send failed: " (:message error))
                      {:type :sms/send-failed :error error})))
    ctx))

(defstep receive-default-pattern
  {:interface :sms
   :requires-protocols [:shiftlefter.sms.protocol/ISMS]
   :svo {:subject :$1 :verb :receive :frame :default
         :object nil
         :args {:to-phone :$2 :match :$3}}}
  [ctx subject-str to-phone match-str]
  (do-receive ctx subject-str to-phone match-str (smart-baseline-ts ctx)))

(defstep receive-since-pattern
  {:interface :sms
   :requires-protocols [:shiftlefter.sms.protocol/ISMS]
   :svo {:subject :$1 :verb :receive :frame :since-iso
         :object nil
         :args {:to-phone :$2 :since-iso :$3 :match :$4}}}
  [ctx subject-str to-phone iso-str match-str]
  (do-receive ctx subject-str to-phone match-str (parse-iso-instant iso-str)))

(defstep receive-within-pattern
  {:interface :sms
   :requires-protocols [:shiftlefter.sms.protocol/ISMS]
   :svo {:subject :$1 :verb :receive :frame :within-last
         :object nil
         :args {:to-phone :$2 :duration :$3 :match :$4}}}
  [ctx subject-str to-phone duration-str match-str]
  (let [duration (parse-duration duration-str)
        baseline (.minus (Instant/now) duration)]
    (do-receive ctx subject-str to-phone match-str baseline)))

(defstep see-count-pattern
  {:interface :sms
   :requires-protocols [:shiftlefter.sms.protocol/ISMS]
   :svo {:subject :$1 :verb :see :frame :count
         :object nil
         :args {:count :$2 :to-phone :$3}}}
  [ctx subject-str count-str to-phone]
  (let [expected (Long/parseLong count-str)
        sms      (get-sms-impl ctx subject-str)
        baseline (or (:sms/scenario-start-ts ctx)
                     (.minus (Instant/now) (Duration/ofMillis default-window-ms)))
        {:keys [ok error]} (sms-proto/query-messages
                            sms
                            {:since-ts  baseline
                             :to        to-phone
                             :direction :inbound})]
    (when error
      (throw (ex-info (str "ISMS query-messages failed: " (:message error))
                      {:type :sms/query-failed :error error})))
    (let [actual (count ok)]
      (when-not (= expected actual)
        (throw (ex-info (str "Expected " expected " messages to " to-phone
                             " but found " actual)
                        {:type     :sms/count-mismatch
                         :expected expected
                         :actual   actual
                         :to-phone to-phone}))))
    ctx))
