(ns shiftlefter.capabilities.ctx-test
  (:require [clojure.test :refer [deftest is testing]]
            [shiftlefter.capabilities.ctx :as cap]))

;; -----------------------------------------------------------------------------
;; capability-key Tests
;; -----------------------------------------------------------------------------

(deftest test-capability-key
  (testing "capability-key constructs namespaced keyword"
    (is (= :cap/web (cap/capability-key :web)))
    (is (= :cap/api (cap/capability-key :api)))
    (is (= :cap/sms (cap/capability-key :sms)))))

;; -----------------------------------------------------------------------------
;; capability-present? Tests
;; -----------------------------------------------------------------------------

(deftest test-capability-present-false
  (testing "capability-present? returns false for empty ctx"
    (is (false? (cap/capability-present? {} :web)))))

(deftest test-capability-present-true
  (testing "capability-present? returns true when capability exists"
    (let [ctx {:cap/web {:impl :fake-browser :mode :ephemeral}}]
      (is (true? (cap/capability-present? ctx :web))))))

(deftest test-capability-present-other-capability
  (testing "capability-present? returns false when different capability exists"
    (let [ctx {:cap/api {:impl :fake-api :mode :ephemeral}}]
      (is (false? (cap/capability-present? ctx :web)))
      (is (true? (cap/capability-present? ctx :api))))))

;; -----------------------------------------------------------------------------
;; get-capability Tests
;; -----------------------------------------------------------------------------

(deftest test-get-capability-nil
  (testing "get-capability returns nil for missing capability"
    (is (nil? (cap/get-capability {} :web)))))

(deftest test-get-capability-returns-impl
  (testing "get-capability returns the :impl value"
    (let [browser {:type :etaoin}
          ctx {:cap/web {:impl browser :mode :ephemeral}}]
      (is (= browser (cap/get-capability ctx :web))))))

;; -----------------------------------------------------------------------------
;; get-capability-mode Tests
;; -----------------------------------------------------------------------------

(deftest test-get-capability-mode-nil
  (testing "get-capability-mode returns nil for missing capability"
    (is (nil? (cap/get-capability-mode {} :web)))))

(deftest test-get-capability-mode-ephemeral
  (testing "get-capability-mode returns :ephemeral"
    (let [ctx {:cap/web {:impl :fake :mode :ephemeral}}]
      (is (= :ephemeral (cap/get-capability-mode ctx :web))))))

(deftest test-get-capability-mode-persistent
  (testing "get-capability-mode returns :persistent"
    (let [ctx {:cap/web {:impl :fake :mode :persistent}}]
      (is (= :persistent (cap/get-capability-mode ctx :web))))))

;; -----------------------------------------------------------------------------
;; get-capability-entry Tests
;; -----------------------------------------------------------------------------

(deftest test-get-capability-entry
  (testing "get-capability-entry returns full entry"
    (let [entry {:impl :fake-browser :mode :ephemeral}
          ctx {:cap/web entry}]
      (is (= entry (cap/get-capability-entry ctx :web))))))

;; -----------------------------------------------------------------------------
;; assoc-capability Tests
;; -----------------------------------------------------------------------------

(deftest test-assoc-capability
  (testing "assoc-capability adds capability to ctx"
    (let [browser {:type :etaoin}
          ctx (cap/assoc-capability {} :web browser :ephemeral)]
      (is (= {:cap/web {:impl browser :mode :ephemeral}} ctx)))))

(deftest test-assoc-capability-multiple
  (testing "assoc-capability can add multiple capabilities"
    (let [ctx (-> {}
                  (cap/assoc-capability :web :browser :ephemeral)
                  (cap/assoc-capability :api :client :persistent))]
      (is (cap/capability-present? ctx :web))
      (is (cap/capability-present? ctx :api))
      (is (= :browser (cap/get-capability ctx :web)))
      (is (= :client (cap/get-capability ctx :api))))))

(deftest test-assoc-capability-overwrites
  (testing "assoc-capability overwrites existing capability"
    (let [ctx (-> {}
                  (cap/assoc-capability :web :old :ephemeral)
                  (cap/assoc-capability :web :new :persistent))]
      (is (= :new (cap/get-capability ctx :web)))
      (is (= :persistent (cap/get-capability-mode ctx :web))))))

;; -----------------------------------------------------------------------------
;; dissoc-capability Tests
;; -----------------------------------------------------------------------------

(deftest test-dissoc-capability
  (testing "dissoc-capability removes capability"
    (let [ctx (-> {}
                  (cap/assoc-capability :web :browser :ephemeral)
                  (cap/dissoc-capability :web))]
      (is (false? (cap/capability-present? ctx :web))))))

(deftest test-dissoc-capability-preserves-others
  (testing "dissoc-capability preserves other capabilities"
    (let [ctx (-> {}
                  (cap/assoc-capability :web :browser :ephemeral)
                  (cap/assoc-capability :api :client :persistent)
                  (cap/dissoc-capability :web))]
      (is (false? (cap/capability-present? ctx :web)))
      (is (true? (cap/capability-present? ctx :api))))))

;; -----------------------------------------------------------------------------
;; update-capability Tests
;; -----------------------------------------------------------------------------

(deftest test-update-capability
  (testing "update-capability updates impl, preserves mode"
    (let [ctx (-> {}
                  (cap/assoc-capability :web :old :ephemeral)
                  (cap/update-capability :web :new))]
      (is (= :new (cap/get-capability ctx :web)))
      (is (= :ephemeral (cap/get-capability-mode ctx :web))))))

(deftest test-update-capability-missing
  (testing "update-capability does nothing if capability missing"
    (let [ctx (cap/update-capability {} :web :browser)]
      (is (= {} ctx)))))

;; -----------------------------------------------------------------------------
;; all-capabilities Tests
;; -----------------------------------------------------------------------------

(deftest test-all-capabilities-empty
  (testing "all-capabilities returns empty map for empty ctx"
    (is (= {} (cap/all-capabilities {})))))

(deftest test-all-capabilities
  (testing "all-capabilities returns all capabilities"
    (let [ctx (-> {}
                  (cap/assoc-capability :web :browser :ephemeral)
                  (cap/assoc-capability :api :client :persistent)
                  (assoc :other-key :value))  ;; non-capability key
          caps (cap/all-capabilities ctx)]
      (is (= 2 (count caps)))
      (is (contains? caps :web))
      (is (contains? caps :api))
      (is (not (contains? caps :other-key))))))

;; -----------------------------------------------------------------------------
;; capability-names Tests
;; -----------------------------------------------------------------------------

(deftest test-capability-names
  (testing "capability-names returns interface names"
    (let [ctx (-> {}
                  (cap/assoc-capability :web :browser :ephemeral)
                  (cap/assoc-capability :api :client :persistent))
          names (cap/capability-names ctx)]
      (is (= #{:web :api} (set names))))))

;; -----------------------------------------------------------------------------
;; ephemeral-capabilities Tests
;; -----------------------------------------------------------------------------

(deftest test-ephemeral-capabilities
  (testing "ephemeral-capabilities returns only ephemeral"
    (let [ctx (-> {}
                  (cap/assoc-capability :web :browser :ephemeral)
                  (cap/assoc-capability :api :client :persistent)
                  (cap/assoc-capability :sms :sender :ephemeral))
          ephemeral (cap/ephemeral-capabilities ctx)]
      (is (= #{:web :sms} (set ephemeral))))))

;; -----------------------------------------------------------------------------
;; persistent-capabilities Tests
;; -----------------------------------------------------------------------------

(deftest test-persistent-capabilities
  (testing "persistent-capabilities returns only persistent"
    (let [ctx (-> {}
                  (cap/assoc-capability :web :browser :ephemeral)
                  (cap/assoc-capability :api :client :persistent))
          persistent (cap/persistent-capabilities ctx)]
      (is (= [:api] persistent)))))

;; -----------------------------------------------------------------------------
;; Task 3.0.9 Acceptance Criteria
;; -----------------------------------------------------------------------------

(deftest acceptance-capability-ctx-test
  (testing "Task 3.0.9 AC: capability-present? on empty ctx"
    (is (false? (cap/capability-present? {} :web))))

  (testing "Task 3.0.9 AC: assoc-capability and get-capability"
    (let [browser-impl {:type :etaoin :driver :fake}
          ctx (cap/assoc-capability {} :web browser-impl :ephemeral)]
      (is (true? (cap/capability-present? ctx :web)))
      (is (= browser-impl (cap/get-capability ctx :web)))))

  (testing "Task 3.0.9 AC: ctx shape"
    (let [browser-impl {:type :etaoin}
          ctx (cap/assoc-capability {} :web browser-impl :ephemeral)]
      ;; Shape should be {:cap/web {:impl <browser> :mode :ephemeral}}
      (is (= {:cap/web {:impl browser-impl :mode :ephemeral}} ctx)))))
