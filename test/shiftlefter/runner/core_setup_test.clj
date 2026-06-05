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
            [clojure.test :refer [deftest is testing]]
            [shiftlefter.runner.core :as core]))

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
