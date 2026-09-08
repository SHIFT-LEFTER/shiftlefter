(ns shiftlefter.arch-doc.derive-test
  "Guards for the architecture-docs derive pipeline (sl-9uuv): the committed
   docs/architecture/* pages are GENERATED-ONLY projections of
   notes/architecture/* — a hand edit to a derived page fails here, like
   docs/AGENT.md's guard. Rule tests use real-messy literals lifted from the
   canon corpus."
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

(def process-vocabulary-re
  "The process-vocabulary WORDLIST (sl-nm9o W20): words that name the
   development process, not the product, and must never reach a derived
   public page. Case-sensitive on purpose — the role names are proper nouns
   in this repo's register (a chair someone sits on, lowercase, is not a
   leak); `bead`/`beads` lowercase, the tracker's noun. Word-bounded, so
   `Towering`, `beaded`, `Chairman` do not match. Bead IDs (`sl-xxxx`) are
   the sibling rule above. No marker exemption is needed here: derived
   pages carry no `sl:internal` spans — the derive drops them before this
   text exists — so every match on a derived page is a leak from canon
   spine text, and the fix is a canon reword."
  #"\b(?:beads?|Tower|Trench|Warden|Herald|Chair)\b")

(defn process-vocabulary-hits
  "Every wordlist match in `text`, with a little context each — the failure
   message names the leak so the canon reword is one search away."
  [text]
  (vec (for [m (re-seq #".{0,30}\b(?:beads?|Tower|Trench|Warden|Herald|Chair)\b.{0,30}" text)]
         (str/trim m))))

(deftest derived-cleanliness-test
  (doseq [page derive/pages]
    (let [text (slurp (str "docs/architecture/" page ".md"))]
      (testing (str page ": no bead ids survive (sl-dev excluded as a binary name)")
        (is (empty? (remove #{"sl-dev"} (re-seq #"\bsl-[a-z0-9]{3,4}\b" text)))))
      (testing (str page ": no internal markers or findings sections survive")
        (is (not (str/includes? text "sl:internal")))
        (is (not (re-find #"(?m)^## Findings\s*$" text))))
      (testing (str page ": no process vocabulary survives (the wordlist)")
        (is (empty? (process-vocabulary-hits text))
            (str page ": process vocabulary leaked through the derive — reword "
                 "in notes/architecture/" page ".md: "
                 (str/join " | " (process-vocabulary-hits text)))))
      (testing (str page ": the stays-true section is present")
        (is (str/includes? text "## How this page stays true"))))))

;; ---------------------------------------------------------------------------
;; Rule tests — real-messy literals from the canon corpus (guard-family law)
;; ---------------------------------------------------------------------------

(deftest wordlist-rule-test
  (testing "red side: the real leak this rule was built from (data-shapes canon,
            reporter.clj's open-map comment quoted into a table cell)"
    (let [leak "| … | spec'd; keys added by later beads must extend, never break |"]
      (is (re-find process-vocabulary-re leak))
      (is (= 1 (count (process-vocabulary-hits leak))))))
  (testing "green side: the reword, and the words the rule deliberately spares"
    (is (empty? (process-vocabulary-hits
                 "| … | spec'd; keys added later must extend, never break |")))
    (is (empty? (process-vocabulary-hits
                 "a towering chair; beaded; the Chairman; sl-dev is a binary")))
    (is (seq (process-vocabulary-hits "Herald's"))
        "an apostrophe is a word boundary — the possessive of a role name is a hit")
    (is (seq (process-vocabulary-hits "ruled by Chair on 2026-08-30"))
        "the capitalized role name is the leak the rule exists for")))

(deftest canon-or-skip-test
  (testing "the public-export branch: no canon file → one explicit, message-bearing
            skip assertion, nil back (so the guard body never runs)"
    (is (nil? (#'shiftlefter.arch-doc.derive-test/canon-or-skip
               "no-such-page-9f3a")))))

(deftest id-strip-rules-test
  (testing "id-only parenthetical vanishes whole, preceding space included"
    (is (= "the invocation map's verdict table"
           (derive/strip-ids-in-line
            "the invocation map's verdict table (sl-7c7i)"))))
  (testing "bold-wrapped id-only parenthetical vanishes (repl-lifetimes shape)"
    (is (= "The chromedriver leak was a JVM-scoped resource"
           (derive/strip-ids-in-line
            "The chromedriver leak (**sl-34fu**) was a JVM-scoped resource"))))
  (testing "mixed parenthetical keeps the pin, loses the id (data-shapes shape)"
    (is (= "consumed by the loader (loader.clj:56-59)"
           (derive/strip-ids-in-line
            "consumed by the loader (sl-4mv8, loader.clj:56-59)"))))
  (testing "multi-id slash group vanishes whole (control-loop capture matrix)"
    (is (= "**Capture — two sites, one ruled matrix.**"
           (derive/strip-ids-in-line
            "**Capture — two sites, one ruled matrix (sl-3q9r/sl-sehc).**"))))
  (testing "date survives, trailing id goes (header stamp shape)"
    (is (= "(pins re-verified 2026-08-23)"
           (derive/strip-ids-in-line "(pins re-verified 2026-08-23, sl-d3cz)"))))
  (testing "sl-dev is a binary, not a bead (control-loop § Method)"
    (is (= "then `bin/sl-dev run` against the corpus"
           (derive/strip-ids-in-line "then `bin/sl-dev run` against the corpus"))))
  (testing "file:line pins are untouched"
    (is (= "read at doctor.clj:110 and step.clj:134"
           (derive/strip-ids-in-line "read at doctor.clj:110 and step.clj:134"))))
  (testing "id-and-date-only parenthetical vanishes whole"
    (is (= "the config-accessor edge"
           (derive/strip-ids-in-line "the config-accessor edge (sl-d3cz, 08-23)")))))

(deftest structure-rules-test
  (let [canon (str "# T — sub\n\nSpine A.\n\n"
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
    (testing "banner above H1, blurb at the end"
      (is (str/starts-with? out "<!-- GENERATED"))
      (is (str/includes? out "## How this page stays true")))))
