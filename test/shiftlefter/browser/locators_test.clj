(ns shiftlefter.browser.locators-test
  (:require [clojure.test :refer [deftest is testing]]
            [shiftlefter.browser.locators :as loc]))

;; -----------------------------------------------------------------------------
;; Map locators
;; -----------------------------------------------------------------------------

(deftest resolve-map-locators-test
  (testing "css selector"
    (is (= {:q {:css "#login"}} (loc/resolve-locator {:css "#login"}))))

  (testing "xpath selector"
    (is (= {:q {:xpath "//div[@id='main']"}}
           (loc/resolve-locator {:xpath "//div[@id='main']"}))))

  (testing "id selector"
    (is (= {:q {:id "submit-btn"}} (loc/resolve-locator {:id "submit-btn"}))))

  (testing "tag selector"
    (is (= {:q {:tag "button"}} (loc/resolve-locator {:tag "button"}))))

  (testing "class selector"
    (is (= {:q {:class "btn-primary"}} (loc/resolve-locator {:class "btn-primary"}))))

  (testing "name selector"
    (is (= {:q {:name "email"}} (loc/resolve-locator {:name "email"})))))

(deftest resolve-map-errors-test
  (testing "empty map"
    (let [result (loc/resolve-locator {})]
      (is (= :browser/selector-invalid (-> result :errors first :type)))))

  (testing "map with unknown key"
    (let [result (loc/resolve-locator {:nope "#x"})]
      (is (= :browser/selector-invalid (-> result :errors first :type)))))

  (testing "map with multiple selector keys"
    (let [result (loc/resolve-locator {:css "#x" :id "y"})]
      (is (= :browser/selector-invalid (-> result :errors first :type)))
      (is (= #{:css :id} (-> result :errors first :data :found-keys))))))

;; -----------------------------------------------------------------------------
;; Vector locators
;; -----------------------------------------------------------------------------

(deftest resolve-vector-locators-test
  (testing "css vector"
    (is (= {:q {:css "#login"}} (loc/resolve-locator [:css "#login"]))))

  (testing "xpath vector"
    (is (= {:q {:xpath "//div"}} (loc/resolve-locator [:xpath "//div"]))))

  (testing "id vector"
    (is (= {:q {:id "main"}} (loc/resolve-locator [:id "main"]))))

  (testing "tag vector"
    (is (= {:q {:tag "input"}} (loc/resolve-locator [:tag "input"]))))

  (testing "class vector"
    (is (= {:q {:class "active"}} (loc/resolve-locator [:class "active"]))))

  (testing "name vector"
    (is (= {:q {:name "password"}} (loc/resolve-locator [:name "password"])))))

(deftest resolve-vector-errors-test
  (testing "unknown selector type"
    (let [result (loc/resolve-locator [:nope "#x"])]
      (is (= :browser/selector-invalid (-> result :errors first :type)))
      (is (= :nope (-> result :errors first :data :selector-type)))))

  (testing "wrong arity - too few"
    (let [result (loc/resolve-locator [:css])]
      (is (= :browser/selector-invalid (-> result :errors first :type)))))

  (testing "wrong arity - too many"
    (let [result (loc/resolve-locator [:css "#x" "extra"])]
      (is (= :browser/selector-invalid (-> result :errors first :type)))))

  (testing "wrong types"
    (let [result (loc/resolve-locator ["css" "#x"])]
      (is (= :browser/selector-invalid (-> result :errors first :type))))))

;; -----------------------------------------------------------------------------
;; Passthrough (string/keyword)
;; -----------------------------------------------------------------------------

(deftest resolve-passthrough-test
  (testing "string passthrough"
    (is (= {:q "#login"} (loc/resolve-locator "#login")))
    (is (= {:q "//div"} (loc/resolve-locator "//div"))))

  (testing "keyword passthrough"
    (is (= {:q :submit} (loc/resolve-locator :submit)))
    (is (= {:q :login-button} (loc/resolve-locator :login-button)))))

;; -----------------------------------------------------------------------------
;; Invalid types
;; -----------------------------------------------------------------------------

(deftest resolve-invalid-types-test
  (testing "number"
    (let [result (loc/resolve-locator 42)]
      (is (= :browser/selector-invalid (-> result :errors first :type)))))

  (testing "nil"
    (let [result (loc/resolve-locator nil)]
      (is (= :browser/selector-invalid (-> result :errors first :type))))))

;; -----------------------------------------------------------------------------
;; Helper functions
;; -----------------------------------------------------------------------------

(deftest valid?-test
  (testing "valid locator"
    (is (true? (loc/valid? {:q {:css "#x"}}))))

  (testing "invalid locator"
    (is (false? (loc/valid? {:errors [{:type :browser/selector-invalid}]})))))

(deftest errors-test
  (testing "returns nil for valid"
    (is (nil? (loc/errors {:q {:css "#x"}}))))

  (testing "returns errors for invalid"
    (let [errs [{:type :browser/selector-invalid}]]
      (is (= errs (loc/errors {:errors errs}))))))

;; -----------------------------------------------------------------------------
;; Acceptance criteria from spec
;; -----------------------------------------------------------------------------

(deftest acceptance-criteria-test
  (testing "map form"
    (is (= {:q {:css "#login"}} (loc/resolve-locator {:css "#login"}))))

  (testing "vector form"
    (is (= {:q {:css "#login"}} (loc/resolve-locator [:css "#login"]))))

  (testing "invalid vector"
    (let [bad (loc/resolve-locator [:nope "#x"])]
      (is (= :browser/selector-invalid (-> bad :errors first :type))))))
