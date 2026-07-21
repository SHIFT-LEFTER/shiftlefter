(ns shiftlefter.runner.core-test
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [shiftlefter.project-context :as project-context]
            [shiftlefter.runner.config :as config]
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
  (testing "over-narrow filter selects nothing: exit 0, zero counts"
    (let [result (run-tagged {:include #{"@nope"}})]
      (is (= 0 (:exit-code result)))
      (is (= :passed (:status result)))
      (is (= {:passed 0 :failed 0 :pending 0 :skipped 0} (:counts result))))))

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

(deftest test-clean-config-edn-summary-has-no-diagnostics
  ;; byte-identity guard: the additive field is ABSENT on clean configs
  (let [{:keys [summary]}
        (run-edn-with-config "{:runner {:step-paths [\"steps/\"]}}" {})]
    (is (= 0 (:run/exit-code summary)))
    (is (not (contains? summary :diagnostics)))))

(deftest test-max-parallel-invalid-boundary
  (testing "malformed :max-parallel at the execute! boundary is a planning error"
    (doseq [bad [0 -1 "4" 2.5]]
      (let [result (runner/execute! {:paths [tagged-feature]
                                     :step-paths [step-path]
                                     :max-parallel bad})]
        (is (= 2 (:exit-code result)) (pr-str bad))
        (is (= :planning-failed (:status result)) (pr-str bad))))))

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

(deftest error-count-makes-the-run-red
  (testing "scenario :error (hook threw) => exit 1, regardless of allow-pending"
    (is (= 1 (#'runner/compute-exit-code {:counts {:passed 3 :error 1}} false)))
    (is (= 1 (#'runner/compute-exit-code {:counts {:passed 3 :error 1}} true))))
  (testing "absent :error count leaves historical semantics untouched"
    (is (= 0 (#'runner/compute-exit-code {:counts {:passed 3}} false)))
    (is (= 1 (#'runner/compute-exit-code {:counts {:failed 1}} false)))))
