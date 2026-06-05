(ns shiftlefter.test-helpers.adapter-registry-test
  "Unit tests for the mock adapter registry helper.

   Focuses on the silent-failure modes — `:fail?` and `:on-provision` —
   because if the helper claims to wire those and silently doesn't,
   every consumer test passes vacuously."
  (:require [clojure.test :refer [deftest is testing]]
            [shiftlefter.test-helpers.adapter-registry :as mock]))

;; -----------------------------------------------------------------------------
;; registry — shape and defaults
;; -----------------------------------------------------------------------------

(deftest test-registry-empty
  (testing "Empty spec map yields empty registry"
    (is (= {} (mock/registry {})))))

(deftest test-registry-default-impl
  (testing "Factory returns the default impl when :impl is omitted"
    (let [r (mock/registry {:a {}})]
      (is (= {:ok {:type :test-impl}}
             ((get-in r [:a :factory]) {})))
      (is (= [] (get-in r [:a :provides])))
      (is (not (contains? (get r :a) :on-provision))
          "Adapter without hook should not have :on-provision key"))))

(deftest test-registry-custom-impl
  (testing "Factory returns the configured :impl"
    (let [r (mock/registry {:web {:impl {:browser :fake-driver}}})]
      (is (= {:ok {:browser :fake-driver}}
             ((get-in r [:web :factory]) {}))))))

(deftest test-registry-provides
  (testing ":provides is passed through unchanged"
    (let [r (mock/registry {:sms {:provides [:shiftlefter.sms.protocol/ISMS
                                             :shiftlefter.sms.protocol/ISMSInbound]}})]
      (is (= [:shiftlefter.sms.protocol/ISMS
              :shiftlefter.sms.protocol/ISMSInbound]
             (get-in r [:sms :provides]))))))

;; -----------------------------------------------------------------------------
;; :fail? — silent-failure mode #1
;; -----------------------------------------------------------------------------

(deftest test-registry-fail-default-error
  (testing ":fail? true → factory returns :error with the default error map"
    (let [r (mock/registry {:flaky {:fail? true}})
          result ((get-in r [:flaky :factory]) {})]
      (is (contains? result :error))
      (is (not (contains? result :ok)))
      (is (= :adapter/test-failure (-> result :error :type))))))

(deftest test-registry-fail-custom-error
  (testing ":fail? true with explicit :error returns that error verbatim"
    (let [my-err {:type :adapter/chromedriver-missing :detail "PATH"}
          r (mock/registry {:flaky {:fail? true :error my-err}})
          result ((get-in r [:flaky :factory]) {})]
      (is (= {:error my-err} result)))))

;; -----------------------------------------------------------------------------
;; :on-provision — silent-failure mode #2
;; -----------------------------------------------------------------------------

(deftest test-registry-on-provision-absent
  (testing "No :on-provision in spec → no :on-provision key in entry"
    (let [r (mock/registry {:a {:impl {}}})]
      (is (not (contains? (get r :a) :on-provision))
          "Matches production: absence, not nil"))))

(deftest test-registry-on-provision-wrapped
  (testing ":on-provision hook is wrapped, fires on call, returns hook's value"
    (let [calls (atom 0)
          hook (fn [ctx _impl] (swap! calls inc) (assoc ctx :hook/fired true))
          r (mock/registry {:a {:on-provision hook}})
          wrapped (get-in r [:a :on-provision])
          ctx' (wrapped {} {:impl :anything})]
      (is (= 1 @calls) "Hook fired exactly once")
      (is (= {:hook/fired true} ctx') "Hook return value is the wrapped fn's return"))))

;; -----------------------------------------------------------------------------
;; Cleanup
;; -----------------------------------------------------------------------------

(deftest test-registry-cleanup-default
  (testing "Default cleanup returns {:ok :closed}"
    (let [r (mock/registry {:a {}})]
      (is (= {:ok :closed} ((get-in r [:a :cleanup]) :any-impl))))))

(deftest test-registry-cleanup-custom
  (testing "Custom cleanup is called with the impl and its return passes through"
    (let [seen (atom nil)
          my-cleanup (fn [impl] (reset! seen impl) {:ok :custom-closed})
          r (mock/registry {:a {:cleanup my-cleanup}})]
      (is (= {:ok :custom-closed} ((get-in r [:a :cleanup]) :impl-x)))
      (is (= :impl-x @seen) "Cleanup saw the impl"))))

;; -----------------------------------------------------------------------------
;; Events — tracking convention
;; -----------------------------------------------------------------------------

(deftest test-registry-events-provision
  (testing "Provision event is recorded under adapter name when factory succeeds"
    (let [events (atom [])
          r (mock/registry {:web {:events events}})]
      ((get-in r [:web :factory]) {})
      (is (= [[:provision :web]] @events)))))

(deftest test-registry-events-provision-failed
  (testing ":provision-failed event is recorded when factory fails"
    (let [events (atom [])
          r (mock/registry {:flaky {:fail? true :events events}})]
      ((get-in r [:flaky :factory]) {})
      (is (= [[:provision-failed :flaky]] @events)))))

(deftest test-registry-events-on-provision
  (testing ":on-provision event is recorded when hook fires"
    (let [events (atom [])
          hook (fn [ctx _impl] ctx)
          r (mock/registry {:a {:on-provision hook :events events}})]
      ((get-in r [:a :on-provision]) {} {:impl :any})
      (is (= [[:on-provision :a]] @events)))))

(deftest test-registry-events-cleanup
  (testing ":cleanup event is recorded when cleanup runs"
    (let [events (atom [])
          r (mock/registry {:a {:events events}})]
      ((get-in r [:a :cleanup]) :impl)
      (is (= [[:cleanup :a]] @events)))))

(deftest test-registry-events-full-cycle
  (testing "Provision → on-provision → cleanup is recorded in order on a single atom"
    (let [events (atom [])
          hook (fn [ctx _impl] ctx)
          r (mock/registry {:a {:on-provision hook :events events}})]
      ((get-in r [:a :factory]) {})
      ((get-in r [:a :on-provision]) {} {:impl :x})
      ((get-in r [:a :cleanup]) :impl-x)
      (is (= [[:provision :a]
              [:on-provision :a]
              [:cleanup :a]]
             @events)))))

(deftest test-registry-events-shared-across-adapters
  (testing "Same events atom records both adapters' provisions in call order"
    (let [events (atom [])
          r (mock/registry {:web {:events events}
                            :sms {:events events}})]
      ((get-in r [:web :factory]) {})
      ((get-in r [:sms :factory]) {})
      (is (= [[:provision :web] [:provision :sms]] @events)))))

(deftest test-registry-events-nil-no-recording
  (testing "No :events atom → no recording, no error"
    (let [r (mock/registry {:a {}})]
      (is (= {:ok {:type :test-impl}} ((get-in r [:a :factory]) {})))
      (is (= {:ok :closed} ((get-in r [:a :cleanup]) :impl))))))

;; -----------------------------------------------------------------------------
;; interfaces — shape and defaults
;; -----------------------------------------------------------------------------

(deftest test-interfaces-empty
  (testing "Empty interfaces spec yields empty config"
    (is (= {} (mock/interfaces {})))))

(deftest test-interfaces-defaults
  (testing ":type defaults to interface name, no :shared-impl?, empty :config"
    (let [i (mock/interfaces {:web {:adapter :web}})]
      (is (= {:type :web :adapter :web :config {}}
             (get i :web)))
      (is (not (contains? (get i :web) :shared-impl?))))))

(deftest test-interfaces-shared-impl
  (testing ":shared-impl? true is included; false would be omitted (matches production)"
    (let [i (mock/interfaces {:sms {:adapter :sms-mock :shared-impl? true}})]
      (is (= true (get-in i [:sms :shared-impl?]))))))

(deftest test-interfaces-custom-type
  (testing ":type override is respected"
    (let [i (mock/interfaces {:mobile {:adapter :appium :type :web}})]
      (is (= :web (get-in i [:mobile :type]))))))

(deftest test-interfaces-custom-config
  (testing ":config is passed through unchanged"
    (let [i (mock/interfaces {:web {:adapter :etaoin :config {:headless true}}})]
      (is (= {:headless true} (get-in i [:web :config]))))))
