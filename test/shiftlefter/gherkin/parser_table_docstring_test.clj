(ns shiftlefter.gherkin.parser-table-docstring-test
  (:require [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is testing]]
            [shiftlefter.gherkin.lexer :as lexer]
            [shiftlefter.gherkin.parser :as parser]
            [shiftlefter.gherkin.pickler :as pickler]
            [shiftlefter.gherkin.printer :as printer]))

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
        (is (instance? shiftlefter.gherkin.parser.DataTable (first (:arguments step))))
        (let [table (first (:arguments step))]
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
        (is (instance? shiftlefter.gherkin.parser.Docstring (first (:arguments step))))
        (let [doc (first (:arguments step))]
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
        (is (instance? shiftlefter.gherkin.parser.Docstring (first (:arguments step))))
        (let [doc (first (:arguments step))]
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
            table (first (:arguments step))]
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
            table (first (:arguments (first (:steps scenario))))]
        (is (instance? shiftlefter.gherkin.parser.DataTable table))
        (is (= [["a"] ["1"]] (mapv :cells (:rows table))))))))

(deftest parse-comment-and-blank-before-tables-first-row
  (testing "Interleaved comment and blank lines before the first row attach the table"
    (let [input "Feature: f\nScenario: s\nGiven t\n# c\n\n| a |\n| 1 |"
          result (parser/parse (lexer/lex input))]
      (is (empty? (:errors result)))
      (let [feature (first (:ast result))
            scenario (first (parser/get-scenarios feature))
            table (first (:arguments (first (:steps scenario))))]
        (is (instance? shiftlefter.gherkin.parser.DataTable table))
        (is (= 2 (count (:rows table))))))))

(deftest parse-comment-before-table-in-background
  (testing "Background steps get the same treatment"
    (let [input "Feature: f\nBackground:\nGiven t\n# c\n| a |"
          result (parser/parse (lexer/lex input))]
      (is (empty? (:errors result)))
      (let [feature (first (:ast result))
            background (parser/get-background feature)
            table (first (:arguments (first (:steps background))))]
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
        (is (every? #(empty? (:arguments %)) steps))))))

(deftest parse-comment-after-last-step-still-no-argument
  (testing "A trailing comment after the last step leaves the step argument-less (regression)"
    (let [input "Feature: f\nScenario: s\nGiven t\n# c\n"
          result (parser/parse (lexer/lex input))]
      (is (empty? (:errors result)))
      (let [feature (first (:ast result))
            scenario (first (parser/get-scenarios feature))
            step (first (:steps scenario))]
        (is (nil? (first (:arguments step))))))))

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
;; -----------------------------------------------------------------------------
;; sl-8nku — Gherkin 42 (cucumber/gherkin #599, #643): a step may carry BOTH a
;; DataTable and a Docstring, in either order.
;; -----------------------------------------------------------------------------

(def ^:private both-table-first
  "Feature: Both\nScenario: table then docstring\nGiven a step with both arguments\n| id | name |\n| 1 | bob |\n\"\"\"\nhello\n\"\"\"\n")

(def ^:private both-docstring-first
  "Feature: Both\nScenario: docstring then table\nGiven a step with both arguments\n\"\"\"\nhello\n\"\"\"\n| id | name |\n| 1 | bob |\n")

(defn- only-step
  "Parse `input`, assert it is error-free, and return its single step."
  [input]
  (let [result (parser/parse (lexer/lex input))]
    (is (empty? (:errors result)) (pr-str (:errors result)))
    (first (:steps (first (parser/get-scenarios (first (:ast result))))))))

(deftest parse-step-with-table-then-docstring
  (testing "DataTable followed by Docstring: two arguments in source order"
    (let [step (only-step both-table-first)
          [table doc] (:arguments step)]
      (is (= [:data-table :docstring] (mapv :type (:arguments step))))
      (is (= [["id" "name"] ["1" "bob"]] (mapv :cells (:rows table))))
      (is (= "hello" (:content doc))))))

(deftest parse-step-with-docstring-then-table
  (testing "Docstring followed by DataTable: two arguments in source order"
    (let [step (only-step both-docstring-first)
          [doc table] (:arguments step)]
      (is (= [:docstring :data-table] (mapv :type (:arguments step))))
      (is (= "hello" (:content doc)))
      (is (= [["id" "name"] ["1" "bob"]] (mapv :cells (:rows table)))))))

(deftest parse-blank-and-comment-between-the-two-arguments
  (testing "blank/comment lines between a docstring and the table still attach the table (sl-ur58 rule)"
    (let [step (only-step "Feature: f\nScenario: s\nGiven x\n\"\"\"\nhello\n\"\"\"\n\n# between\n| a |\n")]
      (is (= [:docstring :data-table] (mapv :type (:arguments step))))))
  (testing "blank/comment lines between a table and the docstring still attach the docstring"
    (let [step (only-step "Feature: f\nScenario: s\nGiven x\n| a |\n\n# between\n\"\"\"\nhello\n\"\"\"\n")]
      (is (= [:data-table :docstring] (mapv :type (:arguments step)))))))

(deftest parse-repeated-docstring-is-rejected
  (testing "two docstrings on one step: the second is not an argument (testdata/bad/repeated_step_docstring)"
    (let [input "Feature: f\nScenario: s\nGiven x\n\"\"\"\none\n\"\"\"\n\"\"\"\ntwo\n\"\"\"\n"
          result (parser/parse (lexer/lex input))
          step (first (:steps (first (parser/get-scenarios (first (:ast result))))))]
      (is (seq (:errors result)))
      (is (= [:docstring] (mapv :type (:arguments step)))))))

(deftest pickle-step-with-both-arguments
  (testing "both arguments ride :step/arguments as [first second] in source order (C1)"
    (doseq [[input expected] [[both-table-first [[["id" "name"] ["1" "bob"]] {:content "hello" :mediaType nil}]]
                              [both-docstring-first [{:content "hello" :mediaType nil} [["id" "name"] ["1" "bob"]]]]]]
      (let [pickles (pickler/pre-pickles (:ast (parser/parse (lexer/lex input))) {} "both.feature")
            args (:step/arguments (first (:pickle/steps (first pickles))))]
        (is (= expected args))
        (is (pickler/both-arguments? args))
        (is (s/valid? :step/arguments args) (s/explain-str :step/arguments args)))))
  (testing "the single-argument shapes are unchanged and valid; a two-row table is not two arguments"
    (is (s/valid? :step/arguments []))
    (is (s/valid? :step/arguments [["a" "b"] ["1" "2"]]))
    (is (s/valid? :step/arguments {:content "text" :mediaType nil}))
    (is (not (pickler/both-arguments? [["a" "b"] ["1" "2"]])))
    (is (not (s/valid? :step/arguments [{:content "x"} {:content "y"}])) "two docstrings is not a valid shape")))

(deftest outline-placeholders-substitute-into-both-arguments
  (testing "a Scenario Outline row substitutes into the table AND the docstring of one step"
    (let [input "Feature: f\nScenario Outline: o\nGiven x\n| <v> |\n\"\"\"\n<v>\n\"\"\"\nExamples:\n| v |\n| 1 |\n"
          pickles (pickler/pre-pickles (:ast (parser/parse (lexer/lex input))) {} "outline.feature")
          args (:step/arguments (first (:pickle/steps (first pickles))))]
      (is (= [[["1"]] {:content "1"}] args)))))

(deftest canonical-format-keeps-both-arguments-in-order
  (testing "canonical formatting renders both arguments in source order and is idempotent"
    (doseq [input [both-table-first both-docstring-first]]
      (let [once (printer/canonical input)
            twice (printer/canonical once)]
        (is (= once twice))
        (is (= (mapv :type (:arguments (only-step input)))
               (mapv :type (:arguments (only-step once)))))))))
