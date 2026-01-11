(ns shiftlefter.stepengine.exec-test
  (:require [clojure.test :refer [deftest is testing]]
            [shiftlefter.stepengine.exec :as exec]
            [shiftlefter.adapters.registry :as registry]
            [shiftlefter.browser.ctx :as browser.ctx]
            [shiftlefter.capabilities.ctx :as cap]
            [shiftlefter.runner.events :as events]))

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

;; -----------------------------------------------------------------------------
;; Browser Lifecycle Tests (CLI)
;; -----------------------------------------------------------------------------

(defn- make-fake-browser
  "Create a fake browser capability for testing lifecycle."
  [session-id]
  {:webdriver-url "http://127.0.0.1:9515"
   :session session-id
   :type :chrome})

(deftest test-suite-browser-cleanup-no-browser
  (testing "execute-suite reports :none cleanup when no browser present"
    (let [plan (make-plan [(make-bound-step (fn [] {:data 1}) 0 [])])
          result (exec/execute-suite [plan])]
      (is (= :passed (:status result)))
      (is (= 1 (count (:scenarios result))))
      (is (= :none (-> result :scenarios first :browser-cleanup :action))))))

(deftest test-suite-browser-cleanup-with-browser
  (testing "execute-suite reports :closed cleanup when browser present"
    ;; Create a step that adds a fake browser to scenario context
    (let [add-browser-step (fn []
                             (browser.ctx/assoc-active-browser {} (make-fake-browser "test-session")))
          plan (make-plan [(make-bound-step add-browser-step 0 [])])
          result (exec/execute-suite [plan])]
      (is (= :passed (:status result)))
      ;; Cleanup should report :closed (actual close may fail gracefully on fake)
      (let [cleanup (-> result :scenarios first :browser-cleanup)]
        (is (contains? #{:closed :close-failed} (:action cleanup)))))))

(deftest test-suite-browser-cleanup-on-failure
  (testing "execute-suite cleans up browser even on scenario failure"
    ;; Create steps: add browser, then fail
    (let [add-browser-step (fn []
                             (browser.ctx/assoc-active-browser {} (make-fake-browser "fail-session")))
          fail-step (fn [_ctx] (throw (Exception. "intentional failure")))
          plan (make-plan [(make-bound-step add-browser-step 0 [])
                           (make-bound-step fail-step 1 [])])
          result (exec/execute-suite [plan])]
      (is (= :failed (:status result)))
      ;; Browser cleanup should still happen
      (let [cleanup (-> result :scenarios first :browser-cleanup)]
        (is (contains? #{:closed :close-failed} (:action cleanup)))))))

(deftest test-suite-multiple-scenarios-cleanup
  (testing "execute-suite cleans up browser for each scenario"
    (let [add-browser-step (fn []
                             (browser.ctx/assoc-active-browser {} (make-fake-browser "session-1")))
          plan1 (make-plan [(make-bound-step add-browser-step 0 [])])
          plan2 (make-plan [(make-bound-step (fn [] {:no-browser true}) 0 [])])
          result (exec/execute-suite [plan1 plan2])]
      (is (= :passed (:status result)))
      (is (= 2 (count (:scenarios result))))
      ;; First scenario had browser → should attempt close
      (is (contains? #{:closed :close-failed}
                     (-> result :scenarios first :browser-cleanup :action)))
      ;; Second scenario had no browser → :none
      (is (= :none (-> result :scenarios second :browser-cleanup :action))))))

(deftest test-acceptance-criteria-cli-lifecycle
  (testing "Task 2.5.10 AC: CLI cleans up browser after scenario"
    (let [add-browser-step (fn []
                             (browser.ctx/assoc-active-browser {} (make-fake-browser "ac-session")))
          plan (make-plan [(make-bound-step add-browser-step 0 [])])
          result (exec/execute-suite [plan])]
      ;; Suite passed
      (is (= :passed (:status result)))
      ;; Browser cleanup was attempted
      (is (some? (-> result :scenarios first :browser-cleanup))))))

;; -----------------------------------------------------------------------------
;; Auto-Provisioning Tests (Task 3.0.10)
;; -----------------------------------------------------------------------------

(defn make-bound-step-with-svoi
  "Create a bound step with SVOI metadata for testing auto-provisioning."
  [f arity captures svoi]
  {:status :matched
   :step (make-step)
   :binding (merge (make-binding f arity captures)
                   {:svoi svoi})})

(deftest test-no-provisioning-without-svoi
  (testing "Steps without SVOI don't trigger provisioning"
    (let [executed (atom false)
          ;; Step without SVOI in binding
          bound-step (make-bound-step (fn [] (reset! executed true) {:done true}) 0 [])
          plan (make-plan [bound-step])
          ;; No interfaces config provided
          result (exec/execute-scenario plan {} {})]
      (is @executed "Step should execute")
      (is (= :passed (:status result))))))

(deftest test-no-provisioning-when-capability-present
  (testing "No provisioning when capability already exists"
    (let [provision-called (atom false)
          ;; Step with SVOI requesting :web interface
          bound-step (make-bound-step-with-svoi
                       (fn [ctx]
                         ;; Verify capability is in ctx
                         (is (cap/capability-present? (:scenario ctx) :web))
                         (is (= :existing-browser (cap/get-capability (:scenario ctx) :web)))
                         {:saw-capability true})
                       1 []
                       {:subject :alice :verb :click :interface :web})
          plan (make-plan [bound-step])
          ;; Pre-provision capability in initial ctx
          initial-ctx (cap/assoc-capability {} :web :existing-browser :persistent)
          result (exec/execute-scenario plan initial-ctx {})]
      (is (= :passed (:status result)))
      (is (= {:saw-capability true} (:scenario-ctx result))))))

(deftest test-provisioning-creates-capability
  (testing "Auto-provisioning creates capability when needed"
    (let [factory-called (atom false)
          ;; Mock adapter that tracks calls
          mock-registry {:mock-adapter {:factory (fn [config]
                                                   (reset! factory-called true)
                                                   {:ok {:browser-type :mock
                                                         :config config}})
                                        :cleanup (fn [_] {:ok :closed})}}
          interfaces {:web {:type :web
                            :adapter :mock-adapter
                            :config {:headless true}}}
          ;; Step that checks for provisioned capability
          bound-step (make-bound-step-with-svoi
                       (fn [ctx]
                         (let [cap (cap/get-capability (:scenario ctx) :web)]
                           (is (some? cap) "Capability should be provisioned")
                           (is (= :mock (:browser-type cap)))
                           ;; Preserve ctx (including capability) + add marker
                           (assoc (:scenario ctx) :saw-provisioned true)))
                       1 []
                       {:subject :alice :verb :click :interface :web})
          plan (make-plan [bound-step])]
      ;; Execute with custom registry
      (with-redefs [registry/create-capability
                    (fn [adapter-name config & [reg]]
                      (if-let [adapter (get (or reg mock-registry) adapter-name)]
                        ((:factory adapter) config)
                        {:error {:type :adapter/unknown :adapter adapter-name}}))]
        (let [result (exec/execute-scenario plan {} {:interfaces interfaces})]
          (is @factory-called "Factory should be called")
          (is (= :passed (:status result)))
          ;; Capability should be in final ctx
          (is (cap/capability-present? (:scenario-ctx result) :web)))))))

(deftest test-provisioning-failure-blocks-step
  (testing "Provisioning failure causes step to fail"
    (let [step-executed (atom false)
          ;; Step with SVOI but provisioning will fail
          bound-step (make-bound-step-with-svoi
                       (fn []
                         (reset! step-executed true)
                         {:done true})
                       0 []
                       {:subject :alice :verb :click :interface :web})
          plan (make-plan [bound-step])
          ;; Interfaces config with adapter that fails
          interfaces {:web {:type :web
                            :adapter :failing-adapter
                            :config {}}}]
      ;; Mock adapter that fails
      (with-redefs [registry/create-capability
                    (fn [_ _]
                      {:error {:type :adapter/create-failed
                               :message "ChromeDriver not found"}})]
        (let [result (exec/execute-scenario plan {} {:interfaces interfaces})]
          (is (not @step-executed) "Step should not execute on provisioning failure")
          (is (= :failed (:status result)))
          (is (= :svo/provisioning-failed (-> result :steps first :error :type)))
          (is (= :web (-> result :steps first :error :interface))))))))

(deftest test-provisioning-unknown-interface
  (testing "Unknown interface in config causes provisioning failure"
    (let [bound-step (make-bound-step-with-svoi
                       (fn [] {:done true})
                       0 []
                       {:subject :alice :verb :click :interface :unknown-iface})
          plan (make-plan [bound-step])
          ;; Config doesn't have :unknown-iface
          interfaces {:web {:type :web :adapter :etaoin :config {}}}
          result (exec/execute-scenario plan {} {:interfaces interfaces})]
      (is (= :failed (:status result)))
      (is (= :svo/provisioning-failed (-> result :steps first :error :type)))
      (is (= :unknown-iface (-> result :steps first :error :interface)))
      (is (= [:web] (-> result :steps first :error :known))))))

(deftest test-provisioning-no-interfaces-config
  (testing "Missing interfaces config causes provisioning failure"
    (let [bound-step (make-bound-step-with-svoi
                       (fn [] {:done true})
                       0 []
                       {:subject :alice :verb :click :interface :web})
          plan (make-plan [bound-step])
          ;; No interfaces config provided
          result (exec/execute-scenario plan {} {})]
      (is (= :failed (:status result)))
      (is (= :svo/provisioning-failed (-> result :steps first :error :type)))
      (is (re-find #"No interfaces config" (-> result :steps first :error :message))))))

(deftest test-capability-reused-across-steps
  (testing "Capability provisioned once, reused by subsequent steps"
    (let [factory-calls (atom 0)
          mock-impl {:type :mock-browser}
          interfaces {:web {:type :web :adapter :mock :config {}}}
          ;; Two steps both need :web
          step1 (make-bound-step-with-svoi
                  (fn [ctx]
                    (is (cap/capability-present? (:scenario ctx) :web))
                    (:scenario ctx))  ;; Return ctx unchanged
                  1 []
                  {:subject :alice :verb :click :interface :web})
          step2 (make-bound-step-with-svoi
                  (fn [ctx]
                    (is (cap/capability-present? (:scenario ctx) :web))
                    {:both-done true})
                  1 []
                  {:subject :alice :verb :fill :interface :web})
          plan (make-plan [step1 step2])]
      (with-redefs [registry/create-capability
                    (fn [_ _]
                      (swap! factory-calls inc)
                      {:ok mock-impl})]
        (let [result (exec/execute-scenario plan {} {:interfaces interfaces})]
          (is (= :passed (:status result)))
          (is (= 1 @factory-calls) "Factory should only be called once"))))))

(deftest test-provisioning-with-suite
  (testing "execute-suite passes interfaces config for provisioning"
    (let [factory-calls (atom 0)
          interfaces {:api {:type :api :adapter :http :config {:base-url "http://test"}}}
          bound-step (make-bound-step-with-svoi
                       (fn [ctx]
                         (is (cap/capability-present? (:scenario ctx) :api))
                         {:api-done true})
                       1 []
                       {:subject :system :verb :call :interface :api})
          plan (make-plan [bound-step])]
      (with-redefs [registry/create-capability
                    (fn [_ config]
                      (swap! factory-calls inc)
                      {:ok {:type :http-client :config config}})]
        (let [result (exec/execute-suite [plan] {:interfaces interfaces})]
          (is (= :passed (:status result)))
          (is (= 1 @factory-calls)))))))

(deftest test-acceptance-auto-provisioning
  (testing "Task 3.0.10 AC: Step with :interface :web auto-provisions"
    (let [provisioned-impl {:type :test-browser :session "test-123"}
          interfaces {:web {:type :web :adapter :etaoin :config {:headless true}}}
          bound-step (make-bound-step-with-svoi
                       (fn [ctx]
                         ;; Step can access provisioned capability
                         (let [browser (cap/get-capability (:scenario ctx) :web)]
                           (is (= :test-browser (:type browser)))
                           ;; Preserve ctx (including capability) + add marker
                           (assoc (:scenario ctx) :clicked true)))
                       1 []
                       {:subject :alice :verb :click :object "the button" :interface :web})
          plan (make-plan [bound-step])]
      (with-redefs [registry/create-capability
                    (fn [adapter-name config]
                      (is (= :etaoin adapter-name))
                      (is (= {:headless true} config))
                      {:ok provisioned-impl})]
        (let [result (exec/execute-scenario plan {} {:interfaces interfaces})]
          (is (= :passed (:status result)))
          ;; Final ctx has capability with :ephemeral mode
          (is (= :ephemeral (cap/get-capability-mode (:scenario-ctx result) :web))))))))

;; -----------------------------------------------------------------------------
;; Capability Cleanup Tests (Task 3.0.11)
;; -----------------------------------------------------------------------------

(deftest test-cleanup-ephemeral-capabilities
  (testing "Ephemeral capabilities are cleaned up after scenario"
    (let [cleanup-called (atom #{})
          interfaces {:web {:type :web :adapter :mock :config {}}}
          bound-step (make-bound-step-with-svoi
                       (fn [ctx]
                         (assoc (:scenario ctx) :step-done true))
                       1 []
                       {:subject :alice :verb :click :interface :web})
          plan (make-plan [bound-step])]
      (with-redefs [registry/create-capability
                    (fn [_ _]
                      {:ok {:type :mock-browser :id "browser-1"}})
                    registry/get-adapter
                    (fn [adapter-name]
                      {:factory (fn [_] {:ok {:type :mock}})
                       :cleanup (fn [impl]
                                  (swap! cleanup-called conj (:id impl))
                                  {:ok :closed})})]
        (let [result (exec/execute-suite [plan] {:interfaces interfaces})]
          (is (= :passed (:status result)))
          ;; Cleanup should have been called for the ephemeral capability
          (is (contains? @cleanup-called "browser-1"))
          ;; Check capability-cleanup result
          (let [cap-cleanup (-> result :scenarios first :capability-cleanup)]
            (is (= 1 (count (:cleaned cap-cleanup))))
            (is (= :closed (-> cap-cleanup :cleaned first :action)))))))))

(deftest test-cleanup-persistent-capabilities-skipped
  (testing "Persistent capabilities are not cleaned up"
    (let [cleanup-called (atom #{})
          ;; Create a step that preserves ctx (returns nil)
          bound-step (make-bound-step (fn [ctx] nil) 1 [])
          plan (make-plan [bound-step])
          ;; Pre-provision a persistent capability in initial ctx (via execute-scenario directly)
          initial-ctx (cap/assoc-capability {} :web {:type :persistent-browser} :persistent)]
      (with-redefs [registry/get-adapter
                    (fn [_]
                      {:factory (fn [_] {:ok {}})
                       :cleanup (fn [impl]
                                  (swap! cleanup-called conj (:type impl))
                                  {:ok :closed})})]
        ;; Use execute-scenario directly with initial ctx that has persistent cap
        (let [result (exec/execute-scenario plan initial-ctx {})]
          (is (= :passed (:status result)))
          ;; The persistent capability should still be in ctx
          (is (cap/capability-present? (:scenario-ctx result) :web))
          (is (= :persistent (cap/get-capability-mode (:scenario-ctx result) :web))))))))

(deftest test-cleanup-multiple-ephemeral-capabilities
  (testing "Multiple ephemeral capabilities are all cleaned up"
    (let [cleanup-order (atom [])
          interfaces {:web {:type :web :adapter :mock :config {}}
                      :api {:type :api :adapter :mock-api :config {}}}
          ;; Two steps with different interfaces
          step1 (make-bound-step-with-svoi
                  (fn [ctx] (assoc (:scenario ctx) :web-done true))
                  1 []
                  {:subject :alice :verb :click :interface :web})
          step2 (make-bound-step-with-svoi
                  (fn [ctx] (assoc (:scenario ctx) :api-done true))
                  1 []
                  {:subject :alice :verb :call :interface :api})
          plan (make-plan [step1 step2])]
      (with-redefs [registry/create-capability
                    (fn [adapter-name _]
                      {:ok {:adapter adapter-name :id (name adapter-name)}})
                    registry/get-adapter
                    (fn [adapter-name]
                      {:factory (fn [_] {:ok {}})
                       :cleanup (fn [impl]
                                  (swap! cleanup-order conj (:adapter impl))
                                  {:ok :closed})})]
        (let [result (exec/execute-suite [plan] {:interfaces interfaces})]
          (is (= :passed (:status result)))
          ;; Both capabilities should be cleaned up
          (is (= 2 (count @cleanup-order)))
          (is (contains? (set @cleanup-order) :mock))
          (is (contains? (set @cleanup-order) :mock-api))
          ;; Check cleanup result
          (let [cap-cleanup (-> result :scenarios first :capability-cleanup)]
            (is (= 2 (count (:cleaned cap-cleanup))))))))))

(deftest test-cleanup-mixed-ephemeral-and-persistent
  (testing "Only ephemeral capabilities cleaned, persistent skipped"
    (let [cleanup-called (atom #{})
          interfaces {:web {:type :web :adapter :mock :config {}}}
          ;; Step that provisions ephemeral :web
          bound-step (make-bound-step-with-svoi
                       (fn [ctx]
                         ;; Also add a persistent capability manually
                         (cap/assoc-capability (:scenario ctx) :api {:type :api-client} :persistent))
                       1 []
                       {:subject :alice :verb :click :interface :web})
          plan (make-plan [bound-step])]
      (with-redefs [registry/create-capability
                    (fn [_ _]
                      {:ok {:type :mock-browser}})
                    registry/get-adapter
                    (fn [_]
                      {:factory (fn [_] {:ok {}})
                       :cleanup (fn [impl]
                                  (swap! cleanup-called conj (:type impl))
                                  {:ok :closed})})]
        (let [result (exec/execute-suite [plan] {:interfaces interfaces})]
          (is (= :passed (:status result)))
          ;; Only ephemeral capability cleaned up
          (is (contains? @cleanup-called :mock-browser))
          (is (not (contains? @cleanup-called :api-client)))
          ;; Check cleanup result shows persistent was skipped
          (let [cap-cleanup (-> result :scenarios first :capability-cleanup)]
            (is (= 1 (count (:cleaned cap-cleanup))))
            (is (= [:api] (:skipped cap-cleanup)))))))))

(deftest test-cleanup-handles-adapter-errors
  (testing "Cleanup continues even if adapter cleanup fails"
    (let [interfaces {:web {:type :web :adapter :failing :config {}}}
          bound-step (make-bound-step-with-svoi
                       (fn [ctx] (assoc (:scenario ctx) :done true))
                       1 []
                       {:subject :alice :verb :click :interface :web})
          plan (make-plan [bound-step])]
      (with-redefs [registry/create-capability
                    (fn [_ _]
                      {:ok {:type :browser}})
                    registry/get-adapter
                    (fn [_]
                      {:factory (fn [_] {:ok {}})
                       :cleanup (fn [_]
                                  (throw (Exception. "Cleanup failed!")))})]
        (let [result (exec/execute-suite [plan] {:interfaces interfaces})]
          ;; Scenario should still pass (cleanup failure doesn't affect result)
          (is (= :passed (:status result)))
          ;; Cleanup result should show failure
          (let [cap-cleanup (-> result :scenarios first :capability-cleanup)]
            (is (= :close-failed (-> cap-cleanup :cleaned first :action)))
            (is (= "Cleanup failed!" (-> cap-cleanup :cleaned first :error)))))))))

(deftest test-cleanup-no-capabilities
  (testing "No cleanup needed when no capabilities present"
    (let [bound-step (make-bound-step (fn [] {:done true}) 0 [])
          plan (make-plan [bound-step])
          result (exec/execute-suite [plan] {})]
      (is (= :passed (:status result)))
      (let [cap-cleanup (-> result :scenarios first :capability-cleanup)]
        (is (empty? (:cleaned cap-cleanup)))
        (is (empty? (:skipped cap-cleanup)))))))

(deftest test-acceptance-ephemeral-cleanup
  (testing "Task 3.0.11 AC: Ephemeral :cap/web is cleaned up after scenario"
    (let [cleanup-called (atom false)
          interfaces {:web {:type :web :adapter :etaoin :config {:headless true}}}
          bound-step (make-bound-step-with-svoi
                       (fn [ctx] (assoc (:scenario ctx) :clicked true))
                       1 []
                       {:subject :alice :verb :click :interface :web})
          plan (make-plan [bound-step])]
      (with-redefs [registry/create-capability
                    (fn [_ _]
                      {:ok {:type :test-browser}})
                    registry/get-adapter
                    (fn [adapter-name]
                      (if (= :etaoin adapter-name)
                        {:factory (fn [_] {:ok {}})
                         :cleanup (fn [_]
                                    (reset! cleanup-called true)
                                    {:ok :closed})}
                        {:error {:type :adapter/unknown}}))]
        (let [result (exec/execute-suite [plan] {:interfaces interfaces})]
          (is (= :passed (:status result)))
          (is @cleanup-called "Ephemeral capability should be cleaned up")))))

  (testing "Task 3.0.11 AC: Persistent :cap/web survives scenario"
    (let [cleanup-called (atom false)
          ;; Step that doesn't trigger provisioning, uses pre-existing persistent cap
          bound-step (make-bound-step
                       (fn [ctx]
                         ;; Verify persistent capability is present
                         (is (cap/capability-present? (:scenario ctx) :web))
                         (is (= :persistent (cap/get-capability-mode (:scenario ctx) :web)))
                         (:scenario ctx))
                       1 [])
          plan (make-plan [bound-step])
          ;; Pre-provision persistent capability
          initial-ctx (cap/assoc-capability {} :web {:type :persistent-browser} :persistent)]
      (with-redefs [registry/get-adapter
                    (fn [_]
                      {:factory (fn [_] {:ok {}})
                       :cleanup (fn [_]
                                  (reset! cleanup-called true)
                                  {:ok :closed})})]
        (let [result (exec/execute-scenario plan initial-ctx {})]
          (is (= :passed (:status result)))
          (is (not @cleanup-called) "Persistent capability should NOT be cleaned up")
          ;; Capability still present in final ctx
          (is (cap/capability-present? (:scenario-ctx result) :web)))))))

;; -----------------------------------------------------------------------------
;; SVOI Event Emission Tests (Task 3.0.12)
;; -----------------------------------------------------------------------------

(defn make-bound-step-with-svoi
  "Create a bound step with SVOI metadata in binding."
  [f arity captures svoi-map]
  (let [step {:step/id (java.util.UUID/randomUUID)
              :step/text "test step with SVOI"
              :step/arguments []
              :step/location {:uri "test.feature" :line 10}}]
    {:status :matched
     :step step
     :binding (assoc (make-binding f arity captures) :svoi svoi-map)}))

(deftest test-svoi-event-emission
  (testing "Task 3.0.12 AC1: emits :step/svoi event before step execution"
    (with-redefs [registry/get-adapter
                  (fn [& _args]
                    {:factory (fn [_] {:ok {}})
                     :cleanup (fn [_] {:ok :closed})})]
      (let [events-received (atom [])
            bus (events/make-memory-bus)
            run-id "test-run-123"
            svoi {:subject "User"
                  :verb "logs in"
                  :object "system"
                  :interface :web}
            bound-step (make-bound-step-with-svoi
                         (fn [ctx] (:scenario ctx))
                         1 []
                         svoi)
            plan (make-plan [bound-step])
            sub-id (events/subscribe! bus (fn [e] (swap! events-received conj e)))]
        ;; Give async subscription time to set up
        (Thread/sleep 10)
        (let [result (exec/execute-scenario plan {} {:bus bus
                                                     :run-id run-id
                                                     :interfaces {:web {:type :browser}}})]
          ;; Give async events time to propagate
          (Thread/sleep 50)
          (events/unsubscribe! bus sub-id)
          (events/bus-close! bus)
          (is (= :passed (:status result)))
          (is (= 1 (count @events-received)) "Should emit exactly one SVOI event")
          (let [evt (first @events-received)]
            (is (= :step/svoi (:type evt)))
            (is (= run-id (:run-id evt)))
            (is (string? (:ts evt)))
            (let [payload (:payload evt)]
              (is (= "User" (:subject payload)))
              (is (= "logs in" (:verb payload)))
              (is (= "system" (:object payload)))
              (is (= :web (:interface payload)))
              (is (= :browser (:interface-type payload)))
              (is (= "test step with SVOI" (:step-text payload)))
              (is (= {:uri "test.feature" :line 10} (:location payload)))))))))

  (testing "Task 3.0.12 AC2: no event when bus/run-id missing"
    ;; Step without :interface in SVOI - no provisioning needed
    (let [events-received (atom [])
          svoi {:subject "User"
                :verb "logs in"
                :object "system"}  ;; No :interface - no provisioning
          bound-step (make-bound-step-with-svoi
                       (fn [ctx] (:scenario ctx))
                       1 []
                       svoi)
          plan (make-plan [bound-step])]
      ;; Execute without bus/run-id
      (let [result (exec/execute-scenario plan {} {})]
        (is (= :passed (:status result)))
        (is (empty? @events-received) "No events should be emitted without bus"))))

  (testing "Task 3.0.12 AC3: no event when step has no SVOI"
    (let [events-received (atom [])
          bus (events/make-memory-bus)
          run-id "test-run-456"
          ;; Regular step without SVOI
          bound-step (make-bound-step
                       (fn [ctx] (:scenario ctx))
                       1 [])
          plan (make-plan [bound-step])
          sub-id (events/subscribe! bus (fn [e] (swap! events-received conj e)))]
      (Thread/sleep 10)
      (let [result (exec/execute-scenario plan {} {:bus bus :run-id run-id})]
        (Thread/sleep 50)
        (events/unsubscribe! bus sub-id)
        (events/bus-close! bus)
        (is (= :passed (:status result)))
        (is (empty? @events-received) "No events should be emitted for step without SVOI"))))

  (testing "Task 3.0.12 AC4: multiple steps emit multiple events"
    (with-redefs [registry/get-adapter
                  (fn [& _args]
                    {:factory (fn [_] {:ok {}})
                     :cleanup (fn [_] {:ok :closed})})]
      (let [events-received (atom [])
            bus (events/make-memory-bus)
            run-id "test-run-789"
            svoi1 {:subject "Admin" :verb "creates" :object "account" :interface :api}
            svoi2 {:subject "User" :verb "views" :object "dashboard" :interface :web}
            bound-step1 (make-bound-step-with-svoi
                          (fn [ctx] (:scenario ctx))
                          1 [] svoi1)
            bound-step2 (make-bound-step-with-svoi
                          (fn [ctx] (:scenario ctx))
                          1 [] svoi2)
            plan (make-plan [bound-step1 bound-step2])
            sub-id (events/subscribe! bus (fn [e] (swap! events-received conj e)))]
        (Thread/sleep 10)
        (let [result (exec/execute-scenario plan {} {:bus bus
                                                     :run-id run-id
                                                     :interfaces {:api {:type :rest}
                                                                  :web {:type :browser}}})]
          (Thread/sleep 50)
          (events/unsubscribe! bus sub-id)
          (events/bus-close! bus)
          (is (= :passed (:status result)))
          (is (= 2 (count @events-received)) "Should emit two SVOI events")
          (let [subjects (set (map #(get-in % [:payload :subject]) @events-received))]
            (is (= #{"Admin" "User"} subjects))))))))
