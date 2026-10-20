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

   - sl-tcsq (2026-09-10): two NEW cases, `console-polling` and
     `edn-polling`, over a new polling fixture — no existing golden
     changed (none of the earlier fixtures poll).

   - sl-xvae (2026-09-19): the dry-run step plan. `edn-dry-run` and
     `edn-dry-run-hooks` RE-CAPTURED — each gained exactly one additive key,
     `:plan` (the per-scenario/per-step listing carrying `:step/resolved`
     and `:step/macro`), appended last so every earlier key kept its order.
     NEW cases: `console-dry-run-verbose` (basic.feature under -v: the
     `plan:` block over untyped stepdefs), `console-dry-run-plan` (the typed
     plan-project fixture with a macro, no -v — proves the listing is absent
     without the flag), `console-dry-run-plan-verbose` and
     `edn-dry-run-plan` (the same fixture: the listing, and the machine
     plan with :step/resolved on every bound step incl. the three expanded
     macro steps with their call-sites, none on the wrapper). Every other
     console golden is byte-identical.
   - sl-jima (2026-10-19): the plan line leads with the resolved triple and
     the stepdef's file:line; the pattern source moves behind -vv as a
     `pattern:` sub-line. `console-dry-run-verbose` and
     `console-dry-run-plan-verbose` RE-CAPTURED (the listing lines only);
     NEW case `console-dry-run-plan-vv` (:verbose 2). Every other golden,
     including both no-flag dry-run consoles, is byte-identical.
   - sl-mk0t (2026-10-19, Warden W5): the :plan macro WRAPPER entry gains
     one additive key, `:definition {:file}` — the macro's definition file,
     the fact console -v already printed beside the wrapper line.
     `edn-dry-run-plan` RE-CAPTURED (the only golden with a macro);
     `edn-dry-run` and `edn-dry-run-hooks` have no macro and are
     byte-identical, as is every console golden.

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

(def ^:private uuid-re
  #"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

(defn- normalize
  "Strip run-to-run nondeterminism while PRESERVING value types: a `#uuid`
   tagged literal must stay distinguishable from a plain string.

   ORDER MATTERS (sl-v6x3): the repo path is scrubbed to ROOT before UUID-shaped
   segments are scrubbed to UUID. A checkout whose path itself contains a UUID
   (a session scratchpad, a CI temp dir) would otherwise have its path rewritten
   first, and the ROOT substitution would never match again — six goldens red
   on a path a stranger never sees."
  ([s] (normalize repo-root s))
  ([root s]
   (-> s
       (str/replace root "ROOT")
       (str/replace uuid-re "UUID")
       (str/replace #"Completed in \d+\.\d+s" "Completed in Xs"))))

(deftest scrub-substitutes-root-before-uuid
  (testing "a repo root that contains a UUID still scrubs to ROOT (sl-v6x3)"
    (let [root "/private/tmp/claude-501/ba56896c-bfdb-4d6f-b98d-819d1101a3bb/scratchpad/wt"
          line (str "FAILED at " root "/test/fixtures/features/basic.feature:4 "
                    ":scenario/id #uuid \"123e4567-e89b-12d3-a456-426614174000\"")]
      (is (= (str "FAILED at ROOT/test/fixtures/features/basic.feature:4 "
                  ":scenario/id #uuid \"UUID\"")
             (normalize root line))))))

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
   ;; sl-tcsq: a passing oracle's poll count. The verbose console line
   ;; gains ` (3 polls)` ONLY on the step that polled more than once; the
   ;; single-answer scenario and the --edn summary (no step records on a
   ;; green run) are byte-identical to a run that never polled.
   ["console-polling"       {:paths ["test/fixtures/features/polling.feature"]   :no-color true :verbose true}]
   ["edn-polling"           {:paths ["test/fixtures/features/polling.feature"]   :edn true}]
   ;; sl-esq AC9: the dry-run hook preview — a project with a hooks.clj
   ;; sibling lists each scenario's hooks in execution order (globals
   ;; outermost, then @hook= tag order). Locks the preview format.
   ["console-dry-run-hooks" {:paths ["test/fixtures/hooks-project/sl/features/hooked.feature"]
                             :config-path "test/fixtures/hooks-project/sl/shiftlefter.edn"
                             :no-color true :dry-run true}]
   ["edn-dry-run-hooks"     {:paths ["test/fixtures/hooks-project/sl/features/hooked.feature"]
                             :config-path "test/fixtures/hooks-project/sl/shiftlefter.edn"
                             :edn true :dry-run true}]
   ;; sl-xvae: the dry-run step plan — the `plan:` listing under -v (absent
   ;; without it) and the :plan / :step/resolved machine record, over the
   ;; typed plan-project fixture (subjects, intents, one macro).
   ["console-dry-run-verbose" {:paths ["test/fixtures/features/basic.feature"] :no-color true :dry-run true :verbose true}]
   ["console-dry-run-plan"  {:paths ["test/fixtures/plan-project/sl/features/plan.feature"]
                             :config-path "test/fixtures/plan-project/sl/shiftlefter.edn"
                             :step-paths ["test/fixtures/plan-project/sl/steps/"]
                             :no-color true :dry-run true}]
   ["console-dry-run-plan-verbose" {:paths ["test/fixtures/plan-project/sl/features/plan.feature"]
                                    :config-path "test/fixtures/plan-project/sl/shiftlefter.edn"
                                    :step-paths ["test/fixtures/plan-project/sl/steps/"]
                                    :no-color true :dry-run true :verbose true}]
   ;; sl-jima: -vv — the same listing plus one `pattern:` sub-line per bound
   ;; step (the CLI's counted -v arrives here as the integer 2).
   ["console-dry-run-plan-vv" {:paths ["test/fixtures/plan-project/sl/features/plan.feature"]
                               :config-path "test/fixtures/plan-project/sl/shiftlefter.edn"
                               :step-paths ["test/fixtures/plan-project/sl/steps/"]
                               :no-color true :dry-run true :verbose 2}]
   ["edn-dry-run-plan"      {:paths ["test/fixtures/plan-project/sl/features/plan.feature"]
                             :config-path "test/fixtures/plan-project/sl/shiftlefter.edn"
                             :step-paths ["test/fixtures/plan-project/sl/steps/"]
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
