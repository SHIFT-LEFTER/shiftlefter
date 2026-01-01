(ns shiftlefter.gherkin.macros-test
  (:require [clojure.test :refer [deftest is testing]]
            [shiftlefter.gherkin.macros :as macros]
            [shiftlefter.gherkin.lexer :as lexer]
            [shiftlefter.gherkin.parser :as parser]))

(def test-macros-dir "test/resources/macros/good/")

(deftest test-load-macro-registry
  (testing "Loads macro registry from .ini files"
    (let [registry (macros/load-macro-registry test-macros-dir)]
      (is (map? registry))
      (is (contains? registry "Log in as admin"))
      (is (= 3 (count registry))) ; valid macros
      (let [macro (get registry "Log in as admin")]
        (is (= (:name macro) "Log in as admin"))
        (is (= 5 (count (:steps macro))))))))

(deftest test-expand-ast-valid-macro
  (testing "Expands valid macro-step with source traceability"
    (let [feature-str "Feature: Test\n\nScenario: Test\n    Given Log in as admin +\n    Then check"
          tokens (lexer/lex feature-str)
          pre-ast (:ast (parser/parse tokens))
          registry (macros/load-macro-registry test-macros-dir)
          _ (println "Registry:" registry)
          expanded (macros/expand-ast pre-ast registry)]
      (is (seq? expanded))
      (let [feature (first expanded)
            scenario (first (parser/get-scenarios feature))
            steps (:steps scenario)]
        (is (= 6 (count steps))) ; 5 expanded + 1 original
        (let [first-expanded (first steps)]
          (is (contains? first-expanded :keyword))
          (is (contains? first-expanded :source))
          (is (= (:macro-name (:source first-expanded)) "Log in as admin"))
          (is (contains? (:source first-expanded) :original-location)))))))

(deftest test-expand-ast-unknown-macro
  (testing "Returns error for unknown macro"
    (let [feature-str "Feature: Test\n\nScenario: Test\n    Given unknown +\n    Then check"
          tokens (lexer/lex feature-str)
          pre-ast (:ast (parser/parse tokens))
          registry (macros/load-macro-registry test-macros-dir)
          expanded (macros/expand-ast pre-ast registry)]
      (is (seq? expanded))
      (let [feature (first expanded)
            scenario (first (parser/get-scenarios feature))
            steps (:steps scenario)]
        (is (= 2 (count steps))) ; error + original
        (let [first-step (first steps)]
          (is (contains? first-step :error))
          (is (= (:message (:error first-step)) "Unknown macro: unknown")))))))

;; TODO: Add test for cycle detection when implemented