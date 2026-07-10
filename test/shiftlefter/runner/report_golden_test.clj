(ns shiftlefter.runner.report-golden-test
  "AC 2 (sl-21z): driving output through the Reporter protocol must be
   BYTE-IDENTICAL to the pre-protocol post-hoc reporting phase.

   The goldens in test/fixtures/golden/ were first captured from the tree
   immediately before the sl-21z refactor. They are a regression lock, not a
   specification: if a change here is intentional, re-capture them and say so in
   the commit.

   Deliberate re-captures so far:
   - sl-dgk (2026-07-08): non-verbose console now prints a per-scenario status
     line as each scenario COMPLETES (live progress for long e2e suites). So
     `console-basic` gained a line and `console-multi-quiet` was added. Verbose
     goldens are unchanged (verbose already printed per-scenario, now just
     live), and every `--edn` golden is unchanged.

   Note what `edn-failing.txt` pins: `#uuid \"UUID\"` (a tagged literal, NOT a
   plain string) and `:ns fixtures.steps.basic-steps` (a bare symbol). Tower's
   R4 ruling keeps the `--edn` machine contract EDN-native; JSON lowering is a
   foreign-worker transcoder concern (sl-rdiz), not an envelope constraint."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [shiftlefter.runner.core :as rc]))

(def ^:private repo-root (System/getProperty "user.dir"))
(def ^:private step-paths ["test/fixtures/steps/"])

(defn- normalize
  "Strip run-to-run nondeterminism while PRESERVING value types: a `#uuid`
   tagged literal must stay distinguishable from a plain string."
  [s]
  (-> s
      (str/replace #"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}" "UUID")
      (str/replace repo-root "ROOT")
      (str/replace #"Completed in \d+\.\d+s" "Completed in Xs")))

(def ^:private cases
  [["console-basic"         {:paths ["test/fixtures/features/basic.feature"]     :no-color true}]
   ["console-basic-verbose" {:paths ["test/fixtures/features/basic.feature"]     :no-color true :verbose true}]
   ["console-failing"       {:paths ["test/fixtures/features/failing.feature"]   :no-color true :verbose true}]
   ["console-pending"       {:paths ["test/fixtures/features/pending.feature"]   :no-color true :verbose true}]
   ["console-undefined"     {:paths ["test/fixtures/features/undefined.feature"] :no-color true}]
   ["console-dry-run"       {:paths ["test/fixtures/features/basic.feature"]     :no-color true :dry-run true}]
   ["console-multi"         {:paths ["test/fixtures/features/basic.feature"
                                     "test/fixtures/features/failing.feature"]   :no-color true :verbose true}]
   ["console-multi-quiet"   {:paths ["test/fixtures/features/basic.feature"
                                     "test/fixtures/features/failing.feature"]   :no-color true}]
   ["edn-basic"             {:paths ["test/fixtures/features/basic.feature"]     :edn true}]
   ["edn-failing"           {:paths ["test/fixtures/features/failing.feature"]   :edn true}]
   ["edn-pending"           {:paths ["test/fixtures/features/pending.feature"]   :edn true}]
   ["edn-undefined"         {:paths ["test/fixtures/features/undefined.feature"] :edn true}]
   ["edn-dry-run"           {:paths ["test/fixtures/features/basic.feature"]     :edn true :dry-run true}]])

(defn- capture
  "Run the pipeline with stdout/stderr captured, rendered in the golden format."
  [opts]
  (let [o (java.io.StringWriter.)
        e (java.io.StringWriter.)
        result (binding [*out* o *err* e]
                 (rc/execute! (merge {:step-paths step-paths} opts)))]
    (str "EXIT " (:exit-code result) "\n"
         "--- STDOUT ---\n" (normalize (str o))
         "--- STDERR ---\n" (normalize (str e)))))

(deftest output-is-byte-identical-to-pre-protocol-golden
  (doseq [[label opts] cases]
    (testing label
      (let [golden-file (io/file "test/fixtures/golden" (str label ".txt"))]
        (is (.exists golden-file)
            (str "missing golden fixture for " label))
        (is (= (slurp golden-file) (capture opts))
            (str label ": output drifted from the pre-sl-21z golden"))))))

(deftest edn-failure-output-stays-edn-native
  (testing "R4: --edn keeps #uuid tagged literals and bare symbols"
    (let [out (capture (second (first (filter #(= "edn-failing" (first %)) cases))))]
      (is (str/includes? out "#uuid \"UUID\"")
          "step/scenario ids must remain #uuid tagged literals, not strings")
      (is (str/includes? out ":ns fixtures.steps.basic-steps")
          "stepdef location :ns must remain a bare symbol, not a string"))))
