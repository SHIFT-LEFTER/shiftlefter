(ns shiftlefter.exit-contract-guard-test
  "sl-7c7i guard (the sl-h0oj family): the exit-code contract must agree
   across every surface that states it. Two mechanisms over eight surfaces
   (extended sl-d3cz, Warden W25; docs/EXIT-CODES.md added at sl-kowi;
   the agent-doc exit-codes topic at sl-r54o):

   TABLE surfaces (markdown `| N |` rows vs `verdict/exit-codes`):
   - README.md § Return Codes
   - docs/CI.md's gate table
   - the exit-codes ladder page (added at sl-kowi as docs/EXIT-CODES.md;
     moved into the derived architecture family at sl-zzrx — ONE surface,
     resolved at runtime: the canon notes/architecture/exit-codes.md in
     trees that carry notes/, else the derived
     docs/architecture/exit-codes.md in the public export)
   - agent_docs/exit-codes.md's table (the packaged agent topic, sl-r54o)
   - docs/architecture/invocation-map.md's verdict table
   - shiftlefter.runner.verdict's OWN ns-docstring table (the
     self-consistency loop: the prose table above the def must match it)

   PROSE surfaces (state the contract as the suite-aggregation chain +
   the exit-4 degraded claim, no table; the chain literal is EXTRACTED
   from the verdict ns docstring, never duplicated here):
   - docs/lifecycle.md
   - resources/shiftlefter/agent_docs/lifecycle.md (+ its generated
     docs/AGENT.md copy)

   Plus the daemon wire spec. A half-migrated contract (code speaks 4, a
   doc still claims 0–3) is exactly the drift class the
   version-agreement guard exists for, one seam over.

   Guard-family law (sl-h0oj/sl-tzub): fixtures are REAL LITERALS lifted
   verbatim from the surfaces — a too-clean fixture goes false-red — and
   the red side is proven first."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [shiftlefter.daemon]
            [shiftlefter.runner.verdict :as verdict]))

;; -----------------------------------------------------------------------------
;; The check
;; -----------------------------------------------------------------------------

(defn- codes-in-first-table
  "The set of exit codes claimed by the first markdown table after
   `marker` in `text`: integers in leading `| N |` cells (leading
   whitespace tolerated — a docstring table indents), scanning until the
   next heading. nil when the marker is absent."
  [text marker]
  (when-let [idx (str/index-of text marker)]
    (let [tail (subs text idx)
          stop (str/index-of tail "\n#")
          block (if stop (subs tail 0 stop) tail)]
      (set (map #(Long/parseLong (second %))
                (re-seq #"(?m)^\s*\|\s*(\d+)\s*\|" block))))))

(defn- table-problems
  "Vector of human-readable disagreements between the doc table at
   `marker` and the canonical verdict table; empty = agreement."
  [surface-name text marker]
  (let [codes (codes-in-first-table text marker)]
    (cond
      (nil? codes)
      [(str surface-name ": marker not found: " (pr-str marker))]

      (not= codes verdict/exit-codes)
      [(str surface-name " claims exit codes " (vec (sort codes))
            " but the canonical table (shiftlefter.runner.verdict) is "
            (vec (sort verdict/exit-codes)))]

      :else [])))

(defn- verdict-ns-doc []
  (:doc (meta (find-ns 'shiftlefter.runner.verdict))))

(defn- aggregation-chain
  "The suite-aggregation order as stated in the verdict ns docstring
   (`2 > 4 > 1 > 0`) — the LONGEST `N > N > …` chain there, so this
   guard never duplicates the literal. nil if the docstring stops
   stating one (which the self-consistency table check would also
   catch, but fail legibly here too)."
  []
  (->> (re-seq #"\d+(?: > \d+)+" (verdict-ns-doc))
       (sort-by count)
       last))

(defn- prose-problems
  "Disagreements for a PROSE surface — one that states the contract as
   the worst-wins aggregation chain plus the exit-4 degraded claim,
   rather than a table. Empty = agreement."
  [surface-name text]
  (let [chain (aggregation-chain)]
    (cond-> []
      (nil? chain)
      (conj "verdict ns docstring no longer states an aggregation chain")

      (and chain (not (str/includes? text chain)))
      (conj (str surface-name " does not state the suite aggregation order "
                 (pr-str chain)))

      (not (re-find #"exit\s*\*{0,2}\s*4" text))
      (conj (str surface-name " never claims the exit-4 degraded row")))))

;; -----------------------------------------------------------------------------
;; Red side FIRST — the pre-sl-7c7i README table, verbatim
;; -----------------------------------------------------------------------------

(def ^:private pre-7c7i-readme-run-table
  "### `sl run`
| Code | Meaning |
|------|---------|
| 0 | All scenarios passed (or pending allowed via config) |
| 1 | One or more scenarios failed or errored (a lifecycle hook threw), or pending steps when not allowed |
| 2 | Planning failure (undefined steps, parse errors, config errors, no features found) |
| 3 | Runner crash (unexpected exception) |

### `sl fmt --check`
| Code | Meaning |
|------|---------|
| 0 | All files valid (parse OK, canonically formatted) |
")

(def ^:private pre-7c7i-lifecycle-prose
  ;; The shape docs/lifecycle.md's aggregation paragraph had before the
  ;; 7c7i contract added 4 — real-literal style, chain one code short.
  "Any scenario `:error` makes the run exit **1** like a failure. Across
groups, exit codes aggregate worst-wins: 2 > 1 > 0.")

(deftest red-side-fires
  (testing "the pre-7c7i 0–3 table (real literal) is reported as drift"
    (let [problems (table-problems "README" pre-7c7i-readme-run-table
                                   "### `sl run`")]
      (is (= 1 (count problems)))
      (is (str/includes? (first problems) "[0 1 2 3]"))))
  (testing "a missing marker is reported, never silently green"
    (is (seq (table-problems "README" "# nothing here\n" "### `sl run`"))))
  (testing "prose: a pre-7c7i aggregation chain (no 4) is reported"
    (let [problems (prose-problems "fixture" pre-7c7i-lifecycle-prose)]
      (is (seq problems))
      (is (str/includes? (first problems) "aggregation"))))
  (testing "prose: text that never claims exit 4 is reported"
    (is (some #(str/includes? % "exit-4")
              (prose-problems "fixture" "codes aggregate 2 > 4 > 1 > 0")))))

(deftest green-side-passes
  (testing "a table with exactly the canonical rows passes"
    (let [doc (str "### `sl run`\n| Code | Meaning |\n|------|---------|\n"
                   (str/join "\n" (map #(str "| " % " | x |")
                                       (sort verdict/exit-codes)))
                   "\n\n### next\n")]
      (is (= [] (table-problems "fixture" doc "### `sl run`")))))
  (testing "prose with the chain and the exit-4 claim passes"
    (is (= [] (prose-problems
               "fixture"
               "makes the run exit **4** … aggregate worst-wins: 2 > 4 > 1 > 0.")))))

;; -----------------------------------------------------------------------------
;; The real surfaces
;; -----------------------------------------------------------------------------

(defn- ladder-page-surface
  "The exit-codes ladder page (sl-zzrx): the CANON when this tree carries
   notes/ (dev repo), else the DERIVED page (the public export ships
   docs/ and test/ but not notes/). One surface, resolved at runtime —
   never vacuous in either tree, and no ninth surface: in the dev repo
   the derived page is covered transitively (the derive guard proves
   derived == derive(canon)); in the public tree this fallback is that
   transitive coverage, executed. sl-u9z8 owns the family-wide sweep of
   notes/-reading guards."
  []
  (let [canon "notes/architecture/exit-codes.md"]
    (if (.exists (java.io.File. canon))
      {:label "notes/architecture/exit-codes.md ladder table (canon)"
       :path canon}
      {:label "docs/architecture/exit-codes.md ladder table (derived fallback)"
       :path "docs/architecture/exit-codes.md"})))

(deftest exit-contract-surfaces-agree
  (let [problems (-> []
                     (into (table-problems "README.md § Return Codes"
                                           (slurp "README.md")
                                           "### `sl run`"))
                     (into (table-problems "docs/CI.md gate table"
                                           (slurp "docs/CI.md")
                                           "`sl run`'s codes:"))
                     (into (let [{:keys [label path]} (ladder-page-surface)]
                             (table-problems label (slurp path) "## The table")))
                     (into (table-problems
                            "agent_docs/exit-codes.md table (packaged agent topic)"
                            (slurp "resources/shiftlefter/agent_docs/exit-codes.md")
                            "## The table"))
                     (into (table-problems
                            "docs/architecture/invocation-map.md verdict table"
                            (slurp "docs/architecture/invocation-map.md")
                            "| Code | Status | Meaning |"))
                     (into (table-problems
                            "runner/verdict.clj ns-docstring table (self-consistency)"
                            (verdict-ns-doc)
                            "| Code | Status"))
                     (into (prose-problems "docs/lifecycle.md"
                                           (slurp "docs/lifecycle.md")))
                     (into (prose-problems
                            "agent_docs/lifecycle.md (source topic)"
                            (slurp "resources/shiftlefter/agent_docs/lifecycle.md")))
                     (into (prose-problems "docs/AGENT.md (generated)"
                                           (slurp "docs/AGENT.md"))))]
    (is (empty? problems) (str/join "\n" problems))))

(deftest daemon-wire-spec-agrees
  (testing "the daemon ::exit spec accepts exactly the canonical codes"
    (doseq [code verdict/exit-codes]
      (is (s/valid? :shiftlefter.daemon/exit code) (str "rejects " code)))
    (doseq [bogus [-1 5 42 69]]
      (is (not (s/valid? :shiftlefter.daemon/exit bogus))
          (str "accepts non-contract code " bogus)))))
