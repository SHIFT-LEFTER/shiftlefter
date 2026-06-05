(ns shiftlefter.sms.twilio-test
  "Unit tests for the Twilio ISMS implementation.

   Stubs HTTP via clj-http-fake. No network. Asserts request shape
   (URL, auth header, params, body), response normalization (status /
   direction enums, RFC 2822 date), error mapping, and client-side
   filter pass."
  (:require [cheshire.core :as json]
            [clj-http.fake :as fake]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [shiftlefter.sms.protocol :as sms]
            [shiftlefter.sms.twilio :as twilio]
            [shiftlefter.test-helpers.log-capture :as log-capture])
  (:import (java.time Instant)
           (java.util Base64)))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(def ^:private account-sid "AC0123456789abcdef")
(def ^:private auth-token  "secret-token")
(def ^:private epoch Instant/EPOCH)

(defn- adapter [] (twilio/make-twilio-sms {:account-sid account-sid
                                           :auth-token  auth-token}))

(def ^:private messages-url-pattern
  #"https://api\.twilio\.com/2010-04-01/Accounts/[^/]+/Messages\.json.*")

(defn- decode-basic-auth [header]
  (when header
    (let [b64 (subs header (count "Basic "))]
      (String. (.decode (Base64/getDecoder) b64)))))

(defn- form-params [body-str]
  (->> (str/split body-str #"&")
       (map #(let [[k v] (str/split % #"=" 2)]
               [(java.net.URLDecoder/decode k "UTF-8")
                (java.net.URLDecoder/decode (or v "") "UTF-8")]))
       (reduce (fn [acc [k v]]
                 (update acc k (fn [old] (if old (conj (if (vector? old) old [old]) v) v))))
               {})))

(defn- twilio-msg
  "Construct a Twilio JSON message object with sensible defaults."
  [overrides]
  (merge {"sid"       "SM1"
          "from"      "+15550000001"
          "to"        "+15550000002"
          "body"      "hello"
          "date_sent" "Fri, 17 Apr 2026 10:00:00 +0000"
          "direction" "outbound-api"
          "status"    "queued"
          "num_segments" "1"}
         overrides))

(defn- twilio-list-response
  ([messages] (twilio-list-response messages nil))
  ([messages next-page-uri]
   (json/generate-string
    (cond-> {"messages" messages}
      next-page-uri (assoc "next_page_uri" next-page-uri)))))

(defn- twilio-send-response [overrides]
  (json/generate-string (twilio-msg overrides)))

;; ---------------------------------------------------------------------------
;; ISMS / ISMSInbound capability
;; ---------------------------------------------------------------------------

(deftest twilio-satisfies-isms-only
  (testing "TwilioSMS satisfies ISMS but NOT ISMSInbound"
    (let [a (adapter)]
      (is (satisfies? sms/ISMS a))
      (is (not (satisfies? sms/ISMSInbound a))
          "Twilio cannot fake inbound; production adapter must not satisfy ISMSInbound"))))

;; ---------------------------------------------------------------------------
;; send!
;; ---------------------------------------------------------------------------

(deftest send!-builds-correct-request
  (testing "POSTs to Messages.json with Basic auth and form params"
    (let [captured (atom nil)]
      (fake/with-fake-routes
        {messages-url-pattern
         (fn [req]
           (reset! captured req)
           {:status 201 :body (twilio-send-response {"sid" "SM-OK"
                                                     "status" "queued"})})}
        (let [result (sms/send! (adapter) {:from "+15550000001"
                                            :to   "+15550000002"
                                            :body "hi"})
              req    @captured
              params (form-params (slurp (:body req)))]
          (is (:ok result))
          (is (= "SM-OK" (-> result :ok :sid)))
          (is (= :outbound (-> result :ok :direction)))
          (is (= :queued (-> result :ok :status)))
          (is (= :post (:request-method req)))
          (is (str/ends-with? (:uri req)
                              (str "/Accounts/" account-sid "/Messages.json")))
          (is (= (str account-sid ":" auth-token)
                 (decode-basic-auth (get-in req [:headers "authorization"]))))
          (is (= {"From" "+15550000001"
                  "To"   "+15550000002"
                  "Body" "hi"} params)))))))

(deftest send!-omits-from-when-not-given
  (testing "send! params without :from sends only To/Body — adapter-default fallback path"
    (let [captured (atom nil)]
      (fake/with-fake-routes
        {messages-url-pattern
         (fn [req] (reset! captured req)
           {:status 201 :body (twilio-send-response {"sid" "SM-NF"})})}
        (sms/send! (adapter) {:to "+12" :body "no from"})
        (let [params (form-params (slurp (:body @captured)))]
          (is (not (contains? params "From"))))))))

(deftest send!-with-media
  (testing "send! with :media attaches MediaUrl as repeated form param"
    (let [captured (atom nil)]
      (fake/with-fake-routes
        {messages-url-pattern
         (fn [req] (reset! captured req)
           {:status 201 :body (twilio-send-response {"sid" "SM-MMS"})})}
        (sms/send! (adapter) {:from "+1" :to "+2" :body "hi"
                              :media [{:url "https://x/a.png" :content-type "image/png"}
                                      {:url "https://x/b.png" :content-type "image/png"}]})
        (let [params (form-params (slurp (:body @captured)))]
          (is (= ["https://x/a.png" "https://x/b.png"] (get params "MediaUrl"))))))))

(deftest send!-maps-401-to-auth-failed
  (fake/with-fake-routes
    {messages-url-pattern
     (fn [_req] {:status 401 :body "{\"code\":20003}"})}
    (let [{:keys [error]} (sms/send! (adapter) {:to "+2" :body "x"})]
      (is (= :sms/auth-failed (:type error)))
      (is (contains? sms/error-types (:type error)))
      (is (= 401 (get-in error [:data :status]))))))

(deftest send!-maps-429-to-rate-limited
  (fake/with-fake-routes
    {messages-url-pattern (fn [_req] {:status 429 :body "{}"})}
    (let [{:keys [error]} (sms/send! (adapter) {:to "+2" :body "x"})]
      (is (= :sms/rate-limited (:type error))))))

(deftest send!-maps-5xx-to-network
  (fake/with-fake-routes
    {messages-url-pattern (fn [_req] {:status 503 :body ""})}
    (let [{:keys [error]} (sms/send! (adapter) {:to "+2" :body "x"})]
      (is (= :sms/network (:type error))))))

(deftest send!-maps-thrown-to-network
  (fake/with-fake-routes
    {messages-url-pattern
     (fn [_req] (throw (java.io.IOException. "connection refused")))}
    (let [{:keys [error]} (sms/send! (adapter) {:to "+2" :body "x"})]
      (is (= :sms/network (:type error)))
      (is (= "connection refused" (:message error))))))

;; ---------------------------------------------------------------------------
;; query-messages
;; ---------------------------------------------------------------------------

(deftest query-without-since-ts-errors
  (let [{:keys [error]} (sms/query-messages (adapter) {})]
    (is (= :sms/invalid-filters (:type error)))))

(deftest query-builds-server-side-filters
  (testing "GET includes DateSent>=, DateSent<=, To, From for single-string filters"
    (let [captured (atom nil)
          since    (Instant/parse "2026-04-17T00:00:00Z")
          until    (Instant/parse "2026-04-18T00:00:00Z")]
      (fake/with-fake-routes
        {messages-url-pattern
         (fn [req] (reset! captured req)
           {:status 200 :body (twilio-list-response [])})}
        (sms/query-messages (adapter) {:since-ts since :until-ts until
                                        :to "+15550000002" :from "+15550000001"})
        (let [qs (:query-string @captured)]
          (is (= :get (:request-method @captured)))
          (is (str/includes? qs "DateSent%3E=2026-04-17T00%3A00%3A00Z"))
          (is (str/includes? qs "DateSent%3C=2026-04-18T00%3A00%3A00Z"))
          (is (str/includes? qs "To=%2B15550000002"))
          (is (str/includes? qs "From=%2B15550000001")))))))

(deftest query-coll-filters-omit-server-side
  (testing "Coll-valued :to skips server-side To= and falls back to client-side filter"
    (let [captured (atom nil)]
      (fake/with-fake-routes
        {messages-url-pattern
         (fn [req] (reset! captured req)
           {:status 200
            :body (twilio-list-response
                   [(twilio-msg {"sid" "S1" "to" "+1A"})
                    (twilio-msg {"sid" "S2" "to" "+1B"})
                    (twilio-msg {"sid" "S3" "to" "+1C"})])})}
        (let [{msgs :ok} (sms/query-messages (adapter)
                                              {:since-ts epoch
                                               :to ["+1A" "+1B"]})
              qs (:query-string @captured)]
          (is (not (str/includes? qs "To=")))
          (is (= #{"S1" "S2"} (set (map :sid msgs)))))))))

(deftest query-normalizes-status
  (testing "Twilio status enum projected onto protocol status-set"
    (fake/with-fake-routes
      {messages-url-pattern
       (fn [_req]
         {:status 200
          :body (twilio-list-response
                 [(twilio-msg {"sid" "Sd" "status" "delivered"})
                  (twilio-msg {"sid" "Sf" "status" "failed"})
                  (twilio-msg {"sid" "Sr" "status" "received"
                                "direction" "inbound"})
                  (twilio-msg {"sid" "Sx" "status" "weird-future-value"})])})}
      (let [{msgs :ok} (sms/query-messages (adapter) {:since-ts epoch})
            by-sid (into {} (map (juxt :sid identity) msgs))]
        (is (= :delivered (-> by-sid (get "Sd") :status)))
        (is (= :failed    (-> by-sid (get "Sf") :status)))
        (is (= :received  (-> by-sid (get "Sr") :status)))
        (is (= :unknown   (-> by-sid (get "Sx") :status)))))))

(deftest query-normalizes-direction
  (testing "Twilio direction projected onto :outbound | :inbound"
    (fake/with-fake-routes
      {messages-url-pattern
       (fn [_req]
         {:status 200
          :body (twilio-list-response
                 [(twilio-msg {"sid" "S1" "direction" "outbound-api"})
                  (twilio-msg {"sid" "S2" "direction" "outbound-call"})
                  (twilio-msg {"sid" "S3" "direction" "outbound-reply"})
                  (twilio-msg {"sid" "S4" "direction" "inbound"})])})}
      (let [{msgs :ok} (sms/query-messages (adapter) {:since-ts epoch})
            by-sid (into {} (map (juxt :sid identity) msgs))]
        (is (= :outbound (-> by-sid (get "S1") :direction)))
        (is (= :outbound (-> by-sid (get "S2") :direction)))
        (is (= :outbound (-> by-sid (get "S3") :direction)))
        (is (= :inbound  (-> by-sid (get "S4") :direction)))))))

(deftest query-message-conforms-to-spec
  (fake/with-fake-routes
    {messages-url-pattern
     (fn [_req] {:status 200
                 :body (twilio-list-response
                        [(twilio-msg {"sid" "SC" "date_sent" "Fri, 17 Apr 2026 12:00:00 +0000"})])})}
    (let [{[msg] :ok} (sms/query-messages (adapter) {:since-ts epoch})]
      (is (s/valid? ::sms/message msg))
      (is (instance? Instant (:date-sent msg)))
      (is (= (Instant/parse "2026-04-17T12:00:00Z") (:date-sent msg)))
      (is (= 1 (:segments msg))))))

(deftest query-applies-direction-filter-client-side
  (fake/with-fake-routes
    {messages-url-pattern
     (fn [_req] {:status 200
                 :body (twilio-list-response
                        [(twilio-msg {"sid" "Sout" "direction" "outbound-api"})
                         (twilio-msg {"sid" "Sin"  "direction" "inbound"})])})}
    (let [{outs :ok} (sms/query-messages (adapter) {:since-ts epoch
                                                     :direction :outbound})
          {ins  :ok} (sms/query-messages (adapter) {:since-ts epoch
                                                     :direction :inbound})]
      (is (= ["Sout"] (mapv :sid outs)))
      (is (= ["Sin"]  (mapv :sid ins))))))

(deftest query-order-and-limit
  (testing ":order :asc default, :order :desc reverses, :limit caps"
    (fake/with-fake-routes
      {messages-url-pattern
       (fn [_req] {:status 200
                   :body (twilio-list-response
                          [(twilio-msg {"sid" "S1" "date_sent" "Fri, 17 Apr 2026 10:00:00 +0000"})
                           (twilio-msg {"sid" "S2" "date_sent" "Fri, 17 Apr 2026 12:00:00 +0000"})
                           (twilio-msg {"sid" "S3" "date_sent" "Fri, 17 Apr 2026 14:00:00 +0000"})])})}
      (let [{asc :ok} (sms/query-messages (adapter) {:since-ts epoch})
            {desc :ok} (sms/query-messages (adapter) {:since-ts epoch :order :desc})
            {lim :ok} (sms/query-messages (adapter) {:since-ts epoch :limit 2})]
        (is (= ["S1" "S2" "S3"] (mapv :sid asc)))
        (is (= ["S3" "S2" "S1"] (mapv :sid desc)))
        (is (= 2 (count lim)))))))

(deftest query-maps-401-to-auth-failed
  (fake/with-fake-routes
    {messages-url-pattern (fn [_req] {:status 401 :body "{}"})}
    (let [{:keys [error]} (sms/query-messages (adapter) {:since-ts epoch})]
      (is (= :sms/auth-failed (:type error))))))

(deftest query-maps-5xx-to-network
  (fake/with-fake-routes
    {messages-url-pattern (fn [_req] {:status 500 :body "boom"})}
    (let [{:keys [error]} (sms/query-messages (adapter) {:since-ts epoch})]
      (is (= :sms/network (:type error)))
      (is (= 500 (get-in error [:data :status]))))))

(deftest query-since-ts-strict-exclusive-client-side
  (testing "Even if Twilio returns the boundary message, client-side strict-exclusive filters it out"
    (let [boundary (Instant/parse "2026-04-17T12:00:00Z")]
      (fake/with-fake-routes
        {messages-url-pattern
         (fn [_req] {:status 200
                     :body (twilio-list-response
                            [(twilio-msg {"sid" "Sboundary"
                                          "date_sent" "Fri, 17 Apr 2026 12:00:00 +0000"})
                             (twilio-msg {"sid" "Safter"
                                          "date_sent" "Fri, 17 Apr 2026 13:00:00 +0000"})])})}
        (let [{msgs :ok} (sms/query-messages (adapter) {:since-ts boundary})]
          (is (= ["Safter"] (mapv :sid msgs))))))))

;; ---------------------------------------------------------------------------
;; Pagination cap (sl-bnk hypothesis 2)
;;
;; Twilio's response carries `next_page_uri` when more messages exist
;; beyond the returned page. We surface that as `:at-cap true` plus a
;; `clojure.tools.logging/warn`. Hard contract: at most min(:limit,
;; 1000) per call.
;; ---------------------------------------------------------------------------

(def ^:private with-captured-logs
  "Alias for the shared helper. Kept local-name to minimise churn in
   the rest of this file."
  log-capture/with-captured-logs)

(deftest query-flags-at-cap-when-next-page-present
  (testing "Non-null next_page_uri sets :at-cap true and emits a WARN log"
    (fake/with-fake-routes
      {messages-url-pattern
       (fn [_req]
         {:status 200
          :body (twilio-list-response
                 [(twilio-msg {"sid" "S1"})
                  (twilio-msg {"sid" "S2"})]
                 "/2010-04-01/Accounts/AC0/Messages.json?Page=1&PageSize=2")})}
      (let [result (atom nil)
            logs   (with-captured-logs
                     #(reset! result
                              (sms/query-messages (adapter)
                                                  {:since-ts epoch :limit 2})))]
        (is (= true (:at-cap @result)))
        (is (= ["S1" "S2"] (mapv :sid (:ok @result))))
        (is (some (fn [[level msg]]
                    (and (= :warn level) (str/includes? msg "PageSize cap")))
                  logs)
            "warn-level log should mention PageSize cap")))))

(deftest query-flags-at-cap-false-when-no-next-page
  (testing "Null/missing next_page_uri leaves :at-cap absent and emits no warn"
    (fake/with-fake-routes
      {messages-url-pattern
       (fn [_req]
         {:status 200
          :body (twilio-list-response [(twilio-msg {"sid" "S1"})])})}
      (let [result (atom nil)
            logs   (with-captured-logs
                     #(reset! result
                              (sms/query-messages (adapter) {:since-ts epoch})))]
        (is (not (:at-cap @result))
            ":at-cap should be falsy when Twilio returns no next_page_uri")
        (is (= ["S1"] (mapv :sid (:ok @result))))
        (is (not-any? (fn [[level _]] (= :warn level)) logs)
            "no warn-level log should be emitted when no next_page_uri")))))
