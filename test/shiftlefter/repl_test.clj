(ns shiftlefter.repl-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [shiftlefter.repl :as repl]
            [shiftlefter.stepengine.registry :as registry :refer [defstep]]))

;; Clear registry and all contexts before each test
(use-fixtures :each
  (fn [f]
    (registry/clear-registry!)
    (repl/reset-ctx!)
    (repl/reset-ctxs!)
    (f)))

;; -----------------------------------------------------------------------------
;; parse-only Tests
;; -----------------------------------------------------------------------------

(deftest test-parse-only-valid
  (testing "Parses valid Gherkin text"
    (let [result (repl/parse-only "Feature: Test\n  Scenario: X\n    Given foo")]
      (is (= :ok (:status result)))
      (is (= 1 (count (:pickles result)))))))

(deftest test-parse-only-invalid
  (testing "Returns parse error for invalid Gherkin"
    (let [result (repl/parse-only "Not valid Gherkin at all")]
      (is (= :parse-error (:status result)))
      (is (seq (:errors result))))))

;; -----------------------------------------------------------------------------
;; run-dry Tests
;; -----------------------------------------------------------------------------

(deftest test-run-dry-undefined-steps
  (testing "Reports undefined steps"
    (let [result (repl/run-dry "Feature: Test\n  Scenario: X\n    Given undefined step")]
      (is (= :bind-error (:status result)))
      (is (seq (-> result :diagnostics :undefined))))))

(deftest test-run-dry-defined-steps
  (testing "Binds defined steps successfully"
    (defstep #"I am ready" [] nil)
    (let [result (repl/run-dry "Feature: Test\n  Scenario: X\n    Given I am ready")]
      (is (= :ok (:status result)))
      (is (= 1 (count (:plans result)))))))

;; -----------------------------------------------------------------------------
;; run Tests
;; -----------------------------------------------------------------------------

(deftest test-run-passing-scenario
  (testing "Executes passing scenario"
    (defstep #"I do something" [] nil)
    (defstep #"it works" [] nil)
    (let [result (repl/run "
Feature: Test
  Scenario: Works
    Given I do something
    Then it works")]
      (is (= :ok (:status result)))
      (is (= 1 (-> result :summary :scenarios)))
      (is (= 1 (-> result :summary :passed)))
      (is (= 0 (-> result :summary :failed))))))

(deftest test-run-failing-scenario
  (testing "Reports failing scenario"
    (defstep #"I fail" [] (throw (ex-info "boom" {})))
    (let [result (repl/run "Feature: Test\n  Scenario: Fails\n    Given I fail")]
      (is (= :ok (:status result)))
      (is (= 1 (-> result :summary :failed))))))

(deftest test-run-pending-scenario
  (testing "Reports pending scenario"
    (defstep #"I am pending" [] :pending)
    (let [result (repl/run "Feature: Test\n  Scenario: Pending\n    Given I am pending")]
      (is (= :ok (:status result)))
      (is (= 1 (-> result :summary :pending))))))

(deftest test-run-parse-error
  (testing "Returns parse error for invalid input"
    (let [result (repl/run "garbage")]
      (is (= :parse-error (:status result))))))

(deftest test-run-bind-error
  (testing "Returns bind error for undefined steps"
    (let [result (repl/run "Feature: X\n  Scenario: Y\n    Given nope")]
      (is (= :bind-error (:status result))))))

;; -----------------------------------------------------------------------------
;; Utility Tests
;; -----------------------------------------------------------------------------

(deftest test-clear!
  (testing "Clears registry"
    (defstep #"something" [] nil)
    (is (seq (repl/steps)))
    (repl/clear!)
    (is (empty? (repl/steps)))))

(deftest test-steps
  (testing "Lists registered steps"
    (defstep #"step one" [] nil)
    (defstep #"step two" [] nil)
    (let [patterns (repl/steps)]
      (is (= 2 (count patterns)))
      (is (some #(= "step one" %) patterns))
      (is (some #(= "step two" %) patterns)))))

;; -----------------------------------------------------------------------------
;; Free Mode Tests
;; -----------------------------------------------------------------------------

(deftest test-step-passing
  (testing "Executes passing step"
    (defstep #"I do a thing" [] nil)
    (let [result (repl/step "I do a thing")]
      (is (= :passed (:status result))))))

(deftest test-step-undefined
  (testing "Reports undefined step"
    (let [result (repl/step "I do something undefined")]
      (is (= :undefined (:status result)))
      (is (= "I do something undefined" (:text result))))))

(deftest test-step-failing
  (testing "Reports failing step"
    (defstep #"I explode" [] (throw (ex-info "boom" {})))
    (let [result (repl/step "I explode")]
      (is (= :failed (:status result)))
      (is (some? (:error result))))))

(deftest test-step-pending
  (testing "Reports pending step"
    (defstep #"I am not ready" [] :pending)
    (let [result (repl/step "I am not ready")]
      (is (= :pending (:status result))))))

(deftest test-step-with-captures
  (testing "Passes captures to step function"
    (defstep #"I have (\d+) items" [n]
      {:count (Integer/parseInt n)})
    (let [result (repl/step "I have 42 items")]
      (is (= :passed (:status result)))
      (is (= 42 (-> result :ctx :count))))))

(deftest test-step-context-accumulation
  (testing "Context accumulates across steps"
    (defstep #"I set x to (\d+)" [n]
      {:x (Integer/parseInt n)})
    (defstep #"I set y to (\d+)" [n]
      {:y (Integer/parseInt n)})
    (repl/step "I set x to 10")
    (repl/step "I set y to 20")
    (is (= {:x 10 :y 20} (repl/ctx)))))

(deftest test-step-context-preserved-on-failure
  (testing "Context preserved when step fails"
    (defstep #"I set value" [] {:value 123})
    (defstep #"I fail" [] (throw (ex-info "oops" {})))
    (repl/step "I set value")
    (repl/step "I fail")
    (is (= {:value 123} (repl/ctx)))))

(deftest test-reset-ctx!
  (testing "reset-ctx! clears context"
    (defstep #"I set foo" [] {:foo "bar"})
    (repl/step "I set foo")
    (is (= {:foo "bar"} (repl/ctx)))
    (repl/reset-ctx!)
    (is (= {} (repl/ctx)))))

(deftest test-reset-ctx!-with-value
  (testing "reset-ctx! can set custom context"
    (repl/reset-ctx! {:custom "data"})
    (is (= {:custom "data"} (repl/ctx)))))

(deftest test-step-ambiguous
  (testing "Reports ambiguous step"
    (defstep #"I am .*" [] nil)
    (defstep #"I am here" [] nil)
    (let [result (repl/step "I am here")]
      (is (= :ambiguous (:status result)))
      (is (= 2 (count (:matches result)))))))

(deftest test-clear!-resets-context
  (testing "clear! also resets session context"
    (defstep #"I set data" [] {:data 1})
    (repl/step "I set data")
    (is (= {:data 1} (repl/ctx)))
    (repl/clear!)
    (is (= {} (repl/ctx)))))

;; -----------------------------------------------------------------------------
;; Named Context Tests (free mode)
;; -----------------------------------------------------------------------------

(deftest test-free-single-step
  (testing "free executes step in named context"
    (defstep #"I am (\w+)" [name]
      {:user name})
    (let [result (repl/free :alice "I am alice")]
      (is (= :passed (:status result)))
      (is (= :alice (:session result)))
      (is (= {:user "alice"} (:ctx result))))))

(deftest test-free-multiple-steps
  (testing "free executes multiple steps in sequence"
    (defstep #"I set x" [] {:x 1})
    (defstep #"I set y" [] {:y 2})
    (let [result (repl/free :test "I set x" "I set y")]
      (is (= :passed (:status result)))
      (is (= {:x 1 :y 2} (:ctx result))))))

(deftest test-free-separate-contexts
  (testing "free maintains separate contexts for different sessions"
    (defstep #"I am (\w+)" [name]
      {:user name})
    (repl/free :alice "I am alice")
    (repl/free :bob "I am bob")
    (is (= {:user "alice"} (repl/ctx :alice)))
    (is (= {:user "bob"} (repl/ctx :bob)))))

(deftest test-free-context-accumulation
  (testing "free accumulates context across calls"
    (defstep #"I set (\w+) to (\d+)" [k v]
      {(keyword k) (Integer/parseInt v)})
    (repl/free :test "I set x to 1")
    (repl/free :test "I set y to 2")
    (is (= {:x 1 :y 2} (repl/ctx :test)))))

(deftest test-free-stops-on-failure
  (testing "free stops on first failing step"
    (defstep #"I pass" [] {:passed true})
    (defstep #"I fail" [] (throw (ex-info "boom" {})))
    (defstep #"I never run" [] {:never true})
    (let [result (repl/free :test "I pass" "I fail" "I never run")]
      (is (= :failed (:status result)))
      (is (= {:passed true} (:ctx result)))
      (is (nil? (:never (repl/ctx :test)))))))

(deftest test-free-undefined-step
  (testing "free reports undefined step"
    (let [result (repl/free :test "I am undefined")]
      (is (= :undefined (:status result)))
      (is (= :test (:session result))))))

(deftest test-ctxs-returns-all
  (testing "ctxs returns all named contexts"
    (defstep #"I am (\w+)" [name] {:user name})
    (repl/free :alice "I am alice")
    (repl/free :bob "I am bob")
    (is (= {:alice {:user "alice"} :bob {:user "bob"}}
           (repl/ctxs)))))

(deftest test-reset-ctxs!
  (testing "reset-ctxs! clears all named contexts"
    (defstep #"I exist" [] {:exists true})
    (repl/free :a "I exist")
    (repl/free :b "I exist")
    (is (= 2 (count (repl/ctxs))))
    (repl/reset-ctxs!)
    (is (= {} (repl/ctxs)))))

(deftest test-clear!-resets-named-contexts
  (testing "clear! also resets named contexts"
    (defstep #"I exist" [] {:exists true})
    (repl/free :test "I exist")
    (is (seq (repl/ctxs)))
    (repl/clear!)
    (is (= {} (repl/ctxs)))))
