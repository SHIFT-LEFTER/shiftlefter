(ns shiftlefter.changelog-guard-test
  "CHANGELOG drift-guard (sl-anru).

   Every CLOSED bead carrying the current release label must appear in
   CHANGELOG.md either as an adjacent `<!-- entry: sl-xxxx -->` marker
   comment next to its public entry, or on an explicit
   `<!-- no-entry: sl-xxxx (reason) -->` allowlist line — so a close that
   ships no changelog text is a loud test failure, never a silent omission
   (origin: the sl-lhsn post-mortem, where the per-close accumulation
   convention died unnoticed during 0.5.2).

   Release-label source — ONE place, the bead graph: the current release
   gate is the open `release-gate` bead whose transitive blocks-closure
   contains no other open gate (the same auto-detect rule as
   notes/gen-roadmap.bb), and the release label is that gate's label that
   isn't `release-gate` (e.g. `0-5-2`). The next release inherits this
   guard with zero edits here: file its gate bead with `release-gate` +
   the new member label and chain it via deps.

   The live guard is dev-repo-only: the public release export ships test/
   and CHANGELOG.md but not .beads (scripts/release-staging/
   public-top-level-allowlist.txt), so it self-skips when
   .beads/issues.jsonl is absent. The fixture tests run everywhere."
  (:require
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.set :as set]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

;; ---------------------------------------------------------------------------
;; bead-graph side: current release gate -> release label
;; ---------------------------------------------------------------------------

(defn- release-gate? [issue]
  (contains? (set (:labels issue)) "release-gate"))

(defn- active? [issue]
  ;; gen-roadmap.bb counts only "open" gates; we also treat an in_progress
  ;; gate as active so the guard doesn't flip to the NEXT release's label
  ;; mid-cut, while the tag session has the gate bead claimed.
  (not= "closed" (:status issue)))

(defn- blockers [issue]
  (->> (:dependencies issue)
       (filter #(= "blocks" (:type %)))
       (map :depends_on_id)))

(defn- closure-up
  "Transitive blocks-closure upstream of `start`, pruned at closed release
   gates (a shipped release summarizes its subtree) — the same walk as
   notes/gen-roadmap.bb."
  [by-id start]
  (loop [stack [start], seen #{}]
    (if (empty? stack)
      seen
      (let [x   (peek stack)
            st  (pop stack)
            iss (by-id x)]
        (cond
          (or (seen x) (nil? iss))
          (recur st seen)

          (and (not= x start) (= "closed" (:status iss)) (release-gate? iss))
          (recur st (conj seen x))

          :else
          (recur (into st (blockers iss)) (conj seen x)))))))

(defn- current-release-gates
  "Active release-gate ids whose blocks-closure holds no OTHER active gate.
   Exactly one when gates chain via deps per convention."
  [issues]
  (let [by-id    (into {} (map (juxt :id identity)) issues)
        gates    (->> issues (filter active?) (filter release-gate?) (mapv :id))
        gate-set (set gates)]
    (filterv (fn [g]
               (empty? (disj (set/intersection (closure-up by-id g) gate-set) g)))
             gates)))

(defn- release-label
  "The gate's member label — its label that isn't `release-gate`."
  [gate-issue]
  (->> (:labels gate-issue) (remove #{"release-gate"}) sort first))

;; ---------------------------------------------------------------------------
;; changelog side: marker scan
;; ---------------------------------------------------------------------------

(def ^:private bead-id-re #"sl-[a-z0-9]+")

(defn- covered-ids
  "IDs granted changelog coverage: every sl- id inside an
   `<!-- entry: ... -->` comment, plus the ids on `<!-- no-entry: ... -->`
   lines BEFORE the first `(` — the parenthesized reason may name other
   beads in prose (\"folded into the sl-esq hooks entry\") and that must
   not grant those beads coverage."
  [changelog]
  (reduce (fn [acc [_ kind body]]
            (let [text (if (= kind "no-entry")
                         (first (str/split body #"\(" 2))
                         body)]
              (into acc (re-seq bead-id-re text))))
          #{}
          (re-seq #"(?s)<!--\s*(no-entry|entry):(.*?)-->" changelog)))

;; ---------------------------------------------------------------------------
;; the guard
;; ---------------------------------------------------------------------------

(defn- guard-report
  "{:gates [ids], :label str-or-nil, :missing #{ids}} — `missing` is the
   closed current-release-labeled beads with no changelog coverage."
  [issues changelog]
  (let [gates (current-release-gates issues)
        by-id (into {} (map (juxt :id identity)) issues)
        label (when (= 1 (count gates)) (release-label (by-id (first gates))))]
    {:gates gates
     :label label
     :missing (if-not label
                #{}
                (set/difference
                 (into #{}
                       (comp (filter #(= "closed" (:status %)))
                             (filter #(contains? (set (:labels %)) label))
                             (map :id))
                       issues)
                 (covered-ids changelog)))}))

(defn- parse-issues-file [path]
  (with-open [rdr (io/reader path)]
    (mapv #(json/parse-string % true) (line-seq rdr))))

;; ---------------------------------------------------------------------------
;; fixture tests — prove both directions without touching repo state
;; ---------------------------------------------------------------------------

(defn- iss [id status labels & [deps]]
  {:id id :status status :labels labels
   :dependencies (mapv (fn [d] {:depends_on_id d :type "blocks"}) deps)})

(def ^:private fixture-issues
  [(iss "sl-g2" "open" ["release-gate" "0-5-2"] ["sl-f1" "sl-f2"])
   (iss "sl-g3" "open" ["release-gate" "0-6"] ["sl-g2"])
   (iss "sl-f1" "closed" ["0-5-2"])
   (iss "sl-f2" "closed" ["0-5-2"])
   (iss "sl-f3" "open" ["0-5-2"])
   (iss "sl-f4" "closed" ["misc"])])

(def ^:private fixture-changelog
  (str "# Changelog: 0.5.2 (Unreleased)\n\n"
       "<!-- no-entry: sl-f2 (folded into the sl-f1 entry) -->\n\n"
       "## What's New\n\n"
       "<!-- entry: sl-f1 -->\n"
       "### Feature one\n"))

(deftest guard-logic
  (testing "current gate resolves through the chain; covered beads pass"
    (is (= {:gates ["sl-g2"] :label "0-5-2" :missing #{}}
           (guard-report fixture-issues fixture-changelog))))

  (testing "a closed labeled bead with no marker is reported missing"
    (is (= #{"sl-f2"}
           (:missing (guard-report fixture-issues
                                   "<!-- entry: sl-f1 -->\n### Feature one\n")))))

  (testing "prose inside a no-entry reason does not grant coverage"
    ;; the reason names sl-f1, but only sl-f2 (before the paren) is covered
    (is (= #{"sl-f1"}
           (:missing (guard-report
                      fixture-issues
                      "<!-- no-entry: sl-f2 (folded into the sl-f1 entry) -->\n")))))

  (testing "comma-separated no-entry lines cover every listed id"
    (is (= #{"sl-f1" "sl-f2"}
           (covered-ids "<!-- no-entry: sl-f1, sl-f2 (release-process beads) -->"))))

  (testing "open beads and beads without the release label are not guarded"
    (is (not (contains? (:missing (guard-report fixture-issues "")) "sl-f3")))
    (is (not (contains? (:missing (guard-report fixture-issues "")) "sl-f4"))))

  (testing "an in_progress gate is still the current gate (mid-cut)"
    (let [issues (mapv #(if (= "sl-g2" (:id %)) (assoc % :status "in_progress") %)
                       fixture-issues)]
      (is (= ["sl-g2"] (:gates (guard-report issues fixture-changelog))))))

  (testing "unchained gates are ambiguous; no active gate means nothing to guard"
    (let [unchained (mapv #(if (= "sl-g3" (:id %)) (assoc % :dependencies []) %)
                          fixture-issues)]
      (is (= 2 (count (:gates (guard-report unchained fixture-changelog))))))
    (let [all-closed (mapv #(assoc % :status "closed") fixture-issues)]
      (is (= {:gates [] :label nil :missing #{}}
             (guard-report all-closed fixture-changelog))))))

;; ---------------------------------------------------------------------------
;; live guard over the repo's actual bead state + changelog
;; ---------------------------------------------------------------------------

(deftest closed-release-beads-have-changelog-coverage
  (let [issues-path ".beads/issues.jsonl"]
    (if-not (.exists (io/file issues-path))
      (is true "dev-repo-only guard: .beads/issues.jsonl absent (public export)")
      (let [{:keys [gates label missing]}
            (guard-report (parse-issues-file issues-path) (slurp "CHANGELOG.md"))]
        (is (< (count gates) 2)
            (str "Ambiguous current release gate — multiple active `release-gate` "
                 "beads whose closure holds no other active gate: " (vec gates)
                 ". Convention: gates chain via blocks deps (see notes/gen-roadmap.bb)."))
        (is (empty? missing)
            (str "Closed " label "-labeled beads with no CHANGELOG.md coverage: "
                 (vec (sort missing))
                 ". For each: add its entry to the current release section with an "
                 "adjacent `<!-- entry: <id> -->` marker, or add an allowlist line "
                 "`<!-- no-entry: <id> (reason) -->` if it has no user-visible "
                 "surface. See sl-anru."))))))
