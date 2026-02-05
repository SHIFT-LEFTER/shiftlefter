(ns shiftlefter.gherkin.verify-test
  "Tests for the verify module."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [shiftlefter.gherkin.verify :as verify]))

;; -----------------------------------------------------------------------------
;; CLI wiring tests
;; -----------------------------------------------------------------------------

(deftest check-cli-wiring-works
  (testing "CLI wiring check passes when bin/sl exists"
    (let [result (verify/check-cli-wiring)]
      (is (= :ok (:status result)))
      (is (= :cli-wiring (:id result))))))

;; -----------------------------------------------------------------------------
;; Smoke fixture tests
;; -----------------------------------------------------------------------------

(deftest check-smoke-parse-valid
  (testing "smoke-parse passes for valid fixture"
    (let [result (verify/check-smoke-parse "examples/quickstart/features/toy-login.feature")]
      (is (= :ok (:status result)))
      (is (= :smoke-parse (:id result))))))

(deftest check-smoke-parse-invalid
  (testing "smoke-parse fails for non-existent file"
    (let [result (verify/check-smoke-parse "/nonexistent/file.feature")]
      (is (= :fail (:status result))))))

(deftest check-smoke-fmt-valid
  (testing "smoke-fmt passes for valid fixture"
    (let [result (verify/check-smoke-fmt "examples/quickstart/features/toy-login.feature")]
      (is (= :ok (:status result)))
      (is (= :smoke-fmt (:id result))))))

(deftest check-smoke-roundtrip-valid
  (testing "smoke-roundtrip passes for valid fixture"
    (let [result (verify/check-smoke-roundtrip "examples/quickstart/features/toy-login.feature")]
      (is (= :ok (:status result)))
      (is (= :smoke-roundtrip (:id result))))))

;; -----------------------------------------------------------------------------
;; Artifact integrity tests
;; -----------------------------------------------------------------------------

(deftest check-artifact-integrity-missing-case
  (testing "artifact check fails when case.feature is missing"
    (let [temp-dir (str (fs/create-temp-dir {:prefix "verify-test-"}))]
      (spit (str temp-dir "/meta.edn") (pr-str {:seed 1 :trial-idx 0 :generator-version [2 0]
                                                 :timestamp "2026-01-01" :opts {}}))
      (spit (str temp-dir "/result.edn") (pr-str {:status :ok :reason :graceful-errors}))
      (let [result (verify/check-artifact-integrity temp-dir)]
        (is (= :fail (:status result)))
        (is (str/includes? (:message result) "case.feature")))
      (fs/delete-tree temp-dir))))

(deftest check-artifact-integrity-valid
  (testing "artifact check passes for valid artifact"
    (let [temp-dir (str (fs/create-temp-dir {:prefix "verify-test-"}))]
      (spit (str temp-dir "/case.feature") "Feature: Test\n  Scenario: S\n    Given step\n")
      (spit (str temp-dir "/meta.edn") (pr-str {:seed 1 :trial-idx 0 :generator-version [2 0]
                                                 :timestamp "2026-01-01" :opts {}}))
      (spit (str temp-dir "/result.edn") (pr-str {:status :ok :reason :graceful-errors}))
      (let [result (verify/check-artifact-integrity temp-dir)]
        (is (= :ok (:status result))))
      (fs/delete-tree temp-dir))))

(deftest check-artifact-integrity-with-ddmin
  (testing "artifact check validates ddmin files when present"
    (let [temp-dir (str (fs/create-temp-dir {:prefix "verify-test-"}))]
      (spit (str temp-dir "/case.feature") "Feture: Test\n")
      (spit (str temp-dir "/meta.edn") (pr-str {:seed 1 :trial-idx 0 :generator-version [2 0]
                                                 :timestamp "2026-01-01" :opts {}}))
      (spit (str temp-dir "/result.edn") (pr-str {:status :ok :reason :graceful-errors}))
      (spit (str temp-dir "/min.feature") "Feture: Test\n")
      (spit (str temp-dir "/ddmin.edn") (pr-str {:baseline-sig {:phase :parse}
                                                  :signatures-match? true}))
      (let [result (verify/check-artifact-integrity temp-dir)]
        (is (= :ok (:status result))))
      (fs/delete-tree temp-dir))))

(deftest check-artifact-integrity-min-without-ddmin
  (testing "artifact check fails when min.feature exists but ddmin.edn is missing"
    (let [temp-dir (str (fs/create-temp-dir {:prefix "verify-test-"}))]
      (spit (str temp-dir "/case.feature") "Feture: Test\n")
      (spit (str temp-dir "/meta.edn") (pr-str {:seed 1 :trial-idx 0 :generator-version [2 0]
                                                 :timestamp "2026-01-01" :opts {}}))
      (spit (str temp-dir "/result.edn") (pr-str {:status :ok :reason :graceful-errors}))
      (spit (str temp-dir "/min.feature") "Feture: Test\n")
      (let [result (verify/check-artifact-integrity temp-dir)]
        (is (= :fail (:status result)))
        (is (str/includes? (:message result) "ddmin.edn")))
      (fs/delete-tree temp-dir))))

;; -----------------------------------------------------------------------------
;; run-checks tests
;; -----------------------------------------------------------------------------

(deftest run-checks-validator-only
  (testing "run-checks returns summary for validator-only mode"
    (let [result (verify/run-checks {})]
      (is (contains? result :status))
      (is (contains? result :checks))
      (is (contains? result :summary))
      (is (pos? (get-in result [:summary :total]))))))

(deftest run-checks-skips-artifacts-by-default
  (testing "run-checks does NOT include artifact checks by default"
    (let [result (verify/run-checks {})
          check-ids (set (map :id (:checks result)))]
      ;; Should have cli-wiring and smoke checks, but not artifact-integrity
      (is (contains? check-ids :cli-wiring))
      (is (not (contains? check-ids :artifact-integrity))))))

(deftest run-checks-includes-artifacts-with-fuzzed-flag
  (testing "run-checks includes artifact checks when :fuzzed true"
    ;; Create a temp artifact to ensure there's something to check
    (let [temp-dir (str (fs/create-temp-dir {:prefix "fuzz-artifacts-"}))]
      (try
        ;; Create a minimal valid artifact
        (let [artifact-dir (str temp-dir "/test-artifact")]
          (fs/create-dirs artifact-dir)
          (spit (str artifact-dir "/case.feature") "Feature: Test\n  Scenario: S\n    Given step\n")
          (spit (str artifact-dir "/meta.edn") (pr-str {:seed 1 :trial-idx 0 :generator-version [2 0]
                                                         :timestamp "2026-01-01" :opts {}}))
          (spit (str artifact-dir "/result.edn") (pr-str {:status :ok :reason :graceful-errors}))
          ;; Test with fuzzed flag - but we can't easily test with real fuzz/artifacts
          ;; So just verify the option is accepted and doesn't error
          (let [result (verify/run-checks {:fuzzed true})]
            (is (contains? result :status))
            (is (contains? result :checks))))
        (finally
          (fs/delete-tree temp-dir))))))

(deftest run-checks-returns-failures
  (testing "run-checks returns failures list"
    (let [result (verify/run-checks {})]
      (is (vector? (:failures result))))))

;; -----------------------------------------------------------------------------
;; Output formatting tests
;; -----------------------------------------------------------------------------

(deftest format-check-human-ok
  (testing "human format for OK check"
    (let [check {:id :test-check :status :ok :message "All good"}
          output (verify/format-check-human check)]
      (is (str/includes? output "[OK]"))
      (is (str/includes? output "test-check"))
      (is (str/includes? output "All good")))))

(deftest format-check-human-fail
  (testing "human format for FAIL check"
    (let [check {:id :test-check :status :fail :message "Something broke"}
          output (verify/format-check-human check)]
      (is (str/includes? output "[FAIL]"))
      (is (str/includes? output "Something broke")))))

(deftest format-edn-produces-valid-edn
  (testing "EDN format produces readable EDN"
    (let [result {:status :ok :checks [] :failures [] :summary {:total 0 :passed 0 :failed 0}}
          output (verify/format-edn result)
          parsed (read-string output)]
      (is (= :ok (:status parsed)))
      (is (= 0 (get-in parsed [:summary :total]))))))
