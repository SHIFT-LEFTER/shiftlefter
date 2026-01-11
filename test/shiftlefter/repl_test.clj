(ns shiftlefter.repl-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [shiftlefter.repl :as repl]
            [shiftlefter.stepengine.registry :as registry :refer [defstep]]
            [shiftlefter.browser.ctx :as browser.ctx]
            [shiftlefter.subjects :as subjects]
            [shiftlefter.subjects.profile :as profile]
            [shiftlefter.webdriver.session-store :as store]
            [babashka.fs :as fs]))

;; Clear registry, contexts, and surfaces before each test
(use-fixtures :each
  (fn [f]
    (registry/clear-registry!)
    (repl/reset-ctx!)
    (repl/reset-ctxs!)
    (repl/reset-surfaces!)
    (f)))

;; -----------------------------------------------------------------------------
;; parse-only Tests
;; -----------------------------------------------------------------------------

(deftest test-parse-only-valid
  (testing "Parses valid Gherkin text"
    (let [result (repl/parse-only "Feature: Test\n  Scenario: X\n    Given foo")]
      (is (= :ok (:status result)))
      (is (= 1 (count (:pickles result)))))))

(deftest test-parse-only-invalid
  (testing "Returns parse error for invalid Gherkin"
    (let [result (repl/parse-only "Not valid Gherkin at all")]
      (is (= :parse-error (:status result)))
      (is (seq (:errors result))))))

;; -----------------------------------------------------------------------------
;; run-dry Tests
;; -----------------------------------------------------------------------------

(deftest test-run-dry-undefined-steps
  (testing "Reports undefined steps"
    (let [result (repl/run-dry "Feature: Test\n  Scenario: X\n    Given undefined step")]
      (is (= :bind-error (:status result)))
      (is (seq (-> result :diagnostics :undefined))))))

(deftest test-run-dry-defined-steps
  (testing "Binds defined steps successfully"
    (defstep #"I am ready" [] nil)
    (let [result (repl/run-dry "Feature: Test\n  Scenario: X\n    Given I am ready")]
      (is (= :ok (:status result)))
      (is (= 1 (count (:plans result)))))))

;; -----------------------------------------------------------------------------
;; run Tests
;; -----------------------------------------------------------------------------

(deftest test-run-passing-scenario
  (testing "Executes passing scenario"
    (defstep #"I do something" [] nil)
    (defstep #"it works" [] nil)
    (let [result (repl/run "
Feature: Test
  Scenario: Works
    Given I do something
    Then it works")]
      (is (= :ok (:status result)))
      (is (= 1 (-> result :summary :scenarios)))
      (is (= 1 (-> result :summary :passed)))
      (is (= 0 (-> result :summary :failed))))))

(deftest test-run-failing-scenario
  (testing "Reports failing scenario"
    (defstep #"I fail" [] (throw (ex-info "boom" {})))
    (let [result (repl/run "Feature: Test\n  Scenario: Fails\n    Given I fail")]
      (is (= :ok (:status result)))
      (is (= 1 (-> result :summary :failed))))))

(deftest test-run-pending-scenario
  (testing "Reports pending scenario"
    (defstep #"I am pending" [] :pending)
    (let [result (repl/run "Feature: Test\n  Scenario: Pending\n    Given I am pending")]
      (is (= :ok (:status result)))
      (is (= 1 (-> result :summary :pending))))))

(deftest test-run-parse-error
  (testing "Returns parse error for invalid input"
    (let [result (repl/run "garbage")]
      (is (= :parse-error (:status result))))))

(deftest test-run-bind-error
  (testing "Returns bind error for undefined steps"
    (let [result (repl/run "Feature: X\n  Scenario: Y\n    Given nope")]
      (is (= :bind-error (:status result))))))

;; -----------------------------------------------------------------------------
;; Utility Tests
;; -----------------------------------------------------------------------------

(deftest test-clear!
  (testing "Clears registry"
    (defstep #"something" [] nil)
    (is (seq (repl/steps)))
    (repl/clear!)
    (is (empty? (repl/steps)))))

(deftest test-steps
  (testing "Lists registered steps"
    (defstep #"step one" [] nil)
    (defstep #"step two" [] nil)
    (let [patterns (repl/steps)]
      (is (= 2 (count patterns)))
      (is (some #(= "step one" %) patterns))
      (is (some #(= "step two" %) patterns)))))

;; -----------------------------------------------------------------------------
;; Free Mode Tests
;; -----------------------------------------------------------------------------

(deftest test-step-passing
  (testing "Executes passing step"
    (defstep #"I do a thing" [] nil)
    (let [result (repl/step "I do a thing")]
      (is (= :passed (:status result))))))

(deftest test-step-undefined
  (testing "Reports undefined step"
    (let [result (repl/step "I do something undefined")]
      (is (= :undefined (:status result)))
      (is (= "I do something undefined" (:text result))))))

(deftest test-step-failing
  (testing "Reports failing step"
    (defstep #"I explode" [] (throw (ex-info "boom" {})))
    (let [result (repl/step "I explode")]
      (is (= :failed (:status result)))
      (is (some? (:error result))))))

(deftest test-step-pending
  (testing "Reports pending step"
    (defstep #"I am not ready" [] :pending)
    (let [result (repl/step "I am not ready")]
      (is (= :pending (:status result))))))

(deftest test-step-with-captures
  (testing "Passes captures to step function"
    (defstep #"I have (\d+) items" [n]
      {:count (Integer/parseInt n)})
    (let [result (repl/step "I have 42 items")]
      (is (= :passed (:status result)))
      (is (= 42 (-> result :ctx :count))))))

(deftest test-step-context-accumulation
  (testing "Context accumulates across steps"
    (defstep #"I set x to (\d+)" [n]
      {:x (Integer/parseInt n)})
    (defstep #"I set y to (\d+)" [n]
      {:y (Integer/parseInt n)})
    (repl/step "I set x to 10")
    (repl/step "I set y to 20")
    (is (= {:x 10 :y 20} (repl/ctx)))))

(deftest test-step-context-preserved-on-failure
  (testing "Context preserved when step fails"
    (defstep #"I set value" [] {:value 123})
    (defstep #"I fail" [] (throw (ex-info "oops" {})))
    (repl/step "I set value")
    (repl/step "I fail")
    (is (= {:value 123} (repl/ctx)))))

(deftest test-reset-ctx!
  (testing "reset-ctx! clears context"
    (defstep #"I set foo" [] {:foo "bar"})
    (repl/step "I set foo")
    (is (= {:foo "bar"} (repl/ctx)))
    (repl/reset-ctx!)
    (is (= {} (repl/ctx)))))

(deftest test-reset-ctx!-with-value
  (testing "reset-ctx! can set custom context"
    (repl/reset-ctx! {:custom "data"})
    (is (= {:custom "data"} (repl/ctx)))))

(deftest test-step-ambiguous
  (testing "Reports ambiguous step"
    (defstep #"I am .*" [] nil)
    (defstep #"I am here" [] nil)
    (let [result (repl/step "I am here")]
      (is (= :ambiguous (:status result)))
      (is (= 2 (count (:matches result)))))))

(deftest test-clear!-resets-context
  (testing "clear! also resets session context"
    (defstep #"I set data" [] {:data 1})
    (repl/step "I set data")
    (is (= {:data 1} (repl/ctx)))
    (repl/clear!)
    (is (= {} (repl/ctx)))))

;; -----------------------------------------------------------------------------
;; Named Context Tests (free mode)
;; -----------------------------------------------------------------------------

(deftest test-free-single-step
  (testing "free executes step in named context"
    (defstep #"I am (\w+)" [name]
      {:user name})
    (let [result (repl/free :alice "I am alice")]
      (is (= :passed (:status result)))
      (is (= :alice (:session result)))
      (is (= {:user "alice"} (:ctx result))))))

(deftest test-free-multiple-steps
  (testing "free executes multiple steps in sequence"
    (defstep #"I set x" [] {:x 1})
    (defstep #"I set y" [] {:y 2})
    (let [result (repl/free :test "I set x" "I set y")]
      (is (= :passed (:status result)))
      (is (= {:x 1 :y 2} (:ctx result))))))

(deftest test-free-separate-contexts
  (testing "free maintains separate contexts for different sessions"
    (defstep #"I am (\w+)" [name]
      {:user name})
    (repl/free :alice "I am alice")
    (repl/free :bob "I am bob")
    (is (= {:user "alice"} (repl/ctx :alice)))
    (is (= {:user "bob"} (repl/ctx :bob)))))

(deftest test-free-context-accumulation
  (testing "free accumulates context across calls"
    (defstep #"I set (\w+) to (\d+)" [k v]
      {(keyword k) (Integer/parseInt v)})
    (repl/free :test "I set x to 1")
    (repl/free :test "I set y to 2")
    (is (= {:x 1 :y 2} (repl/ctx :test)))))

(deftest test-free-stops-on-failure
  (testing "free stops on first failing step"
    (defstep #"I pass" [] {:passed true})
    (defstep #"I fail" [] (throw (ex-info "boom" {})))
    (defstep #"I never run" [] {:never true})
    (let [result (repl/free :test "I pass" "I fail" "I never run")]
      (is (= :failed (:status result)))
      (is (= {:passed true} (:ctx result)))
      (is (nil? (:never (repl/ctx :test)))))))

(deftest test-free-undefined-step
  (testing "free reports undefined step"
    (let [result (repl/free :test "I am undefined")]
      (is (= :undefined (:status result)))
      (is (= :test (:session result))))))

(deftest test-ctxs-returns-all
  (testing "ctxs returns all named contexts"
    (defstep #"I am (\w+)" [name] {:user name})
    (repl/free :alice "I am alice")
    (repl/free :bob "I am bob")
    (is (= {:alice {:user "alice"} :bob {:user "bob"}}
           (repl/ctxs)))))

(deftest test-reset-ctxs!
  (testing "reset-ctxs! clears all named contexts"
    (defstep #"I exist" [] {:exists true})
    (repl/free :a "I exist")
    (repl/free :b "I exist")
    (is (= 2 (count (repl/ctxs))))
    (repl/reset-ctxs!)
    (is (= {} (repl/ctxs)))))

(deftest test-clear!-resets-named-contexts
  (testing "clear! also resets named contexts"
    (defstep #"I exist" [] {:exists true})
    (repl/free :test "I exist")
    (is (seq (repl/ctxs)))
    (repl/clear!)
    (is (= {} (repl/ctxs)))))

;; -----------------------------------------------------------------------------
;; Surface Tests
;; -----------------------------------------------------------------------------

(deftest test-mark-surface!
  (testing "marks context as surface"
    (is (= :marked (repl/mark-surface! :alice)))
    (is (true? (repl/surface? :alice)))))

(deftest test-unmark-surface!
  (testing "unmarks context as surface"
    (repl/mark-surface! :alice)
    (is (true? (repl/surface? :alice)))
    (is (= :unmarked (repl/unmark-surface! :alice)))
    (is (false? (repl/surface? :alice)))))

(deftest test-surface?
  (testing "returns false for unmarked context"
    (is (false? (repl/surface? :nobody))))

  (testing "returns true for marked context"
    (repl/mark-surface! :alice)
    (is (true? (repl/surface? :alice))))

  (testing "returns false after unmark"
    (repl/mark-surface! :bob)
    (repl/unmark-surface! :bob)
    (is (false? (repl/surface? :bob)))))

(deftest test-list-surfaces
  (testing "returns empty when none marked"
    (is (empty? (repl/list-surfaces))))

  (testing "returns all marked surfaces"
    (repl/mark-surface! :alice)
    (repl/mark-surface! :bob)
    (is (= #{:alice :bob} (set (repl/list-surfaces))))))

(deftest test-reset-surfaces!
  (testing "clears all surface markings"
    (repl/mark-surface! :alice)
    (repl/mark-surface! :bob)
    (is (= 2 (count (repl/list-surfaces))))
    (repl/reset-surfaces!)
    (is (empty? (repl/list-surfaces)))))

(deftest test-clear!-resets-surfaces
  (testing "clear! also resets surface markings"
    (repl/mark-surface! :alice)
    (is (true? (repl/surface? :alice)))
    (repl/clear!)
    (is (false? (repl/surface? :alice)))))

(deftest test-acceptance-criteria-surfaces
  (testing "Task 2.5.8 AC: mark/unmark/check surfaces"
    (repl/clear!)
    (repl/mark-surface! :alice)
    (is (true? (repl/surface? :alice)))
    (repl/unmark-surface! :alice)
    (is (false? (repl/surface? :alice)))))

;; -----------------------------------------------------------------------------
;; Browser Lifecycle Tests
;; -----------------------------------------------------------------------------

;; Spy store for testing lifecycle behavior
(defrecord SpyStore [saves deletes]
  store/ISessionStore
  (load-session-handle [_ _surface-name] nil)
  (save-session-handle! [_ surface-name handle]
    (swap! saves conj {:surface surface-name :handle handle})
    {:ok {:saved-at "test" :surface surface-name}})
  (delete-session-handle! [_ surface-name]
    (swap! deletes conj surface-name)
    {:ok true}))

(defn make-spy-store []
  (->SpyStore (atom []) (atom [])))

(defn- make-fake-browser
  "Create a fake browser capability for testing lifecycle."
  [session-id]
  {:webdriver-url "http://127.0.0.1:9515"
   :session session-id
   :type :chrome})

(defn- inject-browser-into-ctx!
  "Inject a fake browser into a named context for testing."
  [ctx-name session-id]
  (let [current (repl/ctx ctx-name)
        browser (make-fake-browser session-id)
        updated (browser.ctx/assoc-active-browser current browser)]
    ;; Directly update the named-contexts atom
    (swap! @#'repl/named-contexts assoc ctx-name updated)))

(deftest test-reset-ctxs-no-browser
  (testing "reset-ctxs! returns :none action when no browser present"
    (defstep #"I exist" [] {:exists true})
    (repl/free :alice "I exist")
    (let [actions (repl/reset-ctxs!)]
      (is (= 1 (count actions)))
      (is (= :none (-> actions first :action))))))

(deftest test-reset-ctxs-non-surface-closes
  (testing "reset-ctxs! returns :closed action for non-surface with browser"
    ;; Inject a fake browser into the context
    (defstep #"I exist" [] {:exists true})
    (repl/free :alice "I exist")
    (inject-browser-into-ctx! :alice "session-123")

    ;; Verify browser is present
    (is (browser.ctx/browser-present? (repl/ctx :alice)))

    ;; Reset should report :closed (actual close fails gracefully)
    (let [actions (repl/reset-ctxs!)]
      (is (= 1 (count actions)))
      (is (= :closed (-> actions first :action)))
      (is (= :alice (-> actions first :ctx-name))))))

(deftest test-reset-ctxs-surface-persists
  (testing "reset-ctxs! persists session for surface contexts"
    (let [spy-store (make-spy-store)]
      (binding [repl/*session-store* (delay spy-store)]
        (defstep #"I exist" [] {:exists true})
        (repl/free :alice "I exist")
        (inject-browser-into-ctx! :alice "session-456")
        (repl/mark-surface! :alice)

        ;; Reset should persist, not close
        (let [actions (repl/reset-ctxs!)]
          (is (= 1 (count actions)))
          (is (= :persisted (-> actions first :action)))
          (is (= :alice (-> actions first :ctx-name)))
          (is (= "session-456" (-> actions first :handle :session-id))))

        ;; Verify store received the save
        (is (= 1 (count @(:saves spy-store))))
        (let [saved (first @(:saves spy-store))]
          (is (= :alice (:surface saved)))
          (is (= "session-456" (-> saved :handle :session-id))))))))

(deftest test-clear-handles-browser-lifecycle
  (testing "clear! handles browser lifecycle before clearing"
    (let [spy-store (make-spy-store)]
      (binding [repl/*session-store* (delay spy-store)]
        (defstep #"I exist" [] {:exists true})
        (repl/free :alice "I exist")
        (repl/free :bob "I exist")
        (inject-browser-into-ctx! :alice "alice-session")
        (inject-browser-into-ctx! :bob "bob-session")
        (repl/mark-surface! :alice)
        ;; bob is not a surface

        (repl/clear!)

        ;; Alice should be persisted
        (is (= 1 (count @(:saves spy-store))))
        (is (= :alice (-> @(:saves spy-store) first :surface)))))))

(deftest test-acceptance-criteria-lifecycle
  (testing "Task 2.5.9 AC: non-surface closes, surface persists"
    (let [spy-store (make-spy-store)]
      (binding [repl/*session-store* (delay spy-store)]
        ;; Setup non-surface
        (repl/clear!)
        (defstep #"I exist" [] {:exists true})
        (repl/free :alice "I exist")
        (inject-browser-into-ctx! :alice "alice-session")

        ;; Reset non-surface - should close (action = :closed)
        (let [actions (repl/reset-ctxs!)]
          (is (= :closed (-> actions first :action))))

        ;; Setup surface
        (repl/free :bob "I exist")
        (inject-browser-into-ctx! :bob "bob-session")
        (repl/mark-surface! :bob)

        ;; Reset surface - should persist
        (let [actions (repl/reset-ctxs!)]
          (is (= :persisted (-> actions first :action)))
          (is (= 1 (count @(:saves spy-store)))))))))

;; -----------------------------------------------------------------------------
;; Persistent Subject Re-export Tests (Task 2.6.9)
;; -----------------------------------------------------------------------------

(def ^:dynamic *test-home* nil)

(defn with-temp-home-for-subjects
  "Fixture that redirects user.home to a temp directory for subject tests."
  [f]
  (let [temp-dir (str (fs/create-temp-dir {:prefix "sl-repl-subjects-test-"}))]
    (try
      (with-redefs [profile/home-dir (constantly temp-dir)]
        (binding [*test-home* temp-dir]
          (f)))
      (finally
        (fs/delete-tree temp-dir)))))

(deftest test-persistent-subject-reexports-list
  (testing "list-persistent-subjects re-exports subjects/list-persistent"
    (with-temp-home-for-subjects
      (fn []
        ;; Should return empty list when no subjects
        (is (= [] (repl/list-persistent-subjects)))
        ;; Create a profile manually
        (profile/ensure-dirs! :test-reexport)
        (profile/save-browser-meta! :test-reexport {:debug-port 9999})
        ;; Now should list it
        (let [result (repl/list-persistent-subjects)]
          (is (= 1 (count result)))
          (is (= "test-reexport" (:name (first result)))))))))

(deftest test-persistent-subject-reexports-destroy
  (testing "destroy-persistent-subject! re-exports subjects/destroy-persistent!"
    (with-temp-home-for-subjects
      (fn []
        ;; Create a profile
        (profile/ensure-dirs! :doomed)
        (profile/save-browser-meta! :doomed {:debug-port 9999})
        (is (profile/profile-exists? :doomed))
        ;; Destroy via repl function
        (let [result (repl/destroy-persistent-subject! :doomed)]
          (is (= :destroyed (:status result)))
          (is (not (profile/profile-exists? :doomed))))))))

(deftest test-persistent-subject-reexports-not-found
  (testing "re-exports return proper errors"
    (with-temp-home-for-subjects
      (fn []
        ;; connect to nonexistent should error
        (let [result (repl/connect-persistent-subject! :nonexistent)]
          (is (:error result))
          (is (= :subject/not-found (get-in result [:error :type]))))
        ;; destroy nonexistent should error
        (let [result (repl/destroy-persistent-subject! :nonexistent)]
          (is (:error result))
          (is (= :subject/not-found (get-in result [:error :type]))))))))
