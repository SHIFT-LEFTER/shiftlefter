(ns shiftlefter.runner.exit-tournament-test
  "The tournament of codes (sl-xwwv): ONE multi-group setup.clj fixture in
   which every group earns a different exit code — 0, 2, 1, 4 in declared
   order, the exit-2 group deliberately SECOND — pinning the whole
   observable multi-group surface end-to-end:

   - no short-circuit: run-with-setups! maps every group unconditionally
     (runner/core.clj run-with-setups!), so groups after an exit-2 group
     still run — asserted from the :start probe log, not inferred;
   - per-group :exit-code values in the :groups result vector;
   - compute-suite-exit-code worst-wins aggregation (2 > 4 > 1 > 0) plus
     each drop-the-winner variant at the pure seam;
   - the ONE invocation JUnit file (sl-xjx0 shared state) written after
     all groups covers exactly the groups that ran scenarios — the exit-2
     group contributes nothing, yet the file exists beside suite exit 2
     (the all-green-beside-exit-2 shape, pinned as a test);
   - the HTML invocation TOC (sl-n9c6) rows carry every group WITH its
     verdict — including the planning-failed one: the TOC is the one
     human-readable place per-group outcomes are displayed;
   - --edn output shape across groups;
   - per-group streaming console output for every running group, including
     those after the 2;
   - CRASH: a Throwable escaping a group's body unwinds past
     write-invocation-report! — run-group! catches it as ::crashed, runs
     :stop, RE-THROWS; execute!'s tail catch is the in-process crash
     surface ({:exit-code 3 :status :crashed}, 'Runner crash:' on stderr).
     Injection = a throwing Reporter scoped to one marker scenario — the
     codebase's own pinned loud-crash contract (reporter_test
     a-throwing-reporter-fails-the-run-loudly; incremental_output_test
     single-release-point-drives-report-plane), wrap-not-replace so real
     reporters keep flowing for pre-crash groups. Groups after the crash
     never start (the crash aborts the invocation — pinned as current
     behavior). The report-file hazard beside exit 3, pinned in two
     shapes: the HTML TOC keeps a previous run's STALE bytes (its write
     is post-groups and never happens), while the shared-state JUnit file
     is SHARPER than stale — materialized at each completed group's end,
     it holds a partial, all-green, CURRENT run. Documented, not
     mitigated (mitigation would be src/ and is not this bead).

   Sequential-shaped on purpose: crash-path orphan behavior under
   :max-parallel is sl-617t's, not pinned here.

   Siblings (rows pinned there, not duplicated): verdict_contract_test
   (per-code rows incl. the redef exit-3 row), error_class_test (the
   :error/class contract behind the exit-4 group), core_setup_test
   (lifecycle/adapter branches), warm_e2e_test (^:daemon process-boundary
   parity — System/exit and the -main/-daemon catches), report_smoke_test
   (report rendering)."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [shiftlefter.runner.core :as rc]
            [shiftlefter.runner.reporter :as reporter]))

;; -----------------------------------------------------------------------------
;; Temp-project helpers (the verdict_contract_test idiom)
;; -----------------------------------------------------------------------------

(defn- with-temp-project [f]
  (let [dir (str (fs/create-temp-dir {:prefix "sl-exit-tournament-test-"}))]
    (try
      (f dir)
      (finally
        (fs/delete-tree dir)))))

(defn- spit-file [dir relative content]
  (let [target (fs/path dir relative)]
    (fs/create-dirs (fs/parent target))
    (spit (str target) content)
    (str target)))

(defn- run-captured
  "Run execute! capturing stdout+stderr; returns {:result :out :err}."
  [opts]
  (let [out (java.io.StringWriter.)
        err (java.io.StringWriter.)
        result (binding [*out* out *err* err]
                 (rc/execute! (merge {:no-color true} opts)))]
    {:result result :out (str out) :err (str err)}))

(defn- read-forms
  "All EDN forms on a stdout string, in order (core_setup_test idiom)."
  [out]
  (let [rdr (java.io.PushbackReader. (java.io.StringReader. out))]
    (loop [acc []]
      (let [form (edn/read {:eof ::eof} rdr)]
        (if (= ::eof form) acc (recur (conj acc form)))))))

;; :start probe log — the setup.clj under test requires this ns and records
;; here, so group lifecycle is asserted from data, not parsed from output.
(defonce tournament-log (atom []))

(defn ^:export record! [tag] (swap! tournament-log conj tag))

;; -----------------------------------------------------------------------------
;; The tournament fixture: four groups, four different codes, the 2 SECOND
;; -----------------------------------------------------------------------------

(def ^:private steps-src
  "(ns tournament-steps
     (:require [shiftlefter.stepengine.registry :refer [defstep]]))
   (defstep #\"the alpha step passes\" [] nil)
   (defstep #\"the charlie step fails\" []
     (throw (ex-info \"charlie designed failure\" {})))
   (defstep #\"the delta line goes dark\" []
     (throw (ex-info \"delta connection refused\" {:error/class :observation})))
   (defstep #\"the kaboom step passes\" [] nil)")

(def ^:private setup-src
  "(ns setup
     (:require [shiftlefter.runner.exit-tournament-test :as t]))
   (def setups
     [{:label \"alpha-pass\"
       :start (fn [_] (t/record! :alpha) {})
       :features [\"features/alpha.feature\"]}
      {:label \"bravo-dead\"
       :adapters [{:name :ghost :provides []}]
       :start (fn [_] (t/record! :bravo) {})
       :features [\"features/bravo.feature\"]}
      {:label \"charlie-fail\"
       :start (fn [_] (t/record! :charlie) {})
       :features [\"features/charlie.feature\"]}
      {:label \"delta-dark\"
       :start (fn [_] (t/record! :delta) {})
       :features [\"features/delta.feature\"]}])")

(defn- tournament-project!
  "Write the four-group tournament project; returns nothing useful — paths
   are group-owned (execute! is called with :paths [])."
  [dir]
  (spit-file dir "shiftlefter.edn" (pr-str {:runner {:step-paths []}}))
  (spit-file dir "steps/tournament.clj" steps-src)
  (spit-file dir "setup.clj" setup-src)
  (spit-file dir "features/alpha.feature"
             "Feature: Alpha\n  Scenario: Alpha passes\n    Given the alpha step passes\n")
  (spit-file dir "features/bravo.feature"
             "Feature: Bravo\n  Scenario: Bravo never runs\n    Given the alpha step passes\n")
  (spit-file dir "features/charlie.feature"
             "Feature: Charlie\n  Scenario: Charlie fails\n    Given the charlie step fails\n")
  (spit-file dir "features/delta.feature"
             "Feature: Delta\n  Scenario: Delta goes dark\n    Given the delta line goes dark\n"))

(defn- tournament-opts [dir]
  {:paths []
   :config-path (str (fs/path dir "shiftlefter.edn"))
   :step-paths [(str (fs/path dir "steps"))]})

;; -----------------------------------------------------------------------------
;; (a)(b)(g)(i) — every group runs, codes land, streams carry every group
;; -----------------------------------------------------------------------------

(deftest tournament-all-groups-run-and-codes-land
  (with-temp-project
    (fn [dir]
      (reset! tournament-log [])
      (tournament-project! dir)
      (let [junit-path (str (fs/path dir "invocation-junit.xml"))
            html-path (str (fs/path dir "invocation-report.html"))
            {:keys [result out err]} (run-captured
                                      (assoc (tournament-opts dir)
                                             :junit-xml junit-path
                                             :html html-path))
            console (str out err)]
        (testing "(a) no short-circuit: groups after the exit-2 group still run"
          (is (= [:alpha :bravo :charlie :delta] @tournament-log)
              ":start fired for every group in declared order — bravo's
               exit 2 stopped nothing (run-with-setups! maps every group
               unconditionally)"))
        (testing "(b) per-group :exit-code values in the :groups vector"
          (is (= ["alpha-pass" "bravo-dead" "charlie-fail" "delta-dark"]
                 (mapv :group-label (:groups result))))
          (is (= [0 2 1 4] (mapv :exit-code (:groups result)))))
        (testing "suite aggregate: worst-wins says 2"
          (is (= 2 (:exit-code result)))
          (is (= :planning-failed (:status result))))
        (testing "(g) per-group streaming output present for every running
                  group, including the two AFTER the exit-2 group"
          (is (str/includes? console "Alpha passes"))
          (is (str/includes? console "Charlie fails"))
          (is (str/includes? console "Delta goes dark"))
          (is (str/includes? err "Setup group \"bravo-dead\"")
              "the dead group's planning error reaches stderr"))
        (testing "(i) the per-group console verdict line (sl-kvn6, built as
                  the sl-pwbp rider — pin MOVED with the ruled change, see
                  the sl-pwbp DISPATCH comment 2026-08-31 16:12; this block
                  previously pinned the display gap): one epilogue line per
                  group, exit-2 group included"
          (is (str/includes? err "Setup group \"alpha-pass\": exit code 0 (passed)"))
          (is (str/includes? err "Setup group \"bravo-dead\": exit code 2 (planning-failed)")
              "the planning-failed group gets a verdict line too — the loop
               seam sees groups that never reach a reporter")
          (is (str/includes? err "Setup group \"charlie-fail\": exit code 1 (failed)"))
          (is (str/includes? err "Setup group \"delta-dark\": exit code 4 (degraded)")))))))

;; -----------------------------------------------------------------------------
;; (c) — aggregation at the pure seam: worst-wins ladder
;; -----------------------------------------------------------------------------

(deftest suite-aggregation-worst-wins
  (let [aggregate @#'rc/compute-suite-exit-code]
    (testing "the tournament's own multiset and each drop-the-winner variant"
      (is (= 2 (aggregate [0 2 1 4])) "2 beats everything (pre-execution exclusive)")
      (is (= 4 (aggregate [0 1 4])) "drop the 2: 4 beats 1 and 0")
      (is (= 1 (aggregate [0 1])) "drop the 4: 1 beats 0")
      (is (= 0 (aggregate [0])) "a lone pass is a pass"))
    (testing "order independence (worst-wins is a fold, not a race)"
      (is (= 2 (aggregate [4 1 2 0])))
      (is (= 4 (aggregate [4 0 1]))))))

;; -----------------------------------------------------------------------------
;; (d) — the ONE invocation JUnit file: exists beside exit 2, covers exactly
;;       the groups that ran scenarios
;; -----------------------------------------------------------------------------

(deftest invocation-junit-covers-exactly-the-groups-that-ran
  (with-temp-project
    (fn [dir]
      (reset! tournament-log [])
      (tournament-project! dir)
      (let [junit-path (str (fs/path dir "invocation-junit.xml"))
            {:keys [result]} (run-captured (assoc (tournament-opts dir)
                                                  :junit-xml junit-path))]
        (is (= 2 (:exit-code result)))
        (testing "the file EXISTS beside a suite exit 2 (sl-xjx0: one
                  invocation file, written after all groups)"
          (is (fs/exists? junit-path)))
        (let [xml (slurp junit-path)]
          (testing "covers exactly the scenarios of the groups that ran"
            (is (str/includes? xml "Alpha passes"))
            (is (str/includes? xml "Charlie fails"))
            (is (str/includes? xml "Delta goes dark"))
            (is (not (str/includes? xml "Bravo never runs"))
                "the exit-2 group contributes nothing — a JUnit consumer
                 alone cannot see bravo's verdict (the all-green-beside-
                 exit-2 shape; the suite exit code is the honest signal)"))
          (testing "red-iff-nonzero: the file is red beside the nonzero
                    suite — pinned mapping: a step EXCEPTION renders as
                    <error> (an assert-style <failure> is not this
                    fixture's shape), and the unobserved class rides the
                    message marker"
            (is (str/includes? xml "errors=\"2\"") "charlie + delta both red")
            (is (str/includes? xml "failures=\"0\""))
            (is (str/includes? xml "<error type=\"clojure.lang.ExceptionInfo\"")
                "charlie's step exception renders as <error>")
            (is (str/includes? xml "[unobserved] delta connection refused")
                "delta's :error/class :observation is visible in the message")))))))

;; -----------------------------------------------------------------------------
;; (e) — the HTML invocation TOC: every group row, verdicts included
;; -----------------------------------------------------------------------------

(deftest invocation-html-toc-rows
  (with-temp-project
    (fn [dir]
      (reset! tournament-log [])
      (tournament-project! dir)
      (let [html-path (str (fs/path dir "invocation-report.html"))
            {:keys [result]} (run-captured (assoc (tournament-opts dir)
                                                  :html html-path))]
        (is (= 2 (:exit-code result)))
        (is (fs/exists? html-path) "the stable --html path is the invocation TOC (sl-n9c6)")
        (let [html (slurp html-path)]
          (testing "rows carry all four groups — the planning-failed one included"
            (doseq [label ["alpha-pass" "bravo-dead" "charlie-fail" "delta-dark"]]
              (is (str/includes? html label) (str label " row present"))))
          (testing "per-group verdicts render here — the one human-readable
                    surface that shows them (item i's finding)"
            (doseq [status ["passed" "failed" "planning-failed" "degraded"]]
              (is (str/includes? html status) (str status " verdict present")))))))))

;; -----------------------------------------------------------------------------
;; (f) — --edn shape across groups
;; -----------------------------------------------------------------------------

(deftest edn-shape-across-groups
  (with-temp-project
    (fn [dir]
      (reset! tournament-log [])
      (tournament-project! dir)
      (let [{:keys [result out]} (run-captured (assoc (tournament-opts dir)
                                                      :edn true))
            forms (read-forms out)
            group-forms (filterv :run/exit-code forms)]
        (is (= 2 (:exit-code result)))
        (testing "one EDN summary per group in DECLARED order, plus the ONE
                  invocation aggregate form LAST (sl-pwbp — this block
                  previously pinned the identity-free 4-form shape; the pin
                  moved with the ruled change, see the sl-pwbp DISPATCH
                  comment 2026-08-31 16:12)"
          (is (= 5 (count forms)) "four group forms + one aggregate form")
          (is (= [0 2 1 4] (mapv :run/exit-code group-forms)))
          (is (= [:passed :planning-failed :failed :degraded]
                 (mapv :run/status group-forms))))
        (testing "group identity on EVERY per-group summary form — order is
                  no longer the only join key (sl-pwbp)"
          (is (= ["alpha-pass" "bravo-dead" "charlie-fail" "delta-dark"]
                 (mapv :group/label group-forms)))
          (is (= [0 1 2 3] (mapv :group/index group-forms))))
        (testing "the dead group's planning error is a full EDN form — EDN
                  mode never aggregates an exit-2 silently (sl-1h03) — and
                  the error carries the group label + type"
          (let [bravo (nth forms 1)
                err (first (get-in bravo [:planning :errors]))]
            (is (= :setup/registry-mismatch (:type err)))
            (is (= "bravo-dead" (:group err)))))
        (testing "the kowi cross-pin: the dark group's counts confess
                  :unobserved inside a multi-group aggregate"
          (is (= 1 (get-in (nth forms 3) [:counts :unobserved]))))
        (testing "the invocation aggregate form (sl-pwbp, replacing the
                  PINNED GAP block — the suite verdict is now readable from
                  the stream): last form, :invocation/* keys, rows echoing
                  the per-group summary vocabulary; deliberately NOT
                  :run/exit-code, so the documented summary discriminator
                  (docs/REPORTS.md) skips it"
          (let [agg (last forms)]
            (is (nil? (:run/exit-code agg))
                "a consumer filtering summaries by :run/exit-code never
                 picks up the aggregate")
            (is (= 2 (:invocation/exit-code agg)) "worst-wins verdict")
            (is (= :planning-failed (:invocation/status agg)))
            (is (= (:run/id (first forms)) (:run/id agg))
                "same invocation run-id as every group form")
            (is (pos-int? (:run/seed agg)) "core-record family: seed rides")
            (testing "sl-alha (Warden W32): the invocation run dir, config-root-
                      relative, the parent of every group's :run/results-path"
              (let [inv (:invocation/results-path agg)]
                (is (string? inv))
                (is (str/starts-with? inv "results/"))
                (is (every? #(str/starts-with? (:run/results-path %) (str inv "/"))
                            (filter :run/results-path group-forms))
                    "group dirs sit under it")))
            (is (= [{:group/label "alpha-pass" :group/index 0
                     :run/exit-code 0 :run/status :passed}
                    {:group/label "bravo-dead" :group/index 1
                     :run/exit-code 2 :run/status :planning-failed}
                    {:group/label "charlie-fail" :group/index 2
                     :run/exit-code 1 :run/status :failed}
                    {:group/label "delta-dark" :group/index 3
                     :run/exit-code 4 :run/status :degraded}]
                   (:invocation/groups agg)))))))))

;; -----------------------------------------------------------------------------
;; :group/index is the DECLARED position — an index-name that survives CLI
;; narrowing, gaps and all (sl-baup, Warden W5)
;; -----------------------------------------------------------------------------

(defn- feature-path [dir name] (str (fs/path dir "features" (str name ".feature"))))

(deftest group-index-is-the-declared-position-under-narrowing
  (with-temp-project
    (fn [dir]
      (reset! tournament-log [])
      (tournament-project! dir)
      (testing "narrowed to bravo + charlie: indexes 1 and 2 on the group forms
                AND the aggregate rows — before the fix 0 and 1, so two
                invocations with different narrowing joined different groups"
        (let [{:keys [result out]} (run-captured (assoc (tournament-opts dir)
                                                        :paths [(feature-path dir "bravo")
                                                                (feature-path dir "charlie")]
                                                        :edn true))
              forms (read-forms out)
              group-forms (filterv :run/exit-code forms)
              agg (last forms)]
          (is (= 2 (:exit-code result)))
          (is (= [[ "bravo-dead" 1] ["charlie-fail" 2]]
                 (mapv (juxt :group/label :group/index) group-forms)))
          (is (= [1 2] (mapv :group/index (:invocation/groups agg))))
          (is (= [1 2] (mapv :group-index (:groups result))) "the in-process results too")))
      (testing "narrowed to charlie alone: STILL index 2 (the project declares
                four groups; identity keys on the declaration, not on how
                many survived — one row, one aggregate)"
        (let [{:keys [out]} (run-captured (assoc (tournament-opts dir)
                                                 :paths [(feature-path dir "charlie")]
                                                 :edn true))
              forms (read-forms out)]
          (is (= 2 (count forms)) "one group form + the aggregate")
          (is (= 2 (:group/index (first forms))))
          (is (= "charlie-fail" (:group/label (first forms))))
          (is (= [2] (mapv :group/index (:invocation/groups (last forms)))))))
      (testing "unnarrowed: unchanged, 0..3"
        (let [{:keys [out]} (run-captured (assoc (tournament-opts dir) :edn true))
              group-forms (filterv :run/exit-code (read-forms out))]
          (is (= [0 1 2 3] (mapv :group/index group-forms)))))
      (testing "dry-run narrowed to alpha + delta: the same declared indexes,
                with the gap"
        (let [{:keys [out]} (run-captured (assoc (tournament-opts dir)
                                                 :paths [(feature-path dir "alpha")
                                                         (feature-path dir "delta")]
                                                 :edn true :dry-run true))
              forms (read-forms out)
              group-forms (filterv :run/exit-code forms)]
          (is (= [0 3] (mapv :group/index group-forms)))
          (is (= [0 3] (mapv :group/index (:invocation/groups (last forms)))))))
      (testing "the HTML invocation TOC island carries the declared index per
                row, in declared order"
        (let [html-path (str (fs/path dir "narrowed-toc.html"))
              _ (run-captured (assoc (tournament-opts dir)
                                     :paths [(feature-path dir "bravo")
                                             (feature-path dir "delta")]
                                     :html html-path))
              html (slurp html-path)]
          (is (str/includes? html "bravo-dead"))
          (is (str/includes? html "delta-dark"))
          (is (< (str/index-of html "bravo-dead") (str/index-of html "delta-dark")))
          (is (re-find #"bravo-dead[^}]*?:index 1|:index 1[^}]*?bravo-dead" html)
              "bravo's row carries declared index 1")
          (is (re-find #"delta-dark[^}]*?:index 3|:index 3[^}]*?delta-dark" html)
              "delta's row carries declared index 3"))))))

;; -----------------------------------------------------------------------------
;; Multi-group DRY-RUN (sl-a7sx — Warden W6 + W23): the aggregate says
;; :dry-run, the config-declared report is noticed exactly once, never written
;; -----------------------------------------------------------------------------

(defn- notice-lines [err]
  (filterv #(str/starts-with? % "dry run: report(s) not written")
           (str/split-lines err)))

(deftest multi-group-dry-run-tells-the-truth
  (with-temp-project
    (fn [dir]
      (reset! tournament-log [])
      (tournament-project! dir)
      ;; The scaffold's config mirror: a report declared in CONFIG, which
      ;; F8's flag-only refusal never saw.
      (spit-file dir "shiftlefter.edn"
                 (pr-str {:runner {:step-paths []
                                   :report {:html "report.html"}}}))
      (let [html (fs/path dir "report.html")]
        (testing "--edn: every group form is :dry-run and carries
                  :run/reports-not-written; the aggregate is EMITTED with
                  :invocation/status :dry-run and exit 0 (W6 — was
                  :passed, a verdict the run never earned); nothing ran"
          (let [{:keys [result out]} (run-captured (assoc (tournament-opts dir)
                                                          :edn true :dry-run true))
                forms (read-forms out)
                group-forms (filterv :run/exit-code forms)
                agg (last forms)]
            (is (= 0 (:exit-code result)))
            (is (= :dry-run (:status result)) "in-process status, not :passed")
            (is (= [] @tournament-log) "no :start ran")
            (is (= 5 (count forms)) "four group forms + one aggregate")
            (is (= [:dry-run :dry-run :dry-run :dry-run]
                   (mapv :run/status group-forms)))
            (is (every? #(= [:html] (:run/reports-not-written %)) group-forms))
            (is (nil? (:run/exit-code agg)))
            (is (= 0 (:invocation/exit-code agg)))
            (is (= :dry-run (:invocation/status agg)))
            (is (= [:dry-run :dry-run :dry-run :dry-run]
                   (mapv :run/status (:invocation/groups agg))))
            (is (not (contains? agg :invocation/results-path))
                "no results dir under dry-run → the key is absent (sl-alha)")
            (is (not (fs/exists? html)))))
        (testing "console: the notice prints EXACTLY once per invocation
                  (after the four per-group dry-run lines), and no file"
          (let [{:keys [result err]} (run-captured (assoc (tournament-opts dir)
                                                          :dry-run true))
                notices (notice-lines err)]
            (is (= 0 (:exit-code result)))
            (is (= 4 (count (re-seq #"bound successfully \(dry run\)" err))))
            (is (= 1 (count notices)))
            (is (str/includes? (first notices)
                               (str "html " (fs/canonicalize html))))
            (is (not (fs/exists? html)))))))))

;; -----------------------------------------------------------------------------
;; (h) — CRASH: unwinds past the invocation report; stale files survive
;; -----------------------------------------------------------------------------

(def ^:private crash-steps-src
  "(ns crash-steps
     (:require [shiftlefter.stepengine.registry :refer [defstep]]))
   (defstep #\"the echo step passes\" [] nil)
   (defstep #\"the kaboom step passes\" [] nil)
   (defstep #\"the foxtrot step passes\" [] nil)")

(def ^:private crash-setup-src
  "(ns setup
     (:require [shiftlefter.runner.exit-tournament-test :as t]))
   (def setups
     [{:label \"echo-pass\"
       :start (fn [_] (t/record! :echo) {})
       :features [\"features/echo.feature\"]}
      {:label \"kaboom\"
       :start (fn [_] (t/record! :kaboom)
                {:stop (fn [] (t/record! :kaboom-stop))})
       :features [\"features/kaboom.feature\"]}
      {:label \"foxtrot-never\"
       :start (fn [_] (t/record! :foxtrot) {})
       :features [\"features/foxtrot.feature\"]}])")

(defn- crash-project! [dir]
  (spit-file dir "shiftlefter.edn" (pr-str {:runner {:step-paths []}}))
  (spit-file dir "steps/crash.clj" crash-steps-src)
  (spit-file dir "setup.clj" crash-setup-src)
  (spit-file dir "features/echo.feature"
             "Feature: Echo\n  Scenario: Echo passes\n    Given the echo step passes\n")
  (spit-file dir "features/kaboom.feature"
             "Feature: Kaboom\n  Scenario: KABOOM marker scenario\n    Given the kaboom step passes\n")
  (spit-file dir "features/foxtrot.feature"
             "Feature: Foxtrot\n  Scenario: Foxtrot passes\n    Given the foxtrot step passes\n"))

(defn- kaboom-probe
  "A Reporter whose on-scenario-complete throws only for the marker
   scenario — every value handed to a reporter is pure EDN data (the
   reporter contract), so pr-str matching is shape-agnostic."
  []
  (reify reporter/Reporter
    (on-run-start [_ _] nil)
    (on-scenario-complete [_ scenario-result]
      (when (str/includes? (pr-str scenario-result) "KABOOM")
        (throw (ex-info "tournament kaboom: deliberate reporter crash" {})))
      nil)
    (on-diagnostics [_ _] nil)
    (on-run-end [_ _] nil)))

(deftest crash-unwinds-past-the-invocation-report
  (with-temp-project
    (fn [dir]
      (reset! tournament-log [])
      (crash-project! dir)
      (let [junit-path (str (fs/path dir "invocation-junit.xml"))
            html-path (str (fs/path dir "invocation-report.html"))
            stale "STALE SENTINEL — a previous run's report\n"
            _ (spit junit-path stale)
            _ (spit html-path stale)
            orig @#'rc/make-reporters
            {:keys [result err]}
            (with-redefs [rc/make-reporters
                          (fn [& args] (conj (vec (apply orig args)) (kaboom-probe)))]
              (run-captured (assoc (tournament-opts dir)
                                   :junit-xml junit-path
                                   :html html-path)))]
        (testing "the crash surface: execute!'s tail catch, in-process"
          (is (= 3 (:exit-code result)))
          (is (= :crashed (:status result)))
          (is (= :runner/crash (get-in result [:error :type])))
          (is (str/includes? err "Runner crash:")
              "the crash line reaches stderr (stacktrace only under :verbose)"))
        (testing "the crash group's :stop still ran (run-group! ::crashed
                  runs :stop, then rethrows)"
          (is (= [:echo :kaboom :kaboom-stop] @tournament-log)
              "echo ran, kaboom started and stopped — and foxtrot NEVER
               started: the crash aborts the invocation (current behavior,
               pinned; parallel orphan timing is sl-617t's)"))
        (testing "the throw unwound PAST write-invocation-report!: the HTML
                  invocation TOC (post-groups write) was never written —
                  the stale sentinel survives beside exit 3"
          (is (= stale (slurp html-path))
              "an HTML consumer after a crash reads the PREVIOUS run"))
        (testing "the JUnit hazard is SHARPER than stale (pinned finding):
                  the shared-state file is materialized at each completed
                  group's end, so beside exit 3 it holds a PARTIAL,
                  ALL-GREEN, CURRENT run — the pre-crash group only, more
                  convincing than a stale file. Pair report files with the
                  exit code; mitigation is a src/ question, not this pin's"
          (let [xml (slurp junit-path)]
            (is (not= stale xml) "the sentinel was overwritten")
            (is (str/includes? xml "Echo passes") "the pre-crash group is in it")
            (is (str/includes? xml "tests=\"1\" failures=\"0\" errors=\"0\"")
                "and it reads green")
            (is (not (str/includes? xml "KABOOM"))
                "the crashing group's scenario never materialized")))))))

(deftest crash-under-edn-emits-no-invocation-aggregate
  ;; sl-6vz8 (Warden W22): the crash case above runs without :edn. Under
  ;; --edn the invocation aggregate (:invocation/exit-code, the suite
  ;; verdict form) is written AFTER the groups — the crash unwinds past that
  ;; write, so the aggregate must be ABSENT and a machine consumer reading
  ;; the stream sees no suite verdict. Pinned as current behavior (the
  ;; agent-doc exit-codes topic already says "absent on a crash"); the
  ;; stale-report hazard beside exit 3 is sl-8orx's.
  (with-temp-project
    (fn [dir]
      (reset! tournament-log [])
      (crash-project! dir)
      (let [orig @#'rc/make-reporters
            {:keys [result out]}
            (with-redefs [rc/make-reporters
                          (fn [& args] (conj (vec (apply orig args)) (kaboom-probe)))]
              (run-captured (assoc (tournament-opts dir) :edn true)))
            forms (read-forms out)]
        (is (= 3 (:exit-code result)) "precondition: the crash surface fired")
        (is (seq forms) "the pre-crash group's forms still reached stdout")
        (is (not-any? #(and (map? %) (contains? % :invocation/exit-code)) forms)
            (str "no :invocation/exit-code form may follow a crash; forms:\n"
                 (pr-str forms)))
        (testing "observed shape, pinned: the stream ends with ONE crash
                  summary — #:run{:exit-code 3 :status :crashed} carrying the
                  :runner/crash error — after the pre-crash group's own
                  summary; that crash form is the last thing a consumer reads"
          (let [crash (last forms)]
            (is (= 3 (:run/exit-code crash)))
            (is (= :crashed (:run/status crash)))
            (is (= :runner/crash (get-in crash [:error :type])))
            (is (= 1 (count (filter #(= :crashed (:run/status %)) forms)))
                "exactly one crash form")
            (is (some #(and (= 0 (:run/exit-code %)) (= "echo-pass" (:group/label %)))
                      forms)
                "the pre-crash group's green summary precedes it")))))))
