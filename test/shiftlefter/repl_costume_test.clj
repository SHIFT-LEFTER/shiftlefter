(ns shiftlefter.repl-costume-test
  "End-to-end (stubbed-Chrome) tests for the REPL costume flow (sl-8mz, AC1).

   Covers the previously-untested wiring:
   connect-costume! -> connected-subjects atom -> inject-subject-browser ->
   repl/as multi-step survival -> clear! teardown.

   The browser is a real CostumeBrowser record (record fidelity matters —
   the capability plumbing is what is under test); only
   costume/connect-costume! is stubbed, so no live Chrome is needed."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [shiftlefter.capabilities.ctx :as cap]
            [shiftlefter.costume :as costume]
            [shiftlefter.costume.browser :as cbrowser]
            [shiftlefter.repl :as repl]
            [shiftlefter.stepengine.registry :as registry :refer [defstep]]
            [shiftlefter.webdriver.etaoin.browser :as ebrowser]))

(use-fixtures :each
  (fn [f]
    (registry/clear-registry!)
    (repl/reset-ctx!)
    (repl/reset-ctxs!)
    (reset! @#'repl/connected-subjects {})
    (repl/reset-config!)
    (f)))

(defn- make-stub-costume-browser [costume-name]
  (cbrowser/make-costume-browser
   (ebrowser/make-etaoin-browser {:host "127.0.0.1" :port 9515
                                  :session (str (name costume-name) "-session")
                                  :type :chrome})
   costume-name
   (constantly nil)))

(defn- stub-connect
  "with-redefs value for costume/connect-costume! returning a fixed browser."
  [stub]
  (fn [n] {:status :connected :costume n :port 9300 :pid 1 :browser stub}))

(deftest test-connect-costume-populates-connected-subjects
  (testing "successful connect registers the browser in connected-subjects"
    (let [stub (make-stub-costume-browser :finance)]
      (with-redefs [costume/connect-costume! (stub-connect stub)]
        (let [result (repl/connect-costume! :finance)]
          (is (= :connected (:status result)))
          (is (identical? stub (:browser result)))
          (is (= {:browser stub} (get @@#'repl/connected-subjects :finance)))))))
  (testing "failed connect does NOT register anything"
    (with-redefs [costume/connect-costume!
                  (fn [n] {:error {:type :costume/not-found :costume n}})]
      (let [result (repl/connect-costume! :ghost)]
        (is (contains? result :error))
        (is (not (contains? @@#'repl/connected-subjects :ghost)))))))

(deftest test-as-injects-costume-browser-stable-across-steps
  (testing "AC1: browser identity is stable across multiple repl/as invocations"
    (let [stub (make-stub-costume-browser :finance)
          seen (atom [])]
      (with-redefs [costume/connect-costume! (stub-connect stub)]
        (repl/connect-costume! :finance)
        ;; ctx-aware step (arity = captures + 1): capture what the step sees.
        (defstep #":(\w+) pokes the app" [ctx subject]
          (swap! seen conj (cap/get-capability ctx :web (keyword subject)))
          nil)
        ;; Two separate as calls = two passes through inject-subject-browser,
        ;; exercising re-injection/short-circuit across invocations — the
        ;; real multi-step survival property.
        (is (= :passed (:status (repl/as :finance "pokes the app"))))
        (is (= :passed (:status (repl/as :finance "pokes the app"))))
        (is (= 2 (count @seen)))
        (is (every? #(identical? stub %) @seen)
            "Both steps saw the SAME costume browser instance")
        ;; Stored subject-keyed with :persistent mode.
        (let [entry (cap/get-capability-entry (repl/ctx :finance) :web :finance)]
          (is (identical? stub (:impl entry)))
          (is (= :persistent (:mode entry))))))))

(deftest test-inject-does-not-clobber-existing-web-cap
  (testing "a pre-existing :web capability for the subject wins over injection"
    (let [stub (make-stub-costume-browser :finance)
          sentinel (make-stub-costume-browser :sentinel)
          seen (atom [])]
      (with-redefs [costume/connect-costume! (stub-connect stub)]
        (repl/connect-costume! :finance)
        ;; Pre-seed the ctx with a different browser under :cap/web.finance.
        (swap! @#'repl/named-contexts assoc :finance
               (cap/assoc-capability {} :web sentinel :ephemeral :finance))
        (defstep #":(\w+) pokes the app" [ctx subject]
          (swap! seen conj (cap/get-capability ctx :web (keyword subject)))
          nil)
        (is (= :passed (:status (repl/as :finance "pokes the app"))))
        (is (identical? sentinel (first @seen))
            "Step saw the pre-existing capability, not the injected costume")))))

(deftest test-clear-resets-connected-subjects-and-stops-injection
  (testing "clear! empties connected-subjects and later as calls see no browser"
    (let [stub (make-stub-costume-browser :finance)
          seen (atom [])]
      (with-redefs [costume/connect-costume! (stub-connect stub)]
        (repl/connect-costume! :finance)
        (defstep #":(\w+) pokes the app" [ctx subject]
          (swap! seen conj (cap/get-capability ctx :web (keyword subject)))
          nil)
        (is (= :passed (:status (repl/as :finance "pokes the app"))))
        (is (identical? stub (first @seen)))
        (repl/clear!)
        (is (empty? @@#'repl/connected-subjects))
        ;; clear! wiped the registry too — re-register the step, then prove
        ;; injection no longer happens.
        (defstep #":(\w+) pokes the app" [ctx subject]
          (swap! seen conj (cap/get-capability ctx :web (keyword subject)))
          nil)
        (is (= :passed (:status (repl/as :finance "pokes the app"))))
        (is (nil? (second @seen))
            "After clear!, no costume browser is injected")))))
