(ns shiftlefter.gherkin.api-test
  "Tests for the stable public API facade."
  (:require [clojure.test :refer [deftest is testing]]
            [shiftlefter.gherkin.api :as api]))

;; -----------------------------------------------------------------------------
;; Test fixtures
;; -----------------------------------------------------------------------------

(def minimal-feature
  "Feature: Test\n  Scenario: Basic\n    Given a step\n")

(def feature-with-tags
  "@tag1 @tag2\nFeature: Tagged\n  @scenario-tag\n  Scenario: Tagged scenario\n    Given something\n")

(def feature-with-outline
  "Feature: Outline test
  Scenario Outline: Parameterized
    Given I have <count> items

  Examples:
    | count |
    | 1     |
    | 2     |
")

(def invalid-feature
  "Scenario: No feature keyword\n  Given something\n")

;; -----------------------------------------------------------------------------
;; lex-string tests
;; -----------------------------------------------------------------------------

(deftest lex-string-returns-envelope
  (testing "returns tokens and empty errors"
    (let [result (api/lex-string minimal-feature)]
      (is (vector? (:tokens result)))
      (is (pos? (count (:tokens result))))
      (is (vector? (:errors result)))
      (is (empty? (:errors result))))))

(deftest lex-string-never-nil
  (testing "returns vectors even for empty input"
    (let [result (api/lex-string "")]
      (is (vector? (:tokens result)))
      (is (vector? (:errors result))))))

;; -----------------------------------------------------------------------------
;; parse-tokens tests
;; -----------------------------------------------------------------------------

(deftest parse-tokens-returns-envelope
  (testing "returns ast and errors as vectors"
    (let [{:keys [tokens]} (api/lex-string minimal-feature)
          result (api/parse-tokens tokens)]
      (is (vector? (:ast result)))
      (is (vector? (:errors result))))))

(deftest parse-tokens-parses-valid-input
  (testing "parses valid feature correctly"
    (let [{:keys [tokens]} (api/lex-string minimal-feature)
          {:keys [ast errors]} (api/parse-tokens tokens)]
      (is (= 1 (count ast)))
      (is (empty? errors)))))

;; -----------------------------------------------------------------------------
;; parse-string tests
;; -----------------------------------------------------------------------------

(deftest parse-string-returns-full-envelope
  (testing "returns tokens, ast, and errors"
    (let [result (api/parse-string minimal-feature)]
      (is (vector? (:tokens result)))
      (is (vector? (:ast result)))
      (is (vector? (:errors result))))))

(deftest parse-string-combines-lex-and-parse
  (testing "parse-string produces same result as lex + parse"
    (let [combined (api/parse-string minimal-feature)
          {:keys [tokens]} (api/lex-string minimal-feature)
          separate (api/parse-tokens tokens)]
      (is (= (:tokens combined) tokens))
      (is (= (:ast combined) (:ast separate)))
      (is (= (:errors combined) (:errors separate))))))

;; -----------------------------------------------------------------------------
;; pickles tests
;; -----------------------------------------------------------------------------

(deftest pickles-returns-envelope
  (testing "returns pickles and errors as vectors"
    (let [{:keys [ast]} (api/parse-string minimal-feature)
          result (api/pickles ast "test.feature")]
      (is (vector? (:pickles result)))
      (is (vector? (:errors result))))))

(deftest pickles-generates-from-outline
  (testing "generates pickles for scenario outline"
    (let [{:keys [ast]} (api/parse-string feature-with-outline)
          {:keys [pickles]} (api/pickles ast "outline.feature")]
      (is (= 2 (count pickles)))))) ; 2 examples = 2 pickles

;; -----------------------------------------------------------------------------
;; print-tokens tests
;; -----------------------------------------------------------------------------

(deftest print-tokens-roundtrips-exactly
  (testing "reconstructs original string exactly"
    (let [{:keys [tokens]} (api/lex-string minimal-feature)
          reconstructed (api/print-tokens tokens)]
      (is (= minimal-feature reconstructed)))))

(deftest print-tokens-preserves-tags
  (testing "preserves tag spacing"
    (let [{:keys [tokens]} (api/lex-string feature-with-tags)
          reconstructed (api/print-tokens tokens)]
      (is (= feature-with-tags reconstructed)))))

;; -----------------------------------------------------------------------------
;; roundtrip-ok? tests
;; -----------------------------------------------------------------------------

(deftest roundtrip-ok-returns-boolean
  (testing "returns true for valid roundtrip"
    (is (true? (api/roundtrip-ok? minimal-feature))))
  (testing "returns true for feature with tags"
    (is (true? (api/roundtrip-ok? feature-with-tags)))))

;; -----------------------------------------------------------------------------
;; fmt-check tests
;; -----------------------------------------------------------------------------

(def canonical-feature
  "Feature: Test\n\n  Scenario: Basic\n    Given a step\n")

(deftest fmt-check-returns-ok-for-canonical
  (testing "returns :ok status for canonical format"
    (let [result (api/fmt-check canonical-feature)]
      (is (= :ok (:status result))))))

(deftest fmt-check-returns-error-for-needs-formatting
  (testing "returns :needs-formatting for non-canonical"
    (let [result (api/fmt-check minimal-feature)]
      (is (= :error (:status result)))
      (is (= :needs-formatting (:reason result))))))

(deftest fmt-check-returns-error-for-parse-errors
  (testing "returns parse-errors for invalid input"
    (let [result (api/fmt-check invalid-feature)]
      (is (= :error (:status result)))
      (is (= :parse-errors (:reason result)))
      (is (vector? (:details result))))))

;; -----------------------------------------------------------------------------
;; fmt-canonical tests
;; -----------------------------------------------------------------------------

(deftest fmt-canonical-returns-formatted-output
  (testing "returns formatted string on success"
    (let [result (api/fmt-canonical minimal-feature)]
      (is (= :ok (:status result)))
      (is (string? (:output result))))))

(deftest fmt-canonical-normalizes-whitespace
  (testing "normalizes indentation to 2 spaces"
    (let [messy "Feature: Test\n    Scenario: Basic\n        Given a step\n"
          result (api/fmt-canonical messy)]
      (is (= :ok (:status result)))
      (is (re-find #"^Feature:" (:output result)))
      (is (re-find #"\n  Scenario:" (:output result))))))

(deftest fmt-canonical-returns-error-for-parse-errors
  (testing "returns error for invalid input"
    (let [result (api/fmt-canonical invalid-feature)]
      (is (= :error (:status result)))
      (is (= :parse-errors (:reason result))))))

;; -----------------------------------------------------------------------------
;; Envelope contract enforcement
;; -----------------------------------------------------------------------------

(deftest envelope-never-contains-nil
  (testing "all envelope functions return vectors, never nil"
    (let [lex-result (api/lex-string "")
          parse-result (api/parse-string "")
          pickle-result (api/pickles [] "empty.feature")]
      (is (every? vector? [(:tokens lex-result)
                           (:errors lex-result)
                           (:tokens parse-result)
                           (:ast parse-result)
                           (:errors parse-result)
                           (:pickles pickle-result)
                           (:errors pickle-result)])))))
