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
(s/def ::reason #{:parse-errors :mismatch :canonical/rules-unsupported
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

(defn fmt-check
  "Check if file round-trips perfectly via lex -> print-tokens.

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

(s/fdef fmt-check
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
;; -----------------------------------------------------------------------------

(def ^:private indent-unit "  ")  ; 2 spaces

(defn- indent
  "Create indentation string for given level."
  [level]
  (apply str (repeat level indent-unit)))

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
  "Format a DataTable with aligned columns."
  [table indent-level]
    (let [rows (:rows table)
        col-widths (max-col-widths rows)
        indent-str (indent indent-level)]
    (->> rows
         (map #(format-table-row (:cells %) col-widths indent-str))
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
  "Format a Step or MacroStep."
  [step indent-level]
  (let [indent-str (indent indent-level)
        keyword-str (:keyword step)
        text (:text step)
        ;; Check for macro marker (ends with " +")
        macro? (instance? shiftlefter.gherkin.parser.MacroStep step)
        step-line (str indent-str keyword-str " " text (when macro? " +"))
        arg (:argument step)]
    (cond
      (nil? arg)
      step-line

      (instance? shiftlefter.gherkin.parser.DataTable arg)
      (str step-line "\n" (format-data-table arg (inc indent-level)))

      (instance? shiftlefter.gherkin.parser.Docstring arg)
      (str step-line "\n" (format-docstring arg (inc indent-level)))

      :else
      step-line)))

(defn- format-steps
  "Format a sequence of steps."
  [steps indent-level]
  (->> steps
       (map #(format-step % indent-level))
       (str/join "\n")))

(defn- format-tags
  "Format tags on a single line.
   Tags are now rich format: [{:name \"@tag\" :location Location} ...]"
  [tags indent-level]
  (when (seq tags)
    (str (indent indent-level) (str/join " " (map :name tags)))))

(defn- format-examples
  "Format an Examples table."
  [examples indent-level]
  (let [indent-str (indent indent-level)
        name-str (if (str/blank? (:name examples)) "" (str ": " (:name examples)))
        tags-line (format-tags (:tags examples) indent-level)
        header (str indent-str "Examples:" name-str)
        ;; Examples uses :table-header and :table-body, not :table
        table-header (:table-header examples)
        table-body (:table-body examples)
        ;; Build aligned table from header + body rows
        all-rows (cons table-header table-body)
        col-widths (when (seq all-rows)
                     (apply map (fn [& col-vals]
                                  (apply max (map #(count (str %)) col-vals)))
                            all-rows))
        table-indent (indent (inc indent-level))
        format-row (fn [cells]
                     (let [padded (map (fn [cell width]
                                         (let [cell-str (str cell)]
                                           (str cell-str (apply str (repeat (- width (count cell-str)) " ")))))
                                       cells col-widths)]
                       (str table-indent "| " (str/join " | " padded) " |")))]
    (str (when tags-line (str tags-line "\n"))
         header "\n"
         (str/join "\n" (map format-row all-rows)))))

(defn- format-scenario
  "Format a Scenario or ScenarioOutline."
  [scenario indent-level]
  (let [indent-str (indent indent-level)
        type-keyword (if (= :scenario-outline (:type scenario))
                       "Scenario Outline"
                       "Scenario")
        name-str (:name scenario)
        tags-line (format-tags (:tags scenario) indent-level)
        header (str indent-str type-keyword ": " name-str)
        steps-str (format-steps (:steps scenario) (inc indent-level))
        examples (:examples scenario)]
    (str (when tags-line (str tags-line "\n"))
         header "\n"
         steps-str
         (when (seq examples)
           (str "\n\n" (str/join "\n\n" (map #(format-examples % indent-level) examples)))))))

(defn- format-background
  "Format a Background section."
  [background indent-level]
  (let [indent-str (indent indent-level)
        tags-line (format-tags (:tags background) indent-level)
        header (str indent-str "Background:")
        steps-str (format-steps (:steps background) (inc indent-level))]
    (str (when tags-line (str tags-line "\n"))
         header "\n"
         steps-str)))

(defn- format-description
  "Format feature description lines."
  [description indent-level]
  (when (and description (not (str/blank? description)))
    (let [indent-str (indent indent-level)
          lines (str/split-lines (str/trim description))]
      (str/join "\n" (map #(str indent-str %) lines)))))

(defn- format-feature
  "Format a Feature node."
  [feature]
  (let [tags-line (format-tags (:tags feature) 0)
        header (str "Feature: " (:name feature))
        desc (format-description (:description feature) 1)
        bg (parser/get-background feature)
        scenarios (parser/get-scenarios feature)
        bg-str (when bg (format-background bg 1))
        scenarios-str (->> scenarios
                           (map #(format-scenario % 1))
                           (str/join "\n\n"))]
    (str (when tags-line (str tags-line "\n"))
         header
         (when desc (str "\n" desc))
         (when bg-str (str "\n\n" bg-str))
         (when (seq scenarios) (str "\n\n" scenarios-str))
         "\n")))

(defn- format-comment
  "Format a Comment node."
  [comment-node]
  (str "# " (:text comment-node)))

(defn- find-first-rule
  "Find the first Rule node in an AST. Returns the rule or nil."
  [ast]
  (let [features (filter #(instance? shiftlefter.gherkin.parser.Feature %) ast)]
    (some (fn [feature]
            (some #(when (instance? shiftlefter.gherkin.parser.Rule %) %)
                  (:children feature)))
          features)))

(defn format-canonical
  "Format AST to canonical Gherkin style.

   Canonical style:
   - 2 spaces indentation
   - Aligned table columns
   - No trailing whitespace
   - Single blank line between sections

   Returns:
   - {:status :ok :output formatted-string} on success
   - {:status :error :reason :canonical/rules-unsupported ...} if Rules present

   Note: Canonical formatter does not yet support Rule: blocks. Use lossless
   roundtrip (print-tokens) for files containing rules."
  [ast]
  ;; Check for Rule: blocks - canonical formatter doesn't support them yet
  (if-let [rule (find-first-rule ast)]
    {:status :error
     :reason :canonical/rules-unsupported
     :message "Canonical formatter does not support Rule: blocks. Use lossless roundtrip instead."
     :location (:location rule)}
    ;; No rules - proceed with formatting
    (let [;; Separate comments at top from feature
          top-comments (take-while #(or (instance? shiftlefter.gherkin.parser.Comment %)
                                        (instance? shiftlefter.gherkin.parser.Blank %))
                                   ast)
          rest-ast (drop (count top-comments) ast)
          ;; Format top comments (filter blanks, just keep comments)
          comment-nodes (filter #(instance? shiftlefter.gherkin.parser.Comment %) top-comments)
          comments-str (when (seq comment-nodes)
                         (str (str/join "\n" (map format-comment comment-nodes)) "\n"))
          ;; Format features (usually just one)
          features (filter #(instance? shiftlefter.gherkin.parser.Feature %) rest-ast)
          feature-str (str/join "\n" (map format-feature features))
          ;; Combine and strip trailing whitespace from each line
          raw-output (str comments-str
                          (when (and comments-str (seq features)) "\n")
                          feature-str)
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
   - {:status :error :reason :canonical/rules-unsupported ...} if Rules present
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
          (let [result (format-canonical ast)]
            (assoc result :path path)))))))

(defn canonical
  "Format a string to canonical style.

   Returns formatted string on success.
   Throws on parse errors or if Rules are present."
  [input]
  (let [tokens (vec (lexer/lex input))
        {:keys [ast errors]} (parser/parse tokens)]
    (if (seq errors)
      (throw (ex-info "Parse errors" {:errors errors}))
      (let [result (format-canonical ast)]
        (if (= :ok (:status result))
          (:output result)
          (throw (ex-info (:message result)
                          {:reason (:reason result)
                           :location (:location result)})))))))
