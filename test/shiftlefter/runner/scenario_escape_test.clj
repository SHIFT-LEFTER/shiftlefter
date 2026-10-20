(ns shiftlefter.runner.scenario-escape-test
  "sl-io05: a Throwable escaping the per-scenario machinery is a RUNNER
   CRASH — exit 3 through the one crash seam (sl-8orx / sl-o26v) on EVERY
   scheduler route:

   (1) :max-parallel 1 — every plan inline on the coordinator;
   (2) a pool task under :max-parallel N — the guard hands back a sentinel
       and the coordinator rethrows the ORIGINAL throwable on take;
   (3) the @serial tail under :max-parallel N — inline on the coordinator.

   The escape is constructed with the src-side proof instrument
   (`:internal-scenario-crash-on` / `--sl-internal-scenario-crash=`): the
   defect class — a bug in ShiftLefter's own weave — has no natural repro.

   The three routes' crash reports are EQUAL (class, message, and the
   crashed scenario's identity): the same defect reads the same whatever
   thread it surfaced on. Every run is deref'd under a timeout — a hung
   coordinator fails the test instead of hanging the suite."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [shiftlefter.runner.core :as rc]))

(defn- with-temp-project [f]
  (let [dir (str (fs/create-temp-dir {:prefix "sl-scenario-escape-test-"}))]
    (try (f dir) (finally (fs/delete-tree dir)))))

(defn- spit-file [dir relative content]
  (let [target (fs/path dir relative)]
    (fs/create-dirs (fs/parent target))
    (spit (str target) content)
    (str target)))

(def ^:private hang-timeout-ms 60000)

(defn- run-captured
  "execute! under a timeout: `{:result :out :err}`, or `{:hung? true}` when
   the run never returned (the coordinator-hang failure shape)."
  [opts]
  (let [run (future
              (let [out (java.io.StringWriter.)
                    err (java.io.StringWriter.)
                    result (binding [*out* out *err* err]
                             (rc/execute! (merge {:no-color true} opts)))]
                {:result result :out (str out) :err (str err)}))
        outcome (deref run hang-timeout-ms ::hung)]
    (if (= ::hung outcome)
      (do (future-cancel run) {:hung? true})
      outcome)))

(defn- read-forms [out]
  (let [rdr (java.io.PushbackReader. (java.io.StringReader. out))]
    (loop [acc []]
      (let [form (edn/read {:eof ::eof} rdr)]
        (if (= ::eof form) acc (recur (conj acc form)))))))

(def ^:private steps-src
  "(ns escape-steps
     (:require [shiftlefter.stepengine.registry :refer [defstep]]))
   (defstep #\"the escape-first step passes\" [] nil)
   (defstep #\"the escape-kaboom step passes\" [] nil)
   (defstep #\"the escape-third step passes\" [] nil)")

(defn- feature-src
  "The marker scenario sits on the SAME line in both variants — a blank
   line stands in for the @serial tag line — so the crashed scenario's
   identity is route-independent."
  [serial?]
  (str "Feature: Escape\n"
       "  Scenario: First completes\n"
       "    Given the escape-first step passes\n"
       (if serial? "  @serial\n" "\n")
       "  Scenario: KABOOM marker scenario\n"
       "    Given the escape-kaboom step passes\n"
       "  Scenario: Third\n"
       "    Given the escape-third step passes\n"))

(def ^:private kaboom-line 5)

(def ^:private routes
  [{:route "max-parallel 1"             :max-parallel 1 :serial? false}
   {:route "pool task, max-parallel 2"  :max-parallel 2 :serial? false}
   {:route "@serial tail, max-parallel 2" :max-parallel 2 :serial? true}])

(defn- project! [dir serial?]
  (spit-file dir "shiftlefter.edn" (pr-str {:runner {:step-paths []}}))
  (spit-file dir "steps/escape.clj" steps-src)
  (spit-file dir "features/escape.feature" (feature-src serial?)))

(defn- run-route
  [{:keys [max-parallel serial?]} extra-opts]
  (with-temp-project
    (fn [dir]
      (project! dir serial?)
      (let [junit-path (str (fs/path dir "junit.xml"))
            html-path (str (fs/path dir "report.html"))
            run (run-captured (merge {:paths [(str (fs/path dir "features"))]
                                      :config-path (str (fs/path dir "shiftlefter.edn"))
                                      :step-paths [(str (fs/path dir "steps"))]
                                      :max-parallel max-parallel
                                      :internal-scenario-crash-on "KABOOM"
                                      :junit-xml junit-path
                                      :html html-path}
                                     extra-opts))]
        (cond-> run
          (fs/exists? junit-path) (assoc :xml (slurp junit-path))
          (fs/exists? html-path) (assoc :html (slurp html-path)))))))

(defn- portable-scenario
  "The crashed scenario's identity with the temp-dir prefix dropped."
  [scenario]
  (update scenario :source-file #(some-> % fs/file-name)))

(deftest scenario-escape-is-a-runner-crash-on-every-route
  (doseq [{:keys [route] :as r} routes]
    (testing route
      (let [{:keys [hung? result err xml html]} (run-route r {})]
        (is (not hung?) "the run returned — no coordinator hang")
        (when-not hung?
          (testing "exit 3 through the one crash seam"
            (is (= 3 (:exit-code result)))
            (is (= :crashed (:status result)))
            (is (= :runner/crash (get-in result [:error :type])))
            (is (= "clojure.lang.ExceptionInfo" (get-in result [:error :exception-class]))
                "the ORIGINAL throwable's class, not a wrapper's")
            (is (str/includes? err "Runner crash: sl-internal-crash: deliberate per-scenario escape")))
          (testing "the crash names the scenario"
            (is (= {:name "KABOOM marker scenario"
                    :source-file "escape.feature"
                    :line kaboom-line}
                   (-> result :error :scenario portable-scenario
                       (select-keys [:name :source-file :line]))))
            (is (re-find #"(?m)^\s+in scenario: KABOOM marker scenario \(.*escape\.feature:5\)$" err)
                "one added stderr line under 'Runner crash:' — name and file:line"))
          (testing "the crash markers are written"
            (is (not (str/includes? err "Crash marker not written")))
            (is (str/includes? (str xml) "<testsuite name=\"sl-crash\""))
            (is (str/includes? (str xml) "<error type=\"crash\""))
            (is (str/includes? (str html) ":status :crashed"))
            (is (not (str/includes? (str html) ":status :error"))
                "no fabricated scenario verdict for the crashed scenario"))
          (testing "sl-n6io: both file surfaces name the crashed scenario"
            (is (str/includes? (str xml)
                               "scenario: KABOOM marker scenario (features/escape.feature:5)")
                "the JUnit sl-crash body's scenario line, project-relative file:line")
            (is (re-find #":crash \{[^}]*:scenario \{:name \"KABOOM marker scenario\", :source-file \"[^\"]*escape\.feature\", :line 5\}"
                         (str html))
                "the HTML island's :crash record carries :scenario — what appendCrashLine renders")))))))

(deftest scenario-escape-reads-the-same-on-every-route
  (testing "class, message and the crashed scenario are route-independent"
    (let [reports (mapv (fn [r]
                          (let [{:keys [hung? result]} (run-route r {})]
                            (when-not hung?
                              (update (:error result) :scenario portable-scenario))))
                        routes)]
      (is (every? some? reports) "every route returned")
      (is (apply = reports)
          (str "the three routes' crash reports differ: " (pr-str reports)))
      (is (= "KABOOM marker scenario" (-> reports first :scenario :name))))))

(deftest scenario-escape-edn-crash-form-names-the-scenario
  (doseq [{:keys [route] :as r} routes]
    (testing route
      (let [{:keys [hung? result out]} (run-route r {:edn true})]
        (is (not hung?))
        (when-not hung?
          (let [form (last (read-forms out))]
            (is (= 3 (:exit-code result)))
            (is (= 3 (:run/exit-code form)) "the crash form still prints last")
            (is (= :crashed (:run/status form)))
            (is (= :runner/crash (get-in form [:error :type])))
            (is (= "KABOOM marker scenario" (get-in form [:error :scenario :name])))
            (is (= kaboom-line (get-in form [:error :scenario :line])))))))))
