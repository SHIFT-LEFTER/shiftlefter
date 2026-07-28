(ns shiftlefter.repl-surfaces-test
  "Characterization tests for the REPL surfaces + session-store subsystem (sl-8mz).

   These tests pin CURRENT behavior with real browser record shapes
   (EtaoinBrowser / CostumeBrowser) — unlike the tests in repl-test, which
   inject plain driver-shaped maps and therefore mask the bugs below.

   Three findings are pinned here. Each FIXME-marked assertion asserts
   behavior that is WRONG on purpose: when the referenced fix bead lands,
   the test fails loudly and should be flipped into its regression test.
   Follow-ups: sl-1qet (remove surfaces + session-store), sl-tss7 (fix
   close leak jointly with mode-awareness).

   1. Surface persist writes a useless all-nil handle: close-or-persist-browser!
      reads :webdriver-url / :session off the capability impl, but real impls
      are records (EtaoinBrowser [driver], CostumeBrowser [browser-atom ...])
      that carry neither key at the top level.
   2. Non-surface close is a silent no-op that reports :closed:
      session/close-session! short-circuits on attached? = (:session driver),
      nil for both record shapes, so no quit is ever issued and the browser
      session leaks.
   3. Connected costumes survive clear! only because of bug 2: REPL cleanup
      keys off surface? alone and ignores capability :mode, so a costume in a
      non-surface ctx takes the close branch — and lives only because that
      branch no-ops. A naive fix of bug 2 would start killing costumes."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [etaoin.api :as eta]
            [shiftlefter.capabilities.ctx :as cap]
            [shiftlefter.costume :as costume]
            [shiftlefter.costume.browser :as cbrowser]
            [shiftlefter.repl :as repl]
            [shiftlefter.stepengine.registry :as registry :refer [defstep]]
            [shiftlefter.webdriver.etaoin.browser :as ebrowser]
            [shiftlefter.webdriver.session-store :as store]))

(use-fixtures :each
  (fn [f]
    (registry/clear-registry!)
    (repl/reset-ctx!)
    (repl/reset-ctxs!)
    (repl/reset-surfaces!)
    (reset! @#'repl/connected-subjects {})
    (repl/vanilla!)
    (f)))

;; Spy store — same shape as repl-test's SpyStore (private there).
(defrecord SpyStore [saves deletes]
  store/ISessionStore
  (load-session-handle [_ _surface-name] nil)
  (save-session-handle! [_ surface-name handle]
    (swap! saves conj {:surface surface-name :handle handle})
    {:ok {:saved-at "test" :surface surface-name}})
  (delete-session-handle! [_ surface-name]
    (swap! deletes conj surface-name)
    {:ok true}))

(defn- make-spy-store []
  (->SpyStore (atom []) (atom [])))

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

(deftest test-surface-persist-writes-nil-handle-with-real-browser-record
  (testing "surface persist writes {:webdriver-url nil :session-id nil} for a real record impl"
    (let [spy (make-spy-store)
          record (make-real-etaoin-record "session-real-1")]
      (binding [repl/*session-store* (delay spy)]
        (inject-record-into-ctx! :alice record)
        (repl/mark-surface! :alice)
        (let [actions (repl/reset-ctxs!)]
          (is (= 1 (count actions)))
          (is (= :persisted (-> actions first :action)))
          ;; FIXME(sl-8mz -> sl-1qet): both handle fields SHOULD carry the values from
          ;; (:driver record) — "session-real-1" / the webdriver url. They are
          ;; nil because close-or-persist-browser! reads :webdriver-url and
          ;; :session off the record itself (repl.clj), where neither exists.
          ;; The persisted handle is unusable for any future reattach.
          (is (nil? (-> actions first :handle :webdriver-url)))
          (is (nil? (-> actions first :handle :session-id))))
        (is (= 1 (count @(:saves spy))))
        (let [saved (first @(:saves spy))]
          (is (= :alice (:surface saved)))
          (is (= {:webdriver-url nil :session-id nil} (:handle saved))))))))

(deftest test-non-surface-close-noops-on-real-record-but-reports-closed
  (testing "non-surface cleanup reports :closed but never issues a WebDriver quit"
    (let [quit-calls (atom 0)
          record (make-real-etaoin-record "session-real-2")]
      (with-redefs [eta/quit (fn [_] (swap! quit-calls inc))]
        (inject-record-into-ctx! :bob record)
        (let [actions (repl/reset-ctxs!)]
          (is (= :closed (-> actions first :action)))
          ;; FIXME(sl-8mz -> sl-tss7): quit SHOULD have been called once. It is never
          ;; called because close-session! short-circuits on attached? =
          ;; (:session driver), which is nil on the record (the session id
          ;; lives at (:session (:driver record))). The browser session
          ;; leaks while the report claims :closed.
          (is (zero? @quit-calls)))))))

(deftest test-costume-survives-clear-only-by-close-noop-accident
  (testing "a connected costume takes the close branch on clear! and survives only via bug 2"
    (let [quit-calls (atom 0)
          spy (make-spy-store)
          stub (cbrowser/make-costume-browser
                (make-real-etaoin-record "costume-session")
                :finance
                (constantly nil))]
      (with-redefs [costume/connect-costume!
                    (fn [n] {:status :connected :costume n
                             :port 9300 :pid 1 :browser stub})
                    eta/quit (fn [_] (swap! quit-calls inc))]
        (binding [repl/*session-store* (delay spy)]
          (repl/connect-costume! :finance)
          (defstep #":(\w+) pokes the app" [_subject] nil)
          (is (= :passed (:status (repl/as :finance "pokes the app"))))
          ;; The costume browser now lives in ctx :finance as :cap/web.finance,
          ;; mode :persistent, and :finance is NOT a surface.
          (is (identical? stub (cap/get-capability (repl/ctx :finance) :web :finance)))
          (is (= :persistent (:mode (cap/get-capability-entry (repl/ctx :finance) :web :finance))))
          (repl/clear!)
          ;; Not a surface → close branch, not persist:
          (is (empty? @(:saves spy)))
          ;; FIXME(sl-8mz -> sl-tss7): the costume survives, but for the WRONG reason.
          ;; Cleanup ignores capability :mode entirely (mode-blindness): the
          ;; costume takes the CLOSE branch and is spared only because that
          ;; branch no-ops on record shapes (see test above). A correct fix
          ;; must consult :mode (skip persistent) BEFORE fixing the close
          ;; no-op, or clear! starts killing live costumes.
          (is (zero? @quit-calls))
          (is (empty? @@#'repl/connected-subjects)))))))
