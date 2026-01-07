(ns shiftlefter.runner.step-loader-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [shiftlefter.runner.step-loader :as loader]
            [shiftlefter.stepengine.registry :as registry]))

;; -----------------------------------------------------------------------------
;; Test Fixtures
;; -----------------------------------------------------------------------------

(defn clean-registry-fixture [f]
  (registry/clear-registry!)
  (f)
  (registry/clear-registry!))

(use-fixtures :each clean-registry-fixture)

;; -----------------------------------------------------------------------------
;; Basic Loading Tests
;; -----------------------------------------------------------------------------

(deftest test-load-from-directory
  (testing "Loading step files from a directory"
    (let [result (loader/load-step-paths! ["test/fixtures/steps/"])]
      (is (= :ok (:status result)))
      (is (pos? (count (:loaded result))) "Should have loaded files")
      (is (>= (count (registry/all-stepdefs)) 1) "Should have registered steps"))))

(deftest test-load-recursive
  (testing "Recursively loads files from subdirectories"
    (let [result (loader/load-step-paths! ["test/fixtures/steps/"])]
      (is (= :ok (:status result)))
      ;; Should find files in both test/fixtures/steps/ and test/fixtures/steps/more/
      (is (>= (count (:loaded result)) 2) "Should load from subdirectories")
      ;; Should have steps from both files
      (is (>= (count (registry/all-stepdefs)) 5)
          "Should have steps from all loaded files"))))

(deftest test-load-deterministic-order
  (testing "Files are loaded in sorted path order"
    (let [result (loader/load-step-paths! ["test/fixtures/steps/"])
          paths (:loaded result)]
      (is (= paths (sort paths)) "Loaded paths should be sorted"))))

(deftest test-load-single-file
  (testing "Loading a single step file directly"
    (let [result (loader/load-step-paths! ["test/fixtures/steps/basic_steps.clj"])]
      (is (= :ok (:status result)))
      (is (= 1 (count (:loaded result))))
      (is (= 5 (count (registry/all-stepdefs))) "basic_steps.clj has 5 steps"))))

(deftest test-load-multiple-paths
  (testing "Loading from multiple paths"
    (let [result (loader/load-step-paths! ["test/fixtures/steps/basic_steps.clj"
                                           "test/fixtures/steps/more/"])]
      (is (= :ok (:status result)))
      (is (= 2 (count (:loaded result))) "1 file + 1 dir with 1 file = 2")
      ;; basic_steps.clj (5 steps) + extra_steps.clj (2 steps) = 7 steps
      (is (= 7 (count (registry/all-stepdefs)))))))

;; -----------------------------------------------------------------------------
;; Registry State Tests
;; -----------------------------------------------------------------------------

(deftest test-clears-registry-before-load
  (testing "Registry is cleared before loading"
    ;; Pre-register a step
    (registry/register! #"pre-existing step" (fn [] nil) {:ns 't :file "t.clj" :line 1})
    (is (= 1 (count (registry/all-stepdefs))))

    ;; Load should clear first
    (loader/load-step-paths! ["test/fixtures/steps/basic_steps.clj"])

    ;; Pre-existing step should be gone
    (is (nil? (registry/find-by-pattern "pre-existing step")))
    ;; Only the loaded steps should exist
    (is (= 5 (count (registry/all-stepdefs))))))

;; -----------------------------------------------------------------------------
;; Error Handling Tests
;; -----------------------------------------------------------------------------

(deftest test-nonexistent-file-error
  (testing "Non-existent file returns error"
    (let [result (loader/load-step-paths! ["nonexistent/path/steps.clj"])]
      (is (= :error (:status result)))
      (is (= 1 (count (:errors result))))
      (is (= 0 (count (:loaded result)))))))

(deftest test-nonexistent-directory-silently-skipped
  (testing "Non-existent directories are silently skipped (for default step-paths)"
    (let [result (loader/load-step-paths! ["nonexistent/path/"])]
      ;; Should be :ok with 0 loaded, not an error
      (is (= :ok (:status result)))
      (is (= 0 (count (:loaded result)))))))

(deftest test-load-or-throw
  (testing "load-step-paths-or-throw! throws on error for non-existent file"
    (try
      ;; Note: non-existent directories are silently skipped (common for defaults)
      ;; but non-existent files still throw errors
      (loader/load-step-paths-or-throw! ["nonexistent/file.clj"])
      (is false "Should have thrown")
      (catch Exception e
        (is (re-find #"Failed to load" (ex-message e)))))))

(deftest test-load-or-throw-success
  (testing "load-step-paths-or-throw! returns result on success"
    (let [result (loader/load-step-paths-or-throw! ["test/fixtures/steps/basic_steps.clj"])]
      (is (= :ok (:status result)))
      (is (= 1 (count (:loaded result)))))))

;; -----------------------------------------------------------------------------
;; Step Registration Verification
;; -----------------------------------------------------------------------------

(deftest test-loaded-steps-are-callable
  (testing "Steps loaded from files are callable"
    (loader/load-step-paths! ["test/fixtures/steps/basic_steps.clj"])

    (let [cart-step (registry/find-by-pattern "I have (\\d+) items in my cart")]
      (is (some? cart-step) "Should find cart step")
      (let [step-fn (:fn cart-step)
            result (step-fn "5")]
        (is (= {:cart-count 5} result))))))
