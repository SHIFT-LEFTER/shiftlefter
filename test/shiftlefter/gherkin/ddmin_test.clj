(ns shiftlefter.gherkin.ddmin-test
  "Tests for delta-debugging minimizer."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [shiftlefter.gherkin.ddmin :as ddmin]
            [shiftlefter.gherkin.api :as api]))

;; -----------------------------------------------------------------------------
;; Test fixtures
;; -----------------------------------------------------------------------------

(def feature-with-typo
  "Feature file with keyword typo (Feture instead of Feature)"
  "Feture: Test
  Scenario: S1
    Given step one
    When step two
  Scenario: S2
    Given step three
    Then step four
")

(def feature-valid
  "Valid feature file"
  "Feature: Test
  Scenario: S1
    Given step one
")

(def feature-with-many-scenarios
  "Feature with many scenarios for reduction testing"
  "Feture: Test
  Scenario: A
    Given a
  Scenario: B
    Given b
  Scenario: C
    Given c
  Scenario: D
    Given d
  Scenario: E
    Given e
")

;; -----------------------------------------------------------------------------
;; Signature tests
;; -----------------------------------------------------------------------------

(deftest make-signature-extracts-fields
  (testing "make-signature extracts phase, reason, and error type"
    (let [result {:phase :parse
                  :reason :graceful-errors
                  :details {:errors [{:type :invalid-keyword}]}}
          sig (ddmin/make-signature result)]
      (is (= :parse (:phase sig)))
      (is (= :graceful-errors (:reason sig)))
      (is (= :invalid-keyword (:error/type sig))))))

(deftest signatures-match-compares-correctly
  (testing "signatures-match? returns true for matching signatures"
    (let [sig1 {:phase :parse :reason :graceful-errors :error/type :invalid-keyword}
          sig2 {:phase :parse :reason :graceful-errors :error/type :invalid-keyword}]
      (is (ddmin/signatures-match? sig1 sig2))))

  (testing "signatures-match? returns false for different phases"
    (let [sig1 {:phase :parse :reason :graceful-errors :error/type :invalid-keyword}
          sig2 {:phase :pickles :reason :graceful-errors :error/type :invalid-keyword}]
      (is (not (ddmin/signatures-match? sig1 sig2)))))

  (testing "signatures-match? returns false for different error types"
    (let [sig1 {:phase :parse :reason :graceful-errors :error/type :invalid-keyword}
          sig2 {:phase :parse :reason :graceful-errors :error/type :missing-feature}]
      (is (not (ddmin/signatures-match? sig1 sig2))))))

;; -----------------------------------------------------------------------------
;; check-failure tests
;; -----------------------------------------------------------------------------

(deftest check-failure-detects-parse-errors
  (testing "check-failure returns graceful-errors for parse failures"
    (let [result (ddmin/check-failure feature-with-typo :parse 200)]
      (is (= :ok (:status result)))
      (is (= :graceful-errors (:reason result)))
      (is (= :parse (:phase result))))))

(deftest check-failure-returns-no-failure-for-valid
  (testing "check-failure returns no-failure for valid content"
    (let [result (ddmin/check-failure feature-valid :parse 200)]
      (is (= :ok (:status result)))
      (is (= :no-failure (:reason result))))))

;; -----------------------------------------------------------------------------
;; identify-units tests
;; -----------------------------------------------------------------------------

(deftest identify-units-finds-scenarios
  (testing "identify-units finds scenario units"
    (let [content "Feature: Test
  Scenario: S1
    Given step
  Scenario: S2
    Given step
"
          {:keys [units tokens]} (ddmin/identify-units content)]
      (is (vector? tokens))
      (is (pos? (count units)))
      ;; Should have at least 2 scenarios and 2 steps
      (is (>= (count (filter #(= :scenario (:type %)) units)) 2))
      (is (>= (count (filter #(= :step (:type %)) units)) 2)))))

(deftest identify-units-handles-parse-errors
  (testing "identify-units works even with parse errors"
    (let [{:keys [units parse-failed?]} (ddmin/identify-units feature-with-typo)]
      ;; Should still find some units
      (is (vector? units)))))

;; -----------------------------------------------------------------------------
;; ddmin structured tests
;; -----------------------------------------------------------------------------

(deftest ddmin-reduces-content
  (testing "ddmin reduces content while preserving failure"
    (let [result (ddmin/ddmin feature-with-typo {:mode :parse :timeout-ms 200})]
      (is (< (count (:minimized result)) (count (:original result))))
      (is (:signatures-match? result))
      (is (pos? (:steps result)))
      (is (pos? (:removed result))))))

(deftest ddmin-preserves-failure-signature
  (testing "minimized content has same failure signature"
    (let [result (ddmin/ddmin feature-with-typo {:mode :parse :timeout-ms 200})
          original-check (ddmin/check-failure feature-with-typo :parse 200)
          minimized-check (ddmin/check-failure (:minimized result) :parse 200)]
      (is (ddmin/signatures-match?
           (ddmin/make-signature original-check)
           (ddmin/make-signature minimized-check))))))

(deftest ddmin-handles-many-scenarios
  (testing "ddmin efficiently reduces many scenarios"
    (let [result (ddmin/ddmin feature-with-many-scenarios {:mode :parse :timeout-ms 200})]
      ;; Should reduce significantly
      (is (< (:reduction-ratio result) 0.5))
      (is (:signatures-match? result)))))

;; -----------------------------------------------------------------------------
;; ddmin raw-lines tests
;; -----------------------------------------------------------------------------

(deftest ddmin-raw-lines-reduces-more
  (testing "raw-lines strategy often reduces more than structured"
    (let [structured (ddmin/ddmin feature-with-typo {:mode :parse :strategy :structured :timeout-ms 200})
          raw-lines (ddmin/ddmin feature-with-typo {:mode :parse :strategy :raw-lines :timeout-ms 200})]
      ;; Raw lines should reduce to minimal (just the typo line)
      (is (<= (count (:minimized raw-lines)) (count (:minimized structured))))
      (is (:signatures-match? raw-lines)))))

(deftest ddmin-raw-lines-produces-minimal-repro
  (testing "raw-lines produces minimal reproducible case"
    (let [result (ddmin/ddmin feature-with-typo {:mode :parse :strategy :raw-lines :timeout-ms 200})]
      ;; Should reduce to just "Feture: Test\n" or similar minimal
      (is (< (count (:minimized result)) 20))
      (is (str/includes? (:minimized result) "Feture")))))

;; -----------------------------------------------------------------------------
;; ddmin error handling tests
;; -----------------------------------------------------------------------------

(deftest ddmin-rejects-non-failing-content
  (testing "ddmin throws for content that doesn't fail"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"does not produce a failure"
                          (ddmin/ddmin feature-valid {:mode :parse :timeout-ms 200})))))

;; -----------------------------------------------------------------------------
;; ddmin-artifact tests
;; -----------------------------------------------------------------------------

(deftest ddmin-artifact-reads-and-writes
  (testing "ddmin-artifact processes fuzz artifact directory"
    (let [temp-dir (str (fs/create-temp-dir {:prefix "ddmin-test-"}))]
      ;; Create mock artifact
      (spit (str temp-dir "/case.feature") feature-with-typo)
      (spit (str temp-dir "/result.edn")
            (pr-str {:status :ok
                     :reason :graceful-errors
                     :phase :parse
                     :signature {:phase :parse
                                 :reason :graceful-errors
                                 :error/type :invalid-keyword}}))

      (let [result (ddmin/ddmin-artifact temp-dir {:timeout-ms 200})]
        ;; Should create min.feature and ddmin.edn
        (is (fs/exists? (fs/path temp-dir "min.feature")))
        (is (fs/exists? (fs/path temp-dir "ddmin.edn")))
        (is (< (count (slurp (str temp-dir "/min.feature")))
               (count feature-with-typo))))

      ;; Cleanup
      (fs/delete-tree temp-dir))))

;; -----------------------------------------------------------------------------
;; Mode inference tests
;; -----------------------------------------------------------------------------

(deftest infer-mode-selects-correct-mode
  (testing "infer-mode returns parse for parse errors"
    (let [result {:phase :parse :reason :graceful-errors}]
      (is (= :parse (ddmin/infer-mode result)))))

  (testing "infer-mode returns pickles for pickle errors"
    (let [result {:phase :pickles :reason :graceful-errors}]
      (is (= :pickles (ddmin/infer-mode result))))))
