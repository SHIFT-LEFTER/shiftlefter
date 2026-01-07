(ns shiftlefter.stepengine.macros-integration-test
  "Integration tests for Phase 2 macro functionality.

   Tests the full macro pipeline:
   - Happy path expansion
   - All typed errors
   - Macros-off literal preservation

   These tests exercise the compile-suite pipeline end-to-end."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [shiftlefter.gherkin.api :as api]
            [shiftlefter.stepengine.compile :as compile]
            [shiftlefter.stepengine.exec :as exec]
            [shiftlefter.stepengine.registry :as registry]
            [shiftlefter.runner.step-loader :as step-loader]))

;; -----------------------------------------------------------------------------
;; Test Fixtures
;; -----------------------------------------------------------------------------

(defn load-steps-fixture [f]
  (step-loader/load-step-paths! ["test/fixtures/steps"])
  (f)
  (registry/clear-registry!))

(use-fixtures :each load-steps-fixture)

(defn- parse-feature [content]
  (let [{:keys [ast errors]} (api/parse-string content)]
    (when (seq errors)
      (throw (ex-info "Parse failed" {:errors errors})))
    (api/pickles ast "test.feature")))

(defn- compile-with-macros [pickles registry-paths]
  (let [runner-cfg {:macros {:enabled? true
                             :registry-paths registry-paths}}
        stepdefs (registry/all-stepdefs)]
    (compile/compile-suite runner-cfg (:pickles pickles) stepdefs)))

(defn- compile-without-macros [pickles]
  (let [runner-cfg {:macros {:enabled? false}}
        stepdefs (registry/all-stepdefs)]
    (compile/compile-suite runner-cfg (:pickles pickles) stepdefs)))

;; -----------------------------------------------------------------------------
;; Happy Path Tests
;; -----------------------------------------------------------------------------

(deftest happy-path-macro-expansion
  (testing "Macro expands to steps and executes successfully"
    (let [pickles (parse-feature "Feature: Test
  Scenario: Login
    Given login as alice +
    Then I should see the dashboard")
          compiled (compile-with-macros pickles ["test/fixtures/macros/auth.ini"])]

      (testing "compilation succeeds"
        (is (:runnable? compiled))
        (is (= 1 (count (:plans compiled)))))

      (testing "expansion produces wrapper + children"
        (let [steps (-> compiled :plans first :plan/steps)]
          ;; Original 2 steps expand to: wrapper + 5 auth steps + dashboard step = 7
          (is (= 7 (count steps)))
          ;; First step is synthetic wrapper
          (is (-> steps first :step :step/synthetic?))
          (is (= :call (-> steps first :step :step/macro :role)))
          ;; Following steps are expanded
          (is (= :expanded (-> steps second :step :step/macro :role)))))

      (testing "execution passes"
        (let [result (exec/execute-suite (:plans compiled) {})]
          (is (= :passed (:status result)))
          (is (= 1 (:passed (:counts result)))))))))

(deftest happy-path-pickle-macros-summary
  (testing ":pickle/macros contains macro summary"
    (let [pickles (parse-feature "Feature: Test
  Scenario: With macro
    Given login as alice +
    Then I should see the dashboard")
          compiled (compile-with-macros pickles ["test/fixtures/macros/auth.ini"])
          plan (first (:plans compiled))
          pickle (:plan/pickle plan)]

      (is (seq (:pickle/macros pickle)) "pickle should have :pickle/macros")
      (is (= 1 (count (:pickle/macros pickle))) "should have 1 macro")
      (is (= "login as alice" (-> pickle :pickle/macros first :key)))
      (is (map? (-> pickle :pickle/macros first :definition)) "should have definition"))))

(deftest happy-path-deterministic-ordering
  (testing "Step order is deterministic after expansion"
    (let [pickles (parse-feature "Feature: Test
  Scenario: Order test
    Given login as alice +
    Then I should see the dashboard")
          compiled1 (compile-with-macros pickles ["test/fixtures/macros/auth.ini"])
          compiled2 (compile-with-macros pickles ["test/fixtures/macros/auth.ini"])
          steps1 (map #(-> % :step :step/text) (-> compiled1 :plans first :plan/steps))
          steps2 (map #(-> % :step :step/text) (-> compiled2 :plans first :plan/steps))]
      (is (= steps1 steps2) "Step ordering should be deterministic"))))

;; -----------------------------------------------------------------------------
;; Error Cases - Typed Errors
;; -----------------------------------------------------------------------------

(deftest error-undefined-macro
  (testing "Undefined macro produces :macro/undefined error"
    (let [pickles (parse-feature "Feature: Test
  Scenario: Undefined
    Given nonexistent macro +")
          compiled (compile-with-macros pickles ["test/fixtures/macros/auth.ini"])]

      (is (not (:runnable? compiled)))
      (let [errors (-> compiled :diagnostics :macro-errors)]
        (is (= 1 (count errors)))
        (is (= :macro/undefined (-> errors first :type)))
        (is (= "nonexistent macro" (-> errors first :macro-key)))
        (is (some? (-> errors first :location)) "should have call-site location")))))

(deftest error-duplicate-macro-keys
  (testing "Duplicate macro keys produce :macro/duplicate-key error"
    (let [pickles (parse-feature "Feature: Test
  Scenario: Dupe
    Given login as alice +")
          compiled (compile-with-macros pickles ["test/fixtures/macros/dupe.ini"])]

      (is (not (:runnable? compiled)))
      (let [errors (-> compiled :diagnostics :macro-errors)]
        (is (= 1 (count errors)))
        (is (= :macro/duplicate-key (-> errors first :type)))
        (is (some? (-> errors first :first-definition)) "should have first definition")
        (is (some? (-> errors first :second-definition)) "should have second definition")))))

(deftest error-empty-expansion
  (testing "Empty macro produces parsing error"
    (let [pickles (parse-feature "Feature: Test
  Scenario: Empty
    Given do nothing +")
          compiled (compile-with-macros pickles ["test/fixtures/macros/empty.ini"])]

      ;; Empty INI produces :macro/missing-steps at parse time
      (is (not (:runnable? compiled)))
      (let [errors (-> compiled :diagnostics :macro-errors)]
        (is (seq errors))
        ;; Either :macro/missing-steps (parse) or :macro/undefined (expansion)
        (is (#{:macro/missing-steps :macro/undefined} (-> errors first :type)))))))

(deftest error-recursion-disallowed
  (testing "Nested macro call produces :macro/recursion-disallowed error"
    (let [pickles (parse-feature "Feature: Test
  Scenario: Recursive
    Given recursive call +")
          compiled (compile-with-macros pickles ["test/fixtures/macros/recursion.ini"])]

      (is (not (:runnable? compiled)))
      (let [errors (-> compiled :diagnostics :macro-errors)]
        (is (= 1 (count errors)))
        (is (= :macro/recursion-disallowed (-> errors first :type)))))))

(deftest error-macro-with-docstring
  (testing "Macro call with docstring produces :macro/argument-not-supported error"
    (let [pickles (parse-feature "Feature: Test
  Scenario: DocString
    Given login as alice +
      \"\"\"
      some text
      \"\"\"")
          compiled (compile-with-macros pickles ["test/fixtures/macros/auth.ini"])]

      (is (not (:runnable? compiled)))
      (let [errors (-> compiled :diagnostics :macro-errors)]
        (is (= 1 (count errors)))
        (is (= :macro/argument-not-supported (-> errors first :type)))))))

(deftest error-macro-with-datatable
  (testing "Macro call with data table produces :macro/argument-not-supported error"
    (let [pickles (parse-feature "Feature: Test
  Scenario: DataTable
    Given login as alice +
      | col1 | col2 |
      | a    | b    |")
          compiled (compile-with-macros pickles ["test/fixtures/macros/auth.ini"])]

      (is (not (:runnable? compiled)))
      (let [errors (-> compiled :diagnostics :macro-errors)]
        (is (= 1 (count errors)))
        (is (= :macro/argument-not-supported (-> errors first :type)))))))

(deftest error-macro-in-scenario-outline
  (testing "Macro call in Scenario Outline produces :macro/scenario-outline-not-supported error"
    (let [pickles (parse-feature "Feature: Test
  Scenario Outline: Outline
    Given login as <user> +

    Examples:
      | user  |
      | alice |")
          compiled (compile-with-macros pickles ["test/fixtures/macros/auth.ini"])]

      (is (not (:runnable? compiled)))
      (let [errors (-> compiled :diagnostics :macro-errors)]
        (is (= 1 (count errors)))
        (is (= :macro/scenario-outline-not-supported (-> errors first :type)))))))

;; -----------------------------------------------------------------------------
;; Macros Disabled - Literal Preservation
;; -----------------------------------------------------------------------------

(deftest macros-disabled-literal-plus
  (testing "When macros disabled, ' +' is literal step text"
    (let [pickles (parse-feature "Feature: Test
  Scenario: Literal
    Given login as alice +
    Then I should see the dashboard")
          compiled (compile-without-macros pickles)]

      (testing "compilation succeeds (steps bound to literal patterns)"
        (is (:runnable? compiled)))

      (testing "no expansion occurs"
        (let [steps (-> compiled :plans first :plan/steps)]
          ;; Original 2 steps remain 2 steps (no expansion)
          (is (= 2 (count steps)))
          ;; First step text includes the " +"
          (is (= "login as alice +" (-> steps first :step :step/text)))
          ;; No synthetic wrapper
          (is (not (-> steps first :step :step/synthetic?)))))

      (testing "execution passes with literal step"
        (let [result (exec/execute-suite (:plans compiled) {})]
          (is (= :passed (:status result))))))))

;; -----------------------------------------------------------------------------
;; Provenance Tests
;; -----------------------------------------------------------------------------

(deftest provenance-call-site-and-definition
  (testing "Expanded steps have call-site and definition provenance"
    (let [pickles (parse-feature "Feature: Test
  Scenario: Provenance
    Given login as alice +")
          compiled (compile-with-macros pickles ["test/fixtures/macros/auth.ini"])
          steps (-> compiled :plans first :plan/steps)
          expanded-step (second steps)]

      (is (= :expanded (-> expanded-step :step :step/macro :role)))

      (testing "call-site location"
        (let [call-site (-> expanded-step :step :step/macro :call-site)]
          (is (some? call-site))
          (is (number? (:line call-site)))))

      (testing "definition location"
        (let [definition (-> expanded-step :step :step/macro :definition)]
          (is (some? definition))
          (is (string? (:file definition)))))

      (testing "definition-step location"
        (let [def-step (-> expanded-step :step :step/macro :definition-step)]
          (is (some? def-step))
          (is (number? (:line def-step))))))))
