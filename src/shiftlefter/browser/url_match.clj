(ns shiftlefter.browser.url-match
  "URL comparison for the location-assertion frames (sl-q81m).

   Two ruled match modes:

   - `region-match` — the `should be on` frame. Path identifies REGION, query
     identifies STATE: compare normalized PATH + FRAGMENT, strip the query,
     ignore scheme/host entirely (host-aware assertion is the named-base-urls
     future bead). Trailing slashes are normalized; fragment is kept so
     hash-routing SPAs get region assertions.
   - `exact-match` — the `should be on exactly` frame. STRUCTURAL exactness,
     not byte equality: the expectation must be a full URL (scheme + host);
     scheme/authority and path and fragment compare exact, and the query
     compares as an ordered multimap — cross-key order insignificant
     (?a=1&b=2 == ?b=2&a=1), duplicate-key value order significant
     (?a=1&a=2 != ?a=2&a=1).

   Deferral valve (ruled): percent-encoding normalization, scheme/host case
   rules, and byte-exact matching are NOT handled here — components are
   compared as raw strings (java.net.URI raw accessors, no decoding). Exotic
   needs go to a custom step assertion or a petition for a third frame.

   Both match fns are pure: nil on match, else a mismatch map
   `{:reason ... :message ... :expected ... :actual ...}` the stepdefs turn
   into a retryable assertion failure."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str])
  (:import [java.net URI URISyntaxException]))

;; -----------------------------------------------------------------------------
;; Specs
;; -----------------------------------------------------------------------------

(s/def ::scheme (s/nilable string?))
(s/def ::authority (s/nilable string?))
(s/def ::path (s/nilable string?))
(s/def ::query (s/nilable string?))
(s/def ::fragment (s/nilable string?))
(s/def ::parts (s/keys :req-un [::scheme ::authority ::path ::query ::fragment]))

(s/def ::reason keyword?)
(s/def ::message string?)
(s/def ::mismatch (s/keys :req-un [::reason ::message]))

;; -----------------------------------------------------------------------------
;; Parsing & normalization
;; -----------------------------------------------------------------------------

(defn parse-url
  "Parse a URL (absolute or relative) into raw, undecoded parts.

   Returns {:ok {:scheme :authority :path :query :fragment}} — absent
   components are nil — or {:error {:type :url/unparseable ...}}."
  [s]
  (try
    (let [uri (URI. (str s))]
      {:ok {:scheme (.getScheme uri)
            :authority (.getRawAuthority uri)
            :path (.getRawPath uri)
            :query (.getRawQuery uri)
            :fragment (.getRawFragment uri)}})
    (catch URISyntaxException e
      {:error {:type :url/unparseable
               :message (str "Unparseable URL: \"" s "\" (" (ex-message e) ")")
               :input s}})))

(defn normalize-path
  "Region-frame path normalization: nil/empty -> \"/\"; trailing slashes
   stripped except the root itself."
  [p]
  (let [p (or p "")]
    (if (str/blank? p)
      "/"
      (let [stripped (str/replace p #"/+$" "")]
        (if (str/blank? stripped) "/" stripped)))))

(defn query-multimap
  "Parse a raw query string into an ordered multimap {key -> [values...]}.
   Raw strings only — no percent-decoding (ruled deferral). A key with no `=`
   maps to nil; `k=` maps to \"\". nil/empty query -> {}."
  [q]
  (if (str/blank? q)
    {}
    (reduce (fn [acc pair]
              (let [[k v] (str/split pair #"=" 2)]
                (update acc k (fnil conj []) v)))
            {}
            (remove str/blank? (str/split q #"&")))))

;; -----------------------------------------------------------------------------
;; Match fns
;; -----------------------------------------------------------------------------

(defn- unparseable-mismatch
  [which err]
  {:reason :unparseable
   :message (str (if (= which :expected) "Expected" "Actual")
                 " URL is unparseable: " (:message err))
   which (:input err)})

(defn region-match
  "Region-frame comparison: nil when `actual-url` is on the same region as
   `expected-url` (normalized path + fragment equal; query stripped; scheme
   and host ignored), else a mismatch map."
  [expected-url actual-url]
  (let [e (parse-url expected-url)
        a (parse-url actual-url)]
    (cond
      (:error e) (unparseable-mismatch :expected (:error e))
      (:error a) (unparseable-mismatch :actual (:error a))
      :else
      (let [ep (normalize-path (:path (:ok e)))
            ap (normalize-path (:path (:ok a)))
            ef (or (:fragment (:ok e)) "")
            af (or (:fragment (:ok a)) "")]
        (cond
          (not= ep ap)
          {:reason :path
           :message (str "Expected region '" ep "' but browser is on '" ap
                         "' (actual URL: '" actual-url "'; query ignored)")
           :expected ep :actual ap}

          (not= ef af)
          {:reason :fragment
           :message (str "Expected fragment '" ef "' but browser has '" af
                         "' (region '" ep "'; actual URL: '" actual-url "')")
           :expected ef :actual af}

          :else nil)))))

(defn exact-match
  "Exactly-frame comparison: nil when `actual-url` is structurally equal to
   `expected-url` (scheme+authority, exact path, exact fragment, query as an
   ordered multimap), else a mismatch map. The expectation must be a full URL —
   'exactly' names an exact resource, which includes its host."
  [expected-url actual-url]
  (let [e (parse-url expected-url)
        a (parse-url actual-url)]
    (cond
      (:error e) (unparseable-mismatch :expected (:error e))
      (:error a) (unparseable-mismatch :actual (:error a))
      :else
      (let [{es :scheme ea :authority ep :path eq :query ef :fragment} (:ok e)
            {as :scheme aa :authority ap :path aq :query af :fragment} (:ok a)]
        (cond
          (or (nil? es) (nil? ea))
          {:reason :not-a-full-url
           :message (str "'should be on exactly' requires a full URL (scheme + "
                         "host), got '" expected-url "' — for a region-level "
                         "assertion use 'should be on'")
           :expected expected-url}

          (or (not= es as) (not= ea aa))
          {:reason :scheme-authority
           :message (str "Expected exactly '" es "://" ea "...' but browser is "
                         "on '" (or as "") "://" (or aa "") "...' (actual URL: '"
                         actual-url "')")
           :expected (str es "://" ea) :actual (str as "://" aa)}

          (not= (or ep "") (or ap ""))
          {:reason :path
           :message (str "Expected exactly path '" (or ep "") "' but browser is "
                         "on '" (or ap "") "' (actual URL: '" actual-url "')")
           :expected (or ep "") :actual (or ap "")}

          (not= (or ef "") (or af ""))
          {:reason :fragment
           :message (str "Expected exactly fragment '" (or ef "") "' but browser "
                         "has '" (or af "") "' (actual URL: '" actual-url "')")
           :expected (or ef "") :actual (or af "")}

          (not= (query-multimap eq) (query-multimap aq))
          {:reason :query
           :message (str "Expected exactly query '" (or eq "") "' but browser "
                         "has '" (or aq "") "' (structural comparison: cross-key "
                         "order ignored, duplicate-key value order significant)")
           :expected (query-multimap eq) :actual (query-multimap aq)}

          :else nil)))))

(s/fdef region-match
  :args (s/cat :expected-url string? :actual-url string?)
  :ret (s/nilable ::mismatch))

(s/fdef exact-match
  :args (s/cat :expected-url string? :actual-url string?)
  :ret (s/nilable ::mismatch))
