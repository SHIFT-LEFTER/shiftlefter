(ns shiftlefter.runner.error-class-test
  "The step/adapter error-and-observation contract, end to end (sl-kowi,
   F15) — constructed repros at the runner seam, the dispatch's
   verification bar:

   (a) a stepdef throwing ex-info with `:error/class :observation` →
       scenario :error, counts {:unobserved 1}, exit 4; the SAME throw
       without the key → :failed, exit 1; :sut → :failed, exit 1 with the
       class on the step; an unrecognized value → identical to absent.
   (b) a mixed run (plain failure + observation) → exit 4, counts confess
       both buckets.
   (c) :halt-on-observation-failure — parallel: in-flight scenarios finish
       and keep their verdicts, everything unstarted (incl. the @serial
       tail) is :skipped, the :halted record and console line are present;
       sequential twin; default-off twin runs everything.
   (e) byte-stability: an error-free run's counts carry exactly the
       historical keys."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [shiftlefter.runner.core :as core]))

;; -----------------------------------------------------------------------------
;; Temp-project helpers (the verdict_contract_test idiom)
;; -----------------------------------------------------------------------------

(defn- with-temp-project [f]
  (let [dir (str (fs/create-temp-dir {:prefix "sl-error-class-test-"}))]
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
  "Run execute! with output captured; returns {:result :out :err}."
  [opts]
  (let [out (java.io.StringWriter.)
        err (java.io.StringWriter.)
        result (binding [*out* out *err* err]
                 (core/execute! (merge {:no-color true} opts)))]
    {:result result :out (str out) :err (str err)}))

(def ^:private steps-src
  "(ns kowi-steps
     (:require [shiftlefter.step :as step]
               [shiftlefter.stepengine.registry :refer [defstep]]))
   (defstep #\"a passing thing\" [] nil)
   (defstep #\"a failing thing\" [] (throw (ex-info \"designed failure\" {})))
   (defstep #\"an unobserved thing\" []
     (throw (ex-info \"connection refused\" {:error/class :observation})))
   (defstep #\"a sut error thing\" []
     (throw (ex-info \"HTTP 500\" {:error/class :sut :status 500})))
   (defstep #\"a mislabeled thing\" []
     (throw (ex-info \"mislabeled\" {:error/class :bogus})))
   (defstep #\"a slow unobserved thing\" []
     (Thread/sleep 150)
     (throw (ex-info \"went dark\" {:error/class :observation})))
   (defstep #\"a slow passing thing\" [] (Thread/sleep 700) nil)
   (defstep #\"a poll whose witness never answers\" [ctx]
     (step/poll-until ctx
       (fn [] (throw (ex-info \"connect refused\" {:type :t/refused :error/class :observation})))
       {:deadline-ms 60 :interval-ms 10
        :retry-error? #(= :t/refused (:type (ex-data %)))})
     ctx)
   (defstep #\"a poll that observes but never satisfies\" [ctx]
     (step/poll-until ctx (fn [] {:state \"running\" :pct 85})
       {:deadline-ms 60 :interval-ms 10 :until #(= \"done\" (:state %))})
     ctx)
   (defstep #\"a poll that answers then goes dark\" [ctx]
     (let [n (atom 0)]
       (step/poll-until ctx
         (fn [] (if (< (swap! n inc) 3)
                  {:state \"running\" :pct (* 40 @n)}
                  (throw (ex-info \"refused\" {:type :t/refused :error/class :observation}))))
         {:deadline-ms 80 :interval-ms 10
          :until #(= \"done\" (:state %))
          :retry-error? #(= :t/refused (:type (ex-data %)))}))
     ctx)")

(defn- project!
  [dir & {:keys [config feature]
          :or {config {:runner {:step-paths []}}}}]
  (spit-file dir "shiftlefter.edn" (pr-str config))
  (spit-file dir "steps/kowi.clj" steps-src)
  (spit-file dir "features/f.feature" feature))

(defn- base-opts [dir fpath]
  {:paths [fpath]
   :config-path (str (fs/path dir "shiftlefter.edn"))
   :step-paths [(str (fs/path dir "steps"))]})

(defn- feature [& scenarios]
  (str "Feature: F\n"
       (str/join (for [[name step & {:keys [tags]}] scenarios]
                   (str (when tags (str "  " tags "\n"))
                        "  Scenario: " name "\n    Given " step "\n")))))

(defn- scenario-statuses [result]
  (mapv :status (-> result :result :scenarios)))

;; -----------------------------------------------------------------------------
;; (a) the contract key, one throw at a time
;; -----------------------------------------------------------------------------

(deftest observation-class-flips-scenario-to-error-and-exits-4
  (with-temp-project
    (fn [dir]
      (let [fpath (project! dir :feature (feature ["Dark" "an unobserved thing"]))
            {:keys [result]} (run-captured (base-opts dir fpath))
            scenario (-> result :result :scenarios first)
            step (-> scenario :steps first)]
        (is (= 4 (:exit-code result)) "exit 4: the run proved nothing here")
        (is (= :degraded (:status result)))
        (is (= :error (:status scenario)) "scenario flips to :error")
        (is (= :failed (:status step)) "the step stays :failed — steps never carry :error")
        (is (= :observation (-> step :error :error/class)) "class rides the step error map")
        (is (= (:error step) (:error scenario))
            "the step's error map IS the scenario attribution (provisioning-flip envelope)")
        (is (= {:passed 0 :failed 0 :pending 0 :skipped 0 :unobserved 1}
               (:counts result))
            "counted :unobserved, NOT :error — disjoint buckets")))))

(deftest same-throw-without-the-key-is-todays-behavior
  (with-temp-project
    (fn [dir]
      (let [fpath (project! dir :feature (feature ["Plain" "a failing thing"]))
            {:keys [result]} (run-captured (base-opts dir fpath))
            step (-> result :result :scenarios first :steps first)]
        (is (= 1 (:exit-code result)))
        (is (= [:failed] (scenario-statuses result)))
        (is (not (contains? (:error step) :error/class)))
        (is (= {:passed 0 :failed 1 :pending 0 :skipped 0} (:counts result))
            "no :unobserved key on an unclassified failure")))))

(deftest sut-class-is-record-and-render-only
  (with-temp-project
    (fn [dir]
      (let [fpath (project! dir :feature (feature ["Five hundred" "a sut error thing"]))
            {:keys [result err]} (run-captured (base-opts dir fpath))
            step (-> result :result :scenarios first :steps first)]
        (is (= 1 (:exit-code result)) ":sut is product evidence — exit 1")
        (is (= [:failed] (scenario-statuses result)) "scenario stays :failed")
        (is (= :sut (-> step :error :error/class)) "class recorded on the step")
        (is (= {:passed 0 :failed 1 :pending 0 :skipped 0} (:counts result)))
        (is (str/includes? err "FAILED (SUT error)") "console names the class")
        (is (str/includes? err "class: sut") "the failure block carries the class line")))))

(deftest unrecognized-class-value-is-treated-as-absent
  (with-temp-project
    (fn [dir]
      (let [fpath (project! dir :feature (feature ["Bogus" "a mislabeled thing"]))
            {:keys [result]} (run-captured (base-opts dir fpath))
            step (-> result :result :scenarios first :steps first)]
        (is (= 1 (:exit-code result)))
        (is (= [:failed] (scenario-statuses result)))
        (is (not (contains? (:error step) :error/class))
            "an unrecognized value is not lifted")
        (is (= :bogus (-> step :error :data :error/class))
            "…but the raw ex-data still rides :data")))))

;; -----------------------------------------------------------------------------
;; Rider C (sl-afs9): the kernel inherits the class from a witness that never
;; answered — kowi Ruling 3b's structural tell, end to end
;; -----------------------------------------------------------------------------

(deftest poll-whose-witness-never-answered-goes-unobserved
  (with-temp-project
    (fn [dir]
      (let [fpath (project! dir :feature (feature ["Dark poll" "a poll whose witness never answers"]))
            {:keys [result]} (run-captured (base-opts dir fpath))
            step (-> result :result :scenarios first :steps first)]
        (is (= 4 (:exit-code result)) "every attempt threw a classified error → exit 4")
        (is (= [:error] (scenario-statuses result)))
        (is (= {:passed 0 :failed 0 :pending 0 :skipped 0 :unobserved 1} (:counts result)))
        (is (= :step/poll-timeout (-> step :error :data :type)) "it is the kernel's timeout…")
        (is (= :observation (-> step :error :error/class)) "…carrying the inherited class")
        (is (= :t/refused (-> step :error :data :last-error :type)))
        (is (not (contains? (-> step :error :data) :last-value)))))))

(deftest poll-that-answered-then-went-dark-stays-a-failure
  ;; sl-78u4 (Warden W2): ever-answered wins — two real observations then a
  ;; classified dark throw at the deadline is the witness having TALKED.
  ;; Before the fix the last attempt decided: exit 4, :last-value gone.
  (with-temp-project
    (fn [dir]
      (let [fpath (project! dir :feature (feature ["Flaky witness" "a poll that answers then goes dark"]))
            {:keys [result]} (run-captured (base-opts dir fpath))
            step (-> result :result :scenarios first :steps first)
            data (-> step :error :data)]
        (is (= 1 (:exit-code result)) "the witness answered — assertion territory, not exit 4")
        (is (= [:failed] (scenario-statuses result)))
        (is (not (contains? (:error step) :error/class)))
        (is (contains? data :last-value) "the answers are kept…")
        (is (= :t/refused (-> data :last-error :type)) "…beside the dark throw")
        (is (= 2 (-> data :poll-counts :answered)))
        (is (pos? (-> data :poll-counts :observation-throws)))))))

(deftest poll-that-observed-a-value-stays-a-failure
  (with-temp-project
    (fn [dir]
      (let [fpath (project! dir :feature (feature ["Slow job" "a poll that observes but never satisfies"]))
            {:keys [result]} (run-captured (base-opts dir fpath))
            step (-> result :result :scenarios first :steps first)]
        (is (= 1 (:exit-code result)) "the witness was answering — assertion territory")
        (is (= [:failed] (scenario-statuses result)))
        (is (= {:passed 0 :failed 1 :pending 0 :skipped 0} (:counts result)))
        (is (not (contains? (:error step) :error/class)))
        (is (str/includes? (str (-> step :error :data :last-value)) ":pct 85")
            "the last observation is the diagnosis")
        (is (not (contains? (-> step :error :data) :hint)) "an :until poll never hints")))))

;; -----------------------------------------------------------------------------
;; (b) mixed run — the counts confess both buckets
;; -----------------------------------------------------------------------------

(deftest mixed-failed-and-unobserved-exits-4-and-confesses-both
  (with-temp-project
    (fn [dir]
      (let [fpath (project! dir :feature (feature ["Plain" "a failing thing"]
                                                  ["Dark" "an unobserved thing"]
                                                  ["Fine" "a passing thing"]))
            {:keys [result err]} (run-captured (base-opts dir fpath))]
        (is (= 4 (:exit-code result)) "4 > 1: the degraded run subsumes the failure")
        (is (= [:failed :error :passed] (scenario-statuses result)))
        (is (= {:passed 1 :failed 1 :pending 0 :skipped 0 :unobserved 1}
               (:counts result)))
        (is (str/includes? err "3 scenario(s): 1 passed, 1 failed, 1 unobserved")
            "the summary line renders unobserved between failed and error")
        (is (str/includes? err "UNOBSERVED Dark") "the status line is distinct from FAILED")
        (is (not (str/includes? err "halted after")) "no halt without the policy")))))

;; -----------------------------------------------------------------------------
;; (c) the halt policy
;; -----------------------------------------------------------------------------

(def ^:private halt-feature
  ;; Plan order A..E. Under :max-parallel 2, A and B start together; A
  ;; goes dark at ~150ms and arms the gate; C then D would start on the
  ;; freed thread and instead return skipped; B finishes at ~700ms and
  ;; keeps its verdict; E is the @serial tail, skipped after the pool
  ;; drains.
  (feature ["A" "a slow unobserved thing"]
           ["B" "a slow passing thing"]
           ["C" "a passing thing"]
           ["D" "a passing thing"]
           ["E" "a passing thing" :tags "@serial"]))

(deftest halt-parallel-keeps-in-flight-verdicts-and-skips-the-rest
  (with-temp-project
    (fn [dir]
      (let [fpath (project! dir
                            :config {:runner {:step-paths []
                                              :max-parallel 2
                                              :halt-on-observation-failure true}}
                            :feature halt-feature)
            {:keys [result err]} (run-captured (base-opts dir fpath))]
        (is (= 4 (:exit-code result)) "exit 4 regardless of the halt")
        (is (= [:error :passed :skipped :skipped :skipped] (scenario-statuses result))
            "A unobserved; B in flight, verdict KEPT; C/D unstarted; E the serial tail")
        (is (= {:passed 1 :failed 0 :pending 0 :skipped 3 :unobserved 1}
               (:counts result)))
        (is (= {:after-scenario 1 :scenario "A"} (-> result :result :halted))
            "the halt record names the triggering scenario (1-based plan index)")
        (is (every? #(= :skipped (:status %))
                    (-> result :result :scenarios (nth 2) :steps))
            "a halted scenario's steps are all :skipped")
        (is (str/includes? err "halted after scenario 1 (A): observation failure")
            "the console summary carries the halt line")
        (is (str/includes? err "5 scenario(s): 1 passed, 1 unobserved, 3 skipped"))))))

(deftest halt-sequential-skips-everything-after-the-trigger
  (with-temp-project
    (fn [dir]
      (let [fpath (project! dir
                            :config {:runner {:step-paths []
                                              :halt-on-observation-failure true}}
                            :feature (feature ["One" "a passing thing"]
                                              ["Two" "an unobserved thing"]
                                              ["Three" "a passing thing"]
                                              ["Four" "a failing thing"]))
            {:keys [result out]} (run-captured (assoc (base-opts dir fpath) :edn true))
            summary (edn/read-string out)]
        (is (= 4 (:exit-code result)))
        (is (= [:passed :error :skipped :skipped] (scenario-statuses result))
            "Four never ran — a failure that was never measured is :skipped, not :failed")
        (is (= {:passed 1 :failed 0 :pending 0 :skipped 2 :unobserved 1}
               (:counts result)))
        (is (= {:after-scenario 2 :scenario "Two"} (-> result :result :halted)))
        (is (= {:after-scenario 2 :scenario "Two"} (:halted summary))
            "the --edn summary carries the halt record")
        (is (= 1 (-> summary :counts :unobserved)))
        (is (= :observation
               (->> (:failures summary) (keep #(-> % :error :error/class)) first))
            "the failure entry carries the class")))))

(deftest halt-default-off-grinds-on
  (with-temp-project
    (fn [dir]
      (let [fpath (project! dir
                            :config {:runner {:step-paths [] :max-parallel 2}}
                            :feature halt-feature)
            {:keys [result err]} (run-captured (base-opts dir fpath))]
        (is (= 4 (:exit-code result)))
        (is (= [:error :passed :passed :passed :passed] (scenario-statuses result))
            "everything runs; the environment may recover mid-run")
        (is (nil? (-> result :result :halted)))
        (is (not (str/includes? err "halted after")))))))

;; -----------------------------------------------------------------------------
;; (c') the halt policy across setup GROUPS — tri-state (sl-78u4, Warden W3)
;; -----------------------------------------------------------------------------

;; :start probe log — the setup.clj under test records which groups STARTED,
;; so "charlie's :start never ran" is asserted from data.
(defonce halt-log (atom []))

(defn ^:export record-start! [tag] (swap! halt-log conj tag))

(def ^:private halt-setup-src
  "(ns setup
     (:require [shiftlefter.runner.error-class-test :as t]))
   (def setups
     [{:label \"alpha\"
       :start (fn [_] (t/record-start! :alpha) {})
       :features [\"features/alpha.feature\"]}
      {:label \"bravo\"
       :start (fn [_] (t/record-start! :bravo) {})
       :features [\"features/bravo.feature\"]}
      {:label \"charlie\"
       :start (fn [_] (t/record-start! :charlie) {})
       :features [\"features/charlie.feature\"]}])")

(defn- halt-groups-project!
  "Three groups: alpha passes; bravo goes dark (then has a second scenario
   its own halt skips); charlie passes. `policy` is the config value."
  [dir policy]
  (spit-file dir "shiftlefter.edn"
             (pr-str {:runner {:step-paths []
                               :halt-on-observation-failure policy}}))
  (spit-file dir "steps/kowi.clj" steps-src)
  (spit-file dir "setup.clj" halt-setup-src)
  (spit-file dir "features/alpha.feature" (feature ["A1" "a passing thing"]))
  (spit-file dir "features/bravo.feature" (feature ["B1" "an unobserved thing"]
                                                   ["B2" "a passing thing"]))
  (spit-file dir "features/charlie.feature" (feature ["C1" "a passing thing"])))

(defn- run-groups [dir & {:keys [edn]}]
  (reset! halt-log [])
  (run-captured (cond-> {:paths []
                         :config-path (str (fs/path dir "shiftlefter.edn"))
                         :step-paths [(str (fs/path dir "steps"))]}
                  edn (assoc :edn true))))

(defn- forms [out]
  (let [rdr (java.io.PushbackReader. (java.io.StringReader. out))]
    (loop [acc []]
      (let [f (edn/read {:eof ::eof} rdr)]
        (if (= ::eof f) acc (recur (conj acc f)))))))

(deftest halt-run-scope-skips-the-groups-not-yet-started
  (with-temp-project
    (fn [dir]
      (halt-groups-project! dir :run)
      (let [{:keys [result out]} (run-groups dir :edn true)
            agg (last (forms out))
            [alpha bravo charlie] (:groups result)]
        (is (= 4 (:exit-code result)) "the halted group's 4 is the invocation verdict")
        (is (= [:alpha :bravo] @halt-log) "charlie's :start NEVER ran")
        (is (= :skipped (:status charlie)))
        (is (not (contains? charlie :exit-code)) "no verdict exists for a group that never ran")
        (is (= {:after-scenario 1 :scenario "B1"} (-> bravo :result :halted))
            "the dark group's own summary carries the record")
        (is (= [:error :skipped] (mapv :status (-> bravo :result :scenarios)))
            "bravo's own tail is halted-skipped too")
        (is (= 0 (:exit-code alpha)))
        (testing "the invocation aggregate: :halted with :group/label, a
                  :skipped row with NO :run/exit-code, exit 4"
          (is (= 4 (:invocation/exit-code agg)))
          (is (= :degraded (:invocation/status agg)))
          (is (= {:after-scenario 1 :scenario "B1" :group/label "bravo"} (:halted agg)))
          (is (= {:group/label "charlie" :group/index 2 :run/status :skipped}
                 (nth (:invocation/groups agg) 2)))
          (is (= [0 4] (mapv :run/exit-code (take 2 (:invocation/groups agg))))))
        (is (= {:after-scenario 1 :scenario "B1" :group/label "bravo"} (:halted result))
            "the in-process result carries it too")))))

(deftest halt-run-scope-console-names-the-skipped-group
  (with-temp-project
    (fn [dir]
      (halt-groups-project! dir :run)
      (let [{:keys [err]} (run-groups dir)]
        (is (str/includes? err "Setup group \"charlie\": skipped (halted)"))
        (is (str/includes? err "halted after scenario 1 (B1): observation failure"))))))

(deftest halt-group-scope-lets-siblings-run
  (with-temp-project
    (fn [dir]
      (halt-groups-project! dir :group)
      (let [{:keys [result out]} (run-groups dir :edn true)
            agg (last (forms out))
            [_ bravo charlie] (:groups result)]
        (is (= 4 (:exit-code result)))
        (is (= [:alpha :bravo :charlie] @halt-log) "every group started")
        (is (= 0 (:exit-code charlie)) "charlie ran to completion")
        (is (= [:error :skipped] (mapv :status (-> bravo :result :scenarios)))
            "the dark group still halts its OWN queue")
        (is (= {:after-scenario 1 :scenario "B1"} (-> bravo :result :halted)))
        (is (not (contains? agg :halted)) "no invocation-level record under :group")
        (is (= [:passed :degraded :passed] (mapv :run/status (:invocation/groups agg))))))))

(deftest halt-false-grinds-on-across-groups
  (with-temp-project
    (fn [dir]
      (halt-groups-project! dir false)
      (let [{:keys [result out]} (run-groups dir :edn true)
            [_ bravo charlie] (:groups result)]
        (is (= 4 (:exit-code result)))
        (is (= [:alpha :bravo :charlie] @halt-log))
        (is (= [:error :passed] (mapv :status (-> bravo :result :scenarios)))
            "nothing halts: B2 runs")
        (is (nil? (-> bravo :result :halted)))
        (is (= 0 (:exit-code charlie)))
        (is (not (contains? (last (forms out)) :halted)))))))

(deftest halt-true-reads-as-run-scope
  (with-temp-project
    (fn [dir]
      (halt-groups-project! dir true)
      (let [{:keys [result]} (run-groups dir)]
        (is (= [:alpha :bravo] @halt-log) "true ⇒ :run — every pre-tri-state doc sentence stays true")
        (is (= :skipped (:status (nth (:groups result) 2))))))))

;; -----------------------------------------------------------------------------
;; (e) byte-stability of the clean run
;; -----------------------------------------------------------------------------

(deftest clean-run-counts-carry-only-the-historical-keys
  (with-temp-project
    (fn [dir]
      (let [fpath (project! dir :feature (feature ["Fine" "a passing thing"]))
            {:keys [result out]} (run-captured (assoc (base-opts dir fpath) :edn true))
            summary (edn/read-string out)]
        (is (= 0 (:exit-code result)))
        (is (= #{:passed :failed :pending :skipped} (set (keys (:counts result)))))
        (is (= #{:passed :failed :pending :skipped :scenarios :steps}
               (set (keys (:counts summary))))
            "--edn counts: no :unobserved, no :error, no :halted on a clean run")
        (is (not (contains? summary :halted)))))))
