(ns shiftlefter.browser.url-match-test
  "Pins the ruled semantics of the two location-assertion frames (sl-q81m):
   region = path + fragment, query stripped, trailing slash normalized, host
   ignored; exactly = full URL required, structural query equality."
  (:require [clojure.test :refer [deftest is testing]]
            [shiftlefter.browser.url-match :as um]))

;; -----------------------------------------------------------------------------
;; Parsing & normalization primitives
;; -----------------------------------------------------------------------------

(deftest parse-url-parts
  (testing "absolute URL splits into raw parts"
    (is (= {:scheme "http" :authority "localhost:9092" :path "/feed"
            :query "a=1&b=2" :fragment "top"}
           (:ok (um/parse-url "http://localhost:9092/feed?a=1&b=2#top")))))
  (testing "relative path-only forms parse"
    (is (= "/feed" (-> (um/parse-url "/feed") :ok :path)))
    (is (nil? (-> (um/parse-url "/feed") :ok :authority))))
  (testing "raw parts stay percent-encoded (no decoding — ruled deferral)"
    (is (= "q=a%20b" (-> (um/parse-url "/s?q=a%20b") :ok :query))))
  (testing "unparseable input is an error, not a throw"
    (is (= :url/unparseable (-> (um/parse-url "http://[bad") :error :type)))))

(deftest normalize-path-rules
  (is (= "/" (um/normalize-path nil)))
  (is (= "/" (um/normalize-path "")))
  (is (= "/" (um/normalize-path "/")))
  (is (= "/feed" (um/normalize-path "/feed")))
  (is (= "/feed" (um/normalize-path "/feed/")))
  (is (= "/feed" (um/normalize-path "/feed//"))))

(deftest query-multimap-rules
  (is (= {} (um/query-multimap nil)))
  (is (= {} (um/query-multimap "")))
  (is (= {"a" ["1"] "b" ["2"]} (um/query-multimap "a=1&b=2")))
  (is (= {"a" ["1" "2"]} (um/query-multimap "a=1&a=2")))
  (testing "flag keys and empty values are distinct"
    (is (= {"flag" [nil]} (um/query-multimap "flag")))
    (is (= {"flag" [""]} (um/query-multimap "flag=")))
    (is (not= (um/query-multimap "flag") (um/query-multimap "flag=")))))

;; -----------------------------------------------------------------------------
;; Region frame (AC 1) — each rule red/green
;; -----------------------------------------------------------------------------

(deftest region-query-stripped
  (testing "query is STATE, not region — green across query differences"
    (is (nil? (um/region-match "/search" "http://h/search?q=foo")))
    (is (nil? (um/region-match "/search?q=old" "http://h/search?q=new")))))

(deftest region-trailing-slash-normalized
  (is (nil? (um/region-match "/feed/" "http://h/feed")))
  (is (nil? (um/region-match "/feed" "http://h/feed/")))
  (is (nil? (um/region-match "/" "http://h"))))

(deftest region-host-ignored
  (testing "host-aware assertion is the named-base-urls future bead"
    (is (nil? (um/region-match "http://staging:1234/feed" "http://prod/feed")))))

(deftest region-path-mismatch-is-red
  (let [m (um/region-match "/feed" "http://h/login")]
    (is (= :path (:reason m)))
    (is (= "/feed" (:expected m)))
    (is (= "/login" (:actual m)))
    (is (.contains ^String (:message m) "/feed"))))

(deftest region-fragment-compared
  (testing "fragment KEPT so hash-routing SPAs get region assertions"
    (is (nil? (um/region-match "/app#/settings" "http://h/app#/settings")))
    (is (= :fragment (:reason (um/region-match "/app#/settings"
                                               "http://h/app#/profile"))))
    (testing "absent fragment == empty fragment"
      (is (nil? (um/region-match "/feed" "http://h/feed#"))))))

;; -----------------------------------------------------------------------------
;; Exactly frame (AC 2) — structural equality
;; -----------------------------------------------------------------------------

(def ^:private base "http://h:9092")

(deftest exactly-cross-key-reorder-passes
  (is (nil? (um/exact-match (str base "/p?a=1&b=2") (str base "/p?b=2&a=1")))))

(deftest exactly-value-change-fails
  (is (= :query (:reason (um/exact-match (str base "/p?a=1")
                                         (str base "/p?a=2"))))))

(deftest exactly-duplicate-key-reorder-fails
  (is (= :query (:reason (um/exact-match (str base "/p?a=1&a=2")
                                         (str base "/p?a=2&a=1")))))
  (testing "same duplicate-key order passes"
    (is (nil? (um/exact-match (str base "/p?a=1&a=2") (str base "/p?a=1&a=2"))))))

(deftest exactly-is-structural-not-substring
  (testing "path is exact — no trailing-slash normalization in this frame"
    (is (= :path (:reason (um/exact-match (str base "/p/") (str base "/p"))))))
  (testing "host and scheme are compared"
    (is (= :scheme-authority
           (:reason (um/exact-match "http://other/p" (str base "/p")))))))

(deftest exactly-requires-a-full-url
  (let [m (um/exact-match "/p?a=1" (str base "/p?a=1"))]
    (is (= :not-a-full-url (:reason m)))
    (is (.contains ^String (:message m) "full URL"))
    (is (.contains ^String (:message m) "should be on"))))

(deftest match-fns-never-throw-on-garbage
  (is (= :unparseable (:reason (um/region-match "http://[bad" "http://h/p"))))
  (is (= :unparseable (:reason (um/exact-match "http://h/p" "http://[worse")))))
