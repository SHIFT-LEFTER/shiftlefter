(ns shiftlefter.repl-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [shiftlefter.config :as config]
            [shiftlefter.repl :as repl]
            [shiftlefter.stepengine.registry :as registry :refer [defstep]]
            [shiftlefter.capabilities.ctx :as cap]
            [shiftlefter.costume.wardrobe :as wardrobe]
            [shiftlefter.browser.chrome :as chrome]
            [babashka.fs :as fs]))

;; Clear registry, contexts, and drop any loaded config before each test
(use-fixtures :each
  (fn [f]
    (registry/clear-registry!)
    (repl/reset-ctx!)
    (repl/reset-ctxs!)
    (repl/reset-config!)  ; built-in defaults between tests
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
;; Named Context Tests (as)
;; -----------------------------------------------------------------------------

(deftest test-as-single-step
  (testing "as executes step in named context"
    (defstep #":(\w+) is named (\w+)" [_subject name]
      {:user name})
    (let [result (repl/as :alice "is named alice")]
      (is (= :passed (:status result)))
      (is (= :alice (:session result)))
      (is (= {:user "alice"} (:ctx result))))))

(deftest test-as-multiple-steps
  (testing "as executes multiple steps in sequence"
    (defstep #":(\w+) sets x" [_subject] {:x 1})
    (defstep #":(\w+) sets y" [_subject] {:y 2})
    (let [result (repl/as :test "sets x" "sets y")]
      (is (= :passed (:status result)))
      (is (= {:x 1 :y 2} (:ctx result))))))

(deftest test-as-separate-contexts
  (testing "as maintains separate contexts for different sessions"
    (defstep #":(\w+) is named (\w+)" [_subject name]
      {:user name})
    (repl/as :alice "is named alice")
    (repl/as :bob "is named bob")
    (is (= {:user "alice"} (repl/ctx :alice)))
    (is (= {:user "bob"} (repl/ctx :bob)))))

(deftest test-as-context-accumulation
  (testing "as accumulates context across calls"
    (defstep #":(\w+) sets (\w+) to (\d+)" [_subject k v]
      {(keyword k) (Integer/parseInt v)})
    (repl/as :test "sets x to 1")
    (repl/as :test "sets y to 2")
    (is (= {:x 1 :y 2} (repl/ctx :test)))))

(deftest test-as-stops-on-failure
  (testing "as stops on first failing step"
    (defstep #":(\w+) passes" [_subject] {:passed true})
    (defstep #":(\w+) fails" [_subject] (throw (ex-info "boom" {})))
    (defstep #":(\w+) never runs" [_subject] {:never true})
    (let [result (repl/as :test "passes" "fails" "never runs")]
      (is (= :failed (:status result)))
      (is (= {:passed true} (:ctx result)))
      (is (nil? (:never (repl/ctx :test)))))))

(deftest test-as-undefined-step
  (testing "as reports undefined step"
    (let [result (repl/as :test "does something undefined")]
      (is (= :undefined (:status result)))
      (is (= :test (:session result))))))

(deftest test-ctxs-returns-all
  (testing "ctxs returns all named contexts"
    (defstep #":(\w+) is named (\w+)" [_subject name] {:user name})
    (repl/as :alice "is named alice")
    (repl/as :bob "is named bob")
    (is (= {:alice {:user "alice"} :bob {:user "bob"}}
           (repl/ctxs)))))

(deftest test-reset-ctxs!
  (testing "reset-ctxs! clears all named contexts"
    (defstep #":(\w+) exists" [_subject] {:exists true})
    (repl/as :a "exists")
    (repl/as :b "exists")
    (is (= 2 (count (repl/ctxs))))
    (repl/reset-ctxs!)
    (is (= {} (repl/ctxs)))))

(deftest test-clear!-resets-named-contexts
  (testing "clear! also resets named contexts"
    (defstep #":(\w+) exists" [_subject] {:exists true})
    (repl/as :test "exists")
    (is (seq (repl/ctxs)))
    (repl/clear!)
    (is (= {} (repl/ctxs)))))

;; -----------------------------------------------------------------------------
;; Browser Lifecycle Tests
;; -----------------------------------------------------------------------------

(defn- make-fake-browser
  "Create a fake browser capability for testing lifecycle."
  [session-id]
  {:webdriver-url "http://127.0.0.1:9515"
   :session session-id
   :type :chrome})

(defn- inject-browser-into-ctx!
  "Inject a fake browser into a named context for testing.

   Stores under the bare `:cap/web` slot (no subject) — equivalent to the
   legacy `:default` session semantics that `assoc-active-browser` produced."
  [ctx-name session-id]
  (let [current (repl/ctx ctx-name)
        browser (make-fake-browser session-id)
        updated (cap/assoc-capability current :web browser :ephemeral)]
    ;; Directly update the named-contexts atom
    (swap! @#'repl/named-contexts assoc ctx-name updated)))

(deftest test-reset-ctxs-no-browser
  (testing "reset-ctxs! returns :none action when no browser present"
    (defstep #":(\w+) exists" [_subject] {:exists true})
    (repl/as :alice "exists")
    (let [actions (repl/reset-ctxs!)]
      (is (= 1 (count actions)))
      (is (= :none (-> actions first :action))))))

(deftest test-reset-ctxs-reports-close-failed-honestly
  (testing "reset-ctxs! reports :close-failed (not :closed) when the quit errors"
    ;; Inject a fake browser into the context
    (defstep #":(\w+) exists" [_subject] {:exists true})
    (repl/as :alice "exists")
    (inject-browser-into-ctx! :alice "session-123")

    ;; Verify browser is present
    (is (some? (cap/get-capability (repl/ctx :alice) :web)))

    ;; The fake driver's port has no ChromeDriver behind it, so the (now
    ;; real, sl-tss7) quit fails — and the action says so instead of lying
    ;; :closed as the pre-tss7 code did (the error used to be swallowed).
    (let [actions (repl/reset-ctxs!)]
      (is (= 1 (count actions)))
      (is (= :close-failed (-> actions first :action)))
      (is (some? (-> actions first :error)))
      (is (= :alice (-> actions first :ctx-name))))))

;; -----------------------------------------------------------------------------
;; Costume Re-export Tests (Task 2.6.9)
;; -----------------------------------------------------------------------------

(def repl-test-meta
  {:debug-port 9999
   :chrome-pid 12345
   :user-data-dir "/tmp/test"})

(defn with-temp-wardrobe-for-costumes
  "Fixture that redirects the wardrobe root to a temp directory outside the repo.

   Stubs ensure-gitignored! so re-export tests never touch the repo's .gitignore;
   the temp dir is outside the repo and untracked so the git-tracked guard passes."
  [f]
  (let [temp-dir (str (fs/create-temp-dir {:prefix "sl-repl-costume-test-"}))]
    (try
      (with-redefs [wardrobe/wardrobe-dir (constantly temp-dir)
                    wardrobe/ensure-gitignored! (constantly nil)]
        (f))
      (finally
        (fs/delete-tree temp-dir)))))

(deftest test-costume-reexports-list
  (testing "list-costumes re-exports costume/list-costumes"
    (with-temp-wardrobe-for-costumes
      (fn []
        ;; Should return empty list when no costumes
        (is (= [] (repl/list-costumes)))
        ;; Create a costume manually
        (wardrobe/ensure-dirs! :test-reexport)
        (wardrobe/save-costume-meta! :test-reexport repl-test-meta)
        ;; Now should list it
        (let [result (repl/list-costumes)]
          (is (= 1 (count result)))
          (is (= "test-reexport" (:name (first result)))))))))

(deftest test-costume-reexports-destroy
  (testing "destroy-costume! re-exports costume/destroy-costume!"
    (with-temp-wardrobe-for-costumes
      (fn []
        ;; Create a costume
        (wardrobe/ensure-dirs! :doomed)
        (wardrobe/save-costume-meta! :doomed repl-test-meta)
        (is (wardrobe/costume-exists? :doomed))
        ;; Browser dead — destroy proceeds without force (sl-7q7w)
        (with-redefs [chrome/port-listener-pids (constantly [])
                      chrome/probe-cdp (constantly {:status :dead})]
          (let [result (repl/destroy-costume! :doomed)]
            (is (= :destroyed (:status result)))
            (is (not (wardrobe/costume-exists? :doomed)))))))))

(deftest test-costume-reexports-destroy-force-passthrough
  (testing "destroy-costume! passes {:force true} through to the core fn (sl-7q7w)"
    (with-temp-wardrobe-for-costumes
      (fn []
        (wardrobe/ensure-dirs! :live-doomed)
        (wardrobe/save-costume-meta! :live-doomed repl-test-meta)
        (let [live (atom true)]
          (with-redefs [chrome/port-listener-pids (fn [_] (if @live [30906] []))
                        chrome/probe-cdp (fn [& _] (if @live {:status :alive} {:status :dead}))
                        chrome/kill-by-pid! (fn [pid] (reset! live false) {:killed true :pid pid})]
            ;; Without force: refusal, data surfaces to the REPL caller
            (let [refused (repl/destroy-costume! :live-doomed)]
              (is (= :costume/browser-live (get-in refused [:error :type])))
              (is (wardrobe/costume-exists? :live-doomed)))
            ;; With force: kill + destroy
            (let [forced (repl/destroy-costume! :live-doomed {:force true})]
              (is (= :destroyed (:status forced)))
              (is (not (wardrobe/costume-exists? :live-doomed))))))))))

(deftest test-costume-reexports-not-found
  (testing "re-exports return proper errors"
    (with-temp-wardrobe-for-costumes
      (fn []
        ;; connect to nonexistent should error
        (let [result (repl/connect-costume! :nonexistent)]
          (is (:error result))
          (is (= :costume/not-found (get-in result [:error :type]))))
        ;; destroy nonexistent should error
        (let [result (repl/destroy-costume! :nonexistent)]
          (is (:error result))
          (is (= :costume/not-found (get-in result [:error :type]))))))))

;; -----------------------------------------------------------------------------
;; Config API (sl-6wh9: one mode — load-config! / reset-config!)
;; -----------------------------------------------------------------------------

(deftest test-get-repl-config-defaults
  (testing "with nothing loaded the REPL runs under the built-in defaults"
    (is (= config/default-config (repl/get-repl-config)))
    (is (map? (:enforcement (repl/get-repl-config))))))

(deftest test-reset-config!-returns-defaults
  (testing "reset-config! returns :defaults"
    (is (= :defaults (repl/reset-config!)))))

(deftest test-load-config!-with-config-map
  (testing "load-config! with a map makes it the effective config"
    (let [cfg {:enforcement {:unknown-subject :warn}
               :runner {:step-paths ["steps/"]}}
          result (repl/load-config! cfg)]
      (is (= :ok (:status result)))
      (is (= cfg (repl/get-repl-config))))))

(deftest test-reset-config!-drops-loaded-config
  (testing "reset-config! returns the REPL to the built-in defaults"
    (repl/load-config! {:enforcement {:unknown-subject :warn}})
    (is (not= config/default-config (repl/get-repl-config)))
    (repl/reset-config!)
    (is (= config/default-config (repl/get-repl-config)))))

(deftest test-run-dry-under-defaults
  (testing "run-dry with nothing loaded compiles under the defaults: an
            untyped stepdef binds, runs, and is reported (warn, not a gate)"
    (defstep #"I am here" [] nil)
    (let [result (repl/run-dry "Feature: Test\n  Scenario: X\n    Given I am here")]
      (is (= :ok (:status result)))
      (is (= 1 (count (:plans result))))
      (is (= [:stepdef/untyped]
             (mapv :type (-> result :diagnostics :svo-issues)))))))

(deftest test-run-dry-under-loaded-config
  (testing "run-dry under a loaded config honors its enforcement levels"
    (defstep #"I am here" [] nil)
    (repl/load-config! {:runner {:step-paths ["steps/"]}
                        :enforcement {:untyped-stepdef :off}})
    (let [result (repl/run-dry "Feature: Test\n  Scenario: X\n    Given I am here")]
      (is (= :ok (:status result)))
      (is (= 1 (count (:plans result))))
      (is (empty? (-> result :diagnostics :svo-issues))))))

(deftest test-run-under-defaults
  (testing "run with nothing loaded executes under the defaults"
    (defstep #"I do something" [] nil)
    (let [result (repl/run "Feature: Test\n  Scenario: X\n    Given I do something")]
      (is (= :ok (:status result)))
      (is (= 1 (-> result :summary :passed))))))

(deftest test-run-under-loaded-config
  (testing "run under a loaded config includes diagnostics"
    (defstep #"I do something" [] nil)
    (repl/load-config! {:runner {:step-paths ["steps/"]}})
    (let [result (repl/run "Feature: Test\n  Scenario: X\n    Given I do something")]
      (is (= :ok (:status result)))
      (is (contains? result :diagnostics))
      (is (= 1 (-> result :summary :passed))))))

(deftest test-as-warns-on-unknown-actor
  (testing "as warns on unknown actor in Shifted mode"
    (defstep #":(\w+) exists" [_subject] {:exists true})
    ;; Create a temp glossary file for testing
    (let [temp-dir (str (fs/create-temp-dir {:prefix "sl-shifted-test-"}))
          subjects-file (str temp-dir "/subjects.edn")]
      (try
        ;; Write a glossary with only :alice
        (spit subjects-file "{:subjects {:alice {:desc \"Known user\"}}}")
        ;; Load a config carrying this glossary
        (repl/load-config! {:enforcement {:unknown-subject :warn}
                            :glossaries {:subjects subjects-file}})
        ;; Call as with unknown actor - should warn but succeed
        ;; repl/as prepends ":bob " so "exists" becomes ":bob exists"
        (let [stderr-out (java.io.StringWriter.)
              result (binding [*err* stderr-out]
                       (repl/as :bob "exists"))]
          (is (= :passed (:status result)))
          (is (str/includes? (str stderr-out) "WARNING"))
          (is (str/includes? (str stderr-out) ":bob"))
          (is (str/includes? (str stderr-out) "not in subject glossary")))
        ;; Call as with known actor - should not warn
        (let [stderr-out (java.io.StringWriter.)
              result (binding [*err* stderr-out]
                       (repl/as :alice "exists"))]
          (is (= :passed (:status result)))
          (is (not (str/includes? (str stderr-out) "WARNING"))))
        (finally
          (fs/delete-tree temp-dir))))))

(deftest test-as-no-warning-without-glossaries
  (testing "as does not warn when the effective config declares no glossaries
            (the built-in defaults) — a bare `as` is not a suite bind"
    (defstep #":([\w-]+) exists" [_subject] {:exists true})
    (repl/reset-config!)
    ;; repl/as prepends ":unknown-actor " so "exists" becomes ":unknown-actor exists"
    (let [stderr-out (java.io.StringWriter.)
          result (binding [*err* stderr-out]
                   (repl/as :unknown-actor "exists"))]
      (is (= :passed (:status result)))
      (is (= "" (str stderr-out))))))

(deftest test-load-config!-no-args-without-config-file
  (testing "load-config! with no args when no shiftlefter.edn exists"
    ;; This test runs from the project root which may or may not have shiftlefter.edn
    ;; Just verify it returns a result (either :ok or :error)
    (let [result (repl/load-config!)]
      (is (contains? result :status))
      ;; Back to the defaults for other tests
      (repl/reset-config!))))

;; -----------------------------------------------------------------------------
;; Exec-opts parity with the runner (sl-xjx0, Warden W8)
;; -----------------------------------------------------------------------------

(deftest test-run-mirrors-timing-and-seed
  (testing "repl/run threads the global :timing rung and a run seed into the
            engine, mirroring the runner's exec-opts (pre-fix both silently
            dropped: :timing fell to :default, ctx carried no :seed)"
    (let [observed (atom nil)]
      (defstep #"I observe the ctx" [ctx]
        (reset! observed {:seed (:seed ctx)
                          :timing (cap/get-run-timing ctx)})
        nil)
      (repl/load-config! {:timing {:deadline-ms 1234 :interval-ms 77}})
      (let [result (repl/run "Feature: T\n  Scenario: S\n    Given I observe the ctx")]
        (is (= :ok (:status result)))
        (is (= 1 (-> result :summary :passed)))
        (testing "the global :timing rung reaches step ctx"
          (is (= {:deadline-ms 1234 :interval-ms 77} (:timing @observed))))
        (testing "a fresh positive run seed reaches step ctx AND the result"
          (is (pos? (long (:seed @observed))))
          (is (= (:seed @observed) (:seed result))
              "the returned :seed is the one the run actually used"))))))
