(ns shiftlefter.stepdefs.browser-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [shiftlefter.stepdefs.browser :as browser-steps]
            [shiftlefter.stepengine.registry :as registry]
            [shiftlefter.browser.ctx :as browser.ctx]
            [shiftlefter.browser.protocol :as bp]))

;; -----------------------------------------------------------------------------
;; Fake Browser for Testing
;; -----------------------------------------------------------------------------

(defrecord FakeBrowser [calls]
  bp/IBrowser
  (open-to! [this url]
    (swap! calls conj {:op :open-to :url url})
    this)
  (click! [this locator]
    (swap! calls conj {:op :click :locator locator})
    this)
  (doubleclick! [this locator]
    (swap! calls conj {:op :doubleclick :locator locator})
    this)
  (rightclick! [this locator]
    (swap! calls conj {:op :rightclick :locator locator})
    this)
  (move-to! [this locator]
    (swap! calls conj {:op :move-to :locator locator})
    this)
  (drag-to! [this from to]
    (swap! calls conj {:op :drag-to :from from :to to})
    this)
  (fill! [this locator text]
    (swap! calls conj {:op :fill :locator locator :text text})
    this)
  (element-count [this locator]
    (swap! calls conj {:op :element-count :locator locator})
    42))

(defn make-fake-browser []
  (->FakeBrowser (atom [])))

(defn get-calls [browser]
  @(:calls browser))

;; -----------------------------------------------------------------------------
;; Test Fixtures
;; -----------------------------------------------------------------------------

(use-fixtures :each
  (fn [f]
    (registry/clear-registry!)
    ;; Load browser stepdefs
    (require 'shiftlefter.stepdefs.browser :reload)
    (f)))

;; -----------------------------------------------------------------------------
;; Helper
;; -----------------------------------------------------------------------------

(defn- make-ctx-with-browser
  "Create a ctx with a fake browser attached."
  [browser]
  (let [scenario-ctx (browser.ctx/assoc-active-browser {} browser)]
    {:step {} :scenario scenario-ctx}))

(defn- find-stepdef
  "Find a stepdef by pattern match."
  [text]
  (first (filter #(re-matches (:pattern %) text) (registry/all-stepdefs))))

(defn- invoke-step
  "Invoke a stepdef with text and ctx."
  [text ctx]
  (let [stepdef (find-stepdef text)
        matcher (re-matcher (:pattern stepdef) text)
        _ (.matches matcher)
        captures (mapv #(.group matcher %) (range 1 (inc (.groupCount matcher))))
        arity (:arity stepdef)
        args (if (= arity (inc (count captures)))
               (conj captures ctx)
               captures)]
    (apply (:fn stepdef) args)))

;; -----------------------------------------------------------------------------
;; Navigation Tests
;; -----------------------------------------------------------------------------

(deftest test-open-browser-to
  (testing "I open the browser to '<url>' navigates"
    (let [browser (make-fake-browser)
          ctx (make-ctx-with-browser browser)
          result (invoke-step "I open the browser to 'https://example.com'" ctx)]
      (is (map? result))
      (is (= [{:op :open-to :url "https://example.com"}] (get-calls browser))))))

;; -----------------------------------------------------------------------------
;; Click Tests
;; -----------------------------------------------------------------------------

(deftest test-click
  (testing "I click {locator} clicks element"
    (let [browser (make-fake-browser)
          ctx (make-ctx-with-browser browser)
          result (invoke-step "I click {:css \"#submit\"}" ctx)]
      (is (map? result))
      (let [calls (get-calls browser)]
        (is (= 1 (count calls)))
        (is (= :click (:op (first calls))))
        (is (= {:css "#submit"} (-> calls first :locator :q)))))))

(deftest test-double-click
  (testing "I double-click {locator} double-clicks element"
    (let [browser (make-fake-browser)
          ctx (make-ctx-with-browser browser)
          result (invoke-step "I double-click {:id \"item\"}" ctx)]
      (is (map? result))
      (let [calls (get-calls browser)]
        (is (= :doubleclick (:op (first calls))))))))

(deftest test-right-click
  (testing "I right-click {locator} right-clicks element"
    (let [browser (make-fake-browser)
          ctx (make-ctx-with-browser browser)
          result (invoke-step "I right-click {:css \".menu\"}" ctx)]
      (is (map? result))
      (let [calls (get-calls browser)]
        (is (= :rightclick (:op (first calls))))))))

;; -----------------------------------------------------------------------------
;; Mouse Tests
;; -----------------------------------------------------------------------------

(deftest test-move-to
  (testing "I move to {locator} moves mouse"
    (let [browser (make-fake-browser)
          ctx (make-ctx-with-browser browser)
          result (invoke-step "I move to {:css \".hover-target\"}" ctx)]
      (is (map? result))
      (let [calls (get-calls browser)]
        (is (= :move-to (:op (first calls))))))))

(deftest test-drag-to
  (testing "I drag {from} to {to} drags element"
    (let [browser (make-fake-browser)
          ctx (make-ctx-with-browser browser)
          result (invoke-step "I drag {:id \"source\"} to {:id \"target\"}" ctx)]
      (is (map? result))
      (let [calls (get-calls browser)]
        (is (= :drag-to (:op (first calls))))
        (is (= {:id "source"} (-> calls first :from :q)))
        (is (= {:id "target"} (-> calls first :to :q)))))))

;; -----------------------------------------------------------------------------
;; Input Tests
;; -----------------------------------------------------------------------------

(deftest test-fill
  (testing "I fill {locator} with '<text>' fills input"
    (let [browser (make-fake-browser)
          ctx (make-ctx-with-browser browser)
          result (invoke-step "I fill {:name \"email\"} with 'test@example.com'" ctx)]
      (is (map? result))
      (let [calls (get-calls browser)]
        (is (= :fill (:op (first calls))))
        (is (= {:name "email"} (-> calls first :locator :q)))
        (is (= "test@example.com" (:text (first calls))))))))

;; -----------------------------------------------------------------------------
;; Query Tests
;; -----------------------------------------------------------------------------

(deftest test-count-elements
  (testing "I count {locator} elements stores count"
    (let [browser (make-fake-browser)
          ctx (make-ctx-with-browser browser)
          result (invoke-step "I count {:css \".item\"} elements" ctx)]
      (is (= 42 (:element-count result)))
      (let [calls (get-calls browser)]
        (is (= :element-count (:op (first calls))))))))

;; -----------------------------------------------------------------------------
;; Vector Locator Syntax Tests
;; -----------------------------------------------------------------------------

(deftest test-vector-locator-syntax
  (testing "Vector locator syntax [:css \"...\"] works"
    (let [browser (make-fake-browser)
          ctx (make-ctx-with-browser browser)
          result (invoke-step "I click [:css \"#login\"]" ctx)]
      (is (map? result))
      (let [calls (get-calls browser)]
        (is (= :click (:op (first calls))))
        ;; Vector normalized to map
        (is (= {:css "#login"} (-> calls first :locator :q)))))))

;; -----------------------------------------------------------------------------
;; Error Handling Tests
;; -----------------------------------------------------------------------------

(deftest test-no-browser-configured
  (testing "throws when no browser in ctx"
    (let [ctx {:step {} :scenario {}}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"No browser configured"
                            (invoke-step "I open the browser to 'https://example.com'" ctx))))))

(deftest test-invalid-locator
  (testing "throws on invalid locator"
    (let [browser (make-fake-browser)
          ctx (make-ctx-with-browser browser)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Invalid locator"
                            (invoke-step "I click {:invalid \"x\"}" ctx))))))

;; -----------------------------------------------------------------------------
;; Acceptance Criteria
;; -----------------------------------------------------------------------------

(deftest test-acceptance-criteria
  (testing "Task 2.5.11 AC: stepdefs call resolve-locator and invoke protocol"
    (let [browser (make-fake-browser)
          ctx (make-ctx-with-browser browser)]
      ;; Navigation
      (invoke-step "I open the browser to 'https://example.com'" ctx)
      ;; Click with resolved locator
      (invoke-step "I click {:css \"a.more\"}" ctx)

      (let [calls (get-calls browser)]
        (is (= 2 (count calls)))
        (is (= :open-to (:op (first calls))))
        (is (= :click (:op (second calls))))))))
