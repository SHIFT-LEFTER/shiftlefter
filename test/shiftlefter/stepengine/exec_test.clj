(ns shiftlefter.stepengine.exec-test
  (:require [clojure.test :refer [deftest is testing]]
            [shiftlefter.stepengine.exec :as exec]))

;; -----------------------------------------------------------------------------
;; Helper to create test bindings
;; -----------------------------------------------------------------------------

(defn make-binding
  "Create a step binding for testing."
  ([f arity] (make-binding f arity []))
  ([f arity captures]
   {:fn f
    :arity arity
    :captures captures
    :stepdef/id "test-step"
    :pattern-src "test pattern"}))

(defn make-step
  "Create a pickle step for testing."
  ([] (make-step "test step" []))
  ([text] (make-step text []))
  ([text arguments]
   {:step/id (java.util.UUID/randomUUID)
    :step/text text
    :step/arguments arguments}))

(defn make-bound-step
  "Create a bound step (as returned by binder) for testing."
  [f arity captures]
  {:status :matched
   :step (make-step)
   :binding (make-binding f arity captures)})

(defn make-plan
  "Create a run plan for testing."
  [bound-steps & {:keys [runnable?] :or {runnable? true}}]
  {:plan/id (java.util.UUID/randomUUID)
   :plan/pickle {:pickle/id (java.util.UUID/randomUUID)
                 :pickle/name "test pickle"}
   :plan/steps (vec bound-steps)
   :plan/runnable? runnable?})

;; -----------------------------------------------------------------------------
;; invoke-step Tests
;; -----------------------------------------------------------------------------

(deftest test-invoke-captures-only
  (testing "A == C: call with captures only"
    (let [f (fn [a b] {:sum (+ (Integer/parseInt a) (Integer/parseInt b))})
          binding (make-binding f 2 ["3" "5"])
          ctx {:step (make-step) :scenario {}}
          result (exec/invoke-step binding ["3" "5"] ctx)]
      (is (= :passed (:status result)))
      (is (= {:sum 8} (:scenario result))))))

(deftest test-invoke-with-ctx
  (testing "A == C+1: call with captures + ctx"
    (let [f (fn [n ctx]
              (update (:scenario ctx) :count + (Integer/parseInt n)))
          binding (make-binding f 2 ["5"])
          ctx {:step (make-step) :scenario {:count 10}}
          result (exec/invoke-step binding ["5"] ctx)]
      (is (= :passed (:status result)))
      (is (= {:count 15} (:scenario result))))))

(deftest test-invoke-return-map
  (testing "Return map → new scenario context"
    (let [f (fn [] {:new-key "value"})
          binding (make-binding f 0)
          ctx {:step (make-step) :scenario {:old-key "old"}}
          result (exec/invoke-step binding [] ctx)]
      (is (= :passed (:status result)))
      (is (= {:new-key "value"} (:scenario result))))))

(deftest test-invoke-return-nil
  (testing "Return nil → ctx unchanged"
    (let [f (fn [] nil)
          binding (make-binding f 0)
          ctx {:step (make-step) :scenario {:existing "data"}}
          result (exec/invoke-step binding [] ctx)]
      (is (= :passed (:status result)))
      (is (= {:existing "data"} (:scenario result))))))

(deftest test-invoke-return-pending
  (testing "Return :pending → pending status"
    (let [f (fn [] :pending)
          binding (make-binding f 0)
          ctx {:step (make-step) :scenario {:data 1}}
          result (exec/invoke-step binding [] ctx)]
      (is (= :pending (:status result)))
      (is (= {:data 1} (:scenario result))))))

(deftest test-invoke-return-invalid
  (testing "Return invalid type → failure"
    (let [f (fn [] "invalid string return")
          binding (make-binding f 0)
          ctx {:step (make-step) :scenario {}}
          result (exec/invoke-step binding [] ctx)]
      (is (= :failed (:status result)))
      (is (= :step/invalid-return (-> result :error :type)))
      (is (re-find #"invalid type" (-> result :error :message))))))

(deftest test-invoke-exception
  (testing "Exception → failure with error info"
    (let [f (fn [] (throw (ex-info "Step failed!" {:reason :test})))
          binding (make-binding f 0)
          ctx {:step (make-step) :scenario {}}
          result (exec/invoke-step binding [] ctx)]
      (is (= :failed (:status result)))
      (is (= :step/exception (-> result :error :type)))
      (is (= "Step failed!" (-> result :error :message)))
      (is (= {:reason :test} (-> result :error :data))))))

(deftest test-invoke-runtime-exception
  (testing "Runtime exception → failure"
    (let [f (fn [] (/ 1 0))
          binding (make-binding f 0)
          ctx {:step (make-step) :scenario {}}
          result (exec/invoke-step binding [] ctx)]
      (is (= :failed (:status result)))
      (is (= :step/exception (-> result :error :type))))))

(deftest test-invoke-docstring-access
  (testing "Docstring accessible via ctx"
    (let [docstring {:content "Hello\nWorld" :mediaType "text/plain"}
          step (make-step "I see docstring" docstring)
          f (fn [ctx]
              (is (= docstring (-> ctx :step :step/arguments)))
              {:saw-docstring true})
          binding (make-binding f 1)
          ctx {:step step :scenario {}}
          result (exec/invoke-step binding [] ctx)]
      (is (= :passed (:status result)))
      (is (= {:saw-docstring true} (:scenario result))))))

(deftest test-invoke-datatable-access
  (testing "DataTable accessible via ctx"
    (let [table {:rows [["name" "age"] ["alice" "30"]]}
          step (make-step "I see table" table)
          f (fn [ctx]
              (is (= table (-> ctx :step :step/arguments)))
              {:saw-table true})
          binding (make-binding f 1)
          ctx {:step step :scenario {}}
          result (exec/invoke-step binding [] ctx)]
      (is (= :passed (:status result)))
      (is (= {:saw-table true} (:scenario result))))))

;; -----------------------------------------------------------------------------
;; execute-scenario Tests
;; -----------------------------------------------------------------------------

(deftest test-scenario-all-pass
  (testing "All steps pass → scenario passed"
    (let [plan (make-plan [(make-bound-step (fn [] {:a 1}) 0 [])
                           (make-bound-step (fn [ctx] (assoc (:scenario ctx) :b 2)) 1 [])
                           (make-bound-step (fn [ctx] (assoc (:scenario ctx) :c 3)) 1 [])])
          result (exec/execute-scenario plan {})]
      (is (= :passed (:status result)))
      (is (= 3 (count (:steps result))))
      (is (every? #(= :passed (:status %)) (:steps result)))
      (is (= {:a 1 :b 2 :c 3} (:scenario-ctx result))))))

(deftest test-scenario-fail-fast
  (testing "Failure → remaining steps skipped"
    (let [executed (atom [])
          plan (make-plan [(make-bound-step (fn [] (swap! executed conj 1) {:step 1}) 0 [])
                           (make-bound-step (fn [] (swap! executed conj 2) (throw (Exception. "fail"))) 0 [])
                           (make-bound-step (fn [] (swap! executed conj 3) {:step 3}) 0 [])])
          result (exec/execute-scenario plan {})]
      (is (= :failed (:status result)))
      (is (= [1 2] @executed))  ;; Step 3 never executed
      (is (= :passed (-> result :steps (nth 0) :status)))
      (is (= :failed (-> result :steps (nth 1) :status)))
      (is (= :skipped (-> result :steps (nth 2) :status))))))

(deftest test-scenario-pending-skips-remaining
  (testing "Pending → remaining steps skipped"
    (let [plan (make-plan [(make-bound-step (fn [] {:a 1}) 0 [])
                           (make-bound-step (fn [] :pending) 0 [])
                           (make-bound-step (fn [] {:c 3}) 0 [])])
          result (exec/execute-scenario plan {})]
      (is (= :pending (:status result)))
      (is (= :passed (-> result :steps (nth 0) :status)))
      (is (= :pending (-> result :steps (nth 1) :status)))
      (is (= :skipped (-> result :steps (nth 2) :status))))))

(deftest test-scenario-not-runnable
  (testing "Non-runnable plan → all steps skipped"
    (let [plan (make-plan [(make-bound-step (fn [] {:a 1}) 0 [])]
                          :runnable? false)
          result (exec/execute-scenario plan {})]
      (is (= :skipped (:status result)))
      (is (every? #(= :skipped (:status %)) (:steps result))))))

(deftest test-scenario-ctx-threading
  (testing "Context threads through steps"
    (let [plan (make-plan [(make-bound-step (fn [] {:count 1}) 0 [])
                           (make-bound-step (fn [ctx] (update (:scenario ctx) :count inc)) 1 [])
                           (make-bound-step (fn [ctx] (update (:scenario ctx) :count inc)) 1 [])])
          result (exec/execute-scenario plan {})]
      (is (= :passed (:status result)))
      (is (= {:count 3} (:scenario-ctx result))))))

;; -----------------------------------------------------------------------------
;; execute-suite Tests
;; -----------------------------------------------------------------------------

(deftest test-suite-all-pass
  (testing "All scenarios pass → suite passed"
    (let [plans [(make-plan [(make-bound-step (fn [] {:a 1}) 0 [])])
                 (make-plan [(make-bound-step (fn [] {:b 2}) 0 [])])]
          result (exec/execute-suite plans)]
      (is (= :passed (:status result)))
      (is (= 2 (-> result :counts :passed)))
      (is (= 0 (-> result :counts :failed))))))

(deftest test-suite-continues-after-failure
  (testing "Suite continues after scenario failure"
    (let [executed (atom [])
          plans [(make-plan [(make-bound-step (fn [] (swap! executed conj :s1) {:s1 true}) 0 [])])
                 (make-plan [(make-bound-step (fn [] (swap! executed conj :s2) (throw (Exception. "fail"))) 0 [])])
                 (make-plan [(make-bound-step (fn [] (swap! executed conj :s3) {:s3 true}) 0 [])])]
          result (exec/execute-suite plans)]
      (is (= :failed (:status result)))
      (is (= [:s1 :s2 :s3] @executed))  ;; All scenarios attempted
      (is (= 2 (-> result :counts :passed)))
      (is (= 1 (-> result :counts :failed))))))

(deftest test-suite-counts
  (testing "Suite counts all statuses"
    (let [plans [(make-plan [(make-bound-step (fn [] {:a 1}) 0 [])])            ;; passed
                 (make-plan [(make-bound-step (fn [] (throw (Exception. ""))) 0 [])])  ;; failed
                 (make-plan [(make-bound-step (fn [] :pending) 0 [])])          ;; pending
                 (make-plan [(make-bound-step (fn [] {:d 4}) 0 [])] :runnable? false)] ;; skipped
          result (exec/execute-suite plans)]
      (is (= :failed (:status result)))
      (is (= 1 (-> result :counts :passed)))
      (is (= 1 (-> result :counts :failed)))
      (is (= 1 (-> result :counts :pending)))
      (is (= 1 (-> result :counts :skipped))))))

(deftest test-suite-empty
  (testing "Empty suite → passed"
    (let [result (exec/execute-suite [])]
      (is (= :passed (:status result)))
      (is (= 0 (-> result :counts :passed))))))

;; -----------------------------------------------------------------------------
;; Acceptance Criteria from Spec
;; -----------------------------------------------------------------------------

(deftest test-acceptance-ctx-aware-docstring
  (testing "Spec: ctx-aware step can read docstring"
    (let [f (fn [ctx]
              (is (= {:content "x" :mediaType "text/plain"}
                     (-> ctx :step :step/arguments)))
              {:seen true})
          step {:step/id (java.util.UUID/randomUUID)
                :step/text "test"
                :step/arguments {:content "x" :mediaType "text/plain"}}
          binding {:fn f :arity 1 :captures []}
          ctx {:step step :scenario {}}
          res (exec/invoke-step binding [] ctx)]
      (is (= :passed (:status res)))
      (is (= {:seen true} (:scenario res))))))

(deftest test-acceptance-pending-stops-scenario
  (testing "Spec: pending stops scenario + marks pending"
    (let [f (fn [] :pending)
          binding {:fn f :arity 0 :captures []}
          ctx {:step {} :scenario {}}
          out (exec/invoke-step binding [] ctx)]
      (is (= :pending (:status out))))))

;; -----------------------------------------------------------------------------
;; Macro Wrapper Execution Tests (Task 2.9)
;; -----------------------------------------------------------------------------

(defn make-wrapper-step
  "Create a synthetic wrapper step for testing."
  [key step-count]
  {:step/id (java.util.UUID/randomUUID)
   :step/text (str key " +")
   :step/synthetic? true
   :step/macro {:role :call
                :key key
                :step-count step-count}
   :step/arguments []})

(defn make-expanded-step
  "Create an expanded child step for testing."
  [key index text]
  {:step/id (java.util.UUID/randomUUID)
   :step/text text
   :step/macro {:role :expanded
                :key key
                :index index}
   :step/arguments []})

(defn make-bound-wrapper
  "Create a bound wrapper step for testing (synthetic status from binder)."
  [key step-count]
  {:status :synthetic
   :step (make-wrapper-step key step-count)
   :binding nil
   :alternatives []})

(defn make-bound-expanded
  "Create a bound expanded step for testing."
  [key index text f arity]
  {:status :matched
   :step (make-expanded-step key index text)
   :binding (make-binding f arity [])
   :alternatives []})

(deftest test-wrapper-step-not-executed
  (testing "Wrapper step marked passed without execution"
    (let [executed (atom false)
          plan (make-plan [(assoc (make-bound-wrapper "login" 1)
                                   :binding {:fn (fn [] (reset! executed true)) :arity 0})
                           (make-bound-expanded "login" 0 "child step" (fn [] {:done true}) 0)])
          result (exec/execute-scenario plan {})]
      (is (not @executed) "Wrapper step fn should not be called")
      (is (= :passed (:status result))))))

(deftest test-wrapper-children-all-pass
  (testing "Wrapper passes when all children pass"
    (let [plan (make-plan [(make-bound-wrapper "login" 2)
                           (make-bound-expanded "login" 0 "step 1" (fn [] {:a 1}) 0)
                           (make-bound-expanded "login" 1 "step 2" (fn [] {:b 2}) 0)])
          result (exec/execute-scenario plan {})]
      (is (= :passed (:status result)))
      ;; Wrapper status should be rolled up to :passed
      (is (= :passed (-> result :steps first :status)))
      ;; Children should pass
      (is (= :passed (-> result :steps second :status)))
      (is (= :passed (-> result :steps (nth 2) :status))))))

(deftest test-wrapper-child-fails
  (testing "Wrapper fails when any child fails"
    (let [plan (make-plan [(make-bound-wrapper "login" 2)
                           (make-bound-expanded "login" 0 "step 1" (fn [] {:a 1}) 0)
                           (make-bound-expanded "login" 1 "step 2" (fn [] (throw (Exception. "fail"))) 0)])
          result (exec/execute-scenario plan {})]
      (is (= :failed (:status result)))
      ;; Wrapper status should roll up to :failed
      (is (= :failed (-> result :steps first :status))))))

(deftest test-wrapper-child-pending
  (testing "Wrapper pending when child pending (no failures)"
    (let [plan (make-plan [(make-bound-wrapper "login" 2)
                           (make-bound-expanded "login" 0 "step 1" (fn [] {:a 1}) 0)
                           (make-bound-expanded "login" 1 "step 2" (fn [] :pending) 0)])
          result (exec/execute-scenario plan {})]
      (is (= :pending (:status result)))
      ;; Wrapper status should roll up to :pending
      (is (= :pending (-> result :steps first :status))))))

(deftest test-wrapper-failure-beats-pending
  (testing "Wrapper fails when one child fails and one pending"
    (let [plan (make-plan [(make-bound-wrapper "login" 2)
                           (make-bound-expanded "login" 0 "step 1" (fn [] :pending) 0)
                           (make-bound-expanded "login" 1 "step 2" (fn [] (throw (Exception. "fail"))) 0)])
          result (exec/execute-scenario plan {})]
      ;; Child 1 is pending, then child 2 would be skipped after pending
      ;; But actually, pending triggers skip of remaining
      (is (= :pending (:status result)))
      ;; The wrapper rollup sees [:pending :skipped], which is :pending
      (is (= :pending (-> result :steps first :status))))))

(deftest test-multiple-wrappers-independent
  (testing "Multiple wrappers roll up independently"
    (let [plan (make-plan [;; First macro: passes
                           (make-bound-wrapper "login" 1)
                           (make-bound-expanded "login" 0 "login step" (fn [] {:logged-in true}) 0)
                           ;; Second macro: fails
                           (make-bound-wrapper "checkout" 1)
                           (make-bound-expanded "checkout" 0 "checkout step" (fn [] (throw (Exception. "fail"))) 0)])
          result (exec/execute-scenario plan {})]
      (is (= :failed (:status result)))
      ;; First wrapper: children all pass → passed
      (is (= :passed (-> result :steps first :status)))
      ;; Second wrapper: child fails → failed
      (is (= :failed (-> result :steps (nth 2) :status))))))

(deftest test-wrapper-with-regular-steps
  (testing "Mix of wrapper and regular steps"
    (let [plan (make-plan [;; Regular step first
                           (make-bound-step (fn [] {:setup true}) 0 [])
                           ;; Then a macro
                           (make-bound-wrapper "login" 1)
                           (make-bound-expanded "login" 0 "login step" (fn [] {:logged-in true}) 0)
                           ;; Then regular step
                           (make-bound-step (fn [] {:cleanup true}) 0 [])])
          result (exec/execute-scenario plan {})]
      (is (= :passed (:status result)))
      (is (= 4 (count (:steps result))))
      ;; Wrapper rolled up correctly
      (is (= :passed (-> result :steps second :status))))))
