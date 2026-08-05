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
            [shiftlefter.runner.events :as events]
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
          (is (= 2 (count forms)) (str "TWO EDN forms on stdout; got " out))
          (is (some #(and (= 2 (:run/exit-code %))
                          (some (fn [e] (= "boom-group" (:group e)))
                                (get-in % [:planning :errors])))
                    forms)
              "the failing group's planning form is present")
          (is (some #(and (= 0 (:run/exit-code %))
                          (= 1 (get-in % [:counts :passed])))
                    forms)
              "the healthy group's run summary is present alongside"))))))

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
          (is (= 2 (count forms)))
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
          (is (= tf (first entries)) "marker entry = the group-result entry"))))))

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
          (is (= 3 (count forms))
              "lint form + one summary per group, all on stdout"))))))

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
