(ns shiftlefter.gherkin.pickler-test
  (:require [clojure.test :refer [deftest is testing]]
            [shiftlefter.gherkin.pickler :as pickler]
            [shiftlefter.gherkin.lexer :as lexer]
            [shiftlefter.gherkin.parser :as parser]
            [shiftlefter.gherkin.macros :as macros]))

(def test-macros-dir "test/resources/macros/good/")

(deftest test-pickles-basic
  (testing "Generates basic flat pickles from expanded AST"
    (let [feature-str "Feature: Test Feature\n\nScenario: Test Scenario\n    Given I do something\n    When I do another\n    Then I check result"
          tokens (lexer/lex feature-str)
          pre-ast (:ast (parser/parse tokens))
          registry (macros/load-macro-registry test-macros-dir)
          expanded-ast (macros/expand-ast pre-ast registry)
          result (pickler/pickles expanded-ast {} "test.feature")
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
          (is (= "I do something" (:step/text first-step)))
          (is (nil? (:step/source first-step))))))))

 (deftest test-pickles-with-macros
   (testing "Preserves macro provenance in pickles"
     (let [feature-str "Feature: Test Feature\n\nScenario: Test Scenario\n    Given Log in as admin +\n    Then check result"
           tokens (lexer/lex feature-str)
           pre-ast (:ast (parser/parse tokens))
           registry (macros/load-macro-registry test-macros-dir)
           expanded-ast (macros/expand-ast pre-ast registry)
           result (pickler/pickles expanded-ast {} "test.feature")
           pickles (:pickles result)]
       (is (empty? (:errors result)))
       (is (seq? pickles))
       (is (= 1 (count pickles)))
       (let [pickle (first pickles)
             steps (:pickle/steps pickle)]
         (is (= "Test Scenario" (:pickle/name pickle)))
         (is (= 6 (count steps))) ; 5 expanded + 1 original
         ;; Check expanded steps have :source
         (let [first-expanded (first steps)]
           (is (= "Given" (:step/keyword first-expanded)))
           (is (= "I am on the login page" (:step/text first-expanded)))
           (is (map? (:step/source first-expanded)))
           (is (= "Log in as admin" (:macro-name (:step/source first-expanded))))
           (is (map? (:original-location (:step/source first-expanded)))))
         ;; Check other expanded steps
         (let [second-step (second steps)]
           (is (= "When" (:step/keyword second-step)))
           (is (= "I fill in \"Email\" with \"{email}\"" (:step/text second-step)))
           (is (= "Log in as admin" (:macro-name (:step/source second-step)))))
         ;; Check the last original step has no :source
         (let [last-step (last steps)]
           (is (= "Then" (:step/keyword last-step)))
           (is (= "check result" (:step/text last-step)))
           (is (nil? (:step/source last-step))))))))
 

(deftest test-pre-pickles-basic
  (testing "Generates pre-expansion pickles with macro provenance"
    (let [feature-str "Feature: Test\n  Scenario: Basic\n    Given regular step\n    Given macro +\n    Then another"
          tokens (lexer/lex feature-str)
          pre-ast (:ast (parser/parse tokens))
          registry (macros/load-macro-registry test-macros-dir)
          pickles (pickler/pre-pickles pre-ast registry "test.feature")]
      (is (= 1 (count pickles)))
      (let [p (first pickles)]
        (is (= "Basic" (:pickle/name p)))
        (is (= 3 (count (:pickle/steps p))))
        (let [steps (:pickle/steps p)
              first-step (nth steps 0)
              macro-step (nth steps 1)
              last-step (nth steps 2)]
          (is (= "regular step" (:step/text first-step)))
          (is (nil? (:step/source first-step)))
          (is (= "macro" (:step/text macro-step)))
          (is (map? (:step/source macro-step)))  ;; Has provenance
          (is (= "macro" (:macro-name (:step/source macro-step))))
          (is (instance? shiftlefter.gherkin.location.Location (:original-location (:step/source macro-step))))  ;; Non-nil location
          (is (= "another" (:step/text last-step)))
          (is (nil? (:step/source last-step))))))))

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
