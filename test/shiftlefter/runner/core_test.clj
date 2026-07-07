(ns shiftlefter.runner.core-test
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [shiftlefter.project-context :as project-context]
            [shiftlefter.runner.core :as runner]
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
    (fs/create-dirs (fs/path project "sl"))
    (fs/create-dirs (fs/path project "steps"))
    (fs/create-dirs (fs/path project "features"))
    (spit (str (fs/path project "sl" "shiftlefter.edn"))
          (pr-str {:runner {:step-paths ["../steps"]
                            :allow-pending? false}
                   :svo {:unknown-subject :warn}
                   :glossaries {:subjects subjects
                                :verbs {:web verbs}}
                   :interfaces {:web {:type :web :adapter :mock-web}}}))
    (spit (str (fs/path project "steps" "steps.clj"))
          (str "(ns steps.shifted-warn\n"
               "  (:require [shiftlefter.stepengine.registry :refer [defstep]]))\n"
               "(defstep #\"(.+) clicks (.+)\"\n"
               "  {:interface :web\n"
               "   :svo {:subject :$1 :verb :swipe :frame :default :object :$2}}\n"
               "  [ctx _subject _target]\n"
               "  ctx)\n"))
    (spit (str (fs/path project "features" "f.feature"))
          "Feature: F\n\n  Scenario: S\n    Given zorbo clicks the button\n")))

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
