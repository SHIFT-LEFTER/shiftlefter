(ns shiftlefter.arch-doc.derive
  "Derive the public docs/architecture/* pages from the canonical internal
   maps in notes/architecture/* (sl-9uuv, source-internal / public-derived).

   The canon keeps everything — findings ledgers, probe records, process
   narrative — and is re-stamped freely. This transform projects a
   publishable page from it, mechanically:

   1. `## Findings` sections are dropped (heading to next `##`/EOF).
   2. `<!-- sl:internal -->` … `<!-- /sl:internal -->` blocks are dropped
      (marker lines inclusive); the same markers within ONE line drop just
      that span (how canon keeps table-cell finding refs internally).
   3. Bead ids (`sl-xxxx`) are KEPT — they are citations, like the ids in
      every other public doc (Chair ruling 2026-09-07, reversed at
      sl-bgar). What stays out of a public page is process narrative, and
      that lives inside the fences above, never in the spine.
   4. `%%` comment lines inside mermaid fences are dropped (canon parks
      in-diagram finding refs there; mermaid ignores them).
   5. A standard 'How this map stays true' section is appended, and a
      GENERATED banner is prepended above the H1 (the site sync drops
      pre-H1 lines, so the banner never reaches shiftlefter.com).

   Deterministic: derivation is a pure function of the canon text. The
   drift guard (test/shiftlefter/arch_doc/derive_test.clj) fails the suite
   when a committed derived page differs from fresh derivation — derived
   pages are GENERATED-ONLY, like docs/AGENT.md.

   Runner: `bb scripts/derive-arch-docs.bb` (this ns is bb-compatible —
   clojure.string + slurp/spit only)."
  (:require [clojure.string :as str]))

(def pages
  "Basenames of the derived architecture set, canon -> public."
  ["module-map" "invocation-map" "control-loop" "data-shapes" "repl-lifetimes"
   "exit-codes" "index"])

(def canon-dir "notes/architecture")
(def derived-dir "docs/architecture")

(defn drop-internal-content
  "Remove every <!-- sl:internal -->…<!-- /sl:internal --> span from the
   full text, markers inclusive — one dotall pass, so spans may be a
   marker-fenced block of lines OR an inline fragment (even one that
   wraps across a line break; the surrounding text joins up). Whole lines
   left empty become blank lines for collapse-blank-runs. An unclosed
   opener is dropped to end-of-text — never leaked."
  [text]
  (-> text
      (str/replace #"(?s)<!-- sl:internal -->.*?<!-- /sl:internal -->" "")
      (str/replace #"(?s)<!-- sl:internal -->.*\z" "")))

(defn- drop-findings-sections
  "Drop every `## Findings` section: the heading through the line before the
   next `## ` heading (or EOF)."
  [lines]
  (loop [ls lines, out [], dropping? false]
    (if-let [line (first ls)]
      (cond
        (re-matches #"## Findings\s*" line)
        (recur (rest ls) out true)

        (and dropping? (str/starts-with? line "## "))
        (recur (rest ls) (conj out line) false)

        dropping?
        (recur (rest ls) out true)

        :else
        (recur (rest ls) (conj out line) false))
      out)))

(defn- mermaid-open? [line] (re-find #"^\s*```\s*mermaid" line))
(defn- fence? [line] (str/starts-with? (str/triml line) "```"))

(defn- tidy-spaces
  "Collapse doubled spaces inside a prose or mermaid line, keeping the
   leading indentation — the gap an inline internal-span drop leaves
   ('keeps <span> going' → 'keeps  going') closes up."
  [line]
  (let [[_ indent rest-of-line] (re-matches #"(\s*)(.*)" line)]
    (str indent (str/replace rest-of-line #"  +" " "))))

(defn- transform-lines
  "The per-line pass, fence-aware. Inside a mermaid fence: drop `%%`
   comment lines, tidy the rest. Inside any OTHER code fence: pass the
   line through untouched (a captured artifact). Outside fences: tidy —
   internal spans are already gone (drop-internal-content runs on the
   full text before lines exist), and the spine's own words, bead ids
   included, are the public page."
  [lines]
  (loop [ls lines, out [], fence nil]
    (if-let [line (first ls)]
      (cond
        (and fence (fence? line))
        (recur (rest ls) (conj out line) nil)

        (= fence :mermaid)
        (if (str/starts-with? (str/trim line) "%%")
          (recur (rest ls) out fence)
          (recur (rest ls) (conj out (tidy-spaces line)) fence))

        fence
        (recur (rest ls) (conj out line) fence)

        (fence? line)
        (recur (rest ls) (conj out line) (if (mermaid-open? line) :mermaid :other))

        :else
        (recur (rest ls) (conj out (tidy-spaces line)) nil))
      out)))

(def stays-true-section
  "The closing section every derived page carries. It claims exactly what
   the drift guard proves for EVERY page — derived from a canon source,
   regenerated never hand-edited, guarded — and nothing more: probe runs
   and pin re-verification are per-page facts that the pages carrying them
   state in their own bodies (the stamped maps, the exit-code table)."
  ["## How this page stays true"
   ""
   "This page is a mechanical projection of a live-maintained internal source,"
   "regenerated — never hand-edited — by the derivation pipeline and"
   "drift-guarded by the test suite: a hand edit here fails a test, and so"
   "does a page that no longer matches its source."])

(defn- banner [page]
  ["<!-- GENERATED — DO NOT EDIT. Derived from the internal source"
   (str "     " canon-dir "/" page ".md (development repo) by")
   "     scripts/derive-arch-docs.bb. Edit the source, then re-run:"
   "     bb scripts/derive-arch-docs.bb -->"])

(defn- trim-trailing-blanks [lines]
  (->> lines reverse (drop-while str/blank?) reverse vec))

(defn- collapse-blank-runs
  "Collapse runs of 2+ blank lines (the crater a dropped section or fence
   leaves) to one."
  [lines]
  (->> lines
       (partition-by str/blank?)
       (mapcat (fn [run] (if (str/blank? (first run)) [""] run)))
       vec))

(defn derive-page
  "Pure derivation: canon markdown text -> derived public markdown text."
  [page canon-text]
  (let [body (->> (str/split-lines (drop-internal-content canon-text))
                  drop-findings-sections
                  transform-lines
                  (map #(str/replace % #" +$" ""))
                  collapse-blank-runs
                  (drop-while str/blank?)
                  vec
                  trim-trailing-blanks)]
    (str (str/join "\n" (concat (banner page) [""] body [""] stays-true-section))
         "\n")))

;; ---------------------------------------------------------------------------
;; Warn-only lints on the DERIVED output (calibration-verdict rules, sl-9uuv;
;; the bare-fragment rule retired with the id stripping at sl-bgar): the
;; derive never fails on these — they flag canon spine text whose public
;; projection still carries a wrong-posture reference.
;; ---------------------------------------------------------------------------

(defn lint-derived
  "Warn-only lint of one derived page's text. Returns a vector of warning
   strings (empty when clean):
   - yearless MM-DD dates (Rule B — spine dates are full ISO)
   - no-users-posture phrases (the wordlist rule: 'first users',
     'no users yet', case-insensitive)"
  [page text]
  (-> []
      ;; month-shaped first pair only, so pin line ranges (56-59) don't hit
      (into (when-let [hits (seq (distinct (re-seq #"\b(?<!\d-)(?:0[1-9]|1[0-2])-\d{2}\b(?!-)" text)))]
              [(str page ": yearless date(s) " (str/join ", " (take 5 hits))
                    " — spine dates carry full years")]))
      (into (for [phrase ["first users" "no users yet"]
                  :when (str/includes? (str/lower-case text) phrase)]
              (str page ": no-users-posture phrase '" phrase
                   "' — rephrase in canon (era/release anchor)")))))

(defn derive-all!
  "Read every canon page, write every derived page. Returns a map of
   page -> lint warnings (warn-only; empty vectors when clean)."
  []
  (into {}
        (for [page pages]
          (let [canon (slurp (str canon-dir "/" page ".md"))
                out (derive-page page canon)]
            (spit (str derived-dir "/" page ".md") out)
            [page (lint-derived page out)]))))

(defn -main [& _]
  (let [warnings (apply concat (vals (derive-all!)))]
    (doseq [w warnings] (println "WARN" w))
    (println "derived" (count pages) "architecture pages ->" derived-dir
             (if (seq warnings) (str "(" (count warnings) " warnings)") "(clean)"))))
