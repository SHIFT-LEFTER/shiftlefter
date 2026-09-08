(ns shiftlefter.arch-doc.derive
  "Derive the public docs/architecture/* pages from the canonical internal
   maps in notes/architecture/* (sl-9uuv, source-internal / public-derived).

   The canon keeps everything — findings ledgers, bead ids, probe records,
   process vocabulary — and is re-stamped freely. This transform projects a
   publishable page from it, mechanically:

   1. `## Findings` sections are dropped (heading to next `##`/EOF).
   2. `<!-- sl:internal -->` … `<!-- /sl:internal -->` blocks are dropped
      (marker lines inclusive); the same markers within ONE line drop just
      that span (how canon keeps table-cell finding refs internally).
   3. Bead ids (`sl-xxxx`) are stripped, KEEPING file:line pins — id-only
      parentheticals vanish whole; mixed parentheticals lose the id plus its
      separator. Grammar the stripper can't save is fixed in the CANON (the
      register discipline), never special-cased here.
   4. `%%` comment lines inside mermaid fences are dropped (canon parks
      in-diagram finding refs and bead pins there; mermaid ignores them).
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

(def ^:private id-exclusions
  ;; Tokens that match the bead-id pattern but are not beads. bin/sl-dev is
  ;; the dev-loop binary (control-loop § Method).
  #{"sl-dev"})

(def ^:private id-re
  ;; A bead id: sl- plus 3-4 alphanumerics at a word boundary. Long slug ids
  ;; (sl-interface-bundles-...) don't match — their 4th char is followed by
  ;; more word chars — and path prefixes like sl/results carry no hyphen.
  #"\bsl-[a-z0-9]{3,4}\b")

(defn- strip-ids-in-text
  "Remove bead-id tokens from a text fragment, cleaning the separators they
   leave behind: ', sl-x' / 'sl-x, ' / 'sl-x/' joints collapse; doubled
   spaces collapse."
  [s]
  (-> s
      ;; id followed by a joiner (comma/slash) — the id owns the joiner
      (str/replace #"\bsl-[a-z0-9]{3,4}\b\s*[,/;]\s*"
                   (fn [m] (if (some #(str/includes? m %) id-exclusions) m "")))
      ;; joiner followed by an id — the id owns the joiner
      (str/replace #"\s*[,/;]\s*\bsl-[a-z0-9]{3,4}\b"
                   (fn [m] (if (some #(str/includes? m %) id-exclusions) m "")))
      ;; em-dash-attached id: ' — sl-x' or 'sl-x — ' keeps ONE dash side tidy
      (str/replace #"\s+[—–-]\s+\bsl-[a-z0-9]{3,4}\b(?=\s*[)\]]|\s*$)"
                   (fn [m] (if (some #(str/includes? m %) id-exclusions) m "")))
      ;; whatever survives: drop the bare token
      (str/replace id-re (fn [m] (if (id-exclusions m) m "")))
      (str/replace #"  +" " ")))

(defn- residue-only?
  "True when a parenthetical's content, with its ids removed, holds nothing
   but separators, emphasis markers, dates, and whitespace — i.e. the group
   existed to carry ids and should vanish whole."
  [inner]
  (let [without-ids (str/replace inner id-re
                                 (fn [m] (if (id-exclusions m) m "")))]
    (and (re-find id-re inner)
         (str/blank? (str/replace without-ids
                                  #"(\d{4}-\d{2}-\d{2}|\d{2}-\d{2}|[,;/—–\-*`\s])+"
                                  "")))))

(defn strip-ids-in-line
  "Strip bead ids from one line, keeping file:line pins and the leading
   indentation. Id-only parentheticals (dates and emphasis allowed as
   residue) are removed whole, including a preceding space; mixed
   parentheticals and running prose lose the ids with separator cleanup."
  [line]
  (let [[_ indent rest-of-line] (re-matches #"(\s*)(.*)" line)]
    (str indent
         (-> rest-of-line
             (str/replace #" ?\(([^()]*)\)"
                          (fn [[whole inner]]
                            (if (residue-only? inner)
                              ""
                              (let [stripped (strip-ids-in-text inner)]
                                (if (= inner stripped)
                                  whole
                                  (str (when (str/starts-with? whole " ") " ")
                                       "(" (str/trim stripped) ")"))))))
             strip-ids-in-text
             (str/replace #" +$" "")))))

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

(defn- transform-lines
  "The per-line pass, fence-aware. Inside a mermaid fence: drop `%%` comment
   lines, strip ids from labels (indentation preserved). Inside any OTHER
   code fence: pass through untouched — fenced blocks are captured
   artifacts, not prose (a bead id inside one fails the cleanliness guard
   and is the canon's problem to fence or reword). Outside fences: drop
   inline internal spans, strip ids."
  [lines]
  (loop [ls lines, out [], fence nil]
    (if-let [line (first ls)]
      (cond
        (and fence (fence? line))
        (recur (rest ls) (conj out line) nil)

        (= fence :mermaid)
        (if (str/starts-with? (str/trim line) "%%")
          (recur (rest ls) out fence)
          (recur (rest ls) (conj out (strip-ids-in-line line)) fence))

        fence
        (recur (rest ls) (conj out line) fence)

        (fence? line)
        (recur (rest ls) (conj out line) (if (mermaid-open? line) :mermaid :other))

        :else
        (recur (rest ls) (conj out (strip-ids-in-line line)) nil))
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
;; Warn-only lints on the DERIVED output (calibration-verdict rules, sl-9uuv):
;; the derive never fails on these — they flag canon spine text whose public
;; projection still carries an unresolvable or wrong-posture reference.
;; ---------------------------------------------------------------------------

(defn live-bead-suffixes
  "The live bead-id suffix set (e.g. \"c76w\") read from a .beads issues.jsonl
   file, for the bare-fragment lint (Rule A: a stripped id can survive as a
   bare fragment like 'the c76w exercise cycle'). Returns #{} when the file
   is unreadable — the lint is warn-only and best-effort."
  [jsonl-path]
  (try
    (->> (re-seq #"\"id\":\"sl-([a-z0-9]{3,4})\"" (slurp jsonl-path))
         (map second)
         set)
    (catch Exception _ #{})))

(defn lint-derived
  "Warn-only lint of one derived page's text. Returns a vector of warning
   strings (empty when clean):
   - bare bead-id-suffix fragments against the live id set (Rule A)
   - yearless MM-DD dates (Rule B — spine dates are full ISO)
   - no-users-posture phrases (the wordlist rule)"
  [page text suffixes]
  (let [;; a fragment must carry at least one letter (pure numbers are
        ;; file:line pins and counts) and not be a ubiquitous code token
        noise #{"clj"}
        tokens (->> (re-seq #"\b[a-z0-9]{3,4}\b" text)
                    (filter #(re-find #"[a-z]" %))
                    (remove noise)
                    set)]
    (-> []
        (into (for [hit (sort (filter (comp suffixes str/lower-case) tokens))]
                (str page ": bare bead-id fragment '" hit
                     "' — reword in canon (date-or-description or era phrasing)")))
        ;; month-shaped first pair only, so pin line ranges (56-59) don't hit
        (into (when-let [hits (seq (distinct (re-seq #"\b(?<!\d-)(?:0[1-9]|1[0-2])-\d{2}\b(?!-)" text)))]
                [(str page ": yearless date(s) " (str/join ", " (take 5 hits))
                      " — spine dates carry full years")]))
        (into (for [phrase ["first users" "no users yet"]
                    :when (str/includes? (str/lower-case text) phrase)]
                (str page ": no-users-posture phrase '" phrase
                     "' — rephrase in canon (era/release anchor)"))))))

(defn derive-all!
  "Read every canon page, write every derived page. Returns a map of
   page -> lint warnings (warn-only; empty vectors when clean)."
  []
  (let [suffixes (live-bead-suffixes ".beads/issues.jsonl")]
    (into {}
          (for [page pages]
            (let [canon (slurp (str canon-dir "/" page ".md"))
                  out (derive-page page canon)]
              (spit (str derived-dir "/" page ".md") out)
              [page (lint-derived page out suffixes)])))))

(defn -main [& _]
  (let [warnings (apply concat (vals (derive-all!)))]
    (doseq [w warnings] (println "WARN" w))
    (println "derived" (count pages) "architecture pages ->" derived-dir
             (if (seq warnings) (str "(" (count warnings) " warnings)") "(clean)"))))
