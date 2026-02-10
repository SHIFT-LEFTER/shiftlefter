(ns shiftlefter.gherkin.parser
  "Recursive-descent parser for Gherkin token sequences.

   Consumes tokens produced by the lexer and produces AST nodes (Feature,
   Scenario, Background, Rule, Step, etc.). Collects errors without
   throwing — returns `{:ast [...] :errors [...]}`.

   Internal to the parser pipeline; use `gherkin.api/parse-string` for
   the public API."
  (:require [shiftlefter.gherkin.tokens :as tokens]
            [shiftlefter.gherkin.location :as loc]
            [shiftlefter.gherkin.dialect :as dialect]
            [shiftlefter.gherkin.io :as io]
            [clojure.string :as str]
            [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]))

;; -----------------------------------------------------------------------------
;; Defrecords for AST nodes
;; -----------------------------------------------------------------------------

(defrecord Feature [type name description tags children keyword-text language location source-text leading-ws span])
(defrecord Rule [type name description tags children keyword-text location source-text leading-ws span])
(defrecord Scenario [type name description tags steps examples keyword-text location source-text leading-ws span])
(defrecord ScenarioOutline [type name description tags steps examples keyword-text location source-text leading-ws span])
(defrecord Background [type name description steps tags keyword-text location source-text leading-ws span])
(defrecord Step [type keyword keyword-text text argument location source-text leading-ws span])
(defrecord Examples [type name description tags table location source-text leading-ws span])
(defrecord Docstring [type content fence mediaType location source-text leading-ws span])
(defrecord DataTable [type rows location source-text leading-ws span])
(defrecord TableRow [type cells location source-text leading-ws span])
(defrecord Blank [type location source-text leading-ws span])
(defrecord Comment [type text location source-text leading-ws span])

;; -----------------------------------------------------------------------------
;; Parser Return Contract
;; -----------------------------------------------------------------------------
;;
;; All sub-parsers follow a consistent return contract:
;;
;;   nil           = No match; caller should try alternative or proceed.
;;   [node ts]     = Match; node is the parsed result, ts is STRICTLY ADVANCED.
;;   [node ts errs]= Match with errors; same as above plus error collection.
;;
;; CRITICAL INVARIANT: When returning [node ts], the `ts` sequence MUST be
;; strictly shorter than the input. Returning [anything same-ts] causes infinite
;; loops in "many" parsers (parse-steps, parse-scenarios, etc.).
;;
;; Example of the bug pattern to avoid:
;;   ;; BAD: [nil ts] is truthy in if-let, causes non-advancing loop
;;   (if-let [[node remaining] (parse-foo ts)]  ; [nil ts] passes!
;;     (recur remaining ...)                     ; ts unchanged → infinite loop
;;     ...)
;;
;; Instead:
;;   ;; GOOD: return nil for no-match
;;   (when (match? (first ts))
;;     [node (rest ts)])  ; or simply nil if no match
;;
;; -----------------------------------------------------------------------------

;; -----------------------------------------------------------------------------
;; Forward declarations
;; -----------------------------------------------------------------------------

(declare parse-description parse-steps parse-step parse-argument
         parse-docstring collect-docstring-content parse-table parse-example parse-scenario
         parse-scenarios parse-background parse-rule parse-rules parse-feature validate-ast-order)

;; -----------------------------------------------------------------------------
;; Specs
;; -----------------------------------------------------------------------------

(s/def ::location ::loc/location)
(s/def ::name string?)
(s/def ::type keyword?)
(s/def ::message string?)
(s/def ::token ::tokens/token)
;; Rich tag format: {:name "@tagname" :location {:line L :column C}}
(s/def ::rich-tag (s/keys :req-un [::name ::location]))
(s/def ::tags (s/coll-of ::rich-tag))
(s/def ::description string?)
(s/def ::text string?)
(s/def ::content string?)
(s/def ::fence #{:triple-quote :backtick})
(s/def ::mediaType (s/or :nil nil? :str string?))
(s/def ::cells (s/coll-of string?))
(s/def ::source-text string?)
(s/def ::table-row
  (s/with-gen
    (s/and (partial instance? TableRow) (s/keys :req-un [::type ::cells ::location ::source-text]))
    #(gen/fmap (fn [[cells loc src]]
                 (->TableRow :table-row cells loc src "" nil))
               (gen/tuple (s/gen ::cells) (s/gen ::location) (s/gen ::source-text)))))
(s/def ::rows (s/coll-of ::table-row))

(s/def ::data-table
  (s/with-gen
    (s/and (partial instance? DataTable) (s/keys :req-un [::type ::rows ::location ::source-text]))
    #(gen/fmap (fn [[rows loc src]]
                 (->DataTable :data-table rows loc src "" nil))
               (gen/tuple (s/gen ::rows) (s/gen ::location) (s/gen ::source-text)))))
(s/def ::table (s/coll-of ::table-row))
(s/def ::comment
  (s/with-gen
    (s/and (partial instance? Comment) (s/keys :req-un [::type ::text ::location ::source-text]))
    #(gen/fmap (fn [[txt loc src]]
                 (->Comment :comment txt loc src "" nil))
               (gen/tuple (s/gen ::text) (s/gen ::location) (s/gen ::source-text)))))

(s/def ::blank
  (s/with-gen
    (s/and (partial instance? Blank) (s/keys :req-un [::type ::location ::source-text]))
    #(gen/fmap (fn [[loc src]]
                 (->Blank :blank loc src "" nil))
               (gen/tuple (s/gen ::location) (s/gen ::source-text)))))
;; Keyword spec for step keywords
(s/def ::keyword #{:given :when :then :and :but :*})

(s/def ::argument
  (s/with-gen
    (s/or :docstring (s/and (partial instance? Docstring)
                            (s/keys :req-un [::type ::content ::fence ::mediaType ::location ::source-text]))
          :data-table ::data-table
          :nil nil?)
    ;; Generator produces nil for simplicity; Docstring/DataTable generators work independently
    #(gen/return nil)))

(s/def ::step
  (s/with-gen
    (s/and (partial instance? Step) (s/keys :req-un [::type ::keyword ::text ::argument ::location ::source-text]))
    #(gen/fmap (fn [[kw txt loc src]]
                 (->Step :step kw (str kw " ") txt nil loc src "" nil))
               (gen/tuple (s/gen ::keyword) (s/gen ::text) (s/gen ::location) (s/gen ::source-text)))))

(s/def ::steps (s/coll-of ::step))
(s/def ::examples any?)

(s/def ::scenario
  (s/with-gen
    (s/and (partial instance? Scenario) (s/keys :req-un [::type ::name ::tags ::steps ::examples ::location ::source-text]))
    #(gen/fmap (fn [[nm tags loc src]]
                 (->Scenario :scenario nm "" tags [] nil "Scenario" loc src "" nil))
               (gen/tuple (s/gen ::name) (s/gen ::tags) (s/gen ::location) (s/gen ::source-text)))))

(s/def ::scenario-outline
  (s/with-gen
    (s/and (partial instance? ScenarioOutline) (s/keys :req-un [::type ::name ::tags ::steps ::examples ::location ::source-text]))
    #(gen/fmap (fn [[nm tags loc src]]
                 (->ScenarioOutline :scenario-outline nm "" tags [] nil "Scenario Outline" loc src "" nil))
               (gen/tuple (s/gen ::name) (s/gen ::tags) (s/gen ::location) (s/gen ::source-text)))))

(s/def ::scenarios (s/coll-of (s/or :scenario ::scenario :scenario-outline ::scenario-outline)))

(s/def ::background
  (s/with-gen
    (s/or :background (s/and (partial instance? Background) (s/keys :req-un [::type ::steps ::tags ::location ::source-text]))
          :nil nil?)
    #(gen/one-of [(gen/return nil)
                  (gen/fmap (fn [[tags loc src]]
                              (->Background :background nil "" [] tags "Background" loc src "" nil))
                            (gen/tuple (s/gen ::tags) (s/gen ::location) (s/gen ::source-text)))])))

(s/def ::rule
  (s/with-gen
    (s/and (partial instance? Rule) (s/keys :req-un [::type ::name ::description ::tags ::children ::location ::source-text]))
    #(gen/fmap (fn [[nm desc tags loc src]]
                 (->Rule :rule nm desc tags [] "Rule" loc src "" nil))
               (gen/tuple (s/gen ::name) (s/gen ::description) (s/gen ::tags) (s/gen ::location) (s/gen ::source-text)))))

(s/def ::children (s/coll-of (s/or :background ::background :scenario ::scenario :scenario-outline ::scenario-outline :rule ::rule)))

(s/def ::feature
  (s/with-gen
    (s/and (partial instance? Feature) (s/keys :req-un [::type ::name ::description ::tags ::children ::location ::source-text]))
    #(gen/fmap (fn [[nm desc tags loc src]]
                 (->Feature :feature nm desc tags [] "Feature" nil loc src "" nil))
               (gen/tuple (s/gen ::name) (s/gen ::description) (s/gen ::tags) (s/gen ::location) (s/gen ::source-text)))))
(s/def ::ast-node (s/or :feature ::feature :background ::background :scenario ::scenario :scenario-outline ::scenario-outline :step ::step :docstring ::argument :data-table ::data-table :table-row ::table-row :blank ::blank :comment ::comment))
(s/def ::ast (s/coll-of ::ast-node))
(s/def ::error (s/keys :req-un [::type ::location] :opt-un [::message ::token]))
(s/def ::errors (s/coll-of ::error))

(s/fdef parse
  :args (s/cat :token-seq (s/coll-of ::tokens/token))
  :ret (s/keys :req [::ast ::errors]))

(s/fdef parse-feature
  :args (s/cat :ts (s/coll-of ::tokens/token) :tags ::tags)
  :ret (s/tuple ::feature (s/coll-of ::tokens/token) ::errors))

(s/fdef parse-scenarios
  :args (s/cat :ts (s/coll-of ::tokens/token))
  :ret (s/tuple ::scenarios (s/coll-of ::tokens/token)))

(s/fdef parse-steps
  :args (s/cat :ts (s/coll-of ::tokens/token))
  :ret (s/tuple ::steps (s/coll-of ::tokens/token)))

;; -----------------------------------------------------------------------------
;; Dynamic vars
;; -----------------------------------------------------------------------------

(def ^:dynamic *dialect*
  "Current dialect for parsing. Bind to a dialect map (from dialect/get-dialect)
   to parse non-English Gherkin. Defaults to nil (uses English-only fallback)."
  nil)

(def ^:dynamic *assert-advancing*
  "Development-time guard for detecting non-advancing parser loops.
   When true, throws if a 'many' parser loop fails to advance the token stream.
   Set to true during testing to catch infinite loop bugs early."
  false)

(defn- assert-advancing!
  "Guard for 'many' parser loops. Throws if ts hasn't advanced.
   Only active when *assert-advancing* is true (test/dev mode)."
  [loop-name prev-ts curr-ts]
  (when *assert-advancing*
    (when (and (seq prev-ts) (identical? prev-ts curr-ts))
      (throw (ex-info (str "Non-advancing loop detected in " loop-name
                           ". Token: " (pr-str (first curr-ts)))
                      {:loop loop-name
                       :token (first curr-ts)})))))

;; -----------------------------------------------------------------------------
;; Helpers
;; -----------------------------------------------------------------------------

(defn- make-span
  "Create a span map from start and end token indices.
   start-idx is inclusive, end-idx is exclusive (like subvec)."
  [start-idx end-idx]
  {:start-idx start-idx :end-idx end-idx})

(defn- token-idx
  "Get the :idx of a token, or nil if token is nil."
  [token]
  (when token (:idx token)))

(defn- ->error
  "Create a structured error map."
  [type location message & {:keys [token]}]
  (cond-> {:type type :location location :message message}
          token (assoc :token token)))

(defn- tag-token->rich-tags
  "Convert a tag-line token to rich tag info with locations.
   Returns [{:name \"@tag\" :location Location} ...]"
  [token]
  (let [value (:value token)
        tags (:tags value)
        positions (:positions value)
        line (:line (:location token))]
    (mapv (fn [tag-name col]
            {:name (str "@" tag-name)
             :location (loc/->Location line col)})
          tags positions)))

(defn- extract-name
  "Extract name from token value. For new-style tokens, value is already the name.
   For old-style tokens (backward compat), strip 'Keyword:' prefix."
  [s]
  (if (and s (str/includes? s ":"))
    (str/trim (subs s (inc (str/index-of s ":"))))
    (or s "")))

(defn- keyword-line? [token]
  (#{:feature-line :background-line :scenario-line :scenario-outline-line :rule-line :examples-line :step-line :tag-line} (:type token)))

(defn- description-terminator?
  "Returns true if this token type terminates a description."
  [token]
  (#{:table-row :docstring-separator} (:type token)))

(defn- parse-description
  "Parse description text following a structural keyword.
   Preserves original indentation by using :raw token text.
   Stops on keyword lines, table rows, or docstrings.
   Trailing blank lines are stripped (they're separators, not description).
   Returns [description remaining-tokens errors]."
  [ts]
  (loop [ts ts
         lines []
         errors []]
    (if (or (empty? ts)
            (keyword-line? (first ts))
            (description-terminator? (first ts)))
      ;; Stop (without consuming) on keywords, table rows, or docstrings
      ;; Drop leading and trailing empty lines (blank lines around description aren't part of it)
      (let [trimmed-lines (->> lines
                               (drop-while #(= % ""))      ;; strip leading blanks
                               reverse
                               (drop-while #(= % ""))      ;; strip trailing blanks
                               reverse
                               vec)]
        [(str/join "\n" trimmed-lines) ts errors])
      (let [token (first ts)]
        (cond
          (= (:type token) :blank)
          ;; Blank lines become empty strings in the lines vector
          ;; When joined, this produces \n\n (empty line in description)
          (recur (rest ts) (conj lines "") errors)

          (= (:type token) :comment)
          ;; Skip comments - they don't appear in description
          (recur (rest ts) lines errors)

          (= (:type token) :unknown-line)
          ;; Use :raw to preserve leading whitespace, strip trailing EOL
          (let [raw-text (io/strip-trailing-eol (:raw token))]
            (recur (rest ts) (conj lines raw-text) errors))

          :else
          ;; Shouldn't happen, but handle gracefully
          (recur (rest ts) lines errors))))))

(defn- parse-steps [ts]
  (loop [prev-ts nil
         ts ts
         steps []
         errors []]
    (assert-advancing! "parse-steps" prev-ts ts)
    (let [token-type (:type (first ts))]
      (cond
        (or (empty? ts) (not (#{:step-line :comment :blank} token-type)))
        [steps ts errors]

        (#{:comment :blank} token-type)
        (recur ts (rest ts) steps errors)

        :else
        (let [[step remaining step-errors] (parse-step ts)]
          (recur ts remaining (conj steps step) (into errors step-errors)))))))

(defn- parse-step [ts]
  (let [token (first ts)
        start-idx (:idx token)
        token-value (:value token)
        ;; Handle both new format {:keyword :given :text "..."} and old format "Given ..."
        [keyword-kw step-text-raw] (if (map? token-value)
                                     [(:keyword token-value) (:text token-value)]
                                     ;; Old format fallback
                                     (if-let [[_ kw txt] (re-matches #"^(Given|When|Then|And|But|\*) (.+)" (or token-value ""))]
                                       [(keyword (str/lower-case kw)) txt]
                                       [nil token-value]))
        ;; Map keyword to canonical string
        keyword-str (case keyword-kw
                      :given "Given"
                      :when "When"
                      :then "Then"
                      :and "And"
                      :but "But"
                      :star "*"
                      nil)
        ;; Reconstruct full text for source-text field
        full-text (str (or (:keyword-text token) "") step-text-raw)
        remaining (rest ts)
        [argument remaining2 arg-errors] (parse-argument remaining)
        end-idx (or (token-idx (first remaining2)) (inc start-idx))
        span (make-span start-idx end-idx)]
    [(->Step :step keyword-str (:keyword-text token) step-text-raw argument (:location token) full-text (:leading-ws token) span)
     remaining2 arg-errors]))

(defn- parse-argument [ts]
  (if (empty? ts)
    [nil ts []]
    (let [token (first ts)]
      (cond
        (= (:type token) :docstring-separator)
        (let [[docstring remaining errors] (parse-docstring ts)]
          [docstring remaining errors])
        (= (:type token) :table-row)
        (let [[table remaining errors] (parse-table ts)]
          [table remaining errors])
        :else
        [nil ts []]))))

(defn- parse-docstring [ts]
  (let [sep-token (first ts)
        start-idx (:idx sep-token)
        sep-val (:value sep-token)
        fence (:fence sep-val)
        mediaType (:language sep-val)
        ;; Base indent is the column of the opening delimiter minus 1 (0-indexed spaces)
        base-indent (dec (:column (:location sep-token)))
        remaining (rest ts)
        [content remaining2 incomplete?] (collect-docstring-content remaining fence base-indent)
        end-idx (or (token-idx (first remaining2)) (inc start-idx))
        span (make-span start-idx end-idx)]
    [(->Docstring :docstring content fence mediaType (:location sep-token) (:value sep-token) (:leading-ws sep-token) span)
     remaining2
     (if incomplete? [(->error :incomplete-docstring (:location sep-token) "Unclosed docstring")] [])]))

(defn- strip-docstring-line-indent
  "Strip base indentation from a docstring content line.
   Uses :raw to preserve relative indentation beyond base.
   Lines with less indent than base have all leading spaces stripped."
  [raw base-indent]
  (let [line (io/strip-trailing-eol raw)
        leading-spaces (count (take-while #(= % \space) line))
        chars-to-strip (min leading-spaces base-indent)]
    (subs line chars-to-strip)))

(defn- collect-docstring-content [ts fence base-indent]
  (loop [ts ts
         lines []]
    (if (empty? ts)
      [(str/join "\n" lines) ts true]
      (let [token (first ts)]
        (if (and (= (:type token) :docstring-separator)
                 (= (:fence (:value token)) fence))
          [(str/join "\n" lines) (rest ts) false]
          (let [line (strip-docstring-line-indent (:raw token) base-indent)]
            (recur (rest ts) (conj lines line))))))))

(defn- validate-table-row
  "Validate a table row for common errors. Returns error or nil."
  [token expected-cell-count]
  (let [val (:value token)
        cells (if (map? val) (:cells val) val)
        raw (if (map? val) (:raw val) "")
        loc (:location token)
        trimmed-raw (str/trimr raw)]
    (cond
      ;; Trailing backslash on unfinished row (empty cells)
      ;; e.g., "| bar \" is invalid, but "| a | b | extra \" is valid (extra content ignored)
      (and (empty? cells)
           (str/ends-with? trimmed-raw "\\"))
      (->error :invalid-table-row loc
               "Invalid table row: trailing backslash"
               :token token)

      ;; Row has pipe but no cells parsed (missing closing |)
      ;; e.g., "| bar" parses to empty cells (backslash case handled above)
      (and (str/includes? raw "|")
           (empty? cells))
      (->error :invalid-table-row loc
               "Invalid table row: missing closing '|'"
               :token token)

      ;; Inconsistent cell count (if we have an expected count)
      (and expected-cell-count
           (pos? (count cells))  ;; Only check if we have cells
           (not= (count cells) expected-cell-count))
      (->error :inconsistent-cell-count loc
               (str "Inconsistent cell count: expected " expected-cell-count " but got " (count cells))
               :token token)

      :else nil)))

(defn- parse-table
  "Parse a DataTable, skipping blanks and comments between rows.
   Per Gherkin spec, tables can have blank lines and comments interspersed.
   Returns [table remaining errors]."
  [ts]
  (let [start-token (first ts)
        start-idx (:idx start-token)]
    (loop [ts ts
           rows []
           errors []
           expected-cell-count nil]
      (let [;; Skip blanks and comments between table rows
            ts-trimmed (loop [t ts]
                         (if (and (seq t) (#{:blank :comment} (:type (first t))))
                           (recur (rest t))
                           t))]
        (if (or (empty? ts-trimmed) (not (= (:type (first ts-trimmed)) :table-row)))
          (let [start-val (:value start-token)
                start-raw (if (map? start-val) (:raw start-val) nil)
                end-idx (or (token-idx (first ts-trimmed)) (inc start-idx))
                span (make-span start-idx end-idx)]
            [(->DataTable :data-table rows (:location start-token) start-raw (:leading-ws start-token) span) ts-trimmed errors])
          (let [token (first ts-trimmed)
                idx (:idx token)
                val (:value token)
                cells (if (map? val) (:cells val) val)
                raw (if (map? val) (:raw val) nil)
                ;; First row establishes expected cell count
                new-expected (or expected-cell-count (count cells))
                row-error (validate-table-row token new-expected)
                row (->TableRow :table-row cells (:location token) raw (:leading-ws token) (make-span idx (inc idx)))]
            (recur (rest ts-trimmed)
                   (conj rows row)
                   (if row-error (conj errors row-error) errors)
                   new-expected)))))))

(defn- parse-example [ts]
  ;; IMPORTANT: return nil (not [nil ts]) when there's no Examples block ahead.
  ;; Callers use `if-let` on the *whole* return value, so `[nil ts]` is truthy
  ;; and will cause non-advancing loops.
  ;; Returns [example-map remaining errors] or nil.
  (when (and (seq ts) (= (:type (first ts)) :examples-line))
    (let [token (first ts)
          name (extract-name (:value token))
          remaining (rest ts)
          ;; Parse description after Examples line
          [description remaining1 desc-errors] (parse-description remaining)
          [data-table remaining2 table-errors] (parse-table remaining1)
          table (:rows data-table)
          ;; Keep full TableRow records for compliance projection (need locations + raw for cell positions)
          header-row (first table)
          body-rows (rest table)
          ;; Also keep simple cell values for backward compatibility
          header (when header-row (:cells header-row))
          body (when (seq body-rows) (mapv :cells body-rows))]
      [{:keyword "Examples"
        :keyword-text (:keyword-text token)
        :name name
        :description description
        :table-header header
        :table-body body
        :table-header-row header-row     ;; Full TableRow for compliance
        :table-body-rows (vec body-rows) ;; Full TableRows for compliance
        :location (:location token)
        :source-text (or (:raw token) (:value token))
        :leading-ws (:leading-ws token)}
       remaining2
       (concat desc-errors table-errors)])))


(defn- parse-scenario [ts tags]
  (let [token (first ts)
        start-idx (:idx token)
        explicit-outline (= (:type token) :scenario-outline-line)
        name (extract-name (:value token))
        remaining (rest ts)
        ;; Parse description after scenario/outline line
        [description remaining1 desc-errors] (parse-description remaining)
        [steps remaining2 step-errors] (parse-steps remaining1)
        ;; Always try to parse Examples - supports both explicit "Scenario Outline:"
        ;; and implicit outlines (Scenario with Examples)
        [examples remaining3 example-tag-errors]
        (letfn [(skip-blanks-and-comments [ts]
                  (loop [ts ts]
                    (if (and (seq ts) (#{:blank :comment} (:type (first ts))))
                      (recur (rest ts))
                      ts)))
                ;; Starting from a tag-line, determine whether the next
                ;; non-blank/comment token is an Examples line.
                (tags-apply-to-examples? [ts]
                  (loop [ts ts]
                    (cond
                      (empty? ts) false
                      (= (:type (first ts)) :tag-line) (recur (rest ts))
                      (#{:blank :comment} (:type (first ts))) (recur (rest ts))
                      :else (= (:type (first ts)) :examples-line))))
                (collect-tag-lines [ts]
                  ;; Returns [tags remaining errors] - tags are rich format with locations
                  (loop [ts ts
                         collected []
                         errs []]
                    (if (and (seq ts) (= (:type (first ts)) :tag-line))
                      (let [token (first ts)
                            tag-value (:value token)
                            tag-error (when-let [err (:error tag-value)]
                                        (->error :invalid-tag-line (:location token) err :token token))
                            rich-tags (tag-token->rich-tags token)]
                        (recur (rest ts)
                               (into collected rich-tags)
                               (if tag-error (conj errs tag-error) errs)))
                      [collected ts errs])))]
          (loop [ts remaining2
                 examples []
                 pending-example-tags []
                 example-tag-errors []]
            (let [ts1 (skip-blanks-and-comments ts)]
              (cond
                (empty? ts1)
                [examples ts1 example-tag-errors]

                (= (:type (first ts1)) :examples-line)
                (let [[ex rem ex-errors] (parse-example ts1)
                      ex (if (seq pending-example-tags)
                           (assoc ex :tags pending-example-tags)
                           ex)]
                  (recur rem (conj examples ex) [] (into example-tag-errors ex-errors)))

                (and (= (:type (first ts1)) :tag-line)
                     (tags-apply-to-examples? ts1))
                (let [[more-tags after-tags tag-errs] (collect-tag-lines ts1)]
                  (recur after-tags examples (into pending-example-tags more-tags) (into example-tag-errors tag-errs)))

                ;; Anything else means we're done with this Scenario/Outline.
                :else
                [examples ts example-tag-errors]))))
        ;; Implicit outline: regular Scenario with Examples becomes ScenarioOutline
        is-outline (or explicit-outline (seq examples))
        end-idx (or (token-idx (first remaining3)) (inc start-idx))
        span (make-span start-idx end-idx)
        ;; Use :raw for source-text to preserve "Scenario: name" format for keyword extraction
        source-text (or (:raw token) (:value token))]
    [(if is-outline
       (->ScenarioOutline :scenario-outline name description tags steps examples (:keyword-text token) (:location token) source-text (:leading-ws token) span)
       (->Scenario :scenario name description tags steps examples (:keyword-text token) (:location token) source-text (:leading-ws token) span))
     remaining3 (concat desc-errors step-errors example-tag-errors)]))



(defn- parse-scenarios
  "Parse multiple Scenario/ScenarioOutline blocks, skipping blanks/comments between them.
   Handles tags before scenarios. Stops when encountering Rule, Feature, Background, or EOF."
  [ts]
  (letfn [(skip-blanks-comments [t]
            (loop [t t]
              (if (and (seq t) (#{:blank :comment} (:type (first t))))
                (recur (rest t))
                t)))
          (collect-tags [t]
            ;; Collect tag-lines, returning [tags remaining-ts tag-errors] - tags are rich format
            (loop [t t tags [] errs []]
              (if (and (seq t) (= (:type (first t)) :tag-line))
                (let [token (first t)
                      tag-value (:value token)
                      tag-error (when-let [err (:error tag-value)]
                                  (->error :invalid-tag-line (:location token) err :token token))
                      rich-tags (tag-token->rich-tags token)]
                  (recur (rest t)
                         (into tags rich-tags)
                         (if tag-error (conj errs tag-error) errs)))
                [tags t errs])))
          (scenario-start? [t]
            (#{:scenario-line :scenario-outline-line} (:type (first t))))
          (should-stop? [t]
            ;; Stop on Rule, Background, Feature, EOF, or other structural tokens
            (or (empty? t)
                (#{:rule-line :background-line :feature-line :eof} (:type (first t)))))]
    (loop [prev-ts nil
           ts ts
           scenarios []
           errors []]
      (assert-advancing! "parse-scenarios" prev-ts ts)
      (let [ts1 (skip-blanks-comments ts)]
        (cond
          ;; Stop conditions
          (should-stop? ts1)
          [scenarios ts1 errors]

          ;; Tag line - collect tags and check what follows
          (= (:type (first ts1)) :tag-line)
          (let [[collected-tags ts2 tag-errs] (collect-tags ts1)
                ts3 (skip-blanks-comments ts2)]
            (cond
              ;; Tags followed by scenario
              (scenario-start? ts3)
              (let [[scenario remaining scenario-errors] (parse-scenario ts3 collected-tags)]
                (recur ts remaining
                       (conj scenarios scenario)
                       (into (into errors tag-errs) scenario-errors)))
              ;; Tags followed by something else (Rule, etc) - stop, don't consume tags
              :else
              [scenarios ts1 errors]))

          ;; Direct scenario line
          (scenario-start? ts1)
          (let [[scenario remaining scenario-errors] (parse-scenario ts1 [])]
            (recur ts remaining
                   (conj scenarios scenario)
                   (into errors scenario-errors)))

          ;; Anything else - stop
          :else
          [scenarios ts1 errors])))))


(defn- parse-background [ts tags]
  (let [token (first ts)
        start-idx (:idx token)
        name (extract-name (:value token))
        remaining (rest ts)
        ;; Parse description after background line
        [description remaining1 desc-errors] (parse-description remaining)
        [steps remaining2 step-errors] (parse-steps remaining1)
        end-idx (or (token-idx (first remaining2)) (inc start-idx))
        span (make-span start-idx end-idx)
        source-text (or (:raw token) (:value token))]
    [(->Background :background name description steps tags (:keyword-text token) (:location token) source-text (:leading-ws token) span)
     remaining2 (concat desc-errors step-errors)]))

(defn- parse-rule
  "Parse a Rule: block which contains optional background and scenarios."
  [ts tags]
  (let [token (first ts)
        start-idx (:idx token)
        name (extract-name (:value token))
        remaining (rest ts)
        [description remaining2 desc-errors] (parse-description remaining)
        ;; Rule can have its own background
        [background remaining3 bg-errors] (if (and (seq remaining2)
                                                   (= (:type (first remaining2)) :background-line))
                                             (parse-background remaining2 [])
                                             [nil remaining2 []])
        ;; Rule contains scenarios (Example/Scenario)
        [scenarios remaining4 scenarios-errors] (parse-scenarios remaining3)
        ;; Build children: optional background + scenarios
        children (if background
                   (into [background] scenarios)
                   (vec scenarios))
        all-errors (concat desc-errors bg-errors scenarios-errors)
        end-idx (or (token-idx (first remaining4)) (inc start-idx))
        span (make-span start-idx end-idx)]
    [(->Rule :rule name description tags children (:keyword-text token) (:location token) (or (:raw token) (:value token)) (:leading-ws token) span)
     remaining4 all-errors]))

(defn- parse-rules
  "Parse multiple Rule blocks, handling tags and skipping blanks/comments between them."
  [ts]
  (loop [prev-ts nil
         ts ts
         rules []
         errors []
         pending-tags []]
    (assert-advancing! "parse-rules" prev-ts ts)
    ;; Skip blanks and comments before checking for next rule or tags
    (let [ts-trimmed (loop [t ts]
                       (if (and (seq t) (#{:blank :comment} (:type (first t))))
                         (recur (rest t))
                         t))
          token (first ts-trimmed)]
      (cond
        (empty? ts-trimmed)
        ;; Report orphan tags if any pending when we hit EOF
        (let [orphan-errors (when (seq pending-tags)
                              [(->error :unexpected-eof
                                        (:location (first pending-tags))
                                        "Unexpected end of file - tag(s) not followed by Rule"
                                        :tags pending-tags)])]
          [rules ts-trimmed (into errors orphan-errors)])

        ;; Collect tags for the next rule
        (= (:type token) :tag-line)
        (let [tag-value (:value token)
              tag-error (when-let [err (:error tag-value)]
                          (->error :invalid-tag-line (:location token) err :token token))
              rich-tags (tag-token->rich-tags token)]
          (recur ts (rest ts-trimmed) rules
                 (if tag-error (conj errors tag-error) errors)
                 (into pending-tags rich-tags)))

        ;; Parse the rule with accumulated tags
        (= (:type token) :rule-line)
        (let [[rule remaining rule-errors] (parse-rule ts-trimmed pending-tags)]
          (recur ts remaining
                 (conj rules rule)
                 (into errors rule-errors)
                 []))  ; reset pending tags

        ;; Not a rule or tag, we're done
        ;; Report orphan tags if any pending
        :else
        (let [next-type (:type token)
              orphan-errors (when (seq pending-tags)
                              [(->error (if (= next-type :eof) :unexpected-eof :orphan-tags)
                                        (:location (first pending-tags))
                                        (cond
                                          (= next-type :eof)
                                          "Unexpected end of file - tag(s) not followed by Feature/Scenario/Rule"
                                          (= next-type :background-line)
                                          "Tags cannot be applied to Background"
                                          :else
                                          (str "Unexpected tag(s) before " (name next-type)))
                                        :tags pending-tags)])]
          [rules ts-trimmed (into errors orphan-errors)])))))

(defn- parse-feature [ts tags]
  (let [token (first ts)
        start-idx (:idx token)
        name (extract-name (:value token))
        remaining (rest ts)
        [description remaining2 desc-errors] (parse-description remaining)
        ;; Feature-level background (before rules/scenarios)
        [background remaining3 bg-errors] (if (and (seq remaining2)
                                                   (= (:type (first remaining2)) :background-line))
                                             (parse-background remaining2 [])
                                             [nil remaining2 []])
        ;; Parse scenarios first (will stop at rules)
        [scenarios remaining4 scenario-errors] (parse-scenarios remaining3)
        ;; Then parse rules (if any)
        [rules remaining5 rule-errors] (parse-rules remaining4)
        ;; Build children: optional background + scenarios + rules
        children (cond-> []
                   background (conj background)
                   (seq scenarios) (into scenarios)
                   (seq rules) (into rules))
        all-feature-errors (concat desc-errors bg-errors scenario-errors rule-errors)
        end-idx (or (token-idx (first remaining5)) (inc start-idx))
        span (make-span start-idx end-idx)]
    [(->Feature :feature name description tags children (:keyword-text token) nil (:location token) (or (:raw token) (:value token)) (:leading-ws token) span)
     remaining5 all-feature-errors]))

;; -----------------------------------------------------------------------------
;; Validation
;; -----------------------------------------------------------------------------

(defn- validate-ast-order [ast]
  (if (empty? ast)
    []
    (let [structural-nodes (filter #(#{:feature :background :scenario :scenario-outline} (:type %)) ast)
          types (map :type structural-nodes)]
      (cond
        ;; No structural nodes = comment-only file, which is valid
        (empty? types)
        []

        (not= (first types) :feature)
        [(->error :missing-feature (some :location ast) "Feature must be the first structural element")]

        (> (count (filter #(= % :feature) types)) 1)
        [(->error :duplicate-feature (:location (second (filter #(= (:type %) :feature) structural-nodes))) "Multiple Feature declarations not allowed")]

        (> (count (filter #(= % :background) types)) 1)
        [(->error :multiple-backgrounds (:location (second (filter #(= (:type %) :background) structural-nodes))) "Multiple Background sections not allowed")]

        (and (some #(= % :background) types)
             (let [bg-idx (first (keep-indexed #(when (= %2 :background) %1) types))
                   scenario-idx (some #(when (#{:scenario :scenario-outline} (nth types %)) %) (range (count types)))]
               (and scenario-idx (< scenario-idx bg-idx))))
        [(->error :background-after-scenario (:location (some #(when (= (:type %) :background) %) structural-nodes)) "Background must appear before all Scenarios")]

        :else []))))

;; -----------------------------------------------------------------------------
;; Public API
;; -----------------------------------------------------------------------------

(defn parse
  "Parse a sequence of tokens into a Gherkin AST.

   Returns a map with:
   - :tokens — the realized token vector
   - :ast — vector of AST nodes (Feature, Blank, Comment records)
   - :errors — vector of parse errors (empty if successful)

   Example:
     (parse (lex \"Feature: Test\\n  Scenario: Demo\"))"
  [token-seq]
  (let [tokens (vec token-seq)]  ;; Realize lazy seq for random access
    (binding [*dialect* (dialect/english-only-dialect)] ; TODO: detect from tokens
      (loop [ts tokens
             ast []
             errors []
             pending-tags []
             pending-language nil]
        (if (empty? ts)
          ;; Check for orphan tags at EOF (tags not followed by a construct)
          (let [orphan-tag-errors (when (seq pending-tags)
                                    [(->error :unexpected-eof
                                              (:location (first pending-tags))
                                              "Unexpected end of file - tag(s) not followed by Feature/Scenario/Examples"
                                              :tags pending-tags)])]
            {:tokens tokens :ast ast :errors (into (into errors orphan-tag-errors) (validate-ast-order ast))})
        (let [token (first ts)]
          (if (not (tokens/token? token))  ;; Skip non-tokens (errors)—the fix
            (recur (rest ts) ast errors pending-tags pending-language)
            (cond
              (= (:type token) :blank)
              (let [idx (:idx token)]
                (recur (rest ts) (conj ast (->Blank :blank (:location token) (:value token) (:leading-ws token) (make-span idx (inc idx)))) errors pending-tags pending-language))
              (= (:type token) :comment)
              (let [idx (:idx token)]
                (recur (rest ts) (conj ast (->Comment :comment (:value token) (:location token) (:value token) (:leading-ws token) (make-span idx (inc idx)))) errors pending-tags pending-language))
              (= (:type token) :tag-line)
              (let [tag-value (:value token)
                    tag-error (when-let [err (:error tag-value)]
                                (->error :invalid-tag-line (:location token) err :token token))
                    rich-tags (tag-token->rich-tags token)]
                (recur (rest ts) ast (if tag-error (conj errors tag-error) errors) (into pending-tags rich-tags) pending-language))
              (= (:type token) :feature-line)
              (let [[node remaining feature-errors] (parse-feature ts pending-tags)
                    node (assoc node :language pending-language)]
                (recur remaining (conj ast node) (into errors feature-errors) [] nil))
              (= (:type token) :background-line)
              (let [[node remaining bg-errors] (parse-background ts pending-tags)]
                (recur remaining (conj ast node) (into errors bg-errors) [] pending-language))
              (#{:scenario-line :scenario-outline-line} (:type token))
              (let [[node remaining scenario-errors] (parse-scenario ts pending-tags)]
                (recur remaining (conj ast node) (into errors scenario-errors) [] pending-language))
              (= (:type token) :eof)
              (recur (rest ts) ast errors pending-tags pending-language)
              (= (:type token) :language-header)
              ;; Capture language for the next Feature node
              (recur (rest ts) ast errors pending-tags (:value token))
              (= (:type token) :unknown-line)
              (recur (next ts) ast (conj errors (->error :invalid-keyword (:location token) (str "Invalid keyword: " (:value token)) :token token)) pending-tags pending-language)
              :else
              (recur (rest ts) ast (conj errors (->error :unexpected-token (:location token) (str "Unexpected token: " (:type token)) :token token)) pending-tags pending-language)))))))))

(defn node->raw
  "Reconstruct raw text from a node's span using the token vector.
   Returns the original source text covered by this node."
  [tokens node]
  (let [{:keys [start-idx end-idx]} (:span node)]
    (apply str (map :raw (subvec tokens start-idx end-idx)))))

;; -----------------------------------------------------------------------------
;; Helper functions for accessing Feature/Rule children
;; -----------------------------------------------------------------------------

(defn get-background
  "Extract the Background from a Feature or Rule's children, if present."
  [node]
  (first (filter #(= (:type %) :background) (:children node))))

(defn get-scenarios
  "Extract Scenarios and ScenarioOutlines from a Feature or Rule's children."
  [node]
  (filterv #(#{:scenario :scenario-outline} (:type %)) (:children node)))

(defn get-rules
  "Extract Rules from a Feature's children."
  [node]
  (filterv #(= (:type %) :rule) (:children node)))
