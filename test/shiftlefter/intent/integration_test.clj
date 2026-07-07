(ns shiftlefter.intent.integration-test
  "Integration tests for the intent resolution system."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [shiftlefter.intent.loader :as loader]
            [shiftlefter.intent.resolve :as resolve]
            [shiftlefter.intent.state :as state]
            [babashka.fs :as fs]))

;; -----------------------------------------------------------------------------
;; Test Fixtures
;; -----------------------------------------------------------------------------

(defn- create-test-intents-dir
  "Create a temp directory with intent files for testing."
  [files]
  (let [dir (fs/create-temp-dir {:prefix "intents-integration-test-"})]
    (doseq [[filename content] files]
      (spit (fs/file dir filename) content))
    dir))

(defn- cleanup-temp-dir [dir]
  (fs/delete-tree dir))

;; Clear intents state between tests
(use-fixtures :each
  (fn [f]
    (state/clear-intents!)
    (f)
    (state/clear-intents!)))

;; -----------------------------------------------------------------------------
;; Full Integration Tests
;; -----------------------------------------------------------------------------

(deftest test-intent-resolution-end-to-end
  (let [dir (create-test-intents-dir
             {"login.edn"
              (pr-str {:intent "Login"
                       :elements
                       {:email {:bindings {:web {:css "#email-input"}
                                           :mobile {:accessibility-id "email-field"}}}
                        :password {:bindings {:web {:css "#password-input"}}}
                        :submit {:bindings {:web {:css "button.login-submit"}}}}})})]
    (try
      ;; Load intents from test directory
      (let [intents (:ok (loader/load-all-intents (str dir)))]

        (testing "Basic intent resolution"
          (let [result (resolve/resolve-intent-string intents "Login.email" :web)]
            (is (:ok result))
            (is (= {:css "#email-input"} (:ok result)))))

        (testing "Mobile interface resolution"
          (let [result (resolve/resolve-intent-string intents "Login.email" :mobile)]
            (is (:ok result))
            (is (= {:accessibility-id "email-field"} (:ok result)))))

        ;; sl-nrv: resolution returns the BASE binding for every index form;
        ;; the index is applied at the browser boundary (Nth match).
        (testing "Intent with positive index — base binding"
          (let [result (resolve/resolve-intent-string intents "Login.submit[1]" :web)]
            (is (:ok result))
            (is (= {:css "button.login-submit"} (:ok result)))))

        (testing "Intent with negative index — base binding"
          (let [result (resolve/resolve-intent-string intents "Login.submit[-1]" :web)]
            (is (:ok result))
            (is (= {:css "button.login-submit"} (:ok result)))))

        (testing "Intent with wildcard index — base binding"
          (let [result (resolve/resolve-intent-string intents "Login.submit[*]" :web)]
            (is (:ok result))
            (is (= {:css "button.login-submit"} (:ok result)))))

        (testing "Unknown intent returns error"
          (let [result (resolve/resolve-intent-string intents "Unknown.submit" :web)]
            (is (:error result))
            (is (= :intent/unknown-element (-> result :error :type)))))

        (testing "Unknown element returns error"
          (let [result (resolve/resolve-intent-string intents "Login.unknown" :web)]
            (is (:error result))
            (is (= :intent/unknown-element (-> result :error :type)))))

        (testing "Missing interface binding returns error"
          (let [result (resolve/resolve-intent-string intents "Login.password" :mobile)]
            (is (:error result))
            (is (= :intent/no-binding-for-interface (-> result :error :type))))))

      (finally
        (cleanup-temp-dir dir)))))

(deftest test-intent-ref-detection
  (testing "Intent reference detection"
    (is (resolve/intent-ref? "Login.submit"))
    (is (resolve/intent-ref? "Login.submit[1]"))
    (is (resolve/intent-ref? "Checkout.pay-button"))
    (is (not (resolve/intent-ref? "{:css \"#foo\"}")))
    (is (not (resolve/intent-ref? "")))
    (is (not (resolve/intent-ref? nil)))))

(deftest test-state-memoization
  (let [dir (create-test-intents-dir
             {"test.edn"
              (pr-str {:intent "Test"
                       :elements
                       {:item {:bindings {:web {:css ".test-item"}}}}})})]
    (try
      ;; First call loads
      (let [intents1 (state/reload-intents! (str dir))]
        (is (some? intents1))
        (is (state/intents-loaded?))

        ;; Second call returns memoized
        (let [intents2 (state/get-intents)]
          (is (= intents1 intents2) "Should return same intents object")))

      (finally
        (cleanup-temp-dir dir)))))
