(ns shiftlefter.corpus.corpus-test
  "sl-vs9m acceptance: the generated runner-mechanics corpus, run through the
   real runner (vanilla, no browser) and verified via the JUnit XML file
   cross-checked with the --edn summary. The tag axis is live (sl-i608):
   filtered runs verify against the tag-filtered manifest subset. sl-q9wp
   activates the parallel axis through the remaining harness knob."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [shiftlefter.corpus.generator :as gen]
            [shiftlefter.corpus.harness :as harness]
            [shiftlefter.corpus.verifier :as verifier]
            [shiftlefter.runner.events :as events]
            [shiftlefter.stepengine.registry :as registry]))

(defn- clean-registry-fixture [f]
  (registry/clear-registry!)
  (f)
  (registry/clear-registry!))

(use-fixtures :each clean-registry-fixture)

(def ^:private seed 4242)

(defn- corpus-bytes
  "relpath -> file content for every file the generator wrote."
  [{:keys [dir files]}]
  (into {} (map (fn [relpath] [relpath (slurp (str dir "/" relpath))])) files))

(defn- ok? [v] (is (:ok? v) (pr-str (:mismatches v))))

;; -----------------------------------------------------------------------------
;; AC1 -- seeded determinism, byte-identical regeneration
;; -----------------------------------------------------------------------------

(deftest generator-determinism
  (testing "same (profile, seed, generator-version) regenerates byte-identically"
    (let [gen1 (corpus-bytes (gen/write-corpus! {:profile :default :seed seed}))
          gen2 (corpus-bytes (gen/write-corpus! {:profile :default :seed seed}))]
      (is (seq gen1))
      (is (= gen1 gen2)))))

;; -----------------------------------------------------------------------------
;; AC2-AC5, AC8 -- default profile end-to-end through the real runner
;; -----------------------------------------------------------------------------

(deftest default-profile-end-to-end
  ;; Explicit-nil knobs: the AC8 seam. :tag-filter is live (sl-i608, nil =
  ;; unfiltered); :max-parallel stays inert until sl-q9wp wires it.
  (let [{:keys [manifest junit-file edn-summary result]}
        (harness/run-corpus! {:seed seed :profile :default
                              :tag-filter nil :max-parallel nil})
        entries (:scenarios manifest)
        statuses (set (map :expected-status entries))]
    (testing "corpus shape: tag matrix, statuses, Background (AC2)"
      (is (<= 30 (count entries)) "~40 scenarios")
      (is (= #{:passed :failed :error :pending} statuses))
      (is (some #(seq (:tags %)) entries) "tagged scenarios exist")
      (is (some #(empty? (:tags %)) entries) "untagged scenarios exist")
      (is (some #(= ["@corpus-outline" "@corpus-row"] (:tags %)) entries)
          "outline pickle inheriting an Examples-block tag (the i608 corner)")
      (is (some #(= ["@corpus-suite"] (:tags %)) entries)
          "feature-level inheritance")
      (is (some #(= ["@corpus-a" "@corpus-b"] (:tags %)) entries) "multi-tag")
      (is (some #(and (= "@corpus-suite" (first (:tags %)))
                      (< 1 (count (:tags %))))
                entries)
          "both-levels inheritance")
      (is (some #(= ["@corpus-rule"] (:tags %)) entries)
          "Rule-level inheritance (sl-i608 addendum 2a)"))
    (testing "run executed and failed as designed (fail/error scenarios exist)"
      (is (= 1 (:exit-code result)))
      (is (fs/exists? junit-file)))
    (testing "JUnit XML vs manifest (AC3)"
      (ok? (verifier/verify-run manifest (verifier/parse-junit junit-file))))
    (testing "--edn aggregate counts + failure identities (AC3)"
      (ok? (verifier/verify-edn manifest edn-summary)))
    (testing "serial-hazard machinery: non-interleaving + control (AC5)"
      (ok? (verifier/verify-serial-hazard manifest)))))

;; -----------------------------------------------------------------------------
;; AC6 -- @broken quarantine: excluded by default, exit 2 when included
;; -----------------------------------------------------------------------------

(deftest broken-quarantine
  (testing "the @broken feature is generated but absent from the default run"
    (let [{:keys [corpus junit-file]} (harness/run-corpus! {:seed seed})]
      (is (fs/exists? (str (:dir corpus) "/broken/broken.feature")))
      (is (not-any? #(= "Corpus broken quarantine" (:classname %))
                    (verifier/testcases (verifier/parse-junit junit-file))))))
  (testing "including it without a tag filter is a planning failure (E009):
            exit 2, :undefined issue, no JUnit file -- sl-i608's acceptance
            case runs this same corpus WITH @broken tag-excluded and passes"
    (let [{:keys [result edn-summary junit-file]}
          (harness/run-corpus! {:seed seed :include-broken? true})]
      (is (= 2 (:exit-code result)))
      (is (= :planning-failed (:status result)))
      (is (some #(= :undefined (:type %))
                (get-in edn-summary [:planning :issues])))
      (is (not (fs/exists? junit-file)) "no JUnit file on planning failure"))))

;; -----------------------------------------------------------------------------
;; sl-i608 -- the tag axis: filtered runs verify against the filtered manifest
;; -----------------------------------------------------------------------------

(defn- expected-filtered-exit
  "1 if the filtered subset contains designed failures, else 0 (corpus runs
   with allow-pending? true, so pending never fails)."
  [manifest]
  (if (some #(#{:failed :error} (:expected-status %)) (:scenarios manifest)) 1 0))

(defn- run-filtered
  "Run the corpus with a tag filter and verify JUnit + EDN against the
   tag-filtered manifest subset. Returns the filtered manifest for extra
   per-case assertions. Serial-hazard is NOT checked: filtered runs drop
   marker scenarios, so scratch files are legitimately absent/partial."
  [rules]
  (let [{:keys [manifest junit-file edn-summary result]}
        (harness/run-corpus! {:seed seed :tag-filter rules})
        filtered (verifier/filter-manifest manifest rules)]
    (is (= (expected-filtered-exit filtered) (:exit-code result)))
    (ok? (verifier/verify-run filtered (verifier/parse-junit junit-file)))
    (ok? (verifier/verify-edn filtered edn-summary))
    filtered))

(deftest tag-include-subset
  (testing "include filter: only @corpus-a scenarios run; counts are honest"
    (let [filtered (run-filtered {:include #{"@corpus-a"}})]
      (is (pos? (count (:scenarios filtered))))
      (is (every? #(some #{"@corpus-a"} (:tags %)) (:scenarios filtered))))))

(deftest tag-exclude-subset
  (testing "exclude by a feature-level tag: inheritance drops whole features"
    (let [filtered (run-filtered {:exclude #{"@corpus-suite"}})]
      (is (not-any? #(some #{"@corpus-suite"} (:tags %)) (:scenarios filtered))))))

(deftest tag-rule-subset
  (testing "Rule-level tag selects exactly the Rule's scenarios (addendum 2a)"
    (let [filtered (run-filtered {:include #{"@corpus-rule"}})
          full (:manifest (gen/write-corpus! {:seed seed}))
          file-entries #(filter (fn [e] (= "09_rule_tags.feature" (:feature-file e)))
                                (:scenarios %))]
      (is (= 2 (count (:scenarios filtered))))
      (is (every? #(= "09_rule_tags.feature" (:feature-file %))
                  (:scenarios filtered)))
      (is (< (count (:scenarios filtered)) (count (file-entries full)))
          "a strict subset of the file: the top-level scenario is not selected"))))

(deftest tag-examples-block-subset
  (testing "Examples-BLOCK tag selects one block's pickles, not the other's"
    (let [filtered (run-filtered {:include #{"@corpus-row"}})]
      (is (= 1 (count (:scenarios filtered))) "only the gamma row")
      (is (str/includes? (:name (first (:scenarios filtered))) "gamma"))))
  (testing "outline-level tag selects all the outline's pickles"
    (let [filtered (run-filtered {:include #{"@corpus-outline"}})]
      (is (= 3 (count (:scenarios filtered))) "alpha, beta, gamma"))))

(deftest broken-quarantine-with-exclusion
  (testing "the bead's sharpest case: include the @broken dir WITH @broken
            excluded -- planning passes because the filtered-out scenario is
            never bound; verifiers stay green against the full manifest
            (the broken feature has no manifest entries)"
    (let [{:keys [manifest junit-file edn-summary result]}
          (harness/run-corpus! {:seed seed :include-broken? true
                                :tag-filter {:exclude #{"@broken"}}})]
      (is (= 1 (:exit-code result)) "designed failures, NOT exit 2")
      (is (not= :planning-failed (:status result)))
      (is (fs/exists? junit-file))
      (is (not-any? #(= "Corpus broken quarantine" (:classname %))
                    (verifier/testcases (verifier/parse-junit junit-file))))
      (ok? (verifier/verify-run manifest (verifier/parse-junit junit-file)))
      (ok? (verifier/verify-edn manifest edn-summary)))))

;; -----------------------------------------------------------------------------
;; AC7 -- :parallel-stress profile, green under today's serial execution
;; -----------------------------------------------------------------------------

(deftest parallel-stress-profile
  (let [{:keys [manifest junit-file edn-summary result]}
        (harness/run-corpus! {:seed seed :profile :parallel-stress})]
    (is (< 80 (count (:scenarios manifest))) "bigger N than :default")
    (is (= 1 (:exit-code result)))
    (ok? (verifier/verify-run manifest (verifier/parse-junit junit-file)))
    (ok? (verifier/verify-edn manifest edn-summary))
    (ok? (verifier/verify-serial-hazard manifest))))

;; -----------------------------------------------------------------------------
;; sl-q9wp -- the parallel axis (R3/DP3)
;; -----------------------------------------------------------------------------

(deftest parallel-axis-n-sweep
  ;; DP3: identical JUnit-vs-manifest results at every N; serial-hazard
  ;; groups (@serial -> phase 2, one lane) never interleave at any N.
  (doseq [n [1 3 5 12]]
    (testing (str ":max-parallel " n)
      (let [{:keys [manifest junit-file edn-summary result]}
            (harness/run-corpus! {:seed seed :profile :parallel-stress
                                  :max-parallel n})]
        (is (= 1 (:exit-code result)))
        (ok? (verifier/verify-run manifest (verifier/parse-junit junit-file)))
        (ok? (verifier/verify-edn manifest edn-summary))
        (ok? (verifier/verify-serial-hazard manifest))))))

(deftest parallel-positive-interleave-control
  ;; The other direction of AC5: the ungrouped control set must OBSERVE
  ;; interleaving at N>1 — a silently-serial implementation passes the
  ;; n-sweep above but fails here.
  (let [{:keys [manifest result]}
        (harness/run-corpus! {:seed seed :profile :parallel-stress
                              :max-parallel 5})]
    (is (= 1 (:exit-code result)))
    (ok? (verifier/verify-control-interleaved manifest))))

(deftest parallel-slow-consumer-never-blocks
  ;; Acceptance 6: a wedged observe-plane consumer can never throw into or
  ;; block execution. The subscriber's handler parks on a promise, so its
  ;; sub-chan fills, the mult parks, and the bus input buffer absorbs (then
  ;; refuses) the run's events — while execution and both report surfaces
  ;; proceed untouched. (The throw-vs-drop behavior itself is unit-proven in
  ;; events-test's flood test; this is the two-plane doctrine under the real
  ;; runner at N>1.)
  (let [real-make events/make-memory-bus
        gate (promise)]
    (try
      (with-redefs [events/make-memory-bus
                    (fn []
                      (let [bus (real-make)]
                        (events/subscribe! bus (fn [_] @gate))
                        bus))]
        (let [{:keys [manifest junit-file edn-summary result]}
              (harness/run-corpus! {:seed seed :profile :parallel-stress
                                    :max-parallel 5})]
          (is (= 1 (:exit-code result)) "run completed with its normal exit code")
          (ok? (verifier/verify-run manifest (verifier/parse-junit junit-file)))
          (ok? (verifier/verify-edn manifest edn-summary))))
      (finally
        ;; Unwedge the handler so the parked dispatch thread drains.
        (deliver gate :released)))))

(deftest parallel-console-byte-identity
  ;; DP3: console output at N=5 BYTE-IDENTICAL to N=1 — the release-buffer
  ;; proof (plan order on the report plane regardless of completion order).
  ;; Full stderr is compared as-is. That is safe only while no wall-clock
  ;; line exists in the reporter path: per the Tower ruling (2026-07-09,
  ;; sl-q9wp notes), console.clj's dormant "Completed in X.XXs" line is
  ;; non-deterministic and must be EXCLUDED from byte-comparison paths if
  ;; ever activated — exclude it here rather than "fixing" this test.
  (let [console-run! (fn [n]
                       (harness/run-corpus! {:seed seed
                                             :profile :parallel-stress
                                             :max-parallel n
                                             :report-mode :console}))
        base (console-run! 1)
        par (console-run! 5)]
    (is (seq (:stderr base)) "console mode produced real output")
    (is (nil? (:edn-summary base)) "no EDN summary in console mode")
    (is (= (:stderr base) (:stderr par))
        "N=5 console output byte-identical to N=1")))
