(ns shiftlefter.gherkin.parser-table-docstring-test
  (:require [clojure.test :refer [deftest is testing]]
            [shiftlefter.gherkin.lexer :as lexer]
            [shiftlefter.gherkin.parser :as parser]
            [shiftlefter.gherkin.pickler :as pickler]))

(deftest parse-step-with-data-table
  (testing "Parse step with data table"
    (let [input "Feature: Table Test\nScenario: Test table\nGiven I have data\n| name | value |\n| foo  | bar   |"
          tokens (lexer/lex input)
          result (parser/parse tokens)]
      (is (empty? (:errors result)))
      (is (= 1 (count (:ast result))))
      (let [feature (first (:ast result))
            scenario (first (parser/get-scenarios feature))
            step (first (:steps scenario))]
        (is (= :step (:type step)))
        (is (instance? shiftlefter.gherkin.parser.DataTable (:argument step)))
        (let [table (:argument step)]
          (is (= :data-table (:type table)))
          (is (= 2 (count (:rows table))))
          (is (= ["name" "value"] (:cells (first (:rows table)))))
          (is (= ["foo" "bar"] (:cells (second (:rows table))))))))))

(deftest parse-step-with-docstring
  (testing "Parse step with docstring"
    (let [input "Feature: Doc Test\nScenario: Test doc\nGiven I have doc\n\"\"\"\nSome content\n\"\"\""
          tokens (lexer/lex input)
          result (parser/parse tokens)]
      (is (empty? (:errors result)))
      (is (= 1 (count (:ast result))))
      (let [feature (first (:ast result))
            scenario (first (parser/get-scenarios feature))
            step (first (:steps scenario))]
        (is (= :step (:type step)))
        (is (instance? shiftlefter.gherkin.parser.Docstring (:argument step)))
        (let [doc (:argument step)]
          (is (= :docstring (:type doc)))
          (is (= "Some content" (:content doc)))
          (is (= :triple-quote (:fence doc)))
          (is (nil? (:mediaType doc))))))))

(deftest parse-step-with-docstring-language
  (testing "Parse step with docstring with language"
    (let [input "Feature: Doc Test\nScenario: Test doc\nGiven I have doc\n```json\n{\"key\": \"value\"}\n```"
          tokens (lexer/lex input)
          result (parser/parse tokens)]
      (is (empty? (:errors result)))
      (is (= 1 (count (:ast result))))
      (let [feature (first (:ast result))
            scenario (first (parser/get-scenarios feature))
            step (first (:steps scenario))]
        (is (= :step (:type step)))
        (is (instance? shiftlefter.gherkin.parser.Docstring (:argument step)))
        (let [doc (:argument step)]
          (is (= :docstring (:type doc)))
          (is (= "{\"key\": \"value\"}" (:content doc)))
          (is (= :backtick (:fence doc)))
          (is (= "json" (:mediaType doc))))))))

(deftest pickle-step-arguments
  (testing "Pickle includes step arguments for table and docstring"
    (let [input "Feature: Pickle Test\nScenario: Test args\nGiven I have table\n| a | b |\n| 1 | 2 |\nWhen I have doc\n\"\"\"\ntext\n\"\"\""
          tokens (lexer/lex input)
          parsed (parser/parse tokens)
          pickles (pickler/pre-pickles (:ast parsed) {} "test.feature")]
      (is (= 1 (count pickles)))
      (let [pickle (first pickles)
            steps (:pickle/steps pickle)]
        (is (= 2 (count steps)))
        (let [table-step (first steps)
              doc-step (second steps)]
          (is (= [["a" "b"] ["1" "2"]] (:step/arguments table-step)))
          (is (= {:content "text", :mediaType nil} (:step/arguments doc-step))))))))