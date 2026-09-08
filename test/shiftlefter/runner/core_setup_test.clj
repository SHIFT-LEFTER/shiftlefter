(ns shiftlefter.runner.core-setup-test
  "End-to-end test of the setup.clj orchestration branch.

   Wires runner.core/execute! against a temp project containing a
   shiftlefter.edn, a setup.clj, and a feature with a trivial built-in
   step. Verifies that:

   1. setup.clj is auto-detected when sibling-of-config.
   2. :start fires before scenarios run.
   3. Custom :adapter-registry from setup.clj reaches binding (a stepdef
      that requires a non-default protocol successfully binds).
   4. :stop fires after the group's scenarios complete (including on pass)."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :as stest]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [shiftlefter.runner.core :as core]
            [shiftlefter.events :as events]
            [shiftlefter.runner.setup :as rsetup]))

;; -----------------------------------------------------------------------------
;; Temp-project helpers
;; -----------------------------------------------------------------------------

(defn- with-temp-project [f]
  (let [dir (str (fs/create-temp-dir {:prefix "sl-core-setup-test-"}))]
    (try
      (f dir)
      (finally
        (fs/delete-tree dir)))))

(defn- spit-file [dir relative content]
  (let [target (fs/path dir relative)]
    (fs/create-dirs (fs/parent target))
    (spit (str target) content)
    (str target)))

;; -----------------------------------------------------------------------------
;; Lifecycle observation hook
;;
;; The setup.clj loaded into the test project records timestamps to this
;; atom so we can assert :start / :stop fired.
;; -----------------------------------------------------------------------------

(defonce ^:private lifecycle-events (atom []))

(defn ^:export record-event! [tag] (swap! lifecycle-events conj tag))

;; Public (not ^:private): the G2 weave fixture's setup.clj hands this atom
;; to the mock adapter registry, which records [:provision x]/[:cleanup x]
;; tuples into it alongside our keyword events — one totally-ordered log.
(defonce weave-log (atom []))

(defn ^:export record-weave! [tag] (swap! weave-log conj tag))

(defn- run-captured
  "Run execute! capturing stdout+stderr; returns {:result :out :err}."
  [opts]
  (let [out (java.io.StringWriter.)
        err (java.io.StringWriter.)
        result (binding [*out* out *err* err]
                 (core/execute! (merge {:no-color true} opts)))]
    {:result result :out (str out) :err (str err)}))

(defn- read-forms
  "All EDN forms on a stdout string, in order."
  [out]
  (let [rdr (java.io.PushbackReader. (java.io.StringReader. out))]
    (loop [acc []]
      (let [form (edn/read {:eof ::eof} rdr)]
        (if (= ::eof form) acc (recur (conj acc form)))))))

;; -----------------------------------------------------------------------------
;; Tests
;; -----------------------------------------------------------------------------

(deftest setup-clj-lifecycle-fires-around-group
  (testing "setup.clj's :start runs before scenarios, :stop after"
    (with-temp-project
      (fn [dir]
        (reset! lifecycle-events [])
        (spit-file dir "shiftlefter.edn"
                   (pr-str {:runner {:step-paths []}}))

        (spit-file dir "features/probe.feature"
                   "Feature: Probe
  Scenario: Trivial
    Given a trivial assertion
")

        (spit-file dir "steps/probe.clj"
                   "(ns probe-steps
                      (:require [shiftlefter.stepengine.registry :refer [defstep]]))
                    (defstep #\"a trivial assertion\" [] nil)")

        (spit-file dir "setup.clj"
                   "(ns setup
                      (:require [shiftlefter.runner.core-setup-test :as t]))
                    (defn- start-fn [_config]
                      (t/record-event! :start)
                      {:stop (fn [] (t/record-event! :stop))})
                    (def setups
                      [{:label \"probe-group\"
                        :start start-fn
                        :features [\"features/probe.feature\"]}])")

        (let [result (core/execute!
                      {:paths       []
                       :config-path (str (fs/path dir "shiftlefter.edn"))
                       :step-paths  [(str (fs/path dir "steps"))]})]
          (is (= 0 (:exit-code result))
              (str "Suite should pass; got " result))
          (is (= [:start :stop] @lifecycle-events)
              ":start fires before, :stop after"))))))

(deftest setup-clj-stop-runs-on-failure
  (testing ":stop is invoked even when a scenario fails"
    (with-temp-project
      (fn [dir]
        (reset! lifecycle-events [])
        (spit-file dir "shiftlefter.edn" (pr-str {:runner {:step-paths []}}))
        (spit-file dir "features/fail.feature"
                   "Feature: Failing
  Scenario: This will fail
    Given an assertion that throws
")
        (spit-file dir "steps/fail.clj"
                   "(ns fail-steps
                      (:require [shiftlefter.stepengine.registry :refer [defstep]]))
                    (defstep #\"an assertion that throws\" []
                      (throw (ex-info \"nope\" {})))")
        (spit-file dir "setup.clj"
                   "(ns setup
                      (:require [shiftlefter.runner.core-setup-test :as t]))
                    (defn- start-fn [_]
                      (t/record-event! :start)
                      {:stop (fn [] (t/record-event! :stop))})
                    (def setups
                      [{:label \"fail-group\"
                        :start start-fn
                        :features [\"features/fail.feature\"]}])")

        (let [result (core/execute!
                      {:paths       []
                       :config-path (str (fs/path dir "shiftlefter.edn"))
                       :step-paths  [(str (fs/path dir "steps"))]})]
          (is (= 1 (:exit-code result)))
          (is (= [:start :stop] @lifecycle-events)
              ":stop must run even on scenario failure"))))))

(deftest multi-group-run-keeps-bus-open-across-groups
  ;; sl-4tsi lifecycle half: the bus used to be closed by group 1's
  ;; execute-and-report-stage, so groups 2..N published into a closed channel
  ;; (silent counted drops). Close is now hoisted to execute!'s suite-level
  ;; finally. This is also the suite's first genuine N>1 group execution
  ;; test — the groups loop was previously never exercised past N=1
  ;; (sl-oobu deferred N>1 semantics; note for its eventual work: this
  ;; scaffold exists).
  (with-temp-project
    (fn [dir]
      (reset! lifecycle-events [])
      (spit-file dir "shiftlefter.edn" (pr-str {:runner {:step-paths []}}))
      (spit-file dir "features/one.feature"
                 "Feature: One\n  Scenario: A\n    Given a trivial assertion\n")
      (spit-file dir "features/two.feature"
                 "Feature: Two\n  Scenario: B\n    Given a trivial assertion\n")
      (spit-file dir "steps/probe.clj"
                 "(ns probe-steps
                    (:require [shiftlefter.stepengine.registry :refer [defstep]]))
                  (defstep #\"a trivial assertion\" [] nil)")
      (spit-file dir "setup.clj"
                 "(ns setup
                    (:require [shiftlefter.runner.core-setup-test :as t]))
                  (def setups
                    [{:label \"group-one\"
                      :start (fn [_] (t/record-event! :start-1)
                               {:stop (fn [] (t/record-event! :stop-1))})
                      :features [\"features/one.feature\"]}
                     {:label \"group-two\"
                      :start (fn [_] (t/record-event! :start-2)
                               {:stop (fn [] (t/record-event! :stop-2))})
                      :features [\"features/two.feature\"]}])")
      (let [seen (atom [])
            captured-bus (atom nil)
            make-bus events/make-memory-bus
            result (with-redefs [events/make-memory-bus
                                 (fn []
                                   (let [b (make-bus)]
                                     (reset! captured-bus b)
                                     (events/subscribe! b #(swap! seen conj %))
                                     b))]
                     (core/execute!
                      {:paths       []
                       :config-path (str (fs/path dir "shiftlefter.edn"))
                       :step-paths  [(str (fs/path dir "steps"))]}))]
        (is (= 0 (:exit-code result)) (str "both groups should pass; got " result))
        (is (= [:start-1 :stop-1 :start-2 :stop-2] @lifecycle-events)
            "groups run sequentially, each with its own lifecycle")
        (testing "one tap sees BOTH groups' full streams (previously group 2 dropped)"
          (is (= 2 (count (filter #(= :test-run/started (:type %)) @seen)))
              "one :test-run/started pair per group (Tower R1 run-scope)")
          (is (= 2 (count (filter #(= :test-run/finished (:type %)) @seen))))
          (is (= 2 (count (filter #(= :scenario/finished (:type %)) @seen)))
              "scenario events from both groups"))
        (testing "run-scope pairs arrive in group order on one totally-ordered bus"
          (is (= [:test-run/started :test-run/finished
                  :test-run/started :test-run/finished]
                 (->> @seen
                      (map :type)
                      (filter #{:test-run/started :test-run/finished})))))
        (testing "zero drops — nothing published into a closed channel"
          (is (zero? (events/dropped-events @captured-bus))))
        (testing "the bus IS closed once execute! returns (suite finally)"
          (events/publish! @captured-bus {:type :probe/after-return :payload {}})
          (is (= 1 (events/dropped-events @captured-bus))
              "publish after return is a counted drop — the bus was closed"))))))

(deftest junit-x-groups-aggregates-across-groups
  ;; sl-xjx0 (Warden W7): the JUnit path is invocation-fixed but reporters
  ;; were per-group — each group's :run-end atomically REPLACED the file, so
  ;; a red group 1 vanished behind a green group 2: all-green XML beside a
  ;; nonzero exit, breaking the ERRATA invariant (>=1 <failure>/<error> iff
  ;; exit nonzero). Groups now share one invocation state; the final file
  ;; carries every group. The setup.clj is load-bearing (sl-8cvd P2-1):
  ;; without it this is a single implicit group, :junit-shared-state is
  ;; never created, and every assertion passes with the fix reverted.
  (with-temp-project
    (fn [dir]
      (spit-file dir "shiftlefter.edn" (pr-str {:runner {:step-paths []}}))
      (spit-file dir "features/red.feature"
                 "Feature: Red\n  Scenario: fails\n    Given a deliberate failure\n")
      (spit-file dir "features/green.feature"
                 "Feature: Green\n  Scenario: passes\n    Given a trivial assertion\n")
      (spit-file dir "steps/probe.clj"
                 "(ns probe-steps
                    (:require [shiftlefter.stepengine.registry :refer [defstep]]))
                  (defstep #\"a trivial assertion\" [] nil)
                  (defstep #\"a deliberate failure\" []
                    (throw (ex-info \"boom\" {:type :test/deliberate})))")
      (spit-file dir "setup.clj"
                 "(ns setup)
                  (def setups
                    [{:label \"red-group\"
                      :start (fn [_] nil)
                      :features [\"features/red.feature\"]}
                     {:label \"green-group\"
                      :start (fn [_] nil)
                      :features [\"features/green.feature\"]}])")
      (let [xml-path (str (fs/path dir "out.xml"))
            result (core/execute!
                    {:paths       []
                     :config-path (str (fs/path dir "shiftlefter.edn"))
                     :step-paths  [(str (fs/path dir "steps"))]
                     :junit-xml   xml-path
                     :no-color    true})
            xml (slurp xml-path)]
        (is (= 1 (:exit-code result)) "red group 1 + green group 2 => exit 1")
        (is (= 2 (count (:groups result)))
            "two REAL groups ran — the multi-group shared-state path, not
             the single-implicit-group branch (sl-8cvd P2-1)")
        (testing "the ERRATA invariant: >=1 <failure>/<error> iff exit nonzero"
          (is (or (str/includes? xml "<failure")
                  (str/includes? xml "<error"))
              "group 1's failure survives group 2's write"))
        (testing "the final document carries BOTH groups' scenarios"
          (is (str/includes? xml "Feature: Red"))
          (is (str/includes? xml "Feature: Green"))
          (is (= 2 (count (re-seq #"<testcase" xml)))))))))

(deftest cli-path-not-declared-is-planning-error
  (testing "CLI path outside the declared union surfaces a planning error"
    (with-temp-project
      (fn [dir]
        (reset! lifecycle-events [])
        (spit-file dir "shiftlefter.edn" (pr-str {:runner {:step-paths []}}))
        (spit-file dir "features/declared.feature"
                   "Feature: D\n  Scenario: x\n    Given a step\n")
        (spit-file dir "features/undeclared.feature"
                   "Feature: U\n  Scenario: x\n    Given a step\n")
        (spit-file dir "steps/probe.clj"
                   "(ns p (:require [shiftlefter.stepengine.registry :refer [defstep]]))
                    (defstep #\"a step\" [] nil)")
        (spit-file dir "setup.clj"
                   "(ns setup)
                    (defn- start-fn [_] {})
                    (def setups
                      [{:label \"only-declared\"
                        :start start-fn
                        :features [\"features/declared.feature\"]}])")

        (let [result (core/execute!
                      {:paths       [(str (fs/path dir "features/undeclared.feature"))]
                       :config-path (str (fs/path dir "shiftlefter.edn"))
                       :step-paths  [(str (fs/path dir "steps"))]})]
          (is (= 2 (:exit-code result))
              "Undeclared CLI path should fail planning"))))))

;; -----------------------------------------------------------------------------
;; Dry-run is a pure plan preview (sl-ev0b)
;; -----------------------------------------------------------------------------

(defn- spit-probe-project!
  "The minimal setup-mode project: one feature, one step, one group whose
   :start RECORDS then THROWS — so any code path that invokes it under
   dry-run turns the preview red. Returns dir."
  [dir]
  (spit-file dir "shiftlefter.edn" (pr-str {:runner {:step-paths []}}))
  (spit-file dir "features/probe.feature"
             "Feature: Probe\n  Scenario: Trivial\n    Given a trivial assertion\n")
  (spit-file dir "steps/probe.clj"
             "(ns probe-steps
                (:require [shiftlefter.stepengine.registry :refer [defstep]]))
              (defstep #\"a trivial assertion\" [] nil)")
  (spit-file dir "setup.clj"
             "(ns setup
                (:require [shiftlefter.runner.core-setup-test :as t]))
              (defn- start-fn [_config]
                (t/record-event! :start)
                (throw (ex-info \"fixture server must not spawn in dry-run\" {})))
              (def setups
                [{:label \"probe-group\"
                  :start start-fn
                  :features [\"features/probe.feature\"]}])")
  dir)

(defn- dry-run! [dir opts]
  (let [out (java.io.StringWriter.)
        err (java.io.StringWriter.)
        result (binding [*out* out *err* err]
                 (core/execute!
                  (merge {:paths []
                          :config-path (str (fs/path dir "shiftlefter.edn"))
                          :step-paths [(str (fs/path dir "steps"))]
                          :dry-run true
                          :no-color true}
                         opts)))]
    {:result result :out (str out) :err (str err)}))

(deftest dry-run-executes-no-user-lifecycle-code
  (testing "AC1/AC3: dry-run never invokes :start/:stop — a THROWING :start
            still yields a green preview, proving it was never called"
    (with-temp-project
      (fn [dir]
        (reset! lifecycle-events [])
        (spit-probe-project! dir)
        (let [{:keys [result err]} (dry-run! dir {})]
          (is (= 0 (:exit-code result))
              (str "dry-run must preview, not execute; got " result))
          (is (empty? @lifecycle-events) "no user lifecycle code ran")
          (is (str/includes?
               err "[probe-group] 1 scenario(s) bound successfully (dry run)")
              "the per-group plan line still prints"))))))

(deftest dry-run-lists-hooks-without-firing-them
  (testing "the sl-esq hooks preview survives; Befores never execute"
    (with-temp-project
      (fn [dir]
        (reset! lifecycle-events [])
        (spit-probe-project! dir)
        (spit-file dir "hooks.clj"
                   "(ns hooks
                      (:require [shiftlefter.runner.core-setup-test :as t]))
                    (def hooks
                      [{:name \"audit\"
                        :global? true
                        :before (fn [_payload] (t/record-event! :before-hook))}])")
        (let [{:keys [result err]} (dry-run! dir {})]
          (is (= 0 (:exit-code result)))
          (is (empty? @lifecycle-events) "neither :start nor any Before fired")
          (is (str/includes? err "hooks:") "hooks preview block prints")
          (is (str/includes? err "Trivial: audit")
              "hook listed against its scenario, execution order"))))))

(deftest dry-run-edn-summary-in-setup-mode-is-side-effect-free
  (with-temp-project
    (fn [dir]
      (reset! lifecycle-events [])
      (spit-probe-project! dir)
      (let [{:keys [result out]} (dry-run! dir {:edn true})
            summary (edn/read-string out)]
        (is (= 0 (:exit-code result)))
        (is (empty? @lifecycle-events))
        (is (= :dry-run (:run/status summary)))
        (is (= "probe-group" (:group summary)))
        (is (= 1 (-> summary :counts :scenarios)))))))

(deftest no-setup-file-uses-classic-pipeline
  (testing "Without setup.clj, runner falls back to discovery + single-suite path"
    (with-temp-project
      (fn [dir]
        (reset! lifecycle-events [])
        (spit-file dir "shiftlefter.edn" (pr-str {:runner {:step-paths []}}))
        (spit-file dir "features/probe.feature"
                   "Feature: P\n  Scenario: x\n    Given a step\n")
        (spit-file dir "steps/probe.clj"
                   "(ns p (:require [shiftlefter.stepengine.registry :refer [defstep]]))
                    (defstep #\"a step\" [] nil)")
        (let [result (core/execute!
                      {:paths       [(str (fs/path dir "features/probe.feature"))]
                       :config-path (str (fs/path dir "shiftlefter.edn"))
                       :step-paths  [(str (fs/path dir "steps"))]})]
          (is (= 0 (:exit-code result))
              "No setup.clj → today's pipeline runs unchanged")
          (is (empty? @lifecycle-events) "No setup means no lifecycle"))))))

;; -----------------------------------------------------------------------------
;; sl-1h03: :start failures must speak in EDN mode (the last known EDN-silence
;; lie). Both branches ride report-planning-error!; the planning block carries
;; :type + :group + :message under :planning :errors.
;; -----------------------------------------------------------------------------

(defn- write-throwing-start-project!
  "Temp project whose single group's :start throws. Returns opts for execute!."
  [dir]
  (spit-file dir "shiftlefter.edn" (pr-str {:runner {:step-paths []}}))
  (spit-file dir "features/probe.feature"
             "Feature: P\n  Scenario: x\n    Given a trivial assertion\n")
  (spit-file dir "steps/probe.clj"
             "(ns probe-steps
                (:require [shiftlefter.stepengine.registry :refer [defstep]]))
              (defstep #\"a trivial assertion\" [] nil)")
  (spit-file dir "setup.clj"
             "(ns setup)
              (def setups
                [{:label \"boom-group\"
                  :start (fn [_] (throw (ex-info \"kaboom\" {})))
                  :features [\"features/probe.feature\"]}])")
  {:paths       []
   :config-path (str (fs/path dir "shiftlefter.edn"))
   :step-paths  [(str (fs/path dir "steps"))]})

(deftest start-throw-emits-edn-planning-summary
  (testing "a group whose :start throws emits an exit-2 planning summary in
            EDN mode — previously NOTHING reached stdout (sl-1h03)"
    (with-temp-project
      (fn [dir]
        (let [opts (write-throwing-start-project! dir)
              result-box (volatile! nil)
              out (with-out-str
                    (vreset! result-box (core/execute! (assoc opts :edn true))))]
          (is (= 2 (:exit-code @result-box)))
          (is (seq (str/trim out)) "EDN mode must not be silent (the lie)")
          (let [summary (edn/read-string out)
                errs (get-in summary [:planning :errors])]
            (is (= 2 (:run/exit-code summary)))
            (is (= :planning-failed (:run/status summary)))
            (is (some #(and (= :setup/start-threw (:type %))
                            (= "boom-group" (:group %))
                            (str/includes? (or (:message %) "") "kaboom")
                            ;; sl-yvaz teaching tail (mmfy G1b): partial-start
                            ;; cleanup is the user's.
                            (str/includes? (or (:message %) "")
                                           "yours to clean up"))
                      errs)
                (str ":planning :errors carries type+group+message+tail; got "
                     (pr-str errs)))))))))

(deftest invalid-start-result-emits-edn-planning-summary
  (testing "an invalid :start return emits an exit-2 planning summary with a
            real :message (previously the error map was bare, sl-1h03)"
    (with-temp-project
      (fn [dir]
        (spit-file dir "shiftlefter.edn" (pr-str {:runner {:step-paths []}}))
        (spit-file dir "features/probe.feature"
                   "Feature: P\n  Scenario: x\n    Given a trivial assertion\n")
        (spit-file dir "steps/probe.clj"
                   "(ns probe-steps2
                      (:require [shiftlefter.stepengine.registry :refer [defstep]]))
                    (defstep #\"a trivial assertion\" [] nil)")
        (spit-file dir "setup.clj"
                   "(ns setup)
                    (def setups
                      [{:label \"bad-shape\"
                        :start (fn [_] 42)
                        :features [\"features/probe.feature\"]}])")
        (let [opts {:paths []
                    :config-path (str (fs/path dir "shiftlefter.edn"))
                    :step-paths [(str (fs/path dir "steps"))]}
              result-box (volatile! nil)
              out (with-out-str
                    (vreset! result-box (core/execute! (assoc opts :edn true))))
              summary (edn/read-string out)
              errs (get-in summary [:planning :errors])]
          (is (= 2 (:exit-code @result-box)))
          (is (some #(and (= :setup/invalid-start-result (:type %))
                          (= "bad-shape" (:group %))
                          (str/includes? (or (:message %) "") "invalid shape"))
                    errs)
              (pr-str errs)))))))

(deftest start-throw-console-parity
  (testing "console mode: same stderr line as before the fix, no stdout EDN"
    (with-temp-project
      (fn [dir]
        (let [opts (write-throwing-start-project! dir)
              err-w (java.io.StringWriter.)
              out (with-out-str
                    (binding [*err* err-w]
                      (core/execute! opts)))]
          (is (str/includes? (str err-w)
                             "Setup group \"boom-group\" :start threw: kaboom")
              "stderr head wording unchanged")
          (is (str/includes? (str err-w) "wrap your own try/catch")
              "sl-yvaz: the teaching tail reaches the console too")
          (is (not (str/includes? out ":run/exit-code"))
              "no EDN on stdout in console mode"))))))

(deftest multi-group-start-failure-and-healthy-group-both-speak
  (testing "group 1's :start throws, group 2 runs: stdout carries BOTH the
            planning form AND the healthy group's run summary (Tower rider —
            the fix must not trade one silence for another)"
    (with-temp-project
      (fn [dir]
        (reset! lifecycle-events [])
        (spit-file dir "shiftlefter.edn" (pr-str {:runner {:step-paths []}}))
        (spit-file dir "features/one.feature"
                   "Feature: One\n  Scenario: A\n    Given a trivial assertion\n")
        (spit-file dir "features/two.feature"
                   "Feature: Two\n  Scenario: B\n    Given a trivial assertion\n")
        (spit-file dir "steps/probe.clj"
                   "(ns probe-steps3
                      (:require [shiftlefter.stepengine.registry :refer [defstep]]))
                    (defstep #\"a trivial assertion\" [] nil)")
        (spit-file dir "setup.clj"
                   "(ns setup
                      (:require [shiftlefter.runner.core-setup-test :as t]))
                    (def setups
                      [{:label \"boom-group\"
                        :start (fn [_] (throw (ex-info \"kaboom\" {})))
                        :features [\"features/one.feature\"]}
                       {:label \"ok-group\"
                        :start (fn [_] (t/record-event! :ok-start) {})
                        :features [\"features/two.feature\"]}])")
        (let [opts {:paths []
                    :config-path (str (fs/path dir "shiftlefter.edn"))
                    :step-paths [(str (fs/path dir "steps"))]}
              result-box (volatile! nil)
              out (with-out-str
                    (vreset! result-box (core/execute! (assoc opts :edn true))))
              forms (let [rdr (java.io.PushbackReader.
                               (java.io.StringReader. out))]
                      (loop [acc []]
                        (let [form (edn/read {:eof ::eof} rdr)]
                          (if (= ::eof form) acc (recur (conj acc form))))))]
          (is (= 2 (:exit-code @result-box)) "suite exit aggregates the 2")
          (is (= [:ok-start] @lifecycle-events)
              "healthy group still executed after the failing one")
          ;; 3 forms since sl-pwbp: the invocation aggregate form follows
          ;; the two group summaries (pin moved with the ruled change).
          (is (= 3 (count forms)) (str "THREE EDN forms on stdout; got " out))
          (is (some #(and (= 2 (:run/exit-code %))
                          (some (fn [e] (= "boom-group" (:group e)))
                                (get-in % [:planning :errors])))
                    forms)
              "the failing group's planning form is present")
          (is (some #(and (= 0 (:run/exit-code %))
                          (= 1 (get-in % [:counts :passed])))
                    forms)
              "the healthy group's run summary is present alongside"))))))

(deftest single-group-edn-stream-carries-no-group-identity-or-aggregate
  (testing "the sl-pwbp absent-unless-multi contract, asserted explicitly:
            a single-group setup.clj --edn stream is byte-shape-identical
            to the pre-pwbp stream — one summary form, no :group/label or
            :group/index, no invocation aggregate form (its lone summary's
            :run/exit-code IS the suite verdict)"
    (with-temp-project
      (fn [dir]
        (spit-file dir "shiftlefter.edn" (pr-str {:runner {:step-paths []}}))
        (spit-file dir "features/solo.feature"
                   "Feature: Solo\n  Scenario: Solo passes\n    Given a trivial assertion\n")
        (spit-file dir "steps/solo.clj"
                   "(ns solo-steps
                      (:require [shiftlefter.stepengine.registry :refer [defstep]]))
                    (defstep #\"a trivial assertion\" [] nil)")
        (spit-file dir "setup.clj"
                   "(ns setup)
                    (def setups
                      [{:label \"solo-group\"
                        :start (fn [_] {})
                        :features [\"features/solo.feature\"]}])")
        (let [{:keys [result out]} (run-captured
                                    {:paths []
                                     :edn true
                                     :config-path (str (fs/path dir "shiftlefter.edn"))
                                     :step-paths [(str (fs/path dir "steps"))]})
              forms (read-forms out)]
          (is (= 0 (:exit-code result)))
          (is (= 1 (count forms)) "one summary form, no aggregate")
          (let [summary (first forms)]
            (is (= 0 (:run/exit-code summary)))
            (is (not (contains? summary :group/label)))
            (is (not (contains? summary :group/index))))
          (is (not-any? :invocation/exit-code forms)))))))

;; -----------------------------------------------------------------------------
;; sl-lbrc: capability-cleanup failures surface as warn-level diagnostics —
;; console stderr block / EDN :diagnostics :cleanup-failures — never the exit
;; code. The leaky adapter comes from setup.clj's custom :adapter-registry
;; (mock helper); a step seeds the ephemeral capability exec_test-style.
;; -----------------------------------------------------------------------------

(defn- write-leaky-cleanup-project!
  "Temp project whose single group uses an adapter registry with a :cleanup
   that fails. Returns opts for execute!."
  [dir]
  (spit-file dir "shiftlefter.edn"
             (pr-str {:runner {:step-paths []}
                      :interfaces {:web {:type :web :adapter :leaky}}}))
  (spit-file dir "features/probe.feature"
             "Feature: P\n  Scenario: leaves a browser behind\n    Given a step that opens a capability\n")
  (spit-file dir "steps/probe.clj"
             "(ns leaky-steps
                (:require [shiftlefter.stepengine.registry :refer [defstep]]
                          [shiftlefter.capabilities.ctx :as cap]))
              (defstep #\"a step that opens a capability\" [ctx]
                (cap/assoc-capability ctx :web {:fake :driver} :ephemeral))")
  (spit-file dir "setup.clj"
             "(ns setup
                (:require [shiftlefter.test-helpers.adapter-registry :as mock]))
              (def setups
                [{:label \"leaky-group\"
                  :adapters [{:name :leaky :provides []}]
                  :start (fn [_]
                           {:adapter-registry
                            (mock/registry
                             {:leaky {:cleanup (fn [_impl]
                                                 {:error {:message \"chrome process tree still running\"}})}})})
                  :features [\"features/probe.feature\"]}])")
  {:paths       []
   :config-path (str (fs/path dir "shiftlefter.edn"))
   :step-paths  [(str (fs/path dir "steps"))]})

(deftest cleanup-failure-surfaces-in-edn-diagnostics
  (testing "a failed capability cleanup rides :diagnostics :cleanup-failures
            on a GREEN run — exit stays 0 (truth via warning, c6wk/sl-lbrc)"
    (with-temp-project
      (fn [dir]
        (let [opts (write-leaky-cleanup-project! dir)
              result-box (volatile! nil)
              out (with-out-str
                    (vreset! result-box (core/execute! (assoc opts :edn true))))
              summary (edn/read-string out)
              failures (get-in summary [:diagnostics :cleanup-failures])]
          (is (= 0 (:exit-code @result-box)) "green run stays green")
          (is (= 0 (:run/exit-code summary)))
          (is (= 1 (count failures)) (pr-str (:diagnostics summary)))
          (let [f (first failures)]
            (is (= "leaves a browser behind" (:scenario f)))
            (is (= :web (:capability f)))
            (is (= :leaky (:adapter f)))
            (is (str/includes? (:error f) "chrome process tree"))))))))

(deftest cleanup-failure-prints-console-warning
  (testing "console mode: stderr carries the cleanup-failure block; nothing
            EDN-shaped reaches stdout"
    (with-temp-project
      (fn [dir]
        (let [opts (write-leaky-cleanup-project! dir)
              err-w (java.io.StringWriter.)
              result-box (volatile! nil)
              out (with-out-str
                    (binding [*err* err-w]
                      (vreset! result-box (core/execute! opts))))]
          (is (= 0 (:exit-code @result-box)))
          (is (str/includes? (str err-w)
                             "Capability cleanup failures (resources may have leaked):"))
          (is (str/includes? (str err-w) "chrome process tree still running"))
          (is (not (str/includes? out ":run/exit-code"))))))))

(deftest cleanup-failure-attributes-to-its-own-group
  (testing "multi-group: only the leaky group's summary carries
            :cleanup-failures (Tower condition 3 — per-group attribution)"
    (with-temp-project
      (fn [dir]
        (spit-file dir "shiftlefter.edn"
                   (pr-str {:runner {:step-paths []}
                            :interfaces {:web {:type :web :adapter :leaky}}}))
        (spit-file dir "features/clean.feature"
                   "Feature: Clean\n  Scenario: tidy\n    Given a trivial assertion\n")
        (spit-file dir "features/leaky.feature"
                   "Feature: Leaky\n  Scenario: leaves a browser behind\n    Given a step that opens a capability\n")
        (spit-file dir "steps/probe.clj"
                   "(ns leaky-steps2
                      (:require [shiftlefter.stepengine.registry :refer [defstep]]
                                [shiftlefter.capabilities.ctx :as cap]))
                    (defstep #\"a trivial assertion\" [] nil)
                    (defstep #\"a step that opens a capability\" [ctx]
                      (cap/assoc-capability ctx :web {:fake :driver} :ephemeral))")
        (spit-file dir "setup.clj"
                   "(ns setup
                      (:require [shiftlefter.test-helpers.adapter-registry :as mock]))
                    (def leaky-registry
                      (mock/registry
                       {:leaky {:cleanup (fn [_impl]
                                           {:error {:message \"leaked\"}})}}))
                    (def setups
                      [{:label \"clean-group\"
                        :start (fn [_] {})
                        :features [\"features/clean.feature\"]}
                       {:label \"leaky-group\"
                        :adapters [{:name :leaky :provides []}]
                        :start (fn [_] {:adapter-registry leaky-registry})
                        :features [\"features/leaky.feature\"]}])")
        (let [opts {:paths []
                    :config-path (str (fs/path dir "shiftlefter.edn"))
                    :step-paths [(str (fs/path dir "steps"))]}
              result-box (volatile! nil)
              out (with-out-str
                    (vreset! result-box (core/execute! (assoc opts :edn true))))
              forms (let [rdr (java.io.PushbackReader.
                               (java.io.StringReader. out))]
                      (loop [acc []]
                        (let [form (edn/read {:eof ::eof} rdr)]
                          (if (= ::eof form) acc (recur (conj acc form))))))]
          (is (= 0 (:exit-code @result-box)))
          ;; 3 forms since sl-pwbp: two group summaries + the invocation
          ;; aggregate form.
          (is (= 3 (count forms)))
          (is (= 1 (count (filter #(get-in % [:diagnostics :cleanup-failures])
                                  forms)))
              "exactly one group's summary carries cleanup failures")
          (is (= "leaves a browser behind"
                 (-> (some #(get-in % [:diagnostics :cleanup-failures]) forms)
                     first :scenario))))))))

;; -----------------------------------------------------------------------------
;; sl-yvaz: honest teardown — :stop-throw surfacing (never the exit code),
;; the cross-run dirty marker, and the G2 composed-weave order test.
;; -----------------------------------------------------------------------------

(def ^:private messy-label
  ;; Guard-family law: a real messy label literal — parens, em-dash,
  ;; apostrophe — the marker and both announcements must survive it.
  "smoke (web) — alice's group")

(defn- marker-file [dir] (str (fs/path dir ".shiftlefter" "dirty-teardown.edn")))

(defn- write-stop-throw-project!
  "Green run whose single group's :stop throws. Returns opts for execute!."
  [dir]
  (spit-file dir "shiftlefter.edn" (pr-str {:runner {:step-paths []}}))
  (spit-file dir "features/probe.feature"
             "Feature: P\n  Scenario: green\n    Given a trivial assertion\n")
  (spit-file dir "steps/probe.clj"
             "(ns stop-throw-steps
                (:require [shiftlefter.stepengine.registry :refer [defstep]]))
              (defstep #\"a trivial assertion\" [] nil)")
  (spit-file dir "setup.clj"
             "(ns setup)
              (def setups
                [{:label \"smoke (web) — alice's group\"
                  :start (fn [_] {:stop (fn [] (throw (ex-info \"port still bound\" {})))})
                  :features [\"features/probe.feature\"]}])")
  {:paths []
   :config-path (str (fs/path dir "shiftlefter.edn"))
   :step-paths [(str (fs/path dir "steps"))]})

(defn- heal-teardown!
  "Overwrite setup.clj with a healthy lifecycle (same group label)."
  [dir]
  (spit-file dir "setup.clj"
             "(ns setup)
              (def setups
                [{:label \"smoke (web) — alice's group\"
                  :start (fn [_] {:stop (fn [] nil)})
                  :features [\"features/probe.feature\"]}])"))

(deftest stop-throw-surfaces-and-writes-marker
  (testing "a :stop throw surfaces (group result + stderr block) WITHOUT
            flipping the green exit, and writes the dirty marker (sl-yvaz)"
    (with-temp-project
      (fn [dir]
        (let [opts (write-stop-throw-project! dir)
              {:keys [result err]} (run-captured opts)
              tf (:teardown-failure (first (:groups result)))
              entries (edn/read-string (slurp (marker-file dir)))]
          (is (= 0 (:exit-code result)) "leaked resource never flips green")
          (is (= messy-label (:group tf)))
          (is (= "port still bound" (:message tf)))
          (is (= (:run-id result) (:run-id tf)))
          (is (str/includes? err (str "Setup group " (pr-str messy-label)
                                      " teardown failed (environment may be dirty):")))
          (is (str/includes? err "port still bound"))
          (is (= 1 (count entries)))
          (is (= tf (first entries)) "marker entry = the group-result entry")
          ;; sl-7dq5 F9: the marker write is a .shiftlefter creation site —
          ;; the dir self-ignores at birth.
          (is (= "*\n" (slurp (str (fs/path dir ".shiftlefter" ".gitignore"))))))))))

(deftest stop-throw-emits-supplementary-edn-form
  (testing "EDN mode: the group summary is followed by ONE supplementary
            diagnostics form — shape byte-pinned (Chair condition C2).
            C3 note: this form folds into the group summary's own
            :diagnostics if the reporting lifecycle is restructured."
    (with-temp-project
      (fn [dir]
        (let [opts (write-stop-throw-project! dir)
              {:keys [result out]} (run-captured (assoc opts :edn true))
              forms (read-forms out)]
          (is (= 0 (:exit-code result)))
          (is (= 2 (count forms)) (str "summary + supplementary; got " out))
          (is (contains? (first forms) :counts) "group summary comes first")
          (let [supp (second forms)
                entry (-> supp :diagnostics :teardown-failures first)]
            (is (= #{:run/id :group :diagnostics} (set (keys supp))))
            (is (= (:run-id result) (:run/id supp)))
            (is (= messy-label (:group supp)))
            (is (= 1 (count (-> supp :diagnostics :teardown-failures))))
            (is (= #{:group :message :run-id :at} (set (keys entry))))
            (is (= messy-label (:group entry)))
            (is (= "port still bound" (:message entry)))
            (is (= (:run-id result) (:run-id entry)))))))))

(deftest next-run-announces-and-clears-marker
  (testing "the run AFTER a dirty teardown announces it on stderr, then
            clears the marker; a healthy run leaves nothing behind"
    (with-temp-project
      (fn [dir]
        (let [opts (write-stop-throw-project! dir)]
          (run-captured opts)
          (heal-teardown! dir)
          (let [{:keys [result err]} (run-captured opts)]
            (is (= 0 (:exit-code result)))
            (is (str/includes?
                 err "Previous run teardown failed — environment may be dirty:"))
            (is (str/includes? err messy-label))
            (is (str/includes? err "port still bound"))
            (is (not (fs/exists? (marker-file dir)))
                "real run clears the marker after announcing")))))))

(deftest next-run-announces-marker-in-edn-mode
  (testing "EDN mode: the announcement is the same supplementary form
            shape, emitted BEFORE the run's summary; entries carry the
            PRIOR run's id (the form self-describes via :run/id mismatch)"
    (with-temp-project
      (fn [dir]
        (let [opts (write-stop-throw-project! dir)
              run1 (:result (run-captured opts))]
          (heal-teardown! dir)
          (let [{:keys [result out]} (run-captured (assoc opts :edn true))
                forms (read-forms out)
                announce (first forms)]
            (is (= 2 (count forms)) (str "announcement + summary; got " out))
            (is (= #{:run/id :diagnostics} (set (keys announce))))
            (is (= (:run-id result) (:run/id announce)))
            (is (= (:run-id run1)
                   (-> announce :diagnostics :teardown-failures first :run-id))
                "entries name the run that made the mess")
            (is (contains? (second forms) :counts))))))))

(deftest dry-run-announces-but-never-clears
  (testing "a dry-run announces the marker but must not consume it — no
            lifecycle runs that could actually clean anything (sl-yvaz)"
    (with-temp-project
      (fn [dir]
        (let [opts (write-stop-throw-project! dir)
              run1 (:result (run-captured opts))
              {:keys [err]} (run-captured (assoc opts :dry-run true))
              entries (edn/read-string (slurp (marker-file dir)))]
          (is (str/includes?
               err "Previous run teardown failed — environment may be dirty:"))
          (is (= 1 (count entries)) "marker survives the dry-run")
          (is (= (:run-id run1) (:run-id (first entries)))))))))

(deftest clean-stop-never-erases-same-run-failure
  (testing "group A's :stop throws, group B's :stop is clean — B must NOT
            clear A's marker entry. (The mmfy 'clean :stop clears any stale
            marker' clause implemented literally would erase it here —
            Chair-ratified correction, sl-yvaz plan approval.)"
    (with-temp-project
      (fn [dir]
        (spit-file dir "shiftlefter.edn" (pr-str {:runner {:step-paths []}}))
        (spit-file dir "features/one.feature"
                   "Feature: One\n  Scenario: A\n    Given a trivial assertion\n")
        (spit-file dir "features/two.feature"
                   "Feature: Two\n  Scenario: B\n    Given a trivial assertion\n")
        (spit-file dir "steps/probe.clj"
                   "(ns clean-stop-steps
                      (:require [shiftlefter.stepengine.registry :refer [defstep]]))
                    (defstep #\"a trivial assertion\" [] nil)")
        (spit-file dir "setup.clj"
                   "(ns setup)
                    (def setups
                      [{:label \"dirty-group\"
                        :start (fn [_] {:stop (fn [] (throw (ex-info \"leaked\" {})))})
                        :features [\"features/one.feature\"]}
                       {:label \"tidy-group\"
                        :start (fn [_] {:stop (fn [] nil)})
                        :features [\"features/two.feature\"]}])")
        (let [{:keys [result]} (run-captured
                                {:paths []
                                 :config-path (str (fs/path dir "shiftlefter.edn"))
                                 :step-paths [(str (fs/path dir "steps"))]})
              entries (edn/read-string (slurp (marker-file dir)))]
          (is (= 0 (:exit-code result)))
          (is (= ["dirty-group"] (mapv :group entries))
              "exactly the dirty group's entry survives the tidy group's stop"))))))

;; -----------------------------------------------------------------------------
;; G2: the composed weave, proven by one totally-ordered log (sl-yvaz).
;; Public canonical prose home is docs/lifecycle.md (sl-fhuj publishes,
;; citing this test); the setup-clj page owns group semantics.
;; -----------------------------------------------------------------------------

(deftest composed-weave-order
  (testing "declared group order; per group :start -> compile -> per
            scenario [Before -> provision -> steps -> After -> cleanup] ->
            :stop; a start-throw skips its group, later groups still run"
    (with-temp-project
      (fn [dir]
        (reset! weave-log [])
        (spit-file dir "shiftlefter.edn"
                   (pr-str {:runner {:step-paths []}
                            :interfaces {:web {:type :web :adapter :probe}}}))
        (spit-file dir "features/weave.feature"
                   "@hook=trace\nFeature: W\n  Scenario: weave\n    Given :user opens the web thing\n")
        (spit-file dir "features/skipped.feature"
                   "Feature: S\n  Scenario: never runs\n    Given :user opens the web thing\n")
        (spit-file dir "steps/weave.clj"
                   "(ns weave-steps
                      (:require [shiftlefter.stepengine.registry :refer [defstep]]
                                [shiftlefter.runner.core-setup-test :as t]))
                    (defstep #\":([\\w./-]+) opens the web thing\"
                      {:interface :web
                       :svo {:subject :$1 :verb :probe :object nil}}
                      [ctx]
                      (t/record-weave! :step)
                      ctx)")
        (spit-file dir "hooks.clj"
                   "(ns hooks
                      (:require [shiftlefter.runner.core-setup-test :as t]))
                    (def hooks
                      [{:name \"trace\"
                        :before (fn [_] (t/record-weave! :before) nil)
                        :after (fn [_] (t/record-weave! :after) nil)}])")
        (spit-file dir "setup.clj"
                   "(ns setup
                      (:require [shiftlefter.runner.core-setup-test :as t]
                                [shiftlefter.test-helpers.adapter-registry :as mock]))
                    (def setups
                      [{:label \"weave-group\"
                        :start (fn [_]
                                 (t/record-weave! :g1-start)
                                 {:adapter-registry
                                  (mock/registry {:probe {:events t/weave-log}})
                                  :stop (fn [] (t/record-weave! :g1-stop))})
                        :features [\"features/weave.feature\"]}
                       {:label \"doomed-group\"
                        :start (fn [_] (throw (ex-info \"no start\" {})))
                        :features [\"features/skipped.feature\"]}])")
        (let [{:keys [result]} (run-captured
                                {:paths []
                                 :config-path (str (fs/path dir "shiftlefter.edn"))
                                 :step-paths [(str (fs/path dir "steps"))]})]
          (is (= 2 (:exit-code result))
              "doomed-group's start-throw aggregates as planning exit")
          (is (= [:g1-start :before [:provision :probe] :step :after
                  [:cleanup :probe] :g1-stop]
                 @weave-log)
              "the ruled weave, in one totally-ordered log"))))))

;; -----------------------------------------------------------------------------
;; G3: the config argument's spec (sl-yvaz) — see runner/setup.clj for why
;; ::start deliberately stays ifn?, never s/fspec.
;; -----------------------------------------------------------------------------

(deftest setup-config-arg-spec
  (is (s/valid? ::rsetup/config {:runner {:step-paths []}})
      "a resolved config map validates")
  (is (not (s/valid? ::rsetup/config nil))
      "nil is not a config — load-setup's fdef would reject it")
  (is (contains? (stest/checkable-syms) 'shiftlefter.runner.setup/load-setup)
      "load-setup's fdef is registered (instrumented in dev/test)"))

;; -----------------------------------------------------------------------------
;; Plan-shape lints at execute! level (sl-lkla)
;; red side FIRST (guard-family law): every miss class must fire
;; -----------------------------------------------------------------------------

(defn- spit-trivial-step! [dir]
  (spit-file dir "steps/probe.clj"
             "(ns probe-steps
                (:require [shiftlefter.stepengine.registry :refer [defstep]]))
              (defstep #\"a trivial assertion\" [] nil)"))

(defn- spit-dead-group-project!
  "A project whose setup.clj declares one live group and one dead group
   (features/checkout.feature does not exist)."
  [dir]
  (spit-file dir "shiftlefter.edn" (pr-str {:runner {:step-paths []}}))
  (spit-file dir "features/live.feature"
             "Feature: Live\n  Scenario: A\n    Given a trivial assertion\n")
  (spit-trivial-step! dir)
  (spit-file dir "setup.clj"
             "(ns setup
                (:require [shiftlefter.runner.core-setup-test :as t]))
              (def setups
                [{:label \"live\"
                  :start (fn [_] (t/record-event! :live-start) {})
                  :features [\"features/live.feature\"]}
                 {:label \"checkout\"
                  :start (fn [_] (t/record-event! :checkout-start) {})
                  :features [\"features/checkout.feature\"]}])"))

(deftest dead-group-is-a-planning-error
  (testing "A group matching no feature files fails the whole plan, exit 2, before any :start"
    (with-temp-project
      (fn [dir]
        (reset! lifecycle-events [])
        (spit-dead-group-project! dir)
        (let [{:keys [result err]} (run-captured
                                    {:paths []
                                     :config-path (str (fs/path dir "shiftlefter.edn"))
                                     :step-paths [(str (fs/path dir "steps"))]})]
          (is (= 2 (:exit-code result)))
          (is (= :planning-failed (:status result)))
          (is (str/includes? err "Setup error:"))
          (is (str/includes? err "match no feature files"))
          (is (= [] @lifecycle-events)
              "plan-shape errors fire before ANY group lifecycle runs"))))))

(deftest dead-group-fails-dry-run-too
  (testing "Dry-run sees the same plan-shape verdict (plan-shape is static)"
    (with-temp-project
      (fn [dir]
        (reset! lifecycle-events [])
        (spit-dead-group-project! dir)
        (let [{:keys [result err]} (run-captured
                                    {:paths []
                                     :dry-run true
                                     :config-path (str (fs/path dir "shiftlefter.edn"))
                                     :step-paths [(str (fs/path dir "steps"))]})]
          (is (= 2 (:exit-code result)))
          (is (str/includes? err "match no feature files"))
          (is (= [] @lifecycle-events)))))))

(deftest literal-empty-features-still-rejected-by-spec
  (testing "The load-time spec layer (::features :min-count 1) still owns literal []"
    (with-temp-project
      (fn [dir]
        (spit-file dir "shiftlefter.edn" (pr-str {:runner {:step-paths []}}))
        (spit-trivial-step! dir)
        (spit-file dir "setup.clj"
                   "(ns setup)
                    (def setups
                      [{:label \"empty\"
                        :start (fn [_] {})
                        :features []}])")
        (let [{:keys [result err]} (run-captured
                                    {:paths []
                                     :config-path (str (fs/path dir "shiftlefter.edn"))
                                     :step-paths [(str (fs/path dir "steps"))]})]
          (is (= 2 (:exit-code result)))
          (is (str/includes? err "failed validation")
              "literal [] is the spec layer's catch, not the lint's"))))))

(defn- spit-shared-feature-project!
  "Two groups both covering features/shared.feature — one by file-spelling,
   one by glob-spelling. The ruled matrix pattern: warns, runs, passes."
  [dir]
  (spit-file dir "shiftlefter.edn" (pr-str {:runner {:step-paths []}}))
  (spit-file dir "features/shared.feature"
             "Feature: Shared\n  Scenario: A\n    Given a trivial assertion\n")
  (spit-trivial-step! dir)
  (spit-file dir "setup.clj"
             "(ns setup)
              (def setups
                [{:label \"registry-a\"
                  :start (fn [_] {})
                  :features [\"features/shared.feature\"]}
                 {:label \"registry-b\"
                  :start (fn [_] {})
                  :features [\"features/*.feature\"]}])"))

(deftest shared-feature-warns-and-still-runs
  (testing "Feature-in-two-groups is a loud warning, never an error, never the exit code"
    (with-temp-project
      (fn [dir]
        (spit-shared-feature-project! dir)
        (let [{:keys [result err]} (run-captured
                                    {:paths []
                                     :config-path (str (fs/path dir "shiftlefter.edn"))
                                     :step-paths [(str (fs/path dir "steps"))]})]
          (is (= 0 (:exit-code result)) (str "both groups must run; got " result))
          (is (= 2 (count (:groups result))) "the feature runs once per group")
          (is (str/includes? err "Setup plan warning:"))
          (is (str/includes? err "deliberate")
              "the message must leave the matrix pattern open"))))))

(deftest shared-feature-warning-rides-edn-envelope
  (testing "Under --edn the warning is a supplementary :setup-lints diagnostics form"
    (with-temp-project
      (fn [dir]
        (spit-shared-feature-project! dir)
        (let [{:keys [result out err]} (run-captured
                                        {:paths []
                                         :edn true
                                         :config-path (str (fs/path dir "shiftlefter.edn"))
                                         :step-paths [(str (fs/path dir "steps"))]})
              forms (read-forms out)
              lint-form (first forms)]
          (is (= 0 (:exit-code result)))
          (is (not (str/includes? err "Setup plan warning:"))
              "stderr line is suppressed under --edn — the form IS the signal")
          (is (contains? (:diagnostics lint-form) :setup-lints)
              (str "first form must be the supplementary lint form; got " lint-form))
          (is (= [:setup/feature-in-multiple-groups]
                 (mapv :type (get-in lint-form [:diagnostics :setup-lints]))))
          (is (= ["registry-a" "registry-b"]
                 (-> lint-form :diagnostics :setup-lints first :groups)))
          (is (= 4 (count forms))
              "lint form + one summary per group + the sl-pwbp invocation
               aggregate form, all on stdout"))))))

(defn- spit-warning-and-error-project!
  "The fisk-#3 config: ONE plan carrying both a shared feature (warning)
   and a dead group (error) — the user who must not fix the error only to
   discover the warning on the next run."
  [dir]
  (spit-file dir "shiftlefter.edn" (pr-str {:runner {:step-paths []}}))
  (spit-file dir "features/shared.feature"
             "Feature: Shared\n  Scenario: A\n    Given a trivial assertion\n")
  (spit-trivial-step! dir)
  (spit-file dir "setup.clj"
             "(ns setup)
              (def setups
                [{:label \"registry-a\"
                  :start (fn [_] {})
                  :features [\"features/shared.feature\"]}
                 {:label \"registry-b\"
                  :start (fn [_] {})
                  :features [\"features/*.feature\"]}
                 {:label \"checkout\"
                  :start (fn [_] {})
                  :features [\"features/checkout.feature\"]}])"))

(deftest warnings-surface-even-when-errors-also-fire
  (testing "Console: the warning line AND the error line, one run, exit 2"
    (with-temp-project
      (fn [dir]
        (spit-warning-and-error-project! dir)
        (let [{:keys [result err]} (run-captured
                                    {:paths []
                                     :config-path (str (fs/path dir "shiftlefter.edn"))
                                     :step-paths [(str (fs/path dir "steps"))]})]
          (is (= 2 (:exit-code result)))
          (is (= :planning-failed (:status result)))
          (is (str/includes? err "Setup plan warning:")
              "the warning must not hide behind the error")
          (is (str/includes? err "Setup error:"))
          (is (str/includes? err "match no feature files"))))))
  (testing "EDN: the :setup-lints form AND the planning block, one stream"
    (with-temp-project
      (fn [dir]
        (spit-warning-and-error-project! dir)
        (let [{:keys [result out]} (run-captured
                                    {:paths []
                                     :edn true
                                     :config-path (str (fs/path dir "shiftlefter.edn"))
                                     :step-paths [(str (fs/path dir "steps"))]})
              forms (read-forms out)]
          (is (= 2 (:exit-code result)))
          (is (= [:setup/feature-in-multiple-groups]
                 (mapv :type (get-in (first forms) [:diagnostics :setup-lints])))
              "warning form still leads the stream")
          (is (some #(= :setup/empty-features (:type %))
                    (get-in (second forms) [:planning :errors]))
              (str "planning block must carry the dead group; got "
                   (second forms))))))))

;; -----------------------------------------------------------------------------
;; Invocation TOC + durable planning-failure pages (sl-n9c6)
;; -----------------------------------------------------------------------------

(defn- island-edn-of
  "Slice and parse the EDN data island out of a report document string."
  [doc]
  (let [marker "id=\"run-data\">"
        start (long (+ (clojure.string/index-of doc marker) (count marker)))
        end (long (clojure.string/index-of doc "</script>" start))]
    (edn/read-string (subs doc start end))))

(deftest multi-group-invocation-toc-and-durable-planning-failure
  ;; The constructed F3 repro (sl-n9c6): alpha green, beta planning-fails at
  ;; the binding gate (undefined step, the 6hsd HTML scope), gamma green,
  ;; delta's :start throws (console/EDN-only scope). The bead's destruction
  ;; scenario — gamma's copy destroying beta's only page — must be dead.
  (with-temp-project
    (fn [dir]
      (spit-file dir "shiftlefter.edn" (pr-str {:runner {:step-paths []}}))
      (spit-file dir "features/a.feature"
                 "Feature: A\n  Scenario: A1\n    Given a trivial assertion\n")
      (spit-file dir "features/b.feature"
                 "Feature: B\n  Scenario: B1\n    Given a step nobody defined\n")
      (spit-file dir "features/c.feature"
                 "Feature: C\n  Scenario: C1\n    Given a trivial assertion\n")
      (spit-file dir "steps/probe.clj"
                 "(ns probe-steps
                    (:require [shiftlefter.stepengine.registry :refer [defstep]]))
                  (defstep #\"a trivial assertion\" [] nil)")
      (spit-file dir "setup.clj"
                 "(ns setup)
                  (defn- ok-start [_] {})
                  (defn- throwing-start [_] (throw (ex-info \"boom\" {})))
                  (def setups
                    [{:label \"alpha\" :start ok-start :features [\"features/a.feature\"]}
                     {:label \"beta\" :start ok-start :features [\"features/b.feature\"]}
                     {:label \"gamma\" :start ok-start :features [\"features/c.feature\"]}
                     {:label \"delta\" :start throwing-start :features [\"features/c.feature\"]}])")
      (let [html-path (str (fs/path dir "out" "report.html"))
            {:keys [result]} (run-captured
                              {:paths       []
                               :config-path (str (fs/path dir "shiftlefter.edn"))
                               :step-paths  [(str (fs/path dir "steps"))]
                               :html        html-path})
            run-dir (:results-dir result)]
        (is (= 2 (:exit-code result)) (str "suite aggregate; got " result))
        (is (some? run-dir))
        (let [toc-doc (slurp (str (fs/path run-dir "report.html")))
              island (island-edn-of toc-doc)
              rows (get-in island [:toc :groups])]
          (testing "rows ride declaration order with per-group verdicts"
            (is (= ["alpha" "beta" "gamma" "delta"] (mapv :label rows)))
            (is (= [:passed :planning-failed :passed :planning-failed]
                   (mapv :status rows)))
            (is (= [0 2 0 2] (mapv :exit-code rows))))
          (testing "invocation verdict is the 7c7i suite aggregate"
            (is (= 2 (get-in island [:summary :exit-code])))
            (is (= :planning-failed (get-in island [:summary :status]))))
          (testing "executed and stashed rows link their pages; start-throw row has none"
            (is (= "alpha/report.html" (:path (nth rows 0))))
            (is (= "beta/report.html" (:path (nth rows 1))))
            (is (= "gamma/report.html" (:path (nth rows 2))))
            (is (nil? (:path (nth rows 3)))))
          (testing "half A: beta's PLANNING FAILED page is durable beside its siblings"
            (let [beta-island (island-edn-of
                               (slurp (str (fs/path run-dir "beta" "report.html"))))]
              (is (= :planning-failed (get-in beta-island [:summary :status])))))
          (testing "the stable path holds the TOC byte copy, not the last group's page"
            (is (= toc-doc (slurp html-path)))))))))

(deftest single-group-setup-stable-path-unchanged
  ;; sl-n9c6 guard: one group under setup.clj — no TOC, no suppression; the
  ;; stable path holds that group's own report exactly as before.
  (with-temp-project
    (fn [dir]
      (spit-file dir "shiftlefter.edn" (pr-str {:runner {:step-paths []}}))
      (spit-file dir "features/a.feature"
                 "Feature: A\n  Scenario: A1\n    Given a trivial assertion\n")
      (spit-file dir "steps/probe.clj"
                 "(ns probe-steps
                    (:require [shiftlefter.stepengine.registry :refer [defstep]]))
                  (defstep #\"a trivial assertion\" [] nil)")
      (spit-file dir "setup.clj"
                 "(ns setup)
                  (def setups
                    [{:label \"solo\" :start (fn [_] {}) :features [\"features/a.feature\"]}])")
      (let [html-path (str (fs/path dir "out" "report.html"))
            {:keys [result]} (run-captured
                              {:paths       []
                               :config-path (str (fs/path dir "shiftlefter.edn"))
                               :step-paths  [(str (fs/path dir "steps"))]
                               :html        html-path})
            island (island-edn-of (slurp html-path))]
        (is (= 0 (:exit-code result)))
        (testing "stable path holds the group report, not a TOC"
          (is (nil? (:toc island)))
          (is (= 1 (count (:scenarios island)))))))))

;; -----------------------------------------------------------------------------
;; sl-e0ml: adapter declare/bind split — preview honesty, verify at :start,
;; and the part-d migration warning. The fixture's stepdef gates on a
;; protocol only the group's NOVEL adapter provides, so binding verdicts
;; directly expose which registry the preview used.
;; -----------------------------------------------------------------------------

(defn- write-gated-adapter-project!
  "Temp project: interface :sms → NOVEL adapter :custom-sms; one stepdef
   gated on ISMSInbound via :requires-protocols. `declare?` puts the
   :adapters declaration on the group; `live-provides` is what the mock
   registry actually carries at :start. Returns opts for execute!."
  [dir declare? live-provides]
  (spit-file dir "shiftlefter.edn"
             (pr-str {:runner {:step-paths []}
                      :interfaces {:sms {:type :sms :adapter :custom-sms}}}))
  (spit-file dir "features/gated.feature"
             "Feature: G\n  Scenario: gated\n    Given a step gated on inbound sms\n")
  (spit-file dir "steps/gated.clj"
             "(ns gated-steps
                (:require [shiftlefter.stepengine.registry :refer [defstep]]))
              (defstep #\"a step gated on inbound sms\"
                {:interface :sms
                 :requires-protocols [:shiftlefter.sms.protocol/ISMSInbound]}
                [] nil)")
  (spit-file dir "setup.clj"
             (str "(ns setup
                (:require [shiftlefter.test-helpers.adapter-registry :as mock]))
              (def setups
                [{:label \"gated-group\"\n"
                  (when declare?
                    "                  :adapters [{:name :custom-sms :provides [:shiftlefter.sms.protocol/ISMSInbound]}]\n")
                  "                  :start (fn [_]
                           {:adapter-registry
                            (mock/registry {:custom-sms {:provides " (pr-str live-provides) "}})})
                  :features [\"features/gated.feature\"]}])"))
  {:paths       []
   :config-path (str (fs/path dir "shiftlefter.edn"))
   :step-paths  [(str (fs/path dir "steps"))]})

(def ^:private inbound-protocol [:shiftlefter.sms.protocol/ISMSInbound])

(deftest declared-adapters-make-dry-run-preview-honest
  (testing "without the declaration the preview cannot JUDGE the novel
            adapter — silent, per the sl-8cvd P2-2 ruling (registry
            membership is owned by the live-only exact check, sl-kkoh; the
            old dry-run exit 2 here was the capability arm's false 'only
            provides []' story). With the declaration, preview and live
            agree by actually binding — the honest half of sl-e0ml"
    (with-temp-project
      (fn [dir]
        (let [opts (write-gated-adapter-project! dir false inbound-protocol)]
          (is (= 0 (:exit-code (:result (run-captured (assoc opts :dry-run true)))))
              "undeclared: an adapter unknown to the preview registry draws
               no capability claim — silence, not the false provides error")
          (is (= 0 (:exit-code (:result (run-captured opts))))
              "undeclared: the live run binds against :start's registry and passes"))))
    (with-temp-project
      (fn [dir]
        (let [opts (write-gated-adapter-project! dir true inbound-protocol)]
          (is (= 0 (:exit-code (:result (run-captured (assoc opts :dry-run true)))))
              "declared: preview binds against the declaration → agrees with live")
          (is (= 0 (:exit-code (:result (run-captured opts))))
              "declared + matching live registry: run passes, no warning"))))))

(deftest registry-mismatch-is-a-planning-error
  (testing "declared contract, runtime-checked: :start returning a registry
            that does not match the declaration exits 2 with
            :setup/registry-mismatch in the EDN planning block"
    (with-temp-project
      (fn [dir]
        (let [opts (write-gated-adapter-project! dir true [])
              {:keys [result out]} (run-captured (assoc opts :edn true))
              summary (first (filter :run/exit-code (read-forms out)))]
          (is (= 2 (:exit-code result)))
          (is (= 2 (:run/exit-code summary)))
          (is (= :setup/registry-mismatch
                 (-> summary :planning :errors first :type)))
          (is (= "gated-group"
                 (-> summary :planning :errors first :group))))))))

(deftest undeclared-registry-warns-but-works
  (testing "part-d migration: a group returning an undeclared registry still
            runs green, with the preview-blind warning on stderr (console)
            and a supplementary :setup-lints form (--edn)"
    (with-temp-project
      (fn [dir]
        (let [opts (write-gated-adapter-project! dir false inbound-protocol)
              {:keys [result err]} (run-captured opts)]
          (is (= 0 (:exit-code result)))
          (is (str/includes? err "Setup plan warning:"))
          (is (str/includes? err "never declared")))))
    (with-temp-project
      (fn [dir]
        (let [opts (write-gated-adapter-project! dir false inbound-protocol)
              {:keys [result out]} (run-captured (assoc opts :edn true))
              forms (read-forms out)
              lint-form (first (filter #(get-in % [:diagnostics :setup-lints]) forms))]
          (is (= 0 (:exit-code result)))
          (is (some? lint-form) "supplementary :setup-lints form present")
          (is (= :setup/undeclared-adapter-registry
                 (-> lint-form :diagnostics :setup-lints first :type)))
          (is (= "gated-group" (:group lint-form)))
          (is (some #(= 0 (:run/exit-code %)) forms)
              "the group's green summary is present alongside"))))))

;; -----------------------------------------------------------------------------
;; sl-wntk: entry-shape validation at the custom-registry install boundary.
;; The returned registry is external input; malformed entries fail with the
;; spec's own legible explain instead of an incidental use-time throw.
;; -----------------------------------------------------------------------------

(defn- write-malformed-registry-project!
  "Temp project whose setup :start returns a registry with a broken entry:
   :factory is a string, :implkey is a typo'd key, :provides is not a coll —
   one entry, three failure classes. `declare?` adds an :adapters declaration
   so the ordering claim (shape check beats mismatch check) is testable."
  [dir declare?]
  (spit-file dir "shiftlefter.edn"
             (pr-str {:runner {:step-paths []}
                      :interfaces {:sms {:type :sms :adapter :broken}}}))
  (spit-file dir "features/plain.feature"
             "Feature: P\n  Scenario: plain\n    Given a plain step\n")
  (spit-file dir "steps/plain.clj"
             "(ns plain-steps
                (:require [shiftlefter.stepengine.registry :refer [defstep]]))
              (defstep #\"a plain step\" [] nil)")
  (spit-file dir "setup.clj"
             (str "(ns setup)
              (def setups
                [{:label \"broken-registry\"\n"
                  (when declare?
                    "                  :adapters [{:name :broken :provides []}]\n")
                  "                  :start (fn [_]
                           {:adapter-registry
                            {:broken {:factory \"not-a-fn\"
                                      :implkey :browser
                                      :provides :oops}}})
                  :features [\"features/plain.feature\"]}])"))
  {:paths       []
   :config-path (str (fs/path dir "shiftlefter.edn"))
   :step-paths  [(str (fs/path dir "steps"))]})

(deftest malformed-custom-registry-is-a-planning-error
  (testing "a malformed entry in :start's returned registry exits 2 with
            :setup/invalid-adapter-registry and a legible explain naming
            the adapter, the failed predicate, and the typo'd key"
    (with-temp-project
      (fn [dir]
        (let [opts (write-malformed-registry-project! dir false)
              {:keys [result out]} (run-captured (assoc opts :edn true))
              summary (first (filter :run/exit-code (read-forms out)))
              error (-> summary :planning :errors first)]
          (is (= 2 (:exit-code result)))
          (is (= :setup/invalid-adapter-registry (:type error)))
          (is (= "broken-registry" (:group error)))
          (is (str/includes? (:message error) "adapter :broken"))
          (is (str/includes? (:message error) "ifn?"))
          (is (str/includes? (:message error) "unknown entry key :implkey"))))))
  (testing "with a declaration present, the shape error still wins over the
            mismatch check (which would throw incidentally on :provides :oops)"
    (with-temp-project
      (fn [dir]
        (let [opts (write-malformed-registry-project! dir true)
              {:keys [result out]} (run-captured (assoc opts :edn true))
              summary (first (filter :run/exit-code (read-forms out)))]
          (is (= 2 (:exit-code result)))
          (is (= :setup/invalid-adapter-registry
                 (-> summary :planning :errors first :type)))))))
  (testing "dry-run never runs :start, so the malformed registry is unseen
            (preview exemption) and the plain scenario previews green"
    (with-temp-project
      (fn [dir]
        (let [opts (write-malformed-registry-project! dir false)
              {:keys [result]} (run-captured (assoc opts :dry-run true))]
          (is (= 0 (:exit-code result))))))))

;; -----------------------------------------------------------------------------
;; Plan-time per-group adapter-capability lint (sl-sehc)
;; -----------------------------------------------------------------------------

(deftest capture-adapter-unsupported-is-per-group
  (testing "the mixed etaoin+playwright multi-group composition (the Warden
            yynw tail row): the playwright group's silence gets ITS OWN
            plan-time lint; the etaoin group stays clean — the once-per-run
            registry-level warn could never see this (the kinds ARE
            contributed by etaoin in the same registry)"
    (with-temp-project
      (fn [dir]
        ;; Shifted mode, BUILTIN vocabulary on both groups (sl-s2oa: two
        ;; :web-typed instances share the builtin browser steps; the
        ;; [:admin] annotation selects the playwright-backed instance —
        ;; before s2oa this composition needed a custom stepdef per
        ;; interface name).
        (spit-file dir "shiftlefter.edn"
                   (pr-str {:runner {:step-paths []}
                            :svo {:unknown-subject :warn}
                            :glossaries {:subjects (str (fs/absolutize "test/fixtures/glossaries/subjects.edn"))}
                            :interfaces {:web {:type :web :adapter :etaoin}
                                         :admin {:type :web :adapter :playwright}}}))
        (spit-file dir "features/web.feature"
                   "Feature: W
  Scenario: S
    Given :user opens the browser to 'http://web.test/'
")
        (spit-file dir "features/admin.feature"
                   "Feature: A
  Scenario: S
    Given [:admin] :user opens the browser to 'http://admin.test/'
")
        (spit-file dir "setup.clj"
                   "(ns setup)
                    (def setups
                      [{:label \"etaoin-group\"
                        :start (fn [_] nil)
                        :features [\"features/web.feature\"]}
                       {:label \"playwright-group\"
                        :start (fn [_] nil)
                        :features [\"features/admin.feature\"]}])")
        (let [{:keys [result out]}
              (run-captured {:paths []
                             :config-path (str (fs/path dir "shiftlefter.edn"))
                             :capture {:console :on-failure}
                             :dry-run true
                             :edn true})
              forms (read-forms out)
              by-group (into {} (comp (filter :group) (map (juxt :group identity)))
                             forms)]
          (is (= 0 (:exit-code result)))
          (let [pw-lints (get-in by-group ["playwright-group" :diagnostics :capture-lints])]
            (is (= 1 (count pw-lints)) "exactly one lint, on the playwright group")
            (is (= :capture/adapter-unsupported (:type (first pw-lints))))
            (is (= :console (:kind (first pw-lints))))
            (is (= [:playwright] (:adapters (first pw-lints))))
            (is (= "playwright-group" (:group (first pw-lints)))))
          (is (nil? (get-in by-group ["etaoin-group" :diagnostics :capture-lints]))
              "the etaoin group carries no capture lints"))))))

;; -----------------------------------------------------------------------------
;; sl-kkoh: the plan-aware EXACT registry check. Shifted-mode fixture as a
;; deliberate CONTROL, not a necessity (sl-8cvd P3-5): provisioning targets
;; ride the bound step's :svo, and the BUILTIN browser stepdefs carry :svo
;; metadata too — so even a vanilla plan binds them and can die of
;; :setup/bound-adapter-missing (Warden pass-2, agent B case C). The
;; explicit shifted project just keeps the touched adapter under the
;; fixture's control.
;; -----------------------------------------------------------------------------

(defn- write-bound-adapter-project!
  "Shifted-mode temp project with a setup.clj group. Interface :web →
   NOVEL adapter :mock-web; `live-adapters` = adapter names the group's
   :start puts in the mock registry; `opts`: :extra-interface? adds a
   second interface :extra → :sms-mock (a DEFAULT-registry name) that no
   scenario touches; :tag-toucher? tags the provisioning scenario @wip
   and adds a second, target-free scenario; :requires-protocols? puts
   `:requires-protocols [IBrowser]` on the stepdef (sl-8cvd P2-2 — the
   shape every bundled stepdef has, which used to trip the suite-lint
   capability arm ahead of the exact registry check). :start/:stop record
   to lifecycle-events. :single-suite? (sl-k1pv) writes NO setup.clj and
   returns feature :paths instead — the no-setup.clj pipeline, where the
   registry is statically the default one and `live-adapters` is unused;
   :extra-unknown-interface? adds :extra2 → :bogus-adapter (a NOVEL name)
   that no scenario touches. Returns execute! opts."
  [dir live-adapters {:keys [extra-interface? tag-toucher? requires-protocols?
                             single-suite? extra-unknown-interface?]}]
  (let [verbs (str (fs/absolutize "test/fixtures/glossaries/verbs-web-project.edn"))]
    (spit-file dir "glossary/subjects.edn"
               (pr-str {:subjects {:user {:desc "u" :instances [{:id :alice}]}}}))
    (spit-file dir "shiftlefter.edn"
               (pr-str (cond-> {:runner {:step-paths []}
                                :svo {}
                                :glossaries {:subjects "glossary/subjects.edn"
                                             :verbs [verbs]}
                                :interfaces {:web {:type :web :adapter :mock-web}}}
                         extra-interface?
                         (assoc-in [:interfaces :extra]
                                   {:type :sms :adapter :sms-mock})
                         extra-unknown-interface?
                         (assoc-in [:interfaces :extra2]
                                   {:type :sms :adapter :bogus-adapter}))))
    (spit-file dir "features/bound.feature"
               (str "Feature: B\n\n"
                    (when tag-toucher? "  @wip\n")
                    "  Scenario: touches\n    Given user/alice clicks the button\n"
                    (when tag-toucher?
                      "\n  Scenario: trivial\n    Given nothing happens\n")))
    (spit-file dir "steps/bound.clj"
               (str "(ns bound-steps
                  (:require [shiftlefter.stepengine.registry :refer [defstep]]))
                (defstep #\"(.+) clicks (.+)\"
                  {:interface :web
                   "
                    (when requires-protocols?
                      ":requires-protocols [:shiftlefter.browser.protocol/IBrowser]
                   ")
                    ":svo {:subject :$1 :verb :swipe :frame :default :object :$2}}
                  [ctx _s _t] ctx)
                (defstep #\"nothing happens\" [] nil)"))
    (when-not single-suite?
      (spit-file dir "setup.clj"
               (str "(ns setup
                  (:require [shiftlefter.runner.core-setup-test :as t]
                            [shiftlefter.test-helpers.adapter-registry :as mock]))
                (def setups
                  [{:label \"bound-group\"
                    :start (fn [_]
                             (t/record-event! :start)
                             {:adapter-registry
                              (mock/registry "
                    (pr-str (into {} (map (fn [nm] [nm {}])) live-adapters))
                    ")
                              :stop (fn [] (t/record-event! :stop))})
                    :features [\"features/bound.feature\"]}])")))
    {:paths       (if single-suite?
                    [(str (fs/path dir "features"))]
                    [])
     :config-path (str (fs/path dir "shiftlefter.edn"))
     :step-paths  [(str (fs/path dir "steps"))]}))

(deftest bound-adapter-missing-is-a-planning-error-and-stop-runs
  (testing "a bound scenario's adapter absent from the live registry: exit 2,
            :setup/bound-adapter-missing in the EDN planning block — and
            :stop STILL RUNS (:start ran, the world exists; unlike the
            pre-compile registry branches, ruled deliberate)"
    (with-temp-project
      (fn [dir]
        (reset! lifecycle-events [])
        (let [opts (write-bound-adapter-project! dir [:other-adapter] {})
              {:keys [result out]} (run-captured (assoc opts :edn true))
              summary (first (filter :run/exit-code (read-forms out)))]
          (is (= 2 (:exit-code result)))
          (is (= 2 (:run/exit-code summary)))
          (is (= :setup/bound-adapter-missing
                 (-> summary :planning :errors first :type)))
          (is (= :mock-web (-> summary :planning :errors first :data :adapter))
              "--edn carries the adapter under :data (serialize-error projection)")
          (is (= [:web] (-> summary :planning :errors first :data :interfaces)))
          (is (= "bound-group" (-> summary :planning :errors first :group)))
          (is (= [:start :stop] @lifecycle-events)
              ":stop ran after the post-compile planning error"))))))

(deftest unbound-removed-default-is-silent
  (testing "silent-when-certainly-safe (the sl-kkoh behavior change): a
            configured interface whose DEFAULT adapter is absent from the
            live registry, but which no bound scenario touches, draws no
            warn and no error — the config-referenced removal warn no
            longer fires at the run seam"
    (with-temp-project
      (fn [dir]
        (reset! lifecycle-events [])
        (let [opts (write-bound-adapter-project! dir [:mock-web]
                                                 {:extra-interface? true})
              {:keys [result err]} (run-captured opts)]
          (is (= 0 (:exit-code result)))
          (is (not (str/includes? err "absent from the"))
              "no removal warn and no bound-adapter error text")
          (is (not (str/includes? err "sms-mock"))))))))

(deftest filtered-out-toucher-is-silent
  (testing "selection-exactness: when the only scenario touching the missing
            adapter is tag-filtered out, the surviving selection is certainly
            safe — exit 0"
    (with-temp-project
      (fn [dir]
        (reset! lifecycle-events [])
        (let [opts (write-bound-adapter-project! dir [:other-adapter]
                                                 {:tag-toucher? true})]
          (let [{:keys [result]} (run-captured opts)]
            (is (= 2 (:exit-code result))
                "control: unfiltered, the @wip toucher errors"))
          (let [{:keys [result]} (run-captured
                                  (assoc opts :tag-filter {:exclude #{"@wip"}}))]
            (is (= 0 (:exit-code result))
                "filtered: the toucher is deselected, the run is safe")))))))

(deftest dry-run-skips-the-bound-adapter-check
  (testing "dry-run has no :start and binds the preview — the live-only
            check stays out of its way"
    (with-temp-project
      (fn [dir]
        (reset! lifecycle-events [])
        (let [opts (write-bound-adapter-project! dir [:other-adapter] {})
              {:keys [result]} (run-captured (assoc opts :dry-run true))]
          (is (= 0 (:exit-code result)))
          (is (= [] @lifecycle-events) "dry-run never ran :start"))))))

(deftest requires-protocols-step-with-missing-adapter-gets-the-exact-error
  (testing "sl-8cvd (P2-2): a stepdef carrying :requires-protocols — the
            bundled-stepdef shape — whose bound adapter is absent from the
            live registry gets kkoh's exact :setup/bound-adapter-missing,
            never the suite-lint capability arm's false 'only provides []'
            remedy (which used to fire first and shadow it for every real
            project)"
    (with-temp-project
      (fn [dir]
        (reset! lifecycle-events [])
        (let [opts (write-bound-adapter-project! dir [:other-adapter]
                                                 {:requires-protocols? true})
              {:keys [result out err]} (run-captured (assoc opts :edn true))
              summary (first (filter :run/exit-code (read-forms out)))]
          (is (= 2 (:exit-code result)))
          (is (= :setup/bound-adapter-missing
                 (-> summary :planning :errors first :type)))
          (is (= :mock-web (-> summary :planning :errors first :data :adapter)))
          (is (not (str/includes? (str out err) "only provides"))
              "the false capability remedy never appears")
          (is (empty? (get-in summary [:planning :suite-lint-issues]))
              "no :stepdef/missing-capability shadow")
          (is (= [:start :stop] @lifecycle-events)
              ":stop still runs after the post-compile planning error"))))))

(deftest requires-protocols-step-with-missing-adapter-dry-run-is-silent
  (testing "the same shape under dry-run stays SILENT — deliberate, not a
            gap (sl-8cvd plan-verdict fold-in): dry-run has no :start and
            binds the declared preview, and the exact bound-adapter check
            is live-only by the sl-kkoh ruling. Before the P2-2 fix this
            path drew the false capability lint instead (exit 2)"
    (with-temp-project
      (fn [dir]
        (reset! lifecycle-events [])
        (let [opts (write-bound-adapter-project! dir [:other-adapter]
                                                 {:requires-protocols? true})
              {:keys [result]} (run-captured (assoc opts :dry-run true))]
          (is (= 0 (:exit-code result)))
          (is (= [] @lifecycle-events) "dry-run never ran :start"))))))

;; -----------------------------------------------------------------------------
;; sl-k1pv: the same exact check on the SINGLE-SUITE (no setup.clj) pipeline.
;; One law across both pipelines: check when knowable, defer when not. Here
;; there is no :start, the registry is statically the default one, so the
;; check fires at plan time — dry-run included (the deliberate divergence
;; from the setup pipeline's live-only posture above).
;; -----------------------------------------------------------------------------

(deftest single-suite-bound-adapter-missing-is-a-planning-error
  (testing "no setup.clj: a config adapter absent from the default registry,
            touched by a bound scenario — exit 2 with the exact
            :setup/bound-adapter-missing at plan time, instead of dying at
            first provisioning with :adapter/unknown"
    (with-temp-project
      (fn [dir]
        (let [opts (write-bound-adapter-project! dir [] {:single-suite? true})
              {:keys [result out]} (run-captured (assoc opts :edn true))
              summary (first (filter :run/exit-code (read-forms out)))
              err (-> summary :planning :errors first)]
          (is (= 2 (:exit-code result)))
          (is (= 2 (:run/exit-code summary)))
          (is (= :setup/bound-adapter-missing (:type err)))
          (is (= :mock-web (-> err :data :adapter))
              "--edn carries the adapter under :data (serialize-error projection)")
          (is (= [:web] (-> err :data :interfaces)))
          (is (not (contains? err :group))
              "no group stamp — there is no setup group on this pipeline"))))))

(deftest single-suite-untouched-unknown-adapter-is-silent
  (testing "selection-exactness on the single-suite pipeline: a configured
            interface on an unknown adapter that NO bound scenario touches
            is certainly safe for this invocation — exit 0, silent
            (honest-late: the config-referenced approximation stays dead)"
    (with-temp-project
      (fn [dir]
        (let [opts (write-bound-adapter-project!
                    dir [] {:single-suite? true
                            :extra-unknown-interface? true})
              ;; make :web bindable: point it at a DEFAULT adapter so only
              ;; the untouched :extra2 → :bogus-adapter is unknown
              _ (spit-file dir "shiftlefter.edn"
                           (-> (slurp (str (fs/path dir "shiftlefter.edn")))
                               (str/replace ":mock-web" ":etaoin")))
              {:keys [result err]} (run-captured (assoc opts :dry-run true))]
          (is (= 0 (:exit-code result)))
          (is (not (str/includes? err "bogus-adapter"))
              "no bound-adapter error text for the untouched adapter"))))))

(deftest single-suite-filtered-out-toucher-is-silent
  (testing "selection-exactness under tag filtering, single-suite: when the
            only toucher of the unknown adapter is filtered out, the
            surviving selection is certainly safe"
    (with-temp-project
      (fn [dir]
        (let [opts (write-bound-adapter-project! dir []
                                                 {:single-suite? true
                                                  :tag-toucher? true})]
          (let [{:keys [result]} (run-captured opts)]
            (is (= 2 (:exit-code result))
                "control: unfiltered, the @wip toucher errors"))
          (let [{:keys [result]} (run-captured
                                  (assoc opts :tag-filter {:exclude #{"@wip"}}))]
            (is (= 0 (:exit-code result))
                "filtered: the toucher is deselected, the run is safe")))))))

(deftest single-suite-dry-run-runs-the-bound-adapter-check
  (testing "dry-run FIRES the check on this pipeline — deliberate divergence
            from dry-run-skips-the-bound-adapter-check above: with no :start
            the registry is plan-time-knowable, and a PLAN-verdict exit 0
            must mean a runnable plan (the 7c7i precedent)"
    (with-temp-project
      (fn [dir]
        (let [opts (write-bound-adapter-project! dir [] {:single-suite? true})
              {:keys [result err]} (run-captured (assoc opts :dry-run true))]
          (is (= 2 (:exit-code result)))
          (is (str/includes? err ":adapter/unknown")
              "the certain-death message reaches stderr at plan time"))))))

(deftest single-suite-requires-protocols-gets-the-exact-error
  (testing "sl-8cvd consistency on the single-suite pipeline: the
            :requires-protocols stepdef shape whose adapter is unknown gets
            the exact :setup/bound-adapter-missing, never the false 'only
            provides []' remedy, and no suite-lint shadow"
    (with-temp-project
      (fn [dir]
        (let [opts (write-bound-adapter-project! dir []
                                                 {:single-suite? true
                                                  :requires-protocols? true})
              {:keys [result out err]} (run-captured (assoc opts :edn true))
              summary (first (filter :run/exit-code (read-forms out)))]
          (is (= 2 (:exit-code result)))
          (is (= :setup/bound-adapter-missing
                 (-> summary :planning :errors first :type)))
          (is (= :mock-web (-> summary :planning :errors first :data :adapter)))
          (is (not (str/includes? (str out err) "only provides"))
              "the false capability remedy never appears")
          (is (empty? (get-in summary [:planning :suite-lint-issues]))
              "no :stepdef/missing-capability shadow"))))))
