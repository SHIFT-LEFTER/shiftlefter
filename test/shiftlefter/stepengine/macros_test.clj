(ns shiftlefter.stepengine.macros-test
  (:require [clojure.test :refer [deftest is testing]]
            [shiftlefter.stepengine.macros :as macros]
            [shiftlefter.gherkin.location :as loc]))

;; -----------------------------------------------------------------------------
;; load-registries Tests
;; -----------------------------------------------------------------------------

(deftest test-load-registries-single-file
  (testing "Loads macros from a single INI file"
    (let [{:keys [registry errors]} (macros/load-registries ["test/fixtures/macros/good/all-macros.ini"])]
      (is (empty? errors))
      (is (= 3 (count registry)))
      (is (contains? registry "Log in as admin"))
      (is (contains? registry "Create valid user"))
      (is (contains? registry "Navigate to dashboard")))))

(deftest test-load-registries-macro-structure
  (testing "Macro has correct structure"
    (let [{:keys [registry]} (macros/load-registries ["test/fixtures/macros/good/all-macros.ini"])
          macro (get registry "Log in as admin")]
      (is (= "Log in as admin" (:macro/key macro)))
      (is (map? (:macro/definition macro)))
      (is (string? (-> macro :macro/definition :file)))
      (is (instance? shiftlefter.gherkin.location.Location (-> macro :macro/definition :location)))
      (is (= "Standard login flow" (:macro/description macro)))
      (is (vector? (:macro/steps macro)))
      (is (= 5 (count (:macro/steps macro)))))))

(deftest test-load-registries-step-structure
  (testing "Steps have correct structure with standard Location"
    (let [{:keys [registry]} (macros/load-registries ["test/fixtures/macros/good/all-macros.ini"])
          macro (get registry "Log in as admin")
          first-step (first (:macro/steps macro))]
      (is (= "Given" (:step/keyword first-step)))
      (is (= "I am on the login page" (:step/text first-step)))
      (is (instance? shiftlefter.gherkin.location.Location (:step/location first-step)))
      ;; Check location uses :column not :col
      (is (pos-int? (:line (:step/location first-step))))
      (is (nat-int? (:column (:step/location first-step)))))))

(deftest test-load-registries-file-not-found
  (testing "Returns error for missing file"
    (let [{:keys [registry errors]} (macros/load-registries ["nonexistent/file.ini"])]
      (is (empty? registry))
      (is (= 1 (count errors)))
      (is (= :macro/file-not-found (-> errors first :type))))))

(deftest test-load-registries-multiple-files
  (testing "Loads macros from multiple files"
    (let [{:keys [registry errors]} (macros/load-registries
                                     ["test/fixtures/macros/auth.ini"
                                      "test/fixtures/macros/good/all-macros.ini"])]
      (is (empty? errors))
      ;; auth.ini has 1 macro, all-macros.ini has 3, but "login as alice" != "Log in as admin"
      (is (= 4 (count registry)))
      (is (contains? registry "login as alice"))
      (is (contains? registry "Log in as admin")))))

(deftest test-load-registries-duplicate-key
  (testing "Returns error for duplicate macro key"
    (let [{:keys [errors]} (macros/load-registries ["test/fixtures/macros/dupe.ini"])]
      (is (seq errors))
      (is (= :macro/duplicate-key (-> errors first :type)))
      (is (= "login as alice" (-> errors first :macro-key))))))

(deftest test-load-registries-duplicate-across-files
  (testing "Detects duplicates across multiple files"
    (let [{:keys [errors]} (macros/load-registries
                            ["test/fixtures/macros/auth.ini"
                             "test/fixtures/macros/dupe.ini"])]
      ;; dupe.ini has internal duplicate, and auth.ini has "login as alice" too
      (is (seq errors))
      (is (some #(= :macro/duplicate-key (:type %)) errors)))))

;; -----------------------------------------------------------------------------
;; Acceptance Criteria (Task 2.4)
;; -----------------------------------------------------------------------------

(deftest test-acceptance-criteria-valid-macro
  (testing "Task 2.4 AC: valid macro with correct location"
    (let [{:keys [registry]} (macros/load-registries ["test/fixtures/macros/auth.ini"])
          m (get registry "login as alice")]
      (is (= "login as alice" (:macro/key m)))
      ;; Definition location should be line 1, column 1 (where "name =" starts)
      (is (= 1 (:line (-> m :macro/definition :location))))
      (is (= 1 (:column (-> m :macro/definition :location)))))))

(deftest test-acceptance-criteria-duplicate-error
  (testing "Task 2.4 AC: duplicate key error"
    (let [{:keys [errors]} (macros/load-registries ["test/fixtures/macros/dupe.ini"])]
      (is (= :macro/duplicate-key (-> errors first :type))))))

;; -----------------------------------------------------------------------------
;; Helper Function Tests
;; -----------------------------------------------------------------------------

(deftest test-get-macro
  (testing "get-macro retrieves by key"
    (let [{:keys [registry]} (macros/load-registries ["test/fixtures/macros/auth.ini"])]
      (is (some? (macros/get-macro registry "login as alice")))
      (is (nil? (macros/get-macro registry "nonexistent"))))))

(deftest test-macro-keys
  (testing "macro-keys returns all keys"
    (let [{:keys [registry]} (macros/load-registries ["test/fixtures/macros/good/all-macros.ini"])]
      (is (= 3 (count (macros/macro-keys registry))))
      (is (contains? (set (macros/macro-keys registry)) "Log in as admin")))))

;; -----------------------------------------------------------------------------
;; detect-call Tests (Task 2.5)
;; -----------------------------------------------------------------------------

(deftest test-detect-call-enabled-with-suffix
  (testing "Enabled + suffix → macro call detected"
    (let [result (macros/detect-call {:enabled? true}
                                     {:step/text "login as alice +"})]
      (is (true? (:is-macro? result)))
      (is (= "login as alice" (:key result))))))

(deftest test-detect-call-disabled-with-suffix
  (testing "Disabled + suffix → not a macro call"
    (let [result (macros/detect-call {:enabled? false}
                                     {:step/text "login as alice +"})]
      (is (false? (:is-macro? result)))
      (is (nil? (:key result))))))

(deftest test-detect-call-enabled-no-suffix
  (testing "Enabled + no suffix → not a macro call"
    (let [result (macros/detect-call {:enabled? true}
                                     {:step/text "login as alice"})]
      (is (false? (:is-macro? result))))))

(deftest test-detect-call-disabled-no-suffix
  (testing "Disabled + no suffix → not a macro call"
    (let [result (macros/detect-call {:enabled? false}
                                     {:step/text "login as alice"})]
      (is (false? (:is-macro? result))))))

(deftest test-detect-call-key-trimming
  (testing "Key is trimmed after removing suffix"
    (let [result (macros/detect-call {:enabled? true}
                                     {:step/text "  login as alice  +"})]
      (is (true? (:is-macro? result)))
      (is (= "login as alice" (:key result))))))

(deftest test-detect-call-preserves-internal-whitespace
  (testing "Internal whitespace in key is preserved"
    (let [result (macros/detect-call {:enabled? true}
                                     {:step/text "login  as  alice +"})]
      (is (true? (:is-macro? result)))
      (is (= "login  as  alice" (:key result))))))

(deftest test-detect-call-nil-step-text
  (testing "Nil step text → not a macro call"
    (let [result (macros/detect-call {:enabled? true}
                                     {:step/text nil})]
      (is (false? (:is-macro? result))))))

(deftest test-detect-call-empty-key
  (testing "Empty key after trimming → still detected as macro"
    (let [result (macros/detect-call {:enabled? true}
                                     {:step/text " +"})]
      (is (true? (:is-macro? result)))
      (is (= "" (:key result))))))

(deftest test-detect-call-plus-without-space
  (testing "Plus without leading space → not a macro call"
    (let [result (macros/detect-call {:enabled? true}
                                     {:step/text "login as alice+"})]
      (is (false? (:is-macro? result))))))

;; -----------------------------------------------------------------------------
;; Acceptance Criteria (Task 2.5)
;; -----------------------------------------------------------------------------

(deftest test-acceptance-criteria-detect-call-enabled
  (testing "Task 2.5 AC: enabled + suffix"
    (is (= {:is-macro? true :key "login as alice"}
           (macros/detect-call {:enabled? true}
                               {:step/text "login as alice +"})))))

(deftest test-acceptance-criteria-detect-call-disabled
  (testing "Task 2.5 AC: disabled + suffix"
    (is (= {:is-macro? false}
           (macros/detect-call {:enabled? false}
                               {:step/text "login as alice +"})))))

;; -----------------------------------------------------------------------------
;; expand-pickle Context Validation Tests (Task 2.6)
;; -----------------------------------------------------------------------------

(deftest test-expand-pickle-outline-context-error
  (testing "Macro in Scenario Outline → error"
    (let [pickle {:pickle/row-location {:line 9 :column 3}
                  :pickle/steps [{:step/text "login as alice +"
                                  :step/arguments []}]}
          result (macros/expand-pickle {:enabled? true} pickle {})]
      (is (= 1 (count (:errors result))))
      (is (= :macro/scenario-outline-not-supported
             (-> result :errors first :type)))
      (is (= "login as alice"
             (-> result :errors first :macro-key))))))

(deftest test-expand-pickle-docstring-argument-error
  (testing "Macro with DocString → error"
    (let [pickle {:pickle/steps [{:step/text "login as alice +"
                                  :step/arguments {:content "some text"
                                                   :mediaType "text/plain"}}]}
          result (macros/expand-pickle {:enabled? true} pickle {})]
      (is (= 1 (count (:errors result))))
      (is (= :macro/argument-not-supported
             (-> result :errors first :type)))
      (is (= "login as alice"
             (-> result :errors first :macro-key))))))

(deftest test-expand-pickle-datatable-argument-error
  (testing "Macro with DataTable → error"
    (let [pickle {:pickle/steps [{:step/text "login as alice +"
                                  :step/arguments [{:cells ["a" "b"]}
                                                   {:cells ["1" "2"]}]}]}
          result (macros/expand-pickle {:enabled? true} pickle {})]
      (is (= 1 (count (:errors result))))
      (is (= :macro/argument-not-supported
             (-> result :errors first :type))))))

(deftest test-expand-pickle-disabled-no-checks
  (testing "Macros disabled → no validation, pass through"
    (let [pickle {:pickle/row-location {:line 9 :column 3}
                  :pickle/steps [{:step/text "login as alice +"
                                  :step/arguments []}]}
          result (macros/expand-pickle {:enabled? false} pickle {})]
      (is (empty? (:errors result)))
      (is (= pickle (:pickle result))))))

(deftest test-expand-pickle-non-macro-pass-through
  (testing "Non-macro steps pass through without errors"
    (let [pickle {:pickle/steps [{:step/text "I am on the login page"
                                  :step/arguments []}
                                 {:step/text "I click submit"
                                  :step/arguments []}]}
          result (macros/expand-pickle {:enabled? true} pickle {})]
      (is (empty? (:errors result)))
      (is (= pickle (:pickle result))))))

(deftest test-expand-pickle-valid-macro-context
  (testing "Valid macro context (no outline, no arguments) → no errors"
    (let [pickle {:pickle/steps [{:step/text "login as alice +"
                                  :step/location {:line 1 :column 1}
                                  :step/arguments []}]}
          reg {"login as alice" {:macro/key "login as alice"
                                 :macro/definition {:file "test.ini" :location {:line 1 :column 1}}
                                 :macro/steps [{:step/keyword "Given" :step/text "I do something" :step/location {:line 2 :column 1}}]}}
          result (macros/expand-pickle {:enabled? true} pickle reg)]
      (is (empty? (:errors result))))))

(deftest test-expand-pickle-multiple-errors
  (testing "Multiple macro calls in outline → multiple errors"
    (let [pickle {:pickle/row-location {:line 9 :column 3}
                  :pickle/steps [{:step/text "login as alice +"}
                                 {:step/text "navigate to dashboard +"}]}
          result (macros/expand-pickle {:enabled? true} pickle {})]
      (is (= 2 (count (:errors result))))
      (is (every? #(= :macro/scenario-outline-not-supported (:type %))
                  (:errors result))))))

(deftest test-expand-pickle-empty-arguments-ok
  (testing "Empty arguments vector is valid context"
    (let [pickle {:pickle/steps [{:step/text "login as alice +"
                                  :step/location {:line 1 :column 1}
                                  :step/arguments []}]}
          reg {"login as alice" {:macro/key "login as alice"
                                 :macro/definition {:file "test.ini" :location {:line 1 :column 1}}
                                 :macro/steps [{:step/keyword "Given" :step/text "I do something" :step/location {:line 2 :column 1}}]}}
          result (macros/expand-pickle {:enabled? true} pickle reg)]
      (is (empty? (:errors result))))))

(deftest test-expand-pickle-nil-arguments-ok
  (testing "Nil arguments is valid context"
    (let [pickle {:pickle/steps [{:step/text "login as alice +"
                                  :step/location {:line 1 :column 1}
                                  :step/arguments nil}]}
          reg {"login as alice" {:macro/key "login as alice"
                                 :macro/definition {:file "test.ini" :location {:line 1 :column 1}}
                                 :macro/steps [{:step/keyword "Given" :step/text "I do something" :step/location {:line 2 :column 1}}]}}
          result (macros/expand-pickle {:enabled? true} pickle reg)]
      (is (empty? (:errors result))))))

;; -----------------------------------------------------------------------------
;; Acceptance Criteria (Task 2.6)
;; -----------------------------------------------------------------------------

(deftest test-acceptance-criteria-outline-error
  (testing "Task 2.6 AC: outline context error"
    (let [pickle {:pickle/row-location {:line 9 :column 3}
                  :pickle/steps [{:step/text "login as alice +" :step/arguments []}]}]
      (is (= :macro/scenario-outline-not-supported
             (-> (macros/expand-pickle {:enabled? true} pickle {}) :errors first :type))))))

(deftest test-acceptance-criteria-argument-error
  (testing "Task 2.6 AC: argument error"
    (let [pickle {:pickle/steps [{:step/text "login as alice +"
                                  :step/arguments {:content "x" :mediaType "text/plain"}}]}]
      (is (= :macro/argument-not-supported
             (-> (macros/expand-pickle {:enabled? true} pickle {}) :errors first :type))))))

;; -----------------------------------------------------------------------------
;; Macro Expansion Tests (Task 2.7)
;; -----------------------------------------------------------------------------

(def ^:private test-registry
  {"login as alice" {:macro/key "login as alice"
                     :macro/definition {:file "macros/auth.ini" :location {:line 1 :column 1}}
                     :macro/steps [{:step/keyword "Given" :step/text "I visit /login" :step/location {:line 2 :column 3}}
                                   {:step/keyword "When" :step/text "I fill username" :step/location {:line 3 :column 3}}]}
   "empty macro" {:macro/key "empty macro"
                  :macro/definition {:file "macros/empty.ini" :location {:line 1 :column 1}}
                  :macro/steps []}
   "recursive macro" {:macro/key "recursive macro"
                      :macro/definition {:file "macros/bad.ini" :location {:line 1 :column 1}}
                      :macro/steps [{:step/keyword "Given" :step/text "I do setup"}
                                    {:step/keyword "When" :step/text "call another +" :step/location {:line 3 :column 3}}]}})

(deftest test-expand-pickle-basic-expansion
  (testing "Expands macro into wrapper + children"
    (let [pickle {:pickle/steps [{:step/keyword "Given"
                                  :step/text "login as alice +"
                                  :step/location {:line 10 :column 5}
                                  :step/arguments []}]}
          result (macros/expand-pickle {:enabled? true} pickle test-registry)
          steps (-> result :pickle :pickle/steps)]
      (is (empty? (:errors result)))
      (is (= 3 (count steps)))  ;; wrapper + 2 expanded
      ;; Wrapper
      (is (true? (:step/synthetic? (first steps))))
      (is (= :call (-> steps first :step/macro :role)))
      (is (= "login as alice" (-> steps first :step/macro :key)))
      (is (= 2 (-> steps first :step/macro :step-count)))
      ;; First expanded step
      (is (= :expanded (-> steps second :step/macro :role)))
      (is (= 0 (-> steps second :step/macro :index)))
      (is (= "I visit /login" (-> steps second :step/text)))
      ;; Second expanded step
      (is (= :expanded (-> steps (nth 2) :step/macro :role)))
      (is (= 1 (-> steps (nth 2) :step/macro :index)))
      (is (= "I fill username" (-> steps (nth 2) :step/text))))))

(deftest test-expand-pickle-undefined-macro
  (testing "Returns error for undefined macro"
    (let [pickle {:pickle/steps [{:step/text "nonexistent macro +"
                                  :step/location {:line 5 :column 3}
                                  :step/arguments []}]}
          result (macros/expand-pickle {:enabled? true} pickle test-registry)]
      (is (= 1 (count (:errors result))))
      (is (= :macro/undefined (-> result :errors first :type)))
      (is (= "nonexistent macro" (-> result :errors first :macro-key))))))

(deftest test-expand-pickle-empty-expansion
  (testing "Returns error for macro with no steps"
    (let [pickle {:pickle/steps [{:step/text "empty macro +"
                                  :step/location {:line 5 :column 3}
                                  :step/arguments []}]}
          result (macros/expand-pickle {:enabled? true} pickle test-registry)]
      (is (= 1 (count (:errors result))))
      (is (= :macro/empty-expansion (-> result :errors first :type))))))

(deftest test-expand-pickle-recursion-disallowed
  (testing "Returns error for recursive macro"
    (let [pickle {:pickle/steps [{:step/text "recursive macro +"
                                  :step/location {:line 5 :column 3}
                                  :step/arguments []}]}
          result (macros/expand-pickle {:enabled? true} pickle test-registry)]
      (is (= 1 (count (:errors result))))
      (is (= :macro/recursion-disallowed (-> result :errors first :type)))
      (is (= "call another +" (-> result :errors first :nested-call))))))

(deftest test-expand-pickle-mixed-steps
  (testing "Non-macro steps pass through unchanged"
    (let [pickle {:pickle/steps [{:step/keyword "Given"
                                  :step/text "I am on homepage"
                                  :step/location {:line 5 :column 3}}
                                 {:step/keyword "When"
                                  :step/text "login as alice +"
                                  :step/location {:line 6 :column 3}
                                  :step/arguments []}
                                 {:step/keyword "Then"
                                  :step/text "I see dashboard"
                                  :step/location {:line 7 :column 3}}]}
          result (macros/expand-pickle {:enabled? true} pickle test-registry)
          steps (-> result :pickle :pickle/steps)]
      (is (empty? (:errors result)))
      (is (= 5 (count steps)))  ;; 1 + (1 wrapper + 2 expanded) + 1
      ;; First step unchanged
      (is (= "I am on homepage" (-> steps first :step/text)))
      (is (nil? (-> steps first :step/macro)))
      ;; Wrapper is second
      (is (true? (-> steps second :step/synthetic?)))
      ;; Last step unchanged
      (is (= "I see dashboard" (-> steps last :step/text)))
      (is (nil? (-> steps last :step/macro))))))

(deftest test-expand-pickle-macros-summary
  (testing "Pickle includes :pickle/macros summary"
    (let [pickle {:pickle/steps [{:step/text "login as alice +"
                                  :step/location {:line 10 :column 5}
                                  :step/arguments []}]}
          result (macros/expand-pickle {:enabled? true} pickle test-registry)
          macros-summary (-> result :pickle :pickle/macros)]
      (is (= 1 (count macros-summary)))
      (is (= "login as alice" (-> macros-summary first :key)))
      (is (= 2 (-> macros-summary first :step-count)))
      (is (map? (-> macros-summary first :definition)))
      (is (map? (-> macros-summary first :call-site))))))

(deftest test-expand-pickle-macros-summary-first-use-order
  (testing "Macro summary in first-use order, no duplicates"
    (let [pickle {:pickle/steps [{:step/text "login as alice +"
                                  :step/location {:line 10 :column 5}
                                  :step/arguments []}
                                 {:step/text "login as alice +"
                                  :step/location {:line 11 :column 5}
                                  :step/arguments []}]}
          result (macros/expand-pickle {:enabled? true} pickle test-registry)
          macros-summary (-> result :pickle :pickle/macros)]
      ;; Only one entry even though macro used twice
      (is (= 1 (count macros-summary)))
      (is (= "login as alice" (-> macros-summary first :key))))))

(deftest test-expand-pickle-provenance-call-site
  (testing "Provenance includes correct call-site"
    (let [pickle {:pickle/steps [{:step/keyword "Given"
                                  :step/text "login as alice +"
                                  :step/location {:line 12 :column 5}
                                  :step/arguments []}]}
          result (macros/expand-pickle {:enabled? true} pickle test-registry)
          wrapper (-> result :pickle :pickle/steps first)
          expanded (-> result :pickle :pickle/steps second)]
      ;; Wrapper call-site
      (is (= {:line 12 :column 5} (-> wrapper :step/macro :call-site)))
      ;; Expanded step call-site (same as wrapper)
      (is (= {:line 12 :column 5} (-> expanded :step/macro :call-site))))))

(deftest test-expand-pickle-provenance-definition-step
  (testing "Expanded steps include definition-step"
    (let [pickle {:pickle/steps [{:step/text "login as alice +"
                                  :step/location {:line 10 :column 5}
                                  :step/arguments []}]}
          result (macros/expand-pickle {:enabled? true} pickle test-registry)
          expanded (-> result :pickle :pickle/steps second)]
      (is (= "macros/auth.ini" (-> expanded :step/macro :definition-step :file)))
      (is (= 2 (-> expanded :step/macro :definition-step :line)))
      (is (= 3 (-> expanded :step/macro :definition-step :column))))))

;; -----------------------------------------------------------------------------
;; Acceptance Criteria (Task 2.7)
;; -----------------------------------------------------------------------------

(deftest test-acceptance-criteria-task-2-7
  (testing "Task 2.7 AC: full expansion example"
    (let [pickle {:pickle/source-file "a.feature"
                  :pickle/steps [{:step/keyword "Given"
                                  :step/text "login as alice +"
                                  :step/location {:line 12 :column 5}
                                  :step/arguments []}]}
          reg {"login as alice" {:macro/key "login as alice"
                                 :macro/definition {:file "macros/auth.ini" :location {:line 8 :column 1}}
                                 :macro/steps [{:step/keyword "Given" :step/text "I visit /login" :step/location {:line 9 :column 3}}
                                               {:step/keyword "And" :step/text "I fill username" :step/location {:line 10 :column 3}}]}}
          out (macros/expand-pickle {:enabled? true} pickle reg)
          steps (:pickle/steps (:pickle out))]
      (is (= 3 (count steps)))
      (is (true? (:step/synthetic? (first steps))))
      (is (= :call (-> steps first :step/macro :role)))
      (is (= :expanded (-> steps second :step/macro :role)))
      (is (= 0 (-> steps second :step/macro :index)))
      (is (= "login as alice" (-> out :pickle :pickle/macros first :key)))
      (is (map? (-> out :pickle :pickle/macros first :definition))))))
