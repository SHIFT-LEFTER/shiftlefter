(ns shiftlefter.arch-doc.derive-test
  "Guards for the architecture-docs derive pipeline (sl-9uuv): the committed
   docs/architecture/* pages are GENERATED-ONLY projections of
   notes/architecture/* — a hand edit to a derived page fails here, like
   docs/AGENT.md's guard. Rule tests use real-messy literals lifted from the
   canon corpus. The bead-id and process-vocabulary assertions retired at
   sl-bgar (ids and role names are citations, not leaks — Chair 2026-09-07)."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [shiftlefter.arch-doc.derive :as derive]))

;; The canon lives in notes/, which is NOT in the public export's allowlist
;; (scripts/release-staging/public-top-level-allowlist.txt), so the two
;; canon-reading guards self-skip there — EXPLICITLY, with the reason in the
;; assertion message, never a bare (is true) — and hard-assert everywhere the
;; canon exists (sl-u9z8 class ruling; the freshness guards use the same
;; idiom). Public-export skip path pinned below (canon-or-skip-test).

(defn- canon-or-skip
  "The canon text for `page`, or nil after recording the explicit skip."
  [page]
  (let [canon (io/file (str "notes/architecture/" page ".md"))]
    (if (.exists canon)
      (slurp canon)
      (do (testing "canon absent (public export) — self-skip"
            (is true (str "skipped: notes/architecture/" page
                          ".md is not in the public export")))
          nil))))

(deftest drift-guard-test
  (doseq [page derive/pages]
    (testing (str "docs/architecture/" page ".md equals fresh derivation")
      (when-let [canon (canon-or-skip page)]
        (let [derived (io/file (str "docs/architecture/" page ".md"))]
          (is (.exists derived) (str derived " must exist"))
          (is (= (slurp derived) (derive/derive-page page canon))
              (str "docs/architecture/" page ".md is stale or hand-edited — "
                   "it is GENERATED: edit notes/architecture/" page ".md "
                   "and re-run `bb scripts/derive-arch-docs.bb`")))))))

(deftest determinism-test
  (doseq [page derive/pages]
    (when-let [canon (canon-or-skip page)]
      (is (= (derive/derive-page page canon)
             (derive/derive-page page canon))))))

(deftest derived-cleanliness-test
  ;; Exactly three assertions per page (sl-bgar): the process journal stays
  ;; out (no marker, no Findings) and the stays-true section is there.
  (doseq [page derive/pages]
    (let [text (slurp (str "docs/architecture/" page ".md"))]
      (testing (str page ": no internal markers or findings sections survive")
        (is (not (str/includes? text "sl:internal")))
        (is (not (re-find #"(?m)^## Findings\s*$" text))))
      (testing (str page ": the stays-true section is present")
        (is (str/includes? text "## How this page stays true"))))))

;; ---------------------------------------------------------------------------
;; Rule tests — real-messy literals from the canon corpus (guard-family law)
;; ---------------------------------------------------------------------------

(deftest no-users-lint-rule-test
  (testing "red side: the data-shapes canon as first committed (e59af938),
            before the 55cc2294 reword — the leak this rule was built from"
    (let [leak (str "at 0.5.1; reopened STRENGTHEN-ONLY for the 0.5.5 window (sl-7c7i);\n"
                    "  re-locks at first users\" — see invocation-map § verdict contract.")
          warnings (derive/lint-derived "data-shapes" leak)]
      (is (= 1 (count warnings)))
      (is (str/starts-with? (first warnings)
                            "data-shapes: no-users-posture phrase 'first users'"))))
  (testing "red side: the same canon's stability-class table cell"
    (is (= 1 (count (derive/lint-derived
                     "data-shapes"
                     "| **Locked** | keys additive, never repurposed | exit-code table (strengthen-only until first users) |")))))
  (testing "red side: the other phrase, case-insensitive (CLAUDE.md's own line)"
    (let [warnings (derive/lint-derived
                    "t" "- **WE HAVE NO USERS YET.** (Chair, 08-12 — cheer for the day this line")]
      (is (some #(str/includes? % "no-users-posture phrase 'no users yet'") warnings))))
  (testing "green side: the reword that landed at 55cc2294"
    (is (empty? (derive/lint-derived
                 "data-shapes"
                 "at 0.5.1, reopened STRENGTHEN-ONLY for the 0.5.5 window (sl-7c7i),\n  re-locks at the contracts release — see invocation-map § verdict"))))
  (testing "green side: a bead id, a role name and a full ISO date are not warnings
            (the retired rules are gone; Rule B wants a full year)"
    (is (empty? (derive/lint-derived
                 "t" "ruled by Chair on 2026-08-30 (sl-7c7i, loader.clj:56-59); keys added by later beads must extend"))))
  (testing "Rule B, kept unchanged: a yearless date warns, a pin line range does not"
    (let [warnings (derive/lint-derived "t" "(pins re-verified 08-23)")]
      (is (= 1 (count warnings)))
      (is (str/includes? (first warnings) "yearless date(s) 08-23")))
    (is (empty? (derive/lint-derived "t" "consumed by the loader (loader.clj:56-59)")))))

(deftest canon-or-skip-test
  (testing "the public-export branch: no canon file → one explicit, message-bearing
            skip assertion, nil back (so the guard body never runs)"
    (is (nil? (#'shiftlefter.arch-doc.derive-test/canon-or-skip
               "no-such-page-9f3a")))))

(deftest structure-rules-test
  (let [canon (str "# T — sub\n\nSpine A (sl-7c7i).\n\n"
                   "<!-- sl:internal -->\ncanon-only banner\n<!-- /sl:internal -->\n\n"
                   "## Findings\n\nF1 — something (sl-t68j).\n\n"
                   "## Method\n\nProbe rig text.\n\n"
                   "| cell | keeps <!-- sl:internal -->(see F8)<!-- /sl:internal --> going |\n\n"
                   "```mermaid\nflowchart LR\n%% F2 pinned here (sl-52ly)\nA --> B\n```\n")
        out (derive/derive-page "t" canon)]
    (testing "findings section dropped, following section kept"
      (is (not (str/includes? out "F1 — something")))
      (is (str/includes? out "## Method")))
    (testing "internal block and inline span dropped"
      (is (not (str/includes? out "canon-only banner")))
      (is (not (str/includes? out "(see F8)")))
      (is (str/includes? out "| cell | keeps going |")))
    (testing "mermaid %% comment dropped, diagram lines kept"
      (is (not (str/includes? out "F2 pinned here")))
      (is (str/includes? out "A --> B")))
    (testing "bead ids: a spine citation survives; an id inside a dropped span,
              section or %% comment goes with its container (sl-bgar)"
      (is (str/includes? out "Spine A (sl-7c7i)."))
      (is (not (str/includes? out "sl-t68j")))
      (is (not (str/includes? out "sl-52ly"))))
    (testing "banner above H1, blurb at the end"
      (is (str/starts-with? out "<!-- GENERATED"))
      (is (str/includes? out "## How this page stays true")))))
