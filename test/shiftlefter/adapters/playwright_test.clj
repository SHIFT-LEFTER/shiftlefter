(ns shiftlefter.adapters.playwright-test
  (:require [clojure.test :refer [deftest is testing]]
            [shiftlefter.adapters.playwright :as playwright]
            [shiftlefter.adapters.registry :as registry]
            [shiftlefter.browser.protocol :as bp]))

;; =============================================================================
;; Shared
;; =============================================================================

(def ^:private live-playwright?
  (= "1" (System/getenv "SHIFTLEFTER_LIVE_PLAYWRIGHT")))

;; =============================================================================
;; Unit Tests (no live browser required)
;; =============================================================================

(deftest test-playwright-adapter-in-registry
  (testing "Playwright adapter is registered"
    (let [adapter (registry/get-adapter :playwright)]
      (is (map? adapter))
      (is (fn? (:factory adapter)))
      (is (fn? (:cleanup adapter))))))

(deftest test-create-browser-error-handling
  (if live-playwright?
    (testing "create-browser returns structured error on failure"
      ;; Test with an invalid browser-type to trigger a runtime error.
      ;; The adapter should never throw — always return {:error ...}.
      (let [result (playwright/create-browser
                    {:headless true :adapter-opts {:browser-type :invalid}})]
        (is (:error result) "Invalid browser-type should return error, not throw")
        (is (= :adapter/create-failed (-> result :error :type)))))
    (testing "skipped - no SHIFTLEFTER_LIVE_PLAYWRIGHT=1"
      (is true))))

;; =============================================================================
;; Integration Tests (require live Playwright browser)
;; =============================================================================

(deftest test-playwright-adapter-create-close
  (if live-playwright?
    (testing "create-browser and close-browser via adapter"
      (let [result (playwright/create-browser {:headless true})]
        (is (:ok result) "create-browser should succeed")
        (let [{:keys [browser]} (:ok result)]
          (is (some? browser))
          (is (satisfies? bp/IBrowser browser))
          ;; Quick smoke test
          (bp/open-to! browser "https://example.com")
          (is (= "Example Domain" (bp/get-title browser)))
          ;; Clean up
          (let [close-result (playwright/close-browser {:browser browser})]
            (is (= {:ok :closed} close-result))))))
    (testing "skipped - no SHIFTLEFTER_LIVE_PLAYWRIGHT=1"
      (is true))))

(deftest test-playwright-adapter-opts-passthrough
  (if live-playwright?
    (testing "adapter-opts are passed through to Playwright"
      ;; Test with :browser-type option
      (let [result (playwright/create-browser
                    {:headless true
                     :adapter-opts {:browser-type :chromium}})]
        (is (:ok result) "create-browser with adapter-opts should succeed")
        (when (:ok result)
          (let [{:keys [browser]} (:ok result)]
            (bp/open-to! browser "https://example.com")
            (is (= "Example Domain" (bp/get-title browser)))
            (playwright/close-browser {:browser browser})))))
    (testing "skipped - no SHIFTLEFTER_LIVE_PLAYWRIGHT=1"
      (is true))))

(deftest test-playwright-via-registry
  (if live-playwright?
    (testing "create-capability :playwright works via registry"
      (let [result (registry/create-capability :playwright {:headless true})]
        (is (:ok result) "create-capability should succeed")
        (when (:ok result)
          (let [{:keys [browser]} (:ok result)]
            (is (satisfies? bp/IBrowser browser))
            (registry/cleanup-capability :playwright {:browser browser})))))
    (testing "skipped - no SHIFTLEFTER_LIVE_PLAYWRIGHT=1"
      (is true))))
