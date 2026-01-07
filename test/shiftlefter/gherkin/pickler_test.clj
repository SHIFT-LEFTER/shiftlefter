(ns shiftlefter.gherkin.pickler-test
  (:require [clojure.test :refer [deftest is testing]]
            [shiftlefter.gherkin.pickler :as pickler]
            [shiftlefter.gherkin.lexer :as lexer]
            [shiftlefter.gherkin.parser :as parser]))

(deftest test-pickles-basic
  (testing "Generates basic flat pickles from AST"
    (let [feature-str "Feature: Test Feature\n\nScenario: Test Scenario\n    Given I do something\n    When I do another\n    Then I check result"
          tokens (lexer/lex feature-str)
          ast (:ast (parser/parse tokens))
          result (pickler/pickles ast {} "test.feature")
          pickles (:pickles result)]
      (is (empty? (:errors result)))
      (is (seq? pickles))
      (is (= 1 (count pickles)))
      (let [pickle (first pickles)]
        (is (= "Test Scenario" (:pickle/name pickle)))
        (is (= "test.feature" (:pickle/source-file pickle)))
        (is (= [] (:pickle/tags pickle)))
        (is (vector? (:pickle/steps pickle)))
        (is (= 3 (count (:pickle/steps pickle))))
        (let [first-step (first (:pickle/steps pickle))]
          (is (instance? java.util.UUID (:step/id first-step)))
          (is (= "Given" (:step/keyword first-step)))
          (is (= "I do something" (:step/text first-step))))))))

(deftest test-pickles-literal-plus-suffix
  (testing "Step text with ' +' suffix is preserved literally (no macro interpretation)"
    (let [feature-str "Feature: Test Feature\n\nScenario: Test Scenario\n    Given login as alice +\n    Then check result"
          tokens (lexer/lex feature-str)
          ast (:ast (parser/parse tokens))
          result (pickler/pickles ast {} "test.feature")
          pickles (:pickles result)]
      (is (empty? (:errors result)))
      (is (= 1 (count pickles)))
      (let [pickle (first pickles)
            steps (:pickle/steps pickle)]
        (is (= 2 (count steps)))
        ;; The " +" suffix is preserved as literal text
        (let [first-step (first steps)]
          (is (= "Given" (:step/keyword first-step)))
          (is (= "login as alice +" (:step/text first-step))))
        (let [last-step (last steps)]
          (is (= "Then" (:step/keyword last-step)))
          (is (= "check result" (:step/text last-step))))))))

;; (deftest test-pre-pickles-outline-stub
;;   (testing "Stubs outlines as single pickle with warning"
;;     (let [feature-str "Feature: Test\n  Scenario Outline: Outlined\n    Given <var>\n  Examples:\n    | var |\n    | val |"
;;           tokens (lexer/lex feature-str)
;;           pre-ast (:ast (parser/parse tokens))
;;           registry {}
;;           pickles (pickler/pre-pickles pre-ast registry "test.feature")]
;;       (is (= 1 (count pickles)))
;;       (is (= "Outlined [STUBBED - Outlines not yet supported]" (:pickle/name (first pickles)))))))

(deftest test-output-formats
  (testing "Output formats produce valid strings"
    (let [pickles [{:pickle/id (java.util.UUID/randomUUID) :pickle/name "test" :pickle/steps []}]
          edn (pickler/pickles->edn pickles)
          json (pickler/pickles->json pickles)
          ndjson (pickler/pickles->ndjson pickles)]
      (is (string? edn))
      (is (string? json))
      (is (string? ndjson))
      (is (.startsWith edn "["))
      (is (.startsWith json "["))
      ;; For single pickle, ndjson has no \n
      (is (not (.contains ndjson "\n"))))))

;;  (deftest ^:generative pickles-gen
;;    (tc/quick-check 100 (prop/for-all [ast (gen/vector (gen/map gen/keyword gen/any) 0 5)
;;                                         registry (gen/map gen/keyword gen/any)
;;                                         file (gen/string)]
;;      (let [result (pickler/pickles ast registry file)]
;;        (s/valid? (s/get-spec `pickler/pickles) result)))))

;; -----------------------------------------------------------------------------
;; Background Injection Tests (PK1)
;; -----------------------------------------------------------------------------

(defn- parse-and-pickle
  "Helper: parse feature string and return pickles."
  [feature-str]
  (let [tokens (lexer/lex feature-str)
        ast (:ast (parser/parse tokens))]
    (:pickles (pickler/pickles ast {} "test.feature"))))

(deftest test-background-injection-none
  (testing "Scenario without any background has only scenario steps with :scenario origin"
    (let [feature-str "Feature: No Background
  Scenario: Simple
    Given step one
    When step two"
          pickles (parse-and-pickle feature-str)]
      (is (= 1 (count pickles)))
      (let [steps (:pickle/steps (first pickles))]
        (is (= 2 (count steps)))
        (is (= "step one" (:step/text (first steps))))
        (is (= :scenario (:step/origin (first steps))))
        (is (= "step two" (:step/text (second steps))))
        (is (= :scenario (:step/origin (second steps))))))))

(deftest test-background-injection-feature-only
  (testing "Feature background steps are prepended with :feature-background origin"
    (let [feature-str "Feature: With Background
  Background:
    Given setup step
    And another setup

  Scenario: Test
    When action
    Then result"
          pickles (parse-and-pickle feature-str)]
      (is (= 1 (count pickles)))
      (let [steps (:pickle/steps (first pickles))]
        (is (= 4 (count steps)) "Should have 2 background + 2 scenario steps")
        ;; First two from background
        (is (= "setup step" (:step/text (nth steps 0))))
        (is (= :feature-background (:step/origin (nth steps 0))))
        (is (= "another setup" (:step/text (nth steps 1))))
        (is (= :feature-background (:step/origin (nth steps 1))))
        ;; Last two from scenario
        (is (= "action" (:step/text (nth steps 2))))
        (is (= :scenario (:step/origin (nth steps 2))))
        (is (= "result" (:step/text (nth steps 3))))
        (is (= :scenario (:step/origin (nth steps 3))))))))

(deftest test-background-injection-rule-only
  (testing "Rule background steps are prepended with :rule-background origin"
    (let [feature-str "Feature: Rule Background
  Rule: Business Rule
    Background:
      Given rule setup

    Scenario: In Rule
      When rule action"
          pickles (parse-and-pickle feature-str)]
      (is (= 1 (count pickles)))
      (let [steps (:pickle/steps (first pickles))]
        (is (= 2 (count steps)) "Should have 1 rule-bg + 1 scenario step")
        (is (= "rule setup" (:step/text (first steps))))
        (is (= :rule-background (:step/origin (first steps))))
        (is (= "rule action" (:step/text (second steps))))
        (is (= :scenario (:step/origin (second steps))))))))

(deftest test-background-injection-both-combined
  (testing "Feature + Rule backgrounds combine correctly"
    (let [feature-str "Feature: Combined Backgrounds
  Background:
    Given feature setup

  Scenario: Feature Level
    When feature action

  Rule: Business Rule
    Background:
      Given rule setup

    Scenario: Rule Level
      When rule action"
          pickles (parse-and-pickle feature-str)]
      (is (= 2 (count pickles)) "Should have 2 pickles (feature scenario + rule scenario)")

      ;; First pickle: feature-level scenario (feature-bg + scenario)
      (let [feature-pickle (first pickles)
            steps (:pickle/steps feature-pickle)]
        (is (= "Feature Level" (:pickle/name feature-pickle)))
        (is (= 2 (count steps)) "Feature scenario: 1 feature-bg + 1 scenario")
        (is (= "feature setup" (:step/text (first steps))))
        (is (= :feature-background (:step/origin (first steps))))
        (is (= "feature action" (:step/text (second steps))))
        (is (= :scenario (:step/origin (second steps)))))

      ;; Second pickle: rule scenario (feature-bg + rule-bg + scenario)
      (let [rule-pickle (second pickles)
            steps (:pickle/steps rule-pickle)]
        (is (= "Rule Level" (:pickle/name rule-pickle)))
        (is (= 3 (count steps)) "Rule scenario: 1 feature-bg + 1 rule-bg + 1 scenario")
        (is (= "feature setup" (:step/text (nth steps 0))))
        (is (= :feature-background (:step/origin (nth steps 0))))
        (is (= "rule setup" (:step/text (nth steps 1))))
        (is (= :rule-background (:step/origin (nth steps 1))))
        (is (= "rule action" (:step/text (nth steps 2))))
        (is (= :scenario (:step/origin (nth steps 2))))))))

(deftest test-background-injection-multiple-scenarios
  (testing "Background is injected into each scenario independently"
    (let [feature-str "Feature: Multi Scenario
  Background:
    Given common setup

  Scenario: First
    When first action

  Scenario: Second
    When second action"
          pickles (parse-and-pickle feature-str)]
      (is (= 2 (count pickles)))

      ;; Both scenarios should have background prepended
      (doseq [pickle pickles]
        (let [steps (:pickle/steps pickle)]
          (is (= 2 (count steps)))
          (is (= "common setup" (:step/text (first steps))))
          (is (= :feature-background (:step/origin (first steps)))))))))

;; -----------------------------------------------------------------------------
;; Tag Inheritance Tests (PK2)
;; -----------------------------------------------------------------------------

(defn- tag-names
  "Extract tag names from a pickle's tags."
  [pickle]
  (mapv :name (:pickle/tags pickle)))

(deftest test-tag-inheritance-feature-only
  (testing "Feature tags are inherited by all scenarios"
    (let [feature-str "@feature-tag
Feature: Test
  Scenario: S1
    Given step"
          pickles (parse-and-pickle feature-str)]
      (is (= 1 (count pickles)))
      (is (= ["@feature-tag"] (tag-names (first pickles)))))))

(deftest test-tag-inheritance-feature-and-scenario
  (testing "Scenario tags are appended after feature tags"
    (let [feature-str "@feature-tag
Feature: Test
  @scenario-tag
  Scenario: S1
    Given step"
          pickles (parse-and-pickle feature-str)]
      (is (= 1 (count pickles)))
      (is (= ["@feature-tag" "@scenario-tag"] (tag-names (first pickles)))))))

(deftest test-tag-inheritance-rule-tags
  (testing "Rule tags are included between feature and scenario tags"
    (let [feature-str "@feature-tag
Feature: Test
  @rule-tag
  Rule: My Rule
    @scenario-tag
    Scenario: In Rule
      Given step"
          pickles (parse-and-pickle feature-str)]
      (is (= 1 (count pickles)))
      (is (= ["@feature-tag" "@rule-tag" "@scenario-tag"] (tag-names (first pickles)))))))

(deftest test-tag-inheritance-mixed-rule-and-feature-scenarios
  (testing "Feature-level scenarios don't get rule tags"
    (let [feature-str "@feature-tag
Feature: Test
  @direct-tag
  Scenario: Direct
    Given step

  @rule-tag
  Rule: My Rule
    @rule-scenario-tag
    Scenario: In Rule
      Given step"
          pickles (parse-and-pickle feature-str)
          direct (first (filter #(= "Direct" (:pickle/name %)) pickles))
          in-rule (first (filter #(= "In Rule" (:pickle/name %)) pickles))]
      (is (= 2 (count pickles)))
      ;; Direct scenario: feature + scenario (no rule)
      (is (= ["@feature-tag" "@direct-tag"] (tag-names direct)))
      ;; In Rule scenario: feature + rule + scenario
      (is (= ["@feature-tag" "@rule-tag" "@rule-scenario-tag"] (tag-names in-rule))))))

(deftest test-tag-inheritance-examples
  (testing "Examples tags are appended after scenario tags"
    (let [feature-str "@feature-tag
Feature: Test
  @outline-tag
  Scenario Outline: Outlined
    Given <thing>
  @examples-tag
  Examples:
    | thing |
    | foo   |"
          pickles (parse-and-pickle feature-str)]
      (is (= 1 (count pickles)))
      (is (= ["@feature-tag" "@outline-tag" "@examples-tag"] (tag-names (first pickles)))))))

(deftest test-tag-deduplication
  (testing "Duplicate tags are removed, preserving first occurrence"
    (let [feature-str "@shared @feature-only
Feature: Test
  @shared
  Rule: My Rule
    @shared @scenario-only
    Scenario: In Rule
      Given step"
          pickles (parse-and-pickle feature-str)]
      (is (= 1 (count pickles)))
      ;; @shared should appear only once (first occurrence from feature)
      (is (= ["@shared" "@feature-only" "@scenario-only"] (tag-names (first pickles)))))))

(deftest test-tag-order-full-hierarchy
  (testing "Full tag hierarchy: feature → rule → scenario → examples"
    (let [feature-str "@f1 @f2
Feature: Test
  @r1
  Rule: My Rule
    @s1 @s2
    Scenario Outline: Outlined
      Given <thing>
    @e1
    Examples:
      | thing |
      | foo   |"
          pickles (parse-and-pickle feature-str)]
      (is (= 1 (count pickles)))
      (is (= ["@f1" "@f2" "@r1" "@s1" "@s2" "@e1"] (tag-names (first pickles)))))))

;; -----------------------------------------------------------------------------
;; Outline Pickle Extensions Tests (BIRDSONG §4.3)
;; -----------------------------------------------------------------------------

(deftest test-outline-pickle-provenance-fields
  (testing "Outline pickles have template-name, row-index, and row-values"
    (let [feature-str "Feature: Outline Test
  Scenario Outline: Login as <role>
    Given I enter <username>
  Examples:
    | role  | username |
    | admin | alice    |
    | user  | bob      |"
          pickles (parse-and-pickle feature-str)]
      (is (= 2 (count pickles)) "Should expand to 2 pickles (one per row)")

      ;; First pickle (row 0)
      (let [p0 (first pickles)]
        (is (= "Login as admin" (:pickle/name p0)) "Name should be substituted")
        (is (= "Login as <role>" (:pickle/template-name p0)) "Template name preserved")
        (is (= 0 (:pickle/row-index p0)) "Row index should be 0")
        (is (= {"role" "admin" "username" "alice"} (:pickle/row-values p0)) "Row values map"))

      ;; Second pickle (row 1)
      (let [p1 (second pickles)]
        (is (= "Login as user" (:pickle/name p1)) "Name should be substituted")
        (is (= "Login as <role>" (:pickle/template-name p1)) "Template name preserved")
        (is (= 1 (:pickle/row-index p1)) "Row index should be 1")
        (is (= {"role" "user" "username" "bob"} (:pickle/row-values p1)) "Row values map")))))

(deftest test-outline-step-template-text
  (testing "Outline steps have template-text with placeholders"
    (let [feature-str "Feature: Step Template Test
  Scenario Outline: Test <var>
    Given I have <count> items
    When I add <more> more
  Examples:
    | var | count | more |
    | foo | 5     | 3    |"
          pickles (parse-and-pickle feature-str)
          pickle (first pickles)
          steps (:pickle/steps pickle)]
      (is (= 1 (count pickles)))
      (is (= 2 (count steps)))

      ;; First step
      (let [step1 (first steps)]
        (is (= "I have 5 items" (:step/text step1)) "Text should be substituted")
        (is (= "I have <count> items" (:step/template-text step1)) "Template text preserved"))

      ;; Second step
      (let [step2 (second steps)]
        (is (= "I add 3 more" (:step/text step2)) "Text should be substituted")
        (is (= "I add <more> more" (:step/template-text step2)) "Template text preserved")))))

(deftest test-non-outline-pickle-nil-fields
  (testing "Non-outline pickles have nil for outline provenance fields"
    (let [feature-str "Feature: Regular Scenario
  Scenario: Simple test
    Given a step
    When another step"
          pickles (parse-and-pickle feature-str)
          pickle (first pickles)]
      (is (= 1 (count pickles)))
      (is (= "Simple test" (:pickle/name pickle)))

      ;; Outline-specific fields should be nil
      (is (nil? (:pickle/template-name pickle)) "template-name should be nil")
      (is (nil? (:pickle/row-index pickle)) "row-index should be nil")
      (is (nil? (:pickle/row-values pickle)) "row-values should be nil")
      (is (nil? (:pickle/scenario-location pickle)) "scenario-location should be nil")
      (is (nil? (:pickle/row-location pickle)) "row-location should be nil")

      ;; Steps should have nil template-text
      (doseq [step (:pickle/steps pickle)]
        (is (nil? (:step/template-text step)) "step template-text should be nil")))))

(deftest test-outline-with-multiple-examples-blocks
  (testing "Multiple Examples blocks each produce pickles with correct indices"
    (let [feature-str "Feature: Multi Examples
  Scenario Outline: Test <val>
    Given step with <val>
  Examples: First set
    | val |
    | a   |
    | b   |
  Examples: Second set
    | val |
    | x   |
    | y   |"
          pickles (parse-and-pickle feature-str)]
      (is (= 4 (count pickles)) "Should have 4 pickles total")

      ;; Row indices reset per Examples block
      (is (= 0 (:pickle/row-index (nth pickles 0))))
      (is (= 1 (:pickle/row-index (nth pickles 1))))
      (is (= 0 (:pickle/row-index (nth pickles 2))))
      (is (= 1 (:pickle/row-index (nth pickles 3))))

      ;; Check values
      (is (= {"val" "a"} (:pickle/row-values (nth pickles 0))))
      (is (= {"val" "b"} (:pickle/row-values (nth pickles 1))))
      (is (= {"val" "x"} (:pickle/row-values (nth pickles 2))))
      (is (= {"val" "y"} (:pickle/row-values (nth pickles 3)))))))

(deftest test-outline-fixture-file
  (testing "Parse outline.feature fixture and verify fields"
    (let [content (slurp "test/fixtures/features/outline.feature")
          pickles (parse-and-pickle content)]
      (is (= 3 (count pickles)) "Should have 3 pickles (admin, user, guest)")

      ;; All should have the same template name
      (doseq [p pickles]
        (is (= "Login as <role>" (:pickle/template-name p))))

      ;; Check specific pickles
      (let [admin-pickle (first pickles)]
        (is (= "Login as admin" (:pickle/name admin-pickle)))
        (is (= 0 (:pickle/row-index admin-pickle)))
        (is (= {"role" "admin" "username" "admin@test" "password" "secret123"}
               (:pickle/row-values admin-pickle))))

      ;; Check step template-text
      (let [steps (:pickle/steps (first pickles))
            step-with-placeholder (second steps)]
        (is (= "I enter \"admin@test\" and \"secret123\"" (:step/text step-with-placeholder)))
        (is (= "I enter \"<username>\" and \"<password>\"" (:step/template-text step-with-placeholder)))))))
