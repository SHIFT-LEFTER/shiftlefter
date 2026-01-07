(ns shiftlefter.runner.discover-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [shiftlefter.runner.discover :as discover]))

;; -----------------------------------------------------------------------------
;; discover-feature-files Tests
;; -----------------------------------------------------------------------------

(deftest test-discover-single-file
  (testing "Discovers a single .feature file"
    (let [files (discover/discover-feature-files ["test/fixtures/features/outline.feature"])]
      (is (= 1 (count files)))
      (is (str/ends-with? (first files) "outline.feature")))))

(deftest test-discover-directory-recursive
  (testing "Recursively discovers .feature files in directory"
    (let [files (discover/discover-feature-files ["test/fixtures/gherkin/eol-types"])]
      (is (pos? (count files)))
      (is (every? #(str/ends-with? % ".feature") files)))))

(deftest test-discover-multiple-directories
  (testing "Discovers from multiple directories"
    (let [files (discover/discover-feature-files ["test/fixtures/features"
                                                   "test/fixtures/gherkin/eol-types"])]
      (is (> (count files) 1))
      (is (every? #(str/ends-with? % ".feature") files)))))

(deftest test-discover-deduplication
  (testing "Deduplicates same file referenced multiple ways"
    (let [;; Reference same file twice (absolute and relative)
          files (discover/discover-feature-files ["test/fixtures/features/outline.feature"
                                                   "test/fixtures/features/"])]
      ;; Should only have one entry for outline.feature
      (is (= 1 (count (filter #(str/ends-with? % "outline.feature") files)))))))

(deftest test-discover-stable-ordering
  (testing "Results are in stable, sorted order"
    (let [files (discover/discover-feature-files ["test/fixtures/gherkin/eol-types"])]
      (is (= files (sort files))))))

(deftest test-discover-empty-paths
  (testing "Empty paths returns empty vector"
    (let [files (discover/discover-feature-files [])]
      (is (= [] files)))))

(deftest test-discover-nonexistent-path
  (testing "Non-existent path returns empty (no error)"
    (let [files (discover/discover-feature-files ["nonexistent/path/"])]
      (is (= [] files)))))

(deftest test-discover-non-feature-file
  (testing "Non-.feature files are ignored"
    (let [files (discover/discover-feature-files ["test/fixtures/steps/basic_steps.clj"])]
      (is (= [] files)))))

(deftest test-discover-mixed-files-and-dirs
  (testing "Handles mix of files and directories"
    (let [files (discover/discover-feature-files ["test/fixtures/features/outline.feature"
                                                   "test/fixtures/gherkin/eol-types/"])]
      (is (pos? (count files)))
      ;; Should include outline.feature
      (is (some #(str/ends-with? % "outline.feature") files))
      ;; Should include files from eol-types
      (is (some #(str/includes? % "eol-types") files)))))

;; -----------------------------------------------------------------------------
;; discover-feature-files-or-error Tests
;; -----------------------------------------------------------------------------

(deftest test-discover-or-error-success
  (testing "Returns :ok with files on success"
    (let [result (discover/discover-feature-files-or-error ["test/fixtures/features/"])]
      (is (= :ok (:status result)))
      (is (pos? (count (:files result)))))))

(deftest test-discover-or-error-path-not-found
  (testing "Returns error for non-existent path"
    (let [result (discover/discover-feature-files-or-error ["nonexistent/definitely/not/here"])]
      (is (= :error (:status result)))
      (is (= :discover/path-not-found (:type result))))))

(deftest test-discover-or-error-no-features
  (testing "Returns error when no .feature files found"
    (let [result (discover/discover-feature-files-or-error ["test/fixtures/steps/"])]
      (is (= :error (:status result)))
      (is (= :discover/no-features (:type result))))))

(deftest test-discover-or-error-no-paths
  (testing "Returns error when no paths specified"
    (let [result (discover/discover-feature-files-or-error [])]
      (is (= :error (:status result)))
      (is (= :discover/no-paths (:type result))))))

;; -----------------------------------------------------------------------------
;; Glob Pattern Tests
;; -----------------------------------------------------------------------------

(deftest test-discover-glob-pattern
  (testing "Expands glob patterns"
    (let [files (discover/discover-feature-files ["test/fixtures/gherkin/**/*.feature"])]
      (is (pos? (count files)))
      (is (every? #(str/ends-with? % ".feature") files)))))

(deftest test-discover-glob-no-matches
  (testing "Glob with no matches returns empty"
    (let [files (discover/discover-feature-files ["test/fixtures/**/*.nonexistent"])]
      (is (= [] files)))))

;; -----------------------------------------------------------------------------
;; Acceptance Criteria
;; -----------------------------------------------------------------------------

(deftest test-acceptance-criteria
  (testing "Spec: discovery finds all .feature files, deterministically"
    (let [files (discover/discover-feature-files ["test/fixtures/gherkin"])]
      (is (every? #(str/ends-with? % ".feature") files))
      (is (= files (sort files))))))
