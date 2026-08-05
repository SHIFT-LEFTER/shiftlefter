(ns shiftlefter.gherkin.printer
  "Lossless printer that reconstructs original input from tokens.

   This is a 'do nothing' printer - it proves the lexer preserves all
   information needed for byte-for-byte roundtrip without any formatting
   or transformation.

   Policy B: Parse errors => refuse to format/rewrite, return error map."
  (:require [shiftlefter.gherkin.lexer :as lexer]
            [shiftlefter.gherkin.parser :as parser]
            [shiftlefter.gherkin.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]))

;; -----------------------------------------------------------------------------
;; Specs
;; -----------------------------------------------------------------------------

(s/def ::status #{:ok :error})
(s/def ::reason #{:parse-errors :mismatch
                  :io/utf8-decode-failed :io/file-not-found :io/read-error})
(s/def ::path string?)
(s/def ::details (s/coll-of map?))

(s/def ::ok-result
  (s/keys :req-un [::status ::path]))

(s/def ::error-result
  (s/keys :req-un [::status ::reason]
          :opt-un [::path ::details]))

(s/def ::fmt-result
  (s/or :ok ::ok-result :error ::error-result))

;; -----------------------------------------------------------------------------
;; Core functions
;; -----------------------------------------------------------------------------

(defn print-tokens
  "Concatenate :raw fields from tokens to reconstruct original input.
   Excludes the EOF token (which has empty :raw)."
  [tokens]
  (->> tokens
       (remove #(= :eof (:type %)))
       (map :raw)
       (apply str)))

(s/fdef print-tokens
  :args (s/cat :tokens (s/coll-of :shiftlefter.gherkin.tokens/token))
  :ret string?)

(defn roundtrip-check
  "Check if file round-trips perfectly via lex -> print-tokens — the
   lossless-fidelity verification (was `fmt-check`, renamed sl-c1u8). Not
   the canonical-style check backing `sl fmt --check` — that is
   `shiftlefter.gherkin.api/fmt-check`.

   Returns:
   - {:status :ok :path path} if roundtrip matches exactly
   - {:status :error :reason :parse-errors :details [...]} if parse fails
   - {:status :error :reason :mismatch :path path ...} if bytes differ
   - {:status :error :reason :io/utf8-decode-failed ...} if file is not valid UTF-8
   - {:status :error :reason :io/file-not-found ...} if file doesn't exist"
  [path]
  (let [read-result (io/read-file-utf8 path)]
    (if (= :error (:status read-result))
      ;; I/O error - return as-is (already has :status, :reason, :path, :message)
      read-result
      ;; Read succeeded - proceed with roundtrip check
      (let [original (:content read-result)
            tokens (vec (lexer/lex original))
            {:keys [errors]} (parser/parse tokens)]
        (cond
          (seq errors)
          {:status :error
           :reason :parse-errors
           :path path
           :details errors}

          :else
          (let [reconstructed (print-tokens tokens)]
            (if (= original reconstructed)
              {:status :ok :path path}
              {:status :error
               :reason :mismatch
               :path path
               :original-length (count original)
               :reconstructed-length (count reconstructed)})))))))

(s/fdef roundtrip-check
  :args (s/cat :path ::path)
  :ret ::fmt-result)

;; -----------------------------------------------------------------------------
;; String-based API (for testing without file I/O)
;; -----------------------------------------------------------------------------

(defn roundtrip
  "Roundtrip a string through lex -> print-tokens.
   Returns the reconstructed string (for testing)."
  [input]
  (-> input lexer/lex vec print-tokens))

(defn roundtrip-ok?
  "Check if a string roundtrips perfectly."
  [input]
  (= input (roundtrip input)))

;; -----------------------------------------------------------------------------
;; Canonical Formatter
;; -----------------------------------------------------------------------------
;;
;; Canonical style rules:
;;   - 2 spaces per indentation level
;;   - Align table columns (pad cells to max width)
;;   - Strip trailing whitespace
;;   - 1 blank line between sections (Feature, Scenario, Background, Rule)
;;   - Tags on their own line, space-separated
;;   - Docstrings indented to step level + 2
;;   - Descriptions (Feature, Rule, Scenario, Background, Examples): directly
;;     under the header, one indent level deeper, then 1 blank line before the
;;     next block. Dedented by the common leading-whitespace prefix so relative
;;     indentation inside a description is preserved (sl-2c68).
;; -----------------------------------------------------------------------------

(def ^:private indent-unit "  ")  ; 2 spaces

(defn- indent
  "Create indentation string for given level."
  [level]
  (apply str (repeat level indent-unit)))

;; -----------------------------------------------------------------------------
;; Comment attachment (sl-lg8v)
;; -----------------------------------------------------------------------------
;;
;; Interior comments never enter the AST (the parser skips them), so the
;; canonical formatter re-attaches comment TOKENS to AST anchors by source
;; line: each comment binds to the FIRST anchor whose line is greater than
;; its own (following-node attachment); comments past the last anchor form
;; the trailing bucket, emitted at end of output. Comments before the
;; Feature anchor emit at the very top, before the language header — the
;; position top-of-file comments always had. Emission indentation is the
;; anchor's canonical indent: canonical style owns comment placement like
;; everything else. Known, deliberate cost: a trailing note after scenario
;; A's last step rides forward to the next block's header (decision record:
;; _docs/wiki/decisions/parser.md).

(defn- examples-anchors
  "Anchors for one Examples block: the block itself, then its rows. Examples
   under a scenario are plain maps (not Examples records), so this is
   matched by shape, not type."
  [ex]
  (concat [ex]
          (when (:table-header-row ex) [(:table-header-row ex)])
          (:table-body-rows ex)))

(defn- anchors-of
  "Emission anchors under an AST node, in document order, the node itself
   first. Anchors are every node the formatter can emit comments above:
   blocks, steps, and table rows. Docstrings are deliberately not anchors —
   a comment between a step and its docstring fence attaches past it."
  [node]
  (cond
    (or (instance? shiftlefter.gherkin.parser.Feature node)
        (instance? shiftlefter.gherkin.parser.Rule node))
    (cons node (mapcat anchors-of (:children node)))

    (instance? shiftlefter.gherkin.parser.Background node)
    (cons node (mapcat anchors-of (:steps node)))

    (or (instance? shiftlefter.gherkin.parser.Scenario node)
        (instance? shiftlefter.gherkin.parser.ScenarioOutline node))
    (concat [node]
            (mapcat anchors-of (:steps node))
            (mapcat examples-anchors (:examples node)))

    (instance? shiftlefter.gherkin.parser.Step node)
    (cons node (when (instance? shiftlefter.gherkin.parser.DataTable (:argument node))
                 (:rows (:argument node))))

    :else nil))

(defn- comment-attachments
  "Assign comment tokens to anchors by the following-node rule. Returns
   {:by-anchor {location [text ...]} :trailing [text ...]} — keyed by the
   anchor node's :location (unique within one parse), texts in source
   order, trimmed with their leading #."
  [ast comment-tokens]
  (let [anchors (->> ast
                     (mapcat anchors-of)
                     (remove nil?)
                     (map (fn [n] [(-> n :location :line) (:location n)]))
                     (sort-by first))]
    (loop [comments (sort-by #(-> % :location :line) comment-tokens)
           by-anchor {}
           trailing []]
      (if-let [{:keys [value location]} (first comments)]
        (let [[[_ anchor-loc] :as remaining]
              (drop-while (fn [[line _]] (<= line (:line location))) anchors)]
          (if (seq remaining)
            (recur (rest comments)
                   (update by-anchor anchor-loc (fnil conj []) value)
                   trailing)
            (recur (rest comments) by-anchor (conj trailing value))))
        {:by-anchor by-anchor :trailing trailing}))))

(defn- attached-comments-block
  "Formatted comment lines attached to node, one per line at indent-level,
   newline-terminated for splicing directly above the node's first line.
   nil when the node has no attached comments."
  [att node indent-level]
  (when-let [texts (seq (get att (:location node)))]
    (str (str/join "\n" (map #(str (indent indent-level) %) texts)) "\n")))

(defn- max-col-widths
  "Calculate max width for each column across all rows."
  [rows]
  (when (seq rows)
    (let [all-cells (map :cells rows)]
      (apply map (fn [& col-vals]
                   (apply max (map count col-vals)))
             all-cells))))

(defn- format-table-row
  "Format a table row with aligned columns."
  [cells col-widths indent-str]
  (let [padded (map (fn [cell width]
                      (let [cell-str (or cell "")]
                        (str cell-str (apply str (repeat (- width (count cell-str)) " ")))))
                    cells col-widths)]
    (str indent-str "| " (str/join " | " padded) " |")))

(defn- format-data-table
  "Format a DataTable with aligned columns. Attached comments emit above
   their row at the table's indent (sl-lg8v)."
  [table indent-level att]
    (let [rows (:rows table)
        col-widths (max-col-widths rows)
        indent-str (indent indent-level)]
    (->> rows
         (map #(str (attached-comments-block att % indent-level)
                    (format-table-row (:cells %) col-widths indent-str)))
         (str/join "\n"))))

(defn- format-docstring
  "Format a Docstring with proper indentation."
  [docstring indent-level]
  (let [indent-str (indent indent-level)
        fence (if (= :backtick (:fence docstring)) "```" "\"\"\"")
        media (or (:mediaType docstring) "")
        content (:content docstring)
        ;; Split content lines and indent each
        lines (str/split-lines content)
        indented-lines (map #(str indent-str %) lines)]
    (str indent-str fence media "\n"
         (str/join "\n" indented-lines)
         "\n"  ; Always newline before closing fence
         indent-str fence)))

(defn- format-step
  "Format a Step.
   Uses :keyword-text (original language keyword with trailing space) when available."
  [step indent-level att]
  (let [indent-str (indent indent-level)
        keyword-str (or (:keyword-text step) (str (:keyword step) " "))
        text (:text step)
        step-line (str (attached-comments-block att step indent-level)
                       indent-str keyword-str text)
        arg (:argument step)]
    (cond
      (nil? arg)
      step-line

      (instance? shiftlefter.gherkin.parser.DataTable arg)
      (str step-line "\n" (format-data-table arg (inc indent-level) att))

      (instance? shiftlefter.gherkin.parser.Docstring arg)
      (str step-line "\n" (format-docstring arg (inc indent-level)))

      :else
      step-line)))

(defn- format-steps
  "Format a sequence of steps."
  [steps indent-level att]
  (->> steps
       (map #(format-step % indent-level att))
       (str/join "\n")))

(defn- format-tags
  "Format tags on a single line.
   Tags are now rich format: [{:name \"@tag\" :location Location} ...]"
  [tags indent-level]
  (when (seq tags)
    (str (indent indent-level) (str/join " " (map :name tags)))))

(defn- leading-ws-prefix
  "Leading whitespace of a line."
  [line]
  (re-find #"^[ \t]*" line))

(defn- common-prefix
  "Longest common prefix of two strings."
  [a b]
  (->> (map vector a b)
       (take-while (fn [[x y]] (= x y)))
       (map first)
       (apply str)))

(defn- dedent-lines
  "Strip the longest common leading-whitespace prefix of the non-blank lines
   from each non-blank line. Blank lines become empty strings."
  [lines]
  (let [non-blank (remove str/blank? lines)
        prefix (when (seq non-blank)
                 (reduce common-prefix (map leading-ws-prefix non-blank)))]
    (map #(if (str/blank? %) "" (subs % (count prefix))) lines)))

(defn- format-description
  "Format description lines to a fixed column.

   Dedents by the common leading-whitespace prefix (preserving intentional
   relative indentation), then indents each non-blank line to indent-level.
   This makes canonical formatting a fixed point regardless of the source
   indentation the parser preserved."
  [description indent-level]
  (when (and description (not (str/blank? description)))
    (let [indent-str (indent indent-level)
          lines (dedent-lines (str/split-lines description))]
      (str/join "\n" (map #(if (str/blank? %) "" (str indent-str %)) lines)))))

(defn- format-examples
  "Format an Examples table. Renders from the full TableRow records
   (:table-header-row/:table-body-rows) so between-row comments have
   anchors (sl-lg8v); cells are identical to :table-header/:table-body."
  [examples indent-level att]
  (let [indent-str (indent indent-level)
        kw (or (:keyword-text examples) "Examples")
        name-str (if (str/blank? (:name examples)) "" (str ": " (:name examples)))
        tags-line (format-tags (:tags examples) indent-level)
        header (str indent-str kw ":" name-str)
        desc (format-description (:description examples) (inc indent-level))
        header-row (:table-header-row examples)
        body-rows (:table-body-rows examples)
        all-rows (remove nil? (cons header-row body-rows))
        col-widths (when (seq all-rows)
                     (apply map (fn [& col-vals]
                                  (apply max (map #(count (str %)) col-vals)))
                            (map :cells all-rows)))
        table-indent-level (inc indent-level)
        table-indent (indent table-indent-level)
        format-row (fn [row]
                     (let [padded (map (fn [cell width]
                                         (let [cell-str (str cell)]
                                           (str cell-str (apply str (repeat (- width (count cell-str)) " ")))))
                                       (:cells row) col-widths)]
                       (str (attached-comments-block att row table-indent-level)
                            table-indent "| " (str/join " | " padded) " |")))]
    (str (attached-comments-block att examples indent-level)
         (when tags-line (str tags-line "\n"))
         header "\n"
         (when desc (str desc "\n\n"))
         (str/join "\n" (map format-row all-rows)))))

(defn- format-scenario
  "Format a Scenario or ScenarioOutline."
  [scenario indent-level att]
  (let [indent-str (indent indent-level)
        type-keyword (or (:keyword-text scenario)
                         (if (= :scenario-outline (:type scenario))
                           "Scenario Outline"
                           "Scenario"))
        name-str (:name scenario)
        tags-line (format-tags (:tags scenario) indent-level)
        header (str indent-str type-keyword ": " name-str)
        desc (format-description (:description scenario) (inc indent-level))
        steps-str (format-steps (:steps scenario) (inc indent-level) att)
        examples (:examples scenario)]
    (str (attached-comments-block att scenario indent-level)
         (when tags-line (str tags-line "\n"))
         header "\n"
         (when desc (str desc "\n\n"))
         steps-str
         (when (seq examples)
           (str "\n\n" (str/join "\n\n" (map #(format-examples % indent-level att) examples)))))))

(defn- format-background
  "Format a Background section."
  [background indent-level att]
  (let [indent-str (indent indent-level)
        kw (or (:keyword-text background) "Background")
        tags-line (format-tags (:tags background) indent-level)
        header (str indent-str kw ":")
        desc (format-description (:description background) (inc indent-level))
        steps-str (format-steps (:steps background) (inc indent-level) att)]
    (str (attached-comments-block att background indent-level)
         (when tags-line (str tags-line "\n"))
         header "\n"
         (when desc (str desc "\n\n"))
         steps-str)))

(defn- format-rule
  "Format a Rule node."
  [rule indent-level att]
  (let [indent-str (indent indent-level)
        kw (or (:keyword-text rule) "Rule")
        tags-line (format-tags (:tags rule) indent-level)
        header (str indent-str kw ": " (:name rule))
        desc (format-description (:description rule) (inc indent-level))
        bg (parser/get-background rule)
        scenarios (parser/get-scenarios rule)
        bg-str (when bg (format-background bg (inc indent-level) att))
        scenarios-str (->> scenarios
                           (map #(format-scenario % (inc indent-level) att))
                           (str/join "\n\n"))]
    (str (attached-comments-block att rule indent-level)
         (when tags-line (str tags-line "\n"))
         header
         (when desc (str "\n" desc))
         (when bg-str (str "\n\n" bg-str))
         (when (seq scenarios) (str "\n\n" scenarios-str)))))

(defn- format-feature
  "Format a Feature node. Feature-attached comments (those before the
   Feature line in the source) emit at the very top, before the language
   header, separated from it by a blank line — the position top-of-file
   comments have always had (sl-lg8v)."
  [feature att]
  (let [kw (or (:keyword-text feature) "Feature")
        language (:language feature)
        language-header (when language (str "# language: " language "\n"))
        top-comments (attached-comments-block att feature 0)
        tags-line (format-tags (:tags feature) 0)
        header (str kw ": " (:name feature))
        desc (format-description (:description feature) 1)
        bg (parser/get-background feature)
        scenarios (parser/get-scenarios feature)
        rules (parser/get-rules feature)
        bg-str (when bg (format-background bg 1 att))
        scenarios-str (->> scenarios
                           (map #(format-scenario % 1 att))
                           (str/join "\n\n"))
        rules-str (->> rules
                       (map #(format-rule % 1 att))
                       (str/join "\n\n"))]
    (str top-comments
         (when top-comments "\n")
         language-header
         (when tags-line (str tags-line "\n"))
         header
         (when desc (str "\n" desc))
         (when bg-str (str "\n\n" bg-str))
         (when (seq scenarios) (str "\n\n" scenarios-str))
         (when (seq rules) (str "\n\n" rules-str))
         "\n")))


(defn format-canonical
  "Format AST to canonical Gherkin style.

   Canonical style:
   - 2 spaces indentation
   - Aligned table columns
   - No trailing whitespace
   - Single blank line between sections

   With comment-tokens (sl-lg8v), every comment is preserved: re-attached
   to its following AST anchor and emitted at that anchor's indent, with
   comments past the last anchor trailing at the end. The 1-arity form
   formats without comments (legacy shape for direct AST callers).

   Returns:
   - {:status :ok :output formatted-string} on success"
  ([ast] (format-canonical ast []))
  ([ast comment-tokens]
   (let [{:keys [by-anchor trailing]} (comment-attachments ast comment-tokens)
         features (filter #(instance? shiftlefter.gherkin.parser.Feature %) ast)
         feature-str (str/join "\n" (map #(format-feature % by-anchor) features))
         trailing-str (when (seq trailing)
                        (str (str/join "\n" trailing) "\n"))
         raw-output (cond
                      (and (seq features) trailing-str)
                      (str feature-str "\n" trailing-str)

                      (seq features) feature-str
                      :else (or trailing-str ""))
         ;; Strip trailing whitespace from each line
         lines (str/split-lines raw-output)
         stripped-lines (map #(str/replace % #"[ \t]+$" "") lines)]
     {:status :ok
      :output (str (str/join "\n" stripped-lines)
                   (when (str/ends-with? raw-output "\n") "\n"))})))

(defn fmt-canonical
  "Format a file to canonical style.

   Returns:
   - {:status :ok :path path :output formatted-string} on success
   - {:status :error :reason :parse-errors :details [...]} on parse failure
   - {:status :error :reason :io/utf8-decode-failed ...} if file is not valid UTF-8
   - {:status :error :reason :io/file-not-found ...} if file doesn't exist"
  [path]
  (let [read-result (io/read-file-utf8 path)]
    (if (= :error (:status read-result))
      ;; I/O error - return as-is
      read-result
      ;; Read succeeded - proceed with canonical formatting
      (let [original (:content read-result)
            tokens (vec (lexer/lex original))
            {:keys [ast errors]} (parser/parse tokens)]
        (if (seq errors)
          {:status :error
           :reason :parse-errors
           :path path
           :details errors}
          (let [result (format-canonical ast (filter #(= :comment (:type %)) tokens))]
            (assoc result :path path)))))))

(defn canonical
  "Format a string to canonical style.

   Returns formatted string on success.
   Throws on parse errors."
  [input]
  (let [tokens (vec (lexer/lex input))
        {:keys [ast errors]} (parser/parse tokens)]
    (if (seq errors)
      (throw (ex-info "Parse errors" {:errors errors}))
      (:output (format-canonical ast (filter #(= :comment (:type %)) tokens))))))

;; -----------------------------------------------------------------------------
;; Comment-loss detection (sl-q2uj guard — now a permanent backstop, sl-lg8v)
;; -----------------------------------------------------------------------------
;;
;; Canonical formatting preserves every comment (attachment emission,
;; sl-lg8v), so this check should ALWAYS return empty. It stays wired as a
;; backstop invariant: if a future printer regression drops comments,
;; `fmt --write` refuses the destructive write instead of silently
;; destroying them (Chair ruling at lg8v plan review — the two-seam
;; philosophy). The four-file comment corpus pins the backstop itself in
;; printer_test.

(s/def ::line pos-int?)
(s/def ::text string?)
(s/def ::lost-comment (s/keys :req-un [::line ::text]))

(defn- comment-tokens
  "Comment tokens in a Gherkin string, in document order."
  [s]
  (filter #(= :comment (:type %)) (lexer/lex s)))

(defn lost-comments
  "Comment lines in s that canonical formatting would drop — the sl-q2uj
   guard, kept as a permanent backstop now that canonical formatting
   preserves comments (sl-lg8v): expected to always return empty; any
   non-empty result is a printer bug caught before a destructive write.

   Compares comment tokens in the input against those in the canonical
   output, matched by trimmed text as a multiset (re-indentation and
   re-attachment count as preserved). Returns a vector of
   {:line N :text \"# ...\"} in input order. Throws on parse errors (like
   `canonical`)."
  [s]
  (let [kept (frequencies (map :value (comment-tokens (canonical s))))
        note-loss (fn [[acc kept] tok]
                    (if (pos? (get kept (:value tok) 0))
                      [acc (update kept (:value tok) dec)]
                      [(conj acc {:line (:line (:location tok))
                                  :text (:value tok)})
                       kept]))]
    (first (reduce note-loss [[] kept] (comment-tokens s)))))

(s/fdef lost-comments
  :args (s/cat :s string?)
  :ret (s/coll-of ::lost-comment :kind vector?))

;; -----------------------------------------------------------------------------
;; Additional fdefs
;; -----------------------------------------------------------------------------

(s/fdef roundtrip
  :args (s/cat :input string?)
  :ret string?)

(s/fdef roundtrip-ok?
  :args (s/cat :input string?)
  :ret boolean?)

(s/fdef format-canonical
  :args (s/cat :ast (s/coll-of :shiftlefter.gherkin.parser/ast-node)
               :comment-tokens (s/? (s/coll-of :shiftlefter.gherkin.tokens/token)))
  :ret (s/keys :req-un [::status]))

(s/fdef fmt-canonical
  :args (s/cat :path ::path)
  :ret ::fmt-result)

(s/fdef canonical
  :args (s/cat :input string?)
  :ret string?)
