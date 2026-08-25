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

;; -----------------------------------------------------------------------------
;; Comments/blanks between a step and its table's first row (sl-ur58)
;; -----------------------------------------------------------------------------

(deftest parse-comment-before-tables-first-row
  (testing "A whole-line comment between a step and its table's first row attaches the table"
    (let [input "Feature: f\nScenario: s\nGiven t\n# c\n| a |\n| 1 |"
          result (parser/parse (lexer/lex input))]
      (is (empty? (:errors result)))
      (let [feature (first (:ast result))
            scenario (first (parser/get-scenarios feature))
            step (first (:steps scenario))
            table (:argument step)]
        (is (instance? shiftlefter.gherkin.parser.DataTable table))
        (is (= [["a"] ["1"]] (mapv :cells (:rows table))))
        (is (= 5 (-> table :location :line))
            "DataTable location stays anchored at the first real row")))))

(deftest parse-blank-before-tables-first-row
  (testing "A blank line between a step and its table's first row attaches the table"
    (let [input "Feature: f\nScenario: s\nGiven t\n\n| a |\n| 1 |"
          result (parser/parse (lexer/lex input))]
      (is (empty? (:errors result)))
      (let [feature (first (:ast result))
            scenario (first (parser/get-scenarios feature))
            table (:argument (first (:steps scenario)))]
        (is (instance? shiftlefter.gherkin.parser.DataTable table))
        (is (= [["a"] ["1"]] (mapv :cells (:rows table))))))))

(deftest parse-comment-and-blank-before-tables-first-row
  (testing "Interleaved comment and blank lines before the first row attach the table"
    (let [input "Feature: f\nScenario: s\nGiven t\n# c\n\n| a |\n| 1 |"
          result (parser/parse (lexer/lex input))]
      (is (empty? (:errors result)))
      (let [feature (first (:ast result))
            scenario (first (parser/get-scenarios feature))
            table (:argument (first (:steps scenario)))]
        (is (instance? shiftlefter.gherkin.parser.DataTable table))
        (is (= 2 (count (:rows table))))))))

(deftest parse-comment-before-table-in-background
  (testing "Background steps get the same treatment"
    (let [input "Feature: f\nBackground:\nGiven t\n# c\n| a |"
          result (parser/parse (lexer/lex input))]
      (is (empty? (:errors result)))
      (let [feature (first (:ast result))
            background (parser/get-background feature)
            table (:argument (first (:steps background)))]
        (is (instance? shiftlefter.gherkin.parser.DataTable table))
        (is (= [["a"]] (mapv :cells (:rows table))))))))

(deftest parse-comment-between-steps-still-no-argument
  (testing "A comment followed by another step stays a between-steps comment (regression)"
    (let [input "Feature: f\nScenario: s\nGiven t\n# c\nWhen u"
          result (parser/parse (lexer/lex input))]
      (is (empty? (:errors result)))
      (let [feature (first (:ast result))
            scenario (first (parser/get-scenarios feature))
            steps (:steps scenario)]
        (is (= 2 (count steps)))
        (is (every? #(nil? (:argument %)) steps))))))

(deftest parse-comment-after-last-step-still-no-argument
  (testing "A trailing comment after the last step leaves the step argument-less (regression)"
    (let [input "Feature: f\nScenario: s\nGiven t\n# c\n"
          result (parser/parse (lexer/lex input))]
      (is (empty? (:errors result)))
      (let [feature (first (:ast result))
            scenario (first (parser/get-scenarios feature))
            step (first (:steps scenario))]
        (is (nil? (:argument step)))))))

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