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

;; -----------------------------------------------------------------------------
;; Error class (sl-kowi): the transport's no-answer edge
;; -----------------------------------------------------------------------------

(defn- http-ex
  "A synthetic etaoin :etaoin/http-ex — the request itself THREW (etaoin
   1.1.42 etaoin.impl.client/call), matching the real ex-data shape."
  []
  (ex-info "throw+: {...}"
           {:type :etaoin/http-ex :host "127.0.0.1" :port 9515
            :method :post :path "session/abc/url"}
           (java.net.ConnectException. "Connection refused")))

(deftest observation-error-is-the-http-ex-edge-only
  (testing ":etaoin/http-ex (no response existed) is the observation edge"
    (is (errors/observation-error? (http-ex))))
  (testing ":etaoin/http-error is an ANSWER from the driver — never classified here"
    (doseq [code ["no such element" "invalid session id" "unknown error"]]
      (is (not (errors/observation-error? (http-error code))) code)))
  (testing "arbitrary throws are not"
    (is (not (errors/observation-error? (ex-info "boom" {}))))))

(deftest classify-observation-labels-only-the-edge
  (testing "an http-ex is rethrown with :error/class :observation, message + data kept, cause chained"
    (let [thrown (try (errors/classify-observation #(throw (http-ex)))
                      nil
                      (catch clojure.lang.ExceptionInfo e e))]
      (is (= :observation (:error/class (ex-data thrown))))
      (is (= :etaoin/http-ex (:type (ex-data thrown))))
      (is (= 9515 (:port (ex-data thrown))) "the original ex-data is merged, not replaced")
      (is (= "throw+: {...}" (ex-message thrown)))
      (is (= :etaoin/http-ex (:type (ex-data (ex-cause thrown)))) "original is the cause")))
  (testing "every other throw passes through as the SAME object"
    (let [original (http-error "no such element")
          thrown (try (errors/classify-observation #(throw original))
                      nil
                      (catch clojure.lang.ExceptionInfo e e))]
      (is (identical? original thrown)))
    (is (thrown? ArithmeticException (errors/classify-observation #(/ 1 0)))))
  (testing "a non-throwing f returns its value"
    (is (= 42 (errors/classify-observation (constantly 42))))))

;; -----------------------------------------------------------------------------
;; The SUT-unreachable navigation edge (sl-kowi rider)
;; -----------------------------------------------------------------------------

(defn- navigation-error
  "The REAL shape observed live (Chrome/ChromeDriver 151, 08-30): the
   navigate command fails with :etaoin/http-error 500, W3C `unknown
   error`, the net code in the message."
  [net-code]
  (ex-info "throw+: {...}"
           {:type :etaoin/http-error
            :status 500
            :method :post
            :path "session/abc/url"
            :response {:value {:error "unknown error"
                               :message (str "unknown error: net::" net-code
                                             "\n  (Session info: chrome=151.0.7922.174)")}}}))

(defn- navigation-timeout-error
  "The REAL shape from the 09-03 audit probe (Chrome 152 / ChromeDriver
   151, page-load timeout 3s, a server that accepts and never writes): the
   navigate command fails with :etaoin/http-error 500, W3C `timeout`."
  []
  (ex-info "throw+: {...}"
           {:type :etaoin/http-error
            :status 500
            :method :post
            :path "session/abc/url"
            :response {:value {:error "timeout"
                               :message (str "timeout: Timed out receiving message"
                                             " from renderer: 3.000\n  (Session info:"
                                             " chrome=152.0.7977.75)")}}}))

(deftest navigation-timeout-is-the-w3c-timeout-answer
  ;; sl-od2v (Warden W4): the silent-SUT hard line — kowi Ruling 3c.
  (is (errors/navigation-timeout? (navigation-timeout-error)))
  (testing "a net-code answer, the transport edge, a plain driver answer and a
            non-etaoin throw are not it"
    (is (not (errors/navigation-timeout? (navigation-error "ERR_CONNECTION_REFUSED"))))
    (is (not (errors/navigation-timeout? (http-ex))))
    (is (not (errors/navigation-timeout? (http-error "no such element"))))
    (is (not (errors/navigation-timeout? (ex-info "boom" {}))))))

(deftest classify-navigation-labels-the-silent-sut
  (testing "a page-load timeout at the navigation → SUT silent, :observation,
            :w3c-error + :url aboard, cause chained — was unclassified, exit 1"
    (let [thrown (try (errors/classify-navigation
                       #(throw (navigation-timeout-error))
                       "http://127.0.0.1:59998/")
                      nil
                      (catch clojure.lang.ExceptionInfo e e))
          data (ex-data thrown)]
      (is (= "SUT silent: navigation timed out for http://127.0.0.1:59998/"
             (ex-message thrown)))
      (is (= :observation (:error/class data)))
      (is (= "timeout" (:w3c-error data)))
      (is (= "http://127.0.0.1:59998/" (:url data)))
      (is (= :etaoin/http-error (:type data)) "original ex-data merged")
      (is (= "timeout" (get-in (ex-data (ex-cause thrown)) [:response :value :error]))
          "original is the cause"))))

(deftest unreachable-net-error-is-the-closed-set
  (testing "each code in the closed set is extracted from the W3C message"
    (doseq [code errors/unreachable-net-errors]
      (is (= code (errors/unreachable-net-error (navigation-error code))) code)))
  (testing "sl-od2v: the generic timeout and the empty response are in the set"
    (is (contains? errors/unreachable-net-errors "ERR_TIMED_OUT"))
    (is (contains? errors/unreachable-net-errors "ERR_EMPTY_RESPONSE")))
  (testing "request errors and everything else stay nil"
    (doseq [code ["ERR_UNSAFE_PORT" "ERR_INVALID_URL" "ERR_ABORTED" "ERR_SOMETHING_NEW"]]
      (is (nil? (errors/unreachable-net-error (navigation-error code))) code))
    (is (nil? (errors/unreachable-net-error (http-error "no such element"))))
    (is (nil? (errors/unreachable-net-error (http-ex)))
        "the transport edge is classify-observation's, not this one")
    (is (nil? (errors/unreachable-net-error (ex-info "boom" {}))))))

(deftest classify-navigation-labels-only-the-unreachable-edge
  (testing "a refused navigation → SUT unreachable, :observation, url + code aboard, cause chained"
    (let [thrown (try (errors/classify-navigation
                       #(throw (navigation-error "ERR_CONNECTION_REFUSED"))
                       "http://localhost:59999/nope")
                      nil
                      (catch clojure.lang.ExceptionInfo e e))
          data (ex-data thrown)]
      (is (= "SUT unreachable: ERR_CONNECTION_REFUSED for http://localhost:59999/nope"
             (ex-message thrown)))
      (is (= :observation (:error/class data)))
      (is (= "ERR_CONNECTION_REFUSED" (:net-error data)))
      (is (= "http://localhost:59999/nope" (:url data)))
      (is (= :etaoin/http-error (:type data)) "original ex-data merged")
      (is (= 500 (:status (ex-data (ex-cause thrown)))) "original is the cause")))
  (testing "an unsafe-port navigation and a plain driver answer pass through as the SAME object"
    (doseq [original [(navigation-error "ERR_UNSAFE_PORT") (http-error "no such element")]]
      (is (identical? original
                      (try (errors/classify-navigation #(throw original) "http://x/")
                           nil
                           (catch clojure.lang.ExceptionInfo e e))))))
  (testing "the transport's own http-ex passes through untouched (the outer wrapper labels it)"
    (let [original (http-ex)]
      (is (identical? original
                      (try (errors/classify-navigation #(throw original) "http://x/")
                           nil
                           (catch clojure.lang.ExceptionInfo e e))))))
  (testing "a successful navigation returns f's value"
    (is (= :navigated (errors/classify-navigation (constantly :navigated) "http://x/")))))
