(ns shiftlefter.runner.core-test
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [shiftlefter.adapters.registry :as adapters]
            [shiftlefter.project-context :as project-context]
            [shiftlefter.runner.config :as config]
            [shiftlefter.runner.core :as runner]
            [shiftlefter.events :as events]
            [shiftlefter.stepengine.registry :as registry]
            [shiftlefter.test-helpers.adapter-registry :as mock]))

;; -----------------------------------------------------------------------------
;; Test Fixtures
;; -----------------------------------------------------------------------------

(defn clean-registry-fixture [f]
  (registry/clear-registry!)
  (f)
  (registry/clear-registry!))

(use-fixtures :each clean-registry-fixture)

;; Test feature paths
(def simple-feature "test/fixtures/features/simple.feature")
(def failing-feature "test/fixtures/features/failing.feature")
(def pending-feature "test/fixtures/features/pending.feature")
(def undefined-feature "test/fixtures/features/undefined.feature")
(def step-path "test/fixtures/steps/")

(defn- write-basic-project! [project config-dir step-dir feature-text step-text]
  (fs/create-dirs (fs/path project config-dir))
  (fs/create-dirs (fs/path project step-dir))
  (fs/create-dirs (fs/path project "features"))
  (spit (str (fs/path project config-dir "shiftlefter.edn"))
        (pr-str {:runner {:step-paths [(fs/file-name step-dir)]
                          :allow-pending? false}}))
  (spit (str (fs/path project step-dir "steps.clj"))
        (str "(ns steps.generated\n"
             "  (:require [shiftlefter.stepengine.registry :refer [defstep]]))\n"
             "(defstep #\"" step-text "\" [] nil)\n"))
  (spit (str (fs/path project "features" "f.feature")) feature-text))

(defn- write-shifted-warn-project!
  "Write a temp Shifted-mode project whose single feature step carries an
   unknown subject at :warn — the sl-qk8l repro shape. Glossaries point at
   the repo fixtures (absolute paths pass through config resolution)."
  [project]
  (let [subjects (str (fs/absolutize "test/fixtures/glossaries/subjects.edn"))
        verbs (str (fs/absolutize "test/fixtures/glossaries/verbs-web-project.edn"))]
    (fs/create-dirs (fs/path project "sl" "steps"))
    (fs/create-dirs (fs/path project "features"))
    (spit (str (fs/path project "sl" "shiftlefter.edn"))
          (pr-str {:runner {:step-paths ["steps/"]
                            :allow-pending? false}
                   :svo {:unknown-subject :warn}
                   :glossaries {:subjects subjects
                                :verbs {:web verbs}}
                   :interfaces {:web {:type :web :adapter :mock-web}}}))
    (spit (str (fs/path project "sl" "steps" "steps.clj"))
          (str "(ns steps.shifted-warn\n"
               "  (:require [shiftlefter.stepengine.registry :refer [defstep]]))\n"
               "(defstep #\"(.+) clicks (.+)\"\n"
               "  {:interface :web\n"
               "   :svo {:subject :$1 :verb :swipe :frame :default :object :$2}}\n"
               "  [ctx _subject _target]\n"
               "  ctx)\n"))
    (spit (str (fs/path project "features" "f.feature"))
          "Feature: F\n\n  Scenario: S\n    Given zorbo clicks the button\n")))

(defn- write-costume-project!
  "Write a temp Shifted-mode project whose persona :user/alice wears
   :finance (sl-1206 lint fixtures). `costumes` is the :costumes config
   value (nil = key absent)."
  [project costumes]
  (let [verbs (str (fs/absolutize "test/fixtures/glossaries/verbs-web-project.edn"))]
    (fs/create-dirs (fs/path project "sl" "steps"))
    (fs/create-dirs (fs/path project "sl" "glossary"))
    (fs/create-dirs (fs/path project "features"))
    (spit (str (fs/path project "sl" "glossary" "subjects.edn"))
          (pr-str {:subjects {:user {:desc "Standard user"
                                     :instances [{:id :alice :wears :finance}]}}}))
    (spit (str (fs/path project "sl" "shiftlefter.edn"))
          (pr-str (cond-> {:runner {:step-paths ["steps/"]
                                    :allow-pending? false}
                           :svo {}
                           :glossaries {:subjects "glossary/subjects.edn"
                                        :verbs {:web verbs}}
                           :interfaces {:web {:type :web :adapter :mock-web}
                                        :admin {:type :web :adapter :mock-web}}}
                    costumes (assoc :costumes costumes))))
    (spit (str (fs/path project "sl" "steps" "steps.clj"))
          (str "(ns steps.costume-lint\n"
               "  (:require [shiftlefter.stepengine.registry :refer [defstep]]))\n"
               "(defstep #\"(.+) clicks (.+)\"\n"
               "  {:interface :web\n"
               "   :svo {:subject :$1 :verb :swipe :frame :default :object :$2}}\n"
               "  [ctx _subject _target]\n"
               "  ctx)\n"))
    (spit (str (fs/path project "features" "f.feature"))
          "Feature: F\n\n  Scenario: S\n    Given user/alice clicks the button\n")))

(defn- write-shared-wears-project!
  "write-costume-project! variant with TWO personas wearing :finance and
   both acting in ONE scenario — the :costume/shared-wears trigger (the
   lint is per-plan: the same two wearers across two scenarios never trip
   it, costume_lint_test:92-98)."
  [project costumes]
  (write-costume-project! project costumes)
  (spit (str (fs/path project "sl" "glossary" "subjects.edn"))
        (pr-str {:subjects {:user {:desc "Standard user"
                                   :instances [{:id :alice :wears :finance}
                                               {:id :bob :wears :finance}]}}}))
  (spit (str (fs/path project "features" "f.feature"))
        (str "Feature: F\n\n  Scenario: S\n"
             "    Given user/alice clicks the button\n"
             "    And user/bob clicks the button\n")))

;; -----------------------------------------------------------------------------
;; Exit Code 0 - All Passed
;; -----------------------------------------------------------------------------

(deftest test-exit-0-all-passed
  (testing "Exit code 0 when all scenarios pass"
    (let [result (runner/execute! {:paths [simple-feature]
                               :step-paths [step-path]})]
      (is (= 0 (:exit-code result)))
      (is (= :passed (:status result)))
      (is (some? (:run-id result)))
      (is (= {:passed 1 :failed 0 :pending 0 :skipped 0}
             (:counts result))))))

(deftest test-exit-0-pending-allowed
  (testing "Exit code 0 when pending allowed and some pending"
    (let [result (runner/execute! {:paths [pending-feature]
                               :step-paths [step-path]
                               :_allow-pending true})]
      ;; With pending allowed, we should exit 0 even with pending steps
      ;; Note: we'd need config to actually set this. For now test basic behavior
      (is (some? (:run-id result))))))

;; -----------------------------------------------------------------------------
;; Exit Code 1 - Execution Failures
;; -----------------------------------------------------------------------------

(deftest test-exit-1-scenario-fails
  (testing "Exit code 1 when a scenario throws exception"
    (let [result (runner/execute! {:paths [failing-feature]
                               :step-paths [step-path]})]
      (is (= 1 (:exit-code result)))
      (is (= :failed (:status result)))
      (is (= 1 (get-in result [:counts :failed]))))))

(deftest test-exit-1-pending-not-allowed
  (testing "Exit code 1 when pending steps and pending not allowed (default)"
    (let [result (runner/execute! {:paths [pending-feature]
                               :step-paths [step-path]})]
      (is (= 1 (:exit-code result)))
      (is (= :failed (:status result)))
      (is (pos? (get-in result [:counts :pending]))))))

;; -----------------------------------------------------------------------------
;; Exit Code 2 - Planning Failures
;; -----------------------------------------------------------------------------

(deftest test-exit-2-undefined-steps
  (testing "Exit code 2 when steps cannot bind (undefined)"
    (let [result (runner/execute! {:paths [undefined-feature]
                               :step-paths [step-path]})]
      (is (= 2 (:exit-code result)))
      (is (= :planning-failed (:status result)))
      (is (some? (:diagnostics result))))))

(deftest test-exit-2-discovery-error
  (testing "Exit code 2 when feature files not found"
    (let [result (runner/execute! {:paths ["nonexistent/path/"]
                               :step-paths [step-path]})]
      (is (= 2 (:exit-code result)))
      (is (= :planning-failed (:status result))))))

(deftest test-exit-2-no-features-in-path
  (testing "Exit code 2 when path exists but has no .feature files"
    (let [result (runner/execute! {:paths ["test/fixtures/steps/"]
                               :step-paths [step-path]})]
      (is (= 2 (:exit-code result)))
      (is (= :planning-failed (:status result))))))

(deftest test-exit-2-parse-error
  (testing "Exit code 2 on parse errors"
    ;; Create a temp file with invalid gherkin
    (let [tmp-dir (System/getProperty "java.io.tmpdir")
          bad-feature (str tmp-dir "/bad_syntax.feature")]
      (spit bad-feature "Not valid Gherkin at all {{{")
      (try
        (let [result (runner/execute! {:paths [bad-feature]
                                   :step-paths [step-path]})]
          (is (= 2 (:exit-code result)))
          (is (= :planning-failed (:status result))))
        (finally
          (io/delete-file bad-feature true))))))

;; -----------------------------------------------------------------------------
;; Exit Code 3 - Crashes
;; -----------------------------------------------------------------------------

;; Note: Testing exit code 3 requires causing an internal crash, which is
;; difficult without modifying internal state. The try/catch in run! should
;; catch any uncaught exceptions and return exit code 3.

;; -----------------------------------------------------------------------------
;; Dry Run Mode
;; -----------------------------------------------------------------------------

(deftest test-dry-run-no-execute
  (testing "Dry run binds but does not execute"
    (let [result (runner/execute! {:paths [simple-feature]
                               :step-paths [step-path]
                               :dry-run true})]
      (is (= 0 (:exit-code result)))
      (is (= :dry-run (:status result)))
      (is (vector? (:plans result)))
      (is (nil? (:result result)) "Should not have execution result"))))

(deftest test-dry-run-with-undefined-steps
  (testing "Dry run still reports binding failures"
    (let [result (runner/execute! {:paths [undefined-feature]
                               :step-paths [step-path]
                               :dry-run true})]
      (is (= 2 (:exit-code result)))
      (is (= :planning-failed (:status result))))))

;; -----------------------------------------------------------------------------
;; Warn-Level SVO Rendering on Success Paths (sl-qk8l / sl-6h4r)
;; -----------------------------------------------------------------------------

(deftest test-dry-run-warn-svo-renders-on-console
  (testing "Warn-level SVO issues reach the console on dry-run success"
    (let [project (str (fs/create-temp-dir))]
      (try
        (write-shifted-warn-project! project)
        (let [ctx (project-context/resolve {:invocation-root project})
              output (java.io.StringWriter.)
              result (binding [*err* output]
                       (runner/execute! {:project-context ctx
                                         :paths ["features/f.feature"]
                                         :dry-run true
                                         :no-color true}))]
          (is (= 0 (:exit-code result)))
          (is (= :dry-run (:status result)))
          (is (str/includes? (str output) "SVO validation warnings:"))
          (is (str/includes? (str output) "WARNING: Unknown subject :zorbo"))
          (is (str/includes? (str output) "bound successfully (dry run)")))
        (finally
          (fs/delete-tree project))))))

(deftest test-dry-run-warn-svo-renders-in-edn
  (testing "Warn-level SVO issues reach the EDN summary on dry-run success"
    (let [project (str (fs/create-temp-dir))]
      (try
        (write-shifted-warn-project! project)
        (let [ctx (project-context/resolve {:invocation-root project})
              stdout (with-out-str
                       (runner/execute! {:project-context ctx
                                         :paths ["features/f.feature"]
                                         :dry-run true
                                         :edn true}))
              summary (edn/read-string stdout)]
          (is (= 0 (:run/exit-code summary)))
          (is (= :dry-run (:run/status summary)))
          (is (= {:scenarios 1 :steps 1} (:counts summary)))
          (is (= 1 (get-in summary [:diagnostics :counts :svo-issue-count])))
          (is (= :svo/unknown-subject
                 (-> summary :diagnostics :svo-issues first :type))))
        (finally
          (fs/delete-tree project))))))

;; -----------------------------------------------------------------------------
;; Costume lints on the dry-run surfaces (sl-1206)
;; -----------------------------------------------------------------------------

(deftest test-dry-run-costume-declared-match-green
  (testing "costume declared for the provisioned instance: dry-run exit 0, clean"
    (let [project (str (fs/create-temp-dir))]
      (try
        (write-costume-project! project {:finance {:interface :web}})
        (let [ctx (project-context/resolve {:invocation-root project})
              result (runner/execute! {:project-context ctx
                                       :paths ["features/f.feature"]
                                       :dry-run true
                                       :no-color true})]
          (is (= 0 (:exit-code result)))
          (is (= :dry-run (:status result))))
        (finally
          (fs/delete-tree project))))))

(deftest test-dry-run-costume-wrong-interface-errors
  (testing "wrong-world wear: exit 2, error on console AND in --edn planning"
    (let [project (str (fs/create-temp-dir))]
      (try
        (write-costume-project! project {:finance {:interface :admin}})
        (let [ctx (project-context/resolve {:invocation-root project})
              output (java.io.StringWriter.)
              result (binding [*err* output]
                       (runner/execute! {:project-context ctx
                                        :paths ["features/f.feature"]
                                        :dry-run true
                                        :no-color true}))]
          (is (= 2 (:exit-code result)))
          (is (= :planning-failed (:status result)))
          (is (str/includes? (str output) "declared for interface :admin"))
          (is (str/includes? (str output) "provisioned on :web")))
        (write-costume-project! project {:finance {:interface :admin}})
        (let [ctx (project-context/resolve {:invocation-root project})
              stdout (with-out-str
                       (runner/execute! {:project-context ctx
                                         :paths ["features/f.feature"]
                                         :dry-run true
                                         :edn true}))
              summary (edn/read-string stdout)]
          (is (= 2 (:run/exit-code summary)))
          (is (= :costume/wrong-interface
                 (-> summary :planning :svo-issues first :type))))
        (finally
          (fs/delete-tree project))))))

(deftest test-dry-run-costume-undeclared-warns
  (testing "undeclared costume: dry-run exit 0, teaching WARN on console and in --edn"
    (let [project (str (fs/create-temp-dir))]
      (try
        (write-costume-project! project nil)
        (let [ctx (project-context/resolve {:invocation-root project})
              output (java.io.StringWriter.)
              result (binding [*err* output]
                       (runner/execute! {:project-context ctx
                                         :paths ["features/f.feature"]
                                         :dry-run true
                                         :no-color true}))]
          (is (= 0 (:exit-code result)))
          (is (= :dry-run (:status result)))
          (is (str/includes? (str output) "is worn but not declared")))
        (let [ctx (project-context/resolve {:invocation-root project})
              stdout (with-out-str
                       (runner/execute! {:project-context ctx
                                         :paths ["features/f.feature"]
                                         :dry-run true
                                         :edn true}))
              summary (edn/read-string stdout)]
          (is (= 0 (:run/exit-code summary)))
          (is (= :costume/undeclared
                 (-> summary :diagnostics :svo-issues first :type)))
          (is (= :warn (-> summary :diagnostics :svo-issues first :severity))))
        (finally
          (fs/delete-tree project))))))

(deftest test-dry-run-costume-shared-wears-errors
  (testing "two personas wearing one costume in one scenario: exit 2,
            error on console AND in --edn planning (sl-d3cz, Warden W25 —
            shared-wears previously had no above-unit exit-2 proof)"
    (let [project (str (fs/create-temp-dir))]
      (try
        (write-shared-wears-project! project {:finance {:interface :web}})
        (let [ctx (project-context/resolve {:invocation-root project})
              output (java.io.StringWriter.)
              result (binding [*err* output]
                       (runner/execute! {:project-context ctx
                                         :paths ["features/f.feature"]
                                         :dry-run true
                                         :no-color true}))]
          (is (= 2 (:exit-code result)))
          (is (= :planning-failed (:status result)))
          (is (str/includes? (str output) ":finance")))
        (write-shared-wears-project! project {:finance {:interface :web}})
        (let [ctx (project-context/resolve {:invocation-root project})
              stdout (with-out-str
                       (runner/execute! {:project-context ctx
                                         :paths ["features/f.feature"]
                                         :dry-run true
                                         :edn true}))
              summary (edn/read-string stdout)]
          (is (= 2 (:run/exit-code summary)))
          (is (= :costume/shared-wears
                 (-> summary :planning :svo-issues first :type)))
          (is (= :error
                 (-> summary :planning :svo-issues first :severity))))
        (finally
          (fs/delete-tree project))))))

(deftest test-costume-config-malformed-is-a-planning-error
  ;; sl-st01 (Warden W2, sl-p1op): the run path never reaches
  ;; config/normalize, so a malformed :costumes entry used to pass silently
  ;; — no error, no warn, and costume enforcement quietly OFF (the lint saw
  ;; a nil declared interface; the undeclared-warn saw the key present).
  ;; The definitive repro: the most-guessable shorthand must be a
  ;; diagnostic from `sl run`, same envelope as every other config error.
  (testing "malformed shorthand {:finance :web}: exit 2, console AND --edn diagnostics"
    (let [project (str (fs/create-temp-dir))]
      (try
        (write-costume-project! project {:finance :web})
        (let [ctx (project-context/resolve {:invocation-root project})
              output (java.io.StringWriter.)
              result (binding [*err* output]
                       (runner/execute! {:project-context ctx
                                        :paths ["features/f.feature"]
                                        :dry-run true
                                        :no-color true}))]
          (is (= 2 (:exit-code result)))
          (is (= :planning-failed (:status result)))
          (is (str/includes? (str output) "Config error: Costume :finance must be a map")))
        (let [ctx (project-context/resolve {:invocation-root project})
              stdout (with-out-str
                       (runner/execute! {:project-context ctx
                                         :paths ["features/f.feature"]
                                         :dry-run true
                                         :edn true}))
              summary (edn/read-string stdout)]
          (is (= 2 (:run/exit-code summary)))
          (is (= :planning-failed (:run/status summary)))
          (is (= [:config/invalid-costume]
                 (mapv :type (-> summary :planning :errors)))
              "the --edn envelope carries the config error under :planning :errors"))
        (finally
          (fs/delete-tree project)))))
  (testing "unknown interface instance: exit 2 with the did-you-mean"
    (let [project (str (fs/create-temp-dir))]
      (try
        (write-costume-project! project {:finance {:interface :admn}})
        (let [ctx (project-context/resolve {:invocation-root project})
              output (java.io.StringWriter.)
              result (binding [*err* output]
                       (runner/execute! {:project-context ctx
                                        :paths ["features/f.feature"]
                                        :dry-run true
                                        :no-color true}))]
          (is (= 2 (:exit-code result)))
          (is (str/includes? (str output) "did you mean :admin?")))
        (finally
          (fs/delete-tree project))))))

(defn- write-config-error-project!
  "Minimal runnable project whose sl/shiftlefter.edn is `config-map` as
   written (sl-74w8 fixtures — config shapes that must be planning errors)."
  [project config-map]
  (fs/create-dirs (fs/path project "sl" "steps"))
  (fs/create-dirs (fs/path project "features"))
  (spit (str (fs/path project "sl" "shiftlefter.edn")) (pr-str config-map))
  (spit (str (fs/path project "sl" "steps" "steps.clj"))
        (str "(ns steps.config-errors\n"
             "  (:require [shiftlefter.stepengine.registry :refer [defstep]]))\n"
             "(defstep #\"a step\" [] nil)\n"))
  (spit (str (fs/path project "features" "f.feature"))
        "Feature: F\n\n  Scenario: S\n    Given a step\n"))

(defn- execute-config-error-project!
  "Run write-config-error-project! + execute! dry-run, returning
   {:result ... :stderr ...}. Cleans up the temp project."
  [config-map]
  (let [project (str (fs/create-temp-dir))]
    (try
      (write-config-error-project! project config-map)
      (let [ctx (project-context/resolve {:invocation-root project})
            output (java.io.StringWriter.)
            result (binding [*err* output]
                     (runner/execute! {:project-context ctx
                                       :paths ["features/f.feature"]
                                       :dry-run true
                                       :no-color true}))]
        {:result result :stderr (str output)})
      (finally
        (fs/delete-tree project)))))

(deftest test-config-validator-errors-are-planning-errors
  ;; sl-74w8: load-config-stage runs config/normalize itself, so the
  ;; validators that were projection-only (interfaces/timing/macro — the
  ;; sl-st01 seam covered only costumes) are planning errors on `sl run`
  ;; too: exit 2 through the same envelope, instead of loading silently
  ;; and failing later (or not at all) with a less legible diagnostic.
  (testing "interface missing :adapter: exit 2 planning error"
    ;; A non-:web instance name — deep-merge fills :web's :type/:adapter
    ;; from default-config, so :web can never repro a missing key.
    (let [{:keys [result stderr]}
          (execute-config-error-project!
           {:runner {:step-paths ["steps/"]}
            :interfaces {:api2 {:type :api}}})]
      (is (= 2 (:exit-code result)))
      (is (= :planning-failed (:status result)))
      (is (str/includes? stderr "Interface :api2 missing required :adapter key"))))
  (testing ":timing unknown inner key: exit 2 with the did-you-mean"
    (let [{:keys [result stderr]}
          (execute-config-error-project!
           {:runner {:step-paths ["steps/"]}
            :timing {:interval 250}})]
      (is (= 2 (:exit-code result)))
      (is (str/includes? stderr "did you mean :interval-ms?"))))
  (testing ":timing error rides the --edn envelope under :planning :errors"
    ;; One envelope proof for the family — the envelope path is shared
    ;; with the proven st01 costume tests above.
    (let [project (str (fs/create-temp-dir))]
      (try
        (write-config-error-project! project {:runner {:step-paths ["steps/"]}
                                              :timing {:interval 250}})
        (let [ctx (project-context/resolve {:invocation-root project})
              stdout (with-out-str
                       (runner/execute! {:project-context ctx
                                         :paths ["features/f.feature"]
                                         :dry-run true
                                         :edn true}))
              summary (edn/read-string stdout)]
          (is (= 2 (:run/exit-code summary)))
          (is (= :planning-failed (:run/status summary)))
          (is (= [:config/invalid-timing]
                 (mapv :type (-> summary :planning :errors)))))
        (finally
          (fs/delete-tree project)))))
  (testing "macros enabled without :registry-paths: exit 2 planning error"
    (let [{:keys [result stderr]}
          (execute-config-error-project!
           {:runner {:step-paths ["steps/"]
                     :macros {:enabled? true}}})]
      (is (= 2 (:exit-code result)))
      (is (str/includes? stderr "Macros enabled but :registry-paths is missing or empty")))))

(deftest test-execute-warn-svo-renders-on-console
  (testing "Warn-level SVO issues reach the console after the execute summary"
    (let [project (str (fs/create-temp-dir))]
      (try
        (write-shifted-warn-project! project)
        (let [ctx (project-context/resolve {:invocation-root project})
              adapter-registry (mock/registry {:mock-web {}})
              output (java.io.StringWriter.)
              result (binding [*err* output]
                       (runner/execute! {:project-context ctx
                                         :paths ["features/f.feature"]
                                         :adapter-registry adapter-registry
                                         :no-color true}))]
          (is (= 0 (:exit-code result)))
          (is (= :passed (:status result)))
          (is (str/includes? (str output) "1 scenario(s): 1 passed"))
          (is (str/includes? (str output) "SVO validation warnings:"))
          (is (str/includes? (str output) "WARNING: Unknown subject :zorbo")))
        (finally
          (fs/delete-tree project))))))

;; -----------------------------------------------------------------------------
;; Result Structure
;; -----------------------------------------------------------------------------

(deftest test-result-structure-on-success
  (testing "Result includes expected keys on success"
    (let [result (runner/execute! {:paths [simple-feature]
                               :step-paths [step-path]})]
      (is (contains? result :exit-code))
      (is (contains? result :run-id))
      (is (contains? result :status))
      (is (contains? result :counts))
      (is (contains? result :result))
      (is (map? (:result result)))
      (is (vector? (get-in result [:result :scenarios]))))))

(deftest test-result-structure-on-planning-failure
  (testing "Result includes diagnostics on binding failure"
    (let [result (runner/execute! {:paths [undefined-feature]
                               :step-paths [step-path]})]
      (is (contains? result :exit-code))
      (is (contains? result :run-id))
      (is (contains? result :status))
      (is (contains? result :diagnostics)))))

;; -----------------------------------------------------------------------------
;; Multiple Features
;; -----------------------------------------------------------------------------

(deftest test-multiple-features
  (testing "Running multiple feature files"
    (let [result (runner/execute! {:paths [simple-feature failing-feature]
                               :step-paths [step-path]})]
      (is (= 1 (:exit-code result)) "Should fail if any scenario fails")
      ;; Total = passed + failed + pending + skipped
      (is (= 2 (+ (get-in result [:counts :passed])
                  (get-in result [:counts :failed])
                  (get-in result [:counts :pending])
                  (get-in result [:counts :skipped]))))
      (is (= 1 (get-in result [:counts :passed])))
      (is (= 1 (get-in result [:counts :failed]))))))

;; -----------------------------------------------------------------------------
;; Counts Verification
;; -----------------------------------------------------------------------------

(deftest test-counts-structure
  (testing "Counts map has expected keys"
    (let [result (runner/execute! {:paths [simple-feature]
                               :step-paths [step-path]})
          counts (:counts result)]
      (is (contains? counts :passed))
      (is (contains? counts :failed))
      (is (contains? counts :pending))
      (is (contains? counts :skipped))
      (is (every? #(integer? %) (vals counts))))))

(deftest config-declared-step-paths-resolve-from-config-root
  (testing "sl/shiftlefter.edn step paths resolve against config root, not invocation root"
    (let [project (str (fs/create-temp-dir))]
      (try
        (write-basic-project!
         project "sl" "sl/steps"
         "Feature: F\n\n  Scenario: S\n    Given config-root step\n"
         "config-root step")
        (let [ctx (project-context/resolve {:invocation-root project})
              result (runner/execute! {:project-context ctx
                                       :paths ["features/f.feature"]
                                       :dry-run true})]
          (is (= :sl-directory (:layout ctx)))
          (is (= 0 (:exit-code result)))
          (is (= :dry-run (:status result))))
        (finally
          (fs/delete-tree project))))))

(deftest cli-step-paths-resolve-from-invocation-root
  (testing "--step-paths resolves against invocation root and overrides config step paths"
    (let [project (str (fs/create-temp-dir))]
      (try
        (fs/create-dirs (fs/path project "sl"))
        (fs/create-dirs (fs/path project "cli-steps"))
        (fs/create-dirs (fs/path project "features"))
        (spit (str (fs/path project "sl" "shiftlefter.edn"))
              (pr-str {:runner {:step-paths ["missing-config-steps"]
                                :allow-pending? false}}))
        (spit (str (fs/path project "cli-steps" "steps.clj"))
              "(ns steps.cli (:require [shiftlefter.stepengine.registry :refer [defstep]]))\n(defstep #\"cli step\" [] nil)\n")
        (spit (str (fs/path project "features" "f.feature"))
              "Feature: F\n\n  Scenario: S\n    Given cli step\n")
        (let [ctx (project-context/resolve {:invocation-root project})
              result (runner/execute! {:project-context ctx
                                       :paths ["features/f.feature"]
                                       :step-paths ["cli-steps"]
                                       :dry-run true})]
          (is (= 0 (:exit-code result)))
          (is (= :dry-run (:status result))))
        (finally
          (fs/delete-tree project))))))

;; -----------------------------------------------------------------------------
;; Bare-run feature corpus default (sl-fcjm): [:runner :features], config-root-
;; relative, real-config contexts only
;; -----------------------------------------------------------------------------

(defn- write-features-default-project!
  "sl/ layout with the corpus at the DEFAULT sl/features/ (no :features key)."
  [project]
  (fs/create-dirs (fs/path project "sl" "features"))
  (fs/create-dirs (fs/path project "sl" "steps"))
  (spit (str (fs/path project "sl" "shiftlefter.edn"))
        (pr-str {:runner {:step-paths ["steps/"] :allow-pending? false}}))
  (spit (str (fs/path project "sl" "steps" "steps.clj"))
        (str "(ns steps.corpus-default\n"
             "  (:require [shiftlefter.stepengine.registry :refer [defstep]]))\n"
             "(defstep #\"a corpus step\" [] nil)\n"))
  (spit (str (fs/path project "sl" "features" "f.feature"))
        "Feature: F\n\n  Scenario: S\n    Given a corpus step\n"))

(deftest bare-run-uses-features-default
  (testing "no CLI paths + real config -> sl/features/ corpus runs"
    (let [project (str (fs/create-temp-dir))]
      (try
        (write-features-default-project! project)
        (let [ctx (project-context/resolve {:invocation-root project})
              result (runner/execute! {:project-context ctx
                                       :paths []
                                       :dry-run true})]
          (is (= 0 (:exit-code result)))
          (is (= :dry-run (:status result)))
          (is (= 1 (count (:plans result)))))
        (finally
          (fs/delete-tree project))))))

(deftest cli-paths-win-over-features-default
  (testing "CLI paths bypass the corpus default entirely"
    (let [project (str (fs/create-temp-dir))]
      (try
        (write-features-default-project! project)
        ;; A second feature OUTSIDE the corpus default, addressed by CLI path.
        (fs/create-dirs (fs/path project "elsewhere"))
        (spit (str (fs/path project "elsewhere" "g.feature"))
              "Feature: G\n\n  Scenario: T\n    Given a corpus step\n")
        (let [ctx (project-context/resolve {:invocation-root project})
              result (runner/execute! {:project-context ctx
                                       :paths ["elsewhere/g.feature"]
                                       :dry-run true})]
          (is (= 0 (:exit-code result)))
          (is (= 1 (count (:plans result))))
          (is (= "T" (-> result :plans first :plan/pickle :pickle/name))
              "only the CLI-addressed scenario planned"))
        (finally
          (fs/delete-tree project))))))

(deftest bare-run-missing-default-corpus-is-planning-error
  (testing "real config but no sl/features/ -> exit 2 naming the default"
    (let [project (str (fs/create-temp-dir))]
      (try
        (fs/create-dirs (fs/path project "sl"))
        (spit (str (fs/path project "sl" "shiftlefter.edn")) "{}")
        (let [err (java.io.StringWriter.)
              ctx (project-context/resolve {:invocation-root project})
              result (binding [*err* err]
                       (runner/execute! {:project-context ctx
                                         :paths []
                                         :dry-run true}))]
          (is (= 2 (:exit-code result)))
          (is (= :planning-failed (:status result)))
          (is (str/includes? (str err) "sl/features/")
              "error points at the conventional corpus location"))
        (finally
          (fs/delete-tree project))))))

(deftest bare-run-without-config-unchanged
  (testing "defaults context (no config anywhere): bare run stays a no-paths error"
    (let [project (str (fs/create-temp-dir))]
      (try
        (let [err (java.io.StringWriter.)
              ctx (project-context/resolve {:invocation-root project})
              result (binding [*err* err]
                       (runner/execute! {:project-context ctx
                                         :paths []
                                         :dry-run true}))]
          (is (= :defaults (:config-source ctx)) "sanity: no config")
          (is (= 2 (:exit-code result)))
          (is (= :planning-failed (:status result)))
          (is (not (str/includes? (str err) "features"))
              "the corpus default is never probed without a real config"))
        (finally
          (fs/delete-tree project))))))

;; -----------------------------------------------------------------------------
;; Tag filtering (sl-i608) — planning-time subset selection
;; -----------------------------------------------------------------------------

(def tagged-feature "test/fixtures/features/tagged.feature")

(defn- run-tagged
  "Run the tagged fixture with an optional :tag-filter; returns the result map."
  [tag-filter & {:keys [dry-run]}]
  (runner/execute! (cond-> {:paths [tagged-feature]
                            :step-paths [step-path]}
                     tag-filter (assoc :tag-filter tag-filter)
                     dry-run (assoc :dry-run true))))

(deftest test-tag-filter-include
  (testing "include filter executes only matching scenarios (OR within set)"
    (let [result (run-tagged {:include #{"@fast"}})]
      (is (= 0 (:exit-code result)))
      (is (= {:passed 2 :failed 0 :pending 0 :skipped 0} (:counts result))
          "only @fast and @fast @wip; filtered-out are not passed or skipped"))))

(deftest test-tag-filter-exclude
  (testing "exclude filter skips matching scenarios"
    (let [result (run-tagged {:exclude #{"@wip"}})]
      (is (= 0 (:exit-code result)))
      (is (= 4 (get-in result [:counts :passed]))))))

(deftest test-tag-filter-exclude-wins
  (testing "composed include+exclude: exclude wins"
    (let [result (run-tagged {:include #{"@fast"} :exclude #{"@wip"}})]
      (is (= 0 (:exit-code result)))
      (is (= 1 (get-in result [:counts :passed]))))))

(deftest test-tag-filter-feature-inheritance
  (testing "feature-level tag inherits to every scenario (Gherkin semantics)"
    (let [result (run-tagged {:include #{"@suite"}})]
      (is (= 5 (get-in result [:counts :passed]))
          "all scenarios, including the one under the Rule, carry @suite"))))

(deftest test-tag-filter-rule-inheritance
  (testing "Rule-level tag inherits to its scenarios (addendum 2a)"
    (let [result (run-tagged {:include #{"@ruled"}})]
      (is (= 1 (get-in result [:counts :passed]))))))

(deftest test-tag-filter-absent-is-identity
  (testing "no :tag-filter => counts identical to an unfiltered run"
    (let [unfiltered (run-tagged nil)]
      (is (= 0 (:exit-code unfiltered)))
      (is (= {:passed 5 :failed 0 :pending 0 :skipped 0} (:counts unfiltered))))))

(deftest test-tag-filter-zero-selected
  (testing "over-narrow filter selects nothing: planning error (sl-7c7i,
            the vacuity witness) — exit 0 is existential, so 0-of-N is
            exit 2 with the selection story on the error"
    (let [result (run-tagged {:include #{"@nope"}})]
      (is (= 2 (:exit-code result)))
      (is (= :planning-failed (:status result)))
      (is (= :selection/empty (-> result :error :type)))
      (is (= {:selected 0 :total 5} (-> result :error :data (select-keys [:selected :total])))
          "the record confesses what was searched and how much existed"))))

(deftest test-tag-filter-invalid-shape
  (testing "invalid :tag-filter at the execute! boundary is a planning error"
    (let [result (run-tagged {:include #{"no-at-prefix"}})]
      (is (= 2 (:exit-code result)))
      (is (= :planning-failed (:status result))))
    (let [result (run-tagged {:include ["@vector-not-set"]})]
      (is (= 2 (:exit-code result))))))

(deftest test-tag-filter-excludes-unbindable-scenario
  (testing "a filtered-out scenario with an undefined step never fails planning
            (filter-before-binding doctrine, mini @broken case)"
    (let [tmp-dir (str (fs/create-temp-dir))
          feature (str (fs/path tmp-dir "quarantine.feature"))]
      (try
        (spit feature (str "Feature: Quarantine\n\n"
                           "  Scenario: Healthy\n"
                           "    Given I have 1 items in my cart\n\n"
                           "  @broken\n"
                           "  Scenario: Unbindable\n"
                           "    Given this step is defined nowhere at all\n"))
        (let [unfiltered (runner/execute! {:paths [feature]
                                           :step-paths [step-path]})
              filtered (runner/execute! {:paths [feature]
                                         :step-paths [step-path]
                                         :tag-filter {:exclude #{"@broken"}}})]
          (is (= 2 (:exit-code unfiltered)) "without a filter the suite can't plan")
          (is (= 0 (:exit-code filtered)) "excluded scenario is never bound")
          (is (= 1 (get-in filtered [:counts :passed]))))
        (finally
          (fs/delete-tree tmp-dir))))))

;; -----------------------------------------------------------------------------
;; Parallel execution surface (sl-q9wp)
;; -----------------------------------------------------------------------------

(deftest test-max-parallel-suite-equivalence
  (testing ":max-parallel N>1 yields counts/exit identical to a sequential run"
    (let [sequential (run-tagged nil)
          parallel (runner/execute! {:paths [tagged-feature]
                                     :step-paths [step-path]
                                     :max-parallel 4})]
      (is (= 0 (:exit-code parallel)))
      (is (= (:counts sequential) (:counts parallel)))
      (is (= (:status sequential) (:status parallel))))))

(deftest test-auto-serial-notice-line
  ;; sl-q9wp DP1: 'N scenario(s) auto-serialized: X costume, Y shared-impl,
  ;; Z hook' — stderr, i608 notice pattern, suppressed in EDN + dry-run;
  ;; @serial (:tag) is user intent and is not counted. The hook segment
  ;; landed with sl-esq AC8 (deliberate format change).
  (let [notice! #'runner/print-auto-serial-notice!
        plans [{:plan/schedule {:serial? true :reason :costume}}
               {:plan/schedule {:serial? true :reason :costume}}
               {:plan/schedule {:serial? true :reason :shared-impl}}
               {:plan/schedule {:serial? true :reason :tag}}
               {}]
        capture (fn [opts ps]
                  (let [sw (java.io.StringWriter.)]
                    (binding [*err* sw] (notice! opts ps))
                    (str sw)))]
    (is (= "3 scenario(s) auto-serialized: 2 costume, 1 shared-impl, 0 hook\n"
           (capture {} plans)))
    (is (= "4 scenario(s) auto-serialized: 2 costume, 1 shared-impl, 1 hook\n"
           (capture {} (conj plans
                             {:plan/schedule {:serial? true
                                              :reason [:hook "reset-db"]}}))))
    (is (= "" (capture {:edn true} plans)) "suppressed in EDN mode")
    (is (= "" (capture {:dry-run true} plans)) "suppressed in dry-run")
    (is (= "" (capture {} [{:plan/schedule {:serial? true :reason :tag}} {}]))
        "no auto gates fired, no line")))

;; -----------------------------------------------------------------------------
;; Config lint notices (sl-hlkz)
;; -----------------------------------------------------------------------------

(deftest test-config-lint-notice-line
  ;; sl-hlkz: i608 notice pattern — stderr, suppressed in EDN mode ONLY.
  ;; Dry-run keeps the warning: unlike the tag-filter notice, nothing else
  ;; carries this signal.
  (let [notice! #'runner/print-config-lint-notices!
        lints [{:type :config/misplaced-key :key :step-paths
                :suggested-path [:runner :step-paths]
                :message (str "config key :step-paths is not read at the top"
                              " level and was ignored — did you mean"
                              " [:runner :step-paths]?")}]
        capture (fn [opts]
                  (let [sw (java.io.StringWriter.)]
                    (binding [*err* sw]
                      (notice! opts "/proj/shiftlefter.edn" lints))
                    (str sw)))]
    (is (str/includes? (capture {}) "Config warning:"))
    (is (str/includes? (capture {}) "[:runner :step-paths]"))
    (is (str/includes? (capture {}) "[/proj/shiftlefter.edn]"))
    (is (str/includes? (capture {:dry-run true}) "Config warning:")
        "dry-run keeps the warning")
    (is (= "" (capture {:edn true})) "suppressed in EDN mode")
    (let [sw (java.io.StringWriter.)]
      (binding [*err* sw] (notice! {} "/p" []))
      (is (= "" (str sw)) "clean config: no output at all"))))

(deftest test-misplaced-step-paths-warns-and-still-runs
  ;; The sl-hlkz evidence case end-to-end: example 03's silent top-level
  ;; :step-paths now produces a stderr warning; the run itself is unaffected
  ;; (warning, never error — forward compat).
  (let [tmp-dir (str (fs/create-temp-dir))
        config-file (str (fs/path tmp-dir "shiftlefter.edn"))]
    (try
      (spit config-file "{:step-paths [\"nowhere/\"]}")
      (let [err (java.io.StringWriter.)
            result (binding [*err* err]
                     (runner/execute! {:paths [simple-feature]
                                       :step-paths [step-path]
                                       :config-path config-file
                                       :no-color true}))]
        (is (= 0 (:exit-code result)) "warning never affects the exit code")
        (is (str/includes? (str err) "Config warning:"))
        (is (str/includes? (str err) "[:runner :step-paths]"))
        (is (str/includes? (str err) config-file)
            "the notice names the config file"))
      (finally
        (fs/delete-tree tmp-dir)))))

(defn- run-edn-with-config
  "Run execute! with a temp shiftlefter.edn holding `config-text`, capturing
   stdout and stderr. Returns {:summary <parsed stdout EDN> :err <stderr str>
   :result <execute! return>}."
  [config-text opts]
  (let [tmp-dir (str (fs/create-temp-dir))
        config-file (str (fs/path tmp-dir "shiftlefter.edn"))]
    (try
      (spit config-file config-text)
      (let [out (java.io.StringWriter.)
            err (java.io.StringWriter.)
            result (binding [*out* out *err* err]
                     (runner/execute! (merge {:paths [simple-feature]
                                              :step-paths [step-path]
                                              :config-path config-file
                                              :edn true}
                                             opts)))]
        {:summary (edn/read-string (str out))
         :err (str err)
         :result result})
      (finally
        (fs/delete-tree tmp-dir)))))

(deftest test-edn-summary-carries-config-lints
  ;; sl-lnj1 chartered acceptance: machine mode is no longer the silent
  ;; corner — the same lints the human sees on stderr ride the EDN summary.
  (let [{:keys [summary err result]}
        (run-edn-with-config "{:step-paths [\"nowhere/\"]}" {})]
    (is (= 0 (:exit-code result)) "warnings never affect the exit code")
    (is (= 0 (:run/exit-code summary)))
    (let [[lint :as lints] (get-in summary [:diagnostics :config-lints])]
      (is (= 1 (count lints)))
      (is (= :config/misplaced-key (:type lint)))
      (is (= :step-paths (:key lint)))
      (is (= [:runner :step-paths] (:suggested-path lint)))
      (is (s/valid? ::config/lint-warnings lints)
          "envelope field conforms to the sl-hlkz warning spec"))
    (is (not (str/includes? err "Config warning:"))
        "EDN mode stderr suppression (sl-hlkz) still holds")
    (is (= summary (edn/read-string (pr-str summary))) "summary round-trips")))

(deftest test-dry-run-edn-summary-carries-config-lints
  (let [{:keys [summary]}
        (run-edn-with-config "{:step-paths [\"nowhere/\"]}" {:dry-run true})]
    (is (= :dry-run (:run/status summary)))
    (is (= [:runner :step-paths]
           (-> summary :diagnostics :config-lints first :suggested-path)))))

(deftest test-planning-edn-summary-carries-config-lints
  ;; a run that fails planning still had a lintable config
  (let [{:keys [summary]}
        (run-edn-with-config "{:step-paths [\"nowhere/\"]}"
                             {:paths [undefined-feature]})]
    (is (= 2 (:run/exit-code summary)))
    (is (= :step-paths (-> summary :planning :config-lints first :key)))))

(deftest test-config-stage-exit-edn-summary-carries-config-lints
  ;; sl-8cvd (Warden P2-5): the CONFIG-STAGE exit 2 — the path sl-74w8 made
  ;; the home of every normalize validator — used to build :errors only,
  ;; so a co-occurring lint vanished from machine mode exactly when a
  ;; config error suppressed the stderr notice. Both must ride :planning.
  (let [{:keys [summary err]}
        (run-edn-with-config
         "{:step-paths [\"nowhere/\"] :timing {:interval 250}}" {})]
    (is (= 2 (:run/exit-code summary)))
    (is (some #(str/includes? (:message %) ":timing")
              (get-in summary [:planning :errors]))
        "the config error rides :planning :errors")
    (is (= :step-paths (-> summary :planning :config-lints first :key))
        "the misplaced-key lint rides :planning :config-lints beside it")
    (is (not (str/includes? err "Config warning:"))
        "EDN mode stderr suppression still holds — the envelope is the carrier")))

(deftest test-clean-config-edn-summary-has-no-diagnostics
  ;; byte-identity guard: the additive field is ABSENT on clean configs
  (let [{:keys [summary]}
        (run-edn-with-config "{:runner {:step-paths [\"steps/\"]}}" {})]
    (is (= 0 (:run/exit-code summary)))
    (is (not (contains? summary :diagnostics)))))

;; -----------------------------------------------------------------------------
;; Exit-2 EDN summaries carry the planning error (sl-0n6t)
;; -----------------------------------------------------------------------------

(deftest test-config-error-rides-planning-edn
  ;; sl-0n6t: a malformed config used to produce :planning nil in --edn.
  (let [tmp-dir (str (fs/create-temp-dir))
        config-file (str (fs/path tmp-dir "shiftlefter.edn"))]
    (try
      (spit config-file "{:runner {")
      (let [out (java.io.StringWriter.)
            result (binding [*out* out]
                     (runner/execute! {:paths [simple-feature]
                                       :step-paths [step-path]
                                       :config-path config-file
                                       :edn true}))
            summary (edn/read-string (str out))
            [err :as errs] (get-in summary [:planning :errors])]
        (is (= 2 (:exit-code result)))
        (is (= 1 (count errs)))
        (is (= :config/error (:type err)))
        (is (string? (:message err))))
      (finally
        (fs/delete-tree tmp-dir)))))

(deftest test-parse-failure-rides-planning-edn-with-sub-errors
  ;; sl-0n6t AC2: the aggregate :parse/failed carries per-file detail —
  ;; nested :errors survive serialize-error (with :path attribution), and
  ;; the record-bearing keys (Location/Token) are projected away so the
  ;; summary stays plain-EDN-readable.
  (let [tmp-dir (str (fs/create-temp-dir))
        bad-feature (str (fs/path tmp-dir "bad.feature"))]
    (try
      (spit bad-feature "Feature: Broken\n  Scenario broken syntax\n  Given nothing\n")
      (let [out (java.io.StringWriter.)
            result (binding [*out* out]
                     (runner/execute! {:paths [bad-feature]
                                       :step-paths [step-path]
                                       :edn true}))
            summary (edn/read-string (str out))
            [err :as errs] (get-in summary [:planning :errors])]
        (is (= 2 (:exit-code result)))
        (is (= 1 (count errs)))
        (is (= :parse/failed (:type err)))
        (is (str/includes? (:message err) "parse error"))
        (is (seq (:errors err)) "per-file sub-errors present")
        (is (every? #(string? (:message %)) (:errors err)))
        (is (str/ends-with? (str (:path (first (:errors err)))) "bad.feature")
            "sub-errors carry file attribution")
        (is (= summary (edn/read-string (pr-str summary))) "summary round-trips"))
      (finally
        (fs/delete-tree tmp-dir)))))

(deftest test-max-parallel-error-rides-planning-edn
  ;; The programmatic execute! boundary (CLI input is caught earlier by
  ;; tools.cli validation, so this site is API-only).
  (let [out (java.io.StringWriter.)
        result (binding [*out* out]
                 (runner/execute! {:paths [simple-feature]
                                   :step-paths [step-path]
                                   :max-parallel 0
                                   :edn true}))
        summary (edn/read-string (str out))
        [err :as errs] (get-in summary [:planning :errors])]
    (is (= 2 (:exit-code result)))
    (is (= 1 (count errs)))
    (is (= :max-parallel/invalid (:type err)))
    (is (str/includes? (:message err) "positive integer"))))

;; -----------------------------------------------------------------------------
;; Bus lifecycle: closed on every exit path (sl-4tsi)
;; -----------------------------------------------------------------------------

(defn- run-with-captured-bus
  "Run execute! with the bus captured; returns {:bus b :result r}. The
   public-API probe for 'was the bus closed': publish! on a closed bus is a
   counted drop (events_test/test-publish-after-close-counts-as-drop), so a
   post-return probe publish must raise dropped-events from 0 to 1."
  [opts]
  (let [captured (atom nil)
        make-bus events/make-memory-bus
        result (with-redefs [events/make-memory-bus
                             (fn [] (let [b (make-bus)]
                                      (reset! captured b)
                                      b))]
                 (runner/execute! opts))]
    {:bus @captured :result result}))

(deftest test-bus-closed-on-dry-run-path
  ;; sl-4tsi lifecycle half: dry-run used to return without ever closing the
  ;; bus — the first real subscriber (sl-yia) would never drain.
  (let [{:keys [bus result]} (run-with-captured-bus
                              {:paths [simple-feature]
                               :step-paths [step-path]
                               :dry-run true
                               :no-color true})]
    (is (= :dry-run (:status result)))
    (is (zero? (events/dropped-events bus)) "nothing dropped during the run")
    (events/publish! bus {:type :probe/after-return :payload {}})
    (is (= 1 (events/dropped-events bus))
        "probe publish after return drops — the bus was closed")))

(deftest test-bus-closed-on-planning-failure-path
  ;; Planning-failure early exits used to escape without closing the bus.
  (let [out (java.io.StringWriter.)
        {:keys [bus result]} (binding [*out* out]
                               (run-with-captured-bus
                                {:paths [undefined-feature]
                                 :step-paths [step-path]
                                 :no-color true}))]
    (is (= 2 (:exit-code result)) "undefined steps are a planning failure")
    (events/publish! bus {:type :probe/after-return :payload {}})
    (is (= 1 (events/dropped-events bus))
        "probe publish after return drops — the bus was closed")))

;; -----------------------------------------------------------------------------
;; Adapter availability at config load (sl-gcdu)
;; -----------------------------------------------------------------------------

(deftest test-not-bundled-adapter-fails-at-config-load-edn
  ;; sl-gcdu AC1: a dead-end :playwright config fails as a planning error
  ;; (exit 2) with the EDN summary carrying the structured error — not as a
  ;; per-scenario provisioning failure at run time. Absence of the dep is
  ;; simulated by stubbing the probe (the test classpath has Playwright).
  (with-redefs [adapters/class-available? (constantly false)]
    (let [{:keys [summary result]}
          (run-edn-with-config
           "{:interfaces {:web {:type :web :adapter :playwright}}}" {})]
      (is (= 2 (:exit-code result)))
      (is (= 2 (:run/exit-code summary)))
      (is (= :planning-failed (:run/status summary)))
      (let [[err :as errs] (get-in summary [:planning :errors])]
        (is (= 1 (count errs)))
        (is (= :config/adapter-not-bundled (:type err)))
        (is (str/includes? (:message err) ":etaoin")
            "names the bundled remedy")
        (is (str/includes? (:message err) "com.microsoft.playwright/playwright")
            "names the dep coordinates remedy")))))

(deftest test-not-bundled-adapter-stderr-reports-every-interface
  ;; Tower condition on sl-gcdu: a config with several not-bundled
  ;; interfaces reports them all on stderr, not just the first.
  (with-redefs [adapters/class-available? (constantly false)]
    (let [tmp-dir (str (fs/create-temp-dir))
          config-file (str (fs/path tmp-dir "shiftlefter.edn"))]
      (try
        (spit config-file
              (pr-str {:interfaces {:web {:type :web :adapter :playwright}
                                    :admin-web {:type :web :adapter :playwright}}}))
        (let [err (java.io.StringWriter.)
              result (binding [*err* err]
                       (runner/execute! {:paths [simple-feature]
                                         :step-paths [step-path]
                                         :config-path config-file
                                         :no-color true}))]
          (is (= 2 (:exit-code result)))
          (is (= 2 (count (re-seq #"Config error:" (str err))))
              "one stderr line per dead-end interface"))
        (finally
          (fs/delete-tree tmp-dir))))))

(deftest test-playwright-config-passes-when-dep-present
  ;; sl-gcdu AC2: the test classpath genuinely has Playwright, so the same
  ;; config must clear config load — no false positive from the probe.
  (let [tmp-dir (str (fs/create-temp-dir))
        config-file (str (fs/path tmp-dir "shiftlefter.edn"))]
    (try
      (spit config-file "{:interfaces {:web {:type :web :adapter :playwright}}}")
      (let [out (java.io.StringWriter.)
            result (binding [*out* out]
                     (runner/execute! {:paths [simple-feature]
                                       :step-paths [step-path]
                                       :config-path config-file
                                       :no-color true}))]
        (is (= 0 (:exit-code result))
            "config load passes; the feature runs without touching :web"))
      (finally
        (fs/delete-tree tmp-dir)))))

(deftest test-max-parallel-invalid-boundary
  (testing "malformed :max-parallel at the execute! boundary is a planning error"
    (doseq [bad [0 -1 "4" 2.5]]
      (let [result (runner/execute! {:paths [tagged-feature]
                                     :step-paths [step-path]
                                     :max-parallel bad})]
        (is (= 2 (:exit-code result)) (pr-str bad))
        (is (= :planning-failed (:status result)) (pr-str bad))))))

;; -----------------------------------------------------------------------------
;; Capture resolution + warn channel (sl-3q9r)
;; -----------------------------------------------------------------------------

(defn- run-in-temp-project
  "execute! against a temp config-rooted project (so a results dir
   exists), stdout+stderr captured. Returns {:result :out :err :root}."
  [opts]
  (let [tmp (str (fs/create-temp-dir))
        _ (spit (str tmp "/shiftlefter.edn")
                (pr-str (or (:config-content opts) {:runner {:step-paths []}})))
        out (java.io.StringWriter.)
        err (java.io.StringWriter.)
        result (binding [*out* out *err* err]
                 (runner/execute! (-> opts
                                      (dissoc :config-content)
                                      (assoc :config-path (str tmp "/shiftlefter.edn")))))]
    {:result result :out (str out) :err (str err) :root tmp}))

(deftest test-capture-threads-to-execution
  (testing "an active policy reaches the exec layer: per-scenario artifact
            dirs appear in the run dir even for capability-less scenarios"
    (let [{:keys [result root]}
          (run-in-temp-project {:paths [simple-feature]
                                :step-paths [step-path]
                                :capture {:screenshot :on-failure}
                                :no-color true})]
      (is (= 0 (:exit-code result)))
      (is (some? (:results-dir result)))
      (is (fs/directory? (fs/path (:results-dir result) "attachments"))
          "capture-active scenarios pre-create their artifact dirs")
      (fs/delete-tree root)))
  (testing ":never everywhere -> no attachments dir"
    (let [{:keys [result root]}
          (run-in-temp-project {:paths [simple-feature]
                                :step-paths [step-path]
                                :capture {:screenshot :never}
                                :no-color true})]
      (is (= 0 (:exit-code result)))
      (is (not (fs/exists? (fs/path (:results-dir result) "attachments"))))
      (fs/delete-tree root))))

(deftest test-capture-flag-beats-config-wholly
  (testing "the flag replaces the config mirror AS A WHOLE VALUE"
    ;; Config declares a typo'd kind (would warn); the flag wins wholly, so
    ;; the typo is never even resolved — no warn for it, but the flag's own
    ;; unknown kind warns.
    (let [{:keys [err root]}
          (run-in-temp-project {:paths [simple-feature]
                                :step-paths [step-path]
                                :config-content {:runner {:step-paths []
                                                          :capture {:config-typo :on-failure}}}
                                :capture {:flag-typo :on-failure}
                                :no-color true})]
      (is (str/includes? err ":flag-typo"))
      (is (not (str/includes? err ":config-typo"))
          "the config mirror is not merged in — whole-value replacement")
      (fs/delete-tree root))))

(deftest test-capture-unknown-kind-warns-once
  (let [{:keys [result err root]}
        (run-in-temp-project {:paths [simple-feature]
                              :step-paths [step-path]
                              :config-content {:runner {:step-paths []
                                                        :capture {:screenshoot :on-failure}}}
                              :no-color true})]
    (is (= 0 (:exit-code result)) "never an error")
    (is (= 1 (count (re-seq #"Capture warning: kind :screenshoot" err)))
        "one stderr warn, and only one")
    (fs/delete-tree root)))

(deftest test-capture-invalid-config-value-warns-and-inerts
  (let [{:keys [result err root]}
        (run-in-temp-project {:paths [simple-feature]
                              :step-paths [step-path]
                              :config-content {:runner {:step-paths []
                                                        :capture {:screenshot :sometimes}}}
                              :no-color true})]
    (is (= 0 (:exit-code result))
        "a config-file bad VALUE never bricks the run (forward compat)")
    (is (str/includes? err "invalid value :sometimes"))
    (is (not (fs/exists? (fs/path (:results-dir result) "attachments")))
        "the kind is inert")
    (fs/delete-tree root)))

(deftest test-capture-without-results-dir-warns-and-disables
  ;; No config file -> :defaults context -> no results dir -> nowhere to
  ;; write. One warn, capture disabled, exit unchanged.
  (let [err (java.io.StringWriter.)
        result (binding [*out* (java.io.StringWriter.) *err* err]
                 (runner/execute! {:paths [simple-feature]
                                   :step-paths [step-path]
                                   :capture {:screenshot :on-failure}
                                   :no-color true}))]
    (is (= 0 (:exit-code result)))
    (is (nil? (:results-dir result)))
    (is (str/includes? (str err) "no results directory"))))

(deftest test-capture-warns-suppressed-under-edn
  (let [{:keys [out err root]}
        (run-in-temp-project {:paths [simple-feature]
                              :step-paths [step-path]
                              :config-content {:runner {:step-paths []
                                                        :capture {:screenshoot :on-failure}}}
                              :edn true})]
    (is (not (str/includes? err "Capture warning"))
        "stderr stays machine-clean under --edn")
    (let [summary (edn/read-string out)
          lints (get-in summary [:diagnostics :capture-lints])]
      (is (= 1 (count lints)) "the warn rides :diagnostics instead")
      (is (= :capture/unknown-kind (:type (first lints))))
      (is (= :screenshoot (:kind (first lints)))))
    (fs/delete-tree root)))

(deftest test-capture-dry-run-inert
  (let [{:keys [result root]}
        (run-in-temp-project {:paths [simple-feature]
                              :step-paths [step-path]
                              :capture {:screenshot :every-step}
                              :dry-run true
                              :no-color true})]
    (is (= 0 (:exit-code result)))
    (is (empty? (when (fs/exists? (fs/path root "results"))
                  (mapcat fs/list-dir
                          (filter fs/directory? (fs/list-dir (fs/path root "results"))))))
        "dry-run creates no run dir and captures nothing")
    (fs/delete-tree root)))

;; -----------------------------------------------------------------------------
;; Plan-time per-group adapter-capability lint (sl-sehc)
;; -----------------------------------------------------------------------------

(def browser-feature "test/fixtures/features/browser_smoke.feature")

(deftest test-capture-adapter-unsupported-playwright-dry-run
  ;; The silent-nothing edge, killed at PLAN time: a pure-Playwright project
  ;; with :console configured learns under dry-run that the kind can never
  ;; fire there — the registry-level unknown-kind check can't see it because
  ;; etaoin contributes :console in the same registry. Agents live on
  ;; dry-run; the test classpath has Playwright, so the probe passes.
  (let [{:keys [out err root]}
        (run-in-temp-project {:paths [browser-feature]
                              :step-paths []
                              :config-content {:runner {:step-paths []}
                                               :interfaces {:web {:type :web
                                                                  :adapter :playwright}}}
                              :capture {:console :on-failure}
                              :dry-run true
                              :edn true})]
    (is (not (str/includes? err "Capture warning"))
        "stderr stays machine-clean under --edn")
    (let [summary (edn/read-string out)
          lints (get-in summary [:diagnostics :capture-lints])]
      (is (= 1 (count lints)))
      (is (= :capture/adapter-unsupported (:type (first lints))))
      (is (= :console (:kind (first lints))))
      (is (= [:playwright] (:adapters (first lints)))))
    (fs/delete-tree root)))

(deftest test-capture-adapter-unsupported-console-mode-wording
  (let [{:keys [err root]}
        (run-in-temp-project {:paths [browser-feature]
                              :step-paths []
                              :config-content {:runner {:step-paths []}
                                               :interfaces {:web {:type :web
                                                                  :adapter :playwright}}}
                              :capture {:console :on-failure}
                              :dry-run true
                              :no-color true})]
    (is (= 1 (count (re-seq #"kind :console is supported by none of the adapters this run uses \(:playwright\)" err)))
        "one stderr warn, and only one")
    (fs/delete-tree root)))

(deftest test-capture-adapter-supported-is-clean
  ;; etaoin contributes all three kinds — a pure-etaoin project stays
  ;; lint-free even with every kind active.
  (let [{:keys [out root]}
        (run-in-temp-project {:paths [browser-feature]
                              :step-paths []
                              :config-content {:runner {:step-paths []}
                                               :interfaces {:web {:type :web
                                                                  :adapter :etaoin}}}
                              :capture {:console :on-failure
                                        :screenshot :every-step
                                        :page-source :on-failure}
                              :dry-run true
                              :edn true})]
    (is (nil? (get-in (edn/read-string out) [:diagnostics :capture-lints])))
    (fs/delete-tree root)))

(deftest test-capture-lint-split-typo-stays-runtime
  ;; The old/new split (sl-sehc D2/D3): under dry-run the NEW per-group
  ;; lint fires (plan-time by design) while the three sl-3q9r runtime
  ;; lints stay execution-stage — a typo'd kind produces NO lint in the
  ;; dry-run preview (it is not contributed anywhere, so it is the
  ;; unknown-kind class, which fires live; test-capture-unknown-kind-warns-
  ;; once covers that half).
  (let [{:keys [out root]}
        (run-in-temp-project {:paths [browser-feature]
                              :step-paths []
                              :config-content {:runner {:step-paths []}
                                               :interfaces {:web {:type :web
                                                                  :adapter :etaoin}}}
                              :capture {:screenshoot :on-failure}
                              :dry-run true
                              :edn true})]
    (is (nil? (get-in (edn/read-string out) [:diagnostics :capture-lints]))
        "typo'd kinds are not the adapter-unsupported class")
    (fs/delete-tree root)))

(deftest test-capture-adapter-unsupported-live-channel
  ;; LIVE-mode emission, no browser needed: a pure sms-mock run with a
  ;; browser-only kind configured. The plan lint rides stderr (console
  ;; mode) and the group diagnostics (--edn), composing with the runtime
  ;; lint channel (the append path in execute-and-report-stage).
  (let [tmp (str (fs/create-temp-dir))
        feature (str tmp "/sms.feature")]
    (try
      (spit feature (str "Feature: F\n  Scenario: S\n"
                         "    When :alice sends an SMS to '+15550100' saying 'hi'\n"))
      (spit (str tmp "/shiftlefter.edn")
            (pr-str {:runner {:step-paths []}
                     :interfaces {:sms {:type :sms :adapter :sms-mock}}}))
      (let [;; sl-uv3r: runs on the DEFAULT registry — the :sms-mock entry
            ;; now declares :impl-key :sms, so the builtin send step gets
            ;; the MockSMS impl (pre-fix this needed a bare-impl override
            ;; registry to work around the wrapper-map bug).
            run! (fn [edn?]
                   (let [out (java.io.StringWriter.)
                         err (java.io.StringWriter.)
                         result (binding [*out* out *err* err]
                                  (runner/execute!
                                   (cond-> {:paths [feature]
                                            :config-path (str tmp "/shiftlefter.edn")
                                            :step-paths []
                                            :capture {:screenshot :on-failure}
                                            :no-color true}
                                     edn? (assoc :edn true))))]
                     {:result result :out (str out) :err (str err)}))
            console (run! false)
            edn-run (run! true)]
        (is (= 0 (get-in console [:result :exit-code])) "the scenario passes")
        (is (= 1 (count (re-seq #"kind :screenshot is supported by none of the adapters this run uses \(:sms-mock\)"
                                (:err console)))))
        (let [lints (get-in (edn/read-string (:out edn-run))
                            [:diagnostics :capture-lints])]
          (is (= [:capture/adapter-unsupported] (mapv :type lints)))
          (is (= [:sms-mock] (:adapters (first lints))))))
      (finally
        (fs/delete-tree tmp)))))

(deftest test-capture-invalid-boundary
  ;; sl-3q9r: the programmatic execute! boundary (CLI input is caught by
  ;; tools.cli). Config-FILE values are deliberately NOT gated here — they
  ;; warn-and-inert at resolution (forward compat).
  (testing "malformed :capture at the execute! boundary is a planning error"
    (doseq [bad [{:screenshot :sometimes} {"screenshot" :never}
                 [:screenshot :never] "screenshot=never"]]
      (let [result (runner/execute! {:paths [tagged-feature]
                                     :step-paths [step-path]
                                     :capture bad})]
        (is (= 2 (:exit-code result)) (pr-str bad))
        (is (= :planning-failed (:status result)) (pr-str bad)))))
  (testing "the error rides the planning EDN with type + explain"
    (let [out (java.io.StringWriter.)
          _ (binding [*out* out]
              (runner/execute! {:paths [tagged-feature]
                                :step-paths [step-path]
                                :capture {:screenshot :sometimes}
                                :edn true}))
          summary (edn/read-string (str out))
          [err] (get-in summary [:planning :errors])]
      (is (= :capture/invalid (:type err)))
      (is (str/includes? (:message err) ":screenshot")))))

;; -----------------------------------------------------------------------------
;; Tag filtering × dry-run: the selection preview (addendum 3)
;; -----------------------------------------------------------------------------

(deftest test-tag-filter-dry-run-preview
  (testing "dry-run previews the selected subset in the result"
    (let [result (run-tagged {:include #{"@fast"}} :dry-run true)]
      (is (= 0 (:exit-code result)))
      (is (= 2 (count (:plans result))))))
  (testing "dry-run console line carries both counts when a filter is active"
    (let [err (java.io.StringWriter.)
          _ (binding [*err* err] (run-tagged {:include #{"@fast"}} :dry-run true))
          line (str err)]
      (is (str/includes? line "2 scenario(s) bound successfully (dry run; 3 filtered out by tags)")
          (str "got: " line))))
  (testing "dry-run console line is byte-identical to today without a filter"
    (let [err (java.io.StringWriter.)
          _ (binding [*err* err] (run-tagged nil :dry-run true))
          line (str err)]
      (is (str/includes? line "5 scenario(s) bound successfully (dry run)")
          (str "got: " line))
      (is (not (str/includes? line "filtered out"))))))

(deftest test-tag-filter-dry-run-edn-preview
  (testing "EDN dry-run summary carries additive :filtered-out only with a filter"
    (let [filtered (let [out (with-out-str
                               (runner/execute! {:paths [tagged-feature]
                                                 :step-paths [step-path]
                                                 :dry-run true :edn true
                                                 :tag-filter {:include #{"@fast"}}}))]
                     (edn/read-string out))
          plain (let [out (with-out-str
                            (runner/execute! {:paths [tagged-feature]
                                              :step-paths [step-path]
                                              :dry-run true :edn true}))]
                  (edn/read-string out))]
      (is (= 2 (get-in filtered [:counts :scenarios])))
      (is (= 3 (:filtered-out filtered)))
      (is (= 5 (get-in plain [:counts :scenarios])))
      (is (not (contains? plain :filtered-out))
          "no filter => summary shape unchanged"))))

;; -----------------------------------------------------------------------------
;; Exit code for :error scenarios (sl-esq)
;; -----------------------------------------------------------------------------

(deftest error-count-makes-the-run-degraded
  (testing "scenario :error (infrastructure family) => exit 4, regardless
            of allow-pending (sl-7c7i)"
    (is (= 4 (#'runner/compute-exit-code {:counts {:passed 3 :error 1}} false)))
    (is (= 4 (#'runner/compute-exit-code {:counts {:passed 3 :error 1}} true))))
  (testing "execution worst-wins: degraded (4) beats failed (1)"
    (is (= 4 (#'runner/compute-exit-code {:counts {:failed 2 :error 1}} false))))
  (testing "absent :error count leaves historical semantics untouched"
    (is (= 0 (#'runner/compute-exit-code {:counts {:passed 3}} false)))
    (is (= 1 (#'runner/compute-exit-code {:counts {:failed 1}} false)))))
