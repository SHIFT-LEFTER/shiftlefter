(ns shiftlefter.repl-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [shiftlefter.repl :as repl]
            [shiftlefter.stepengine.registry :as registry :refer [defstep]]
            [shiftlefter.capabilities.ctx :as cap]
            [shiftlefter.costume.wardrobe :as wardrobe]
            [babashka.fs :as fs]))

;; Clear registry, contexts, and reset to vanilla mode before each test
(use-fixtures :each
  (fn [f]
    (registry/clear-registry!)
    (repl/reset-ctx!)
    (repl/reset-ctxs!)
    (repl/vanilla!)  ; Ensure vanilla mode between tests
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
        ;; Destroy via repl function
        (let [result (repl/destroy-costume! :doomed)]
          (is (= :destroyed (:status result)))
          (is (not (wardrobe/costume-exists? :doomed))))))))

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
;; Shifted Mode Tests (WI-031.011)
;; -----------------------------------------------------------------------------

(deftest test-shifted?-defaults-to-false
  (testing "shifted? returns false by default"
    (is (false? (repl/shifted?)))))

(deftest test-vanilla!-returns-vanilla
  (testing "vanilla! returns :vanilla"
    (is (= :vanilla (repl/vanilla!)))))

(deftest test-shifted!-with-config-map
  (testing "shifted! with custom config enables Shifted mode"
    (let [cfg {:svo {:unknown-subject :warn}
               :runner {:step-paths ["steps/"]}}
          result (repl/shifted! cfg)]
      (is (= :ok (:status result)))
      (is (true? (repl/shifted?)))
      (is (= cfg (repl/get-repl-config))))))

(deftest test-vanilla!-disables-shifted-mode
  (testing "vanilla! disables Shifted mode"
    (repl/shifted! {:svo {:unknown-subject :warn}})
    (is (true? (repl/shifted?)))
    (repl/vanilla!)
    (is (false? (repl/shifted?)))
    (is (nil? (repl/get-repl-config)))))

(deftest test-run-dry-vanilla-mode
  (testing "run-dry in vanilla mode uses bind-suite directly"
    (defstep #"I am here" [] nil)
    (let [result (repl/run-dry "Feature: Test\n  Scenario: X\n    Given I am here")]
      (is (= :ok (:status result)))
      (is (= 1 (count (:plans result)))))))

(deftest test-run-dry-shifted-mode
  (testing "run-dry in Shifted mode uses compile-suite"
    (defstep #"I am here" [] nil)
    ;; Shifted mode with minimal config (no glossaries = vanilla behavior)
    (repl/shifted! {:runner {:step-paths ["steps/"]}})
    (let [result (repl/run-dry "Feature: Test\n  Scenario: X\n    Given I am here")]
      (is (= :ok (:status result)))
      (is (= 1 (count (:plans result)))))))

(deftest test-run-vanilla-mode
  (testing "run in vanilla mode executes without SVO validation"
    (defstep #"I do something" [] nil)
    (let [result (repl/run "Feature: Test\n  Scenario: X\n    Given I do something")]
      (is (= :ok (:status result)))
      (is (= 1 (-> result :summary :passed))))))

(deftest test-run-shifted-mode
  (testing "run in Shifted mode includes diagnostics"
    (defstep #"I do something" [] nil)
    (repl/shifted! {:runner {:step-paths ["steps/"]}})
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
        ;; Enable Shifted mode with this glossary
        (repl/shifted! {:svo {:unknown-subject :warn}
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

(deftest test-as-no-warning-in-vanilla-mode
  (testing "as does not warn in vanilla mode"
    (defstep #":([\w-]+) exists" [_subject] {:exists true})
    (repl/vanilla!)
    ;; repl/as prepends ":unknown-actor " so "exists" becomes ":unknown-actor exists"
    (let [stderr-out (java.io.StringWriter.)
          result (binding [*err* stderr-out]
                   (repl/as :unknown-actor "exists"))]
      (is (= :passed (:status result)))
      (is (= "" (str stderr-out))))))

(deftest test-shifted!-no-args-without-config-file
  (testing "shifted! with no args when no shiftlefter.edn exists"
    ;; This test runs from the project root which may or may not have shiftlefter.edn
    ;; Just verify it returns a result (either :ok or :error)
    (let [result (repl/shifted!)]
      (is (contains? result :status))
      ;; Reset to vanilla for other tests
      (repl/vanilla!))))
