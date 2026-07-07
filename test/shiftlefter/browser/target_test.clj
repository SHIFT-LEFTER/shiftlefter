(ns shiftlefter.browser.target-test
  "Unit tests for the resolved-target shape: predicates, the cardinality guard,
   and query-all boundary validation."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.spec.alpha :as s]
            [shiftlefter.browser.target :as target]))

;; -----------------------------------------------------------------------------
;; Predicates
;; -----------------------------------------------------------------------------

(deftest predicates
  (testing "query-target?"
    (is (target/query-target? {:q {:css ".x"}}))
    (is (not (target/query-target? {:el :h}))))
  (testing "el-target?"
    (is (target/el-target? {:el :h}))
    (is (not (target/el-target? {:q {:css ".x"}}))))
  (testing "targets? (the [*] vector)"
    (is (target/targets? [{:el :a} {:el :b}]))
    (is (target/targets? []))
    (is (not (target/targets? {:el :a}))))
  (testing "target? accepts any valid shape"
    (is (target/target? {:q {:css ".x"}}))
    (is (target/target? {:el :h}))
    (is (target/target? [{:el :a}]))
    (is (not (target/target? 42)))))

;; -----------------------------------------------------------------------------
;; Specs
;; -----------------------------------------------------------------------------

(deftest specs
  (is (s/valid? ::target/target {:q {:css ".x"}}))
  (is (s/valid? ::target/target {:el :h}))
  (is (s/valid? ::target/scope :document))
  (is (s/valid? ::target/scope nil))
  (is (s/valid? ::target/scope {:el :h}))
  (is (not (s/valid? ::target/scope {:q {:css ".x"}})))
  (is (s/valid? ::target/targets [{:el :a} {:el :b}])))

;; -----------------------------------------------------------------------------
;; Cardinality guard
;; -----------------------------------------------------------------------------

(deftest ensure-single
  (testing "passes single targets through"
    (is (= {:q {:css ".x"}} (target/ensure-single {:q {:css ".x"}} "loc")))
    (is (= {:el :h} (target/ensure-single {:el :h} "loc"))))
  (testing "throws on a [*] collection"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"collection"
                          (target/ensure-single [{:el :a} {:el :b}] "loc")))
    (try
      (target/ensure-single [{:el :a}] "Region.x[*]")
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (is (= :browser/target-cardinality (:type (ex-data e))))
        (is (= "Region.x[*]" (:location (ex-data e))))))))

;; -----------------------------------------------------------------------------
;; Boundary validation
;; -----------------------------------------------------------------------------

(deftest check-query-all-args
  (testing "valid args return nil (no throw)"
    (is (nil? (target/check-query-all-args! :document {:q {:css ".x"}})))
    (is (nil? (target/check-query-all-args! nil {:q {:css ".x"}})))
    (is (nil? (target/check-query-all-args! {:el :h} {:q {:css ".x"}}))))
  (testing "bad scope throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"scope"
                          (target/check-query-all-args! {:q {:css ".x"}} {:q {:css ".x"}}))))
  (testing "bad locator throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"locator"
                          (target/check-query-all-args! :document {:el :h})))))
