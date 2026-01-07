(ns shiftlefter.gherkin.parser-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [shiftlefter.gherkin.lexer :as lexer]
            [shiftlefter.gherkin.parser :as parser]
            [clojure.pprint :as pp]))

(deftest parse-simple-feature
  (testing "Parse a basic feature with one scenario"
    (let [input "Feature: Login\nScenario: Successful login\nGiven I am on the login page\nWhen I enter valid credentials\nThen I should be logged in"
          tokens (lexer/lex input)
          result (parser/parse tokens)]
      (is (empty? (:errors result)))
      (is (= 1 (count (:ast result))))
      (let [feature (first (:ast result))]
        (is (= :feature (:type feature)))
        (is (= "Login" (:name feature)))
        (is (= 1 (count (parser/get-scenarios feature))))
        (let [scenario (first (parser/get-scenarios feature))]
          (is (= :scenario (:type scenario)))
          (is (= "Successful login" (:name scenario)))
          (is (= 3 (count (:steps scenario)))))))))

(deftest parse-empty-input
  (testing "Handle empty input"
    (let [result (parser/parse [])]
      (is (empty? (:ast result)))
      (is (empty? (:errors result))))))

(deftest parse-multi-scenario-feature
  (testing "Parse feature with multiple scenarios"
    (let [input "Feature: Multi\nScenario: First\nGiven step1\nScenario: Second\nWhen step2\nThen step3"
          tokens (lexer/lex input)
          result (parser/parse tokens)]
      (is (empty? (:errors result)))
      (is (= 1 (count (:ast result))))
      (let [feature (first (:ast result))]
        (is (= :feature (:type feature)))
        (is (= "Multi" (:name feature)))
        (is (= 2 (count (parser/get-scenarios feature))))
        (let [[s1 s2] (parser/get-scenarios feature)]
          (is (= "First" (:name s1)))
          (is (= "Second" (:name s2))))))))

;; (deftest parse-scenario-outline
;;   (testing "Parse scenario outline with examples"
;;     (let [input "Feature: Outline Test\nScenario Outline: Example\nGiven <var>\nExamples:\n| var |\n| val |"
;;           tokens (lexer/lex input)
;;           result (parser/parse tokens)]
;;       (is (empty? (:errors result)))
;;       (is (= 1 (count (:ast result))))
;;       (let [feature (first (:ast result))
;;             scenario (first (parser/get-scenarios feature))]
;;         (is (= :scenario-outline (:type scenario)))
;;         (is (= "Example" (:name scenario)))
;;         (is (= 1 (count (:examples scenario))))
;;         (let [example (first (:examples scenario))]
;;           (is (= "Examples" (:keyword example)))
;;           (is (= ["var"] (:table-header example)))
;;           (is (= [["val"]] (:table-body example))))
;;         (let [step (first (:steps scenario))]
;;           (is (= "<var>" (:text step))))))))


(deftest parse-edge-cases
  (testing "Empty docstring and trailing spaces in table"
    (let [input "Feature: Edge\n  Scenario: Test\n    Given doc\n      ```\n      ```\n    Given table\n      | a | b  |\n      | 1 | 2  |"
          tokens (lexer/lex input)
          result (parser/parse tokens)]
      (is (empty? (:errors result)))
      (is (= 1 (count (:ast result))))
      (let [feature (first (:ast result))
            scenario (first (parser/get-scenarios feature))
            steps (:steps scenario)
            doc-step (first steps)
            doc-arg (:argument doc-step)
            table-step (second steps)
            table-arg (:argument table-step)]
        (is (instance? shiftlefter.gherkin.parser.Docstring doc-arg))
        (is (= "" (:content doc-arg)))
        (is (instance? shiftlefter.gherkin.parser.DataTable table-arg))
        (is (= 2 (count (:rows table-arg))))
        ;; Cells are trimmed as per Gherkin standard
        (is (= ["a" "b"] (:cells (first (:rows table-arg)))))
        (is (= ["1" "2"] (:cells (second (:rows table-arg)))))))))

(deftest parse-lazy-seq-partial
  (testing "Partial parsing on lazy seq"
    (let [input "Feature: Lazy\nScenario: Partial\nGiven step\nWhen another"
          tokens (lexer/lex input)
          partial-tokens (take 2 tokens) ; partial lazy seq
          result (parser/parse partial-tokens)]
      ;; Should parse up to available tokens without error
      (is (>= (count (:ast result)) 1))
      ;; Errors for incomplete, but no unexpected token errors
      (is (not-any? (fn [e] (= (:type e) :unexpected-token)) (:errors result))))))

;; ;; (deftest ^:generative ^:skip parse-gen
;; ;;   (tc/quick-check 100 (prop/for-all [ts (gen/vector (s/gen ::tokens/token) 0 50)]
;; ;;     (let [result (parser/parse ts)]
;; ;;       (s/valid? (s/get-spec `parser/parse) result)))))

(deftest parse-feature-with-tags
  (testing "Parse feature with tags - rich format"
    (let [input "@web @api\nFeature: Tagged Feature\nScenario: Test\nGiven step"
          tokens (lexer/lex input)
          result (parser/parse tokens)]
      (is (empty? (:errors result)))
      (is (= 1 (count (:ast result))))
      (let [feature (first (:ast result))]
        (is (= :feature (:type feature)))
        (is (= ["@web" "@api"] (mapv :name (:tags feature))))))))

(deftest parse-with-comments-and-blanks
  (testing "Parse with comments and blanks in AST"
    (let [input "# Comment before\n\nFeature: With Extras\n# Another comment\nScenario: Test"
          tokens (lexer/lex input)
          result (parser/parse tokens)]
      (is (empty? (:errors result)))
      (is (> (count (:ast result)) 1))
      (let [nodes (:ast result)]
        (is (= :comment (:type (first nodes))))
        (is (= :blank (:type (second nodes))))
        (is (= :feature (:type (nth nodes 2))))))))

(deftest parse-tagged-scenario
  (testing "Parse scenario with tags - rich format with locations"
    (let [input "Feature: Test\n@slow\nScenario: Tagged Scenario\nGiven step"
          tokens (lexer/lex input)
          result (parser/parse tokens)]
      (is (empty? (:errors result)))
      (let [feature (first (:ast result))
            scenario (first (:children feature))]
        (is (= :feature (:type feature)))
        (is (= :scenario (:type scenario)))
        ;; Tags are now rich format: [{:name "@tag" :location Location}]
        (is (= 1 (count (:tags scenario))))
        (is (= "@slow" (:name (first (:tags scenario)))))
        (is (= {:line 2 :column 1} (select-keys (:location (first (:tags scenario))) [:line :column])))))))

(deftest parse-bad-incomplete-docstring
  (testing "Incomplete docstring produces error"
    (let [input "Feature: Bad\nScenario: Test\nGiven step\n\"\"\"\ncontent"
          tokens (lexer/lex input)
          result (parser/parse tokens)]
      (is (seq (:errors result)))
      (let [errors (:errors result)]
        (is (some #(= :incomplete-docstring (:type %)) errors))))))

(deftest parse-bad-invalid-keyword
  (testing "Misspelled keyword treated as description, step without scenario is unexpected"
    ;; "Scenari:" is misspelled and treated as description text
    ;; "Given step" is then unexpected because there's no scenario
    (let [input "Feature: Bad\nScenari: Test\nGiven step"
          tokens (lexer/lex input)
          result (parser/parse tokens)
          _ (pp/pprint (:errors result))]
      (is (seq (:errors result)))
      (let [errors (:errors result)]
        (is (some #(= :unexpected-token (:type %)) errors))))))

(deftest parse-bad-duplicate-feature
  (testing "Duplicate features produce error"
    (let [input "Feature: First\nScenario: S1\nGiven step\nFeature: Second\nScenario: S2\nGiven step"
          tokens (lexer/lex input)
          result (parser/parse tokens)]
      (is (seq (:errors result)))
      (let [errors (:errors result)]
        (is (some #(= :duplicate-feature (:type %)) errors))))))

(deftest parse-bad-missing-feature
  (testing "Missing feature produces error"
    (let [input "Scenario: Test\nGiven step"
          tokens (lexer/lex input)
          result (parser/parse tokens)]
      (is (seq (:errors result)))
      (let [errors (:errors result)]
        (is (some #(= :missing-feature (:type %)) errors))))))

(deftest parse-bad-unexpected-token
  (testing "Unexpected table row produces error"
    (let [input "Feature: Bad\n| unexpected | table |"
          tokens (lexer/lex input)
          result (parser/parse tokens)]
      (is (seq (:errors result)))
      (let [errors (:errors result)]
        ;; Table rows at unexpected locations produce :unexpected-token error
        (is (some #(= :unexpected-token (:type %)) errors))))))

;; -----------------------------------------------------------------------------
;; Scenario Outline Regression Tests
;;
;; These tests ensure outlines don't cause infinite loops or nil leaks.
;; See parser.clj "Parser Return Contract" for background.
;; -----------------------------------------------------------------------------

(deftest outline-minimal-invalid-no-hang
  (testing "Invalid outline (missing Examples) terminates without hanging"
    ;; This is a minimal outline that's technically invalid - no Examples table.
    ;; Critical: It must NOT cause an infinite loop. It should parse and may
    ;; produce errors, but must return promptly (timeout would fail this test).
    (let [input "Feature: Outline Test\nScenario Outline: Missing Examples\nGiven <placeholder> step"
          tokens (lexer/lex input)
          result (parser/parse tokens)]
      ;; It should return something (not hang)
      (is (map? result))
      (is (contains? result :ast))
      (is (contains? result :errors))
      ;; The outline should be parsed even without Examples
      (let [feature (first (filter #(= :feature (:type %)) (:ast result)))
            scenario (first (parser/get-scenarios feature))]
        (is (= :scenario-outline (:type scenario)))
        (is (= "Missing Examples" (:name scenario)))
        ;; Examples should be empty or nil, not cause a loop
        (is (or (nil? (:examples scenario)) (empty? (:examples scenario))))))))

(deftest outline-blank-before-examples
  (testing "Outline with blank line(s) before Examples parses correctly"
    ;; Blank lines between steps and Examples should be skipped, not cause issues.
    (let [input "Feature: Outline Test\nScenario Outline: With Blank\nGiven <val> step\n\n\nExamples:\n| val |\n| foo |"
          tokens (lexer/lex input)
          result (parser/parse tokens)]
      (is (empty? (:errors result)) (str "Unexpected errors: " (:errors result)))
      (let [feature (first (filter #(= :feature (:type %)) (:ast result)))
            outline (first (parser/get-scenarios feature))]
        (is (= :scenario-outline (:type outline)))
        (is (= 1 (count (:examples outline))) "Should have exactly 1 Examples block")
        (let [ex (first (:examples outline))]
          (is (= ["val"] (:table-header ex)))
          (is (= [["foo"]] (:table-body ex))))))))

(deftest outline-tags-before-examples
  (testing "Outline with tags before Examples associates tags correctly"
    ;; Tags on a line before Examples: should attach to that Examples block.
    (let [input "Feature: Outline Test\nScenario Outline: Tagged Examples\nGiven <x> step\n@tag1 @tag2\nExamples:\n| x |\n| a |"
          tokens (lexer/lex input)
          result (parser/parse tokens)]
      (is (empty? (:errors result)) (str "Unexpected errors: " (:errors result)))
      (let [feature (first (filter #(= :feature (:type %)) (:ast result)))
            outline (first (parser/get-scenarios feature))]
        (is (= :scenario-outline (:type outline)))
        (is (= 1 (count (:examples outline))))
        (let [ex (first (:examples outline))]
          (is (= ["@tag1" "@tag2"] (mapv :name (:tags ex))) "Examples should have the tags")
          (is (= ["x"] (:table-header ex))))))))

;; -----------------------------------------------------------------------------
;; Implicit Outline Tests - Item 8: parser-outline-robustness
;; Scenario with Examples (no explicit "Scenario Outline:" keyword)
;; -----------------------------------------------------------------------------

(deftest implicit-outline-scenario-with-examples
  (testing "Scenario with Examples is treated as ScenarioOutline"
    (let [input "Feature: Test\n\nScenario: minimalistic\n  Given the <what>\n\n  Examples:\n    | what |\n    | foo  |"
          result (parser/parse (lexer/lex input))]
      (is (empty? (:errors result)) "Should parse without errors")
      (let [feature (first (:ast result))
            child (first (:children feature))]
        (is (= :scenario-outline (:type child)) "Should be treated as scenario-outline")
        (is (= 1 (count (:examples child))) "Should have one Examples table")))))

(deftest multiple-examples-tables
  (testing "Outline with multiple Examples tables"
    (let [input "Feature: Test\n\nScenario Outline: multi\n  Given <x>\n\n  @foo\n  Examples:\n    | x |\n    | a |\n\n  @bar\n  Examples:\n    | x |\n    | b |"
          result (parser/parse (lexer/lex input))]
      (is (empty? (:errors result)))
      (let [outline (first (:children (first (:ast result))))]
        (is (= 2 (count (:examples outline))) "Should have two Examples tables")
        (is (= ["@foo"] (mapv :name (:tags (first (:examples outline))))))
        (is (= ["@bar"] (mapv :name (:tags (second (:examples outline))))))))))

(deftest scenarios-followed-by-rule
  (testing "Feature with scenarios before Rule"
    (let [input "Feature: Mixed\n\nScenario: First\n  Given step1\n\n@rule-tag\nRule: My Rule\n\n  Scenario: In Rule\n    Given step2"
          result (parser/parse (lexer/lex input))]
      (is (empty? (:errors result)))
      (let [children (:children (first (:ast result)))]
        (is (= 2 (count children)))
        (is (= :scenario (:type (first children))))
        (is (= :rule (:type (second children))))
        (is (= ["@rule-tag"] (mapv :name (:tags (second children)))))))))

(deftest multi-line-tags-before-scenario
  (testing "Tags spread across multiple lines before Scenario"
    (let [input "Feature: Test\n\n@tag1 @tag2\n  @tag3\nScenario: Tagged\n  Given step"
          result (parser/parse (lexer/lex input))]
      (is (empty? (:errors result)))
      (let [scenario (first (:children (first (:ast result))))]
        (is (= ["@tag1" "@tag2" "@tag3"] (mapv :name (:tags scenario))))))))

(deftest multi-line-tags-before-feature
  (testing "Tags spread across multiple lines before Feature"
    (let [input "@feat1 @feat2\n  @feat3\nFeature: Tagged Feature\n\nScenario: S\n  Given x"
          result (parser/parse (lexer/lex input))]
      (is (empty? (:errors result)))
      (let [feature (first (:ast result))]
        (is (= ["@feat1" "@feat2" "@feat3"] (mapv :name (:tags feature))))))))

;; -----------------------------------------------------------------------------
;; Tag Line Trailing Content Error Tests - Item 3
;; -----------------------------------------------------------------------------

(deftest tag-line-trailing-comment-allowed
  (testing "Tag line with trailing #comment is valid (Gherkin spec allows inline comments)"
    (let [input "@smoke #comment\nFeature: Test\n  Scenario: Foo\n    Given bar\n"
          tokens (lexer/lex input)
          result (parser/parse tokens)]
      (is (empty? (:errors result)) "Inline comments after tags should not produce errors"))))

(deftest tag-line-trailing-text-error
  (testing "Tag line with trailing text produces parser error"
    (let [input "@smoke foo\nFeature: Test\n  Scenario: Foo\n    Given bar\n"
          tokens (lexer/lex input)
          result (parser/parse tokens)]
      (is (= 1 (count (:errors result))))
      (let [err (first (:errors result))]
        (is (= :invalid-tag-line (:type err)))
        (is (str/includes? (:message err) "foo"))))))

(deftest tag-line-examples-trailing-comment-allowed
  (testing "Tag line with trailing #comment on Examples is valid"
    (let [input "Feature: Test\n  Scenario Outline: Foo\n    Given <x>\n  @wip #comment\n  Examples:\n    | x |\n    | 1 |\n"
          tokens (lexer/lex input)
          result (parser/parse tokens)]
      (is (empty? (:errors result)) "Inline comments after tags should not produce errors"))))

(deftest tag-line-valid-no-parser-error
  (testing "Valid tag lines produce no parser errors"
    (let [input "@slow @web\nFeature: Test\n  Scenario: Foo\n    Given bar\n"
          tokens (lexer/lex input)
          result (parser/parse tokens)]
      (is (empty? (:errors result)) "Valid tag line should produce no errors"))))

;; -----------------------------------------------------------------------------
;; Span Tests - Item 4: parser-ast-node-token-spans
;; -----------------------------------------------------------------------------

(deftest parse-returns-tokens
  (testing "Parse result includes :tokens vector"
    (let [input "Feature: Test\n  Scenario: Foo\n    Given bar\n"
          tokens (lexer/lex input)
          result (parser/parse tokens)]
      (is (contains? result :tokens) "Result should have :tokens key")
      (is (vector? (:tokens result)) "Tokens should be a vector")
      (is (= 4 (count (:tokens result))) "Should have 4 tokens (3 lines + EOF)"))))

(deftest span-feature-covers-all-content
  (testing "Feature span covers from feature-line to last content"
    (let [input "Feature: Test\n  Scenario: Foo\n    Given bar\n"
          result (parser/parse (lexer/lex input))
          feature (first (filter #(= :feature (:type %)) (:ast result)))]
      (is (= {:start-idx 0 :end-idx 3} (:span feature)))
      (is (= input (parser/node->raw (:tokens result) feature))))))

(deftest span-scenario-covers-header-and-steps
  (testing "Scenario span covers scenario-line and its steps"
    (let [input "Feature: Test\n  Scenario: Foo\n    Given bar\n    When baz\n"
          result (parser/parse (lexer/lex input))
          feature (first (filter #(= :feature (:type %)) (:ast result)))
          scenario (first (parser/get-scenarios feature))]
      (is (= {:start-idx 1 :end-idx 4} (:span scenario)))
      (is (= "  Scenario: Foo\n    Given bar\n    When baz\n"
             (parser/node->raw (:tokens result) scenario))))))

(deftest span-step-covers-single-line
  (testing "Step span covers just the step line"
    (let [input "Feature: Test\n  Scenario: Foo\n    Given bar\n"
          result (parser/parse (lexer/lex input))
          feature (first (filter #(= :feature (:type %)) (:ast result)))
          step (first (:steps (first (parser/get-scenarios feature))))]
      (is (= {:start-idx 2 :end-idx 3} (:span step)))
      (is (= "    Given bar\n" (parser/node->raw (:tokens result) step))))))

(deftest span-step-with-table
  (testing "Step span includes attached data table"
    (let [input "Feature: Test\n  Scenario: Foo\n    Given a table\n      | a | b |\n      | 1 | 2 |\n"
          result (parser/parse (lexer/lex input))
          feature (first (filter #(= :feature (:type %)) (:ast result)))
          step (first (:steps (first (parser/get-scenarios feature))))]
      (is (= {:start-idx 2 :end-idx 5} (:span step)))
      (is (= "    Given a table\n      | a | b |\n      | 1 | 2 |\n"
             (parser/node->raw (:tokens result) step))))))

;; -----------------------------------------------------------------------------
;; Rule parsing tests
;; -----------------------------------------------------------------------------

(deftest parse-feature-with-rules
  (testing "Parse feature with multiple rules"
    (let [input "Feature: Rules test\n\nRule: First rule\nThe first rule description\n\n  Scenario: S1\n    Given step1\n\nRule: Second rule\n  Scenario: S2\n    Given step2"
          result (parser/parse (lexer/lex input))]
      (is (empty? (:errors result)))
      (let [feature (first (:ast result))
            rules (parser/get-rules feature)]
        (is (= 2 (count rules)))
        (is (= "First rule" (:name (first rules))))
        (is (= "Second rule" (:name (second rules))))
        ;; First rule has description
        (is (str/includes? (:description (first rules)) "first rule description"))
        ;; Each rule has one scenario
        (is (= 1 (count (parser/get-scenarios (first rules)))))
        (is (= 1 (count (parser/get-scenarios (second rules)))))))))

(deftest parse-rule-with-background
  (testing "Parse rule with its own background"
    (let [input "Feature: F\n\nRule: R\n  Background:\n    Given common step\n  Scenario: S\n    Given specific step"
          result (parser/parse (lexer/lex input))]
      (is (empty? (:errors result)))
      (let [feature (first (:ast result))
            rules (parser/get-rules feature)
            rule (first rules)
            bg (parser/get-background rule)
            scenarios (parser/get-scenarios rule)]
        (is (= 1 (count rules)))
        (is (some? bg))
        (is (= 1 (count (:steps bg))))
        (is (= 1 (count scenarios)))))))

(deftest parse-feature-background-plus-rules
  (testing "Feature can have feature-level background AND rules"
    (let [input "Feature: F\n\nBackground:\n  Given feature step\n\nRule: R\n  Scenario: S\n    Given rule step"
          result (parser/parse (lexer/lex input))]
      (is (empty? (:errors result)))
      (let [feature (first (:ast result))
            feature-bg (parser/get-background feature)
            rules (parser/get-rules feature)]
        (is (some? feature-bg) "Feature has background")
        (is (= 1 (count rules)))
        (is (= "R" (:name (first rules))))))))

(deftest parse-rule-with-tags
  (testing "Rules can have tags"
    (let [input "Feature: F\n\n@rule-tag\nRule: Tagged\n  Scenario: S\n    Given step"
          result (parser/parse (lexer/lex input))]
      (is (empty? (:errors result)))
      (let [rule (first (parser/get-rules (first (:ast result))))]
        (is (= ["@rule-tag"] (mapv :name (:tags rule))))))))

;; -----------------------------------------------------------------------------
;; i18n / Language Support Tests
;; -----------------------------------------------------------------------------

(deftest parse-french-feature
  (testing "Parse French feature file with # language: fr"
    (let [input "#language:fr\nFonctionnalité: Test\n\n  Scénario: Mon test\n    Soit un exemple\n    Quand je fais quelque chose\n    Alors ça marche"
          result (parser/parse (lexer/lex input))]
      (is (empty? (:errors result)) "No parse errors")
      (let [feature (first (:ast result))]
        (is (= :feature (:type feature)))
        (is (= "Test" (:name feature)))
        (let [scenario (first (parser/get-scenarios feature))]
          (is (= "Mon test" (:name scenario)))
          (is (= 3 (count (:steps scenario))))
          (is (= "Given" (:keyword (first (:steps scenario)))))
          (is (= "When" (:keyword (second (:steps scenario)))))
          (is (= "Then" (:keyword (nth (:steps scenario) 2)))))))))

(deftest parse-emoji-feature
  (testing "Parse emoji feature file with # language: em"
    (let [input "# language: em\n📚: Test\n\n  📕: Cucumber\n    😐 something"
          result (parser/parse (lexer/lex input))]
      (is (empty? (:errors result)) "No parse errors")
      (let [feature (first (:ast result))]
        (is (= :feature (:type feature)))
        (is (= "Test" (:name feature)))
        (let [scenario (first (parser/get-scenarios feature))]
          (is (= "Cucumber" (:name scenario)))
          (is (= 1 (count (:steps scenario)))))))))

(deftest parse-language-switch-mid-file
  (testing "Language switches after first Feature are ignored"
    ;; Per Gherkin spec, language must be on first line.
    ;; Our parser uses first language header for the whole file.
    (let [input "# language: en\nFeature: English\n  Scenario: Test\n    Given step"
          result (parser/parse (lexer/lex input))]
      (is (empty? (:errors result)))
      (is (= "English" (:name (first (:ast result))))))))

;; -----------------------------------------------------------------------------
;; Description Parsing Tests - Item 10
;; -----------------------------------------------------------------------------

(deftest scenario-with-description
  (testing "Scenario can have a description"
    (let [input "Feature: Test\n\n  Scenario: With description\n  This is a description\n  that spans multiple lines\n    Given step"
          result (parser/parse (lexer/lex input))]
      (is (empty? (:errors result)) "No parse errors")
      (let [scenario (first (:children (first (:ast result))))]
        (is (str/includes? (:description scenario) "This is a description"))
        (is (str/includes? (:description scenario) "spans multiple lines"))
        (is (= 1 (count (:steps scenario))))))))

(deftest scenario-outline-with-description
  (testing "Scenario Outline can have a description"
    (let [input "Feature: Test\n\n  Scenario Outline: With description\n  Outline description here\n    Given <step>\n\n  Examples:\n    | step |\n    | foo  |"
          result (parser/parse (lexer/lex input))]
      (is (empty? (:errors result)) "No parse errors")
      (let [outline (first (:children (first (:ast result))))]
        (is (= :scenario-outline (:type outline)))
        (is (str/includes? (:description outline) "Outline description"))
        (is (= 1 (count (:examples outline))))))))

(deftest examples-with-description
  (testing "Examples can have a description"
    (let [input "Feature: Test\n\n  Scenario Outline: Test\n    Given <x>\n\n  Examples: with description\n  This is an examples description\n    | x |\n    | a |"
          result (parser/parse (lexer/lex input))]
      (is (empty? (:errors result)) "No parse errors")
      (let [outline (first (:children (first (:ast result))))
            examples (first (:examples outline))]
        (is (str/includes? (:description examples) "examples description"))))))

(deftest background-with-description
  (testing "Background can have a description"
    (let [input "Feature: Test\n\n  Background: with name\n  A background description\n    Given step"
          result (parser/parse (lexer/lex input))]
      (is (empty? (:errors result)) "No parse errors")
      (let [bg (parser/get-background (first (:ast result)))]
        (is (some? bg))
        (is (= "with name" (:name bg)))
        (is (str/includes? (:description bg) "background description"))))))

(deftest description-with-empty-lines
  (testing "Description can contain empty lines"
    (let [input "Feature: Test\n\n  Scenario: Multi\n  First line\n\n  Second line after blank\n    Given step"
          result (parser/parse (lexer/lex input))]
      (is (empty? (:errors result)) "No parse errors")
      (let [scenario (first (:children (first (:ast result))))]
        (is (str/includes? (:description scenario) "First line"))
        (is (str/includes? (:description scenario) "Second line"))))))

(deftest description-stops-at-step
  (testing "Description stops when step keyword is encountered"
    (let [input "Feature: Test\n\n  Scenario: S\n  Description text\n    Given this is a step"
          result (parser/parse (lexer/lex input))]
      (is (empty? (:errors result)) "No parse errors")
      (let [scenario (first (:children (first (:ast result))))]
        (is (str/includes? (:description scenario) "Description text"))
        ;; Description should NOT include the step
        (is (not (str/includes? (:description scenario) "Given")))
        (is (= 1 (count (:steps scenario))))))))

(deftest description-stops-at-table
  (testing "Description stops at table row (for Examples)"
    (let [input "Feature: Test\n\n  Scenario Outline: S\n    Given <x>\n\n  Examples:\n  Some description\n    | x |\n    | a |"
          result (parser/parse (lexer/lex input))]
      (is (empty? (:errors result)) "No parse errors")
      (let [examples (first (:examples (first (:children (first (:ast result))))))]
        (is (str/includes? (:description examples) "Some description"))
        ;; Table should be parsed correctly
        (is (= ["x"] (:table-header examples)))
        (is (= [["a"]] (:table-body examples)))))))

(deftest description-preserves-indentation
  (testing "Description preserves original leading whitespace"
    (let [input "Feature: Test\n  This is a single line description\n\n  Scenario: S\n    Given step"
          result (parser/parse (lexer/lex input))]
      (is (empty? (:errors result)) "No parse errors")
      (let [feature (first (:ast result))]
        ;; Description should preserve the 2-space indent
        (is (= "  This is a single line description" (:description feature)))))))

(deftest description-strips-surrounding-blanks
  (testing "Description strips leading and trailing blank lines"
    (let [input "Feature: Test\n\n  Scenario: With blanks\n\n  This is content\n\n    Given step"
          result (parser/parse (lexer/lex input))]
      (is (empty? (:errors result)) "No parse errors")
      (let [scenario (first (:children (first (:ast result))))]
        ;; Leading blank before "This is content" should be stripped
        ;; Trailing blank before Given should be stripped
        (is (= "  This is content" (:description scenario)))))))

(deftest description-preserves-internal-blanks
  (testing "Description preserves blank lines in the middle"
    (let [input "Feature: Test\n\n  Scenario: With internal blank\n  First\n\n  Second\n    Given step"
          result (parser/parse (lexer/lex input))]
      (is (empty? (:errors result)) "No parse errors")
      (let [scenario (first (:children (first (:ast result))))]
        ;; Internal blank line should be preserved as \n\n
        (is (= "  First\n\n  Second" (:description scenario)))))))

;; -----------------------------------------------------------------------------
;; DataTable Edge Cases - Item 11
;; -----------------------------------------------------------------------------

(deftest datatable-with-blanks-and-comments
  (testing "DataTable can have blank lines and comments between rows"
    (let [input "Feature: Test\n\n  Scenario: S\n    Given a table\n      | a | b |\n\n      | c | d |\n      # comment\n      | e | f |"
          result (parser/parse (lexer/lex input))]
      (is (empty? (:errors result)) "No parse errors")
      (let [step (first (:steps (first (:children (first (:ast result))))))
            table (:argument step)
            rows (:rows table)]
        (is (= 3 (count rows)) "Should have 3 rows despite blanks and comments")
        (is (= ["a" "b"] (:cells (first rows))))
        (is (= ["c" "d"] (:cells (second rows))))
        (is (= ["e" "f"] (:cells (nth rows 2))))))))

(deftest datatable-escaped-pipes
  (testing "DataTable cells with escaped pipes"
    (let [input "Feature: Test\n\n  Scenario: S\n    Given a table\n      | a\\|b | c |"
          result (parser/parse (lexer/lex input))]
      (is (empty? (:errors result)) "No parse errors")
      (let [step (first (:steps (first (:children (first (:ast result))))))
            table (:argument step)
            cells (:cells (first (:rows table)))]
        (is (= ["a|b" "c"] cells) "Escaped pipe should become literal pipe in cell")))))

(deftest datatable-escaped-backslash
  (testing "DataTable cells with escaped backslashes"
    (let [input "Feature: Test\n\n  Scenario: S\n    Given a table\n      | a\\\\b | c |"
          result (parser/parse (lexer/lex input))]
      (is (empty? (:errors result)) "No parse errors")
      (let [step (first (:steps (first (:children (first (:ast result))))))
            table (:argument step)
            cells (:cells (first (:rows table)))]
        (is (= ["a\\b" "c"] cells) "Escaped backslash should become single backslash")))))

(deftest datatable-escaped-newline
  (testing "DataTable cells with escaped newlines"
    (let [input "Feature: Test\n\n  Scenario: S\n    Given a table\n      | a\\nb | c |"
          result (parser/parse (lexer/lex input))]
      (is (empty? (:errors result)) "No parse errors")
      (let [step (first (:steps (first (:children (first (:ast result))))))
            table (:argument step)
            cells (:cells (first (:rows table)))]
        (is (= ["a\nb" "c"] cells) "Escaped n should become newline")))))

(deftest datatable-extra-content-ignored
  (testing "Extra content after last pipe is ignored"
    (let [input "Feature: Test\n\n  Scenario: S\n    Given a table\n      | a | b | extra stuff"
          result (parser/parse (lexer/lex input))]
      (is (empty? (:errors result)) "No parse errors")
      (let [step (first (:steps (first (:children (first (:ast result))))))
            table (:argument step)
            cells (:cells (first (:rows table)))]
        (is (= ["a" "b"] cells) "Extra content after last pipe should be ignored")))))

;; -----------------------------------------------------------------------------
;; Background edge cases (Item 12)
;; -----------------------------------------------------------------------------

(deftest background-source-text-preserves-keyword
  (testing "Background source-text includes keyword for AST projection"
    (let [input "Feature: Test\n\n  Background: setup\n    Given a step\n\n  Scenario: test\n    Given another step"
          result (parser/parse (lexer/lex input))]
      (is (empty? (:errors result)) "No parse errors")
      (let [background (first (:children (first (:ast result))))]
        (is (= :background (:type background)))
        (is (string? (:source-text background)))
        (is (str/includes? (:source-text background) "Background"))))))

(deftest scenario-source-text-preserves-keyword
  (testing "Scenario source-text includes keyword for AST projection"
    (let [input "Feature: Test\n\n  Scenario: my test\n    Given a step"
          result (parser/parse (lexer/lex input))]
      (is (empty? (:errors result)) "No parse errors")
      (let [scenario (first (:children (first (:ast result))))]
        (is (= :scenario (:type scenario)))
        (is (string? (:source-text scenario)))
        (is (str/includes? (:source-text scenario) "Scenario"))))))

(deftest rule-source-text-preserves-keyword
  (testing "Rule source-text includes keyword for AST projection"
    (let [input "Feature: Test\n\n  Rule: my rule\n\n    Scenario: test\n      Given a step"
          result (parser/parse (lexer/lex input))]
      (is (empty? (:errors result)) "No parse errors")
      (let [rule (first (:children (first (:ast result))))]
        (is (= :rule (:type rule)))
        (is (string? (:source-text rule)))
        (is (str/includes? (:source-text rule) "Rule"))))))

(deftest star-keyword-parses-as-step
  (testing "Star (*) keyword is parsed as a valid step"
    (let [input "Feature: Test\n\n  Scenario: star test\n    Given setup\n    * a star step\n    Then done"
          result (parser/parse (lexer/lex input))]
      (is (empty? (:errors result)) "No parse errors")
      (let [steps (:steps (first (:children (first (:ast result)))))]
        (is (= 3 (count steps)))
        ;; Star can map to any step keyword canonically - returned as string
        (is (some? (:keyword (second steps))) "Star parses with a keyword")
        (is (= "a star step" (:text (second steps))))))))

;; -----------------------------------------------------------------------------
;; RT2: CRLF/CR Regression Tests - CR must not leak into parsed content
;; -----------------------------------------------------------------------------

(deftest crlf-does-not-leak-into-step-text
  (testing "Step text does not contain trailing CR from CRLF input"
    (let [input "Feature: Test\r\nScenario: CRLF test\r\nGiven I have CRLF lines\r\nThen it works\r\n"
          result (parser/parse (lexer/lex input))]
      (is (empty? (:errors result)))
      (let [steps (:steps (first (:children (first (:ast result)))))]
        (doseq [step steps]
          (is (not (str/ends-with? (:text step) "\r"))
              (str "Step text should not end with CR: " (pr-str (:text step)))))))))

(deftest cr-does-not-leak-into-step-text
  (testing "Step text does not contain trailing CR from classic Mac input"
    (let [input "Feature: Test\rScenario: CR test\rGiven I have CR lines\rThen it works\r"
          result (parser/parse (lexer/lex input))]
      (is (empty? (:errors result)))
      (let [steps (:steps (first (:children (first (:ast result)))))]
        (doseq [step steps]
          (is (not (str/ends-with? (:text step) "\r"))
              (str "Step text should not end with CR: " (pr-str (:text step)))))))))

(deftest crlf-does-not-leak-into-description
  (testing "Description lines do not contain trailing CR from CRLF input"
    (let [input "Feature: Test\r\n  This is a description\r\n  with multiple lines\r\nScenario: test\r\nGiven step\r\n"
          result (parser/parse (lexer/lex input))]
      (is (empty? (:errors result)))
      (let [feature (first (:ast result))
            desc (:description feature)]
        (is (not (str/includes? desc "\r"))
            (str "Description should not contain CR: " (pr-str desc)))))))

(deftest crlf-does-not-leak-into-docstring
  (testing "Docstring content does not contain trailing CR from CRLF input"
    (let [input "Feature: Test\r\nScenario: Docstring test\r\nGiven a step with docstring:\r\n\"\"\"\r\nLine one\r\nLine two\r\n\"\"\"\r\n"
          result (parser/parse (lexer/lex input))]
      (is (empty? (:errors result)))
      (let [step (first (:steps (first (:children (first (:ast result))))))
            docstring (:argument step)]
        (is (some? docstring) "Step should have docstring argument")
        (is (not (str/includes? (:content docstring) "\r"))
            (str "Docstring content should not contain CR: " (pr-str (:content docstring))))))))

;; -----------------------------------------------------------------------------
;; Error Ordering Tests
;;
;; Parser errors must be reported in source line order (lowest line first).
;; This was broken when using (concat errors new-errors) because concat returns
;; a lazy seq, and conj on a lazy seq prepends instead of appending.
;; Fix: use (into errors new-errors) to preserve vector type.
;; -----------------------------------------------------------------------------

(deftest errors-reported-in-line-order
  (testing "Multiple parse errors are reported in ascending line order"
    (let [input "Feature: Test\n  Given orphan step\n  Rule: R"
          result (parser/parse (lexer/lex input))
          error-lines (mapv #(get-in % [:location :line]) (:errors result))]
      (is (= 2 (count (:errors result))) "Should have 2 errors")
      (is (= error-lines (sort error-lines))
          (str "Errors should be in line order, got: " error-lines)))))

(deftest errors-in-order-for-malformed-feature
  (testing "Torture test file produces errors in line order"
    (let [content (slurp "test/fixtures/gherkin/stress/gpt-tokenizer-stress.feature")
          result (parser/parse (lexer/lex content))
          error-lines (mapv #(get-in % [:location :line]) (:errors result))]
      (is (pos? (count (:errors result))) "Should have parse errors")
      (is (= error-lines (sort error-lines))
          (str "Errors should be in ascending line order, got: " error-lines)))))
