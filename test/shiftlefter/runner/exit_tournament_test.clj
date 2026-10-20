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
   - CRASH: a Throwable escaping a group's body unwinds past the normal
     epilogue — run-group! catches it as ::crashed, runs :stop, RE-THROWS;
     execute!'s tail catch is the in-process crash surface ({:exit-code 3
     :status :crashed}, 'Runner crash:' on stderr). Injection = the
     src-side crash probe (`:internal-crash-on` / `--sl-internal-crash=`,
     sl-8orx): a Reporter appended LAST that throws when one marker
     scenario's envelope completes — the codebase's own pinned loud-crash
     contract (reporter_test a-throwing-reporter-fails-the-run-loudly;
     incremental_output_test single-release-point-drives-report-plane),
     so real reporters have already seen every pre-crash scenario. Groups
     after the crash never start. The report files beside exit 3 (sl-8orx,
     the xwwv finding, mitigated): the invocation JUnit — shared state,
     materialized at each completed group's end, so it USED to hold a
     partial, all-green, CURRENT run — now carries the `sl-crash` suite
     with one error element of type crash (red iff nonzero holds); the stable
     --html path — whose post-groups TOC write never happened, so a
     previous run's STALE bytes survived — is now a CRASHED TOC over the
     groups that ran. A marker write that itself fails is one stderr
     line; exit 3 stands.

   The crash pins come in both shapes: sequential (the tournament fixture,
   whose :stop-still-runs and never-started-group assertions are
   sequential on purpose) and parallel (sl-8orx: a coordinator crash under
   :max-parallel 2 arms the halt gate and shutdownNow drains the queue —
   no queued scenario starts after the crash). Aborts (Ctrl-C, the daemon
   abort op) are sl-617t's, not pinned here.

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
            [shiftlefter.runner.report.html :as report-html]
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

;; sl-8orx parallel crash pin: the counting scenarios BLOCK on this latch
;; after recording :started, so "how many started" is measured without a
;; sleep race (Tower C2). A worker interrupted by shutdownNow leaves the
;; deref with InterruptedException — the step fails, the scenario ends.
(defonce pool-latch (atom (promise)))

(defn ^:export reset-pool-latch! [] (reset! pool-latch (promise)))

(defn ^:export await-pool! [] (deref @pool-latch 10000 :timeout))

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

(defn- crash-opts
  "The crash-project run: the src-side probe (sl-8orx) crashes the
   coordinator when the KABOOM marker scenario's envelope completes."
  [dir junit-path html-path]
  (assoc (tournament-opts dir)
         :internal-crash-on "KABOOM"
         :junit-xml junit-path
         :html html-path))

(defn- island-of
  "The HTML report's embedded EDN island as a string (the renderer's input)."
  [html]
  (let [[_ island] (re-find #"(?s)<script type=\"application/edn\" id=\"run-data\">\n(.*?)\n</script>" html)]
    island))

(deftest crash-leaves-a-truthful-report
  (with-temp-project
    (fn [dir]
      (reset! tournament-log [])
      (crash-project! dir)
      (let [junit-path (str (fs/path dir "invocation-junit.xml"))
            html-path (str (fs/path dir "invocation-report.html"))
            stale "STALE SENTINEL — a previous run's report\n"
            _ (spit junit-path stale)
            _ (spit html-path stale)
            {:keys [result err]} (run-captured (crash-opts dir junit-path html-path))]
        (testing "the crash surface: execute!'s tail catch, in-process —
                  unchanged by the markers"
          (is (= 3 (:exit-code result)))
          (is (= :crashed (:status result)))
          (is (= :runner/crash (get-in result [:error :type])))
          (is (str/includes? err "Runner crash:")
              "the crash line reaches stderr (stacktrace only under :verbose)")
          (is (not (str/includes? err "Crash marker not written"))
              "both marker writes succeeded"))
        (testing "the crash group's :stop still ran (run-group! ::crashed
                  runs :stop, then rethrows)"
          (is (= [:echo :kaboom :kaboom-stop] @tournament-log)
              "echo ran, kaboom started and stopped — and foxtrot NEVER
               started: the crash aborts the invocation"))
        (testing "the stable --html path is a CRASHED TOC over the groups
                  that ran — the stale sentinel is gone (sl-8orx)"
          (let [html (slurp html-path)
                island (island-of html)]
            (is (not= stale html) "the sentinel was overwritten")
            (is (some? island) "a real report, with its EDN island")
            (is (str/includes? island ":status :crashed") "the invocation verdict is CRASHED")
            (is (str/includes? island ":exit-code 3"))
            (is (str/includes? island ":crash {") "the summary carries the :crash record")
            (is (str/includes? island ":group/label \"kaboom\"")
                "the crash record names the group in flight")
            (is (str/includes? island ":run-id") "and the run-id (matches the JUnit body)")
            (is (re-find #":label \"echo-pass\"[^}]*:status :passed" island)
                "the completed group keeps its verdict row")
            (is (re-find #":label \"kaboom\"[^}]*:status :crashed" island)
                "the crashing group renders :crashed")
            (is (re-find #":label \"foxtrot-never\"[^}]*:status :skipped" island)
                "the never-started group renders :skipped (the sl-78u4 word)")
            (is (str/includes? island ":crashed-by") "…carrying the crash record as the reason")))
        (testing "the invocation JUnit carries the crash marker: red iff
                  nonzero holds beside exit 3 (sl-8orx) — before, this file
                  was a PARTIAL, ALL-GREEN, CURRENT run"
          (let [xml (slurp junit-path)]
            (is (not= stale xml) "the sentinel was overwritten")
            (is (str/includes? xml "Echo passes") "the pre-crash group is still in it")
            (is (str/includes? xml "<testsuite name=\"sl-crash\"") "the synthetic crash suite")
            (is (str/includes? xml "<error type=\"crash\"") "with one error element")
            (is (str/includes? xml "tests=\"3\" failures=\"0\" errors=\"1\"")
                "root totals count the marker: no longer green")
            (is (str/includes? xml "sl-internal-crash: deliberate coordinator crash")
                "the message is the exception's")
            (is (str/includes? xml (str "run-id: " (:run-id result)))
                "the body names the run-id")
            (is (str/includes? xml "KABOOM marker scenario")
                "the crashing group's completed scenario IS in it: the
                 shared state had recorded it before the probe (appended
                 last) threw — the marker re-materializes everything the
                 reporters saw, then says the runner crashed")))))))

(deftest crash-marker-write-failure-never-masks-the-crash
  ;; sl-8orx item 7: a marker write can itself throw (reporter invariant 4).
  ;; It is one stderr line per surface; the original crash keeps exit 3.
  (with-temp-project
    (fn [dir]
      (reset! tournament-log [])
      (crash-project! dir)
      (let [junit-path (str (fs/path dir "invocation-junit.xml"))
            html-path (str (fs/path dir "invocation-report.html"))
            orig reporter/write-atomically!
            {:keys [result err]}
            (with-redefs [reporter/write-atomically!
                          (fn [path content]
                            ;; Only the marker artifacts carry the crash
                            ;; message; every pre-crash group write is real.
                            (if (str/includes? (str content) "sl-internal-crash")
                              (throw (java.io.IOException. "disk full (simulated)"))
                              (orig path content)))]
              (run-captured (crash-opts dir junit-path html-path)))]
        (is (= 3 (:exit-code result)) "exit 3 stands")
        (is (= :runner/crash (get-in result [:error :type])))
        (is (str/includes? err "Runner crash: sl-internal-crash")
            "the ORIGINAL crash is the one reported")
        (is (str/includes? err "Crash marker not written (junit): java.io.IOException: disk full")
            "the JUnit marker failure is one stderr line")
        (is (str/includes? err "Crash marker not written (html): java.io.IOException: disk full")
            "the HTML marker failure is one stderr line — the surfaces fail independently")
        (is (= [:echo :kaboom :kaboom-stop] @tournament-log) ":stop still ran")
        (is (str/includes? (slurp junit-path) "tests=\"1\" failures=\"0\" errors=\"0\"")
            "the pre-crash partial is what remains when the marker cannot land
             (the documented fallback: gate on the exit code)")))))

(deftest epilogue-crash-leaves-a-truthful-report
  ;; sl-8orx F1: the throw comes AFTER every group ran — the invocation TOC
  ;; write itself fails (exactly where the original tournament crash
  ;; unwound). No group is in flight: every row keeps its verdict, no row
  ;; is :crashed or :skipped, the summary is :crashed, the JUnit carries
  ;; every scenario plus the sl-crash suite, exit 3.
  (with-temp-project
    (fn [dir]
      (reset! tournament-log [])
      (crash-project! dir)
      (let [junit-path (str (fs/path dir "invocation-junit.xml"))
            html-path (str (fs/path dir "invocation-report.html"))
            orig report-html/write-invocation-toc!
            calls (atom 0)
            {:keys [result err]}
            (with-redefs [report-html/write-invocation-toc!
                          (fn [& args]
                            ;; The normal epilogue's write throws; the
                            ;; crash path's rewrite (second call) lands.
                            (if (= 1 (swap! calls inc))
                              (throw (java.io.IOException. "toc write failed (simulated)"))
                              (apply orig args)))]
              (run-captured (assoc (tournament-opts dir)
                                   :junit-xml junit-path
                                   :html html-path)))]
        (is (= 3 (:exit-code result)))
        (is (= :runner/crash (get-in result [:error :type])))
        (is (str/includes? err "Runner crash: toc write failed"))
        (is (not (str/includes? err "Crash marker not written")))
        (is (= 2 @calls) "the epilogue write threw, the crashed TOC write followed")
        (is (= [:echo :kaboom :kaboom-stop :foxtrot] @tournament-log)
            "every group ran before the throw")
        (let [island (island-of (slurp html-path))]
          (is (str/includes? island ":status :crashed"))
          (is (str/includes? island ":crash {"))
          (is (not (str/includes? island ":group/label"))
              "no group was in flight — the crash record names none")
          (doseq [label ["echo-pass" "kaboom" "foxtrot-never"]]
            (is (re-find (re-pattern (str ":label \"" label "\"[^}]*:status :passed")) island)
                (str label " keeps its completed verdict row")))
          (is (not (str/includes? island ":crashed-by")) "no never-started rows"))
        (let [xml (slurp junit-path)]
          (is (str/includes? xml "<testsuite name=\"sl-crash\""))
          (is (str/includes? xml "tests=\"4\" failures=\"0\" errors=\"1\"")
              "three scenarios plus the marker")
          (doseq [nm ["Echo passes" "KABOOM marker scenario" "Foxtrot passes"]]
            (is (str/includes? xml nm))))))))

;; -----------------------------------------------------------------------------
;; (j) — CRASH under --max-parallel: the gate arms, the queue drains
;; -----------------------------------------------------------------------------

(def ^:private pool-steps-src
  "(ns pool-steps
     (:require [shiftlefter.stepengine.registry :refer [defstep]]
               [shiftlefter.runner.exit-tournament-test :as t]))
   (defstep #\"the warmup step passes\" [] nil)
   (defstep #\"the kaboom step passes\" [] nil)
   (defstep #\"the pool step counts\" []
     (t/record! :started)
     (t/await-pool!)
     nil)")

(def ^:private pool-setup-src
  "(ns setup
     (:require [shiftlefter.runner.exit-tournament-test :as t]))
   (def setups
     [{:label \"warmup-pass\"
       :start (fn [_] (t/record! :warmup) {})
       :features [\"features/warmup.feature\"]}
      {:label \"pool\"
       :start (fn [_] (t/record! :pool)
                {:stop (fn [] (t/record! :pool-stop))})
       :features [\"features/pool.feature\"]}
      {:label \"after-never\"
       :start (fn [_] (t/record! :after) {})
       :features [\"features/warmup.feature\"]}])")

(def ^:private pool-scenario-count 5)

(defn- pool-project! [dir]
  (spit-file dir "shiftlefter.edn" (pr-str {:runner {:step-paths []}}))
  (spit-file dir "steps/pool.clj" pool-steps-src)
  (spit-file dir "setup.clj" pool-setup-src)
  (spit-file dir "features/warmup.feature"
             "Feature: Warmup\n  Scenario: Warmup passes\n    Given the warmup step passes\n")
  (spit-file dir "features/pool.feature"
             (apply str
                    "Feature: Pool\n  Scenario: KABOOM marker scenario\n    Given the kaboom step passes\n"
                    (for [n (range 1 (inc pool-scenario-count))]
                      (str "  Scenario: Pool scenario " n "\n    Given the pool step counts\n")))))

(defn- settle
  "Poll `pred` for up to `ms`; returns as soon as it is true. Used only to
   give the OLD behavior (queued tasks still running after the coordinator
   is gone) time to show itself — never to assert timing."
  [pred ms]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop []
      (cond (pred) true
            (> (System/currentTimeMillis) deadline) false
            :else (do (Thread/sleep 25) (recur))))))

(deftest parallel-crash-arms-the-gate-and-drains-the-queue
  (with-temp-project
    (fn [dir]
      (reset! tournament-log [])
      (reset-pool-latch!)
      (pool-project! dir)
      (let [junit-path (str (fs/path dir "invocation-junit.xml"))
            html-path (str (fs/path dir "invocation-report.html"))
            max-parallel 2
            {:keys [result err]}
            (try
              (run-captured (assoc (crash-opts dir junit-path html-path)
                                   :max-parallel max-parallel))
              (finally
                ;; Release anything still blocked (in-flight workers the
                ;; interrupt did not reach) — and, under the OLD graceful
                ;; shutdown, every queued scenario would now start.
                (deliver @pool-latch :go)))
            started-count #(count (filter #{:started} @tournament-log))]
        (settle #(>= (started-count) pool-scenario-count) 1500)
        (testing "the crash surface"
          (is (= 3 (:exit-code result)))
          (is (str/includes? err "Runner crash:"))
          (is (= [:warmup :pool :pool-stop]
                 (remove #{:started} @tournament-log))
              "warmup ran, pool started and stopped, after-never never started"))
        (testing "no queued scenario started after the crash (Tower C2):
                  at most max-parallel counting scenarios were ever in
                  flight, and the queue was drained rather than run"
          (let [started (started-count)]
            (is (<= started max-parallel)
                (str "started " started " > max-parallel " max-parallel))
            (is (< started pool-scenario-count)
                (str "all " pool-scenario-count " ran — the queue was not drained"))))
        (testing "both markers landed"
          (let [xml (slurp junit-path)
                island (island-of (slurp html-path))]
            (is (str/includes? xml "<testsuite name=\"sl-crash\""))
            (is (str/includes? xml "<error type=\"crash\""))
            (is (str/includes? xml "KABOOM marker scenario")
                "the marker scenario reached the JUnit reporter before the probe (appended last) threw")
            (is (str/includes? xml "Warmup passes"))
            (is (str/includes? island ":status :crashed"))
            (is (re-find #":label \"pool\"[^}]*:status :crashed" island))
            (is (re-find #":label \"after-never\"[^}]*:status :skipped" island))))))))

(deftest crash-under-edn-emits-no-invocation-aggregate
  ;; sl-6vz8 (Warden W22): the crash case above runs without :edn. Under
  ;; --edn the invocation aggregate (:invocation/exit-code, the suite
  ;; verdict form) is written AFTER the groups — the crash unwinds past that
  ;; write, so the aggregate must be ABSENT and a machine consumer reading
  ;; the stream sees no suite verdict. Pinned as current behavior (the
  ;; agent-doc exit-codes topic already says "absent on a crash"); the
  ;; report-file hazard beside exit 3 was sl-8orx's (the marker pins above).
  (with-temp-project
    (fn [dir]
      (reset! tournament-log [])
      (crash-project! dir)
      (let [{:keys [result out]}
            (run-captured (assoc (tournament-opts dir)
                                 :edn true
                                 :internal-crash-on "KABOOM"))
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
