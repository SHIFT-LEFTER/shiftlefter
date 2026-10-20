(ns shiftlefter.runner.hold-family-test
  "sl-190x — the hold family (@wip, @skip=reason, @blocked=REF) driven
   through `runner/execute!` on a real temp project, every surface:

   - @wip / @skip never run and ride the ONE :held record (EDN, the
     verdict-line breakdown, JUnit <skipped held: …>, the HTML island,
     the dry-run notice);
   - @blocked RUNS: a failure as claimed is HELD with the measured outcome
     (C2); a pass is XPASS = exit 1 with the imperative (J-a);
   - the cut is by WHO failed (J-e): an unobserved run is held, a harness
     :error is not;
   - --tags @wip lifts (Cucumber --wip); --tags @blocked selects every
     blocked scenario and keeps xfail (J-f, the bare-key rule);
     --skip-tags composes (exclude wins);
   - the bare-@skip lint on stderr and under :tag-lints (J-h);
   - every-scenario-held is the empty-selection exit 2 (J-g);
   - [:runner :ref-url] renders the link into the record, fetches nothing;
   - a project without hold tags is byte-identical on --edn (no :held, no
     :xpass, no :tag-lints) — the green control."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [shiftlefter.runner.core :as runner]
            [shiftlefter.stepengine.registry :as registry]))

(defn- clean-registry-fixture [f]
  (registry/clear-registry!)
  (f)
  (registry/clear-registry!))

(use-fixtures :each clean-registry-fixture)

;; -----------------------------------------------------------------------------
;; Fixtures — a typed step library with the three ways a step can go wrong
;; -----------------------------------------------------------------------------

(def ^:private typed-steps
  "(ns steps.holds
     (:require [shiftlefter.stepengine.registry :refer [defstep]]))
   (defstep #\"^:(\\S+) counts to (\\d+)$\"
     {:svo {:subject :$1 :verb :count :frame :to}}
     [ctx _subject n]
     (assoc ctx :n (parse-long n)))
   (defstep #\"^:(\\S+) should have counted (\\d+)$\"
     {:svo {:subject :$1 :verb :check :frame :count}}
     [ctx _subject n]
     (when-not (= (parse-long n) (:n ctx))
       (throw (ex-info \"count mismatch\" {:error/class :assertion})))
     ctx)
   (defstep #\"^:(\\S+) observes darkness$\"
     {:svo {:subject :$1 :verb :observe :frame :darkness}}
     [ctx _subject]
     (throw (ex-info \"connection refused\" {:error/class :observation})))")

(def ^:private subjects-glossary
  "{:subjects {:user {:desc \"the counter\"}}}")

(def ^:private hold-feature
  "Feature: Holds
  Scenario: Green
    Given :user counts to 1
    Then :user should have counted 1

  @wip
  Scenario: Draft
    Given :user counts to 1

  @skip=flaky-on-ci
  Scenario: Flaky
    Given :user counts to 1

  @skip
  Scenario: Bare skip
    Given :user counts to 1

  @blocked=PROJ-1
  Scenario: Tracked bug
    Given :user counts to 1
    Then :user should have counted 2

  @blocked=PROJ-2
  Scenario: Tracked outage
    Given :user observes darkness
")

(def ^:private xpass-feature
  "Feature: Fixed
  @blocked=PROJ-3
  Scenario: Fixed already
    Given :user counts to 1
    Then :user should have counted 1
")

(def ^:private green-feature
  "Feature: Clean
  Scenario: One
    Given :user counts to 1
    Then :user should have counted 1
")

(defn- with-project
  [{:keys [features config]} f]
  (let [dir (str (fs/create-temp-dir {:prefix "sl-holds-"}))
        sl-dir (fs/path dir "sl")]
    (try
      (fs/create-dirs (fs/path sl-dir "steps"))
      (fs/create-dirs (fs/path sl-dir "features"))
      (fs/create-dirs (fs/path sl-dir "glossary"))
      (doseq [[name text] features]
        (spit (str (fs/path sl-dir "features" name)) text))
      (spit (str (fs/path sl-dir "steps" "holds.clj")) typed-steps)
      (spit (str (fs/path sl-dir "glossary" "subjects.edn")) subjects-glossary)
      (spit (str (fs/path sl-dir "shiftlefter.edn")) (pr-str (or config {})))
      (f {:dir dir
          :config-path (str (fs/path sl-dir "shiftlefter.edn"))
          :steps (str (fs/path sl-dir "steps"))
          :features (str (fs/path sl-dir "features"))})
      (finally
        (fs/delete-tree dir)))))

(defn- execute
  [{:keys [config-path steps features]} opts]
  (let [out (java.io.StringWriter.)
        err (java.io.StringWriter.)
        result (binding [*out* out *err* err]
                 (runner/execute! (merge {:paths [features]
                                          :config-path config-path
                                          :step-paths [steps]
                                          :no-color true}
                                         opts)))]
    {:result result
     :summary (when (:edn opts) (edn/read-string (str out)))
     :err (str err)}))

(defn- held-by-name [summary]
  (into {} (map (juxt :scenario identity)) (:held summary)))

;; -----------------------------------------------------------------------------
;; The live run — every surface
;; -----------------------------------------------------------------------------

(deftest holds-are-counted-and-visible-on-every-surface
  (with-project {:features {"holds.feature" hold-feature}
                 :config {:runner {:ref-url "https://tracker.example/browse/{ref}"}}}
    (fn [{:keys [dir] :as p}]
      (let [junit (str (fs/path dir "results.xml"))
            html (str (fs/path dir "report.html"))
            {:keys [result summary err]} (execute p {:edn true :junit-xml junit :html html})
            held (held-by-name summary)]
        (testing "the verdict: exit 0 — one green, five held, nothing failed"
          (is (= 0 (:exit-code result)))
          (is (= :passed (:run/status summary)))
          (is (= {:passed 1 :failed 0 :pending 0 :skipped 0 :held 5 :scenarios 3 :steps 5}
                 (:counts summary))
              "held is carved out of every verdict bucket; :scenarios counts the three that bound; :xpass absent"))
        (testing "the ONE :held record: planning holds first, then the engine's, each with its facts"
          (is (= ["Draft" "Flaky" "Bare skip" "Tracked bug" "Tracked outage"]
                 (mapv :scenario (:held summary))))
          (is (= {:reason :wip :tag "@wip"} (select-keys (held "Draft") [:reason :tag :note])))
          (is (= {:reason :skip :tag "@skip=flaky-on-ci" :note "flaky-on-ci"}
                 (select-keys (held "Flaky") [:reason :tag :note])))
          (is (= {:reason :skip :tag "@skip"} (select-keys (held "Bare skip") [:reason :tag :note])))
          (is (= {:reason :blocked :tag "@blocked=PROJ-1" :ref "PROJ-1"
                  :ref-url "https://tracker.example/browse/PROJ-1"
                  :outcome {:status :failed :at-step 2 :message "count mismatch"}}
                 (select-keys (held "Tracked bug") [:reason :tag :ref :ref-url :outcome]))
              "C2: the measured outcome rides the record; the link is rendered, never fetched")
          (is (= {:status :unobserved :at-step 1 :message "connection refused"}
                 (:outcome (held "Tracked outage")))
              "J-e: an unobserved run is held — the endpoint that never answers IS the tracked bug")
          (is (every? #(and (:feature %) (:uri %) (:line %)) (:held summary))
              "every entry carries the 6wh9 locator keys"))
        (testing "the tag lint rides the EDN diagnostics; no failures entry for a held scenario"
          (is (= [:tag/reason-required] (mapv :type (get-in summary [:diagnostics :tag-lints]))))
          (is (not (contains? summary :failures))))
        (testing "--edn keeps stderr quiet about the lint"
          (is (not (str/includes? err "Tag warning:"))))
        (testing "JUnit: held: messages per kind, XPASS-free"
          (let [xml (slurp junit)]
            (is (str/includes? xml "<skipped message=\"held: @wip\""))
            (is (str/includes? xml "<skipped message=\"held: @skip=flaky-on-ci\""))
            (is (str/includes? xml "<skipped message=\"held: @skip\""))
            (is (str/includes? xml "<skipped message=\"held: blocked by PROJ-1 — failed at step 2: count mismatch\""))
            (is (str/includes? xml "<skipped message=\"held: blocked by PROJ-2 — unobserved at step 1: connection refused\""))
            (is (str/includes? xml "tests=\"6\""))
            (is (str/includes? xml "skipped=\"5\""))
            (is (str/includes? xml "failures=\"0\""))))
        (testing "HTML: the island carries the records and the link"
          (let [page (slurp html)]
            (is (str/includes? page ":ref-url \"https://tracker.example/browse/PROJ-1\""))
            (is (str/includes? page ":outcome {:status :unobserved, :at-step 1, :message \"connection refused\"}"))
            (is (str/includes? page ":reason :wip, :tag \"@wip\"")))))
      (testing "the console: HELD rows with the measured outcome, the breakdown, the lint"
        (let [{:keys [result err]} (execute p {})]
          (is (= 0 (:exit-code result)))
          (is (str/includes? err "HELD (blocked by PROJ-1 — failed at step 2: count mismatch) Tracked bug"))
          (is (str/includes? err "HELD (blocked by PROJ-2 — unobserved at step 1: connection refused) Tracked outage"))
          (is (str/includes? err "6 scenario(s): 1 passed, 5 held (1 wip, 2 skip, 2 blocked)"))
          (is (str/includes? err "Tag warning: @skip without a reason — reason-free holds rot; write @skip=<why> (scenario \"Bare skip\""))
          (is (not (str/includes? err "Failures:")) "a held scenario is not a failure of this run"))))))

(deftest xpass-is-exit-1-with-the-imperative
  (with-project {:features {"fixed.feature" xpass-feature}}
    (fn [{:keys [dir] :as p}]
      (let [junit (str (fs/path dir "results.xml"))
            {:keys [result summary]} (execute p {:edn true :junit-xml junit})]
        (is (= 1 (:exit-code result)) "J-a: XPASS = exit 1, a stale claim is a wrong test")
        (is (= :failed (:run/status summary)))
        (is (= {:passed 0 :failed 1 :pending 0 :skipped 0 :xpass 1 :scenarios 1 :steps 2}
               (:counts summary)))
        (is (not (contains? summary :held)) "an XPASS is not a hold")
        (is (= [{:scenario/name "Fixed already" :type :xpass :tag "@blocked=PROJ-3" :ref "PROJ-3"
                 :message "blocked scenario passed: remove @blocked=PROJ-3 or reopen PROJ-3"}]
               (mapv #(dissoc % :scenario/id) (:failures summary))))
        (is (str/includes? (slurp junit)
                           "<failure type=\"xpass\" message=\"blocked scenario passed: remove @blocked=PROJ-3 or reopen PROJ-3\"")))
      (let [{:keys [result err]} (execute p {})]
        (is (= 1 (:exit-code result)))
        (is (str/includes? err "FAILED (blocked scenario passed) Fixed already"))
        (is (str/includes? err "1 scenario(s): 1 failed (1 XPASS)"))
        (is (str/includes? err "Scenario: Fixed already\n  blocked scenario passed: remove @blocked=PROJ-3 or reopen PROJ-3"))))))

;; -----------------------------------------------------------------------------
;; Selection: lifts, the bare-key rule, composition
;; -----------------------------------------------------------------------------

(deftest tags-wip-lifts-the-hold
  (with-project {:features {"holds.feature" hold-feature}}
    (fn [p]
      (let [{:keys [result summary err]} (execute p {:edn true :tag-filter {:include #{"@wip"}}})]
        (is (= 0 (:exit-code result)))
        (is (= 1 (get-in summary [:counts :passed])) "the wip scenario RAN")
        (is (not (contains? summary :held)) "named in --tags, it is no longer held; the rest are filtered out")
        (is (not (str/includes? err "held")))))))

(deftest tags-blocked-selects-every-blocked-scenario-and-keeps-xfail
  (with-project {:features {"holds.feature" hold-feature "fixed.feature" xpass-feature}}
    (fn [p]
      (let [{:keys [result summary]} (execute p {:edn true :tag-filter {:include #{"@blocked"}}})]
        (is (= 1 (:exit-code result)) "the XPASS among them still fails the run")
        (is (= 3 (get-in summary [:counts :scenarios])) "bare key = all three @blocked=*")
        (is (= 2 (get-in summary [:counts :held])) "the two that fail as claimed stay held")
        (is (= 1 (get-in summary [:counts :xpass])))))))

(deftest skip-tags-composes-exclude-wins
  (with-project {:features {"holds.feature" hold-feature}}
    (fn [p]
      (let [{:keys [result summary err]} (execute p {:edn true :tag-filter {:exclude #{"@skip" "@blocked"}}})]
        (is (= 0 (:exit-code result)))
        (is (= ["Draft"] (mapv :scenario (:held summary)))
            "--skip-tags @skip excludes the skips outright (filtered, not held); @wip is still a hold")
        (is (= {:passed 1 :failed 0 :pending 0 :skipped 0 :held 1 :scenarios 1 :steps 2}
               (:counts summary)))
        (is (not (str/includes? err "Tag filter:")) "--edn suppresses the notice")))))

(deftest every-scenario-held-is-the-empty-selection-exit-2
  (with-project {:features {"only-holds.feature"
                            "Feature: Drafts\n  @wip\n  Scenario: A\n    Given :user counts to 1\n  @skip=later\n  Scenario: B\n    Given :user counts to 1\n"}}
    (fn [p]
      (let [{:keys [result err]} (execute p {})]
        (is (= 2 (:exit-code result)))
        (is (= :selection/empty (-> result :error :type)))
        (is (= 2 (-> result :error :data :held)))
        (is (str/includes? err "Empty selection: 0 of 2 scenario(s) selected (searched: "))
        (is (str/includes? err ") (2 held by @wip/@skip tags) — nothing would run, so nothing can pass"))))))

;; -----------------------------------------------------------------------------
;; Dry run and the green control
;; -----------------------------------------------------------------------------

(deftest dry-run-previews-the-holds
  (with-project {:features {"holds.feature" hold-feature}}
    (fn [p]
      (let [{:keys [result summary]} (execute p {:dry-run true :edn true})]
        (is (= 0 (:exit-code result)))
        (is (= :dry-run (:run/status summary)))
        (is (= {:scenarios 3 :steps 5 :held 3} (:counts summary))
            "the three never-run holds; @blocked binds like any scenario")
        (is (= ["Draft" "Flaky" "Bare skip"] (mapv :scenario (:held summary)))))
      (let [{:keys [err]} (execute p {:dry-run true})]
        (is (str/includes? err "3 scenario(s) bound successfully (dry run)"))
        (is (str/includes? err "3 scenario(s) held:"))
        (is (str/includes? err "  - Draft ("))
        (is (str/includes? err "; @wip)"))
        (is (str/includes? err "; @skip=flaky-on-ci)"))))))

(deftest a-project-without-hold-tags-is-byte-identical
  (with-project {:features {"clean.feature" green-feature}}
    (fn [p]
      (let [{:keys [result summary err]} (execute p {:edn true})]
        (is (= 0 (:exit-code result)))
        (is (= {:passed 1 :failed 0 :pending 0 :skipped 0 :scenarios 1 :steps 2} (:counts summary)))
        (is (not (contains? summary :held)))
        (is (not (contains? summary :diagnostics)))
        (is (re-matches #"run seed: \d+\n" err) "nothing on stderr but the seed line"))
      (let [{:keys [err]} (execute p {})]
        (is (not (str/includes? err "held")))
        (is (not (str/includes? err "Tag warning")))
        (is (str/includes? err "1 scenario(s): 1 passed"))))))

;; -----------------------------------------------------------------------------
;; sl-mk0t (Warden W7, Chair-ruled: lint the SELECTION)
;; -----------------------------------------------------------------------------

(deftest tag-lints-walk-the-selection-not-the-corpus
  (with-project {:features {"holds.feature" hold-feature
                            "fixed.feature" xpass-feature}}
    (fn [p]
      (testing "a --tags filter that deselects the bare-@skip scenario silences its lint on every surface"
        (let [{:keys [summary]} (execute p {:edn true :tag-filter {:include #{"@blocked"}}})]
          (is (= ["Tracked bug" "Tracked outage"] (mapv :scenario (:held summary)))
              "(sanity) the @blocked scenarios were selected; Bare skip was filtered out, not held")
          (is (not (contains? (:diagnostics summary) :tag-lints))
              "the deselected scenario's lint no longer rides :tag-lints"))
        (let [{:keys [err]} (execute p {:tag-filter {:include #{"@blocked"}}})]
          (is (not (str/includes? err "Tag warning:"))
              "and nothing prints on stderr about a scenario this run never selected")))
      (testing "the control: without a filter the held bare-@skip scenario is in the selection and still lints"
        (let [{:keys [summary]} (execute p {:edn true})]
          (is (= [:tag/reason-required] (mapv :type (get-in summary [:diagnostics :tag-lints])))))))))
