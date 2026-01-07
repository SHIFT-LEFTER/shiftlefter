(ns shiftlefter.stepengine.compile-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [shiftlefter.stepengine.compile :as compile]
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
;; Helpers
;; -----------------------------------------------------------------------------

(defn make-step
  "Create a pickle step map for testing."
  [text]
  {:step/id (java.util.UUID/randomUUID)
   :step/text text
   :step/arguments []})

(defn make-pickle
  "Create a pickle map for testing."
  [name steps]
  {:pickle/id (java.util.UUID/randomUUID)
   :pickle/name name
   :pickle/steps (mapv #(if (string? %) (make-step %) %) steps)})

;; -----------------------------------------------------------------------------
;; compile-suite Tests
;; -----------------------------------------------------------------------------

(deftest test-compile-suite-delegates-to-bind
  (testing "compile-suite produces same result as bind-suite"
    (registry/register! #"I have (\d+) items"
                        (fn [n] n)
                        {:ns 's :file "s.clj" :line 1})
    (registry/register! #"I add (\d+) more"
                        (fn [n] n)
                        {:ns 's :file "s.clj" :line 2})
    (let [pickles [(make-pickle "p1" ["I have 5 items"])
                   (make-pickle "p2" ["I add 3 more"])]
          stepdefs (registry/all-stepdefs)
          runner-config {}  ; unused in Phase 2.1
          compile-result (compile/compile-suite runner-config pickles stepdefs)
          bind-result (bind/bind-suite pickles stepdefs)]
      ;; Same structure
      (is (= (:runnable? compile-result) (:runnable? bind-result)))
      (is (= (count (:plans compile-result)) (count (:plans bind-result))))
      (is (= (:diagnostics compile-result) (:diagnostics bind-result))))))

(deftest test-compile-suite-runnable-plan
  (testing "compile-suite returns runnable plan when all steps match"
    (registry/register! #"I have (\d+) items in my cart"
                        (fn [n] nil)
                        {:ns 's :file "s.clj" :line 1})
    (registry/register! #"I add (\d+) more items"
                        (fn [n] nil)
                        {:ns 's :file "s.clj" :line 2})
    (registry/register! #"I should have (\d+) items total"
                        (fn [n] nil)
                        {:ns 's :file "s.clj" :line 3})
    (let [pickle (make-pickle "basic" ["I have 5 items in my cart"
                                       "I add 3 more items"
                                       "I should have 8 items total"])
          {:keys [plans runnable? diagnostics]} (compile/compile-suite {} [pickle] (registry/all-stepdefs))]
      (is runnable?)
      (is (= 1 (count plans)))
      (is (:plan/runnable? (first plans)))
      (is (zero? (-> diagnostics :counts :total-issues))))))

(deftest test-compile-suite-not-runnable-undefined
  (testing "compile-suite returns not runnable when step undefined"
    (registry/register! #"I have (\d+) items"
                        (fn [n] n)
                        {:ns 's :file "s.clj" :line 1})
    (let [pickle (make-pickle "test" ["I have 5 items" "undefined step"])
          {:keys [runnable? diagnostics]} (compile/compile-suite {} [pickle] (registry/all-stepdefs))]
      (is (not runnable?))
      (is (= 1 (-> diagnostics :counts :undefined-count))))))

(deftest test-compile-suite-accepts-runner-config
  (testing "compile-suite accepts runner-config parameter (for future use)"
    (registry/register! #"a step"
                        (fn [] nil)
                        {:ns 's :file "s.clj" :line 1})
    (let [runner-config {:macros {:enabled? false}
                         :step-paths ["steps/"]}
          pickle (make-pickle "test" ["a step"])
          {:keys [runnable?]} (compile/compile-suite runner-config [pickle] (registry/all-stepdefs))]
      ;; Config is accepted without error (currently unused)
      (is runnable?))))

(deftest test-compile-suite-empty-pickles
  (testing "compile-suite handles empty pickle list"
    (let [{:keys [plans runnable? diagnostics]} (compile/compile-suite {} [] (registry/all-stepdefs))]
      (is (empty? plans))
      (is runnable?)  ; no issues = runnable
      (is (zero? (-> diagnostics :counts :total-issues))))))

;; -----------------------------------------------------------------------------
;; Acceptance Criteria (from Task 2.1 spec)
;; -----------------------------------------------------------------------------

(deftest test-acceptance-criteria-phase1-integration
  (testing "Phase 1 integration unchanged - compile produces executable plans"
    ;; Setup stepdefs matching basic.feature
    (registry/register! #"I have (\d+) items in my cart"
                        (fn [n] {:cart (parse-long n)})
                        {:ns 'steps :file "steps.clj" :line 1})
    (registry/register! #"I add (\d+) more items"
                        (fn [n ctx]
                          (update-in ctx [:scenario :cart] + (parse-long n)))
                        {:ns 'steps :file "steps.clj" :line 2})
    (registry/register! #"I should have (\d+) items total"
                        (fn [n ctx]
                          (assert (= (parse-long n) (get-in ctx [:scenario :cart])))
                          nil)
                        {:ns 'steps :file "steps.clj" :line 3})
    (let [pickle (make-pickle "Basic passing scenario"
                              ["I have 5 items in my cart"
                               "I add 3 more items"
                               "I should have 8 items total"])
          {:keys [plans runnable?]} (compile/compile-suite {} [pickle] (registry/all-stepdefs))]
      (is runnable?)
      (is (= 1 (count plans)))
      (is (every? #(= :matched (:status %)) (-> plans first :plan/steps))))))

;; -----------------------------------------------------------------------------
;; Macro Pipeline Tests (Task 2.8)
;; -----------------------------------------------------------------------------

(deftest test-compile-suite-macros-disabled-passthrough
  (testing "macros disabled passes pickles through unchanged"
    (registry/register! #"I have (\d+) items"
                        (fn [n] n)
                        {:ns 's :file "s.clj" :line 1})
    (let [pickle (make-pickle "test" ["I have 5 items"])
          ;; Macros explicitly disabled
          runner-config {:macros {:enabled? false}}
          {:keys [runnable? plans]} (compile/compile-suite runner-config [pickle] (registry/all-stepdefs))]
      (is runnable?)
      (is (= 1 (count plans)))
      ;; No macro metadata on steps
      (is (nil? (-> plans first :plan/steps first :step :step/macro))))))

(deftest test-compile-suite-macros-enabled-with-registry
  (testing "macros enabled loads registry and expands pickles"
    ;; Register stepdefs for macro expansion (matching auth.ini steps)
    (registry/register! #"I visit the login page"
                        (fn [] nil)
                        {:ns 's :file "s.clj" :line 1})
    (registry/register! #"I enter username \"([^\"]+)\""
                        (fn [_] nil)
                        {:ns 's :file "s.clj" :line 2})
    (registry/register! #"I enter password \"([^\"]+)\""
                        (fn [_] nil)
                        {:ns 's :file "s.clj" :line 3})
    (registry/register! #"I click the login button"
                        (fn [] nil)
                        {:ns 's :file "s.clj" :line 4})
    (registry/register! #"I should see the welcome message"
                        (fn [] nil)
                        {:ns 's :file "s.clj" :line 5})
    ;; Use auth.ini macro fixture
    (let [macro-path "test/fixtures/macros/auth.ini"
          runner-config {:macros {:enabled? true
                                  :registry-paths [macro-path]}}
          ;; Pickle with macro call
          pickle {:pickle/id (java.util.UUID/randomUUID)
                  :pickle/name "test"
                  :pickle/steps [{:step/id (java.util.UUID/randomUUID)
                                  :step/keyword "Given"
                                  :step/text "login as alice +"
                                  :step/location {:line 5 :column 5}
                                  :step/arguments []}]}
          {:keys [runnable? plans]} (compile/compile-suite runner-config [pickle] (registry/all-stepdefs))]
      (is runnable?)
      (is (= 1 (count plans)))
      ;; Expanded: wrapper + 5 children = 6 steps
      (is (= 6 (count (-> plans first :plan/steps))))
      ;; First step is synthetic wrapper
      (is (true? (-> plans first :plan/steps first :step :step/synthetic?)))
      (is (= :call (-> plans first :plan/steps first :step :step/macro :role))))))

(deftest test-compile-suite-macros-registry-load-error
  (testing "macro registry load error returns not runnable"
    (let [;; Non-existent file
          runner-config {:macros {:enabled? true
                                  :registry-paths ["nonexistent/macros.ini"]}}
          pickle (make-pickle "test" ["some step"])
          {:keys [runnable? diagnostics]} (compile/compile-suite runner-config [pickle] (registry/all-stepdefs))]
      (is (not runnable?))
      (is (seq (:macro-errors diagnostics)))
      (is (= :macro/file-not-found (-> diagnostics :macro-errors first :type))))))

(deftest test-compile-suite-macros-expansion-error
  (testing "macro expansion error (undefined macro) returns not runnable"
    ;; Use auth.ini but call undefined macro
    (let [macro-path "test/fixtures/macros/auth.ini"
          runner-config {:macros {:enabled? true
                                  :registry-paths [macro-path]}}
          ;; Pickle with undefined macro call
          pickle {:pickle/id (java.util.UUID/randomUUID)
                  :pickle/name "test"
                  :pickle/steps [{:step/id (java.util.UUID/randomUUID)
                                  :step/keyword "Given"
                                  :step/text "undefined macro +"
                                  :step/location {:line 5 :column 5}
                                  :step/arguments []}]}
          {:keys [runnable? diagnostics]} (compile/compile-suite runner-config [pickle] (registry/all-stepdefs))]
      (is (not runnable?))
      (is (seq (:macro-errors diagnostics)))
      (is (= :macro/undefined (-> diagnostics :macro-errors first :type))))))

(deftest test-compile-suite-macros-enabled-no-macro-calls
  (testing "macros enabled but no macro calls works normally"
    (registry/register! #"I have (\d+) items"
                        (fn [n] n)
                        {:ns 's :file "s.clj" :line 1})
    (let [macro-path "test/fixtures/macros/auth.ini"
          runner-config {:macros {:enabled? true
                                  :registry-paths [macro-path]}}
          ;; Regular step (no + suffix)
          pickle (make-pickle "test" ["I have 5 items"])
          {:keys [runnable? plans]} (compile/compile-suite runner-config [pickle] (registry/all-stepdefs))]
      (is runnable?)
      (is (= 1 (count plans)))
      ;; No expansion, just 1 step
      (is (= 1 (count (-> plans first :plan/steps)))))))

(deftest test-compile-suite-macros-multiple-pickles
  (testing "macro expansion works across multiple pickles"
    ;; Register stepdefs for macro expansion (matching auth.ini steps)
    (registry/register! #"I visit the login page"
                        (fn [] nil)
                        {:ns 's :file "s.clj" :line 1})
    (registry/register! #"I enter username \"([^\"]+)\""
                        (fn [_] nil)
                        {:ns 's :file "s.clj" :line 2})
    (registry/register! #"I enter password \"([^\"]+)\""
                        (fn [_] nil)
                        {:ns 's :file "s.clj" :line 3})
    (registry/register! #"I click the login button"
                        (fn [] nil)
                        {:ns 's :file "s.clj" :line 4})
    (registry/register! #"I should see the welcome message"
                        (fn [] nil)
                        {:ns 's :file "s.clj" :line 5})
    (registry/register! #"I have (\d+) items"
                        (fn [n] n)
                        {:ns 's :file "s.clj" :line 6})
    (let [macro-path "test/fixtures/macros/auth.ini"
          runner-config {:macros {:enabled? true
                                  :registry-paths [macro-path]}}
          pickle1 {:pickle/id (java.util.UUID/randomUUID)
                   :pickle/name "with macro"
                   :pickle/steps [{:step/id (java.util.UUID/randomUUID)
                                   :step/keyword "Given"
                                   :step/text "login as alice +"
                                   :step/location {:line 5 :column 5}
                                   :step/arguments []}]}
          pickle2 (make-pickle "without macro" ["I have 5 items"])
          {:keys [runnable? plans]} (compile/compile-suite runner-config [pickle1 pickle2] (registry/all-stepdefs))]
      (is runnable?)
      (is (= 2 (count plans)))
      ;; First pickle: expanded (wrapper + 5 children = 6 steps)
      (is (= 6 (count (-> plans first :plan/steps))))
      ;; Second pickle: not expanded (1 step)
      (is (= 1 (count (-> plans second :plan/steps)))))))
