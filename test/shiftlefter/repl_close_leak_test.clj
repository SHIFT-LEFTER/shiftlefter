(ns shiftlefter.repl-close-leak-test
  "Regression suite for the REPL close-leak class (sl-tss7, from the sl-8mz
   findings). Until tss7, REPL cleanup was broken twice over: the close path
   silently no-oped on real record shapes while reporting :closed (leaking
   Chrome/ChromeDriver process trees — the sl-9vag orphan class), and cleanup
   was mode-blind — a connected costume took the close path and survived ONLY
   because the close never happened.

   The fix landed both halves in one change: capability impls are unwrapped
   to their raw driver map so the close is real, and :persistent capabilities
   (worn costumes) are deliberately SKIPPED — observable as
   {:action :skipped-persistent}. These tests hold that line:

   - an ephemeral real record IS quit (the leak stays fixed);
   - a connected costume survives clear! BY DESIGN (the skip, not an
     accident) — only destroy-costume! kills a costume's Chrome;
   - the skip is observable in the reset-ctxs! action vocabulary;
   - a failed close reports :close-failed, never a false :closed."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [etaoin.api :as eta]
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

(defn- make-real-etaoin-record
  "A real EtaoinBrowser record wrapping a raw etaoin driver map — the shape
   steps actually receive from provisioning/connect, where the session id
   lives INSIDE (:driver record), not on the record itself."
  [session-id]
  (ebrowser/make-etaoin-browser {:host "127.0.0.1"
                                 :port 9515
                                 :webdriver-url "http://127.0.0.1:9515"
                                 :session session-id
                                 :type :chrome}))

(defn- inject-record-into-ctx!
  [ctx-name record]
  (swap! @#'repl/named-contexts assoc ctx-name
         (cap/assoc-capability {} :web record :ephemeral)))

(defn- make-real-SHAPED-etaoin-record
  "An EtaoinBrowser over the shape `eta/chrome` ACTUALLY returns — `:url`
   plus a spawned `:process`, NO `:webdriver-url`. The pre-vmc5 close
   path (`close-session!`) read `:webdriver-url`, NPE'd on this shape,
   and reported :close-failed while leaking the driver process; the
   hand-crafted record above masked it."
  [session-id]
  (ebrowser/make-etaoin-browser {:host "127.0.0.1"
                                 :port 9515
                                 :url "http://127.0.0.1:9515"
                                 :session session-id
                                 :type :chrome
                                 :process :fake-process}))

(defn- stub-costume-browser []
  (cbrowser/make-costume-browser
   (make-real-etaoin-record "costume-session")
   :finance
   (constantly nil)))

(deftest test-ephemeral-record-is-actually-quit
  (testing "context cleanup unwraps a real record and issues the WebDriver quit"
    (let [quit-calls (atom 0)
          record (make-real-etaoin-record "session-real-2")]
      (with-redefs [eta/quit (fn [_] (swap! quit-calls inc))]
        (inject-record-into-ctx! :bob record)
        (let [actions (repl/reset-ctxs!)]
          (is (= 1 (count actions)))
          (is (= :closed (-> actions first :action)))
          (is (= :web (-> actions first :capability)))
          ;; Regression (sl-tss7 flip of the sl-8mz bug-2 pin): pre-fix this
          ;; was zero — close-session! short-circuited on (:session record),
          ;; nil for records, and the session leaked while reporting :closed.
          (is (= 1 @quit-calls)))))))

(deftest test-real-shaped-driver-is-actually-quit
  (testing "cleanup closes the shape eta/chrome actually returns (sl-vmc5)"
    (let [quit-calls (atom 0)
          record (make-real-SHAPED-etaoin-record "session-real-shape")]
      (with-redefs [eta/quit (fn [_] (swap! quit-calls inc))]
        (inject-record-into-ctx! :carol record)
        (let [actions (repl/reset-ctxs!)]
          ;; Pre-fix: close-session! NPE'd on the missing :webdriver-url →
          ;; :close-failed, zero quits, driver process leaked.
          (is (= :closed (-> actions first :action)))
          (is (= 1 @quit-calls)))))))

(deftest test-costume-survives-clear-by-mode-skip
  (testing "a connected costume survives clear! by design: the :persistent skip"
    (let [quit-calls (atom 0)
          stub (stub-costume-browser)]
      (with-redefs [costume/connect-costume!
                    (fn [n] {:status :connected :costume n
                             :port 9300 :pid 1 :browser stub})
                    eta/quit (fn [_] (swap! quit-calls inc))]
        (repl/connect-costume! :finance)
        (defstep #":(\w+) pokes the app" [_subject] nil)
        (is (= :passed (:status (repl/as :finance "pokes the app"))))
        (is (= :persistent (:mode (cap/get-capability-entry (repl/ctx :finance) :web :finance))))
        (repl/clear!)
        ;; Regression (sl-tss7 flip of the sl-8mz bug-3 pin): still zero, but
        ;; now because cleanup consults :mode and skips persistent capabilities
        ;; — not because the close path happened to no-op. Only
        ;; destroy-costume! kills a costume's Chrome.
        (is (zero? @quit-calls))
        (is (empty? @@#'repl/connected-subjects))))))

(deftest test-costume-skip-is-observable
  (testing "the persistent skip shows up in the reset-ctxs! action vocabulary"
    (let [stub (stub-costume-browser)]
      (with-redefs [costume/connect-costume!
                    (fn [n] {:status :connected :costume n
                             :port 9300 :pid 1 :browser stub})
                    eta/quit (fn [_] (throw (ex-info "must not quit a costume" {})))]
        (repl/connect-costume! :finance)
        (defstep #":(\w+) pokes the app" [_subject] nil)
        (is (= :passed (:status (repl/as :finance "pokes the app"))))
        (let [actions (repl/reset-ctxs!)]
          (is (= [{:action :skipped-persistent
                   :ctx-name :finance
                   :capability :web.finance}]
                 actions)))))))

(deftest test-mixed-ctxs-quit-ephemeral-spare-costume
  (testing "one clear!: the ephemeral record is quit exactly once, the costume spared"
    (let [quit-calls (atom 0)
          stub (stub-costume-browser)]
      (with-redefs [costume/connect-costume!
                    (fn [n] {:status :connected :costume n
                             :port 9300 :pid 1 :browser stub})
                    eta/quit (fn [_] (swap! quit-calls inc))]
        (repl/connect-costume! :finance)
        (defstep #":(\w+) pokes the app" [_subject] nil)
        (is (= :passed (:status (repl/as :finance "pokes the app"))))
        (inject-record-into-ctx! :bob (make-real-etaoin-record "bob-session"))
        (is (= 2 (count (repl/ctxs))) "precondition: costume ctx + ephemeral ctx")
        (repl/clear!)
        (is (= 1 @quit-calls)
            "exactly one quit — bob's ephemeral session, never the costume")))))
