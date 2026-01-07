(ns shiftlefter.stepengine.macros.ini-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [shiftlefter.stepengine.macros.ini :as ini])
  (:import [shiftlefter.gherkin.location Location]))

;; -----------------------------------------------------------------------------
;; parse-file Tests
;; -----------------------------------------------------------------------------

(deftest test-parse-file-valid
  (testing "Parses valid macros from file"
    (let [{:keys [macros errors]} (ini/parse-file "test/fixtures/macros/good/all-macros.ini")]
      (is (empty? errors))
      (is (= 3 (count macros)))
      (let [macro (first (filter #(= "Log in as admin" (:macro/key %)) macros))]
        (is (some? macro))
        (is (= "Standard login flow" (:macro/description macro)))
        (is (= 5 (count (:macro/steps macro))))))))

(deftest test-parse-file-macro-definition
  (testing "Macro definition has file and location"
    (let [{:keys [macros]} (ini/parse-file "test/fixtures/macros/auth.ini")
          macro (first macros)]
      (is (string? (-> macro :macro/definition :file)))
      (is (instance? Location (-> macro :macro/definition :location)))
      (is (= 1 (:line (-> macro :macro/definition :location))))
      (is (= 1 (:column (-> macro :macro/definition :location)))))))

(deftest test-parse-file-step-location
  (testing "Steps use standard Location with :column"
    (let [{:keys [macros]} (ini/parse-file "test/fixtures/macros/auth.ini")
          macro (first macros)
          step (first (:macro/steps macro))]
      (is (instance? Location (:step/location step)))
      (is (contains? (:step/location step) :column))
      (is (not (contains? (:step/location step) :col))))))

(deftest test-parse-file-not-found
  (testing "Returns error for missing file"
    (let [{:keys [macros errors]} (ini/parse-file "nonexistent.ini")]
      (is (empty? macros))
      (is (= 1 (count errors)))
      (is (= :macro/file-read-error (-> errors first :type))))))

(deftest test-parse-file-steps-preserved
  (testing "Step keywords and text are preserved"
    (let [{:keys [macros]} (ini/parse-file "test/fixtures/macros/good/all-macros.ini")
          macro (first (filter #(= "Log in as admin" (:macro/key %)) macros))
          steps (:macro/steps macro)]
      (is (= "Given" (-> steps first :step/keyword)))
      (is (= "I am on the login page" (-> steps first :step/text)))
      (is (= "When" (-> steps second :step/keyword)))
      ;; Check placeholder preservation
      (is (some #(str/includes? (:step/text %) "{email}") steps)))))

(deftest test-parse-file-multiple-macros
  (testing "Parses multiple macros from single file"
    (let [{:keys [macros]} (ini/parse-file "test/fixtures/macros/good/all-macros.ini")]
      (is (= 3 (count macros)))
      (is (= #{"Log in as admin" "Create valid user" "Navigate to dashboard"}
             (set (map :macro/key macros)))))))
