(ns shiftlefter.runner.run-seed-test
  "sl-08f7: one run seed per invocation — precedence (--seed > config >
   generated), core-record presence on every summary shape, determinism,
   the console line, multi-group identity, and the :start hand-down.

   Temp-project harness borrowed from core_setup_test; the hook/step-ctx
   hand-down halves live at the exec seam in exec_hooks_test."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [shiftlefter.runner.core :as rc]))

(def ^:private step-paths ["test/fixtures/steps/"])
(def ^:private basic ["test/fixtures/features/basic.feature"])

(defn- run-captured
  [opts]
  (let [out (java.io.StringWriter.)
        err (java.io.StringWriter.)
        result (binding [*out* out *err* err]
                 (rc/execute! (merge {:no-color true} opts)))]
    {:result result :out (str out) :err (str err)}))

(defn- read-forms
  [out]
  (let [rdr (java.io.PushbackReader. (java.io.StringReader. out))]
    (loop [acc []]
      (let [form (edn/read {:eof ::eof} rdr)]
        (if (= ::eof form) acc (recur (conj acc form)))))))

(defn- summaries
  "The run-summary forms (those carrying :run/exit-code) off stdout."
  [out]
  (filterv :run/exit-code (read-forms out)))

(defn- spit-file [dir relative content]
  (let [target (fs/path dir relative)]
    (fs/create-dirs (fs/parent target))
    (spit (str target) content)
    (str target)))

;; :start hand-down probe — setup.clj in the temp project records the
;; :seed key off its config argument here.
(defonce ^:private start-seed (atom ::unset))
(defn ^:export record-start-seed! [sd] (reset! start-seed sd))

;; -----------------------------------------------------------------------------
;; Precedence + determinism + recording
;; -----------------------------------------------------------------------------

(deftest explicit-seed-is-recorded-and-deterministic
  (testing "--seed N twice yields the identical recorded seed"
    (let [run #(run-captured {:paths basic :step-paths step-paths
                              :edn true :seed 7})
          a (first (summaries (:out (run))))
          b (first (summaries (:out (run))))]
      (is (= 7 (:run/seed a)))
      (is (= (:run/seed a) (:run/seed b))))))

(deftest generated-seed-is-positive-and-recorded
  (testing "no --seed, no config seed → generated positive long, still recorded"
    (let [{:keys [out err]} (run-captured {:paths basic :step-paths step-paths
                                           :edn true})
          sd (:run/seed (first (summaries out)))]
      (is (pos-int? sd))
      (is (str/includes? err (str "run seed: " sd))
          "the one console line carries the same seed"))))

(deftest console-line-prints-on-non-edn-runs
  (testing "run seed: N on stderr, stdout untouched"
    (let [{:keys [err]} (run-captured {:paths basic :step-paths step-paths
                                       :seed 99})]
      (is (str/includes? err "run seed: 99")))))

(deftest malformed-seed-is-exit-2-with-valid-recorded-seed
  (testing "a non-positive :seed rides the option-error path; the record
            carries a GENERATED seed, never the invalid value"
    (let [{:keys [result out]} (run-captured {:paths basic :step-paths step-paths
                                              :edn true :seed -3})
          summary (first (summaries out))]
      (is (= 2 (:exit-code result)))
      (is (pos-int? (:run/seed summary)))
      (is (not= -3 (:run/seed summary))))))

(deftest config-seed-used-and-cli-wins
  (let [dir (str (fs/create-temp-dir {:prefix "sl-run-seed-test-"}))]
    (try
      (spit-file dir "shiftlefter.edn" (pr-str {:runner {:step-paths []
                                                         :seed 1234}}))
      (testing "config [:runner :seed] used when no --seed"
        (let [{:keys [out]} (run-captured
                             {:paths basic :step-paths step-paths :edn true
                              :config-path (str (fs/path dir "shiftlefter.edn"))})]
          (is (= 1234 (:run/seed (first (summaries out)))))))
      (testing "--seed wins over config"
        (let [{:keys [out]} (run-captured
                             {:paths basic :step-paths step-paths :edn true :seed 7
                              :config-path (str (fs/path dir "shiftlefter.edn"))})]
          (is (= 7 (:run/seed (first (summaries out)))))))
      (finally (fs/delete-tree dir)))))

(deftest invalid-config-seed-inerts-with-warning
  (let [dir (str (fs/create-temp-dir {:prefix "sl-run-seed-test-"}))]
    (try
      (spit-file dir "shiftlefter.edn" (pr-str {:runner {:step-paths []
                                                         :seed -8}}))
      (let [{:keys [out err]} (run-captured
                               {:paths basic :step-paths step-paths :edn true
                                :config-path (str (fs/path dir "shiftlefter.edn"))})
            sd (:run/seed (first (summaries out)))]
        (is (pos-int? sd))
        (is (not= -8 sd) "invalid config seed never becomes the run's seed")
        ;; stderr warn is suppressed under --edn; verify it on a console run
        (let [{:keys [err]} (run-captured
                             {:paths basic :step-paths step-paths
                              :config-path (str (fs/path dir "shiftlefter.edn"))})]
          (is (str/includes? err "ignoring invalid [:runner :seed]")))
        (is (string? err)))
      (finally (fs/delete-tree dir)))))

(deftest dry-run-summary-carries-seed
  (testing "dry-run is an invocation too — :run/seed present"
    (let [{:keys [out]} (run-captured {:paths basic :step-paths step-paths
                                       :edn true :dry-run true :seed 55})]
      (is (= 55 (:run/seed (first (read-forms out))))))))

;; -----------------------------------------------------------------------------
;; Multi-group identity + :start hand-down
;; -----------------------------------------------------------------------------

(deftest multi-group-summaries-share-one-seed-and-start-receives-it
  (let [dir (str (fs/create-temp-dir {:prefix "sl-run-seed-test-"}))]
    (try
      (reset! start-seed ::unset)
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
                    (:require [shiftlefter.runner.run-seed-test :as t]))
                  (def setups
                    [{:label \"g1\"
                      :start (fn [config] (t/record-start-seed! (:seed config)) {})
                      :features [\"features/one.feature\"]}
                     {:label \"g2\"
                      :start (fn [_config] {})
                      :features [\"features/two.feature\"]}])")
      (let [{:keys [result out]} (run-captured
                                  {:paths []
                                   :config-path (str (fs/path dir "shiftlefter.edn"))
                                   :step-paths [(str (fs/path dir "steps"))]
                                   :edn true :seed 4242})
            sums (summaries out)]
        (is (= 0 (:exit-code result)) (str "suite should pass; got " result))
        (is (= 2 (count sums)) "one summary per group")
        (is (= [4242 4242] (mapv :run/seed sums))
            "ONE seed per invocation, identical in every group summary")
        (is (= 4242 @start-seed) ":start received the seed on its config argument"))
      (finally (fs/delete-tree dir)))))
