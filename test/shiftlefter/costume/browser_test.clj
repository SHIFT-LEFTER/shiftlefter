(ns shiftlefter.costume.browser-test
  (:require [clojure.test :refer [deftest is testing]]
            [shiftlefter.costume.browser :as cbrowser]
            [shiftlefter.browser.protocol :as bp]))

;; -----------------------------------------------------------------------------
;; Mock Browser for Testing
;; -----------------------------------------------------------------------------

(defrecord MockBrowser [call-log-atom fail-atom]
  bp/IBrowser
  (open-to! [this url]
    (when @fail-atom
      (throw (ex-info "invalid session id" {:type :session-error})))
    (swap! call-log-atom conj [:open-to! url])
    this)

  (click! [this locator]
    (when @fail-atom
      (throw (ex-info "invalid session id" {:type :session-error})))
    (swap! call-log-atom conj [:click! locator])
    this)

  (doubleclick! [this locator]
    (when @fail-atom
      (throw (ex-info "invalid session id" {:type :session-error})))
    (swap! call-log-atom conj [:doubleclick! locator])
    this)

  (rightclick! [this locator]
    (when @fail-atom
      (throw (ex-info "invalid session id" {:type :session-error})))
    (swap! call-log-atom conj [:rightclick! locator])
    this)

  (move-to! [this locator]
    (when @fail-atom
      (throw (ex-info "invalid session id" {:type :session-error})))
    (swap! call-log-atom conj [:move-to! locator])
    this)

  (drag-to! [this from to]
    (when @fail-atom
      (throw (ex-info "invalid session id" {:type :session-error})))
    (swap! call-log-atom conj [:drag-to! from to])
    this)

  (fill! [this locator text]
    (when @fail-atom
      (throw (ex-info "invalid session id" {:type :session-error})))
    (swap! call-log-atom conj [:fill! locator text])
    this)

  (element-count [_this _locator]
    (when @fail-atom
      (throw (ex-info "invalid session id" {:type :session-error})))
    42)

  (get-text [_this _locator]
    (when @fail-atom
      (throw (ex-info "invalid session id" {:type :session-error})))
    "mock text")

  (get-url [_this]
    (when @fail-atom
      (throw (ex-info "invalid session id" {:type :session-error})))
    "https://mock.example.com")

  (get-title [_this]
    (when @fail-atom
      (throw (ex-info "invalid session id" {:type :session-error})))
    "Mock Title")

  (visible? [_this _locator]
    (when @fail-atom
      (throw (ex-info "invalid session id" {:type :session-error})))
    true)

  ;; --- Navigation (0.3.6) ---
  (go-back! [this]
    (when @fail-atom
      (throw (ex-info "invalid session id" {:type :session-error})))
    (swap! call-log-atom conj [:go-back!])
    this)
  (go-forward! [this]
    (when @fail-atom
      (throw (ex-info "invalid session id" {:type :session-error})))
    (swap! call-log-atom conj [:go-forward!])
    this)
  (refresh! [this]
    (when @fail-atom
      (throw (ex-info "invalid session id" {:type :session-error})))
    (swap! call-log-atom conj [:refresh!])
    this)

  ;; --- Scrolling (0.3.6) ---
  (scroll-to! [this locator]
    (when @fail-atom
      (throw (ex-info "invalid session id" {:type :session-error})))
    (swap! call-log-atom conj [:scroll-to! locator])
    this)
  (scroll-to-position! [this position]
    (when @fail-atom
      (throw (ex-info "invalid session id" {:type :session-error})))
    (swap! call-log-atom conj [:scroll-to-position! position])
    this)

  ;; --- Form Operations (0.3.6) ---
  (clear! [this locator]
    (when @fail-atom
      (throw (ex-info "invalid session id" {:type :session-error})))
    (swap! call-log-atom conj [:clear! locator])
    this)
  (select! [this locator text]
    (when @fail-atom
      (throw (ex-info "invalid session id" {:type :session-error})))
    (swap! call-log-atom conj [:select! locator text])
    this)
  (press-key! [this key-str]
    (when @fail-atom
      (throw (ex-info "invalid session id" {:type :session-error})))
    (swap! call-log-atom conj [:press-key! key-str])
    this)

  ;; --- Element Queries (0.3.6) ---
  (get-attribute [_this _locator _attribute]
    (when @fail-atom
      (throw (ex-info "invalid session id" {:type :session-error})))
    "mock-attr")
  (get-value [_this _locator]
    (when @fail-atom
      (throw (ex-info "invalid session id" {:type :session-error})))
    "mock-value")
  (enabled? [_this _locator]
    (when @fail-atom
      (throw (ex-info "invalid session id" {:type :session-error})))
    true)

  ;; --- Alerts (0.3.6) ---
  (accept-alert! [this]
    (when @fail-atom
      (throw (ex-info "invalid session id" {:type :session-error})))
    (swap! call-log-atom conj [:accept-alert!])
    this)
  (dismiss-alert! [this]
    (when @fail-atom
      (throw (ex-info "invalid session id" {:type :session-error})))
    (swap! call-log-atom conj [:dismiss-alert!])
    this)
  (get-alert-text [_this]
    (when @fail-atom
      (throw (ex-info "invalid session id" {:type :session-error})))
    "mock alert")

  ;; --- Window Management (0.3.6) ---
  (maximize-window! [this]
    (when @fail-atom
      (throw (ex-info "invalid session id" {:type :session-error})))
    (swap! call-log-atom conj [:maximize-window!])
    this)
  (set-window-size! [this width height]
    (when @fail-atom
      (throw (ex-info "invalid session id" {:type :session-error})))
    (swap! call-log-atom conj [:set-window-size! width height])
    this)
  (switch-to-next-window! [this]
    (when @fail-atom
      (throw (ex-info "invalid session id" {:type :session-error})))
    (swap! call-log-atom conj [:switch-to-next-window!])
    this)

  ;; --- Frames (0.3.6) ---
  (switch-to-frame! [this locator]
    (when @fail-atom
      (throw (ex-info "invalid session id" {:type :session-error})))
    (swap! call-log-atom conj [:switch-to-frame! locator])
    this)
  (switch-to-main-frame! [this]
    (when @fail-atom
      (throw (ex-info "invalid session id" {:type :session-error})))
    (swap! call-log-atom conj [:switch-to-main-frame!])
    this)
  (query-all [_this scope locator]
    (when @fail-atom
      (throw (ex-info "invalid session id" {:type :session-error})))
    (swap! call-log-atom conj [:query-all scope locator])
    [])

  (query-all-pruned [_this scope locator boundary-css]
    (when @fail-atom
      (throw (ex-info "invalid session id" {:type :session-error})))
    (swap! call-log-atom conj [:query-all-pruned scope locator boundary-css])
    []))

(defn make-mock-browser
  "Create a mock browser for testing.
   Returns [browser call-log-atom fail-atom]"
  []
  (let [call-log (atom [])
        fail (atom false)
        browser (->MockBrowser call-log fail)]
    [browser call-log fail]))

;; -----------------------------------------------------------------------------
;; Session Error Detection Tests
;; -----------------------------------------------------------------------------

(deftest session-error-detection-test
  (testing "detects 'invalid session id' errors"
    (is (true? (#'cbrowser/session-error? (ex-info "invalid session id" {})))))

  (testing "detects 'session not found' errors"
    (is (true? (#'cbrowser/session-error? (ex-info "session not found" {})))))

  (testing "detects 'no such session' errors"
    (is (true? (#'cbrowser/session-error? (ex-info "no such session" {})))))

  (testing "doesn't match other errors"
    (is (false? (#'cbrowser/session-error? (ex-info "element not found" {}))))
    (is (false? (#'cbrowser/session-error? (ex-info "timeout" {}))))))

;; -----------------------------------------------------------------------------
;; CostumeBrowser Normal Operation Tests
;; -----------------------------------------------------------------------------

(deftest costume-browser-normal-operation-test
  (testing "operations pass through to underlying browser"
    (let [[mock-browser call-log _fail] (make-mock-browser)
          reconnect-fn (fn [_] nil)  ;; Won't be called
          pb (cbrowser/make-costume-browser mock-browser :test reconnect-fn)]

      (bp/open-to! pb "https://example.com")
      (bp/click! pb {:q {:css "button"}})
      (bp/fill! pb {:q {:id "input"}} "hello")

      (is (= [[:open-to! "https://example.com"]
              [:click! {:q {:css "button"}}]
              [:fill! {:q {:id "input"}} "hello"]]
             @call-log)))))

(deftest costume-browser-element-count-test
  (testing "element-count returns result from underlying browser"
    (let [[mock-browser _ _] (make-mock-browser)
          reconnect-fn (fn [_] nil)
          pb (cbrowser/make-costume-browser mock-browser :test reconnect-fn)]

      (is (= 42 (bp/element-count pb {:q {:css "div"}}))))))

;; -----------------------------------------------------------------------------
;; CostumeBrowser Reconnection Tests
;; -----------------------------------------------------------------------------

(deftest costume-browser-reconnect-on-session-error-test
  (testing "reconnects and retries on session error"
    (let [[mock-browser-1 call-log-1 fail-1] (make-mock-browser)
          [mock-browser-2 call-log-2 _fail-2] (make-mock-browser)
          reconnect-called (atom false)
          reconnect-fn (fn [subject]
                         (reset! reconnect-called true)
                         (is (= :test-subject subject))
                         mock-browser-2)
          pb (cbrowser/make-costume-browser mock-browser-1 :test-subject reconnect-fn)]

      ;; First call succeeds
      (bp/open-to! pb "https://example.com")
      (is (= [[:open-to! "https://example.com"]] @call-log-1))

      ;; Now make the browser fail
      (reset! fail-1 true)

      ;; Next call should trigger reconnect and retry
      (bp/click! pb {:q {:css "button"}})

      (is (true? @reconnect-called))
      ;; The retry happened on the new browser
      (is (= [[:click! {:q {:css "button"}}]] @call-log-2)))))

(deftest costume-browser-reconnect-failure-test
  (testing "surfaces original error when reconnect fails"
    (let [[mock-browser _ fail] (make-mock-browser)
          reconnect-fn (fn [_] nil)  ;; Reconnect fails (returns nil)
          pb (cbrowser/make-costume-browser mock-browser :test reconnect-fn)]

      ;; Make browser fail
      (reset! fail true)

      ;; Should throw the original error
      (is (thrown-with-msg? Exception #"invalid session id"
            (bp/open-to! pb "https://example.com"))))))

(deftest costume-browser-non-session-error-not-retried-test
  (testing "non-session errors are not retried"
    (let [call-log (atom [])
          fail-with-other (atom false)
          mock-browser (reify bp/IBrowser
                         (open-to! [this _url]
                           (when @fail-with-other
                             (throw (ex-info "element not found" {})))
                           (swap! call-log conj :open)
                           this)
                         (click! [this _loc] this)
                         (doubleclick! [this _loc] this)
                         (rightclick! [this _loc] this)
                         (move-to! [this _loc] this)
                         (drag-to! [this _from _to] this)
                         (fill! [this _loc _text] this)
                         (element-count [_this _loc] 0)
                         (get-text [_this _loc] "")
                         (get-url [_this] "")
                         (get-title [_this] "")
                         (visible? [_this _loc] false)
                         ;; Navigation (0.3.6)
                         (go-back! [this] this)
                         (go-forward! [this] this)
                         (refresh! [this] this)
                         ;; Scrolling
                         (scroll-to! [this _loc] this)
                         (scroll-to-position! [this _pos] this)
                         ;; Form Operations
                         (clear! [this _loc] this)
                         (select! [this _loc _text] this)
                         (press-key! [this _key] this)
                         ;; Element Queries
                         (get-attribute [_this _loc _attr] "")
                         (get-value [_this _loc] "")
                         (enabled? [_this _loc] true)
                         ;; Alerts
                         (accept-alert! [this] this)
                         (dismiss-alert! [this] this)
                         (get-alert-text [_this] "")
                         ;; Window Management
                         (maximize-window! [this] this)
                         (set-window-size! [this _w _h] this)
                         (switch-to-next-window! [this] this)
                         ;; Frames
                         (switch-to-frame! [this _loc] this)
                         (switch-to-main-frame! [this] this)
                         ;; Element handles & scoped find
                         (query-all [_this _scope _loc] [])
                         (query-all-pruned [_this _scope _loc _b] []))
          reconnect-called (atom false)
          reconnect-fn (fn [_]
                         (reset! reconnect-called true)
                         mock-browser)
          pb (cbrowser/make-costume-browser mock-browser :test reconnect-fn)]

      (reset! fail-with-other true)

      ;; Should throw without trying to reconnect
      (is (thrown-with-msg? Exception #"element not found"
            (bp/open-to! pb "https://example.com")))

      ;; Reconnect should NOT have been called
      (is (false? @reconnect-called)))))

;; -----------------------------------------------------------------------------
;; Underlying Browser Access Test
;; -----------------------------------------------------------------------------

(deftest underlying-browser-test
  (testing "can access underlying browser"
    (let [[mock-browser _ _] (make-mock-browser)
          pb (cbrowser/make-costume-browser mock-browser :test (fn [_] nil))]

      (is (= mock-browser (cbrowser/underlying-browser pb))))))

;; -----------------------------------------------------------------------------
;; session-error? (sl-jxi) — must NOT reconnect on transient DOM errors
;; -----------------------------------------------------------------------------

(def ^:private session-error?
  #'cbrowser/session-error?)

(deftest session-error-excludes-transient-dom-errors
  (testing "genuine session/connection death IS a session error → reconnect"
    (is (session-error? (ex-info "invalid session id" {:type :etaoin/http-error})))
    (is (session-error? (ex-info "no such window" {})))
    (is (session-error? (ex-info "Connection refused" {})))
    (is (session-error? (ex-info "x" {:type :etaoin/http-ex}))))

  (testing "a transient DOM error is NOT a session error (sl-jxi drift fix)"
    ;; etaoin surfaces stale-element as the SAME :etaoin/http-error type as a
    ;; dead session; the bare type check used to force a wasteful reconnect.
    (is (not (session-error?
              (ex-info "stale" {:type :etaoin/http-error
                                :response {:value {:error "stale element reference"}}})))
        "stale-element http-error retries in place, not a reconnect")
    (is (not (session-error?
              (ex-info "Node with given id does not belong to the document"
                       {:type :etaoin/http-error})))
        "sl-bnk inspector error is a live-session retry, not a dead session")))
