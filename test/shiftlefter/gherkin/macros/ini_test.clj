(ns shiftlefter.gherkin.macros.ini-test
  (:require [clojure.test :refer [deftest is testing]]
            [shiftlefter.gherkin.macros.ini :as ini]))

(def test-file "test/resources/macros/bad-macros.ini")

(deftest test-parse-valid-macro
  (testing "Parses a valid macro correctly"
    (let [result (ini/parse-macro test-file "Log in as admin")]
      (is (= (:errors result) []))
      (is (= (:name (:macro result)) "Log in as admin"))
      (is (= (:description (:macro result)) "Standard login flow"))
      (is (= (count (:steps (:macro result))) 5))
      (is (= (:keyword (first (:steps (:macro result)))) "Given")))))

(deftest test-parse-another-valid-macro
  (testing "Parses another valid macro"
    (let [result (ini/parse-macro test-file "Create valid user")]
      (is (= (:errors result) []))
      (is (= (:name (:macro result)) "Create valid user")))))

(deftest test-parse-macro-not-found
  (testing "Returns error for non-existent macro"
    (let [result (ini/parse-macro test-file "Non-existent")]
      (is (nil? (:macro result)))
      (is (= (count (:errors result)) 1))
      (is (.contains (:message (first (:errors result))) "not found")))))

(deftest test-parse-bad-indent
  (testing "Detects inconsistent indentation"
    (let [result (ini/parse-macro test-file "bad-indent")]
      (is (nil? (:macro result)))
      (is (= (count (:errors result)) 1))
      (is (.contains (:message (first (:errors result))) "Inconsistent indentation")))))

(deftest test-parse-invalid-steps
  (testing "Detects invalid step lines"
    (let [result (ini/parse-macro test-file "not-steps")]
      (is (nil? (:macro result)))
      (is (> (count (:errors result)) 0))
      (is (some #(.contains (:message %) "Invalid step line") (:errors result))))))

(deftest test-parse-macro-with-placeholders
  (testing "Handles placeholders in steps"
    (let [result (ini/parse-macro test-file "Log in as admin")
          steps (:steps (:macro result))]
      (is (some #(.contains (:text %) "{email}") steps)))))