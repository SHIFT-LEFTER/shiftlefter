(ns shiftlefter.runner.abort-test
  "The run abort through the runner (sl-617t): exit 4 with `:halted
   {:reason :aborted :after-scenario N}` on every surface, every teardown
   run, reports always written.

   (a) single run (no setup.clj): a step requests the abort mid-suite —
       the rest is held, exit 4, the console carries HELD rows + the abort
       line, the EDN summary carries :halted + :counts :held, JUnit and
       HTML land.
   (b) multi-group (the tournament shape): the abort in group 2 stops
       group 2 at its boundary, runs its :stop, skips group 3 without its
       :start, aggregates to exit 4, and the invocation surfaces carry the
       abort record naming the group.
   (c) between groups: an abort landing inside a :stop still makes the
       invocation 4 and the aggregate record names the last group that ran.
   (d) the cold SIGINT handler body, driven directly (no real signal):
       no-run => 130; strike one prints the immediate line and returns;
       strike two runs the registered crash epilogue then exits 3 — unless
       finalizing (fold-in C1), when it exits 3 without the epilogue.

   The trigger is a user step calling `abort/request!` — the same call the
   daemon op and the SIGINT handler make; no probe flag needed."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [shiftlefter.core :as core]
            [shiftlefter.runner.core :as rc]
            [shiftlefter.stepengine.exec.abort :as abort]))

;; -----------------------------------------------------------------------------
;; Helpers (the exit_tournament_test idiom)
;; -----------------------------------------------------------------------------

(defn- with-temp-project [f]
  (let [dir (str (fs/create-temp-dir {:prefix "sl-abort-test-"}))]
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

(defn- read-forms [out]
  (let [rdr (java.io.PushbackReader. (java.io.StringReader. out))]
    (loop [acc []]
      (let [form (edn/read {:eof ::eof} rdr)]
        (if (= ::eof form) acc (recur (conj acc form)))))))

(defn- island-of [html]
  (let [[_ island] (re-find #"(?s)<script type=\"application/edn\" id=\"run-data\">\n(.*?)\n</script>" html)]
    island))

;; The setup.clj under test records lifecycle here.
(defonce abort-log (atom []))

(defn ^:export record! [tag] (swap! abort-log conj tag))

(def ^:private steps-src
  "(ns abort-steps
     (:require [shiftlefter.runner.abort-test :as t]
               [shiftlefter.stepengine.exec.abort :as abort]
               [shiftlefter.stepengine.registry :refer [defstep]]))
   (defstep #\"a quiet step\" [] (t/record! :quiet) nil)
   (defstep #\"the abort is requested\" [] (t/record! :requested) (abort/request!) nil)
   (defstep #\"a step after the request\" [] (t/record! :after-request) nil)
   (defstep #\"the second strike lands\" [] (t/run-strike-hook!) nil)")

(def ^:private single-feature
  (str "Feature: Single\n"
       "  Scenario: One passes\n    Given a quiet step\n"
       "  Scenario: Two asks then stops\n    Given the abort is requested\n    And a step after the request\n"
       "  Scenario: Three never runs\n    Given a quiet step\n"))

(defn- single-project! [dir]
  (spit-file dir "shiftlefter.edn" (pr-str {:runner {:step-paths []}}))
  (spit-file dir "steps/abort.clj" steps-src)
  (spit-file dir "features/single.feature" single-feature))

(def ^:private strike-feature
  (str "Feature: Strike\n"
       "  Scenario: One passes\n    Given a quiet step\n"
       "  Scenario: Two strikes\n    Given the second strike lands\n"))

(defn- opts [dir & more]
  (merge {:paths [(str (fs/path dir "features"))]
          :config-path (str (fs/path dir "shiftlefter.edn"))
          :step-paths [(str (fs/path dir "steps"))]}
         (apply hash-map more)))

;; -----------------------------------------------------------------------------
;; (a) single run
;; -----------------------------------------------------------------------------

(deftest single-run-abort-exits-4-and-writes-every-surface
  (with-temp-project
    (fn [dir]
      (reset! abort-log [])
      (single-project! dir)
      (let [junit-path (str (fs/path dir "junit.xml"))
            html-path (str (fs/path dir "report.html"))
            {:keys [result err]} (run-captured (opts dir :junit-xml junit-path :html html-path))]
        (testing "the verdict: exit 4, degraded, the abort record"
          (is (= 4 (:exit-code result)))
          (is (= :degraded (:status result)))
          (is (= {:reason :aborted :after-scenario 1} (-> result :result :halted)))
          (is (= {:passed 1 :failed 0 :pending 0 :skipped 0 :held 2} (:counts result))))
        (testing "the step after the request never ran; scenario three never started"
          (is (= [:quiet :requested] @abort-log))
          (is (= [:passed :skipped :skipped] (mapv :status (-> result :result :scenarios))))
          (is (= [nil {:reason :aborted :after-step 1} {:reason :aborted}]
                 (mapv :held (-> result :result :scenarios)))))
        (testing "the console: HELD rows and the abort line"
          (is (str/includes? err "HELD (aborted after step 1) Two asks then stops"))
          (is (str/includes? err "HELD (aborted) Three never runs"))
          (is (str/includes? err "3 scenario(s): 1 passed, 2 held"))
          (is (str/includes? err "aborted after scenario 1: 2 scenario(s) held")))
        (testing "JUnit: the held scenarios are <skipped> with the held: prefix"
          (let [xml (slurp junit-path)]
            (is (str/includes? xml "message=\"held: aborted after step 1 of 2\""))
            (is (str/includes? xml "message=\"held: aborted before this scenario ran\""))))
        (testing "HTML: the island carries the abort record and the held rows"
          (let [island (island-of (slurp html-path))]
            (is (some? island))
            (is (str/includes? island ":halted {:reason :aborted, :after-scenario 1}"))
            (is (str/includes? island ":held {:reason :aborted, :after-step 1}"))))
        (testing "the registry is clean after execute!"
          (is (not (abort/run-registered?)))
          (is (not (abort/requested?))))))))

(deftest single-run-abort-edn-summary
  (with-temp-project
    (fn [dir]
      (reset! abort-log [])
      (single-project! dir)
      (let [{:keys [result out]} (run-captured (opts dir :edn true))
            summary (last (read-forms out))]
        (is (= 4 (:exit-code result)))
        (is (= 4 (:run/exit-code summary)))
        (is (= :degraded (:run/status summary)))
        (is (= {:reason :aborted :after-scenario 1} (:halted summary)))
        (is (= 2 (-> summary :counts :held)))
        (is (= 0 (-> summary :counts :skipped)) "held is carved out of skipped")))))

;; -----------------------------------------------------------------------------
;; (b)(c) multi-group
;; -----------------------------------------------------------------------------

(def ^:private setup-src
  "(ns setup
     (:require [shiftlefter.runner.abort-test :as t]
               [shiftlefter.stepengine.exec.abort :as abort]))
   (def setups
     [{:label \"alpha\"
       :start (fn [_] (t/record! :alpha-start)
                {:stop (fn [] (t/record! :alpha-stop)
                         (when (System/getProperty \"sl.abort-test.in-stop\")
                           (abort/request!)))})
       :features [\"features/alpha.feature\"]}
      {:label \"bravo\"
       :start (fn [_] (t/record! :bravo-start)
                {:stop (fn [] (t/record! :bravo-stop) (t/run-stop-hook!))})
       :features [\"features/bravo.feature\"]}
      {:label \"charlie\"
       :start (fn [_] (t/record! :charlie-start) {})
       :features [\"features/charlie.feature\"]}])")

(defn- multi-project! [dir]
  (spit-file dir "shiftlefter.edn" (pr-str {:runner {:step-paths []}}))
  (spit-file dir "steps/abort.clj" steps-src)
  (spit-file dir "setup.clj" setup-src)
  (spit-file dir "features/alpha.feature"
             "Feature: Alpha\n  Scenario: Alpha passes\n    Given a quiet step\n")
  (spit-file dir "features/bravo.feature"
             (str "Feature: Bravo\n"
                  "  Scenario: Bravo asks\n    Given the abort is requested\n    And a step after the request\n"
                  "  Scenario: Bravo never runs\n    Given a quiet step\n"))
  (spit-file dir "features/charlie.feature"
             "Feature: Charlie\n  Scenario: Charlie never starts\n    Given a quiet step\n"))

(defn- multi-opts [dir & more]
  (merge {:paths []
          :config-path (str (fs/path dir "shiftlefter.edn"))
          :step-paths [(str (fs/path dir "steps"))]}
         (apply hash-map more)))

(deftest multi-group-abort-stops-the-group-runs-its-stop-and-skips-the-rest
  (with-temp-project
    (fn [dir]
      (reset! abort-log [])
      (multi-project! dir)
      (let [junit-path (str (fs/path dir "invocation-junit.xml"))
            html-path (str (fs/path dir "invocation-report.html"))
            {:keys [result err]} (run-captured (multi-opts dir :junit-xml junit-path
                                                          :html html-path))]
        (testing "lifecycle: alpha whole, bravo stopped at its boundary and torn
                  down, charlie's :start never ran"
          (is (= [:alpha-start :quiet :alpha-stop
                  :bravo-start :requested :bravo-stop]
                 @abort-log)))
        (testing "the invocation verdict"
          (is (= 4 (:exit-code result)))
          (is (= {:reason :aborted :after-scenario 0 :group/label "bravo"}
                 (:halted result))
              "the aborted group's record, naming the group; no bravo scenario reached a verdict")
          (is (= [0 4] (->> (:groups result) (keep :exit-code)))
              "alpha 0, bravo 4; charlie has no exit code")
          (is (= :skipped (-> (:groups result) last :status)))
          (is (= :aborted (-> (:groups result) last :halted-by :reason))))
        (testing "the console group lines"
          (is (str/includes? err "Setup group \"bravo\": exit code 4 (degraded)"))
          (is (str/includes? err "Setup group \"charlie\": skipped (aborted)")))
        (testing "the invocation surfaces were written"
          (is (str/includes? (slurp junit-path) "held: aborted"))
          (let [island (island-of (slurp html-path))]
            (is (some? island))
            (is (str/includes? island ":reason :aborted"))
            (is (re-find #":label \"charlie\"[^}]*:status :skipped" island))))))))

(deftest multi-group-abort-edn-stream
  (with-temp-project
    (fn [dir]
      (reset! abort-log [])
      (multi-project! dir)
      (let [{:keys [result out]} (run-captured (multi-opts dir :edn true))
            forms (read-forms out)
            aggregate (last forms)
            bravo (some #(when (= "bravo" (:group/label %)) %) forms)]
        (is (= 4 (:exit-code result)))
        (testing "bravo's own summary carries the record and the held count"
          (is (= {:reason :aborted :after-scenario 0} (:halted bravo)))
          (is (= 2 (-> bravo :counts :held))))
        (testing "the aggregate form: exit 4, the record naming the group,
                  charlie's row with no exit code"
          (is (= 4 (:invocation/exit-code aggregate)))
          (is (= :degraded (:invocation/status aggregate)))
          (is (= {:reason :aborted :after-scenario 0 :group/label "bravo"}
                 (:halted aggregate)))
          (let [charlie (some #(when (= "charlie" (:group/label %)) %)
                              (:invocation/groups aggregate))]
            (is (= :skipped (:run/status charlie)))
            (is (not (contains? charlie :run/exit-code)))))))))

(deftest abort-between-groups-still-exits-4
  (with-temp-project
    (fn [dir]
      (reset! abort-log [])
      (multi-project! dir)
      ;; alpha's :stop requests the abort (the bravo feature's request step
      ;; therefore never runs — bravo never starts).
      (System/setProperty "sl.abort-test.in-stop" "1")
      (try
        (let [{:keys [result]} (run-captured (multi-opts dir))]
          (is (= [:alpha-start :quiet :alpha-stop] @abort-log)
              "bravo's :start never ran")
          (is (= 4 (:exit-code result)) "alpha was green; the abort alone makes it 4")
          (is (= {:reason :aborted :after-scenario 1 :group/label "alpha"}
                 (:halted result))
              "the record names the last group that ran and its verdict count")
          (is (= [:skipped :skipped] (map :status (rest (:groups result))))))
        (finally
          (System/clearProperty "sl.abort-test.in-stop"))))))

;; -----------------------------------------------------------------------------
;; (d) the cold SIGINT handler body
;; -----------------------------------------------------------------------------

(defmacro ^:private with-err-str [& body]
  `(let [sw# (java.io.StringWriter.)]
     (binding [*err* sw#] ~@body)
     (str sw#)))

(deftest cold-sigint-handler-body
  (let [exits (atom [])
        exit! #(swap! exits conj %)]
    (testing "no run registered: the JVM's 130, nothing printed"
      (abort/end-run!)
      (is (str/blank? (with-err-str (core/handle-cold-sigint! exit!))))
      (is (= [130] @exits)))
    (testing "strike one: the immediate line, the request, no exit"
      (reset! exits [])
      (abort/begin-run!)
      (abort/register-results-dir! "/tmp/run-x")
      (let [err (with-err-str (core/handle-cold-sigint! exit!))]
        (is (str/starts-with? err "aborting: 0 scenario(s) finishing their current step"))
        (is (str/includes? err "reports will land at /tmp/run-x"))
        (is (abort/requested?))
        (is (= [] @exits))))
    (testing "strike two: the crash epilogue runs, then exit 3"
      (let [ran (atom nil)]
        (abort/register-crash-epilogue! (fn [t] (reset! ran (:type (ex-data t)))))
        (let [err (with-err-str (core/handle-cold-sigint! exit!))]
          (is (str/includes? err "second interrupt: abandoning the unwind — crash row; exit 3"))
          (is (= :runner/abort-abandoned @ran))
          (is (= [3] @exits)))))
    (testing "strike three: ignored"
      (reset! exits [])
      (is (str/blank? (with-err-str (core/handle-cold-sigint! exit!))))
      (is (= [] @exits)))
    (testing "fold-in C1 / P1: finalizing => strike two is a NO-OP — the line,
              no epilogue, no exit; the run finishes and exits 4"
      (reset! exits [])
      (abort/begin-run!)
      (let [ran (atom false)]
        (abort/register-crash-epilogue! (fn [_] (reset! ran true)))
        (core/handle-cold-sigint! exit!)
        (abort/finalizing!)
        (let [err (with-err-str (core/handle-cold-sigint! exit!))]
          (is (str/includes? err "second interrupt: final reports being written; exiting 4"))
          (is (false? @ran))
          (is (= [] @exits)))))
    (abort/end-run!)))

;; -----------------------------------------------------------------------------
;; (e) P1: strike two DURING a multi-group :stop — after the in-flight group
;; returned, before the invocation report — is still the crash row (the
;; invocation surfaces are live: the JUnit re-materializes per group end,
;; the TOC is written after the loop); once the final writes begin it is a
;; no-op. The group's :stop calls the handler body directly (no signal).
;; -----------------------------------------------------------------------------

(defonce stop-hook (atom nil))

(defn ^:export run-stop-hook! [] (when-let [f @stop-hook] (f)))

;; sl-o26v: a hook a STEP calls, for the single-scope strike-two case.
(defonce strike-hook (atom nil))

(defn ^:export run-strike-hook! [] (when-let [f @strike-hook] (f)))

(deftest strike-two-during-a-multi-group-stop-is-the-crash-row
  (with-temp-project
    (fn [dir]
      (reset! abort-log [])
      (multi-project! dir)
      (let [junit-path (str (fs/path dir "invocation-junit.xml"))
            html-path (str (fs/path dir "invocation-report.html"))
            exits (atom [])
            snapshot (atom nil)
            err-at-stop (atom "")]
        ;; bravo's :stop: the abort was already requested by bravo's step;
        ;; strike one re-reports it, strike two takes the crash row. Snapshot
        ;; the surfaces THEN — the normal epilogue overwrites them after.
        (reset! stop-hook
                (fn []
                  (let [err (with-err-str
                              (core/handle-cold-sigint! #(swap! exits conj %))
                              (core/handle-cold-sigint! #(swap! exits conj %)))]
                    (reset! err-at-stop err)
                    (reset! snapshot {:junit (slurp junit-path) :html (slurp html-path)}))))
        (try
          (let [{:keys [result]} (run-captured (multi-opts dir :junit-xml junit-path
                                                          :html html-path))]
            (is (= 4 (:exit-code result)) "the stubbed exit let the run finish: 4")
            (is (= [3] @exits) "strike two asked for exit 3")
            (is (str/includes? @err-at-stop "aborting: 0 scenario(s)") "strike one's line")
            (is (str/includes? @err-at-stop "second interrupt: abandoning the unwind — crash row; exit 3"))
            (is (str/includes? @err-at-stop "Runner crash: run abandoned: second interrupt"))
            (testing "the markers were written at strike time (the 8orx shape avoided)"
              (is (str/includes? (:junit @snapshot) "<testsuite name=\"sl-crash\""))
              (is (str/includes? (:html @snapshot) ":status :crashed"))))
          (finally
            (reset! stop-hook nil)))))))

(deftest strike-two-under-the-finalizing-flag-is-a-no-op
  (with-temp-project
    (fn [dir]
      (reset! abort-log [])
      (multi-project! dir)
      (let [junit-path (str (fs/path dir "invocation-junit.xml"))
            html-path (str (fs/path dir "invocation-report.html"))
            exits (atom [])
            ran (atom false)
            err-at-stop (atom "")]
        (reset! stop-hook
                (fn []
                  (abort/register-crash-epilogue! (fn [_] (reset! ran true)))
                  (core/handle-cold-sigint! #(swap! exits conj %))
                  ;; The twin: the final writes have begun.
                  (abort/finalizing!)
                  (reset! err-at-stop
                          (with-err-str (core/handle-cold-sigint! #(swap! exits conj %))))))
        (try
          (let [{:keys [result]} (run-captured (multi-opts dir :junit-xml junit-path
                                                          :html html-path))]
            (is (= 4 (:exit-code result)))
            (is (= [] @exits) "no exit requested")
            (is (false? @ran) "no crash epilogue")
            (is (str/includes? @err-at-stop "second interrupt: final reports being written; exiting 4"))
            (is (not (str/includes? (slurp junit-path) "sl-crash"))
                "the final JUnit is the abort record, not a crash marker"))
          (finally
            (reset! stop-hook nil)))))))

;; -----------------------------------------------------------------------------
;; (f) sl-o26v: strike two in a SINGLE-SCOPE run now leaves the crash markers
;; — the same one seam the outer catch uses. The second scenario's step
;; drives the handler body twice with a stubbed exit and snapshots the
;; files at strike time (the normal epilogue overwrites them afterwards).
;; -----------------------------------------------------------------------------

(deftest strike-two-in-a-single-scope-run-writes-the-markers
  (with-temp-project
    (fn [dir]
      (reset! abort-log [])
      (spit-file dir "shiftlefter.edn" (pr-str {:runner {:step-paths []}}))
      (spit-file dir "steps/abort.clj" steps-src)
      (let [feature (spit-file dir "features/strike.feature" strike-feature)
            junit-path (str (fs/path dir "junit.xml"))
            html-path (str (fs/path dir "report.html"))
            exits (atom [])
            snapshot (atom nil)]
        (reset! strike-hook
                (fn []
                  (core/handle-cold-sigint! #(swap! exits conj %))
                  (with-err-str (core/handle-cold-sigint! #(swap! exits conj %)))
                  (reset! snapshot {:junit (slurp junit-path) :html (slurp html-path)})))
        (try
          (let [{:keys [result]} (run-captured {:paths [feature]
                                                :config-path (str (fs/path dir "shiftlefter.edn"))
                                                :step-paths [(str (fs/path dir "steps"))]
                                                :junit-xml junit-path
                                                :html html-path})]
            (is (= [3] @exits) "strike two asked for exit 3")
            (testing "the markers were on disk at strike time — the single-scope path"
              (is (str/includes? (:junit @snapshot) "<testsuite name=\"sl-crash\""))
              (is (str/includes? (:junit @snapshot) "One passes")
                  "the scenario that completed before the strike is in the JUnit")
              (is (str/includes? (:html @snapshot) ":status :crashed")))
            (is (= 4 (:exit-code result)) "the stubbed exit let the aborted run finish: 4"))
          (finally
            (reset! strike-hook nil)))))))
