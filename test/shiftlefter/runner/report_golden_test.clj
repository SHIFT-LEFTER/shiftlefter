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
   ["edn-dry-run"           {:paths ["test/fixtures/features/basic.feature"]     :edn true :dry-run true}]
   ;; sl-esq AC9: the dry-run hook preview — a project with a hooks.clj
   ;; sibling lists each scenario's hooks in execution order (globals
   ;; outermost, then @hook= tag order). Locks the preview format.
   ["console-dry-run-hooks" {:paths ["test/fixtures/hooks-project/sl/features/hooked.feature"]
                             :config-path "test/fixtures/hooks-project/sl/shiftlefter.edn"
                             :no-color true :dry-run true}]
   ["edn-dry-run-hooks"     {:paths ["test/fixtures/hooks-project/sl/features/hooked.feature"]
                             :config-path "test/fixtures/hooks-project/sl/shiftlefter.edn"
                             :edn true :dry-run true}]])

(defn- capture
  "Run the pipeline with stdout/stderr captured, rendered in the golden format.
   The seed is PINNED (sl-08f7): the run seed is recorded on every summary
   and printed as a console line, so goldens need a deterministic one —
   pinning also exercises the --seed acceptance path on every case."
  [opts]
  (let [o (java.io.StringWriter.)
        e (java.io.StringWriter.)
        result (binding [*out* o *err* e]
                 (rc/execute! (merge {:step-paths step-paths :seed 42} opts)))]
    (str "EXIT " (:exit-code result) "\n"
         "--- STDOUT ---\n" (normalize (str o))
         "--- STDERR ---\n" (normalize (str e)))))

(deftest output-is-byte-identical-to-pre-protocol-golden
  ;; Deliberate re-capture (sl-08f7): SL_UPDATE_GOLDENS=1 rewrites the
  ;; fixtures from the current tree instead of comparing. Eyeball the diff
  ;; before committing — a golden update is a reviewed decision, never a
  ;; reflex.
  (doseq [[label opts] cases]
    (testing label
      (let [golden-file (io/file "test/fixtures/golden" (str label ".txt"))]
        (if (System/getenv "SL_UPDATE_GOLDENS")
          (do (spit golden-file (capture opts))
              (is (.exists golden-file) (str label ": golden rewritten")))
          (do
            (is (.exists golden-file)
                (str "missing golden fixture for " label))
            (is (= (slurp golden-file) (capture opts))
                (str label ": output drifted from the pre-sl-21z golden"))))))))

(deftest edn-failure-output-stays-edn-native
  (testing "R4: --edn keeps #uuid tagged literals and bare symbols"
    (let [out (capture (second (first (filter #(= "edn-failing" (first %)) cases))))]
      (is (str/includes? out "#uuid \"UUID\"")
          "step/scenario ids must remain #uuid tagged literals, not strings")
      (is (str/includes? out ":ns fixtures.steps.basic-steps")
          "stepdef location :ns must remain a bare symbol, not a string"))))
