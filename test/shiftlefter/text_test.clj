(ns shiftlefter.text-test
  "Direct coverage for the neutral text-similarity substrate (sl-raf4).
   First direct tests for this logic: before the dedupe it lived as two
   private copies (svo.validate matrix impl, stepengine.annotations
   two-row impl) covered only through did-you-mean message assertions."
  (:require [clojure.test :refer [deftest is testing]]
            [shiftlefter.text :as text]))

(deftest levenshtein-test
  (testing "empty-string bases"
    (is (= 0 (text/levenshtein "" "")))
    (is (= 3 (text/levenshtein "" "abc")))
    (is (= 3 (text/levenshtein "abc" ""))))
  (testing "identity"
    (is (= 0 (text/levenshtein "web" "web"))))
  (testing "known distances"
    (is (= 1 (text/levenshtein "web" "wb")))      ; deletion
    (is (= 1 (text/levenshtein "web" "webb")))    ; insertion
    (is (= 1 (text/levenshtein "web" "web2")))    ; insertion at end
    (is (= 2 (text/levenshtein "wbe" "web")))     ; transposition = 2 edits
    (is (= 3 (text/levenshtein "kitten" "sitting")))
    (is (= 2 (text/levenshtein "flaw" "lawn"))))
  (testing "symmetry"
    (is (= (text/levenshtein "deadline-ms" "deadline")
           (text/levenshtein "deadline" "deadline-ms")))))

(deftest keyword->str-test
  (is (= "web" (text/keyword->str :web)))
  (is (= "user/alice" (text/keyword->str :user/alice))))

(deftest suggest-similar-tolerates-non-keyword-input
  ;; sl-q4cb (Warden W35): the suggester is the thing that diagnoses a
  ;; typo'd key — it must never throw on a string-keyed map's key.
  (testing "a string target is compared by its string form"
    (is (= :deadline-ms (text/suggest-similar "deadline-ms" [:deadline-ms :interval-ms] 2)))
    (is (= :interval-ms (text/suggest-similar "intervalms" [:deadline-ms :interval-ms] 2))))
  (testing "string candidates come back AS GIVEN"
    (is (= "warn" (text/suggest-similar :warm ["warn" "error" "off"] 2))))
  (testing "a number is str'd, not thrown on"
    (is (nil? (text/suggest-similar 42 [:deadline-ms] 2))))
  (testing "keyword->str on a string and a number"
    (is (= "deadline-ms" (text/keyword->str "deadline-ms")))
    (is (= "42" (text/keyword->str 42)))))

(deftest suggest-similar-test
  (testing "finds the closest candidate within the distance bound"
    (is (= :web (text/suggest-similar :wbe [:web :sms :email] 2)))
    (is (= :deadline-ms (text/suggest-similar :deadline
                                              [:deadline-ms :interval-ms] 3))))
  (testing "nil when nothing is close enough"
    (is (nil? (text/suggest-similar :zzz [:web :sms] 2))))
  (testing "nil target or empty candidates"
    (is (nil? (text/suggest-similar nil [:web] 2)))
    (is (nil? (text/suggest-similar :web [] 2))))
  (testing "namespaced keywords compare on the full name"
    (is (= :user/alice (text/suggest-similar :user/alicia
                                             [:user/alice :user/bob] 2)))))
