(ns shiftlefter.browser.errors-test
  "Tests for the shared WebDriver error classifier (sl-jxi). Feeds synthetic
   exceptions shaped like etaoin 1.1.42's `:etaoin/http-error` ex-data
   (`{:type :etaoin/http-error :response {:value {:error <w3c-code>}}}`) and the
   legacy message-only surfaces, asserting each documented transient class is
   retryable and — critically — that non-transient errors are NOT (the
   over-match guard)."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
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
   1.1.42 etaoin.impl.client/call), matching the real ex-data shape AND the
   real message: etaoin throws through slingshot, whose message is the whole
   ex-data pretty-printed (`throw+: {…}`) — the dump itself (sl-6qf6 F1)."
  []
  (let [data {:type :etaoin/http-ex :host "127.0.0.1" :port 9515
              :method :post :path "session/abc/url"
              :driver {:type :chrome :host "127.0.0.1" :port 9515 :session "abc"}
              :payload {:url "http://127.0.0.1:59999/"}}]
    (ex-info (str "throw+: " (pr-str data))
             data
             (java.net.ConnectException. "Connection refused"))))

(deftest observation-error-is-the-http-ex-edge-only
  (testing ":etaoin/http-ex (no response existed) is the observation edge"
    (is (errors/observation-error? (http-ex))))
  (testing ":etaoin/http-error is an ANSWER from the driver — never classified here"
    (doseq [code ["no such element" "invalid session id" "unknown error"]]
      (is (not (errors/observation-error? (http-error code))) code)))
  (testing "arbitrary throws are not"
    (is (not (errors/observation-error? (ex-info "boom" {}))))))

(deftest classify-observation-labels-only-the-edge
  (testing "an http-ex is rethrown with :error/class :observation, its OWN message and the
            allowlisted ex-data (sl-6qf6 — the driver's answer, not a driver dump), cause chained"
    (let [thrown (try (errors/classify-observation #(throw (http-ex)))
                      nil
                      (catch clojure.lang.ExceptionInfo e e))]
      (is (= :observation (:error/class (ex-data thrown))))
      (is (= :etaoin/http-ex (:type (ex-data thrown))))
      (is (= 9515 (:port (ex-data thrown))) "the allowlisted keys survive the projection")
      (is (= "Driver unreachable: POST session/abc/url to 127.0.0.1:9515 threw — Connection refused"
             (ex-message thrown))
          "the message is composed here — etaoin's slingshot message IS the dump (F1)")
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

;; -----------------------------------------------------------------------------
;; The driver's answer, not a driver dump (sl-6qf6)
;; -----------------------------------------------------------------------------

(defn- read-fixture
  "A live-captured etaoin ex-data (test/fixtures/browser/*.edn). The
   placeholders (<DRIVER-PORT> etc.) are not readable EDN — swap them for
   representative values first, so the shape is the real one, keys and all."
  [name]
  (-> (slurp (str "test/fixtures/browser/" name ".edn"))
      (str/replace "<DRIVER-PORT>" "50343")
      (str/replace "<EPOCH-MS>" "1789961645559")
      (str/replace #"<[A-Z-]+>" "placeholder")
      edn/read-string))

(def ^:private refused-ex-data (read-fixture "refused-navigation-ex-data"))

(def ^:private transport-capture
  "The transport fixture carries the real MESSAGE beside the ex-data (F1)."
  (read-fixture "transport-http-ex-data"))
(def ^:private http-ex-data (:ex-data transport-capture))

(defn- real-http-ex
  "The captured transport throw rebuilt whole: slingshot's message, the
   ex-data, and the cause it had live (a ConnectException)."
  []
  (ex-info (:ex-message transport-capture)
           http-ex-data
           (java.net.ConnectException. (:cause-message transport-capture))))

(deftest fixtures-are-the-real-dump
  (testing "the captured shapes carry the dump the bead names — the guard is not blind"
    (is (some? (get-in refused-ex-data [:driver :process])))
    (is (string? (get-in refused-ex-data [:response :value :stacktrace])))
    (is (contains? refused-ex-data :payload))
    (is (some? (:driver http-ex-data)))
    (is (contains? http-ex-data :payload)))
  (testing "the real transport MESSAGE is the dump (slingshot pretty-prints the ex-data)"
    (is (str/starts-with? (:ex-message transport-capture) "throw+: {"))
    (is (str/includes? (:ex-message transport-capture) ":driver"))
    (is (str/includes? (:ex-message transport-capture) ":payload"))))

(deftest sanitize-driver-ex-data-is-an-allowlist
  (testing "the refused navigation answer: what was asked, of which driver, and what it answered"
    (is (= {:type :etaoin/http-error
            :status 500
            :method :post
            :path "session/placeholder/url"
            :host "127.0.0.1"
            :port 50343
            :response {:value {:error "unknown error"
                               :message (str "unknown error: net::ERR_CONNECTION_REFUSED"
                                             "\n  (Session info: chrome=153.0.8010.52)")}}}
           (errors/sanitize-driver-ex-data refused-ex-data)))
    (let [before (count (pr-str refused-ex-data))
          after (count (pr-str (errors/sanitize-driver-ex-data refused-ex-data)))]
      (is (< after 300) (str "sanitized record is small: " after " bytes"))
      (is (> before (* 4 after)) (str "dump " before " → record " after))))
  (testing "the transport edge: no answer existed, so no :response — and no :driver, no :payload"
    (is (= {:type :etaoin/http-ex :method :post :path "session/no-session/url"
            :host "127.0.0.1" :port 50343}
           (errors/sanitize-driver-ex-data http-ex-data))))
  (testing "dropped by omission, never by name"
    (doseq [data [refused-ex-data http-ex-data]
            k [:driver :payload :webdriver-url :process-liveness-ex :anything-a-future-etaoin-adds]]
      (is (not (contains? (errors/sanitize-driver-ex-data (assoc data :anything-a-future-etaoin-adds 1)) k))
          (str k))))
  (testing "total: a non-etaoin ex-data projects to what it has of the allowlist"
    (is (= {} (errors/sanitize-driver-ex-data {:foo 1})))
    (is (= {:type :bindings/capture-failure} (errors/sanitize-driver-ex-data {:type :bindings/capture-failure :locator "x"})))))

(deftest classified-navigation-carries-the-answer-not-the-dump
  (testing "the refused arm, on the REAL captured shape: sanitized record + classification keys, cause preserved"
    (let [original (ex-info "throw+: {...}" refused-ex-data)
          thrown (try (errors/classify-navigation #(throw original) "http://127.0.0.1:59999/")
                      nil
                      (catch clojure.lang.ExceptionInfo e e))
          data (ex-data thrown)]
      (is (= "SUT unreachable: ERR_CONNECTION_REFUSED for http://127.0.0.1:59999/" (ex-message thrown)))
      (is (= (assoc (errors/sanitize-driver-ex-data refused-ex-data)
                    :error/class :observation
                    :net-error "ERR_CONNECTION_REFUSED"
                    :url "http://127.0.0.1:59999/")
             data))
      (is (not (contains? data :driver)))
      (is (nil? (get-in data [:response :value :stacktrace])))
      (is (identical? original (ex-cause thrown)) "the full dump is one ex-cause away")
      (is (some? (get-in (ex-data (ex-cause thrown)) [:driver :process])))))
  (testing "the timeout arm, on the same real shape with the driver's timeout answer swapped in"
    (let [timeout-data (assoc-in refused-ex-data [:response :value]
                                 {:error "timeout"
                                  :message "timeout: Timed out receiving message from renderer: 3.000\n  (Session info: chrome=153.0.8010.52)"
                                  :stacktrace (get-in refused-ex-data [:response :value :stacktrace])})
          thrown (try (errors/classify-navigation #(throw (ex-info "throw+: {...}" timeout-data)) "http://127.0.0.1:59998/")
                      nil
                      (catch clojure.lang.ExceptionInfo e e))
          data (ex-data thrown)]
      (is (= "timeout" (:w3c-error data)))
      (is (= :observation (:error/class data)))
      (is (not (contains? data :driver)))
      (is (= #{:error :message} (set (keys (get-in data [:response :value]))))))))

(deftest classified-observation-carries-the-answer-not-the-dump
  (testing "the transport edge, on the REAL captured http-ex shape — data AND message"
    (let [original (real-http-ex)
          thrown (try (errors/classify-observation #(throw original))
                      nil
                      (catch clojure.lang.ExceptionInfo e e))
          data (ex-data thrown)
          msg (ex-message thrown)]
      (is (= (assoc (errors/sanitize-driver-ex-data http-ex-data) :error/class :observation) data))
      (is (not (contains? data :driver)))
      (is (not (contains? data :payload)))
      (is (= "Driver unreachable: POST session/no-session/url to 127.0.0.1:50343 threw — Connection refused" msg))
      (is (not (str/includes? msg ":driver")) "the message carries no driver map")
      (is (not (str/includes? msg ":payload")) "the message carries no payload")
      (is (< (count msg) (count (:ex-message transport-capture))))
      (is (identical? original (ex-cause thrown)) "the full dump, message included, is the cause")))
  (testing "transport-edge-message is total: no cause, no method — still no dump"
    (is (= "Driver unreachable: (request) to 127.0.0.1:1 threw"
           (errors/transport-edge-message (ex-info "throw+: {…}" {:type :etaoin/http-ex :host "127.0.0.1" :port 1 :driver {:x 1}}))))))
