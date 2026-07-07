(ns shiftlefter.adapters.registry-test
  (:require [clojure.test :refer [deftest is testing]]
            [shiftlefter.adapters.registry :as registry]
            [shiftlefter.adapters.etaoin :as etaoin]
            [shiftlefter.adapters.playwright :as playwright]
            [shiftlefter.browser.protocol :as bp]))

;; -----------------------------------------------------------------------------
;; get-adapter Tests
;; -----------------------------------------------------------------------------

(deftest test-get-adapter-known
  (testing "get-adapter returns adapter for known name"
    (let [adapter (registry/get-adapter :etaoin)]
      (is (map? adapter))
      (is (fn? (:factory adapter)))
      (is (fn? (:cleanup adapter)))
      (is (= etaoin/create-browser (:factory adapter)))
      (is (= etaoin/close-browser (:cleanup adapter))))))

(deftest test-get-adapter-playwright
  (testing "get-adapter returns adapter for :playwright"
    (let [adapter (registry/get-adapter :playwright)]
      (is (map? adapter))
      (is (fn? (:factory adapter)))
      (is (fn? (:cleanup adapter)))
      (is (= playwright/create-browser (:factory adapter)))
      (is (= playwright/close-browser (:cleanup adapter))))))

(deftest test-get-adapter-unknown
  (testing "get-adapter returns error for unknown adapter"
    (let [result (registry/get-adapter :unknown)]
      (is (map? (:error result)))
      (is (= :adapter/unknown (-> result :error :type)))
      (is (= :unknown (-> result :error :adapter)))
      (is (contains? (set (-> result :error :known)) :etaoin))
      (is (contains? (set (-> result :error :known)) :playwright)))))

(deftest test-get-adapter-custom-registry
  (testing "get-adapter can use custom registry"
    (let [custom-registry {:custom {:factory identity :cleanup identity}}
          adapter (registry/get-adapter :custom custom-registry)]
      (is (fn? (:factory adapter)))
      (is (= identity (:factory adapter))))
    ;; Default adapter not in custom registry
    (let [result (registry/get-adapter :etaoin {:custom {}})]
      (is (:error result)))))

;; -----------------------------------------------------------------------------
;; known-adapters Tests
;; -----------------------------------------------------------------------------

(deftest test-known-adapters
  (testing "known-adapters returns list of adapter names"
    (let [adapters (registry/known-adapters)]
      (is (vector? adapters))
      (is (contains? (set adapters) :etaoin)))))

(deftest test-known-adapters-custom-registry
  (testing "known-adapters works with custom registry"
    (let [adapters (registry/known-adapters {:a {} :b {} :c {}})]
      (is (= 3 (count adapters)))
      (is (= #{:a :b :c} (set adapters))))))

;; -----------------------------------------------------------------------------
;; default-registry Tests
;; -----------------------------------------------------------------------------

(deftest test-default-registry-structure
  (testing "default-registry has expected structure"
    (is (map? registry/default-registry))
    (is (contains? registry/default-registry :etaoin))
    (is (contains? registry/default-registry :playwright))
    (let [etaoin-adapter (:etaoin registry/default-registry)]
      (is (contains? etaoin-adapter :factory))
      (is (contains? etaoin-adapter :cleanup)))
    (let [pw-adapter (:playwright registry/default-registry)]
      (is (contains? pw-adapter :factory))
      (is (contains? pw-adapter :cleanup)))))

;; -----------------------------------------------------------------------------
;; create-capability Tests (with mock)
;; -----------------------------------------------------------------------------

(deftest test-create-capability-unknown-adapter
  (testing "create-capability returns error for unknown adapter"
    (let [result (registry/create-capability :unknown {})]
      (is (:error result))
      (is (= :adapter/unknown (-> result :error :type))))))

(deftest test-create-capability-with-mock
  (testing "create-capability calls factory with config"
    (let [captured-config (atom nil)
          mock-factory (fn [config]
                         (reset! captured-config config)
                         {:ok {:mock true}})
          mock-registry {:mock {:factory mock-factory :cleanup identity}}
          result (registry/create-capability :mock {:headless true} mock-registry)]
      (is (= {:ok {:mock true}} result))
      (is (= {:headless true} @captured-config)))))

(deftest test-create-capability-factory-error
  (testing "create-capability returns factory's error on failure"
    (let [mock-factory (fn [_config]
                         {:error {:type :adapter/create-failed :message "boom"}})
          mock-registry {:mock {:factory mock-factory :cleanup identity}}
          result (registry/create-capability :mock {} mock-registry)]
      (is (:error result))
      (is (= :adapter/create-failed (-> result :error :type))))))

;; -----------------------------------------------------------------------------
;; cleanup-capability Tests (with mock)
;; -----------------------------------------------------------------------------

(deftest test-cleanup-capability-unknown-adapter
  (testing "cleanup-capability returns error for unknown adapter"
    (let [result (registry/cleanup-capability :unknown {})]
      (is (:error result))
      (is (= :adapter/unknown (-> result :error :type))))))

(deftest test-cleanup-capability-with-mock
  (testing "cleanup-capability calls cleanup with capability"
    (let [captured-capability (atom nil)
          mock-cleanup (fn [capability]
                         (reset! captured-capability capability)
                         {:ok :closed})
          mock-registry {:mock {:factory identity :cleanup mock-cleanup}}
          result (registry/cleanup-capability :mock {:browser :fake} mock-registry)]
      (is (= {:ok :closed} result))
      (is (= {:browser :fake} @captured-capability)))))

;; -----------------------------------------------------------------------------
;; Task 3.0.8 Acceptance Criteria
;; -----------------------------------------------------------------------------

;; -----------------------------------------------------------------------------
;; impl-key + check-extracted-impl Tests (sl-091)
;; -----------------------------------------------------------------------------

(def ^:private ibrowser-kw :shiftlefter.browser.protocol/IBrowser)

(defn- fake-ibrowser
  "An object that satisfies IBrowser (no method bodies needed for satisfies?)."
  []
  (reify bp/IBrowser))

(deftest test-impl-key-accessor
  (testing "browser adapters declare :impl-key :browser"
    (is (= :browser (registry/impl-key :etaoin)))
    (is (= :browser (registry/impl-key :playwright))))
  (testing "SMS adapters declare no :impl-key"
    (is (nil? (registry/impl-key :sms-mock)))
    (is (nil? (registry/impl-key :sms-twilio))))
  (testing "custom registry is honored"
    (is (= :foo (registry/impl-key :a {:a {:impl-key :foo}})))
    (is (nil? (registry/impl-key :a {:a {}})))))

(deftest test-check-extracted-impl-satisfied
  (testing "returns nil when the impl satisfies every declared protocol"
    (let [reg {:webwrap {:impl-key :browser
                         :provides [ibrowser-kw]}}]
      (is (nil? (registry/check-extracted-impl :webwrap (fake-ibrowser) reg))))))

(deftest test-check-extracted-impl-unsatisfied
  (testing "returns the unsatisfied protocols when the impl is the wrong shape"
    (let [reg {:webwrap {:impl-key :browser
                         :provides [ibrowser-kw]}}
          ;; A plain map is what the sl-091 bug produced — the wrapper, not
          ;; the IBrowser inside it.
          result (registry/check-extracted-impl :webwrap {:browser :nested} reg)]
      (is (= [ibrowser-kw] result)))))

(deftest test-check-extracted-impl-exempts-no-impl-key
  (testing "adapters without :impl-key are exempt (SMS bundle path)"
    ;; The bundle does not satisfy ISMS — only the nested :sms does — but
    ;; without :impl-key the check must not run.
    (let [reg {:sms {:provides [:shiftlefter.sms.protocol/ISMS]}}]
      (is (nil? (registry/check-extracted-impl :sms {:sms :nested :from-number "x"} reg))))))

(deftest test-check-extracted-impl-unresolvable-protocol
  (testing "an unresolvable :provides keyword is reported as unsatisfied"
    (let [reg {:webwrap {:impl-key :browser
                         :provides [:no.such.ns/NopeProtocol]}}]
      (is (= [:no.such.ns/NopeProtocol]
             (registry/check-extracted-impl :webwrap (fake-ibrowser) reg))))))

(deftest acceptance-adapter-registry-test
  (testing "Task 3.0.8 AC: get-adapter for known adapter"
    (let [adapter (registry/get-adapter :etaoin)]
      (is (fn? (:factory adapter)))
      (is (fn? (:cleanup adapter)))))

  (testing "Task 3.0.8 AC: get-adapter for unknown adapter"
    (let [result (registry/get-adapter :unknown)]
      (is (:error result))
      (is (= :adapter/unknown (-> result :error :type)))
      (is (= :unknown (-> result :error :adapter)))
      (is (seq (-> result :error :known))))))
