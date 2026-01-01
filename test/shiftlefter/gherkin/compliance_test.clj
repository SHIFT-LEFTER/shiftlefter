(ns shiftlefter.gherkin.compliance-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [shiftlefter.gherkin.compliance :as compliance]
            [shiftlefter.gherkin.location :as loc]
            [shiftlefter.gherkin.parser :as parser]
            [shiftlefter.gherkin.tokens :as tokens]
            [cheshire.core :as json]))

(deftest loc->json-test
  (is (= {:line 1 :column 3} (compliance/loc->json (loc/->Location 1 3)))))

(deftest step->json-test
  (let [step {:keyword "Given" :text "the minimalism" :location (loc/->Location 4 5)}]
    (is (= {:id "0" :keyword "Given " :keywordType "Context" :location {:line 4 :column 5} :text "the minimalism"}
           (compliance/step->json step)))))

(deftest scenario->json-test
  (let [scenario {:name "minimalistic" :location (loc/->Location 3 3) :steps []}]
    (is (= {:id "1" :keyword "Scenario" :location {:line 3 :column 3} :name "minimalistic" :description "" :tags [] :steps [] :examples []}
           (compliance/scenario->json scenario)))))

(deftest feature->json-test
  (let [feature {:name "Minimal" :location (loc/->Location 1 1) :children []}]
    (is (= {:keyword "Feature" :location {:line 1 :column 1} :name "Minimal" :description "" :tags [] :language "en" :children []}
           (compliance/feature->json feature)))))

(deftest tokens->ndjson-multi-tag-test
  ;; New format: tags on one line with column positions, calculated from raw
  (let [tokens [(tokens/->Token :tag-line {:tags ["slow" "ui"] :positions [3 9]} (loc/->Location 2 3) "  " 0 "  @slow @ui\n" nil)
                (tokens/->Token :eof nil (loc/->Location 3 0) "" 1 "" nil)]]
    (is (= "(2:3)TagLine://3:@slow,9:@ui\nEOF\n"
           (compliance/tokens->ndjson tokens)))))

(deftest tokens->ndjson-blank-in-description-is-other
  (testing "Blank lines within description areas should be Other, not Empty"
    (let [tokens [(tokens/->Token :feature-line "Test" (loc/->Location 1 1) "" 0 "Feature: Test\n" "Feature")
                  (tokens/->Token :unknown-line "Description line" (loc/->Location 2 1) "" 1 "Description line\n" nil)
                  (tokens/->Token :blank nil (loc/->Location 3 1) "" 2 "\n" nil)  ; blank in description
                  (tokens/->Token :scenario-line "Foo" (loc/->Location 4 1) "" 3 "Scenario: Foo\n" "Scenario")
                  (tokens/->Token :eof nil (loc/->Location 5 0) "" 4 "" nil)]
          result (compliance/tokens->ndjson tokens)]
      (is (str/includes? result "(3:1)Other://")
          "Blank in description should be Other"))))

(deftest tokens->ndjson-blank-before-description-is-empty
  (testing "Blank lines before description text should be Empty"
    (let [tokens [(tokens/->Token :feature-line "Test" (loc/->Location 1 1) "" 0 "Feature: Test\n" "Feature")
                  (tokens/->Token :blank nil (loc/->Location 2 1) "" 1 "\n" nil)  ; blank before description
                  (tokens/->Token :scenario-line "Foo" (loc/->Location 3 1) "" 2 "Scenario: Foo\n" "Scenario")
                  (tokens/->Token :eof nil (loc/->Location 4 0) "" 3 "" nil)]
          result (compliance/tokens->ndjson tokens)]
      (is (str/includes? result "(2:1)Empty://")
          "Blank before description should be Empty"))))

(deftest tokens->ndjson-comment-enables-description-mode
  (testing "Comments enable description mode, so following blanks are Other"
    (let [tokens [(tokens/->Token :scenario-line "Test" (loc/->Location 1 1) "" 0 "Scenario: Test\n" "Scenario")
                  (tokens/->Token :comment "# a comment" (loc/->Location 2 1) "" 1 "# a comment\n" nil)
                  (tokens/->Token :blank nil (loc/->Location 3 1) "" 2 "\n" nil)  ; blank after comment
                  (tokens/->Token :step-line {:keyword :given :text "x"} (loc/->Location 4 1) "" 3 "Given x\n" "Given ")
                  (tokens/->Token :eof nil (loc/->Location 5 0) "" 4 "" nil)]
          result (compliance/tokens->ndjson tokens)]
      (is (str/includes? result "(3:1)Other://")
          "Blank after comment should be Other"))))

;; (deftest
;;   (let [report (compliance/run-compliance "compliance/testdata")]
;;     (is (map? report))
;;     (is (contains? report :good))
;;     (is (contains? report :bad))
;;     (is (contains? (:good report) :total))
;;     (is (contains? (:good report) :passes))
;;     (is (contains? (:good report) :fails))
;;     (is (contains? (:bad report) :total))
;;     (is (contains? (:bad report) :passes))
;;     (is (contains? (:bad report) :fails))
;;     (is (vector? (:fails (:good report))))
;;     (is (vector? (:fails (:bad report))))
;;     (is (every? string? (:fails (:good report))))
;;     (is (every? string? (:fails (:bad report))))))

;; -----------------------------------------------------------------------------
;; Snapshot Tests - Known-good AST → Expected JSON
;;
;; These tests verify end-to-end projection from a constructed AST to the
;; expected Cucumber JSON format. They serve as regression tests for the
;; compliance projection layer.
;; -----------------------------------------------------------------------------

(deftest snapshot-minimal-feature
  (testing "Minimal feature AST projects to expected JSON structure"
    (let [;; Construct a minimal Feature AST directly (bypassing parser)
          feature (parser/->Feature
                   :feature
                   "Minimal"
                   ""
                   []
                   [(parser/->Scenario
                     :scenario
                     "minimalistic"
                     ""  ;; description
                     []
                     [(parser/->Step :step "Given" "Given " "the minimalism" nil
                                     (loc/->Location 4 5) "Given the minimalism" "    " nil)]
                     nil
                     (loc/->Location 3 3)
                     "Scenario: minimalistic"
                     "  "
                     nil)]
                   (loc/->Location 1 1)
                   "Feature: Minimal"
                   ""
                   nil)
          result (compliance/feature->json feature)
          ;; Parse the result to compare structure
          expected {:keyword "Feature"
                    :location {:line 1 :column 1}
                    :name "Minimal"
                    :description ""
                    :tags []
                    :language "en"
                    :children [{:scenario {:id "1"
                                           :keyword "Scenario"
                                           :location {:line 3 :column 3}
                                           :name "minimalistic"
                                           :description ""
                                           :tags []
                                           :steps [{:id "0"
                                                    :keyword "Given "
                                                    :keywordType "Context"
                                                    :location {:line 4 :column 5}
                                                    :text "the minimalism"}]
                                           :examples []}}]}]
      (is (= expected result)))))

(deftest snapshot-feature-with-multiple-steps
  (testing "Feature with multiple steps projects correctly"
    (let [feature (parser/->Feature
                   :feature
                   "Login"
                   ""
                   []
                   [(parser/->Scenario
                     :scenario
                     "User logs in"
                     ""  ;; description
                     []
                     [(parser/->Step :step "Given" "Given " "I am on the login page" nil
                                     (loc/->Location 3 5) "Given I am on the login page" "    " nil)
                      (parser/->Step :step "When" "When " "I enter credentials" nil
                                     (loc/->Location 4 5) "When I enter credentials" "    " nil)
                      (parser/->Step :step "Then" "Then " "I see dashboard" nil
                                     (loc/->Location 5 5) "Then I see dashboard" "    " nil)]
                     nil
                     (loc/->Location 2 3)
                     "Scenario: User logs in"
                     "  "
                     nil)]
                   (loc/->Location 1 1)
                   "Feature: Login"
                   ""
                   nil)
          result (compliance/feature->json feature)]
      ;; Check structure
      (is (= "Feature" (:keyword result)))
      (is (= "Login" (:name result)))
      (is (= 1 (count (:children result))))
      (let [scenario (:scenario (first (:children result)))]
        (is (= "User logs in" (:name scenario)))
        (is (= 3 (count (:steps scenario))))
        ;; Verify step keyword types
        (is (= ["Context" "Action" "Outcome"]
               (mapv :keywordType (:steps scenario))))))))

(deftest snapshot-ast->ndjson-structure
  (testing "ast->ndjson produces valid JSON with gherkinDocument wrapper"
    (let [feature (parser/->Feature
                   :feature
                   "Test"
                   ""
                   []
                   []
                   (loc/->Location 1 1)
                   "Feature: Test"
                   ""
                   nil)
          ast [feature]
          result (compliance/ast->ndjson ast "test.feature")
          parsed (json/parse-string result true)]
      ;; Check top-level structure
      (is (contains? parsed :gherkinDocument))
      (is (contains? (:gherkinDocument parsed) :uri))
      (is (= "test.feature" (:uri (:gherkinDocument parsed))))
      (is (contains? (:gherkinDocument parsed) :feature))
      (is (= "Test" (get-in parsed [:gherkinDocument :feature :name]))))))

;; -----------------------------------------------------------------------------
;; Pickle Projection Tests
;;
;; These tests verify the pickle -> Cucumber JSON projection, which transforms
;; our internal pickle format to Cucumber's expected compliance format.
;; -----------------------------------------------------------------------------

(deftest pickle-projection-minimal
  (testing "Minimal pickle projects to Cucumber format with correct structure"
    (let [;; Create AST for ID mapping
          feature (parser/->Feature
                   :feature
                   "Minimal"
                   ""
                   []
                   [(parser/->Scenario
                     :scenario
                     "minimalistic"
                     ""  ;; description
                     []
                     [(parser/->Step :step "Given" "Given " "the minimalism" nil
                                     (loc/->Location 4 5) "Given the minimalism" "    " nil)]
                     nil
                     (loc/->Location 3 3)
                     "Scenario: minimalistic"
                     "  "
                     nil)]
                   (loc/->Location 1 1)
                   "Feature: Minimal"
                   ""
                   nil)
          ast [feature]
          uri "../testdata/good/minimal.feature"
          ;; Get ID mapping from AST projection
          ast-result (compliance/ast->ndjson-with-ids ast uri)
          ;; Create a pickle matching the scenario
          pickle {:pickle/id (java.util.UUID/randomUUID)
                  :pickle/name "minimalistic"
                  :pickle/source-file "minimal.feature"
                  :pickle/location {:line 3 :column 3}
                  :pickle/tags []
                  :pickle/steps [{:step/id (java.util.UUID/randomUUID)
                                  :step/text "the minimalism"
                                  :step/keyword "Given"
                                  :step/location {:line 4 :column 5}
                                  :step/source nil
                                  :step/arguments []}]}
          ;; Project to NDJSON
          result (compliance/pickles->ndjson-with-ids [pickle] (:id-map ast-result) (:next-id ast-result) uri ast)
          parsed (json/parse-string result true)]
      ;; Check structure (no background in this feature, so 1 step expected)
      (is (contains? parsed :pickle))
      (let [p (:pickle parsed)]
        (is (= "minimalistic" (:name p)))
        (is (= uri (:uri p)))
        (is (= "en" (:language p)))
        (is (= ["1"] (:astNodeIds p)) "Pickle should reference scenario AST ID")
        (is (= {:line 3 :column 3} (:location p)))
        (is (= 1 (count (:steps p))))
        (let [step (first (:steps p))]
          (is (= "the minimalism" (:text step)))
          (is (= "Context" (:type step)))
          (is (= ["0"] (:astNodeIds step)) "Step should reference AST step ID"))))))

(deftest pickle-projection-id-assignment
  (testing "Pickle IDs continue from AST IDs"
    (let [feature (parser/->Feature
                   :feature
                   "Test"
                   ""
                   []
                   [(parser/->Scenario
                     :scenario
                     "test"
                     ""  ;; description
                     []
                     [(parser/->Step :step "Given" "Given " "a step" nil
                                     (loc/->Location 3 5) "Given a step" "    " nil)]
                     nil
                     (loc/->Location 2 3)
                     "Scenario: test"
                     "  "
                     nil)]
                   (loc/->Location 1 1)
                   "Feature: Test"
                   ""
                   nil)
          ast [feature]
          uri "test.feature"
          ast-result (compliance/ast->ndjson-with-ids ast uri)
          pickle {:pickle/id (java.util.UUID/randomUUID)
                  :pickle/name "test"
                  :pickle/location {:line 2 :column 3}
                  :pickle/tags []
                  :pickle/steps [{:step/id (java.util.UUID/randomUUID)
                                  :step/text "a step"
                                  :step/keyword "Given"
                                  :step/location {:line 3 :column 5}}]}
          result (compliance/pickles->ndjson-with-ids [pickle] (:id-map ast-result) (:next-id ast-result) uri ast)
          parsed (json/parse-string result true)]
      ;; AST IDs: step=0, scenario=1, so next-id=2
      ;; Pickle step should get id=2, pickle should get id=3
      (is (= 2 (:next-id ast-result)) "AST should assign 2 IDs (step + scenario)")
      (let [p (:pickle parsed)]
        (is (= "3" (:id p)) "Pickle ID should continue from AST (step=2, pickle=3)")
        (is (= "2" (:id (first (:steps p)))) "Pickle step ID should be next after AST")))))

(deftest pickle-projection-keyword-types
  (testing "Step keywords are converted to proper types"
    (let [feature (parser/->Feature
                   :feature
                   "Test"
                   ""
                   []
                   [(parser/->Scenario
                     :scenario
                     "test"
                     ""  ;; description
                     []
                     [(parser/->Step :step "Given" "Given " "context" nil (loc/->Location 3 5) "" "" nil)
                      (parser/->Step :step "When" "When " "action" nil (loc/->Location 4 5) "" "" nil)
                      (parser/->Step :step "Then" "Then " "outcome" nil (loc/->Location 5 5) "" "" nil)
                      (parser/->Step :step "And" "And " "conjunction" nil (loc/->Location 6 5) "" "" nil)]
                     nil
                     (loc/->Location 2 3)
                     "Scenario: test"
                     "  "
                     nil)]
                   (loc/->Location 1 1)
                   "Feature: Test"
                   ""
                   nil)
          ast [feature]
          ast-result (compliance/ast->ndjson-with-ids ast "test.feature")
          pickles [{:pickle/name "test"
                    :pickle/location {:line 2 :column 3}
                    :pickle/tags []
                    :pickle/steps [{:step/text "context" :step/keyword "Given" :step/location {:line 3 :column 5}}
                                   {:step/text "action" :step/keyword "When" :step/location {:line 4 :column 5}}
                                   {:step/text "outcome" :step/keyword "Then" :step/location {:line 5 :column 5}}
                                   {:step/text "conjunction" :step/keyword "And" :step/location {:line 6 :column 5}}]}]
          result (compliance/pickles->ndjson-with-ids pickles (:id-map ast-result) (:next-id ast-result) "test.feature" ast)
          parsed (json/parse-string result true)
          types (mapv :type (:steps (:pickle parsed)))]
      ;; Note: And/But inherit type from previous step in pickles
      ;; "And" after "Then" → "Outcome" (inherits from Then)
      (is (= ["Context" "Action" "Outcome" "Outcome"] types)))))

;; -----------------------------------------------------------------------------
;; Background Projection Tests
;; Note: Background injection now happens in the pickler. These tests verify
;; that the compliance layer correctly projects pickles that already have
;; background steps included (with :step/origin metadata).
;; -----------------------------------------------------------------------------

(deftest pickle-projection-background-merge
  (testing "Pickles with background steps are correctly projected"
    (let [;; Feature with Background + Scenario
          feature (parser/->Feature
                   :feature
                   "Background Test"
                   ""
                   []
                   [(parser/->Background
                     :background
                     "setup"
                     ""
                     [(parser/->Step :step "Given" "Given " "bg step" nil (loc/->Location 4 5) "Given bg step" "    " nil)]
                     []
                     (loc/->Location 3 3)
                     "Background: setup"
                     "  "
                     nil)
                    (parser/->Scenario
                     :scenario
                     "test scenario"
                     ""
                     []
                     [(parser/->Step :step "When" "When " "scenario step" nil (loc/->Location 7 5) "When scenario step" "    " nil)]
                     nil
                     (loc/->Location 6 3)
                     "Scenario: test scenario"
                     "  "
                     nil)]
                   (loc/->Location 1 1)
                   "Feature: Background Test"
                   ""
                   nil)
          ast [feature]
          uri "bg-test.feature"
          ast-result (compliance/ast->ndjson-with-ids ast uri)
          ;; Create pickle WITH background steps already included (as pickler now does)
          pickle {:pickle/id (java.util.UUID/randomUUID)
                  :pickle/name "test scenario"
                  :pickle/location {:line 6 :column 3}
                  :pickle/tags []
                  :pickle/steps [{:step/text "bg step"
                                  :step/keyword "Given"
                                  :step/location {:line 4 :column 5}
                                  :step/origin :feature-background}
                                 {:step/text "scenario step"
                                  :step/keyword "When"
                                  :step/location {:line 7 :column 5}
                                  :step/origin :scenario}]}
          result (compliance/pickles->ndjson-with-ids [pickle] (:id-map ast-result) (:next-id ast-result) uri ast)
          parsed (json/parse-string result true)
          p (:pickle parsed)]
      ;; Should have 2 steps: bg step + scenario step
      (is (= 2 (count (:steps p))) "Pickle should have background + scenario steps")
      (is (= "bg step" (:text (first (:steps p)))) "First step should be from background")
      (is (= "scenario step" (:text (second (:steps p)))) "Second step should be from scenario")
      ;; Background step should reference background step AST ID
      (is (= ["0"] (:astNodeIds (first (:steps p)))) "Background step should reference AST ID"))))

(deftest pickle-projection-rule-background
  (testing "Pickles with rule background steps are correctly projected"
    (let [;; Feature with Rule containing Background + Scenario
          feature (parser/->Feature
                   :feature
                   "Rule Background Test"
                   ""
                   []
                   [(parser/->Rule
                     :rule
                     "My Rule"
                     ""
                     []
                     [(parser/->Background
                       :background
                       "rule setup"
                       ""
                       [(parser/->Step :step "Given" "Given " "rule bg step" nil (loc/->Location 6 7) "Given rule bg step" "      " nil)]
                       []
                       (loc/->Location 5 5)
                       "Background: rule setup"
                       "    "
                       nil)
                      (parser/->Scenario
                       :scenario
                       "rule scenario"
                       ""
                       []
                       [(parser/->Step :step "When" "When " "rule scenario step" nil (loc/->Location 9 7) "When rule scenario step" "      " nil)]
                       nil
                       (loc/->Location 8 5)
                       "Scenario: rule scenario"
                       "    "
                       nil)]
                     (loc/->Location 3 3)
                     "Rule: My Rule"
                     "  "
                     nil)]
                   (loc/->Location 1 1)
                   "Feature: Rule Background Test"
                   ""
                   nil)
          ast [feature]
          uri "rule-bg-test.feature"
          ast-result (compliance/ast->ndjson-with-ids ast uri)
          ;; Create pickle WITH background steps already included (as pickler now does)
          pickle {:pickle/id (java.util.UUID/randomUUID)
                  :pickle/name "rule scenario"
                  :pickle/location {:line 8 :column 5}
                  :pickle/tags []
                  :pickle/steps [{:step/text "rule bg step"
                                  :step/keyword "Given"
                                  :step/location {:line 6 :column 7}
                                  :step/origin :rule-background}
                                 {:step/text "rule scenario step"
                                  :step/keyword "When"
                                  :step/location {:line 9 :column 7}
                                  :step/origin :scenario}]}
          result (compliance/pickles->ndjson-with-ids [pickle] (:id-map ast-result) (:next-id ast-result) uri ast)
          parsed (json/parse-string result true)
          p (:pickle parsed)]
      ;; Should have 2 steps: rule bg step + scenario step
      (is (= 2 (count (:steps p))) "Pickle should have rule background + scenario steps")
      (is (= "rule bg step" (:text (first (:steps p)))) "First step should be from rule background")
      (is (= "rule scenario step" (:text (second (:steps p)))) "Second step should be from scenario"))))

(deftest pickle-projection-no-background
  (testing "Scenarios without background have only their own steps"
    (let [feature (parser/->Feature
                   :feature
                   "No Background"
                   ""
                   []
                   [(parser/->Scenario
                     :scenario
                     "plain scenario"
                     ""
                     []
                     [(parser/->Step :step "Given" "Given " "only step" nil (loc/->Location 4 5) "Given only step" "    " nil)]
                     nil
                     (loc/->Location 3 3)
                     "Scenario: plain scenario"
                     "  "
                     nil)]
                   (loc/->Location 1 1)
                   "Feature: No Background"
                   ""
                   nil)
          ast [feature]
          uri "no-bg.feature"
          ast-result (compliance/ast->ndjson-with-ids ast uri)
          pickle {:pickle/id (java.util.UUID/randomUUID)
                  :pickle/name "plain scenario"
                  :pickle/location {:line 3 :column 3}
                  :pickle/tags []
                  :pickle/steps [{:step/text "only step"
                                  :step/keyword "Given"
                                  :step/location {:line 4 :column 5}}]}
          result (compliance/pickles->ndjson-with-ids [pickle] (:id-map ast-result) (:next-id ast-result) uri ast)
          parsed (json/parse-string result true)
          p (:pickle parsed)]
      ;; Should have only 1 step
      (is (= 1 (count (:steps p))) "Pickle should have only scenario step")
      (is (= "only step" (:text (first (:steps p))))))))