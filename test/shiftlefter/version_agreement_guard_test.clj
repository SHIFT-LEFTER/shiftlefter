(ns shiftlefter.version-agreement-guard-test
  "Version-agreement drift guard (sl-h0oj).

   The 0.5.3 tag-day miss: the sl-8rd7 checklist's version bumps (build.clj
   `(def version ...)`, release/install.sh `DEFAULT_VERSION`) were skipped
   pre-tag — caught only after tagging, requiring tag surgery + a staging
   reset. Q06 law: agreement must be mechanical, not a checklist walk.

   The invariant (strengthened form, Chair-approved 07-31): build.clj and
   install.sh agree ALWAYS — they bump together in the tag session (7a65a47)
   and no legitimate state diverges them. If a deliberate tag-without-publish
   lag ever wants install.sh pinned to the last PUBLISHED release, this guard
   going red is the prompt to decide that policy consciously. On top of that,
   mode-specific header agreement:

   - dated top section (`**Release Date:**` line): header version ==
     build.clj, and the section carries a `## Stats` fenced block whose
     first line matches `N tests, M assertions, 0 failures (<command>` —
     parsed by an external consumer (the treasury-app resume-facts script);
     its absence at 0.5.3 broke that pipeline silently (scope rider, 07-28).
   - `(Unreleased)` top section: build.clj holds the PRIOR release's version
     (the tag that exists); the bump belongs to the tagger.
   - both markers, or neither: ambiguous — reported, never guessed.

   Guard-family law applies: fixtures use real literals (the dated green
   fixture carries the 0.5.3 Stats line VERBATIM, em-dash tail and all —
   sl-tzub went false-red precisely because its fixture was cleaner than
   reality), red side first. Belt+braces at the script seam lives in
   append-release.sh (refuses a SOURCE_REF whose tree's build.clj
   disagrees with the tag name).

   All three guarded files ship in the public export
   (public-top-level-allowlist.txt: build.clj, CHANGELOG.md, release/), so
   unlike the changelog guard this live test needs no self-skip."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

;; ---------------------------------------------------------------------------
;; parsers — pure functions over file contents
;; ---------------------------------------------------------------------------

(defn- changelog-sections
  "Raw text of each `# Changelog:` section, in file order."
  [changelog]
  (->> (str/split changelog #"(?m)(?=^# Changelog: )")
       (remove str/blank?)
       vec))

(defn- section-header
  "{:version \"X.Y.Z\" :unreleased? bool} from a section's first line, or nil."
  [section]
  (when-let [[_ ver unrel]
             (re-find #"^# Changelog: (\d+\.\d+\.\d+)( \(Unreleased\))?" section)]
    {:version ver :unreleased? (boolean unrel)}))

(defn- stats-first-line
  "First non-blank line inside the top section's `## Stats` fenced block,
   or nil when the heading or fence is absent."
  [section]
  (when-let [[_ block] (re-find #"(?ms)^## Stats\s*\n+```[^\n]*\n(.*?)```" section)]
    (->> (str/split-lines block) (remove str/blank?) first)))

(defn- changelog-head
  "Parse the top section: {:version :mode :prior-version :stats-first-line}.
   :mode is :released, :unreleased, or :ambiguous (both markers or neither)."
  [changelog]
  (let [[top prior] (changelog-sections changelog)
        {:keys [version unreleased?]} (some-> top section-header)
        released? (boolean (some->> top (re-find #"(?m)^\*\*Release Date:\*\*")))]
    {:version version
     :prior-version (:version (some-> prior section-header))
     :stats-first-line (some-> top stats-first-line)
     :mode (cond
             (and unreleased? released?) :ambiguous
             unreleased? :unreleased
             released? :released
             :else :ambiguous)}))

(defn- build-clj-version [build-clj]
  (second (re-find #"(?m)^\(def version \"(\d+\.\d+\.\d+)\"\)" build-clj)))

(defn- install-sh-version [install-sh]
  (second (re-find #"(?m)^DEFAULT_VERSION=\"(\d+\.\d+\.\d+)\"" install-sh)))

;; ---------------------------------------------------------------------------
;; the agreement check
;; ---------------------------------------------------------------------------

(def ^:private stats-shape-re
  ;; `N tests, M assertions, 0 failures (<command>` — the open paren plus a
  ;; non-empty tail is what enforces the producing command's presence.
  #"^\d+ tests, \d+ assertions, 0 failures \(.+")

(defn- released-problems
  [{:keys [version stats-first-line]} build]
  (cond-> []
    (not= version build)
    (conj (str "CHANGELOG.md dated header says " version
               " but build.clj (def version ...) says " build))

    (nil? stats-first-line)
    (conj (str "released top section has no `## Stats` fenced block — the "
               "treasury-app resume-facts script parses it (broke at 0.5.3)"))

    (and stats-first-line (not (re-find stats-shape-re stats-first-line)))
    (conj (str "Stats first line doesn't match "
               "`N tests, M assertions, 0 failures (<command>`: "
               (pr-str stats-first-line)))))

(defn- unreleased-problems
  [{:keys [version prior-version]} build]
  (cond
    (nil? prior-version)
    [(str "top section " version " is (Unreleased) but no prior "
          "`# Changelog:` section exists to check build.clj against")]

    (not= build prior-version)
    [(str "top section " version " is (Unreleased) so build.clj must hold "
          "the prior release " prior-version " (the tag that exists), "
          "but it says " build " — the bump belongs to the tagger")]

    :else []))

(defn- surface-problems
  "Unparseable-surface problems; agreement checks only run when this is empty."
  [{:keys [version]} build install]
  (cond-> []
    (nil? version)
    (conj "CHANGELOG.md: no top `# Changelog: X.Y.Z` header found")
    (nil? build)
    (conj "build.clj: no `(def version \"X.Y.Z\")` found")
    (nil? install)
    (conj "release/install.sh: no `DEFAULT_VERSION=\"X.Y.Z\"` found")))

(defn- agreement-problems
  "Problem strings for the three version surfaces; empty vector = agreement."
  [{:keys [changelog build-clj install-sh]}]
  (let [head (changelog-head changelog)
        build (build-clj-version build-clj)
        install (install-sh-version install-sh)
        unparseable (surface-problems head build install)]
    (if (seq unparseable)
      unparseable
      (into (cond-> []
              (not= build install)
              (conj (str "build.clj (def version \"" build "\") != "
                         "release/install.sh DEFAULT_VERSION=\"" install "\" "
                         "— these bump together in the tag session"))
              (= :ambiguous (:mode head))
              (conj (str "CHANGELOG.md top section needs exactly one of "
                         "`(Unreleased)` in the header or a `**Release Date:**` "
                         "line — found both or neither")))
            (case (:mode head)
              :released (released-problems head build)
              :unreleased (unreleased-problems head build)
              :ambiguous [])))))

;; ---------------------------------------------------------------------------
;; fixtures — real literals (guard-family law)
;; ---------------------------------------------------------------------------

;; The 0.5.3 Stats first line VERBATIM — em-dash tail and all. A sanitized
;; `N tests...` shape here would hide an over-tight regex (the sl-tzub class).
(def ^:private real-stats-line
  "2012 tests, 6939 assertions, 0 failures (./bin/kaocha — the default suite; unit-only: 2003 tests, 6930 assertions)")

(defn- changelog-fixture
  "A two-section changelog: top section built from `header-tail` (text after
   the version on the H1 line) and `body`, over a dated 0.5.2 prior section."
  [version header-tail body]
  (str "# Changelog: " version header-tail "\n\n"
       body
       "# Changelog: 0.5.2\n\n**Release Date:** 2026-07-21\n"))

(def ^:private dated-body-053
  (str "**Release Date:** 2026-07-28\n\n"
       "The theme of this release is **the framework tells the truth**.\n\n"
       "## Stats\n\n"
       "```\n" real-stats-line "\n"
       "Cucumber compliance: 46/46 good, 11/11 bad (100%)\n"
       "```\n\n"
       "## Fixes\n\n"
       "<!-- entry: sl-lbrc -->\n"
       "- **A failed capability cleanup no longer disappears on a green run.**\n\n"))

(def ^:private dated-changelog-053 (changelog-fixture "0.5.3" "" dated-body-053))

(def ^:private unreleased-changelog-053
  (changelog-fixture "0.5.3" " (Unreleased)" "## Fixes\n\n- Pending.\n\n"))

(defn- build-clj-fixture [v]
  (str "(ns build)\n\n(def lib 'com.shiftlefter/shiftlefter)\n"
       "(def version \"" v "\")\n(def class-dir \"target/classes\")\n"))

(defn- install-sh-fixture [v]
  (str "#!/usr/bin/env bash\nset -euo pipefail\n"
       "DEFAULT_VERSION=\"" v "\"\n"))

(defn- problems-for [changelog build-v install-v]
  (agreement-problems {:changelog changelog
                       :build-clj (build-clj-fixture build-v)
                       :install-sh (install-sh-fixture install-v)}))

;; ---------------------------------------------------------------------------
;; red side FIRST (guard-family law): every miss class must fire
;; ---------------------------------------------------------------------------

(deftest red-side-fires
  (testing "the literal 0.5.3 tag-day miss: dated header, both bumps skipped"
    (let [problems (problems-for dated-changelog-053 "0.5.2" "0.5.2")]
      (is (seq problems))
      (is (some #(str/includes? % "dated header says 0.5.3") problems)
          "must name the header/build.clj disagreement")))

  (testing "build.clj/install.sh divergence names both files and both values"
    (let [problems (problems-for dated-changelog-053 "0.5.3" "0.5.2")]
      (is (= 1 (count problems)))
      (let [msg (first problems)]
        (is (str/includes? msg "build.clj (def version \"0.5.3\")"))
        (is (str/includes? msg "install.sh DEFAULT_VERSION=\"0.5.2\"")))))

  (testing "dated section with no ## Stats block (the literal 0.5.3 rider miss)"
    (let [no-stats (changelog-fixture
                    "0.5.3" ""
                    "**Release Date:** 2026-07-28\n\n## Fixes\n\n- A fix.\n\n")
          problems (problems-for no-stats "0.5.3" "0.5.3")]
      (is (= 1 (count problems)))
      (is (str/includes? (first problems) "no `## Stats` fenced block"))))

  (testing "malformed Stats first line (counts without a producing command)"
    (let [bare-counts (changelog-fixture
                       "0.5.3" ""
                       (str "**Release Date:** 2026-07-28\n\n## Stats\n\n"
                            "```\n2012 tests, 6939 assertions, 0 failures\n```\n\n"))
          problems (problems-for bare-counts "0.5.3" "0.5.3")]
      (is (= 1 (count problems)))
      (is (str/includes? (first problems) "Stats first line"))))

  (testing "a failing suite line does not pass as a Stats line"
    (let [red-suite (changelog-fixture
                     "0.5.3" ""
                     (str "**Release Date:** 2026-07-28\n\n## Stats\n\n"
                          "```\n2012 tests, 6939 assertions, 3 failures (./bin/kaocha)\n```\n\n"))]
      (is (= 1 (count (problems-for red-suite "0.5.3" "0.5.3"))))))

  (testing "(Unreleased) with build.clj already bumped — the early-bump miss"
    (let [next-cycle (changelog-fixture "0.5.3" " (Unreleased)" "## Fixes\n\n- Pending.\n\n")
          problems (problems-for next-cycle "0.5.3" "0.5.3")]
      ;; prior section in the fixture is 0.5.2; build.clj must still say 0.5.2
      (is (= 1 (count problems)))
      (is (str/includes? (first problems) "the bump belongs to the tagger"))))

  (testing "ambiguous top section: both markers, or neither"
    (let [both (changelog-fixture "0.5.3" " (Unreleased)"
                                  "**Release Date:** 2026-07-28\n\n")
          neither (changelog-fixture "0.5.3" "" "## Fixes\n\n- A fix.\n\n")]
      (is (some #(str/includes? % "found both or neither")
                (problems-for both "0.5.2" "0.5.2")))
      (is (some #(str/includes? % "found both or neither")
                (problems-for neither "0.5.3" "0.5.3")))))

  (testing "unparseable surfaces are reported, not silently skipped"
    (is (= ["build.clj: no `(def version \"X.Y.Z\")` found"]
           (agreement-problems {:changelog dated-changelog-053
                                :build-clj "(ns build)\n"
                                :install-sh (install-sh-fixture "0.5.3")})))
    (is (= ["CHANGELOG.md: no top `# Changelog: X.Y.Z` header found"]
           (agreement-problems {:changelog "# Some Other Doc\n"
                                :build-clj (build-clj-fixture "0.5.3")
                                :install-sh (install-sh-fixture "0.5.3")})))))

;; ---------------------------------------------------------------------------
;; green side
;; ---------------------------------------------------------------------------

(deftest green-side-passes
  (testing "dated top section, all three surfaces agreeing, real Stats line"
    (is (= [] (problems-for dated-changelog-053 "0.5.3" "0.5.3"))))

  (testing "(Unreleased) top section with build.clj at the prior release"
    (is (= [] (problems-for unreleased-changelog-053 "0.5.2" "0.5.2")))))

;; ---------------------------------------------------------------------------
;; live guard over the repo's actual files
;; ---------------------------------------------------------------------------

(deftest version-surfaces-agree
  (let [paths {:changelog "CHANGELOG.md"
               :build-clj "build.clj"
               :install-sh "release/install.sh"}]
    (doseq [[_ p] paths]
      (is (.exists (io/file p))
          (str p " is missing — it ships in every deployment instantiation "
               "that carries this test (public-top-level-allowlist.txt)")))
    (when (every? #(.exists (io/file (val %))) paths)
      (let [problems (agreement-problems (update-vals paths slurp))]
        (is (empty? problems)
            (str "Version surfaces disagree (sl-h0oj):\n"
                 (str/join "\n" problems)))))))
