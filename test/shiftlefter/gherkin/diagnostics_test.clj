(ns shiftlefter.gherkin.diagnostics-test
  "Tests for diagnostics formatting and error snapshots."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [shiftlefter.gherkin.api :as api]
            [shiftlefter.gherkin.diagnostics :as diag]
            [shiftlefter.gherkin.io :as io]))

;; -----------------------------------------------------------------------------
;; format-error tests
;; -----------------------------------------------------------------------------

(deftest format-error-standard-format
  (testing "format-error produces path:line:col: type: message"
    (let [error {:type :invalid-keyword
                 :message "Invalid keyword: Feture"
                 :location {:line 1 :column 1}}
          result (diag/format-error "test.feature" error)]
      (is (= "test.feature:1:1: invalid-keyword: Invalid keyword: Feture" result)))))

(deftest format-error-with-nil-path
  (testing "format-error uses '-' for nil path"
    (let [error {:type :parse-error :message "Error" :location {:line 5 :column 10}}
          result (diag/format-error nil error)]
      (is (str/starts-with? result "-:5:10:")))))

(deftest format-error-missing-location
  (testing "format-error defaults to line 1, col 1 for missing location"
    (let [error {:type :some-error :message "Error"}
          result (diag/format-error "test.feature" error)]
      (is (str/includes? result ":1:1:")))))

(deftest format-error-short-no-path
  (testing "format-error-short produces line:col: type: message"
    (let [error {:type :invalid-keyword
                 :message "Bad"
                 :location {:line 3 :column 5}}
          result (diag/format-error-short error)]
      (is (= "3:5: invalid-keyword: Bad" result)))))

;; -----------------------------------------------------------------------------
;; EDN formatting tests
;; -----------------------------------------------------------------------------

(deftest format-error-edn-structure
  (testing "format-error-edn produces clean map"
    (let [error {:type :invalid-keyword
                 :message "Bad"
                 :location {:line 1 :column 1}}
          result (diag/format-error-edn error)]
      (is (= :invalid-keyword (:type result)))
      (is (= "Bad" (:message result)))
      (is (= 1 (:line result)))
      (is (= 1 (:column result))))))

(deftest error-type-counts-groups
  (testing "error-type-counts groups by type"
    (let [errors [{:type :invalid-keyword}
                  {:type :invalid-keyword}
                  {:type :unexpected-token}]
          result (diag/error-type-counts errors)]
      (is (= 2 (:invalid-keyword result)))
      (is (= 1 (:unexpected-token result))))))

;; -----------------------------------------------------------------------------
;; Error snapshot tests - lock error types and locations
;; Note: Messages may change; only lock type and location
;; -----------------------------------------------------------------------------

(defn- parse-fixture
  "Parse a fixture file and return errors."
  [path]
  (let [content (:content (io/read-file-utf8 path))
        {:keys [errors]} (api/parse-string content)]
    errors))

(defn- first-error-snapshot
  "Return snapshot of first error: [type line column]"
  [errors]
  (when-let [e (first errors)]
    [(:type e)
     (get-in e [:location :line])
     (get-in e [:location :column])]))

(deftest snapshot-invalid-keyword
  (testing "invalid-keyword error at line 1"
    (let [errors (parse-fixture "test/fixtures/gherkin/invalid/invalid-keyword.feature")
          [type line col] (first-error-snapshot errors)]
      (is (= :invalid-keyword type))
      (is (= 1 line))
      (is (= 1 col)))))

(deftest snapshot-missing-feature
  (testing "missing-feature error at line 1"
    (let [errors (parse-fixture "test/fixtures/gherkin/invalid/missing-feature.feature")
          [type line _col] (first-error-snapshot errors)]
      (is (= :missing-feature type))
      (is (= 1 line)))))

(deftest snapshot-incomplete-docstring
  (testing "incomplete-docstring error"
    (let [errors (parse-fixture "test/fixtures/gherkin/invalid/incomplete-docstring.feature")
          [type line _col] (first-error-snapshot errors)]
      (is (= :incomplete-docstring type))
      (is (= 4 line)))))

(deftest snapshot-inconsistent-cells
  (testing "inconsistent-cell-count error"
    (let [errors (parse-fixture "test/fixtures/gherkin/invalid/inconsistent-cells.feature")
          [type line _col] (first-error-snapshot errors)]
      (is (= :inconsistent-cell-count type))
      (is (= 5 line)))))

(deftest snapshot-orphan-tags
  (testing "orphan-tags produces unexpected-eof at end of file"
    (let [errors (parse-fixture "test/fixtures/gherkin/invalid/orphan-tags.feature")
          [type line _col] (first-error-snapshot errors)]
      ;; Orphan tags at EOF produce :unexpected-eof with message about tags
      (is (= :unexpected-eof type))
      (is (= 2 line)))))

(deftest snapshot-duplicate-feature
  (testing "duplicate-feature error at second Feature"
    (let [errors (parse-fixture "test/fixtures/gherkin/invalid/duplicate-feature.feature")
          [type line _col] (first-error-snapshot errors)]
      (is (= :duplicate-feature type))
      (is (= 5 line)))))

;; -----------------------------------------------------------------------------
;; Distinct error types test
;; -----------------------------------------------------------------------------

(deftest all-fixtures-have-distinct-error-types
  (testing "each fixture produces a distinct error type"
    (let [fixtures ["test/fixtures/gherkin/invalid/invalid-keyword.feature"
                    "test/fixtures/gherkin/invalid/missing-feature.feature"
                    "test/fixtures/gherkin/invalid/incomplete-docstring.feature"
                    "test/fixtures/gherkin/invalid/inconsistent-cells.feature"
                    "test/fixtures/gherkin/invalid/orphan-tags.feature"
                    "test/fixtures/gherkin/invalid/duplicate-feature.feature"]
          types (map (fn [f] (-> f parse-fixture first :type)) fixtures)]
      ;; All types should be distinct (no duplicates in our test suite)
      (is (= (count types) (count (set types)))
          "Each fixture should test a unique error type"))))
