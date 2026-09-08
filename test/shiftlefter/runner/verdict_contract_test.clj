(ns shiftlefter.runner.verdict-contract-test
  "The verdict contract's semantics table, one deftest per row (sl-7c7i
   AC 1). The canonical table lives in shiftlefter.runner.verdict; this ns
   proves each row end-to-end where the row is runner-visible and at the
   compute seam where it is pure aggregation.

   Rows pinned in sibling namespaces (not duplicated here):
   - provisioning/harness membership in the scenario :error family:
     stepengine/exec_test.clj + exec_hooks_test.clj (the Phase-A pins)
   - capture-failure membership (D4: green scenario with failed promised
     evidence flips to :error, capture-attributed):
     stepengine/exec_capture_test.clj wired-d4-flips-green-only; its
     run-level exit-4 twin is exit-4-capture-throw-degrades-run BELOW
   - post-pending step-skip (steps after a :pending step are :skipped):
     stepengine/exec_test.clj test-scenario-pending-skips-remaining
   - dead group => exit 2, incl. dry-run: core_setup_test.clj
     dead-group-is-a-planning-error / dead-group-fails-dry-run-too
   - empty features dir => exit 2 (:discover/no-features): core_test.clj
     test-exit-2-no-features-in-path
   - JUnit red-iff-nonzero mirror: report/junit_test.clj
   - warm/cold process parity for 0/1/2/3/4: warm_e2e_test.clj (:daemon)
   - daemon wire spec = verdict/exit-codes: daemon_test.clj"
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [shiftlefter.core :as sl-core]
            [shiftlefter.runner.core :as core]
            [shiftlefter.runner.verdict :as verdict]))

;; -----------------------------------------------------------------------------
;; Temp-project helpers (the core_hooks_test idiom)
;; -----------------------------------------------------------------------------

(defn- with-temp-project [f]
  (let [dir (str (fs/create-temp-dir {:prefix "sl-verdict-contract-test-"}))]
    (try
      (f dir)
      (finally
        (fs/delete-tree dir)))))

(defn- spit-file [dir relative content]
  (let [target (fs/path dir relative)]
    (fs/create-dirs (fs/parent target))
    (spit (str target) content)
    (str target)))

(defn- run-quiet
  "Run execute! with output captured; returns the result map."
  [opts]
  (let [out (java.io.StringWriter.)
        err (java.io.StringWriter.)]
    (binding [*out* out *err* err]
      (core/execute! (merge {:no-color true} opts)))))

(def ^:private steps-src
  "(ns verdict-steps
     (:require [shiftlefter.stepengine.registry :refer [defstep]]))
   (defstep #\"a passing thing\" [] nil)
   (defstep #\"a failing thing\" [] (throw (ex-info \"designed failure\" {})))
   (defstep #\"a pending thing\" [] :pending)")

(defn- project!
  "Minimal project: config + steps + one feature. Returns the feature path.
   opts: :config (map, default {:runner {:step-paths []}}), :feature (text)."
  [dir & {:keys [config feature]
          :or {config {:runner {:step-paths []}}}}]
  (spit-file dir "shiftlefter.edn" (pr-str config))
  (spit-file dir "steps/verdict.clj" steps-src)
  (spit-file dir "features/f.feature" feature))

(defn- base-opts [dir fpath]
  {:paths [fpath]
   :config-path (str (fs/path dir "shiftlefter.edn"))
   :step-paths [(str (fs/path dir "steps"))]})

(def ^:private passing-feature
  "Feature: F\n  Scenario: Passes\n    Given a passing thing\n")

(def ^:private failing-feature
  "Feature: F\n  Scenario: Fails\n    Given a failing thing\n")

(def ^:private pending-feature
  "Feature: F\n  Scenario: Pends\n    Given a pending thing\n")

;; -----------------------------------------------------------------------------
;; Row: exit 0 — existential pass
;; -----------------------------------------------------------------------------

(deftest exit-0-existential-pass
  (testing ">=1 scenario selected, all ran to a tolerated outcome, none
            failed/errored => exit 0, :passed"
    (with-temp-project
      (fn [dir]
        (let [fpath (project! dir :feature passing-feature)
              result (run-quiet (base-opts dir fpath))]
          (is (= 0 (:exit-code result)))
          (is (= :passed (:status result)))
          (is (pos? (get-in result [:counts :passed]))
              "existential: the pass is witnessed by >=1 passed scenario"))))))

(deftest exit-0-all-pending-with-allow-pending-confesses
  (testing "the pinned edge (Chair 08-07): all-pending under
            :allow-pending? is exit 0 — the knob wins — and the counts
            confess 0 passed / N pending; JUnit renders <skipped>, never a
            bare success (skipped-never-success)"
    (with-temp-project
      (fn [dir]
        (let [fpath (project! dir
                              :config {:runner {:step-paths []
                                                :allow-pending? true}}
                              :feature pending-feature)
              junit-path (str (fs/path dir "junit.xml"))
              result (run-quiet (assoc (base-opts dir fpath)
                                       :junit-xml junit-path))
              xml (slurp junit-path)]
          (is (= 0 (:exit-code result)) "the knob wins")
          (is (= 0 (get-in result [:counts :passed])) "counts confess: 0 passed")
          (is (= 1 (get-in result [:counts :pending])) "counts confess: N pending")
          (is (str/includes? xml "<skipped") "pending renders <skipped>")
          (is (not (str/includes? xml "<failure")) "never a failure under the knob")
          (is (not (str/includes? xml "<error")) "never an error either"))))))

(deftest dry-run-exit-0-is-a-plan-verdict
  (testing "dry-run exit 0 claims THE PLAN BINDS (a different claim from
            run-exit-0, documented, not vacuity) — and requires a nonempty
            selection (see exit-2 dry-run row below)"
    (with-temp-project
      (fn [dir]
        (let [fpath (project! dir :feature passing-feature)
              result (run-quiet (assoc (base-opts dir fpath) :dry-run true))]
          (is (= 0 (:exit-code result)))
          (is (= :dry-run (:status result))
              "its status says plan-verdict, not :passed"))))))

;; -----------------------------------------------------------------------------
;; Row: exit 1 — at least one scenario FAILED (valid tests, app wrong)
;; -----------------------------------------------------------------------------

(deftest exit-1-scenario-failed
  (with-temp-project
    (fn [dir]
      (let [fpath (project! dir :feature failing-feature)
            result (run-quiet (base-opts dir fpath))]
        (is (= 1 (:exit-code result)))
        (is (= :failed (:status result)))))))

(deftest exit-1-pending-under-strict-mode
  (testing "pending without the knob is a red run (a pending step is an
            unfinished test, not a passing one)"
    (with-temp-project
      (fn [dir]
        (let [fpath (project! dir :feature pending-feature)
              result (run-quiet (base-opts dir fpath))]
          (is (= 1 (:exit-code result))))))))

;; -----------------------------------------------------------------------------
;; Row: exit 2 — invalid before execution (pre-execution exclusive)
;; -----------------------------------------------------------------------------

(deftest exit-2-empty-selection-zero-of-n-tags
  (testing "0-of-N tag narrowing is the vacuity witness: exit 2 with the
            selection story on the error (no escape knob; the escape is
            the data)"
    (with-temp-project
      (fn [dir]
        (let [fpath (project! dir :feature passing-feature)
              result (run-quiet (assoc (base-opts dir fpath)
                                       :tag-filter {:include #{"@nope"}}))]
          (is (= 2 (:exit-code result)))
          (is (= :planning-failed (:status result)))
          (is (= :selection/empty (-> result :error :type)))
          (is (= 0 (-> result :error :data :selected)))
          (is (= 1 (-> result :error :data :total)))
          (is (seq (-> result :error :data :searched))
              "the record says what was searched"))))))

(deftest exit-2-empty-selection-no-scenarios-in-file
  (testing "a feature file with no scenarios selects nothing => exit 2"
    (with-temp-project
      (fn [dir]
        (let [fpath (project! dir :feature "Feature: Empty\n")
              result (run-quiet (base-opts dir fpath))]
          (is (= 2 (:exit-code result)))
          (is (= :selection/empty (-> result :error :type))))))))

(deftest exit-2-empty-selection-in-dry-run-too
  (testing "Chair-ratified 08-09: the PLAN-verdict exit 0 requires a
            nonempty selection — an empty selection fails the dry-run the
            same way, so the dry-run-first CI wrapper sees the record"
    (with-temp-project
      (fn [dir]
        (let [fpath (project! dir :feature passing-feature)
              result (run-quiet (assoc (base-opts dir fpath)
                                       :dry-run true
                                       :tag-filter {:include #{"@nope"}}))]
          (is (= 2 (:exit-code result)))
          (is (= :selection/empty (-> result :error :type))))))))

(deftest exit-2-arg-parse-error
  (testing "a typo'd flag never executed anything — un-runnable invocation
            (Chair-ratified 08-09), same code as unknown command"
    (let [err (java.io.StringWriter.)]
      (is (= 2 (binding [*err* err] (sl-core/dispatch ["--bogus-flag"])))))))

(deftest exit-2-unknown-command
  (let [out (java.io.StringWriter.)
        err (java.io.StringWriter.)]
    (is (= 2 (binding [*out* out *err* err]
               (sl-core/dispatch ["no-such-command"]))))))

;; -----------------------------------------------------------------------------
;; Row: exit 2 across setup groups — the preflight (pre-execution proof)
;; -----------------------------------------------------------------------------

(defonce lifecycle-log (atom []))

(defn ^:export record! [tag] (swap! lifecycle-log conj tag))

(deftest exit-2-empty-selection-across-groups-runs-no-start
  (testing "a tag filter zeroing EVERY group is caught by the selection
            preflight BEFORE any :start runs — exit 2 stays pre-execution
            exclusive even under setup.clj orchestration; also the shape
            that used to aggregate [] into a silent green"
    (with-temp-project
      (fn [dir]
        (reset! lifecycle-log [])
        (spit-file dir "shiftlefter.edn" (pr-str {:runner {:step-paths []}}))
        (spit-file dir "features/a.feature" passing-feature)
        (spit-file dir "features/b.feature"
                   "Feature: G\n  Scenario: AlsoPasses\n    Given a passing thing\n")
        (spit-file dir "steps/verdict.clj" steps-src)
        (spit-file dir "setup.clj"
                   "(ns setup
                      (:require [shiftlefter.runner.verdict-contract-test :as t]))
                    (def setups
                      [{:label \"g-a\"
                        :start (fn [_] (t/record! :start-a) {})
                        :features [\"features/a.feature\"]}
                       {:label \"g-b\"
                        :start (fn [_] (t/record! :start-b) {})
                        :features [\"features/b.feature\"]}])")
        (let [result (run-quiet {:paths []
                                 :config-path (str (fs/path dir "shiftlefter.edn"))
                                 :step-paths [(str (fs/path dir "steps"))]
                                 :tag-filter {:include #{"@nope"}}})]
          (is (= 2 (:exit-code result)))
          (is (= :selection/empty (-> result :error :type)))
          (is (= ["g-a" "g-b"] (-> result :error :data :groups)))
          (is (= [] @lifecycle-log) "NO :start ran — genuinely pre-execution"))))))

;; -----------------------------------------------------------------------------
;; Row: exit 3 — runner crash (anywhere, wins over everything)
;; -----------------------------------------------------------------------------

(deftest exit-3-runner-crash
  (with-temp-project
    (fn [dir]
      (let [fpath (project! dir :feature passing-feature)]
        (with-redefs-fn {#'core/execute-and-report-stage
                         (fn [& _] (throw (ex-info "deliberate crash" {})))}
          (fn []
            (let [result (run-quiet (base-opts dir fpath))]
              (is (= 3 (:exit-code result)))
              (is (= :crashed (:status result)))
              (is (= :runner/crash (-> result :error :type))))))))))

;; -----------------------------------------------------------------------------
;; Row: exit 4 — run degraded (scenario :error family)
;; -----------------------------------------------------------------------------

(deftest exit-4-hook-throw-degrades-run
  (testing "a lifecycle hook throw is a broken harness, not a broken app:
            exit 4, :degraded — never 1, never 2"
    (with-temp-project
      (fn [dir]
        (spit-file dir "shiftlefter.edn" (pr-str {:runner {:step-paths []}}))
        (spit-file dir "steps/verdict.clj" steps-src)
        (spit-file dir "hooks.clj"
                   "(ns hooks)
                    (def hooks
                      [{:name \"boom\" :before (fn [_] (throw (ex-info \"db down\" {})))}])")
        (let [fpath (spit-file dir "features/f.feature"
                               "@hook=boom\nFeature: F\n  Scenario: Errors\n    Given a passing thing\n")
              result (run-quiet (base-opts dir fpath))]
          (is (= 4 (:exit-code result)))
          (is (= :degraded (:status result)))
          (is (= :error (-> result :result :scenarios first :status))))))))

(deftest exit-4-capture-throw-degrades-run
  (testing "a capture fn throw on an otherwise-GREEN scenario is missing
            promised evidence: exit 4, :degraded, capture-attributed —
            the run-level twin of exec_capture_test's
            wired-d4-flips-green-only (sl-d3cz, Warden W25)"
    (with-temp-project
      (fn [dir]
        ;; Shifted-mode project: provisioning keys off the bound step's
        ;; :svo :interface, so the step needs a subject + SVO metadata for
        ;; the mock capability to go live at all (a vanilla no-actor step
        ;; provisions nothing and capture has nothing to witness).
        (let [verbs (str (fs/absolutize
                          "test/fixtures/glossaries/verbs-web-project.edn"))]
          (spit-file dir "sl/glossary/subjects.edn"
                     (pr-str {:subjects {:user {:desc "u"
                                                :instances [{:id :alice}]}}}))
          (spit-file dir "sl/shiftlefter.edn"
                     (pr-str {:runner {:step-paths ["steps/"]
                                       :capture {:screenshot :every-step}}
                              :svo {}
                              :glossaries {:subjects "glossary/subjects.edn"
                                           :verbs [verbs]}
                              :interfaces {:web {:type :web
                                                 :adapter :mock-web}}})))
        (spit-file dir "sl/steps/steps.clj"
                   "(ns steps.capture-d4
                      (:require [shiftlefter.stepengine.registry :refer [defstep]]))
                    (defstep #\"(.+) clicks (.+)\"
                      {:interface :web
                       :svo {:subject :$1 :verb :swipe :frame :default :object :$2}}
                      [ctx _s _t]
                      ctx)")
        (spit-file dir "sl/setup.clj"
                   "(ns setup
                      (:require [shiftlefter.test-helpers.adapter-registry :as mock]))
                    (def setups
                      [{:label \"capture-d4\"
                        :adapters [{:name :mock-web :provides []
                                    :capture {:screenshot {}}}]
                        :start (fn [_config]
                                 {:adapter-registry
                                  (mock/registry
                                   {:mock-web
                                    {:capture
                                     {:screenshot
                                      {:fn (fn [_req]
                                             (throw (ex-info \"evidence lost\"
                                                             {:kind :boom})))}}}})})
                        :features [\"../features/f.feature\"]}])")
        (spit-file dir "features/f.feature"
                   "Feature: F\n\n  Scenario: Green\n    Given user/alice clicks the button\n")
        (let [result (run-quiet {:paths []
                                 :config-path
                                 (str (fs/path dir "sl" "shiftlefter.edn"))})
              scenario (-> result :groups first :result :scenarios first)]
          (is (= 4 (:exit-code result)))
          (is (= :degraded (:status result)))
          (is (= :error (:status scenario))
              "the green scenario flips to :error — green with missing evidence")
          (is (= :capture/failed (-> scenario :error :type))
              "capture-attributed, not step-attributed")
          (is (= :alice (-> scenario :error :data :subject))))))))

;; -----------------------------------------------------------------------------
;; Rows: precedence — execution worst-wins and suite aggregation
;; -----------------------------------------------------------------------------

(deftest execution-worst-wins-4-over-1-over-0
  (let [compute #'core/compute-exit-code]
    (is (= 4 (compute {:counts {:failed 2 :error 1}} false)) "4 beats 1")
    (is (= 4 (compute {:counts {:passed 1 :error 1}} false)) "4 beats 0")
    (is (= 1 (compute {:counts {:passed 1 :failed 1}} false)) "1 beats 0")
    (is (= 0 (compute {:counts {:passed 2}} false)))))

(deftest suite-aggregation-worst-wins-2-4-1-0
  (let [suite #'core/compute-suite-exit-code]
    (is (= 2 (suite [0 4 1 2])) "2 wins the suite (pre-execution exclusive)")
    (is (= 4 (suite [0 1 4])) "4 beats 1 across groups")
    (is (= 1 (suite [0 1 0])))
    (is (= 0 (suite [0 0])))))

(deftest the-canonical-table-is-total
  (testing "every exit code has exactly one status and vice versa"
    (is (= #{0 1 2 3 4} verdict/exit-codes))
    (is (= #{:passed :failed :planning-failed :crashed :degraded}
           verdict/run-statuses))
    (is (= (count verdict/exit-codes)
           (count verdict/run-statuses))
        "the projection is a bijection")))
