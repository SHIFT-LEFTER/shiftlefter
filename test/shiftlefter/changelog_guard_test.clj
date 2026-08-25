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
   board/gen-roadmap.bb), and the release label is that gate's label that
   isn't `release-gate` (e.g. `0-5-2`). The next release inherits this
   guard with zero edits here: file its gate bead with `release-gate` +
   the new member label and chain it via deps.

   The live guard is dev-repo-only: the public release export ships test/
   and CHANGELOG.md but not .beads (scripts/release-staging/
   public-top-level-allowlist.txt), so it self-skips when
   .beads/issues.jsonl is absent. The fixture tests run everywhere.

   Blind-spot map (sl-2b2k, from the p1op W16 audit's 8-escape
   enumeration). MECHANICAL arms in this ns:

   - Span arm (escapes 1/2/5): closed beads named by non-merge release-span
     commits touching public-export-SURVIVING files need coverage regardless
     of label — wrong label (sl-l781: a 0.5.3 ship labeled 0-5-4), no label
     (sl-2edk), and relabeled-away alike. `Shipped` is defined by the export
     lists themselves (read, never duplicated).
   - Section scoping (escape 4, the W3/sl-oxiz shape): coverage is granted
     only from the CURRENT (Unreleased) section — a marker in a shipped
     release's section is retro-written history, not coverage.
   - Marker-anchors-prose (escape 6, presence half): a bare entry marker
     with no adjacent bullet grants nothing.
   - Gate presence (escape 7): the live guard demands exactly ONE active
     release gate — zero gates fails loud instead of silently no-opping.

   PROCESS, deliberately not code: escape 3 (open beads whose code
   shipped) — close-precedes-merge law (the worker prompt's Finishing Steps)
   plus the per-release Warden audit are the net, and the span arm catches
   such a bead mechanically the moment it closes; escape 6's quality half
   (does the prose say something true) — fisk + fit-pass review own entry
   text (prompts/cross-session-messaging.md); escape 8 (this guard reads
   the FLUSHED .beads/issues.jsonl snapshot) — BY DESIGN: worktree law
   makes Tower's board-pass flush on main the only jsonl writer, so the
   board-pass suite run on main is this guard's authoritative enforcement
   point, and a live-db read would add a tool dependency the public export
   lacks. Named residual: a span commit omitting the trailing `(sl-xxxx)`
   subject convention is invisible to the span arm — the commit convention
   (CLAUDE.md) is the cover."
  (:require
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
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
   board/gen-roadmap.bb."
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

;; Hyphenated segments included, no trailing-hyphen match (sl-tzub): ids like
;; sl-builtins-frame-pattern-bqo must extract WHOLE — the first hyphen-less
;; version extracted the leading segment only and false-flagged long ids.
(def ^:private bead-id-re #"sl-[a-z0-9]+(?:-[a-z0-9]+)*")

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
;; current-section slice (sl-2b2k, escape 4 — the W3/sl-oxiz shape)
;; ---------------------------------------------------------------------------

(defn- current-section
  "The changelog's top (current-release) section: everything before the
   SECOND `# Changelog:` heading, plus the first heading itself. Coverage
   is granted only from this slice — a marker inside a shipped release's
   section is retro-written history and must not satisfy the current
   release's guard. Returns {:header <line-or-nil> :text <slice>}."
  [changelog]
  (let [lines (vec (str/split-lines changelog))
        idxs  (keep-indexed
               (fn [i l] (when (str/starts-with? l "# Changelog:") i))
               lines)
        [first-idx second-idx] idxs]
    {:header (when first-idx (nth lines first-idx))
     :text   (if second-idx
               (str/join "\n" (subvec lines 0 second-idx))
               changelog)}))

(defn- section-matches-label?
  "Does the top section's heading name the release the guard enforces?
   `0-5-5` label ↔ `# Changelog: 0.5.5 ...` header; a two-segment label
   (`0-6`) matches its three-segment header (`0.6.0`) by prefix. Head
   FORMAT (the `(Unreleased)` marker, build.clj agreement) is
   version-agreement-guard's job — this only proves the slice granting
   coverage belongs to the guarded release."
  [header label]
  (boolean
   (when (and header label)
     (when-let [v (second (re-find #"^# Changelog: (\d+(?:\.\d+)+)" header))]
       (let [lv (str/replace label "-" ".")]
         (or (= v lv) (str/starts-with? v (str lv "."))))))))

(defn- bare-entry-markers
  "1-based line numbers of `<!-- entry: -->` markers in `text` that anchor
   no prose (escape 6, presence half): skipping the CONTIGUOUS marker
   stack (stacked markers over one bullet stay legal — the sl-ddi6-in-
   sl-nart's-block shape), then blanks, the next line must exist and must
   not be a heading or another HTML comment. A BLANK line between markers
   is NOT stacking — it is the lost-bullet signature of the W3/sl-oxiz
   merge-conflict shape this arm was built for (sl-8cvd P2-4): the
   blank-separated next marker falls through to the `<!--` catch below.
   Prose QUALITY is review's job."
  [text]
  (let [lines  (vec (str/split-lines text))
        entry? #(re-find #"^\s*<!--\s*entry:" %)]
    (->> lines
         (keep-indexed
          (fn [i line]
            (when (entry? line)
              (let [nxt (->> (subvec lines (inc i))
                             (drop-while entry?)
                             (drop-while str/blank?)
                             first)]
                (when (or (nil? nxt)
                          (str/starts-with? (str/triml nxt) "#")
                          (str/starts-with? (str/triml nxt) "<!--"))
                  (inc i))))))
         vec)))

;; ---------------------------------------------------------------------------
;; span arm (sl-2b2k, escapes 1/2/5 — the sl-l781 / sl-2edk classes)
;; ---------------------------------------------------------------------------

(defn- read-path-list [path]
  (->> (str/split-lines (slurp path))
       (map str/trim)
       (remove str/blank?)
       vec))

(defn- glob-pattern->re
  "Shell-glob (the forbidden list's `codex-*.md` shape) to a regex that
   matches whole paths; `*`/`?` do not cross `/`."
  [pattern]
  (-> pattern
      (str/replace #"([.^$+{}\[\]()|\\])" "\\\\$1")
      (str/replace "*" "[^/]*")
      (str/replace "?" "[^/]")
      re-pattern))

(defn- public-survivor?
  "Would `path` survive the public release export? Top-level segment on
   the allowlist AND no forbidden entry matching the path itself (glob)
   or naming it or an ancestor directory. audit-public-tree.sh is the
   semantics authority; both lists are READ from scripts/release-staging/
   — never duplicated here."
  [allowlist forbidden path]
  (let [top (first (str/split path #"/"))]
    (boolean
     (and (some #(= top %) allowlist)
          (not-any? (fn [pat]
                      (or (= pat path)
                          (str/starts-with? path (str pat "/"))
                          (re-matches (glob-pattern->re pat) path)))
                    forbidden)))))

(defn- parse-span-log
  "Parse `git log --format=%P%x00%s --name-only` output into
   [{:merge? bool :subject str :files [path ...]}] — pure, so the arm's
   logic is fixture-testable without a repo. Header lines carry a NUL
   between the parent hashes and the subject; file lines follow each
   header until the next blank."
  [out]
  (reduce (fn [acc line]
            (cond
              (str/includes? line "\u0000")
              (let [[parents subject] (str/split line #"\u0000" 2)]
                (conj acc {:merge?  (> (count (str/split parents #" +")) 1)
                           :subject subject
                           :files   []}))

              (or (str/blank? line) (empty? acc))
              acc

              :else
              (update acc (dec (count acc)) update :files conj (str/trim line))))
          []
          (str/split-lines out)))

(defn- subject-tail-ids
  "Bead ids in the subject's TRAILING parenthesized group only — the
   commit convention (`Fix thing (sl-a1b2)`). Prose or branch-name
   mentions elsewhere must not count: measured on the live span,
   `task/afsp-sl-doctor` and `pointing at sl-9gfg` would false-flag."
  [subject]
  (when-let [[_ tail] (re-find #"\(([^()]*)\)\s*$" subject)]
    (re-seq bead-id-re tail)))

(defn- span-shipped-ids
  "Ids named (trailing-parens convention) by non-merge span commits that
   touch at least one public-surviving file. Merges are skipped — their
   constituent commits carry the same ids with real file lists."
  [commits public?]
  (into #{}
        (comp (remove :merge?)
              (filter #(some public? (:files %)))
              (mapcat #(subject-tail-ids (:subject %))))
        commits))

(defn- span-missing
  "Escapes 1/2/5: closed beads among `shipped-ids` with no coverage.
   Label-blind by design — the base guard keys on the release label, so a
   wrong, missing, or relabeled-away label is exactly what escapes it."
  [issues shipped-ids covered]
  (into #{}
        (comp (filter #(= "closed" (:status %)))
              (map :id)
              (filter shipped-ids)
              (remove covered))
        issues))

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

  (testing "hyphenated ids extract WHOLE (sl-tzub — the literal id that went
            red on a clean close: the old regex stopped at the first hyphen
            segment, extracted 'sl-builtins', and reported the full id missing)"
    (is (= #{"sl-builtins-frame-pattern-bqo"}
           (covered-ids "<!-- entry: sl-builtins-frame-pattern-bqo -->")))
    (is (= #{"sl-long-hyphen-id"}
           (covered-ids "<!-- no-entry: sl-long-hyphen-id (test infra) -->")))
    (is (= #{"sl-f2"}
           (covered-ids
            "<!-- no-entry: sl-f2 (folded into the sl-esq-hooks-era entry) -->"))
        "a hyphenated prose id inside the reason still grants nothing"))

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

(def ^:private two-section-changelog
  (str "# Changelog: 0.5.2 (Unreleased)\n\n"
       "## What's New\n\n"
       "<!-- entry: sl-f1 -->\n"
       "### Feature one\n\n"
       "# Changelog: 0.5.1\n\n"
       "<!-- entry: sl-f2 -->\n"
       "### Old feature\n"))

(deftest section-slice-logic
  (testing "slice stops at the second heading; header is the first"
    (let [{:keys [header text]} (current-section two-section-changelog)]
      (is (= "# Changelog: 0.5.2 (Unreleased)" header))
      (is (str/includes? text "sl-f1"))
      (is (not (str/includes? text "sl-f2")))))

  (testing "single-section changelog returns whole text"
    (is (= fixture-changelog (:text (current-section fixture-changelog)))))

  (testing "a marker in a SHIPPED section grants nothing (W3, the oxiz shape)"
    ;; sl-f2 is closed + current-labeled; its only marker sits in the 0.5.1
    ;; section — with the slice, the guard reports it missing.
    (is (= #{"sl-f2"}
           (:missing (guard-report fixture-issues
                                   (:text (current-section two-section-changelog)))))))

  (testing "section header matches the release label, prefix rule for 0-6"
    (is (section-matches-label? "# Changelog: 0.5.2 (Unreleased)" "0-5-2"))
    (is (section-matches-label? "# Changelog: 0.6.0 (Unreleased)" "0-6"))
    (is (section-matches-label? "# Changelog: 0.5.2" "0-5-2")
        "mid-cut: an already-finalized header still matches")
    (is (not (section-matches-label? "# Changelog: 0.5.2 (Unreleased)" "0-5-5")))
    (is (not (section-matches-label? "# Changelog: 0.5.20" "0-5-2"))
        "prefix rule never crosses a digit boundary")
    (is (not (section-matches-label? nil "0-5-2")))
    (is (not (section-matches-label? "# Changelog: 0.5.2" nil)))))

(deftest bare-marker-logic
  (testing "a marker with an adjacent bullet anchors prose"
    (is (= [] (bare-entry-markers "<!-- entry: sl-f1 -->\n- **Feature.** Text.\n"))))

  (testing "stacked markers over one bullet stay legal (the ddi6-in-nart shape)"
    (is (= [] (bare-entry-markers
               "<!-- entry: sl-f1 -->\n<!-- entry: sl-f2 -->\n- **Feature.**\n")))
    (is (= [] (bare-entry-markers
               "<!-- entry: sl-f1 -->\n<!-- entry: sl-f2 -->\n\n- **Feature.**\n"))
        "a blank AFTER the contiguous stack, before the bullet, is fine"))

  (testing "a BLANK line between markers is the lost-bullet signature, not
            stacking (sl-8cvd P2-4 — the W3/oxiz merge-conflict shape)"
    (is (= [1] (bare-entry-markers
                "<!-- entry: sl-f1 -->\n\n<!-- entry: sl-f2 -->\n- **Fix.**\n")))
    (is (= [1] (bare-entry-markers
                "<!-- entry: sl-f1 -->\n\n\n<!-- entry: sl-f2 -->\n- **Fix.**\n"))
        "multiple blanks are the same signature"))

  (testing "a bare marker before a heading, another comment, or EOF is flagged"
    (is (= [1] (bare-entry-markers "<!-- entry: sl-f1 -->\n\n## Fixes\n")))
    (is (= [1] (bare-entry-markers
                "<!-- entry: sl-f1 -->\n<!-- no-entry: sl-f2 (x) -->\n- Text.\n")))
    (is (= [3] (bare-entry-markers "- Text.\n\n<!-- entry: sl-f1 -->\n"))))

  (testing "no-entry lines are not entry markers and need no prose"
    (is (= [] (bare-entry-markers "<!-- no-entry: sl-f1 (reason) -->\n\n## Fixes\n")))))

;; ---------------------------------------------------------------------------
;; span-arm fixtures (sl-2b2k)
;; ---------------------------------------------------------------------------

(def ^:private fixture-allowlist ["src" "bin" "docs" "CHANGELOG.md"])
(def ^:private fixture-forbidden ["bin/bounce-repl" "docs/api" "codex-*.md"])

(defn- fixture-public? [path]
  (public-survivor? fixture-allowlist fixture-forbidden path))

(deftest public-survivor-logic
  (testing "allowlisted top-levels survive; others don't"
    (is (fixture-public? "src/foo.clj"))
    (is (fixture-public? "CHANGELOG.md"))
    (is (not (fixture-public? "board/sprint-order.md")))
    (is (not (fixture-public? ".beads/issues.jsonl"))))

  (testing "forbidden entries strip files, subtrees, and globs"
    (is (not (fixture-public? "bin/bounce-repl")))
    (is (fixture-public? "bin/sl"))
    (is (not (fixture-public? "docs/api/index.html")))
    (is (fixture-public? "docs/CI.md"))
    (is (not (fixture-public? "codex-notes.md")))))

(deftest span-arm-logic
  (let [log (str "abc def\u0000Merge branch 'task/f9-thing'\n"
                 "\n"
                 "abc\u0000Ship the feature (sl-f9)\n"
                 "src/foo.clj\n"
                 "\n"
                 "abc\u0000Board pass — f9 merged, cites sl-f8 (sl-f7)\n"
                 ".beads/issues.jsonl\n"
                 "\n"
                 "abc\u0000Mention sl-f8 in prose, ship steps (sl-f6)\n"
                 "src/steps.clj\n")
        commits (parse-span-log log)]
    (testing "parse: merges detected, files attributed to their commit"
      (is (= [true false false false] (mapv :merge? commits)))
      (is (= ["src/foo.clj"] (:files (second commits)))))

    (testing "trailing-parens extraction only — prose and branch names don't count"
      (is (nil? (subject-tail-ids "Merge branch 'task/afsp-sl-doctor'")))
      (is (= ["sl-f7"] (subject-tail-ids "Board pass — cites sl-f8 (sl-f7)")))
      (is (= ["sl-a1" "sl-b2"] (subject-tail-ids "Two beads (sl-a1, sl-b2)"))))

    (testing "shipped ids: non-merge commits touching public files only"
      ;; sl-f7's commit touches only .beads (never ships); sl-f8 is prose
      (is (= #{"sl-f9" "sl-f6"} (span-shipped-ids commits fixture-public?))))

    (testing "span-missing is label-blind: wrong-label (l781), label-less
              (2edk), and relabeled-away beads are all caught; open beads
              and covered beads are not"
      (let [issues [(iss "sl-f9" "closed" ["0-5-1"])   ; wrong release label
                    (iss "sl-f6" "closed" nil)         ; no label at all
                    (iss "sl-f8" "closed" nil)         ; prose mention only
                    (iss "sl-f5" "open" ["0-5-2"])]
            shipped (span-shipped-ids commits fixture-public?)]
        (is (= #{"sl-f9" "sl-f6"} (span-missing issues shipped #{})))
        (is (= #{"sl-f6"} (span-missing issues shipped #{"sl-f9"}))
            "coverage (entry or no-entry) clears a span-arm demand")))))

;; ---------------------------------------------------------------------------
;; live guard over the repo's actual bead state + changelog
;; ---------------------------------------------------------------------------

(deftest closed-release-beads-have-changelog-coverage
  (let [issues-path ".beads/issues.jsonl"]
    (if-not (.exists (io/file issues-path))
      (is true "dev-repo-only guard: .beads/issues.jsonl absent (public export)")
      (let [{:keys [header text]} (current-section (slurp "CHANGELOG.md"))
            {:keys [gates label missing]}
            (guard-report (parse-issues-file issues-path) text)]
        ;; sl-2b2k escape 7: exactly ONE active gate — zero used to no-op
        ;; the whole guard silently (label nil -> missing #{}).
        (is (= 1 (count gates))
            (if (empty? gates)
              (str "No active `release-gate` bead — the changelog guard has "
                   "nothing to key on and would silently no-op. File the next "
                   "release's gate bead (release-gate + member label, chained "
                   "via blocks deps — see the ns docstring and board/gen-roadmap.bb).")
              (str "Ambiguous current release gate — multiple active `release-gate` "
                   "beads whose closure holds no other active gate: " (vec gates)
                   ". Convention: gates chain via blocks deps (see board/gen-roadmap.bb).")))
        ;; sl-2b2k escape 4: coverage was granted from the current-section
        ;; slice above — prove the slice belongs to the guarded release.
        (when label
          (is (section-matches-label? header label)
              (str "CHANGELOG.md's top section header " (pr-str header)
                   " does not name the guarded release (label " label "). "
                   "Coverage is only granted from the top section, so a wrong "
                   "top section would fail every close.")))
        (is (empty? missing)
            (str "Closed " label "-labeled beads with no CHANGELOG.md coverage "
                 "in the CURRENT (top) section: " (vec (sort missing))
                 ". For each: add its entry to the current release section with an "
                 "adjacent `<!-- entry: <id> -->` marker, or add an allowlist line "
                 "`<!-- no-entry: <id> (reason) -->` if it has no user-visible "
                 "surface. A marker in a SHIPPED release's section does not count "
                 "(sl-2b2k, the W3/sl-oxiz shape). See sl-anru."))))))

(deftest entry-markers-in-current-section-anchor-prose
  (let [{:keys [text]} (current-section (slurp "CHANGELOG.md"))]
    (is (empty? (bare-entry-markers text))
        (str "Bare `<!-- entry: -->` markers (no adjacent bullet) in the "
             "current CHANGELOG.md section at line(s) "
             (vec (bare-entry-markers text))
             " — a marker grants guard coverage, so with no prose the release "
             "notes still ship nothing for that bead (sl-2b2k, escape 6)."))))

;; ---------------------------------------------------------------------------
;; live span arm (sl-2b2k): git + export lists; self-skips outside the dev repo
;; ---------------------------------------------------------------------------

(defn- git-out
  "Run git, returning trimmed stdout on success, nil on any failure —
   the live span arm treats nil as `cannot see the span, self-skip`."
  [& args]
  (try
    (let [{:keys [exit out]} (apply shell/sh "git" args)]
      (when (zero? exit) (str/trimr out)))
    (catch java.io.IOException _ nil)))

(deftest span-shipped-closed-beads-have-changelog-coverage
  (let [issues-path ".beads/issues.jsonl"
        allow-path  "scripts/release-staging/public-top-level-allowlist.txt"
        ;; --match v* is load-bearing: bare `git describe` returns whatever
        ;; tag is nearest (live repro: workflow-pre-messaging-20260808).
        tag         (git-out "describe" "--tags" "--abbrev=0" "--match" "v*")]
    (cond
      (not (.exists (io/file issues-path)))
      (is true "dev-repo-only guard: .beads/issues.jsonl absent (public export)")

      (not (.exists (io/file allow-path)))
      (is true "export lists absent — span arm self-skips")

      (nil? tag)
      (is true "no v* release tag reachable (shallow clone?) — span arm self-skips")

      :else
      (let [log-out (git-out "log" (str tag "..HEAD")
                             "--format=%P%x00%s" "--name-only")]
        (if (nil? log-out)
          (is true "git log unavailable — span arm self-skips")
          (let [allow   (read-path-list allow-path)
                forb    (read-path-list
                         "scripts/release-staging/public-forbidden-paths.txt")
                shipped (span-shipped-ids (parse-span-log log-out)
                                          #(public-survivor? allow forb %))
                covered (covered-ids
                         (:text (current-section (slurp "CHANGELOG.md"))))
                missing (span-missing (parse-issues-file issues-path)
                                      shipped covered)]
            (is (empty? missing)
                (str "Closed beads named by " tag "..HEAD commits that touch "
                     "public-export-surviving files, but with no CHANGELOG.md "
                     "coverage in the current section: " (vec (sort missing))
                     ". This arm is label-blind (sl-2b2k — the sl-l781 wrong-label "
                     "/ sl-2edk no-label escapes): add an entry or no-entry line "
                     "in the CURRENT section, whatever the bead's label says."))))))))
