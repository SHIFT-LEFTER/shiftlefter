(ns shiftlefter.browser.protocol-test
  (:require [clojure.test :refer [deftest is testing]]
            [shiftlefter.browser.protocol :as bp]))

;; -----------------------------------------------------------------------------
;; FakeBrowser — test implementation of IBrowser
;; -----------------------------------------------------------------------------

(defrecord FakeBrowser [calls-atom]
  bp/IBrowser
  (open-to! [this url]
    (swap! calls-atom conj [:open-to! url])
    this)
  (click! [this loc]
    (swap! calls-atom conj [:click! loc])
    this)
  (doubleclick! [this loc]
    (swap! calls-atom conj [:doubleclick! loc])
    this)
  (rightclick! [this loc]
    (swap! calls-atom conj [:rightclick! loc])
    this)
  (move-to! [this loc]
    (swap! calls-atom conj [:move-to! loc])
    this)
  (drag-to! [this from to]
    (swap! calls-atom conj [:drag-to! from to])
    this)
  (fill! [this loc text]
    (swap! calls-atom conj [:fill! loc text])
    this)
  (element-count [_ loc]
    (swap! calls-atom conj [:element-count loc])
    42)

  (get-text [_ loc]
    (swap! calls-atom conj [:get-text loc])
    "fake text content")

  (get-url [_]
    (swap! calls-atom conj [:get-url])
    "https://example.com/page")

  (get-title [_]
    (swap! calls-atom conj [:get-title])
    "Fake Page Title")

  (visible? [_ loc]
    (swap! calls-atom conj [:visible? loc])
    true)

  ;; --- Navigation (0.3.6) ---
  (go-back! [this]
    (swap! calls-atom conj [:go-back!])
    this)
  (go-forward! [this]
    (swap! calls-atom conj [:go-forward!])
    this)
  (refresh! [this]
    (swap! calls-atom conj [:refresh!])
    this)

  ;; --- Scrolling ---
  (scroll-to! [this loc]
    (swap! calls-atom conj [:scroll-to! loc])
    this)
  (scroll-to-position! [this position]
    (swap! calls-atom conj [:scroll-to-position! position])
    this)

  ;; --- Form Operations ---
  (clear! [this loc]
    (swap! calls-atom conj [:clear! loc])
    this)
  (select! [this loc text]
    (swap! calls-atom conj [:select! loc text])
    this)
  (press-key! [this key-str]
    (swap! calls-atom conj [:press-key! key-str])
    this)

  ;; --- Element Queries ---
  (get-attribute [_ loc attr]
    (swap! calls-atom conj [:get-attribute loc attr])
    "fake-attr")
  (get-value [_ loc]
    (swap! calls-atom conj [:get-value loc])
    "fake-value")
  (enabled? [_ loc]
    (swap! calls-atom conj [:enabled? loc])
    true)

  ;; --- Alerts ---
  (accept-alert! [this]
    (swap! calls-atom conj [:accept-alert!])
    this)
  (dismiss-alert! [this]
    (swap! calls-atom conj [:dismiss-alert!])
    this)
  (get-alert-text [_]
    (swap! calls-atom conj [:get-alert-text])
    "fake alert")

  ;; --- Window Management ---
  (maximize-window! [this]
    (swap! calls-atom conj [:maximize-window!])
    this)
  (set-window-size! [this w h]
    (swap! calls-atom conj [:set-window-size! w h])
    this)
  (switch-to-next-window! [this]
    (swap! calls-atom conj [:switch-to-next-window!])
    this)

  ;; --- Frames ---
  (switch-to-frame! [this loc]
    (swap! calls-atom conj [:switch-to-frame! loc])
    this)
  (switch-to-main-frame! [this]
    (swap! calls-atom conj [:switch-to-main-frame!])
    this))

(defn make-fake-browser []
  (->FakeBrowser (atom [])))

(defn get-calls [b]
  @(:calls-atom b))

;; -----------------------------------------------------------------------------
;; Tests
;; -----------------------------------------------------------------------------

(deftest protocol-compiles-test
  (testing "FakeBrowser implements IBrowser"
    (let [b (make-fake-browser)]
      (is (satisfies? bp/IBrowser b)))))

(deftest open-to!-test
  (testing "open-to! navigates and returns this"
    (let [b (make-fake-browser)
          result (bp/open-to! b "https://example.com")]
      (is (identical? b result))
      (is (= [[:open-to! "https://example.com"]] (get-calls b))))))

(deftest click!-test
  (testing "click! clicks element and returns this"
    (let [b (make-fake-browser)
          loc {:q {:css "#login"}}
          result (bp/click! b loc)]
      (is (identical? b result))
      (is (= [[:click! loc]] (get-calls b))))))

(deftest doubleclick!-test
  (testing "doubleclick! double-clicks and returns this"
    (let [b (make-fake-browser)
          loc {:q {:css ".item"}}
          result (bp/doubleclick! b loc)]
      (is (identical? b result))
      (is (= [[:doubleclick! loc]] (get-calls b))))))

(deftest rightclick!-test
  (testing "rightclick! right-clicks and returns this"
    (let [b (make-fake-browser)
          loc {:q {:id "context-target"}}
          result (bp/rightclick! b loc)]
      (is (identical? b result))
      (is (= [[:rightclick! loc]] (get-calls b))))))

(deftest move-to!-test
  (testing "move-to! moves mouse and returns this"
    (let [b (make-fake-browser)
          loc {:q {:xpath "//div[@class='hover']"}}
          result (bp/move-to! b loc)]
      (is (identical? b result))
      (is (= [[:move-to! loc]] (get-calls b))))))

(deftest drag-to!-test
  (testing "drag-to! drags between elements and returns this"
    (let [b (make-fake-browser)
          from {:q {:css ".source"}}
          to {:q {:css ".target"}}
          result (bp/drag-to! b from to)]
      (is (identical? b result))
      (is (= [[:drag-to! from to]] (get-calls b))))))

(deftest fill!-test
  (testing "fill! enters text and returns this"
    (let [b (make-fake-browser)
          loc {:q {:css "input[name='email']"}}
          result (bp/fill! b loc "test@example.com")]
      (is (identical? b result))
      (is (= [[:fill! loc "test@example.com"]] (get-calls b))))))

(deftest element-count-test
  (testing "element-count returns count"
    (let [b (make-fake-browser)
          loc {:q {:css ".x"}}
          result (bp/element-count b loc)]
      (is (= 42 result))
      (is (= [[:element-count loc]] (get-calls b))))))

(deftest get-text-test
  (testing "get-text returns element text"
    (let [b (make-fake-browser)
          loc {:q {:css "#content"}}
          result (bp/get-text b loc)]
      (is (= "fake text content" result))
      (is (= [[:get-text loc]] (get-calls b))))))

(deftest get-url-test
  (testing "get-url returns current URL"
    (let [b (make-fake-browser)
          result (bp/get-url b)]
      (is (= "https://example.com/page" result))
      (is (= [[:get-url]] (get-calls b))))))

(deftest get-title-test
  (testing "get-title returns page title"
    (let [b (make-fake-browser)
          result (bp/get-title b)]
      (is (= "Fake Page Title" result))
      (is (= [[:get-title]] (get-calls b))))))

(deftest visible?-test
  (testing "visible? returns boolean"
    (let [b (make-fake-browser)
          loc {:q {:css ".element"}}
          result (bp/visible? b loc)]
      (is (true? result))
      (is (= [[:visible? loc]] (get-calls b))))))

(deftest chaining-test
  (testing "operations can be chained"
    (let [b (make-fake-browser)]
      (-> b
          (bp/open-to! "https://example.com")
          (bp/fill! {:q {:css "#user"}} "alice")
          (bp/fill! {:q {:css "#pass"}} "secret")
          (bp/click! {:q {:css "#submit"}}))
      (is (= 4 (count (get-calls b)))))))
