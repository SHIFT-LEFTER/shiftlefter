(ns shiftlefter.gherkin.tokens
  (:require [shiftlefter.gherkin.location :as loc]
            [shiftlefter.gherkin.dialect :as dialect]
            [clojure.string :as str]
            [clojure.spec.alpha :as s]))

;; -----------------------------------------------------------------------------
;; Token type hierarchy
;; -----------------------------------------------------------------------------

(defrecord Token [type value location leading-ws idx raw keyword-text])

(def ^:const token-types
  "All possible token types — exhaustive and spec'd later"
  #{:blank
    :comment
    :tag-line
    :feature-line
    :background-line
    :scenario-line
    :scenario-outline-line
    :rule-line
    :examples-line
    :step-line
    :docstring-separator
    :table-row
    :language-header
    :eof
    :unknown-line})

;; -----------------------------------------------------------------------------
;; Token predicate
;; -----------------------------------------------------------------------------

(defn token?
  "Predicate for Token records"
  [t]
  (instance? Token t))
;; -----------------------------------------------------------------------------
;; Public API
;; -----------------------------------------------------------------------------

(declare parse-tags-with-positions parse-table-row)

(defn- parse-tags-with-positions
  "Turn '  @slow @ui' into {:tags [\"slow\" \"ui\"] :positions [3 10]}
   If there's non-comment trailing content after tags, returns :error key.
   Comments (starting with #) after tags are allowed per Gherkin spec.
   Joined tags like @tag1@tag2 are split on @ boundaries.
   Examples:
   '@smoke @web' -> {:tags [\"smoke\" \"web\"] :positions [1 8]}
   '@smoke foo' -> {:tags [\"smoke\"] :positions [1] :error \"Trailing content after tags: foo\"}
   '@smoke #comment' -> {:tags [\"smoke\"] :positions [1]} (comment is allowed)
   '@tag1@tag2' -> {:tags [\"tag1\" \"tag2\"] :positions [1 6]} (joined tags split)"
  [s start-col]
  ;; Match @tag until whitespace OR another @ (handles joined tags like @tag1@tag2)
  (let [matches (re-seq #"@[^@\s]+" s)
        tags (mapv #(subs % 1) matches)
        ;; Find all @ positions in string, add start-col for 1-indexed column
        at-positions (vec (keep-indexed #(when (= %2 \@) (+ start-col %1)) (seq s)))
        ;; Find the end of the last tag by finding where content after last @ ends
        last-at-idx (when (seq matches)
                      (str/last-index-of s "@"))
        last-tag-end (when last-at-idx
                       (let [last-match (last matches)]
                         (+ last-at-idx (count last-match))))
        ;; Check for trailing content after the last tag
        trailing (when last-tag-end
                   (str/trim (subs s last-tag-end)))
        ;; Comments (starting with #) are allowed after tags
        is-comment? (and trailing (str/starts-with? trailing "#"))
        result {:tags tags :positions at-positions}]
    (if (and trailing (seq trailing) (not is-comment?))
      (assoc result :error (str "Trailing content after tags: " trailing))
      result)))

(defn tokenize-line
  "Take a line number, raw line (with newline), token index, language, and docstring-state → return a Token.
   The raw-line parameter should include the trailing newline (if present in original input).
   docstring-state is nil (not in docstring), :triple-quote, or :backtick."
  [line-num raw-line token-idx language docstring-state]
  (let [;; Strip any trailing line ending (CRLF, LF, or CR) for parsing
        ;; but keep raw-line for :raw field (preserves original EOL)
        line (str/replace raw-line #"\r?\n$|\r$" "")
        leading-ws (apply str (take-while #(or (= % \space) (= % \tab)) line))
        col (inc (count leading-ws))
        location (loc/->Location (inc line-num) col)
        trimmed (str/triml line)
        dialect-lookup (dialect/get-dialect language)]

    ;; Inside docstring: only the matching separator can exit, everything else is content
    (if docstring-state
      (let [is-triple-quote (str/starts-with? trimmed "\"\"\"")
            is-backtick (str/starts-with? trimmed "```")
            matching-exit (or (and (= docstring-state :triple-quote) is-triple-quote)
                              (and (= docstring-state :backtick) is-backtick))]
        (if matching-exit
          ;; Matching separator - exit docstring
          (let [fence (if is-triple-quote :triple-quote :backtick)]
            (->Token :docstring-separator
                     {:fence fence :language nil}
                     location leading-ws token-idx raw-line nil))
          ;; Content inside docstring - use :unknown-line (compliance converts to Other)
          ;; Blank lines stay :blank so compliance can convert them to Other:// correctly
          (if (str/blank? line)
            (->Token :blank nil (loc/->Location (inc line-num) 1) leading-ws token-idx raw-line nil)
            (->Token :unknown-line trimmed location leading-ws token-idx raw-line nil))))

      ;; Not inside docstring - normal tokenization
      (cond
        ;; Blank lines
        (str/blank? line)
        (->Token :blank nil (loc/->Location (inc line-num) 1) leading-ws token-idx raw-line nil)

        ;; Language header (flexible spacing: #  language  :   en)
        (and (str/starts-with? trimmed "#")
             (re-matches #"(?i)#\s*language\s*:\s*.*" trimmed))
        (let [lang-value (str/trim (str/replace trimmed #"(?i)#\s*language\s*:\s*" ""))]
          (->Token :language-header lang-value location leading-ws token-idx raw-line nil))

        ;; Comments (but not language headers)
        (str/starts-with? trimmed "#")
        (->Token :comment (str/trim trimmed) location leading-ws token-idx raw-line nil)

        ;; Tags
        (str/starts-with? trimmed "@")
        (->Token :tag-line (parse-tags-with-positions trimmed col) location leading-ws token-idx raw-line nil)

        ;; Docstrings
        (or (str/starts-with? trimmed "\"\"\"")
            (str/starts-with? trimmed "```"))
        (let [fence (if (str/starts-with? trimmed "\"\"\"") :triple-quote :backtick)
              lang (when (> (count trimmed) 3)
                     (subs trimmed 3))]
          (->Token :docstring-separator
                   {:fence fence
                    :language (when (seq lang) lang)}
                   location leading-ws token-idx raw-line nil))

        ;; Table rows
        (str/starts-with? trimmed "|")
        (->Token :table-row (parse-table-row line) location leading-ws token-idx raw-line nil)

        ;; Try block keywords (Feature:, Scenario:, etc.) using dialect
        :else
        (if-let [block-match (dialect/match-block-keyword trimmed dialect-lookup)]
          (let [{:keys [keyword matched name]} block-match
                token-type (case keyword
                             :feature :feature-line
                             :background :background-line
                             :scenario :scenario-line
                             :scenario-outline :scenario-outline-line
                             :examples :examples-line
                             :rule :rule-line
                             :unknown-line)]
            (->Token token-type name location leading-ws token-idx raw-line matched))
          ;; Try step keywords (Given, When, Then, etc.)
          (if-let [step-match (dialect/match-step-keyword trimmed dialect-lookup)]
            (let [{:keys [keyword matched text]} step-match]
              (->Token :step-line {:keyword keyword :text text} location leading-ws token-idx raw-line matched))
            ;; Unknown line
            (->Token :unknown-line trimmed location leading-ws token-idx raw-line nil)))))))

(defn- unicode-trim
  "Trim Unicode whitespace from both ends of string.
   Handles non-breaking space (U+00A0), tabs, and other Unicode whitespace."
  [s]
  (when s
    (str/replace s #"^[\s\u00A0\u2000-\u200A\u2028\u2029\u202F\u205F\u3000]+|[\s\u00A0\u2000-\u200A\u2028\u2029\u202F\u205F\u3000]+$" "")))

(defn- unescape-cell
  "Unescape a table cell value according to Gherkin spec.
   Escape sequences:
   - \\| -> | (escaped pipe)
   - \\\\ -> \\ (escaped backslash)
   - \\n -> newline

   Uses placeholder approach to avoid double-unescaping:
   \\\\n should become \\n (backslash + n), not backslash + newline."
  [s]
  (let [;; First, protect escaped backslashes with placeholder
        bs-placeholder "\u0001BACKSLASH\u0001"
        s1 (str/replace s "\\\\" bs-placeholder)
        ;; Now we can safely replace \n with newline
        s2 (str/replace s1 "\\n" "\n")
        ;; And \| with pipe
        s3 (str/replace s2 "\\|" "|")
        ;; Finally restore backslashes
        s4 (str/replace s3 bs-placeholder "\\")]
    s4))

(defn- find-last-unescaped-pipe
  "Find the index of the last unescaped pipe in the string.
   Returns nil if no unescaped pipe is found."
  [s]
  (loop [idx (dec (count s))]
    (when (>= idx 0)
      (if (and (= (nth s idx) \|)
               ;; Check if pipe is unescaped (not preceded by odd number of backslashes)
               (let [bs-count (count (take-while #(= % \\) (reverse (subs s 0 idx))))]
                 (even? bs-count)))
        idx
        (recur (dec idx))))))

(defn- parse-table-cells
  "Parse table row cells, handling escape sequences and preserving empty cells.
   Returns vector of cell values with escapes decoded.

   Escape sequences (per Gherkin spec):
   - \\| -> | (escaped pipe)
   - \\\\ -> \\ (escaped backslash)
   - \\n -> newline

   Extra content after the last pipe is ignored (per Gherkin spec).

   Examples:
   '| a | b |' -> [\"a\" \"b\"]
   '| a | | c |' -> [\"a\" \"\" \"c\"]
   '| a | b\\|c |' -> [\"a\" \"b|c\"]
   '| a | b | extra' -> [\"a\" \"b\"] (extra content ignored)"
  [line]
  (let [trimmed (str/trim line)]
    ;; Must start with | for valid table row
    (when (str/starts-with? trimmed "|")
      ;; Find the last unescaped pipe to delimit the table
      (if-let [last-pipe-idx (find-last-unescaped-pipe trimmed)]
        ;; Need at least 2 pipes (first and last) with something between
        (when (> last-pipe-idx 0)
          ;; Extract content between first and last pipe
          (let [inner (subs trimmed 1 last-pipe-idx)
                ;; Split on unescaped | (not preceded by \)
                ;; We do this by replacing \| with a placeholder, splitting, then restoring
                placeholder "\u0000ESCAPED_PIPE\u0000"
                escaped (str/replace inner "\\|" placeholder)
                parts (str/split escaped #"\|" -1)  ;; -1 keeps trailing empty strings
                cells (mapv (fn [part]
                              (-> part
                                  (str/replace placeholder "\\|")  ;; Restore for unescape
                                  unicode-trim  ;; Use Unicode-aware trim for non-breaking space etc.
                                  unescape-cell))
                            parts)]
            cells))
        ;; No unescaped closing pipe - return nil (not a valid table row)
        nil))))

(defn- parse-table-row
  "Turn '  | admin | secret  |' into {:cells [\"admin\" \"secret\"] :raw \"  | admin | secret  |\"}
   Handles escaped pipes (\\|) and preserves empty cells."
  [line]
  {:cells (or (parse-table-cells line) [])
   :raw line})

;; -----------------------------------------------------------------------------
;; Specs (for generative testing later)
;; -----------------------------------------------------------------------------

(s/def ::token
  (s/and (partial instance? shiftlefter.gherkin.tokens.Token)
         (s/keys :req-un [::type ::value ::location])))

(s/def ::type token-types)
