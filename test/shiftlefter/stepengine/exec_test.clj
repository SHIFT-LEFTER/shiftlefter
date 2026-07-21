(ns shiftlefter.stepengine.exec-test
  (:require [clojure.test :refer [deftest is testing]]
            [shiftlefter.stepengine.exec :as exec]
            [shiftlefter.browser.protocol :as bp]
            [shiftlefter.capabilities.ctx :as cap]
            [shiftlefter.costume :as costume]
            [shiftlefter.runner.events :as events]
            [shiftlefter.sms.adapter-hooks :as sms-hooks]
            [shiftlefter.test-helpers.adapter-registry :as mock]))

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
  (testing "A == C+1: call with ctx first, then captures"
    (let [f (fn [ctx n]
              (update ctx :count + (Integer/parseInt n)))
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
  (testing "Docstring accessible via ctx metadata"
    (let [docstring {:content "Hello\nWorld" :mediaType "text/plain"}
          step (make-step "I see docstring" docstring)
          f (fn [ctx]
              ;; Step arguments now in metadata
              (is (= docstring (:step/arguments (meta ctx))))
              {:saw-docstring true})
          binding (make-binding f 1)
          ctx {:step step :scenario {}}
          result (exec/invoke-step binding [] ctx)]
      (is (= :passed (:status result)))
      (is (= {:saw-docstring true} (:scenario result))))))

(deftest test-invoke-datatable-access
  (testing "DataTable accessible via ctx metadata"
    (let [table {:rows [["name" "age"] ["alice" "30"]]}
          step (make-step "I see table" table)
          f (fn [ctx]
              ;; Step arguments now in metadata
              (is (= table (:step/arguments (meta ctx))))
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
                           (make-bound-step (fn [ctx] (assoc ctx :b 2)) 1 [])
                           (make-bound-step (fn [ctx] (assoc ctx :c 3)) 1 [])])
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
                           (make-bound-step (fn [ctx] (update ctx :count inc)) 1 [])
                           (make-bound-step (fn [ctx] (update ctx :count inc)) 1 [])])
          result (exec/execute-scenario plan {})]
      (is (= :passed (:status result)))
      (is (= {:count 3} (:scenario-ctx result))))))

(deftest test-run-interfaces-stashed-in-scenario-ctx
  (testing "opts :interfaces is stashed into scenario ctx for step bodies (sl-3jr4)"
    (let [interfaces {:web {:type :web :adapter :etaoin
                            :config {:base-url "http://localhost:9092"}}}
          seen (atom nil)
          plan (make-plan [(make-bound-step
                            (fn [ctx]
                              (reset! seen (cap/get-run-interface-config ctx :web))
                              ctx)
                            1 [])])
          result (exec/execute-scenario plan {} {:interfaces interfaces})]
      (is (= :passed (:status result)))
      (is (= {:base-url "http://localhost:9092"} @seen)
          "Step body reads the interface :config from ctx")
      (is (= interfaces (:run/interfaces (:scenario-ctx result))))))

  (testing "No :interfaces opts → no stash (bare/REPL invoke path)"
    (let [plan (make-plan [(make-bound-step (fn [ctx] ctx) 1 [])])
          result (exec/execute-scenario plan {})]
      (is (nil? (:run/interfaces (:scenario-ctx result)))))))

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
  (testing "Spec: ctx-aware step can read docstring via metadata"
    (let [f (fn [ctx]
              (is (= {:content "x" :mediaType "text/plain"}
                     (:step/arguments (meta ctx))))
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

;; Stub web adapter for cleanup-orchestration tests below. The fake browser
;; map (`make-fake-browser`) never makes a real WebDriver call — we just
;; need cleanup to be invoked so :capability-cleanup is populated.
(def ^:private stub-web-interfaces
  {:web {:type :web :adapter :stub-web :config {}}})

(def ^:private stub-web-registry
  "Mock registry providing a no-op :stub-web adapter. Pass via
   `:adapter-registry` alongside `stub-web-interfaces`."
  (mock/registry {:stub-web {}}))

(deftest test-suite-browser-cleanup-no-browser
  (testing "execute-suite reports empty :cleaned when no capability present"
    (let [plan (make-plan [(make-bound-step (fn [] {:data 1}) 0 [])])
          result (exec/execute-suite [plan])]
      (is (= :passed (:status result)))
      (is (= 1 (count (:scenarios result))))
      (let [cap-cleanup (-> result :scenarios first :capability-cleanup)]
        (is (empty? (:cleaned cap-cleanup)))))))

(deftest test-suite-browser-cleanup-with-browser
  (testing "execute-suite cleans up :web ephemeral when browser present"
    ;; Step seeds a :web ephemeral capability into scenario ctx.
    (let [add-browser-step (fn []
                             (cap/assoc-capability {} :web (make-fake-browser "test-session") :ephemeral))
          plan (make-plan [(make-bound-step add-browser-step 0 [])])
          result (exec/execute-suite [plan] {:interfaces stub-web-interfaces
                                             :adapter-registry stub-web-registry})]
      (is (= :passed (:status result)))
      (let [cap-cleanup (-> result :scenarios first :capability-cleanup)]
        (is (= 1 (count (:cleaned cap-cleanup))))
        (is (contains? #{:closed :close-failed}
                       (-> cap-cleanup :cleaned first :action)))))))

(deftest test-suite-browser-cleanup-on-failure
  (testing "execute-suite cleans up capability even on scenario failure"
    (let [add-browser-step (fn []
                             (cap/assoc-capability {} :web (make-fake-browser "fail-session") :ephemeral))
          fail-step (fn [_ctx] (throw (Exception. "intentional failure")))
          plan (make-plan [(make-bound-step add-browser-step 0 [])
                           (make-bound-step fail-step 1 [])])
          result (exec/execute-suite [plan] {:interfaces stub-web-interfaces
                                             :adapter-registry stub-web-registry})]
      (is (= :failed (:status result)))
      (let [cap-cleanup (-> result :scenarios first :capability-cleanup)]
        (is (= 1 (count (:cleaned cap-cleanup))))
        (is (contains? #{:closed :close-failed}
                       (-> cap-cleanup :cleaned first :action)))))))

(deftest test-suite-multiple-scenarios-cleanup
  (testing "execute-suite cleans up capability for each scenario"
    (let [add-browser-step (fn []
                             (cap/assoc-capability {} :web (make-fake-browser "session-1") :ephemeral))
          plan1 (make-plan [(make-bound-step add-browser-step 0 [])])
          plan2 (make-plan [(make-bound-step (fn [] {:no-browser true}) 0 [])])
          result (exec/execute-suite [plan1 plan2] {:interfaces stub-web-interfaces
                                                    :adapter-registry stub-web-registry})]
      (is (= :passed (:status result)))
      (is (= 2 (count (:scenarios result))))
      ;; First scenario had a :web cap → should clean it up
      (is (contains? #{:closed :close-failed}
                     (-> result :scenarios first :capability-cleanup :cleaned first :action)))
      ;; Second scenario had no caps → empty :cleaned
      (is (empty? (-> result :scenarios second :capability-cleanup :cleaned))))))

(deftest test-acceptance-criteria-cli-lifecycle
  (testing "Task 2.5.10 AC: CLI cleans up capability after scenario"
    (let [add-browser-step (fn []
                             (cap/assoc-capability {} :web (make-fake-browser "ac-session") :ephemeral))
          plan (make-plan [(make-bound-step add-browser-step 0 [])])
          result (exec/execute-suite [plan] {:interfaces stub-web-interfaces
                                             :adapter-registry stub-web-registry})]
      (is (= :passed (:status result)))
      ;; Capability cleanup was attempted (either :closed or :close-failed)
      (is (= 1 (count (-> result :scenarios first :capability-cleanup :cleaned)))))))

;; -----------------------------------------------------------------------------
;; Auto-Provisioning Tests (Task 3.0.10)
;; -----------------------------------------------------------------------------

(defn make-bound-step-with-svo
  "Create a bound step with SVO metadata for testing auto-provisioning.
   Includes step location data for SVO event emission tests."
  ([f arity captures svo]
   (make-bound-step-with-svo f arity captures svo "test step with SVO"))
  ([f arity captures svo step-text]
   {:status :matched
    :step {:step/id (java.util.UUID/randomUUID)
           :step/text step-text
           :step/arguments []
           :step/location {:uri "test.feature" :line 10}}
    :binding (merge (make-binding f arity captures)
                    {:svo svo})}))

(deftest test-no-provisioning-without-svo
  (testing "Steps without SVO don't trigger provisioning"
    (let [executed (atom false)
          ;; Step without SVO in binding
          bound-step (make-bound-step (fn [] (reset! executed true) {:done true}) 0 [])
          plan (make-plan [bound-step])
          ;; No interfaces config provided
          result (exec/execute-scenario plan {} {})]
      (is @executed "Step should execute")
      (is (= :passed (:status result))))))

(deftest test-no-provisioning-when-capability-present
  (testing "No provisioning when capability already exists for subject"
    (let [_provision-called (atom false)
          ;; Step with SVO requesting :web interface for :alice
          bound-step (make-bound-step-with-svo
                       (fn [ctx]
                         ;; Verify subject-keyed capability is in ctx
                         (is (cap/capability-present? ctx :web :alice))
                         (is (= :existing-browser (cap/get-capability ctx :web :alice)))
                         {:saw-capability true})
                       1 []
                       {:subject :alice :verb :click :interface :web})
          plan (make-plan [bound-step])
          ;; Pre-provision capability for :alice subject
          initial-ctx (cap/assoc-capability {} :web :existing-browser :persistent :alice)
          result (exec/execute-scenario plan initial-ctx {})]
      (is (= :passed (:status result)))
      (is (= {:saw-capability true} (:scenario-ctx result))))))

(deftest test-provisioning-creates-capability
  (testing "Auto-provisioning creates subject-keyed capability when needed"
    (let [events (atom [])
          registry (mock/registry {:mock-adapter {:impl   {:browser-type :mock}
                                                  :events events}})
          interfaces (mock/interfaces {:web {:adapter :mock-adapter
                                             :config  {:headless true}}})
          ;; Step that checks for provisioned capability (subject-keyed)
          bound-step (make-bound-step-with-svo
                       (fn [ctx]
                         (let [cap (cap/get-capability ctx :web :alice)]
                           (is (some? cap) "Capability should be provisioned for :alice")
                           (is (= :mock (:browser-type cap)))
                           ;; Preserve ctx (including capability) + add marker
                           (assoc ctx :saw-provisioned true)))
                       1 []
                       {:subject :alice :verb :click :interface :web})
          plan (make-plan [bound-step])
          result (exec/execute-scenario plan {} {:interfaces interfaces
                                                 :adapter-registry registry})]
      (is (seq @events) "Factory should be called")
      (is (= :passed (:status result)))
      ;; Subject-keyed capability should be in final ctx
      (is (cap/capability-present? (:scenario-ctx result) :web :alice)))))

(deftest test-qualified-subject-session-keyed-by-instance
  ;; Regression for sl-t55. A qualified subject like :user/alice must be
  ;; provisioned such that its capability is keyed by the instance
  ;; (:alice) — matching how runtime step routing looks it up. Before the
  ;; fix, provisioning stored the cap under :user/alice and every
  ;; subsequent step lookup missed. cap/capability-key normalizes via
  ;; (name subject), so :user/alice → :cap/web.alice.
  (testing "Qualified subject :user/alice provisions :cap/web.alice"
    (let [mock-cap {:impl :fake :driver :chromedriver-stub}
          registry (mock/registry {:mock-adapter {:impl mock-cap}})
          interfaces (mock/interfaces {:web {:adapter :mock-adapter}})
          captured-ctx (atom nil)
          bound-step (make-bound-step-with-svo
                       (fn [ctx]
                         (reset! captured-ctx ctx)
                         (assoc ctx :saw true))
                       1 []
                       {:subject :user/alice :verb :navigate :interface :web})
          plan (make-plan [bound-step])
          result (exec/execute-scenario plan {} {:interfaces interfaces
                                                 :adapter-registry registry})
          ctx (or @captured-ctx (:scenario-ctx result))]
      (is (= :passed (:status result)))
      (is (= mock-cap (cap/get-capability ctx :web :alice))
          "Capability should be stored under :alice (instance)")
      ;; Both :alice and :user/alice route to :cap/web.alice via
      ;; (name subject) normalization in cap/capability-key — that
      ;; equivalence IS the regression fix. Legacy shape stored under
      ;; the literal key and could miss; unified shape can't.
      (is (= mock-cap (cap/get-capability ctx :web :user/alice))
          "Qualified :user/alice resolves to the same instance-keyed slot"))))

(deftest test-provisioning-failure-blocks-step
  (testing "Provisioning failure causes step to fail"
    (let [step-executed (atom false)
          ;; Step with SVO but provisioning will fail
          bound-step (make-bound-step-with-svo
                       (fn []
                         (reset! step-executed true)
                         {:done true})
                       0 []
                       {:subject :alice :verb :click :interface :web})
          plan (make-plan [bound-step])
          interfaces (mock/interfaces {:web {:adapter :failing-adapter}})
          registry (mock/registry {:failing-adapter
                                   {:fail? true
                                    :error {:type    :adapter/create-failed
                                            :message "ChromeDriver not found"}}})
          result (exec/execute-scenario plan {} {:interfaces interfaces
                                                 :adapter-registry registry})]
      (is (not @step-executed) "Step should not execute on provisioning failure")
      (is (= :failed (:status result)))
      (is (= :svo/provisioning-failed (-> result :steps first :error :type)))
      (is (= :web (-> result :steps first :error :interface))))))

(deftest test-provisioning-unknown-interface
  (testing "Unknown interface in config causes provisioning failure"
    (let [bound-step (make-bound-step-with-svo
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

(deftest test-provisioning-no-interfaces-config-skips-gracefully
  (testing "Missing interfaces config skips provisioning gracefully"
    (let [bound-step (make-bound-step-with-svo
                       (fn [] {:done true})
                       0 []
                       {:subject :alice :verb :click :interface :web})
          plan (make-plan [bound-step])
          ;; No interfaces config provided — step still runs
          result (exec/execute-scenario plan {} {})]
      (is (= :passed (:status result)))
      (is (= {:done true} (:scenario-ctx result))))))

(deftest test-capability-reused-across-steps
  (testing "Capability provisioned once, reused by subsequent steps with same subject"
    (let [events (atom [])
          mock-impl {:type :mock-browser}
          interfaces (mock/interfaces {:web {:adapter :mock}})
          registry (mock/registry {:mock {:impl mock-impl :events events}})
          ;; Two steps both need :web for :alice
          step1 (make-bound-step-with-svo
                  (fn [ctx]
                    (is (cap/capability-present? ctx :web :alice))
                    ctx)  ;; Return ctx unchanged
                  1 []
                  {:subject :alice :verb :click :interface :web})
          step2 (make-bound-step-with-svo
                  (fn [ctx]
                    (is (cap/capability-present? ctx :web :alice))
                    {:both-done true})
                  1 []
                  {:subject :alice :verb :fill :interface :web})
          plan (make-plan [step1 step2])
          result (exec/execute-scenario plan {} {:interfaces interfaces
                                                 :adapter-registry registry})]
      (is (= :passed (:status result)))
      (is (= 1 (count (filter #(= :provision (first %)) @events)))
          "Factory should only be called once"))))

(deftest test-provisioning-with-suite
  (testing "execute-suite passes interfaces config for provisioning"
    (let [events (atom [])
          interfaces (mock/interfaces {:api {:adapter :http
                                             :config  {:base-url "http://test"}}})
          registry (mock/registry {:http {:impl   {:type :http-client}
                                          :events events}})
          bound-step (make-bound-step-with-svo
                       (fn [ctx]
                         (is (cap/capability-present? ctx :api :system))
                         {:api-done true})
                       1 []
                       {:subject :system :verb :call :interface :api})
          plan (make-plan [bound-step])
          result (exec/execute-suite [plan] {:interfaces       interfaces
                                             :adapter-registry registry})]
      (is (= :passed (:status result)))
      (is (= 1 (count (filter #(= :provision (first %)) @events)))))))

(deftest test-acceptance-auto-provisioning
  (testing "Task 3.0.10 AC: Step with :interface :web auto-provisions for subject"
    (let [provisioned-impl {:type :test-browser :session "test-123"}
          interfaces (mock/interfaces {:web {:adapter :etaoin
                                             :config  {:headless true}}})
          ;; Factory returns {:browser <impl>} — adapter/interface wiring is
          ;; verified by the resulting capability shape, not by inside-factory
          ;; assertions (those were redundant with the post-hoc cap check).
          registry (mock/registry {:etaoin {:impl {:browser provisioned-impl}}})
          bound-step (make-bound-step-with-svo
                       (fn [ctx]
                         ;; Step can access provisioned subject-keyed capability
                         (let [cap-impl (cap/get-capability ctx :web :alice)]
                           (is (= :test-browser (-> cap-impl :browser :type)))
                           ;; Preserve ctx (including capability) + add marker
                           (assoc ctx :clicked true)))
                       1 []
                       {:subject :alice :verb :click :object "the button" :interface :web})
          plan (make-plan [bound-step])
          result (exec/execute-scenario plan {} {:interfaces       interfaces
                                                 :adapter-registry registry})]
      (is (= :passed (:status result)))
      ;; Final ctx has subject-keyed capability with :ephemeral mode
      (let [final-ctx (:scenario-ctx result)]
        (is (cap/capability-present? final-ctx :web :alice))
        ;; Subject-keyed capability is the unified storage.
        (is (= :test-browser (-> (cap/get-capability final-ctx :web :alice)
                                 :browser :type)))))))

;; -----------------------------------------------------------------------------
;; Capability Cleanup Tests (Task 3.0.11)
;; -----------------------------------------------------------------------------

(deftest test-cleanup-ephemeral-capabilities
  (testing "Ephemeral subject-keyed capabilities are cleaned up after scenario"
    (let [cleanup-called (atom #{})
          interfaces (mock/interfaces {:web {:adapter :mock}})
          registry (mock/registry {:mock {:impl    {:type :mock-browser :id "browser-1"}
                                          :cleanup (fn [impl]
                                                     (swap! cleanup-called conj (:id impl))
                                                     {:ok :closed})}})
          bound-step (make-bound-step-with-svo
                       (fn [ctx]
                         (assoc ctx :step-done true))
                       1 []
                       {:subject :alice :verb :click :interface :web})
          plan (make-plan [bound-step])
          result (exec/execute-suite [plan] {:interfaces       interfaces
                                             :adapter-registry registry})]
      (is (= :passed (:status result)))
      ;; Cleanup should have been called for the ephemeral capability
      (is (contains? @cleanup-called "browser-1"))
      ;; Check capability-cleanup result
      (let [cap-cleanup (-> result :scenarios first :capability-cleanup)]
        (is (= 1 (count (:cleaned cap-cleanup))))
        (is (= :closed (-> cap-cleanup :cleaned first :action)))))))

(deftest test-cleanup-persistent-capabilities-skipped
  (testing "Persistent capabilities are not cleaned up"
    (let [cleanup-called (atom #{})
          ;; Create a step that preserves ctx (returns nil)
          bound-step (make-bound-step (fn [_ctx] nil) 1 [])
          plan (make-plan [bound-step])
          ;; Pre-provision a persistent capability in initial ctx (via execute-scenario directly)
          initial-ctx (cap/assoc-capability {} :web {:type :persistent-browser} :persistent)
          ;; Registry exists but should not be consulted for cleanup; persistent
          ;; capabilities stay in ctx across scenarios. If it ever IS called,
          ;; the assertion below catches the regression.
          registry (mock/registry {:any {:cleanup (fn [impl]
                                                    (swap! cleanup-called conj (:type impl))
                                                    {:ok :closed})}})
          result (exec/execute-scenario plan initial-ctx {:adapter-registry registry})]
      (is (= :passed (:status result)))
      ;; The persistent capability should still be in ctx
      (is (cap/capability-present? (:scenario-ctx result) :web))
      (is (= :persistent (cap/get-capability-mode (:scenario-ctx result) :web))))))

(deftest test-cleanup-multiple-ephemeral-capabilities
  (testing "Multiple ephemeral subject-keyed capabilities are all cleaned up"
    (let [cleanup-order (atom [])
          interfaces (mock/interfaces {:web {:adapter :mock}
                                       :api {:adapter :mock-api}})
          registry (mock/registry {:mock     {:impl    {:adapter :mock :id "mock"}
                                              :cleanup (fn [impl]
                                                         (swap! cleanup-order conj (:adapter impl))
                                                         {:ok :closed})}
                                   :mock-api {:impl    {:adapter :mock-api :id "mock-api"}
                                              :cleanup (fn [impl]
                                                         (swap! cleanup-order conj (:adapter impl))
                                                         {:ok :closed})}})
          ;; Two steps with different interfaces, same subject
          step1 (make-bound-step-with-svo
                  (fn [ctx] (assoc ctx :web-done true))
                  1 []
                  {:subject :alice :verb :click :interface :web})
          step2 (make-bound-step-with-svo
                  (fn [ctx] (assoc ctx :api-done true))
                  1 []
                  {:subject :alice :verb :call :interface :api})
          plan (make-plan [step1 step2])
          result (exec/execute-suite [plan] {:interfaces       interfaces
                                             :adapter-registry registry})]
      (is (= :passed (:status result)))
      ;; Both capabilities should be cleaned up
      (is (= 2 (count @cleanup-order)))
      (is (contains? (set @cleanup-order) :mock))
      (is (contains? (set @cleanup-order) :mock-api))
      ;; Check cleanup result
      (let [cap-cleanup (-> result :scenarios first :capability-cleanup)]
        (is (= 2 (count (:cleaned cap-cleanup))))))))

(deftest test-cleanup-mixed-ephemeral-and-persistent
  (testing "Only ephemeral capabilities cleaned, persistent skipped"
    (let [cleanup-called (atom #{})
          interfaces (mock/interfaces {:web {:adapter :mock}})
          registry (mock/registry {:mock {:impl    {:type :mock-browser}
                                          :cleanup (fn [impl]
                                                     (swap! cleanup-called conj (:type impl))
                                                     {:ok :closed})}})
          ;; Step that provisions ephemeral :web (subject-keyed as :web.alice)
          bound-step (make-bound-step-with-svo
                       (fn [ctx]
                         ;; Also add a persistent capability manually (no subject)
                         (cap/assoc-capability ctx :api {:type :api-client} :persistent))
                       1 []
                       {:subject :alice :verb :click :interface :web})
          plan (make-plan [bound-step])
          result (exec/execute-suite [plan] {:interfaces       interfaces
                                             :adapter-registry registry})]
      (is (= :passed (:status result)))
      ;; Only ephemeral capability cleaned up
      (is (contains? @cleanup-called :mock-browser))
      (is (not (contains? @cleanup-called :api-client)))
      ;; Check cleanup result shows persistent was skipped
      (let [cap-cleanup (-> result :scenarios first :capability-cleanup)]
        (is (= 1 (count (:cleaned cap-cleanup))))
        (is (= [:api] (:skipped cap-cleanup)))))))

(deftest test-cleanup-handles-adapter-errors
  (testing "Cleanup continues even if adapter cleanup fails"
    (let [interfaces (mock/interfaces {:web {:adapter :failing}})
          registry (mock/registry {:failing {:impl    {:type :browser}
                                             :cleanup (fn [_]
                                                        (throw (Exception. "Cleanup failed!")))}})
          bound-step (make-bound-step-with-svo
                       (fn [ctx] (assoc ctx :done true))
                       1 []
                       {:subject :alice :verb :click :interface :web})
          plan (make-plan [bound-step])
          result (exec/execute-suite [plan] {:interfaces       interfaces
                                             :adapter-registry registry})]
      ;; Scenario should still pass (cleanup failure doesn't affect result)
      (is (= :passed (:status result)))
      ;; Cleanup result should show failure
      (let [cap-cleanup (-> result :scenarios first :capability-cleanup)]
        (is (= :close-failed (-> cap-cleanup :cleaned first :action)))
        (is (string? (-> cap-cleanup :cleaned first :error)))))))

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
  (testing "Task 3.0.11 AC: Ephemeral :cap/web.alice is cleaned up after scenario"
    (let [cleanup-called (atom false)
          interfaces (mock/interfaces {:web {:adapter :etaoin
                                             :config  {:headless true}}})
          registry (mock/registry {:etaoin {:impl    {:type :test-browser}
                                            :cleanup (fn [_]
                                                       (reset! cleanup-called true)
                                                       {:ok :closed})}})
          bound-step (make-bound-step-with-svo
                       (fn [ctx] (assoc ctx :clicked true))
                       1 []
                       {:subject :alice :verb :click :interface :web})
          plan (make-plan [bound-step])
          result (exec/execute-suite [plan] {:interfaces       interfaces
                                             :adapter-registry registry})]
      (is (= :passed (:status result)))
      (is @cleanup-called "Ephemeral capability should be cleaned up")))

  (testing "Task 3.0.11 AC: Persistent :cap/web survives scenario"
    (let [cleanup-called (atom false)
          ;; Step that doesn't trigger provisioning, uses pre-existing persistent cap
          bound-step (make-bound-step
                       (fn [ctx]
                         ;; Verify persistent capability is present
                         (is (cap/capability-present? ctx :web))
                         (is (= :persistent (cap/get-capability-mode ctx :web)))
                         ctx)
                       1 [])
          plan (make-plan [bound-step])
          ;; Pre-provision persistent capability (no subject)
          initial-ctx (cap/assoc-capability {} :web {:type :persistent-browser} :persistent)
          registry (mock/registry {:web {:cleanup (fn [_]
                                                    (reset! cleanup-called true)
                                                    {:ok :closed})}})
          result (exec/execute-scenario plan initial-ctx {:adapter-registry registry})]
      (is (= :passed (:status result)))
      (is (not @cleanup-called) "Persistent capability should NOT be cleaned up")
      ;; Capability still present in final ctx
      (is (cap/capability-present? (:scenario-ctx result) :web)))))

;; -----------------------------------------------------------------------------
;; SVO Event Emission Tests (Task 3.0.12)
;; -----------------------------------------------------------------------------

;; Note: make-bound-step-with-svo defined above in Auto-Provisioning Tests section

(deftest test-svo-event-emission
  (testing "Task 3.0.12 AC1: emits :step/svo event before step execution"
    (let [events-received (atom [])
          bus (events/make-memory-bus)
          run-id "test-run-123"
          svo {:subject "User"
                :verb "logs in"
                :object "system"
                :interface :web}
          bound-step (make-bound-step-with-svo
                       (fn [ctx] ctx)
                       1 []
                       svo)
          plan (make-plan [bound-step])
          sub-id (events/subscribe! bus (fn [e] (swap! events-received conj e)))]
      ;; Give async subscription time to set up
      (Thread/sleep 10)
      (let [result (exec/execute-scenario plan {} {:bus bus
                                                   :run-id run-id
                                                   :interfaces       {:web {:type :browser :adapter :web}}
                                                   :adapter-registry (mock/registry {:web {}})})]
          ;; Give async events time to propagate
          (Thread/sleep 50)
          (events/unsubscribe! bus sub-id)
          (events/bus-close! bus)
          (is (= :passed (:status result)))
          (is (= 1 (count @events-received)) "Should emit exactly one SVO event")
          (let [evt (first @events-received)]
            (is (= :step/svo (:type evt)))
            (is (= run-id (:run-id evt)))
            (is (string? (:ts evt)))
            (let [payload (:payload evt)]
              (is (= "User" (:subject payload)))
              (is (= "logs in" (:verb payload)))
              (is (= "system" (:object payload)))
              (is (= :web (:interface payload)))
              (is (= :browser (:interface-type payload)))
              (is (= "test step with SVO" (:step-text payload)))
              (is (= {:uri "test.feature" :line 10} (:location payload))))))))

  (testing "Task 3.0.12 AC2: no event when bus/run-id missing"
    ;; Step without :interface in SVO - no provisioning needed
    (let [events-received (atom [])
          svo {:subject "User"
                :verb "logs in"
                :object "system"}  ;; No :interface - no provisioning
          bound-step (make-bound-step-with-svo
                       (fn [ctx] ctx)
                       1 []
                       svo)
          plan (make-plan [bound-step])]
      ;; Execute without bus/run-id
      #_{:clj-kondo/ignore [:redundant-let]}
      (let [result (exec/execute-scenario plan {} {})]
        (is (= :passed (:status result)))
        (is (empty? @events-received) "No events should be emitted without bus"))))

  (testing "Task 3.0.12 AC3: no event when step has no SVO"
    (let [events-received (atom [])
          bus (events/make-memory-bus)
          run-id "test-run-456"
          ;; Regular step without SVO
          bound-step (make-bound-step
                       (fn [ctx] ctx)
                       1 [])
          plan (make-plan [bound-step])
          sub-id (events/subscribe! bus (fn [e] (swap! events-received conj e)))]
      (Thread/sleep 10)
      (let [result (exec/execute-scenario plan {} {:bus bus :run-id run-id})]
        (Thread/sleep 50)
        (events/unsubscribe! bus sub-id)
        (events/bus-close! bus)
        (is (= :passed (:status result)))
        (is (empty? @events-received) "No events should be emitted for step without SVO"))))

  (testing "Task 3.0.12 AC4: multiple steps emit multiple events"
    (let [events-received (atom [])
          bus (events/make-memory-bus)
          run-id "test-run-789"
          svo1 {:subject "Admin" :verb "creates" :object "account" :interface :api}
          svo2 {:subject "User" :verb "views" :object "dashboard" :interface :web}
          bound-step1 (make-bound-step-with-svo
                        (fn [ctx] ctx)
                        1 [] svo1)
          bound-step2 (make-bound-step-with-svo
                        (fn [ctx] ctx)
                        1 [] svo2)
          plan (make-plan [bound-step1 bound-step2])
          sub-id (events/subscribe! bus (fn [e] (swap! events-received conj e)))]
      (Thread/sleep 10)
      (let [result (exec/execute-scenario plan {} {:bus bus
                                                   :run-id run-id
                                                   :interfaces       {:api {:type :rest    :adapter :api}
                                                                      :web {:type :browser :adapter :web}}
                                                   :adapter-registry (mock/registry {:api {} :web {}})})]
        (Thread/sleep 50)
        (events/unsubscribe! bus sub-id)
        (events/bus-close! bus)
        (is (= :passed (:status result)))
        (is (= 2 (count @events-received)) "Should emit two SVO events")
        (let [subjects (set (map #(get-in % [:payload :subject]) @events-received))]
          (is (= #{"Admin" "User"} subjects)))))))

;; -----------------------------------------------------------------------------
;; Subject-Keyed Provisioning + Browser Bridge Tests (WI-032.025)
;; -----------------------------------------------------------------------------

(deftest test-different-subjects-get-separate-capabilities
  (testing "Alice and Bob get separate browser capabilities"
    (let [events (atom [])
          interfaces (mock/interfaces {:web {:adapter :mock}})
          registry (mock/registry {:mock {:impl   {:id "browser"}
                                          :events events}})
          step-alice (make-bound-step-with-svo
                       (fn [ctx]
                         (is (cap/capability-present? ctx :web :alice))
                         ctx)
                       1 []
                       {:subject :alice :verb :click :interface :web})
          step-bob (make-bound-step-with-svo
                     (fn [ctx]
                       (is (cap/capability-present? ctx :web :bob))
                       ;; Alice's should still be there too
                       (is (cap/capability-present? ctx :web :alice))
                       ctx)
                     1 []
                     {:subject :bob :verb :click :interface :web})
          plan (make-plan [step-alice step-bob])
          result (exec/execute-scenario plan {} {:interfaces       interfaces
                                                 :adapter-registry registry})]
      (is (= :passed (:status result)))
      ;; Two separate factory calls (one per subject)
      (is (= 2 (count (filter #(= :provision (first %)) @events)))))))

;; Three bridge-specific tests removed in sl-fds:
;;   test-browser-ctx-bridge-on-provisioning
;;   test-browser-ctx-bridge-default-session
;;   test-no-browser-bridge-for-non-browser-capabilities
;; They asserted on the legacy :cap/browser mirror written by
;; bridge-to-browser-ctx, which was deleted along with the bridge.
;; Subject-keyed unified-shape coverage lives in
;; test-different-subjects-get-separate-capabilities and
;; test-qualified-subject-session-keyed-by-instance.

(deftest test-cleanup-subject-keyed-capabilities
  (testing "Subject-keyed ephemeral capabilities are cleaned up correctly"
    (let [cleanup-called  (atom #{})
          factory-counter (atom 0)
          interfaces (mock/interfaces {:web {:adapter :mock}})
          ;; :factory escape hatch: mint a unique :id per call so Alice and
          ;; Bob's caps are distinct impl objects (otherwise the framework
          ;; would treat them as identical and run cleanup once).
          registry (mock/registry
                    {:mock {:factory (fn [_]
                                       {:ok {:id (str "browser-"
                                                      (swap! factory-counter inc))}})
                            :cleanup (fn [impl]
                                       (swap! cleanup-called conj (:id impl))
                                       {:ok :closed})}})
          step-alice (make-bound-step-with-svo
                       (fn [ctx] ctx)
                       1 []
                       {:subject :alice :verb :click :interface :web})
          step-bob (make-bound-step-with-svo
                     (fn [ctx] ctx)
                     1 []
                     {:subject :bob :verb :click :interface :web})
          plan (make-plan [step-alice step-bob])
          result (exec/execute-suite [plan] {:interfaces       interfaces
                                             :adapter-registry registry})]
      (is (= :passed (:status result)))
      ;; Both subject capabilities should be cleaned up
      (is (= 2 (count @cleanup-called))))))

;; -----------------------------------------------------------------------------
;; :shared-impl? Provisioning Tests (sl-ups)
;; -----------------------------------------------------------------------------

(deftest test-shared-impl-single-factory-call-across-subjects
  (testing ":shared-impl? true causes Alice and Bob to share one impl"
    (let [events (atom [])
          shared-impl {:type :mock-sms :id "shared-1"}
          interfaces (mock/interfaces {:sms {:adapter      :sms-mock
                                             :shared-impl? true}})
          registry (mock/registry {:sms-mock {:impl   shared-impl
                                              :events events}})
          step-alice (make-bound-step-with-svo
                       (fn [ctx]
                         (is (cap/capability-present? ctx :sms :alice))
                         ctx)
                       1 []
                       {:subject :alice :verb :send :interface :sms})
          step-bob (make-bound-step-with-svo
                     (fn [ctx]
                       (is (cap/capability-present? ctx :sms :bob))
                       (let [alice-impl (cap/get-capability ctx :sms :alice)
                             bob-impl   (cap/get-capability ctx :sms :bob)]
                         (is (identical? alice-impl bob-impl)
                             "Alice and Bob should reference the SAME impl instance"))
                       ctx)
                     1 []
                     {:subject :bob :verb :send :interface :sms})
          plan (make-plan [step-alice step-bob])
          result (exec/execute-scenario plan {} {:interfaces       interfaces
                                                 :adapter-registry registry})]
      (is (= :passed (:status result)))
      (is (= 1 (count (filter #(= :provision (first %)) @events)))
          "Factory should be called exactly once under :shared-impl?"))))

(deftest test-shared-impl-cleanup-runs-once-per-impl
  (testing ":shared-impl? cleanup dedup — N subject entries → 1 cleanup call"
    (let [cleanup-calls (atom [])
          shared-impl   {:type :mock-sms :id "shared-1"}
          interfaces (mock/interfaces {:sms {:adapter      :sms-mock
                                             :shared-impl? true}})
          registry (mock/registry {:sms-mock {:impl    shared-impl
                                              :cleanup (fn [impl]
                                                         (swap! cleanup-calls conj impl)
                                                         {:ok :closed})}})
          step-alice (make-bound-step-with-svo
                       (fn [ctx] ctx)
                       1 []
                       {:subject :alice :verb :send :interface :sms})
          step-bob (make-bound-step-with-svo
                     (fn [ctx] ctx)
                     1 []
                     {:subject :bob :verb :send :interface :sms})
          plan (make-plan [step-alice step-bob])
          result (exec/execute-suite [plan] {:interfaces       interfaces
                                             :adapter-registry registry})]
      (is (= :passed (:status result)))
      (is (= 1 (count @cleanup-calls))
          "Cleanup should run exactly once despite two subject entries")
      (is (identical? shared-impl (first @cleanup-calls)))
      ;; Cleanup result should report duplicates as :skipped, not :cleaned.
      (let [cap-cleanup (-> result :scenarios first :capability-cleanup)]
        (is (= 1 (count (:cleaned cap-cleanup))))
        (is (some #(= "shared impl already cleaned" (:reason %))
                  (:skipped cap-cleanup)))))))

(deftest test-non-shared-impl-default-behavior-unchanged
  (testing "Without :shared-impl?, each subject still gets its own impl"
    (let [factory-calls (atom 0)
          interfaces (mock/interfaces {:web {:adapter :mock}})
          registry (mock/registry
                    {:mock {:factory (fn [_]
                                       (swap! factory-calls inc)
                                       {:ok {:id (str "impl-" @factory-calls)}})}})
          step-alice (make-bound-step-with-svo
                       (fn [ctx] ctx)
                       1 []
                       {:subject :alice :verb :click :interface :web})
          step-bob (make-bound-step-with-svo
                     (fn [ctx]
                       (let [alice-impl (cap/get-capability ctx :web :alice)
                             bob-impl   (cap/get-capability ctx :web :bob)]
                         (is (not (identical? alice-impl bob-impl))
                             "Without :shared-impl?, impls must be distinct"))
                       ctx)
                     1 []
                     {:subject :bob :verb :click :interface :web})
          plan (make-plan [step-alice step-bob])
          result (exec/execute-scenario plan {} {:interfaces       interfaces
                                                 :adapter-registry registry})]
      (is (= :passed (:status result)))
      (is (= 2 @factory-calls)
          "Without :shared-impl?, factory runs once per subject"))))

;; -----------------------------------------------------------------------------
;; :sms/scenario-start-ts setter (sl-ups; replaced by sl-3mq hook later)
;; -----------------------------------------------------------------------------

(deftest test-sms-scenario-start-ts-set-on-first-provision
  (testing "First SMS provisioning sets :sms/scenario-start-ts"
    (let [interfaces (mock/interfaces {:sms {:adapter      :sms-mock
                                             :shared-impl? true}})
          ;; Wire the production :on-provision hook so the timestamp setter
          ;; fires — that's what this test is verifying.
          registry (mock/registry {:sms-mock {:impl         {:type :mock-sms}
                                              :on-provision sms-hooks/set-scenario-start-ts}})
          observed-ts (atom nil)
          bound-step (make-bound-step-with-svo
                       (fn [ctx]
                         (reset! observed-ts (:sms/scenario-start-ts ctx))
                         ctx)
                       1 []
                       {:subject :alice :verb :send :interface :sms})
          plan (make-plan [bound-step])
          result (exec/execute-scenario plan {} {:interfaces       interfaces
                                                 :adapter-registry registry})]
      (is (= :passed (:status result)))
      (is (instance? java.time.Instant @observed-ts)
          ":sms/scenario-start-ts is an Instant"))))

(deftest test-sms-scenario-start-ts-not-clobbered-on-second-subject
  (testing "Second SMS subject's provision does not overwrite :sms/scenario-start-ts"
    (let [interfaces (mock/interfaces {:sms {:adapter      :sms-mock
                                             :shared-impl? true}})
          registry (mock/registry {:sms-mock {:impl         {:type :mock-sms}
                                              :on-provision sms-hooks/set-scenario-start-ts}})
          alice-ts (atom nil)
          bob-ts   (atom nil)
          step-alice (make-bound-step-with-svo
                       (fn [ctx]
                         (reset! alice-ts (:sms/scenario-start-ts ctx))
                         ctx)
                       1 []
                       {:subject :alice :verb :send :interface :sms})
          step-bob (make-bound-step-with-svo
                     (fn [ctx]
                       (reset! bob-ts (:sms/scenario-start-ts ctx))
                       ctx)
                     1 []
                     {:subject :bob :verb :send :interface :sms})
          plan (make-plan [step-alice step-bob])
          result (exec/execute-scenario plan {} {:interfaces       interfaces
                                                 :adapter-registry registry})]
      (is (= :passed (:status result)))
      (is (some? @alice-ts))
      (is (= @alice-ts @bob-ts)
          "Bob sees Alice's timestamp, not a fresh one"))))

(deftest test-non-sms-interface-leaves-scenario-start-ts-unset
  (testing "Provisioning a non-SMS interface does not touch :sms/scenario-start-ts"
    (let [interfaces (mock/interfaces {:web {:adapter :mock}})
          registry (mock/registry {:mock {:impl {:type :mock-browser}}})
          observed-keys (atom nil)
          bound-step (make-bound-step-with-svo
                       (fn [ctx]
                         (reset! observed-keys (set (keys ctx)))
                         ctx)
                       1 []
                       {:subject :alice :verb :click :interface :web})
          plan (make-plan [bound-step])
          result (exec/execute-scenario plan {} {:interfaces       interfaces
                                                 :adapter-registry registry})]
      (is (= :passed (:status result)))
      (is (not (contains? @observed-keys :sms/scenario-start-ts))
          ":sms/scenario-start-ts must not appear for non-SMS interfaces"))))

;; -----------------------------------------------------------------------------
;; Generic adapter :on-provision hook (sl-3mq)
;; -----------------------------------------------------------------------------
;;
;; The hook signature is (fn [ctx impl] -> ctx). It runs after
;; cap/assoc-capability so adapters can seed per-interface scenario state
;; without the engine special-casing each interface type. The SMS
;; :sms/scenario-start-ts tests above exercise the same machinery via the
;; default registry; these tests use a custom registry passed as
;; :adapter-registry to verify the mechanism in isolation.

(deftest test-on-provision-hook-invoked-once-with-correct-args
  (testing "Hook fires exactly once per provisioning with (ctx, impl)"
    (let [calls (atom [])
          hook (fn [ctx impl]
                 (swap! calls conj {:ctx-at-call ctx :impl-at-call impl})
                 ctx)
          bound-step (make-bound-step-with-svo
                       (fn [ctx] ctx) 1 []
                       {:subject :alice :verb :doit :interface :test})
          plan (make-plan [bound-step])
          opts {:interfaces (mock/interfaces {:test {:adapter :test-adapter}})
                :adapter-registry (mock/registry {:test-adapter {:on-provision hook}})}
          result (exec/execute-scenario plan {} opts)]
      (is (= :passed (:status result)))
      (is (= 1 (count @calls)) "Hook called exactly once")
      (let [{:keys [ctx-at-call impl-at-call]} (first @calls)]
        (is (= {:type :test-impl} impl-at-call)
            "Hook receives the provisioned impl as second arg")
        (is (cap/capability-present? ctx-at-call :test :alice)
            "Hook sees ctx after assoc-capability")))))

(deftest test-on-provision-hook-return-value-becomes-ctx
  (testing "Hook return value threads forward as the new scenario ctx"
    (let [observed (atom nil)
          hook (fn [ctx _impl] (assoc ctx :hook/touched :yes))
          step-fn (fn [ctx]
                    (reset! observed (:hook/touched ctx))
                    ctx)
          bound-step (make-bound-step-with-svo
                       step-fn 1 []
                       {:subject :alice :verb :doit :interface :test})
          plan (make-plan [bound-step])
          opts {:interfaces (mock/interfaces {:test {:adapter :test-adapter}})
                :adapter-registry (mock/registry {:test-adapter {:on-provision hook}})}
          result (exec/execute-scenario plan {} opts)]
      (is (= :passed (:status result)))
      (is (= :yes @observed)
          "Step body sees the key the hook added to ctx"))))

(deftest test-on-provision-hook-absent-is-no-op
  (testing "Adapter with no :on-provision provisions cleanly; ctx unchanged by hook step"
    (let [observed-keys (atom nil)
          step-fn (fn [ctx]
                    (reset! observed-keys (set (keys ctx)))
                    ctx)
          bound-step (make-bound-step-with-svo
                       step-fn 1 []
                       {:subject :alice :verb :doit :interface :test})
          plan (make-plan [bound-step])
          opts {:interfaces (mock/interfaces {:test {:adapter :test-adapter}})
                :adapter-registry (mock/registry {:test-adapter {}})}
          result (exec/execute-scenario plan {} opts)]
      (is (= :passed (:status result))
          "No hook → no error")
      (is (contains? @observed-keys :cap/test.alice)
          "Capability still provisioned without a hook")
      (is (not-any? #{:hook/touched :sms/scenario-start-ts} @observed-keys)
          "No hook-side-effects in ctx"))))

(deftest test-on-provision-hook-throw-fails-scenario
  (testing "Hook exceptions surface as :adapter/on-provision-failed"
    (let [hook (fn [_ctx _impl]
                 (throw (ex-info "boom" {:reason :test})))
          bound-step (make-bound-step-with-svo
                       (fn [ctx] ctx) 1 []
                       {:subject :alice :verb :doit :interface :test})
          plan (make-plan [bound-step])
          opts {:interfaces (mock/interfaces {:test {:adapter :test-adapter}})
                :adapter-registry (mock/registry {:test-adapter {:on-provision hook}})}
          result (exec/execute-scenario plan {} opts)
          step-error (:error (first (:steps result)))]
      (is (= :failed (:status result)))
      (is (= :adapter/on-provision-failed (:type step-error))
          "Error type is the structured hook-failure tag")
      (is (= :test-adapter (:adapter step-error))
          "Adapter name is included in the error map")
      (is (re-find #"boom" (:message step-error))
          "Hook's exception message is surfaced in :message"))))

(deftest test-on-provision-hook-fires-for-shared-impl-reuse
  (testing "Shared-impl reuse on second subject still invokes the hook"
    (let [calls (atom 0)
          hook (fn [ctx _impl] (swap! calls inc) ctx)
          interfaces {:test {:type :test
                             :adapter :test-adapter
                             :config {}
                             :shared-impl? true}}
          step-alice (make-bound-step-with-svo
                       (fn [ctx] ctx) 1 []
                       {:subject :alice :verb :doit :interface :test})
          step-bob (make-bound-step-with-svo
                     (fn [ctx] ctx) 1 []
                     {:subject :bob :verb :doit :interface :test})
          plan (make-plan [step-alice step-bob])
          opts {:interfaces interfaces
                :adapter-registry (mock/registry {:test-adapter {:on-provision hook}})}
          result (exec/execute-scenario plan {} opts)]
      (is (= :passed (:status result)))
      (is (= 2 @calls)
          "Hook fires for both Alice and Bob; idempotency is the hook's job, not the engine's"))))

;; -----------------------------------------------------------------------------
;; Scoped-Eager Provisioning Tests (sl-aa5)
;;
;; The eager phase fires before any step in the scenario runs. Per-step
;; ensure-capability calls remain in place — they no-op when ctx already
;; has the cap. These tests use the `:provisioning :eager` opt to enable
;; the phase; bare callers (REPL) keep today's lazy behavior.
;; -----------------------------------------------------------------------------

(deftest test-eager-provisions-before-first-step
  (testing ":provisioning :eager fires factory before any step body runs"
    (let [events (atom [])
          interfaces (mock/interfaces {:web {:adapter :web}})
          registry (mock/registry {:web {:events events}})
          step-fn (fn [ctx]
                    (swap! events conj [:step :s1])
                    ;; Capability is already present; lazy ensure-capability
                    ;; in classify-and-execute-step finds it and no-ops.
                    (is (cap/capability-present? ctx :web :alice))
                    ctx)
          bound (make-bound-step-with-svo
                  step-fn 1 []
                  {:subject :alice :verb :click :interface :web})
          plan (make-plan [bound])
          opts {:interfaces interfaces
                :adapter-registry registry
                :provisioning :eager}
          result (exec/execute-scenario plan {} opts)]
      (is (= :passed (:status result)))
      (is (= [[:provision :web] [:step :s1]] @events)
          "Provisioning happens before the first step body"))))

(deftest test-run-interfaces-stash-survives-eager-provisioning
  (testing "The :run/interfaces stash (sl-3jr4) rides through eager delta merges"
    (let [interfaces (mock/interfaces {:web {:adapter :web}})
          registry (mock/registry {:web {}})
          bound (make-bound-step-with-svo
                 (fn [ctx] ctx) 1 []
                 {:subject :alice :verb :click :interface :web})
          plan (make-plan [bound])
          result (exec/execute-scenario plan {} {:interfaces interfaces
                                                 :adapter-registry registry
                                                 :provisioning :eager})]
      (is (= :passed (:status result)))
      (is (= interfaces (:run/interfaces (:scenario-ctx result)))))))

(deftest test-eager-fails-fast-on-bad-creds
  (testing "Eager provisioning failure → first step :failed, rest :skipped, no step body runs"
    (let [step1-ran? (atom false)
          step2-ran? (atom false)
          events (atom [])
          interfaces (mock/interfaces {:web {:adapter :web}})
          registry (mock/registry {:web {:events events :fail? true}})
          s1 (make-bound-step-with-svo
               (fn [ctx] (reset! step1-ran? true) ctx) 1 []
               {:subject :alice :verb :click :interface :web})
          s2 (make-bound-step-with-svo
               (fn [ctx] (reset! step2-ran? true) ctx) 1 []
               {:subject :alice :verb :fill :interface :web})
          plan (make-plan [s1 s2])
          opts {:interfaces interfaces
                :adapter-registry registry
                :provisioning :eager}
          result (exec/execute-scenario plan {} opts)
          [r1 r2] (:steps result)]
      (is (= :failed (:status result)))
      (is (= :failed (:status r1)) "First step bears the failure")
      (is (= :skipped (:status r2)) "Remaining steps skipped")
      (is (not @step1-ran?) "No step body executed")
      (is (not @step2-ran?))
      (is (= :svo/provisioning-failed (-> r1 :error :type))
          "Single-interface failure surfaces verbatim, not wrapped"))))

(deftest test-eager-respects-shared-impl
  (testing ":shared-impl? interface provisioned once across multiple subjects"
    (let [events (atom [])
          interfaces (mock/interfaces {:sms {:adapter :sms :shared-impl? true}})
          registry (mock/registry {:sms {:events events}})
          alice (make-bound-step-with-svo
                  (fn [ctx] ctx) 1 []
                  {:subject :alice :verb :receive :interface :sms})
          bob (make-bound-step-with-svo
                (fn [ctx] ctx) 1 []
                {:subject :bob :verb :receive :interface :sms})
          plan (make-plan [alice bob])
          opts {:interfaces interfaces
                :adapter-registry registry
                :provisioning :eager}
          result (exec/execute-scenario plan {} opts)
          provision-calls (filter #(= :provision (first %)) @events)]
      (is (= :passed (:status result)))
      (is (= 1 (count provision-calls))
          "Factory invoked exactly once; Bob's target reused Alice's impl via find-existing-shared-impl")
      (is (cap/capability-present? (:scenario-ctx result) :sms :alice))
      (is (cap/capability-present? (:scenario-ctx result) :sms :bob))
      (is (= (cap/get-capability (:scenario-ctx result) :sms :alice)
             (cap/get-capability (:scenario-ctx result) :sms :bob))
          "Both subjects point at the same impl"))))

(deftest test-eager-aggregates-errors-across-interfaces
  (testing "Multiple interface failures aggregate cleanly without losing context"
    (let [events (atom [])
          interfaces (mock/interfaces {:web {:adapter :web}
                                       :sms {:adapter :sms}})
          registry (mock/registry {:web {:events events :fail? true}
                                   :sms {:events events :fail? true}})
          web-step (make-bound-step-with-svo
                     (fn [ctx] ctx) 1 []
                     {:subject :alice :verb :click :interface :web})
          sms-step (make-bound-step-with-svo
                     (fn [ctx] ctx) 1 []
                     {:subject :alice :verb :receive :interface :sms})
          plan (make-plan [web-step sms-step])
          opts {:interfaces interfaces
                :adapter-registry registry
                :provisioning :eager}
          result (exec/execute-scenario plan {} opts)
          err (-> result :steps first :error)]
      (is (= :failed (:status result)))
      (is (= :scenario/eager-provisioning-failed (:type err))
          "Multi-interface failure wraps under a scenario-level type")
      (is (= 2 (count (:errors err)))
          "Both per-interface errors preserved")
      (is (= #{:web :sms} (set (map :interface (:errors err))))
          "Each original error keeps its :interface tag"))))

(deftest test-eager-no-targets-does-nothing
  (testing "Plan with no SVO'd steps doesn't invoke the registry"
    (let [events (atom [])
          interfaces (mock/interfaces {:web {:adapter :web}})
          registry (mock/registry {:web {:events events}})
          ;; Plain step, no svo
          bound (make-bound-step (fn [ctx] (assoc ctx :ran true)) 1 [])
          plan (make-plan [bound])
          opts {:interfaces interfaces
                :adapter-registry registry
                :provisioning :eager}
          result (exec/execute-scenario plan {} opts)]
      (is (= :passed (:status result)))
      (is (empty? @events) "No factory call when nothing needs provisioning"))))

(deftest test-eager-multi-interface-parallel-both-provisioned
  (testing "Multi-interface scenario provisions all distinct interfaces (parallel branch)"
    (let [events (atom [])
          interfaces (mock/interfaces {:web {:adapter :web}
                                       :api {:adapter :api}})
          registry (mock/registry {:web {:events events}
                                   :api {:events events}})
          web-step (make-bound-step-with-svo
                     (fn [ctx] ctx) 1 []
                     {:subject :alice :verb :click :interface :web})
          api-step (make-bound-step-with-svo
                     (fn [ctx] ctx) 1 []
                     {:subject :system :verb :call :interface :api})
          plan (make-plan [web-step api-step])
          opts {:interfaces interfaces
                :adapter-registry registry
                :provisioning :eager}
          result (exec/execute-scenario plan {} opts)
          ifaces-provisioned (->> @events (filter #(= :provision (first %))) (map second) set)]
      (is (= :passed (:status result)))
      (is (= #{:web :api} ifaces-provisioned)
          "Both interfaces brought up before any step ran")
      (is (cap/capability-present? (:scenario-ctx result) :web :alice))
      (is (cap/capability-present? (:scenario-ctx result) :api :system)))))

(deftest test-lazy-mode-opt-out-preserves-old-timing
  (testing ":provisioning :lazy reverts to per-step on-first-touch"
    (let [events (atom [])
          interfaces (mock/interfaces {:web {:adapter :web}})
          registry (mock/registry {:web {:events events}})
          ;; First step is interface-less (no provisioning); second touches :web.
          ;; With :lazy, the :web factory should fire AFTER step 1's body runs.
          s1 (make-bound-step
               (fn [ctx] (swap! events conj [:step :pre]) ctx) 1 [])
          s2 (make-bound-step-with-svo
               (fn [ctx] (swap! events conj [:step :web]) ctx) 1 []
               {:subject :alice :verb :click :interface :web})
          plan (make-plan [s1 s2])
          opts {:interfaces interfaces
                :adapter-registry registry
                :provisioning :lazy}
          result (exec/execute-scenario plan {} opts)]
      (is (= :passed (:status result)))
      ;; Step :pre ran first, then provisioning happened (lazy), then step :web
      (is (= [[:step :pre] [:provision :web] [:step :web]] @events)
          "Lazy: provisioning interleaves with steps, not all upfront"))))

(deftest test-repl-path-bare-execute-stays-lazy
  (testing "execute-scenario without :provisioning opt → no eager phase (REPL default)"
    (let [events (atom [])
          interfaces (mock/interfaces {:web {:adapter :web}})
          registry (mock/registry {:web {:events events}})
          s1 (make-bound-step
               (fn [ctx] (swap! events conj [:step :pre]) ctx) 1 [])
          s2 (make-bound-step-with-svo
               (fn [ctx] (swap! events conj [:step :web]) ctx) 1 []
               {:subject :alice :verb :click :interface :web})
          plan (make-plan [s1 s2])
          ;; NO :provisioning key — REPL-style invocation
          opts {:interfaces interfaces
                :adapter-registry registry}
          result (exec/execute-scenario plan {} opts)]
      (is (= :passed (:status result)))
      (is (= [[:step :pre] [:provision :web] [:step :web]] @events)
          "Bare exec call defaults to lazy; :web provisioned only when its step runs"))))

(deftest test-eager-skips-synthetic-steps
  (testing "Synthetic macro wrappers are excluded from eager target collection"
    (let [events (atom [])
          interfaces (mock/interfaces {:web {:adapter :web}})
          registry (mock/registry {:web {:events events}})
          ;; A synthetic step shaped like a macro wrapper — has :svo-ish
          ;; metadata but should be ignored by the eager scanner.
          synthetic {:status :matched
                     :step {:step/id (java.util.UUID/randomUUID)
                            :step/text "macro wrapper"
                            :step/synthetic? true
                            :step/macro {:role :call :step-count 0}}
                     :binding {:fn (fn [ctx] ctx) :arity 1 :captures []
                               :svo {:subject :alice :verb :do :interface :web}}}
          plan (make-plan [synthetic])
          opts {:interfaces interfaces
                :adapter-registry registry
                :provisioning :eager}
          result (exec/execute-scenario plan {} opts)]
      (is (= :passed (:status result)))
      (is (empty? @events)
          "Synthetic step's svo shouldn't trigger eager provisioning"))))

;; -----------------------------------------------------------------------------
;; Partial-Provisioning Failure Cleanup (sl-uu7x)
;;
;; When provisioning fails partway — Alice's browser up, Bob's factory
;; fails — the error result carries the partially-provisioned ctx so
;; scenario-end cleanup still closes the live impls. Before sl-uu7x the
;; ctx was dropped and every such failure leaked a Chrome/driver process.
;; -----------------------------------------------------------------------------

(defn- fail-on-nth-factory
  "Factory succeeding until the `n`th call (1-based), which errors."
  [n]
  (let [calls (atom 0)]
    (fn [_config]
      (if (< (swap! calls inc) n)
        {:ok {:type :test-impl :call @calls}}
        {:error {:type :adapter/test-failure
                 :message (str "provision call " @calls " fails")}}))))

(deftest test-partial-eager-failure-cleans-provisioned-capability
  (testing "Second subject's provision failure still closes the first's impl"
    (let [events (atom [])
          registry (mock/registry {:mock-web {:events events
                                              :factory (fail-on-nth-factory 2)}})
          interfaces (mock/interfaces {:web {:adapter :mock-web}})
          step (fn [subj]
                 (make-bound-step-with-svo
                   (fn [ctx] ctx) 1 []
                   {:subject subj :verb :click :interface :web}))
          plan (make-plan [(step :alice) (step :bob)])
          result (exec/execute-suite [plan] {:interfaces interfaces
                                             :adapter-registry registry
                                             :provisioning :eager})
          scenario (-> result :scenarios first)]
      (is (= :failed (:status scenario)))
      (is (= :svo/provisioning-failed (-> scenario :steps first :error :type)))
      (is (= [{:action :closed :interface :web.alice :adapter :mock-web}]
             (-> scenario :capability-cleanup :cleaned))
          "Alice's already-provisioned capability is closed, not orphaned")
      (is (= 1 (count (filter #(= :cleanup (first %)) @events)))
          "Adapter :cleanup fired exactly once"))))

(deftest test-multi-interface-eager-failure-cleans-healthy-group
  (testing "A failing interface group doesn't orphan a sibling group's impls"
    (let [events (atom [])
          registry (mock/registry {:mock-web {:events events}
                                   :mock-sms {:events events :fail? true}})
          interfaces (mock/interfaces {:web {:adapter :mock-web}
                                       :sms {:adapter :mock-sms}})
          web-step (make-bound-step-with-svo
                     (fn [ctx] ctx) 1 []
                     {:subject :alice :verb :click :interface :web})
          sms-step (make-bound-step-with-svo
                     (fn [ctx] ctx) 1 []
                     {:subject :alice :verb :recv :interface :sms})
          plan (make-plan [web-step sms-step])
          result (exec/execute-suite [plan] {:interfaces interfaces
                                             :adapter-registry registry
                                             :provisioning :eager})
          scenario (-> result :scenarios first)]
      (is (= :failed (:status scenario)))
      (is (some #{[:cleanup :mock-web]} @events)
          "The web group provisioned fine and its impl is closed")
      (is (= [:web.alice]
             (mapv :interface (-> scenario :capability-cleanup :cleaned)))))))

(deftest test-on-provision-hook-failure-cleans-impl-eager
  (testing "Eager: hook throw after successful provision still closes the impl"
    (let [events (atom [])
          registry (mock/registry
                    {:mock-web {:events events
                                :on-provision (fn [_ctx _impl]
                                                (throw (ex-info "hook boom" {})))}})
          interfaces (mock/interfaces {:web {:adapter :mock-web}})
          bound (make-bound-step-with-svo
                  (fn [ctx] ctx) 1 []
                  {:subject :alice :verb :click :interface :web})
          plan (make-plan [bound])
          result (exec/execute-suite [plan] {:interfaces interfaces
                                             :adapter-registry registry
                                             :provisioning :eager})
          scenario (-> result :scenarios first)]
      (is (= :failed (:status scenario)))
      (is (= :adapter/on-provision-failed (-> scenario :steps first :error :type)))
      (is (some #{[:cleanup :mock-web]} @events)
          "The impl provisioned before the hook threw is closed"))))

(deftest test-on-provision-hook-failure-cleans-impl-lazy
  (testing "Lazy: hook throw on per-step provisioning still closes the impl"
    (let [events (atom [])
          registry (mock/registry
                    {:mock-web {:events events
                                :on-provision (fn [_ctx _impl]
                                                (throw (ex-info "hook boom" {})))}})
          interfaces (mock/interfaces {:web {:adapter :mock-web}})
          bound (make-bound-step-with-svo
                  (fn [ctx] ctx) 1 []
                  {:subject :alice :verb :click :interface :web})
          plan (make-plan [bound])
          ;; No :provisioning :eager — the per-step lazy path provisions.
          result (exec/execute-suite [plan] {:interfaces interfaces
                                             :adapter-registry registry})
          scenario (-> result :scenarios first)]
      (is (= :failed (:status scenario)))
      (is (some #{[:cleanup :mock-web]} @events)
          "The impl provisioned before the hook threw is closed"))))

;; -----------------------------------------------------------------------------
;; Costume :wears Provisioning Tests (sl-rnm)
;;
;; A subject that :wears a costume attaches to that persistent authenticated
;; session (connect-costume!) instead of fresh-spawning. Chrome is stubbed —
;; these assert the provisioning *wiring*, the deterministic functional facts:
;; attach-not-spawn, :persistent mode, and survival past scenario cleanup.
;; (Real-browser / navigator.webdriver observation is the manual x-bookmarks
;; consumer test, not asserted here.)
;; -----------------------------------------------------------------------------

(def ^:private fake-costume-browser
  "Stand-in for a CostumeBrowser impl returned by connect-costume!."
  {:type :costume-browser :session "costume-session" :costume :finance})

(deftest test-wears-attaches-to-costume-not-fresh-spawn
  (testing "Subject :wears a costume → provisioned via connect-costume!, not the adapter factory"
    (let [connect-called (atom nil)
          events (atom [])
          ;; If the adapter factory ran (fresh spawn), @events would be non-empty.
          registry (mock/registry {:web {:impl {:browser-type :spawned} :events events}})
          interfaces (mock/interfaces {:web {:adapter :web}})
          captured-ctx (atom nil)
          bound-step (make-bound-step-with-svo
                       (fn [ctx] (reset! captured-ctx ctx) (assoc ctx :saw true))
                       1 []
                       {:subject :alice :verb :navigate :interface :web :wears :finance})
          plan (make-plan [bound-step])]
      (with-redefs [costume/connect-costume! (fn [name]
                                               (reset! connect-called name)
                                               {:status :connected :costume name
                                                :port 9300 :pid 1 :browser fake-costume-browser})]
        (let [result (exec/execute-scenario plan {} {:interfaces interfaces
                                                     :adapter-registry registry})
              ctx (or @captured-ctx (:scenario-ctx result))]
          (is (= :passed (:status result)))
          (is (= :finance @connect-called) "connect-costume! called with the worn costume")
          (is (empty? @events) "Adapter factory must NOT run — attach, not fresh spawn")
          (is (= fake-costume-browser (cap/get-capability ctx :web :alice))
              "Capability impl is the costume's browser"))))))

(deftest test-wears-is-persistent-and-survives-cleanup
  (testing "Costume-backed capability is :persistent and not torn down by cleanup"
    (let [registry (mock/registry {:web {:impl {:browser-type :spawned}}})
          interfaces (mock/interfaces {:web {:adapter :web}})
          captured-ctx (atom nil)
          bound-step (make-bound-step-with-svo
                       (fn [ctx] (reset! captured-ctx ctx) (assoc ctx :saw true))
                       1 []
                       {:subject :alice :verb :navigate :interface :web :wears :finance})
          plan (make-plan [bound-step])]
      (with-redefs [costume/connect-costume! (fn [name]
                                               {:status :connected :costume name
                                                :port 9300 :pid 1 :browser fake-costume-browser})]
        (let [result (exec/execute-suite [plan] {:interfaces interfaces
                                                 :adapter-registry registry})
              ctx (or @captured-ctx (-> result :scenarios first :scenario-ctx))
              cap-cleanup (-> result :scenarios first :capability-cleanup)]
          (is (= :passed (:status result)))
          ;; :persistent mode (criterion 2)
          (is (seq (cap/persistent-capabilities ctx)) "Costume cap is persistent")
          (is (empty? (cap/ephemeral-capabilities ctx)) "No ephemeral cap was created")
          ;; survives cleanup (criterion 3) — never closed, only skipped
          (is (empty? (:cleaned cap-cleanup)) "Persistent costume session was NOT closed")
          (is (seq (:skipped cap-cleanup)) "Costume session was skipped by cleanup"))))))

(deftest test-wears-connect-failure-fails-scenario-cleanly
  (testing "A missing/locked costume surfaces a structured provisioning failure"
    (let [registry (mock/registry {:web {:impl {:browser-type :spawned}}})
          interfaces (mock/interfaces {:web {:adapter :web}})
          step-ran (atom false)
          bound-step (make-bound-step-with-svo
                       (fn [ctx] (reset! step-ran true) ctx)
                       1 []
                       {:subject :alice :verb :navigate :interface :web :wears :finance})
          plan (make-plan [bound-step])]
      (with-redefs [costume/connect-costume! (fn [_name]
                                               {:error {:type :costume/not-found
                                                        :message "no such costume"}})]
        (let [result (exec/execute-scenario plan {} {:interfaces interfaces
                                                     :adapter-registry registry})]
          (is (not= :passed (:status result)) "Scenario does not pass when the costume can't be attached")
          (is (false? @step-ran) "Step does not run when provisioning fails"))))))

;; -----------------------------------------------------------------------------
;; One-wearer-per-costume enforcement (sl-s7t)
;;
;; Two subjects resolving to the same costume (e.g. a multi-instance type that
;; :wears one costume, used by two actors) must NOT silently double-attach two
;; WebDriver sessions to one Chrome. We fail cleanly (option b) instead.
;; -----------------------------------------------------------------------------

(deftest test-two-wearers-same-costume-fails-cleanly
  (testing "A second subject wearing an already-worn costume fails, no double-attach"
    (let [connect-calls (atom 0)
          registry (mock/registry {:web {:impl {:browser-type :spawned}}})
          interfaces (mock/interfaces {:web {:adapter :web}})
          alice-step (make-bound-step-with-svo
                       (fn [ctx] ctx) 1 []
                       {:subject :alice :verb :navigate :interface :web :wears :finance})
          bob-step (make-bound-step-with-svo
                     (fn [ctx] ctx) 1 []
                     {:subject :bob :verb :navigate :interface :web :wears :finance})
          plan (make-plan [alice-step bob-step])]
      (with-redefs [costume/connect-costume! (fn [name]
                                               (swap! connect-calls inc)
                                               {:status :connected :costume name
                                                :port 9300 :pid 1 :browser fake-costume-browser})]
        (let [result (exec/execute-scenario plan {} {:interfaces interfaces
                                                     :adapter-registry registry})
              err (->> result :steps (keep :error) first)]
          (is (= :failed (:status result)) "Two wearers of one costume fails the scenario")
          (is (= :svo/provisioning-failed (:type err)))
          (is (= :costume/already-worn (:reason err)) "Tagged as the one-wearer violation")
          (is (= :finance (:costume err)))
          (is (= 1 @connect-calls)
              "connect-costume! called once (alice) — bob never double-attaches"))))))

(deftest test-two-wearers-same-costume-fails-fast-eager
  (testing "Scoped-eager phase rejects the second wearer before any step runs"
    (let [connect-calls (atom 0)
          steps-ran (atom 0)
          registry (mock/registry {:web {:impl {:browser-type :spawned}}})
          interfaces (mock/interfaces {:web {:adapter :web}})
          alice-step (make-bound-step-with-svo
                       (fn [ctx] (swap! steps-ran inc) ctx) 1 []
                       {:subject :alice :verb :navigate :interface :web :wears :finance})
          bob-step (make-bound-step-with-svo
                     (fn [ctx] (swap! steps-ran inc) ctx) 1 []
                     {:subject :bob :verb :navigate :interface :web :wears :finance})
          plan (make-plan [alice-step bob-step])]
      (with-redefs [costume/connect-costume! (fn [name]
                                               (swap! connect-calls inc)
                                               {:status :connected :costume name
                                                :port 9300 :pid 1 :browser fake-costume-browser})]
        (let [result (exec/execute-scenario plan {} {:interfaces interfaces
                                                     :adapter-registry registry
                                                     :provisioning :eager})
              err (->> result :steps (keep :error) first)]
          (is (= :failed (:status result)))
          (is (= :costume/already-worn (:reason err)))
          (is (= 0 @steps-ran) "Eager fail-fast: no step runs when provisioning is rejected")
          (is (= 1 @connect-calls) "Only the first wearer attaches"))))))

(deftest test-distinct-costumes-two-subjects-both-attach
  (testing "Two subjects wearing DIFFERENT costumes both provision (no false positive)"
    (let [connected (atom #{})
          registry (mock/registry {:web {:impl {:browser-type :spawned}}})
          interfaces (mock/interfaces {:web {:adapter :web}})
          alice-step (make-bound-step-with-svo
                       (fn [ctx] ctx) 1 []
                       {:subject :alice :verb :navigate :interface :web :wears :finance})
          bob-step (make-bound-step-with-svo
                     (fn [ctx] ctx) 1 []
                     {:subject :bob :verb :navigate :interface :web :wears :personal})
          plan (make-plan [alice-step bob-step])]
      (with-redefs [costume/connect-costume! (fn [name]
                                               (swap! connected conj name)
                                               {:status :connected :costume name
                                                :port 9300 :pid 1
                                                :browser (assoc fake-costume-browser :costume name)})]
        (let [result (exec/execute-scenario plan {} {:interfaces interfaces
                                                     :adapter-registry registry})]
          (is (= :passed (:status result)) "Distinct costumes do not trip the guard")
          (is (= #{:finance :personal} @connected) "Both costumes attached"))))))

;; -----------------------------------------------------------------------------
;; sl-091: wrapping-adapter impl/cleanup-handle decoupling
;;
;; A generic :web browser (no costume) goes through provision-capability. The
;; browser factory wraps the IBrowser alongside its driver
;; ({:browser <IBrowser> :etaoin-driver <driver>}); steps need the bare
;; IBrowser while cleanup needs the driver. The `:impl-key` contract splits
;; the two. These exercise the split end-to-end through the public seams —
;; the path the live .feature examples drive (and which had never run green).
;; -----------------------------------------------------------------------------

(deftest test-impl-key-adapter-exposes-bare-impl
  (testing "step sees the unwrapped IBrowser; the wrapper is stashed as :cleanup-handle"
    (let [fake       (reify bp/IBrowser)
          registry   (mock/registry
                      {:webwrap {:impl-key :browser
                                 :impl     {:browser fake :driver :fake-driver}
                                 :provides [:shiftlefter.browser.protocol/IBrowser]}})
          interfaces (mock/interfaces {:web {:adapter :webwrap}})
          bound-step (make-bound-step-with-svo
                      (fn [ctx]
                        ;; The bug: this used to be the wrapper map, failing
                        ;; the protocol dispatch in open-to!.
                        (is (identical? fake (cap/get-capability ctx :web :alice)))
                        (is (satisfies? bp/IBrowser (cap/get-capability ctx :web :alice)))
                        ctx)
                      1 []
                      {:subject :alice :verb :navigate :interface :web})
          plan       (make-plan [bound-step])
          result     (exec/execute-scenario plan {} {:interfaces       interfaces
                                                     :adapter-registry registry})]
      (is (= :passed (:status result)))
      (is (= {:browser fake :driver :fake-driver}
             (get-in (:scenario-ctx result) [:cap/web.alice :cleanup-handle]))
          "full factory result retained for cleanup"))))

(deftest test-impl-key-mismatch-fails-provisioning
  (testing "a misconfigured :impl-key fails at provision time, not at the first step"
    (let [registry   (mock/registry
                      {:webwrap {:impl-key :missing  ;; key absent from the impl map
                                 :impl     {:browser (reify bp/IBrowser)}
                                 :provides [:shiftlefter.browser.protocol/IBrowser]}})
          interfaces (mock/interfaces {:web {:adapter :webwrap}})
          step-ran   (atom false)
          bound-step (make-bound-step-with-svo
                      (fn [ctx] (reset! step-ran true) ctx) 1 []
                      {:subject :alice :verb :navigate :interface :web})
          plan       (make-plan [bound-step])
          result     (exec/execute-scenario plan {} {:interfaces       interfaces
                                                     :adapter-registry registry})]
      (is (= :failed (:status result)))
      (is (false? @step-ran) "step never runs — provisioning fails first")
      (let [err (-> result :steps first :error)]
        (is (= :svo/provisioning-failed (:type err)))
        (is (= :adapter/impl-protocol-mismatch (:reason err)))
        (is (= [:shiftlefter.browser.protocol/IBrowser] (:missing-protocols err)))))))

(deftest test-cleanup-receives-handle-not-impl
  (testing "full cycle: provision unwraps, cleanup receives the wrapper (carrying the driver)"
    (let [captured   (atom nil)
          fake       (reify bp/IBrowser)
          registry   (mock/registry
                      {:webwrap {:impl-key :browser
                                 :impl     {:browser fake :driver :fake-driver}
                                 :provides [:shiftlefter.browser.protocol/IBrowser]
                                 :cleanup  (fn [h] (reset! captured h) {:ok :closed})}})
          interfaces (mock/interfaces {:web {:adapter :webwrap}})
          bound-step (make-bound-step-with-svo
                      (fn [ctx] ctx) 1 []
                      {:subject :alice :verb :navigate :interface :web})
          plan       (make-plan [bound-step])
          result     (exec/execute-suite [plan] {:interfaces       interfaces
                                                 :adapter-registry registry})]
      (is (= :passed (:status result)))
      (is (= {:browser fake :driver :fake-driver} @captured)
          "cleanup gets the driver-bearing wrapper")
      (is (not (identical? fake @captured)) "not the bare impl"))))
