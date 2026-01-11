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
    42))

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

(deftest chaining-test
  (testing "operations can be chained"
    (let [b (make-fake-browser)]
      (-> b
          (bp/open-to! "https://example.com")
          (bp/fill! {:q {:css "#user"}} "alice")
          (bp/fill! {:q {:css "#pass"}} "secret")
          (bp/click! {:q {:css "#submit"}}))
      (is (= 4 (count (get-calls b)))))))
