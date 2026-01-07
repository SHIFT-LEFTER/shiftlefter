(ns shiftlefter.core-test
  "Tests for CLI functionality."
  (:require
   [clojure.java.io :as jio]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [shiftlefter.core :as core]))

;; Access private functions for testing
(def find-feature-files #'core/find-feature-files)
(def check-single-file #'core/check-single-file)
(def check-files #'core/check-files)
(def format-single-file #'core/format-single-file)
(def format-files #'core/format-files)

;; Test fixture for temp files
(def ^:dynamic *temp-dir* nil)

(defn temp-dir-fixture [f]
  (let [dir (jio/file (System/getProperty "java.io.tmpdir")
                      (str "shiftlefter-test-" (System/currentTimeMillis)))]
    (.mkdirs dir)
    (binding [*temp-dir* dir]
      (try
        (f)
        (finally
          ;; Cleanup
          (doseq [file (file-seq dir)]
            (.delete file)))))))

(use-fixtures :each temp-dir-fixture)

(deftest find-feature-files-test
  (testing "finds .feature files in directory"
    (let [files (find-feature-files ["examples/quickstart/features/"])]
      (is (seq files) "should find at least one file")
      (is (every? #(str/ends-with? % ".feature") files)
          "all files should be .feature")))

  (testing "returns single file when given file path"
    (let [files (find-feature-files ["examples/quickstart/features/toy-login.feature"])]
      (is (= 1 (count files)))
      (is (= "examples/quickstart/features/toy-login.feature" (first files)))))

  (testing "returns empty for non-existent path"
    (let [files (find-feature-files ["nonexistent/path/"])]
      (is (empty? files))))

  (testing "skips non-.feature files"
    (let [files (find-feature-files ["deps.edn"])]
      (is (empty? files)))))

(deftest check-single-file-test
  (testing "valid file returns :ok status"
    (let [result (check-single-file "examples/quickstart/features/toy-login.feature")]
      (is (= :ok (:status result)))
      (is (= "examples/quickstart/features/toy-login.feature" (:path result)))))

  (testing "non-existent file returns :not-found status"
    (let [result (check-single-file "nonexistent.feature")]
      (is (= :not-found (:status result))))))

(deftest check-files-test
  (testing "all valid files returns exit-code 0"
    (let [result (check-files ["examples/quickstart/features/toy-login.feature"])]
      (is (= 0 (:exit-code result)))
      (is (= 1 (:valid result)))
      (is (= 0 (:invalid result)))))

  (testing "directory with valid files returns exit-code 0"
    (let [result (check-files ["compliance/gherkin/testdata/good/"])]
      (is (= 0 (:exit-code result)))
      (is (= 46 (:valid result)))
      (is (= 0 (:invalid result)))))

  (testing "directory with invalid files returns exit-code 1"
    (let [result (check-files ["compliance/gherkin/testdata/bad/"])]
      (is (= 1 (:exit-code result)))
      (is (= 0 (:valid result)))
      (is (pos? (:invalid result)))))

  (testing "non-existent path returns exit-code 2"
    (let [result (check-files ["nonexistent/"])]
      (is (= 2 (:exit-code result)))))

  (testing "empty directory returns exit-code 2"
    (let [result (check-files ["src/"])] ;; no .feature files in src
      (is (= 2 (:exit-code result)))
      (is (= 0 (:total result)))))

  (testing "multiple paths works"
    (let [result (check-files ["examples/quickstart/features/toy-login.feature"
                               "compliance/gherkin/testdata/good/minimal.feature"])]
      (is (= 0 (:exit-code result)))
      (is (= 2 (:valid result))))))

;; -----------------------------------------------------------------------------
;; Format tests (--write mode)
;; -----------------------------------------------------------------------------

(deftest format-single-file-test
  (testing "formats file and returns :reformatted when content changes"
    (let [path (str (jio/file *temp-dir* "test.feature"))
          ;; Non-canonical whitespace (extra spaces)
          content "Feature:  Test\n  Scenario:  S\n    Given  step\n"]
      (spit path content)
      (let [result (format-single-file path)]
        (is (= :reformatted (:status result)))
        (is (= path (:path result)))
        ;; Verify file was actually modified
        (is (not= content (slurp path))))))

  (testing "returns :unchanged when file already canonical"
    (let [path (str (jio/file *temp-dir* "canonical.feature"))
          ;; Canonical format: blank line after Feature, 2-space indent
          content "Feature: Test\n\n  Scenario: S\n    Given step\n"]
      (spit path content)
      (let [result (format-single-file path)]
        (is (= :unchanged (:status result)))
        ;; Verify file unchanged
        (is (= content (slurp path))))))

  (testing "returns :error for files with parse errors"
    (let [path (str (jio/file *temp-dir* "invalid.feature"))
          content "Not valid gherkin at all"]
      (spit path content)
      (let [result (format-single-file path)]
        (is (= :error (:status result)))
        (is (= :parse-errors (:reason result)))
        ;; Verify file unchanged
        (is (= content (slurp path))))))

  (testing "returns :not-found for non-existent file"
    (let [result (format-single-file "/nonexistent/path.feature")]
      (is (= :not-found (:status result))))))

(deftest format-files-directory-test
  (testing "formats directory and returns correct counts"
    (let [file1 (str (jio/file *temp-dir* "a.feature"))
          file2 (str (jio/file *temp-dir* "b.feature"))]
      ;; One needs reformatting, one is already canonical
      (spit file1 "Feature:  Test\n  Scenario:  S\n    Given  step\n")
      (spit file2 "Feature: Test\n\n  Scenario: S\n    Given step\n")
      (let [result (format-files [(str *temp-dir*)])]
        (is (= 0 (:exit-code result)))
        (is (= 2 (:total result)))
        (is (= 1 (:reformatted result)))
        (is (= 1 (:unchanged result)))
        (is (= 0 (:errors result)))))))

(deftest format-files-with-errors-test
  (testing "returns exit-code 1 when some files have errors"
    (let [valid (str (jio/file *temp-dir* "valid.feature"))
          invalid (str (jio/file *temp-dir* "invalid.feature"))]
      (spit valid "Feature: Test\n\n  Scenario: S\n    Given step\n")
      (spit invalid "Not valid gherkin")
      (let [result (format-files [(str *temp-dir*)])]
        (is (= 1 (:exit-code result)))
        (is (= 1 (:unchanged result)))
        (is (= 1 (:errors result)))))))

(deftest format-files-edge-cases-test
  (testing "returns exit-code 2 for non-existent path"
    (let [result (format-files ["/nonexistent/path/"])]
      (is (= 2 (:exit-code result)))))

  (testing "returns exit-code 2 when no .feature files found"
    (let [result (format-files ["src/"])]
      (is (= 2 (:exit-code result))))))

(deftest format-files-idempotent-test
  (testing "idempotent - running twice gives all unchanged"
    (let [path (str (jio/file *temp-dir* "idem.feature"))
          content "Feature:  Test\n  Scenario:  S\n    Given  step\n"]
      (spit path content)
      ;; First run reformats
      (let [r1 (format-files [(str *temp-dir*)])]
        (is (= 1 (:reformatted r1))))
      ;; Second run should be unchanged
      (let [r2 (format-files [(str *temp-dir*)])]
        (is (= 0 (:reformatted r2)))
        (is (= 1 (:unchanged r2)))))))
