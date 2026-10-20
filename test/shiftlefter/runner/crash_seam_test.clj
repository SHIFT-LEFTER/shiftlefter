(ns shiftlefter.runner.crash-seam-test
  "sl-o26v: ONE crash seam — a mid-run crash leaves truthful JUnit + HTML
   files beside exit 3 in EVERY pipeline, not only multi-group runs.

   (a) the classic pipeline (no setup.clj): the sl-crash suite lands in
       the JUnit reporter's own accumulated document, the HTML page is
       CRASHED over the scenarios seen, stale sentinels are overwritten.
   (b) a setup.clj run narrowed to ONE filtered group (multi? false):
       the same shape through the private-state path, the crash record
       naming the group, :stop still run.
   (c) a single-scope marker write that fails never masks the crash.
   (d) D3: a dry run writes nothing; D4: under --edn the crash form still
       prints last AND the files carry the markers.
   (e) the once-guard: the epilogue called twice writes the markers once.

   The multi-group pins (sl-8orx) live in exit_tournament_test and stay as
   they are — that path is byte-identical."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [shiftlefter.runner.core :as rc]
            [shiftlefter.runner.reporter :as reporter]
            [shiftlefter.stepengine.exec.abort :as abort]))

(defn- with-temp-project [f]
  (let [dir (str (fs/create-temp-dir {:prefix "sl-crash-seam-test-"}))]
    (try (f dir) (finally (fs/delete-tree dir)))))

(defn- spit-file [dir relative content]
  (let [target (fs/path dir relative)]
    (fs/create-dirs (fs/parent target))
    (spit (str target) content)
    (str target)))

(defn- run-captured [opts]
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

(defonce seam-log (atom []))
(defn ^:export record! [tag] (swap! seam-log conj tag))

(def ^:private steps-src
  "(ns seam-steps
     (:require [shiftlefter.stepengine.registry :refer [defstep]]))
   (defstep #\"the first step passes\" [] nil)
   (defstep #\"the kaboom step passes\" [] nil)
   (defstep #\"the third step passes\" [] nil)")

(def ^:private feature-src
  (str "Feature: Seam\n"
       "  Scenario: First completes\n    Given the first step passes\n"
       "  Scenario: KABOOM marker scenario\n    Given the kaboom step passes\n"
       "  Scenario: Third never starts\n    Given the third step passes\n"))

(defn- classic-project! [dir]
  (spit-file dir "shiftlefter.edn" (pr-str {:runner {:step-paths []}}))
  (spit-file dir "steps/seam.clj" steps-src)
  (spit-file dir "features/seam.feature" feature-src))

(defn- classic-opts [dir junit-path html-path]
  {:paths [(str (fs/path dir "features"))]
   :config-path (str (fs/path dir "shiftlefter.edn"))
   :step-paths [(str (fs/path dir "steps"))]
   :internal-crash-on "KABOOM"
   :junit-xml junit-path
   :html html-path})

(def ^:private stale "STALE SENTINEL — a previous run's report\n")

(defn- assert-crashed-surfaces!
  "The shared assertion set: JUnit carries the completed scenario + the
   sl-crash suite; HTML is a CRASHED page over the scenarios seen."
  [junit-path html-path result]
  (let [xml (slurp junit-path)
        html (slurp html-path)
        island (island-of html)]
    (is (not= stale xml) "the stale JUnit sentinel was overwritten")
    (is (not= stale html) "the stale HTML sentinel was overwritten")
    (is (str/includes? xml "First completes") "the completed scenario is in the JUnit")
    (is (str/includes? xml "KABOOM marker scenario")
        "the marker scenario had been recorded before the probe threw")
    (is (str/includes? xml "<testsuite name=\"sl-crash\"") "the synthetic crash suite")
    (is (str/includes? xml "<error type=\"crash\""))
    (is (str/includes? xml (str "run-id: " (:run-id result))) "the JUnit body names the run-id")
    (is (not (str/includes? xml "Third never starts")) "a scenario that never started is absent")
    (is (some? island) "a real HTML report, with its EDN island")
    (is (str/includes? island ":status :crashed") "the run page's verdict is CRASHED")
    (is (str/includes? island ":exit-code 3"))
    (is (str/includes? island ":crash {") "the summary carries the :crash record")
    (is (str/includes? island (str ":run-id \"" (:run-id result) "\"")))
    (is (str/includes? island "First completes") "the scenarios seen are on the page")))

;; -----------------------------------------------------------------------------
;; (a) the classic pipeline
;; -----------------------------------------------------------------------------

(deftest classic-pipeline-crash-leaves-truthful-files
  (with-temp-project
    (fn [dir]
      (classic-project! dir)
      (let [junit-path (str (fs/path dir "junit.xml"))
            html-path (str (fs/path dir "report.html"))
            _ (spit junit-path stale)
            _ (spit html-path stale)
            {:keys [result err]} (run-captured (classic-opts dir junit-path html-path))]
        (testing "the crash surface itself is unchanged: exit 3, the line"
          (is (= 3 (:exit-code result)))
          (is (= :crashed (:status result)))
          (is (= :runner/crash (get-in result [:error :type])))
          (is (str/includes? err "Runner crash: sl-internal-crash"))
          (is (not (str/includes? err "Crash marker not written")) "both writes landed"))
        (assert-crashed-surfaces! junit-path html-path result)))))

;; -----------------------------------------------------------------------------
;; (b) one filtered setup.clj group
;; -----------------------------------------------------------------------------

(def ^:private setup-src
  "(ns setup
     (:require [shiftlefter.runner.crash-seam-test :as t]))
   (def setups
     [{:label \"alpha\"
       :start (fn [_] (t/record! :alpha) {})
       :features [\"features/alpha.feature\"]}
      {:label \"kaboom\"
       :start (fn [_] (t/record! :kaboom)
                {:stop (fn [] (t/record! :kaboom-stop))})
       :features [\"features/seam.feature\"]}])")

(defn- grouped-project! [dir]
  (spit-file dir "shiftlefter.edn" (pr-str {:runner {:step-paths []}}))
  (spit-file dir "steps/seam.clj" steps-src)
  (spit-file dir "setup.clj" setup-src)
  (spit-file dir "features/alpha.feature"
             "Feature: Alpha\n  Scenario: Alpha passes\n    Given the first step passes\n")
  (spit-file dir "features/seam.feature" feature-src))

(deftest single-filtered-group-crash-leaves-truthful-files
  (with-temp-project
    (fn [dir]
      (reset! seam-log [])
      (grouped-project! dir)
      (let [junit-path (str (fs/path dir "junit.xml"))
            html-path (str (fs/path dir "report.html"))
            _ (spit junit-path stale)
            _ (spit html-path stale)
            {:keys [result err]}
            (run-captured {:paths [(str (fs/path dir "features/seam.feature"))]
                           :config-path (str (fs/path dir "shiftlefter.edn"))
                           :step-paths [(str (fs/path dir "steps"))]
                           :internal-crash-on "KABOOM"
                           :junit-xml junit-path
                           :html html-path})]
        (is (= 3 (:exit-code result)))
        (is (str/includes? err "Runner crash: sl-internal-crash"))
        (is (= [:kaboom :kaboom-stop] @seam-log)
            "only the narrowed group started; its :stop still ran; alpha never did")
        (assert-crashed-surfaces! junit-path html-path result)
        (is (str/includes? (island-of (slurp html-path)) ":group/label \"kaboom\"")
            "the single group's crash record names the group")))))

;; -----------------------------------------------------------------------------
;; (c) a failing single-scope marker write never masks the crash
;; -----------------------------------------------------------------------------

(deftest single-scope-marker-write-failure-never-masks-the-crash
  (with-temp-project
    (fn [dir]
      (classic-project! dir)
      (let [junit-path (str (fs/path dir "junit.xml"))
            html-path (str (fs/path dir "report.html"))
            orig reporter/write-atomically!
            {:keys [result err]}
            (with-redefs [reporter/write-atomically!
                          (fn [path content]
                            (if (str/includes? (str content) "sl-internal-crash")
                              (throw (java.io.IOException. "disk full (simulated)"))
                              (orig path content)))]
              (run-captured (classic-opts dir junit-path html-path)))]
        (is (= 3 (:exit-code result)) "exit 3 stands")
        (is (str/includes? err "Runner crash: sl-internal-crash") "the ORIGINAL crash is reported")
        (is (str/includes? err "Crash marker not written (junit): java.io.IOException: disk full"))
        (is (str/includes? err "Crash marker not written (html): java.io.IOException: disk full"))
        (is (not (.exists (java.io.File. junit-path)))
            "nothing had been written before: the file stays absent, the exit code is the gate")))))

;; -----------------------------------------------------------------------------
;; (d) D3 dry-run; D4 --edn
;; -----------------------------------------------------------------------------

(deftest dry-run-never-writes-crash-markers
  ;; The probe fires on scenario completion, which a dry run never reaches;
  ;; the seam's own guard is the contract, pinned directly.
  (with-temp-project
    (fn [dir]
      (let [junit-path (str (fs/path dir "junit.xml"))
            html-path (str (fs/path dir "report.html"))
            surfaces {:opts {:dry-run true} :config {}
                      :junit-path junit-path :junit-state (atom {:scenarios [] :run-ctx nil})
                      :html-path html-path :html-state (atom {:scenarios [] :run-ctx nil})}]
        (#'rc/write-crash-markers! "run-dry" surfaces (ex-info "boom" {}))
        (is (not (.exists (java.io.File. junit-path))))
        (is (not (.exists (java.io.File. html-path))))
        (testing "and nil surfaces (a crash before any reporter existed) write nothing either"
          (#'rc/write-crash-markers! "run-none" nil (ex-info "boom" {}))
          (is (not (.exists (java.io.File. junit-path)))))))))

(deftest edn-crash-prints-the-form-and-still-writes-the-files
  (with-temp-project
    (fn [dir]
      (classic-project! dir)
      (let [junit-path (str (fs/path dir "junit.xml"))
            html-path (str (fs/path dir "report.html"))
            {:keys [result out]} (run-captured (assoc (classic-opts dir junit-path html-path)
                                                      :edn true))
            forms (read-forms out)
            crash (last forms)]
        (is (= 3 (:exit-code result)))
        (is (= 3 (:run/exit-code crash)) "the crash form is the last thing on stdout")
        (is (= :crashed (:run/status crash)))
        (assert-crashed-surfaces! junit-path html-path result)))))

;; -----------------------------------------------------------------------------
;; (e) the once-guard
;; -----------------------------------------------------------------------------

(defonce once-hook (atom nil))
(defn ^:export run-once-hook! [] (when-let [f @once-hook] (f)))

(deftest the-epilogue-writes-markers-once
  ;; A cold second strike runs the registered epilogue, then exits 3; if
  ;; the coordinator ALSO throws before the JVM dies, the outer catch runs
  ;; it again — the markers must land once. Drive the registered epilogue
  ;; twice from inside the run (a step) and count the JUnit marker writes
  ;; AT THAT MOMENT (the stubbed run then finishes normally, and its own
  ;; end-of-run write legitimately carries the stored crash record).
  (with-temp-project
    (fn [dir]
      (spit-file dir "shiftlefter.edn" (pr-str {:runner {:step-paths []}}))
      (spit-file dir "steps/once.clj"
                 "(ns once-steps
                    (:require [shiftlefter.runner.crash-seam-test :as t]
                              [shiftlefter.stepengine.registry :refer [defstep]]))
                  (defstep #\"the epilogue runs twice\" [] (t/run-once-hook!) nil)")
      (spit-file dir "features/once.feature"
                 "Feature: Once\n  Scenario: twice\n    Given the epilogue runs twice\n")
      (let [junit-path (str (fs/path dir "junit.xml"))
            writes (atom 0)
            writes-after-strikes (atom nil)
            orig reporter/write-atomically!]
        (reset! once-hook
                (fn []
                  (let [f (abort/crash-epilogue)]
                    (f (ex-info "strike" {}))
                    (f (ex-info "strike" {}))
                    (reset! writes-after-strikes @writes))))
        (try
          (let [{:keys [result err]}
                (with-redefs [reporter/write-atomically!
                              (fn [path content]
                                (when (str/includes? (str content) "sl-crash")
                                  (swap! writes inc))
                                (orig path content))]
                  (run-captured {:paths [(str (fs/path dir "features"))]
                                 :config-path (str (fs/path dir "shiftlefter.edn"))
                                 :step-paths [(str (fs/path dir "steps"))]
                                 :junit-xml junit-path}))]
            (is (= 1 @writes-after-strikes) "two epilogue calls, one marker write")
            (is (= 2 (count (re-seq #"Runner crash: strike" err)))
                "the crash report itself runs on every call (D5)")
            (is (= 0 (:exit-code result))
                "the run itself was not aborted — the step returned normally")
            (is (not (abort/run-registered?)) "the registry is clean afterwards"))
          (finally
            (reset! once-hook nil)))))))
