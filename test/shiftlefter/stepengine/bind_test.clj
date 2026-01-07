(ns shiftlefter.stepengine.bind-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [shiftlefter.stepengine.bind :as bind]
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
;; Helper to create pickle steps
;; -----------------------------------------------------------------------------

(defn make-step
  "Create a pickle step map for testing."
  ([text] (make-step text []))
  ([text arguments]
   {:step/id (java.util.UUID/randomUUID)
    :step/text text
    :step/arguments arguments}))

(defn make-pickle
  "Create a pickle map for testing."
  [name steps]
  {:pickle/id (java.util.UUID/randomUUID)
   :pickle/name name
   :pickle/steps (mapv #(if (string? %) (make-step %) %) steps)})

;; -----------------------------------------------------------------------------
;; match-step Tests
;; -----------------------------------------------------------------------------

(deftest test-match-step-no-match
  (testing "Returns nil when pattern doesn't match"
    (let [stepdef (registry/register! #"I have (\d+) items"
                                      (fn [n] n)
                                      {:ns 't :file "t.clj" :line 1})]
      (is (nil? (bind/match-step "I have many items" stepdef))))))

(deftest test-match-step-with-captures
  (testing "Returns stepdef and captures on match"
    (let [stepdef (registry/register! #"I type \"([^\"]+)\" into \"([^\"]+)\""
                                      (fn [val field] nil)
                                      {:ns 't :file "t.clj" :line 1})
          result (bind/match-step "I type \"alice\" into \"username\"" stepdef)]
      (is (some? result))
      (is (= stepdef (:stepdef result)))
      (is (= ["alice" "username"] (:captures result))))))

(deftest test-match-step-no-capture-groups
  (testing "Returns empty captures when pattern has no groups"
    (let [stepdef (registry/register! #"I click submit"
                                      (fn [] nil)
                                      {:ns 't :file "t.clj" :line 1})
          result (bind/match-step "I click submit" stepdef)]
      (is (some? result))
      (is (= [] (:captures result))))))

(deftest test-match-step-optional-groups
  (testing "Captures may include nil for optional groups"
    (let [stepdef (registry/register! #"I have (\d+) items?(?: in my (\w+))?"
                                      (fn [n container] nil)
                                      {:ns 't :file "t.clj" :line 1})]
      ;; With optional group present
      (let [result (bind/match-step "I have 5 items in my cart" stepdef)]
        (is (= ["5" "cart"] (:captures result))))
      ;; Without optional group
      (let [result (bind/match-step "I have 5 item" stepdef)]
        (is (= ["5" nil] (:captures result)))))))

(deftest test-match-step-full-string-match
  (testing "Uses re-matches (full string), not re-find (partial)"
    (let [stepdef (registry/register! #"I click"
                                      (fn [] nil)
                                      {:ns 't :file "t.clj" :line 1})]
      ;; Partial match should NOT succeed
      (is (nil? (bind/match-step "I click the button" stepdef)))
      ;; Full match should succeed
      (is (some? (bind/match-step "I click" stepdef))))))

;; -----------------------------------------------------------------------------
;; validate-arity Tests
;; -----------------------------------------------------------------------------

(deftest test-validate-arity-captures-only
  (testing "A == C is valid (captures only, no ctx)"
    (let [result (bind/validate-arity 2 2)]
      (is (:arity-ok? result))
      (is (= #{2 3} (:expected result)))
      (is (= 2 (:actual result))))))

(deftest test-validate-arity-with-ctx
  (testing "A == C+1 is valid (captures + ctx)"
    (let [result (bind/validate-arity 2 3)]
      (is (:arity-ok? result))
      (is (= #{2 3} (:expected result)))
      (is (= 3 (:actual result))))))

(deftest test-validate-arity-invalid
  (testing "A outside {C, C+1} is invalid"
    (let [result (bind/validate-arity 2 5)]
      (is (not (:arity-ok? result)))
      (is (= #{2 3} (:expected result)))
      (is (= 5 (:actual result))))))

(deftest test-validate-arity-zero-captures
  (testing "Zero captures: A must be 0 or 1"
    (is (:arity-ok? (bind/validate-arity 0 0)))
    (is (:arity-ok? (bind/validate-arity 0 1)))
    (is (not (:arity-ok? (bind/validate-arity 0 2))))))

;; -----------------------------------------------------------------------------
;; bind-step Tests
;; -----------------------------------------------------------------------------

(deftest test-bind-step-matched
  (testing "Single match returns :matched with binding"
    (registry/register! #"I have (\d+) items"
                        (fn [n] n)
                        {:ns 's :file "s.clj" :line 10})
    (let [step (make-step "I have 42 items")
          result (bind/bind-step step (registry/all-stepdefs))]
      (is (= :matched (:status result)))
      (is (= step (:step result)))
      (is (= ["42"] (-> result :binding :captures)))
      (is (:arity-ok? (:binding result)))
      (is (empty? (:alternatives result))))))

(deftest test-bind-step-undefined
  (testing "No matches returns :undefined"
    (registry/register! #"I have (\d+) items"
                        (fn [n] n)
                        {:ns 's :file "s.clj" :line 10})
    (let [step (make-step "I click the button")
          result (bind/bind-step step (registry/all-stepdefs))]
      (is (= :undefined (:status result)))
      (is (= step (:step result)))
      (is (nil? (:binding result)))
      (is (empty? (:alternatives result))))))

(deftest test-bind-step-ambiguous
  (testing "Multiple matches returns :ambiguous with alternatives"
    (registry/register! #"I have (\d+) items"
                        (fn [n] n)
                        {:ns 's1 :file "s1.clj" :line 1})
    (registry/clear-registry!)  ;; Clear to avoid duplicate error
    (registry/register! #"I have (\d+) items"
                        (fn [n] n)
                        {:ns 's1 :file "s1.clj" :line 1})
    ;; Register a second pattern that also matches
    (registry/register! #"I have (\w+) items"
                        (fn [n] n)
                        {:ns 's2 :file "s2.clj" :line 2})
    (let [step (make-step "I have 42 items")
          result (bind/bind-step step (registry/all-stepdefs))]
      (is (= :ambiguous (:status result)))
      (is (= step (:step result)))
      (is (nil? (:binding result)))
      (is (= 2 (count (:alternatives result))))
      ;; Alternatives should have stepdef summaries
      (is (every? :stepdef/id (:alternatives result)))
      (is (every? :pattern-src (:alternatives result)))
      (is (every? :source (:alternatives result))))))

(deftest test-bind-step-arity-mismatch
  (testing "Matched step with invalid arity has arity-ok? false"
    ;; 2 captures but arity 0
    (registry/register! #"I type \"([^\"]+)\" into \"([^\"]+)\""
                        (fn [] nil)
                        {:ns 's :file "s.clj" :line 1})
    (let [step (make-step "I type \"foo\" into \"bar\"")
          result (bind/bind-step step (registry/all-stepdefs))]
      (is (= :matched (:status result)))
      (is (not (:arity-ok? (:binding result))))
      (is (= #{2 3} (:expected (:binding result))))
      (is (= 0 (:actual (:binding result)))))))

;; -----------------------------------------------------------------------------
;; bind-pickle Tests
;; -----------------------------------------------------------------------------

(deftest test-bind-pickle-all-matched
  (testing "Pickle with all steps matched is runnable"
    (registry/register! #"I have (\d+) items"
                        (fn [n] n)
                        {:ns 's :file "s.clj" :line 1})
    (registry/register! #"I add (\d+) more"
                        (fn [n] n)
                        {:ns 's :file "s.clj" :line 2})
    (let [pickle (make-pickle "test" ["I have 5 items" "I add 3 more"])
          plan (bind/bind-pickle pickle (registry/all-stepdefs))]
      (is (uuid? (:plan/id plan)))
      (is (= pickle (:plan/pickle plan)))
      (is (= 2 (count (:plan/steps plan))))
      (is (every? #(= :matched (:status %)) (:plan/steps plan)))
      (is (:plan/runnable? plan)))))

(deftest test-bind-pickle-undefined-step
  (testing "Pickle with undefined step is not runnable"
    (registry/register! #"I have (\d+) items"
                        (fn [n] n)
                        {:ns 's :file "s.clj" :line 1})
    (let [pickle (make-pickle "test" ["I have 5 items" "I click submit"])
          plan (bind/bind-pickle pickle (registry/all-stepdefs))]
      (is (not (:plan/runnable? plan)))
      (is (= :matched (-> plan :plan/steps first :status)))
      (is (= :undefined (-> plan :plan/steps second :status))))))

(deftest test-bind-pickle-arity-mismatch
  (testing "Pickle with arity mismatch is not runnable"
    ;; 1 capture but arity 5
    (registry/register! #"I have (\d+) items"
                        (fn [a b c d e] nil)
                        {:ns 's :file "s.clj" :line 1})
    (let [pickle (make-pickle "test" ["I have 5 items"])
          plan (bind/bind-pickle pickle (registry/all-stepdefs))]
      (is (not (:plan/runnable? plan)))
      (is (= :matched (-> plan :plan/steps first :status)))
      (is (not (:arity-ok? (-> plan :plan/steps first :binding)))))))

;; -----------------------------------------------------------------------------
;; bind-suite Tests
;; -----------------------------------------------------------------------------

(deftest test-bind-suite-all-runnable
  (testing "Suite with all pickles runnable"
    (registry/register! #"I have (\d+) items"
                        (fn [n] n)
                        {:ns 's :file "s.clj" :line 1})
    (registry/register! #"I add (\d+) more"
                        (fn [n] n)
                        {:ns 's :file "s.clj" :line 2})
    (let [pickles [(make-pickle "p1" ["I have 5 items"])
                   (make-pickle "p2" ["I add 3 more"])]
          {:keys [plans runnable? diagnostics]} (bind/bind-suite pickles (registry/all-stepdefs))]
      (is (= 2 (count plans)))
      (is runnable?)
      (is (zero? (-> diagnostics :counts :total-issues))))))

(deftest test-bind-suite-with-undefined
  (testing "Suite with undefined step is not runnable"
    (registry/register! #"I have (\d+) items"
                        (fn [n] n)
                        {:ns 's :file "s.clj" :line 1})
    (let [pickles [(make-pickle "p1" ["I have 5 items"])
                   (make-pickle "p2" ["I click the button"])]
          {:keys [plans runnable? diagnostics]} (bind/bind-suite pickles (registry/all-stepdefs))]
      (is (not runnable?))
      (is (= 1 (-> diagnostics :counts :undefined-count)))
      (is (= 1 (count (:undefined diagnostics)))))))

(deftest test-bind-suite-diagnostics-structure
  (testing "Diagnostics contains all issue types"
    (registry/register! #"I have (\d+) items"
                        (fn [n] n)
                        {:ns 's :file "s.clj" :line 1})
    ;; Also register overlapping pattern for ambiguous
    (registry/register! #"I have (\w+) items"
                        (fn [n] n)
                        {:ns 's :file "s.clj" :line 2})
    ;; Bad arity step
    (registry/register! #"I click submit"
                        (fn [a b c] nil)
                        {:ns 's :file "s.clj" :line 3})
    (let [pickles [(make-pickle "p1" ["I have 5 items"])      ;; ambiguous
                   (make-pickle "p2" ["undefined step"])      ;; undefined
                   (make-pickle "p3" ["I click submit"])]     ;; invalid arity
          {:keys [diagnostics]} (bind/bind-suite pickles (registry/all-stepdefs))]
      (is (= 1 (-> diagnostics :counts :undefined-count)))
      (is (= 1 (-> diagnostics :counts :ambiguous-count)))
      (is (= 1 (-> diagnostics :counts :invalid-arity-count)))
      (is (= 3 (-> diagnostics :counts :total-issues))))))

;; -----------------------------------------------------------------------------
;; Acceptance Criteria from Spec
;; -----------------------------------------------------------------------------

(deftest test-acceptance-criteria
  (testing "Spec acceptance criteria"
    (registry/register! #"I type \"([^\"]+)\" into \"([^\"]+)\""
                        (fn [value field] nil)
                        {:ns 's :file "s.clj" :line 1})
    (let [pickle {:pickle/id #uuid "00000000-0000-0000-0000-000000000001"
                  :pickle/name "test"
                  :pickle/steps [{:step/id #uuid "00000000-0000-0000-0000-000000000002"
                                  :step/text "I type \"alice\" into \"username\""
                                  :step/arguments []}]}
          plan (bind/bind-pickle pickle (registry/all-stepdefs))]
      (is (:plan/runnable? plan))
      (is (= :matched (-> plan :plan/steps first :status)))
      (is (= ["alice" "username"] (-> plan :plan/steps first :binding :captures))))))
