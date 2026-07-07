(ns shiftlefter.browser.errors-test
  "Tests for the shared WebDriver error classifier (sl-jxi). Feeds synthetic
   exceptions shaped like etaoin 1.1.42's `:etaoin/http-error` ex-data
   (`{:type :etaoin/http-error :response {:value {:error <w3c-code>}}}`) and the
   legacy message-only surfaces, asserting each documented transient class is
   retryable and — critically — that non-transient errors are NOT (the
   over-match guard)."
  (:require [clojure.test :refer [deftest is testing]]
            [shiftlefter.browser.errors :as errors]))

(defn- http-error
  "A synthetic etaoin :etaoin/http-error carrying a W3C error code, matching the
   real ex-data shape (etaoin.impl.client)."
  [w3c-code]
  (ex-info (str "WebDriver error: " w3c-code)
           {:type :etaoin/http-error
            :status 404
            :response {:value {:error w3c-code
                               :message (str w3c-code " details")}}}))

(deftest transient-dom-error-structured-codes
  (testing "each transient W3C code (structured) is retryable"
    (doseq [code ["stale element reference" "no such element" "detached shadow root"]]
      (is (errors/transient-dom-error? (http-error code))
          (str code " should be a transient DOM error"))
      (is (= code (errors/webdriver-error-code (http-error code)))
          "code is extracted from [:response :value :error]"))))

(deftest transient-dom-error-legacy-message-surfaces
  (testing "legacy message-only surfaces stay retryable (no clean W3C code)"
    (is (errors/transient-dom-error?
         (ex-info "stale element: handle is stale" {}))
        "older 'stale element' message path")
    (is (errors/transient-dom-error?
         (ex-info "org.openqa...StaleElementReferenceException: x" {}))
        "Java StaleElementReferenceException message")
    (is (errors/transient-dom-error?
         (ex-info "no such element: Unable to locate" {}))
        "'no such element' message path"))

  (testing "the sl-bnk chromedriver inspector error stays covered"
    ;; DevTools inspector message — NO W3C code; message is the only signal.
    (is (errors/transient-dom-error?
         (ex-info "Node with given id does not belong to the document" {}))
        "sl-bnk navigation-race case must remain retryable")))

(deftest non-transient-errors-are-not-retryable
  (testing "EXCLUDED W3C codes are NOT treated as transient (over-match guard)"
    (doseq [code ["element click intercepted" "element not interactable"
                  "invalid element state" "move target out of bounds"]]
      (is (not (errors/transient-dom-error? (http-error code)))
          (str code " must NOT auto-retry — it usually signals a real bug"))))

  (testing "session-death and arbitrary errors are not transient DOM errors"
    (is (not (errors/transient-dom-error? (http-error "invalid session id"))))
    (is (not (errors/transient-dom-error? (ex-info "boom" {}))))
    (is (not (errors/transient-dom-error? (ex-info "assertion failed: 1 != 2" {}))))))

(deftest webdriver-error-code-extraction
  (testing "returns nil when there is no structured WebDriver response"
    (is (nil? (errors/webdriver-error-code (ex-info "plain" {}))))
    (is (nil? (errors/webdriver-error-code (ex-info "x" {:type :other}))))))
