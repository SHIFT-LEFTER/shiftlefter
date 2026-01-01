(ns shiftlefter.gherkin.compliance
  "Compliance module for Cucumber Gherkin test suite compatibility.

   This module provides pure projection functions that transform ShiftLefter's
   internal AST representation to Cucumber's expected JSON format. The projection
   layer is intentionally pure and side-effect-free to enable:

   1. Snapshot testing with known-good inputs
   2. Deterministic debugging
   3. Clear boundary between parsing and output formatting

   ## Module Structure

   - Pure Projections: loc->json, step->json, scenario->json, feature->json, ast->ndjson
   - Token Projections: tokens->ndjson (for lexer compliance)
   - Shim Helpers: Tag column calculations for format compatibility
   - IO Functions: download-testdata, run-compliance (side-effectful, for CI/REPL)"
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.java.shell :refer [sh]]
            [clojure.pprint :as pp]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [shiftlefter.gherkin.io :as io]
            [shiftlefter.gherkin.lexer :as lexer]
            [shiftlefter.gherkin.parser :as parser]
            [shiftlefter.gherkin.pickler :as pickler]
            [shiftlefter.gherkin.location :as loc]))

;; -----------------------------------------------------------------------------
;; Specs - Boundary contracts for compliance projection functions
;; -----------------------------------------------------------------------------

(s/def ::json-location (s/keys :req-un [::line ::column]))
(s/def ::line pos-int?)
(s/def ::column pos-int?)

(s/def ::json-step
  (s/keys :req-un [::id ::keyword ::keywordType ::location ::text]))

(s/def ::json-scenario
  (s/keys :req-un [::id ::keyword ::location ::name ::description ::tags ::steps ::examples]))

(s/def ::json-feature
  (s/keys :req-un [::keyword ::location ::name ::description ::tags ::language ::children]))

(s/fdef loc->json
  :args (s/cat :loc ::loc/location)
  :ret ::json-location)

(s/fdef step->json
  :args (s/cat :step (s/keys :req-un [::keyword ::text ::location]))
  :ret ::json-step)

(s/fdef scenario->json
  :args (s/cat :scenario (s/keys :req-un [::name ::location]))
  :ret ::json-scenario)

(s/fdef feature->json
  :args (s/cat :feature (s/keys :req-un [::name ::location]))
  :ret ::json-feature)

(s/fdef ast->ndjson
  :args (s/cat :ast sequential? :uri string?)
  :ret string?)

(s/fdef tokens->ndjson
  :args (s/cat :tokens sequential?)
  :ret string?)

;; Forward declaration for functions used before definition
(declare tags->json-with-ids)

;; -----------------------------------------------------------------------------
;; IO Functions - Side-effectful operations (download, file I/O)
;; -----------------------------------------------------------------------------

(defn download-testdata
  "Download cucumber/gherkin testdata to compliance dir in project root. Returns path to testdata."
  []
  (let [compliance-dir (fs/path "compliance")
        repo-dir (fs/path compliance-dir "gherkin")
        testdata-dir (fs/path compliance-dir "testdata")]
    (fs/create-dirs compliance-dir)
    (when-not (fs/exists? repo-dir)
      (sh "git" "clone" "https://github.com/cucumber/gherkin.git" (str repo-dir)))
    (when-not (fs/exists? testdata-dir)
      (fs/copy-tree (fs/path repo-dir "testdata") testdata-dir))
    (str testdata-dir)))

;; -----------------------------------------------------------------------------
;; Shim Helpers - Format compatibility utilities
;;
;; These helpers handle edge cases where ShiftLefter's internal representation
;; differs from Cucumber's expected format (e.g., tag column positions).
;; -----------------------------------------------------------------------------


;; -----------------------------------------------------------------------------
;; Token Projection - Tokens → Compliance format
;;
;; Transforms lexer output to Cucumber's .tokens format for lexer compliance.
;; -----------------------------------------------------------------------------

(defn- unescaped-pipe?
  "Returns true if the pipe at position idx is unescaped.
   A pipe is escaped if preceded by an odd number of backslashes."
  [s idx]
  (if (zero? idx)
    true
    (let [;; Count consecutive backslashes before this position
          before (subs s 0 idx)
          backslash-count (count (take-while #(= % \\) (reverse before)))]
      ;; Pipe is unescaped if preceded by even number of backslashes (0, 2, 4, ...)
      (even? backslash-count))))

(defn- calculate-table-cell-positions
  "Calculate the 1-indexed column positions where each cell value starts.
   Returns a seq of [column value] pairs.

   Example: '  | type  | content |' with cells [\"type\" \"content\"]
            → [[5 \"type\"] [13 \"content\"]]

   Handles escaped pipes (\\|) which are NOT cell separators."
  [raw-line cells]
  (when (and raw-line (str/includes? raw-line "|"))
    (let [;; Find all UNESCAPED pipe positions (0-indexed)
          pipe-indices (keep-indexed (fn [idx ch]
                                       (when (and (= ch \|) (unescaped-pipe? raw-line idx))
                                         idx))
                                     raw-line)]
      ;; For each pair of pipes, find where the cell content starts
      (loop [pipes (seq pipe-indices)
             remaining-cells cells
             result []]
        (if (or (empty? pipes) (empty? remaining-cells))
          result
          (let [start-pipe (first pipes)
                remaining-pipes (rest pipes)
                cell (first remaining-cells)
                ;; Cell content starts after the pipe and any leading whitespace
                ;; Count Unicode whitespace: space, tab, non-breaking space, etc.
                after-pipe (subs raw-line (inc start-pipe))
                unicode-ws? (fn [ch] (or (Character/isWhitespace ch)
                                         (= ch \u00A0)  ;; non-breaking space
                                         (<= 0x2000 (int ch) 0x200A)  ;; various Unicode spaces
                                         (= ch \u202F) (= ch \u205F) (= ch \u3000)))
                leading-ws (count (take-while unicode-ws? after-pipe))
                cell-col (+ start-pipe 1 leading-ws 1)]  ; +1 for 0→1 indexed, +1 for pipe
            (recur remaining-pipes (rest remaining-cells) (conj result [cell-col cell]))))))))

(defn- strip-docstring-indent
  "Strip the docstring's base indentation from a raw line content.
   Returns the content after the base indentation is removed.
   Also unescapes backslash sequences like \\\"\\\"\\\" → \"\"\"."
  [raw-line base-indent]
  (let [content (io/strip-trailing-eol raw-line)
        ;; Count actual leading spaces
        leading-spaces (count (take-while #(= % \space) content))
        ;; Strip base indent, or all leading spaces if less than base
        chars-to-strip (min leading-spaces base-indent)
        stripped (subs content chars-to-strip)
        ;; Unescape backslash sequences
        unescaped (-> stripped
                      (str/replace "\\\"" "\"")
                      (str/replace "\\`" "`"))]
    unescaped))

(defn tokens->ndjson
  "Convert our token seq to plain-text format matching Cucumber .tokens. Pure projection."
  [tokens]
  ;; Use reduce to track:
  ;; - docstring-indent: base indentation when inside docstring (nil when not in docstring)
  ;; - in-description: true when we've seen description text (unknown-line) after a structural keyword
  ;;   Blank lines become Other:// when in-description, Empty:// otherwise
  ;; - seen-structural?: true after first structural keyword (Feature/Scenario/etc)
  ;;   Comments only enable description mode after we've seen a structural keyword
  (let [{:keys [lines]}
        (reduce
         (fn [{:keys [lines docstring-indent in-description seen-structural?]} token]
           (let [loc (:location token)
                 line (:line loc)
                 col (or (:column loc) 1)
                 token-type (:type token)
                 value (:value token)
                 raw (:raw token)
                 keyword-text (:keyword-text token)]
             (cond
               (= token-type :eof)
               {:lines (conj lines "EOF") :docstring-indent docstring-indent :in-description false :seen-structural? seen-structural?}

               (= token-type :docstring-separator)
               ;; Toggle docstring state and record base indent
               (let [fence (if (= :backtick (:fence value)) "```" "\"\"\"")
                     media (or (:language value) "")
                     new-indent (if docstring-indent nil (dec col))]  ; col is 1-indexed, indent is 0-indexed
                 {:lines (conj lines (str "(" line ":" col ")DocStringSeparator:()" fence "/" media "/"))
                  :docstring-indent new-indent
                  :in-description in-description
                  :seen-structural? seen-structural?})

               ;; Inside docstring: convert unknown-line and blank to Other
               (and docstring-indent (= token-type :unknown-line))
               (let [content (strip-docstring-indent raw docstring-indent)]
                 {:lines (conj lines (str "(" line ":1)Other:/" content "/"))
                  :docstring-indent docstring-indent
                  :in-description in-description
                  :seen-structural? seen-structural?})

               (and docstring-indent (= token-type :blank))
               ;; Blank line inside docstring becomes Other://
               {:lines (conj lines (str "(" line ":1)Other://"))
                :docstring-indent docstring-indent
                :in-description in-description
                :seen-structural? seen-structural?}

               ;; Normal tokens outside docstring
               (= token-type :blank)
               ;; Blank in description context becomes Other, otherwise Empty
               {:lines (conj lines (str "(" line ":" col ")" (if in-description "Other" "Empty") "://"))
                :docstring-indent docstring-indent
                :in-description in-description
                :seen-structural? seen-structural?}

               (= token-type :language-header)
               {:lines (conj lines (str "(" line ":" col ")Language:/" value "/"))
                :docstring-indent docstring-indent
                :in-description in-description
                :seen-structural? seen-structural?}

               (= token-type :comment)
               ;; Cucumber uses "Comment:/" with raw content including leading whitespace
               ;; Column is always 1 for comments
               ;; Comments only enable description mode after we've seen a structural keyword
               (let [raw-content (io/strip-trailing-eol raw)]
                 {:lines (conj lines (str "(" line ":1)Comment:/" raw-content "/"))
                  :docstring-indent docstring-indent
                  :in-description (if seen-structural? true in-description)
                  :seen-structural? seen-structural?})

               (= token-type :tag-line)
               ;; TagLine format: //col:@tag,col:@tag (all tags on one line)
               ;; Lexer provides correct positions in :positions
               (let [tags (:tags value)
                     positions (:positions value)
                     tag-pairs (map (fn [tag pos] (str pos ":@" tag)) tags positions)
                     formatted (str/join "," tag-pairs)]
                 {:lines (conj lines (str "(" line ":" col ")TagLine://" formatted))
                  :docstring-indent docstring-indent
                  :in-description in-description
                  :seen-structural? seen-structural?})

               ;; Structural keywords reset description mode and set seen-structural?
               (= token-type :feature-line)
               (let [kw (or keyword-text "Feature")
                     name (or value "")]
                 {:lines (conj lines (str "(" line ":" col ")FeatureLine:()" kw "/" name "/"))
                  :docstring-indent docstring-indent
                  :in-description false
                  :seen-structural? true})

               (= token-type :scenario-line)
               (let [kw (or keyword-text "Scenario")
                     name (or value "")]
                 {:lines (conj lines (str "(" line ":" col ")ScenarioLine:()" kw "/" name "/"))
                  :docstring-indent docstring-indent
                  :in-description false
                  :seen-structural? true})

               (= token-type :scenario-outline-line)
               (let [kw (or keyword-text "Scenario Outline")
                     name (or value "")]
                 {:lines (conj lines (str "(" line ":" col ")ScenarioLine:()" kw "/" name "/"))
                  :docstring-indent docstring-indent
                  :in-description false
                  :seen-structural? true})

               (= token-type :background-line)
               (let [kw (or keyword-text "Background")
                     name (or value "")]
                 {:lines (conj lines (str "(" line ":" col ")BackgroundLine:()" kw "/" name "/"))
                  :docstring-indent docstring-indent
                  :in-description false
                  :seen-structural? true})

               (= token-type :rule-line)
               (let [kw (or keyword-text "Rule")
                     name (or value "")]
                 {:lines (conj lines (str "(" line ":" col ")RuleLine:()" kw "/" name "/"))
                  :docstring-indent docstring-indent
                  :in-description false
                  :seen-structural? true})

               (= token-type :examples-line)
               (let [kw (or keyword-text "Examples")
                     name (or value "")]
                 {:lines (conj lines (str "(" line ":" col ")ExamplesLine:()" kw "/" name "/"))
                  :docstring-indent docstring-indent
                  :in-description false
                  :seen-structural? true})

               (= token-type :table-row)
               ;; Table rows reset description mode
               (let [raw (:raw value)
                     cells (:cells value)
                     cell-positions (calculate-table-cell-positions raw cells)
                     formatted-cells (str/join "," (map (fn [[c v]] (str c ":" v)) cell-positions))]
                 {:lines (conj lines (str "(" line ":" col ")TableRow://" formatted-cells))
                  :docstring-indent docstring-indent
                  :in-description false
                  :seen-structural? seen-structural?})

               (= token-type :step-line)
               (let [;; Handle new format {:keyword :given :text "..."} or old format "Given ..."
                     [kw text] (if (map? value)
                                 [(name (:keyword value)) (:text value)]
                                 (let [[k & rest] (str/split (or value "") #" " 2)]
                                   [k (first rest)]))
                     ;; Use keyword-text if available (preserves original keyword like "Soit ")
                     kw-display (or keyword-text (str kw " "))
                     ;; Step text is already trimmed in lexer (dialect/match-step-keyword)
                     ;; Check if original keyword was * - should be Unknown, not Conjunction
                     keyword-type (if (and keyword-text (str/starts-with? (str/trim keyword-text) "*"))
                                    "(Unknown)"
                                    (case (keyword (str/lower-case (or kw "")))
                                      :given "(Context)"
                                      :when "(Action)"
                                      :then "(Outcome)"
                                      :and "(Conjunction)"
                                      :but "(Conjunction)"
                                      "(Unknown)"))]
                 {:lines (conj lines (str "(" line ":" col ")StepLine:" keyword-type kw-display "/" (or text "") "/"))
                  :docstring-indent docstring-indent
                  :in-description false
                  :seen-structural? seen-structural?})  ; Steps reset description mode

               ;; Unknown lines (description text) - output as Other, enable description mode
               (= token-type :unknown-line)
               (let [content (io/strip-trailing-eol raw)]
                 {:lines (conj lines (str "(" line ":1)Other:/" content "/"))
                  :docstring-indent docstring-indent
                  :in-description true
                  :seen-structural? seen-structural?})  ; Now in description mode

               :else
               {:lines (conj lines (str "(" line ":" col ")" (name token-type) ":()" value "/"))
                :docstring-indent docstring-indent
                :in-description in-description
                :seen-structural? seen-structural?})))
         {:lines [] :docstring-indent nil :in-description false :seen-structural? false}
         tokens)]
    (str (str/join "\n" lines) "\n")))

;; -----------------------------------------------------------------------------
;; Pure Projection Functions - AST → Compliance JSON
;;
;; These functions are pure (no side effects) and transform ShiftLefter's
;; internal AST nodes to Cucumber's expected JSON structure. They are the
;; core of the compliance layer and are designed for snapshot testing.
;; -----------------------------------------------------------------------------

(defn loc->json
  "Convert internal Location to Cucumber JSON format. Pure projection."
  [loc]
  {:line (:line loc) :column (:column loc)})

(defn loc-key
  "Create a unique key from a location for ID mapping."
  [loc]
  [(:line loc) (:column loc)])

(defn- keyword->type
  "Convert step keyword to Cucumber keywordType.
   For AST output, And/But are 'Conjunction'.
   For pickle output, pass prev-type to inherit from previous step."
  ([kw] (keyword->type kw nil))
  ([kw prev-type]
   (case kw
     "Given" "Context"
     :given "Context"
     "When" "Action"
     :when "Action"
     "Then" "Outcome"
     :then "Outcome"
     ;; And/But inherit from previous step in pickles, or default to Conjunction
     "And" (or prev-type "Conjunction")
     :and (or prev-type "Conjunction")
     "But" (or prev-type "Conjunction")
     :but (or prev-type "Conjunction")
     "*" "Unknown"
     :star "Unknown"
     "Unknown")))

(defn- primary-step-type?
  "Returns true if keyword establishes a new semantic type (not And/But)."
  [kw]
  (contains? #{"Given" :given "When" :when "Then" :then "*" :star} kw))

(defn- data-table-row->json-with-id
  "Convert a DataTable row to Cucumber JSON format with ID.
   Uses the existing calculate-table-cell-positions for cell locations."
  [row row-id]
  (let [cells (:cells row)
        raw (:source-text row)
        row-loc (:location row)
        cell-positions (calculate-table-cell-positions raw cells)
        cell-jsons (mapv (fn [[col val]]
                          {:location {:line (:line row-loc) :column col}
                           :value val})
                        cell-positions)]
    {:cells cell-jsons
     :id (str row-id)
     :location (loc->json row-loc)}))

(defn- data-table->json-with-ids
  "Convert a DataTable to Cucumber JSON format with ID assignment.
   Returns [data-table-json next-id] where IDs are assigned to rows."
  [data-table start-id]
  (let [rows (:rows data-table)
        table-loc (:location data-table)
        [row-jsons next-id]
        (reduce (fn [[jsons current-id] row]
                  [(conj jsons (data-table-row->json-with-id row current-id))
                   (inc current-id)])
                [[] start-id]
                rows)]
    [{:location (loc->json table-loc)
      :rows row-jsons}
     next-id]))

(defn- docstring->json
  "Convert a Docstring to Cucumber JSON format.
   Docstring has: :content, :fence (:quote or :backtick), :mediaType, :location
   Unescapes backslash sequences (\\\" → \" and \\` → `) in content."
  [docstring]
  (let [delimiter (if (= :backtick (:fence docstring)) "```" "\"\"\"")
        ;; Unescape backslash sequences in content
        content (-> (:content docstring)
                    (str/replace "\\\"" "\"")
                    (str/replace "\\`" "`"))]
    (cond-> {:content content
             :delimiter delimiter
             :location (loc->json (:location docstring))}
      (:mediaType docstring) (assoc :mediaType (:mediaType docstring)))))

(defn- step->json-with-id
  "Convert internal Step to Cucumber JSON format with assigned ID.
   If data-table-json or docstring-json is provided, includes it in the output."
  ([step id] (step->json-with-id step id nil nil))
  ([step id data-table-json] (step->json-with-id step id data-table-json nil))
  ([step id data-table-json docstring-json]
   (cond-> {:id (str id)
            ;; Use :keyword-text for localized keywords (e.g., "Soit "), fall back to English
            :keyword (or (:keyword-text step) (str (:keyword step) " "))
            :keywordType (keyword->type (:keyword step))
            :location (loc->json (:location step))
            :text (:text step)}
     data-table-json (assoc :dataTable data-table-json)
     docstring-json (assoc :docString docstring-json))))

(defn step->json
  "Convert internal Step to Cucumber JSON format. Pure projection."
  [step]
  (step->json-with-id step 0))

(defn- extract-keyword-from-source
  "Extract the keyword (Feature, Scenario, etc.) from source-text.
   Source text format: 'Keyword: name' -> returns 'Keyword'"
  [source-text]
  (when source-text
    (str/trim (first (str/split source-text #":")))))

(defn- extract-language-from-tokens
  "Extract language code from token stream.
   Looks for :language-header token. Returns 'en' if not found."
  [tokens]
  (or (some (fn [t] (when (= (:type t) :language-header) (:value t)))
            tokens)
      "en"))

(defn- scenario->json-with-id
  "Convert internal Scenario to Cucumber JSON format with assigned ID."
  [scenario id step-ids]
  (let [[tag-jsons _ _] (tags->json-with-ids (:tags scenario) 0)]
    {:id (str id)
     :keyword (or (extract-keyword-from-source (:source-text scenario)) "Scenario")
     :location (loc->json (:location scenario))
     :name (:name scenario)
     :description ""
     :tags tag-jsons
     :steps (map-indexed (fn [idx step]
                           (step->json-with-id step (nth step-ids idx)))
                         (:steps scenario))
     :examples []}))

(defn scenario->json
  "Convert internal Scenario to Cucumber JSON format. Pure projection."
  [scenario]
  (scenario->json-with-id scenario 1 (range (count (:steps scenario)))))

;; -----------------------------------------------------------------------------
;; ID Assignment for AST Projection
;;
;; Cucumber assigns sequential IDs to AST nodes in a specific order:
;; - Walk each child (background, scenarios, rules) depth-first
;; - For each container, assign IDs to steps first, then to the container
;; - Return both the JSON and the ID mapping for pickle projection
;; -----------------------------------------------------------------------------

(defn- tags->json-with-ids
  "Convert rich tags to Cucumber JSON format with ID assignment.
   Tags in our format: [{:name \"@tag\" :location {:line L :column C}} ...]
   Cucumber format: [{:id \"X\" :location {:line L :column C} :name \"@tag\"} ...]
   Returns [tag-jsons next-id tag-id-map] where tag-id-map maps tag locations to IDs."
  [tags start-id]
  (if (empty? tags)
    [[] start-id {}]
    (let [indexed-tags (map-indexed (fn [idx tag]
                                      (let [tag-id (+ start-id idx)]
                                        {:json {:id (str tag-id)
                                                :location (loc->json (:location tag))
                                                :name (:name tag)}
                                         :loc-key (loc-key (:location tag))
                                         :id tag-id}))
                                    tags)
          tag-jsons (mapv :json indexed-tags)
          tag-id-map (into {} (map (fn [t] [(:loc-key t) (:id t)]) indexed-tags))]
      [tag-jsons (+ start-id (count tags)) tag-id-map])))

(defn- assign-ids-to-steps
  "Assign sequential IDs to steps. Returns [step-jsons next-id id-map].
   DataTable rows get IDs before the step that contains them.
   Docstrings are included inline (no separate IDs for docstring content)."
  [steps start-id]
  (loop [remaining steps
         current-id start-id
         step-jsons []
         id-map {}]
    (if (empty? remaining)
      [step-jsons current-id id-map]
      (let [step (first remaining)
            arg (:argument step)
            ;; Check if step has a DataTable or Docstring argument
            has-data-table? (instance? shiftlefter.gherkin.parser.DataTable arg)
            has-docstring? (instance? shiftlefter.gherkin.parser.Docstring arg)
            ;; Assign IDs to DataTable rows first (they come before step ID)
            [data-table-json next-id-after-table]
            (if has-data-table?
              (data-table->json-with-ids arg current-id)
              [nil current-id])
            ;; Convert Docstring to JSON (no IDs needed for docstring)
            docstring-json (when has-docstring? (docstring->json arg))
            ;; Step gets next ID after DataTable rows
            step-id next-id-after-table
            step-json (step->json-with-id step step-id data-table-json docstring-json)
            loc (loc-key (:location step))]
        (recur (rest remaining)
               (inc step-id)
               (conj step-jsons step-json)
               (assoc id-map loc step-id))))))

(defn- table-row->json-with-id
  "Convert a TableRow to Cucumber JSON format with ID.
   TableRow has: :cells (vec of strings), :location, :source-text
   Cucumber format: {:id \"X\", :location {...}, :cells [{:location {...}, :value \"...\"}]}"
  [table-row row-id]
  (let [cells (:cells table-row)
        raw (:source-text table-row)
        row-loc (:location table-row)
        ;; Calculate cell positions from raw text
        cell-positions (calculate-table-cell-positions raw cells)
        cell-jsons (mapv (fn [[col val]]
                          {:location {:line (:line row-loc) :column col}
                           :value val})
                        cell-positions)]
    {:id (str row-id)
     :location (loc->json row-loc)
     :cells cell-jsons}))

(defn- examples->json-with-ids
  "Convert an Examples block to Cucumber JSON format with ID assignment.
   Cucumber's order: table header → table body rows → tags → container
   Returns [examples-json next-id id-map] where id-map contains row location -> ID mappings."
  [examples start-id]
  (let [;; 1. Assign ID to header row first
        header-row (:table-header-row examples)
        header-id start-id
        header-json (when header-row (table-row->json-with-id header-row header-id))
        header-id-map (if header-row
                        {(loc-key (:location header-row)) header-id}
                        {})
        next-id-after-header (if header-row (inc header-id) start-id)
        ;; 2. Assign IDs to body rows (and build ID map for pickle projection)
        body-rows (:table-body-rows examples)
        [body-jsons next-id-after-body body-id-map]
        (reduce (fn [[jsons current-id id-map] row]
                  [(conj jsons (table-row->json-with-id row current-id))
                   (inc current-id)
                   (assoc id-map (loc-key (:location row)) current-id)])
                [[] next-id-after-header {}]
                body-rows)
        ;; 3. Assign IDs to tags
        [tag-jsons next-id-after-tags tag-id-map] (tags->json-with-ids (:tags examples) next-id-after-body)
        ;; 4. Assign ID to the Examples block itself
        examples-id next-id-after-tags
        examples-json (cond-> {:description (or (:description examples) "")
                               :id (str examples-id)
                               :keyword (or (extract-keyword-from-source (:source-text examples)) "Examples")
                               :location (loc->json (:location examples))
                               :name (or (:name examples) "")
                               :tableBody (vec body-jsons)
                               :tags tag-jsons}
                        ;; Only include tableHeader if it exists (omit null)
                        header-json (assoc :tableHeader header-json))
        id-map (merge header-id-map body-id-map tag-id-map)]
    [examples-json (inc examples-id) id-map]))

(defn- assign-ids-to-examples
  "Assign sequential IDs to Examples blocks. Returns [examples-jsons next-id id-map]."
  [examples start-id]
  (reduce (fn [[jsons current-id id-map] ex]
            (let [[ex-json next-id ex-id-map] (examples->json-with-ids ex current-id)]
              [(conj jsons ex-json) next-id (merge id-map ex-id-map)]))
          [[] start-id {}]
          examples))

(defn- background->json-with-ids
  "Convert internal Background to Cucumber JSON format with ID assignment."
  [background start-id]
  (let [[step-jsons next-id step-map] (assign-ids-to-steps (:steps background) start-id)
        bg-id next-id
        bg-json {:description (or (:description background) "")
                 :id (str bg-id)
                 :keyword (or (extract-keyword-from-source (:source-text background)) "Background")
                 :location (loc->json (:location background))
                 :name (or (:name background) "")
                 :steps step-jsons}]
    [bg-json (inc next-id) (assoc step-map (loc-key (:location background)) bg-id)]))

(defn- scenario->json-with-ids
  "Convert internal Scenario to Cucumber JSON format with ID assignment.
   Cucumber's order: steps → examples → tags → container"
  [scenario start-id]
  (let [;; 1. Assign IDs to steps first
        [step-jsons next-id-after-steps step-map] (assign-ids-to-steps (:steps scenario) start-id)
        ;; 2. Then assign IDs to examples (includes row location -> ID mappings)
        [examples-jsons next-id-after-examples examples-id-map] (assign-ids-to-examples (:examples scenario) next-id-after-steps)
        ;; 3. Then assign IDs to tags
        [tag-jsons next-id-after-tags tag-id-map] (tags->json-with-ids (:tags scenario) next-id-after-examples)
        ;; 4. Finally assign ID to container
        scenario-id next-id-after-tags
        scenario-json {:id (str scenario-id)
                       :keyword (or (extract-keyword-from-source (:source-text scenario)) "Scenario")
                       :location (loc->json (:location scenario))
                       :name (:name scenario)
                       :description (or (:description scenario) "")
                       :tags tag-jsons
                       :steps step-jsons
                       :examples examples-jsons}
        ;; Merge step map with examples ID map, tag ID map, and add scenario location
        full-id-map (-> step-map
                        (merge examples-id-map)
                        (merge tag-id-map)
                        (assoc (loc-key (:location scenario)) scenario-id))]
    [scenario-json (inc next-id-after-tags) full-id-map]))

(defn- rule->json-with-ids
  "Convert internal Rule to Cucumber JSON format with ID assignment.
   Cucumber's order: children (background, scenarios) → tags → container"
  [rule start-id]
  (let [bg (parser/get-background rule)
        scenarios (parser/get-scenarios rule)
        ;; 1. Process background if present
        [bg-result next-id-after-bg id-map-after-bg]
        (if bg
          (let [[bg-json next-id bg-map] (background->json-with-ids bg start-id)]
            [[{:background bg-json}] next-id bg-map])
          [[] start-id {}])
        ;; 2. Process scenarios
        [scenario-results next-id-after-scenarios scenario-map]
        (reduce (fn [[children current-id id-map] scenario]
                  (let [[s-json next-id s-map] (scenario->json-with-ids scenario current-id)]
                    [(conj children {:scenario s-json})
                     next-id
                     (merge id-map s-map)]))
                [bg-result next-id-after-bg id-map-after-bg]
                scenarios)
        ;; 3. Assign IDs to tags
        [tag-jsons next-id-after-tags tag-id-map] (tags->json-with-ids (:tags rule) next-id-after-scenarios)
        ;; 4. Assign ID to container
        rule-id next-id-after-tags
        rule-json {:children scenario-results
                   :description (or (:description rule) "")
                   :id (str rule-id)
                   :keyword (or (extract-keyword-from-source (:source-text rule)) "Rule")
                   :location (loc->json (:location rule))
                   :name (:name rule)
                   :tags tag-jsons}]
    [rule-json (inc rule-id) (-> scenario-map
                                 (merge tag-id-map)
                                 (assoc (loc-key (:location rule)) rule-id))]))

(defn- child->json-with-ids
  "Convert a Feature child with ID assignment. Returns [child-json next-id id-map]."
  [child start-id]
  (case (:type child)
    :background (let [[json next-id id-map] (background->json-with-ids child start-id)]
                  [{:background json} next-id id-map])
    :scenario (let [[json next-id id-map] (scenario->json-with-ids child start-id)]
                [{:scenario json} next-id id-map])
    :scenario-outline (let [[json next-id id-map] (scenario->json-with-ids child start-id)]
                        [{:scenario json} next-id id-map])
    :rule (let [[json next-id id-map] (rule->json-with-ids child start-id)]
            [{:rule json} next-id id-map])))

(defn- feature->json-with-ids
  "Convert internal Feature to Cucumber JSON format with ID assignment.
   Cucumber's order: children → tags (Feature itself has no ID in output).
   Returns [feature-json next-id id-map].
   Optional language parameter overrides default 'en'."
  ([feature start-id] (feature->json-with-ids feature start-id "en"))
  ([feature start-id language]
   (let [;; 1. Process children first
         [children-jsons next-id-after-children children-map]
         (reduce (fn [[children current-id id-map] child]
                   (let [[child-json next-id child-map] (child->json-with-ids child current-id)]
                     [(conj children child-json)
                      next-id
                      (merge id-map child-map)]))
                 [[] start-id {}]
                 (:children feature))
         ;; 2. Then assign IDs to feature tags
         [tag-jsons final-id tag-id-map] (tags->json-with-ids (:tags feature) next-id-after-children)
         feature-json {:keyword (or (extract-keyword-from-source (:source-text feature)) "Feature")
                       :location (loc->json (:location feature))
                       :name (:name feature)
                       :description (or (:description feature) "")
                       :tags tag-jsons
                       :language language
                       :children children-jsons}]
     [feature-json final-id (merge children-map tag-id-map)])))

(defn feature->json
  "Convert internal Feature to Cucumber JSON format. Pure projection."
  [feature]
  (first (feature->json-with-ids feature 0)))

(defn- sort-keys
  "Recursively sort map keys alphabetically for deterministic JSON output."
  [x]
  (cond
    (map? x) (into (sorted-map) (map (fn [[k v]] [k (sort-keys v)]) x))
    (sequential? x) (mapv sort-keys x)
    :else x))

(defn ast->ndjson
  "Convert our AST to NDJSON string (simplified, match their structure)."
  [ast uri]
  (let [doc {:gherkinDocument
             {:comments []
              :feature (feature->json (first ast))
              :uri uri}}]
    (str (json/generate-string (sort-keys doc)) "\n")))

(defn- extract-comments
  "Extract comment tokens and project to Cucumber AST format.
   Returns list of {:location {:line N :column 1} :text \"...\"}"
  [tokens]
  (->> tokens
       (filter #(= (:type %) :comment))
       (map (fn [token]
              ;; Use raw content (includes leading whitespace) minus trailing EOL (LF, CRLF, or CR)
              ;; Location is always column 1 (Cucumber convention)
              {:location {:column 1 :line (:line (:location token))}
               :text (str/replace (or (:raw token) (:value token)) #"\r?\n$|\r$" "")}))
       vec))

(defn ast->ndjson-with-ids
  "Convert our AST to NDJSON string and return ID mapping for pickle projection.
   Returns {:ndjson string :next-id int :id-map {location -> id}}.
   For files without a feature (e.g., comment-only), outputs gherkinDocument without feature field.
   Accepts optional tokens to extract comments and detect language for AST output."
  ([ast uri] (ast->ndjson-with-ids ast uri nil))
  ([ast uri tokens]
   (let [comments (if tokens (extract-comments tokens) [])
         language (if tokens (extract-language-from-tokens tokens) "en")
         ;; Find the actual feature node in the AST (skip comments, blanks, etc.)
         feature-node (first (filter #(= (:type %) :feature) ast))]
     (if (nil? feature-node)
       ;; No feature (comment-only file, empty file, etc.)
       {:ndjson (str (json/generate-string (sort-keys {:gherkinDocument {:comments comments :uri uri}})) "\n")
        :next-id 0
        :id-map {}}
       ;; Has feature
       (let [[feature-json next-id id-map] (feature->json-with-ids feature-node 0 language)
             doc {:gherkinDocument
                  {:comments comments
                   :feature feature-json
                   :uri uri}}]
         {:ndjson (str (json/generate-string (sort-keys doc)) "\n")
          :next-id next-id
          :id-map id-map})))))

;; -----------------------------------------------------------------------------
;; Pickle Projection - Internal pickles → Cucumber JSON format
;;
;; Transforms ShiftLefter's internal pickle format to Cucumber's expected JSON.
;; Requires the AST ID map to populate astNodeIds references.
;;
;; Background Merge:
;; Note: Background steps are now injected by the pickler (see pickler.clj).
;; The compliance layer only needs to project pickle steps to Cucumber JSON format.
;; -----------------------------------------------------------------------------

(defn- build-tags-lookup
  "Build a lookup from the AST for pickle tag projection.

   Returns:
   {:feature-tags [{:name \"@tag\" :location {:line L :column C}} ...]
    :scenarios {[line col] {:tags [...scenario tags...] :rule-tags [...if in rule...]}}
    :examples-rows {[line col] [...examples tags...]}}

   For each scenario location, provides its own tags plus rule tags if applicable.
   For each examples row location, provides the examples tags."
  [ast]
  (let [feature (first ast)]
    (when feature
      (let [feature-tags (or (:tags feature) [])
            children (:children feature)]
        (reduce
         (fn [lookup child]
           (case (:type child)
             ;; Feature-level scenario/outline
             (:scenario :scenario-outline)
             (let [scenario-loc (loc-key (:location child))
                   scenario-tags (or (:tags child) [])
                   ;; For outlines, also capture examples tags for each row
                   examples (:examples child)
                   examples-lookup (reduce
                                    (fn [ex-lkp ex]
                                      (let [ex-tags (or (:tags ex) [])
                                            body-rows (:table-body-rows ex)]
                                        (reduce
                                         (fn [lkp row]
                                           (assoc lkp (loc-key (:location row)) ex-tags))
                                         ex-lkp
                                         body-rows)))
                                    {}
                                    examples)]
               (-> lookup
                   (assoc-in [:scenarios scenario-loc] {:tags scenario-tags :rule-tags []})
                   (update :examples-rows merge examples-lookup)))

             ;; Rule: scenarios inherit rule tags
             :rule
             (let [rule-tags (or (:tags child) [])
                   rule-scenarios (parser/get-scenarios child)]
               (reduce
                (fn [lkp scenario]
                  (let [scenario-loc (loc-key (:location scenario))
                        scenario-tags (or (:tags scenario) [])
                        ;; For outlines in rules, capture examples tags
                        examples (:examples scenario)
                        examples-lookup (reduce
                                         (fn [ex-lkp ex]
                                           (let [ex-tags (or (:tags ex) [])
                                                 body-rows (:table-body-rows ex)]
                                             (reduce
                                              (fn [l row]
                                                (assoc l (loc-key (:location row)) ex-tags))
                                              ex-lkp
                                              body-rows)))
                                         {}
                                         examples)]
                    (-> lkp
                        (assoc-in [:scenarios scenario-loc] {:tags scenario-tags :rule-tags rule-tags})
                        (update :examples-rows merge examples-lookup))))
                lookup
                rule-scenarios))

             ;; Background: skip
             :background lookup

             ;; Default: skip
             lookup))
         {:feature-tags feature-tags :scenarios {} :examples-rows {}}
         children)))))

(defn- tag->pickle-json
  "Convert a rich tag to pickle JSON format using the ast-id-map.
   Tag has {:name \"@tag\" :location {:line L :column C}}.
   Returns {:astNodeId \"X\" :name \"@tag\"}."
  [tag ast-id-map]
  (let [tag-loc (loc-key (:location tag))
        ast-id (get ast-id-map tag-loc)]
    {:astNodeId (str ast-id)
     :name (:name tag)}))

(defn- collect-pickle-tags
  "Collect all applicable tags for a pickle in the correct order.
   Order: feature tags → rule tags → scenario tags → examples tags (for outlines)."
  [tags-lookup ast-id-map scenario-loc row-loc]
  (let [feature-tags (:feature-tags tags-lookup)
        scenario-entry (get-in tags-lookup [:scenarios scenario-loc])
        scenario-tags (or (:tags scenario-entry) [])
        rule-tags (or (:rule-tags scenario-entry) [])
        examples-tags (when row-loc (get-in tags-lookup [:examples-rows row-loc]))
        ;; Combine in order: feature → rule → scenario → examples
        all-tags (concat feature-tags rule-tags scenario-tags (or examples-tags []))]
    (mapv #(tag->pickle-json % ast-id-map) all-tags)))

(defn- unescape-docstring-content
  "Unescape docstring content for pickles.
   Cucumber unescapes \\\" → \" and \\` → ` in pickle docstrings."
  [content]
  (-> content
      (str/replace "\\\"" "\"")
      (str/replace "\\`" "`")))

(defn- arguments->pickle-argument-json
  "Convert pickle step arguments to Cucumber JSON argument format.
   Arguments can be:
   - Vector of cell vectors (DataTable): [[\"a\" \"b\"] [\"c\" \"d\"]]
   - Map with :content (DocString): {:content \"...\" :mediaType \"...\"}
   - nil/empty"
  [arguments]
  (cond
    ;; DataTable: vector of cell vectors
    (and (vector? arguments) (seq arguments) (vector? (first arguments)))
    {:dataTable
     {:rows (mapv (fn [cells]
                    {:cells (mapv (fn [val] {:value val}) cells)})
                  arguments)}}
    ;; DocString: map with :content
    (and (map? arguments) (:content arguments))
    (let [content (unescape-docstring-content (:content arguments))
          media-type (:mediaType arguments)]
      {:docString
       (cond-> {:content content}
         ;; Only include mediaType if non-empty (Cucumber omits empty mediaType)
         (and media-type (seq media-type)) (assoc :mediaType media-type))})
    ;; No argument
    :else nil))

(defn- pickle-step->json
  "Convert an internal pickle step to Cucumber JSON format.
   row-ast-id is included for outline pickles (nil for regular scenarios).
   prev-type is the previous step's semantic type for And/But inheritance."
  [step step-id ast-id-map row-ast-id prev-type]
  (let [step-loc (loc-key (or (:step/location step) (:location step)))
        ast-node-id (get ast-id-map step-loc)
        kw (or (:step/keyword step) (:keyword step))
        step-type (keyword->type kw prev-type)
        ast-node-ids (cond-> []
                       ast-node-id (conj (str ast-node-id))
                       row-ast-id (conj (str row-ast-id)))
        ;; Get arguments from pickle step (already in processed form)
        arguments (or (:step/arguments step) (:arguments step))
        arg-json (arguments->pickle-argument-json arguments)]
    (cond-> {:astNodeIds ast-node-ids
             :id (str step-id)
             :text (or (:step/text step) (:text step))
             :type step-type}
      arg-json (assoc :argument arg-json))))

(defn- pickle->json
  "Convert an internal pickle to Cucumber JSON format.
   Arguments:
   - pickle: The internal pickle map (steps already include backgrounds from pickler)
   - pickle-id: The ID for this pickle
   - step-start-id: Starting ID for pickle steps
   - ast-id-map: Map of location keys to AST node IDs
   - uri: The file URI (relative path)
   - language: Language code for pickle (default 'en')
   - tags-lookup: Tag lookup structure from build-tags-lookup

   Returns {:pickle {...}} wrapped structure.

   Note: Background steps are now injected by the pickler (with :step/origin metadata),
   so this function simply projects all pickle steps without separate background handling."
  [pickle pickle-id step-start-id ast-id-map uri language tags-lookup]
  (let [all-steps (or (:pickle/steps pickle) (:steps pickle))
        ;; Check if this is an outline pickle with row location
        row-loc (:pickle/row-location pickle)
        row-ast-id (when row-loc (get ast-id-map (loc-key row-loc)))
        ;; Get scenario location (stored directly for outlines, or from pickle location)
        scenario-loc (or (:pickle/scenario-location pickle)
                         (:pickle/location pickle)
                         (:location pickle))
        scenario-ast-id (when scenario-loc (get ast-id-map (loc-key scenario-loc)))
        ;; Collect tags for this pickle
        pickle-tags (if tags-lookup
                      (collect-pickle-tags tags-lookup ast-id-map
                                           (loc-key scenario-loc)
                                           (when row-loc (loc-key row-loc)))
                      [])
        ;; Project all steps with type inheritance
        all-step-jsons (first
                        (reduce
                         (fn [[jsons current-id prev-type] step]
                           (let [kw (or (:step/keyword step) (:keyword step))
                                 step-json (pickle-step->json step current-id ast-id-map row-ast-id prev-type)
                                 ;; Track the semantic type for inheritance
                                 new-prev-type (if (primary-step-type? kw)
                                                 (:type step-json)
                                                 prev-type)]
                             [(conj jsons step-json) (inc current-id) new-prev-type]))
                         [[] step-start-id nil]
                         all-steps))
        loc (or (:pickle/location pickle) (:location pickle))
        ;; Build astNodeIds: [scenario-id, row-id] for outlines, [scenario-id] for regular
        ast-node-ids (cond-> []
                       scenario-ast-id (conj (str scenario-ast-id))
                       row-ast-id (conj (str row-ast-id)))]
    {:pickle
     {:astNodeIds ast-node-ids
      :id (str pickle-id)
      :language language
      :location (loc->json loc)
      :name (or (:pickle/name pickle) (:name pickle))
      :steps (vec all-step-jsons)
      :tags pickle-tags
      :uri uri}}))

(defn pickles->ndjson
  "Convert our pickles to NDJSON string (legacy format, no ID mapping)."
  [pickles]
  (->> pickles
       (map json/generate-string)
       (str/join "\n")))

(defn pickles->ndjson-with-ids
  "Convert our pickles to Cucumber NDJSON format with proper IDs.
   Arguments:
   - pickles: Sequence of internal pickle maps (steps already include backgrounds)
   - ast-id-map: Map of location keys to AST node IDs
   - next-id: The next available ID (continues from AST)
   - uri: The file URI (relative path)
   - ast: The AST (for tags lookup only; backgrounds are already in pickles)
   - language: Language code (default 'en')

   Returns NDJSON string.

   Note: Background steps are now injected by the pickler, so this function
   no longer needs to do background lookup/injection."
  ([pickles ast-id-map next-id uri ast] (pickles->ndjson-with-ids pickles ast-id-map next-id uri ast "en"))
  ([pickles ast-id-map next-id uri ast language]
   (let [tags-lookup (build-tags-lookup ast)]
     (loop [remaining pickles
            current-id next-id
            lines []]
       (if (empty? remaining)
         (str (str/join "\n" lines) (when (seq lines) "\n"))
         (let [pickle (first remaining)
               all-steps (or (:pickle/steps pickle) (:steps pickle))
               step-count (count all-steps)
               ;; Steps get IDs first, then pickle gets an ID
               step-start-id current-id
               pickle-id (+ current-id step-count)
               pickle-json (pickle->json pickle pickle-id step-start-id ast-id-map uri language tags-lookup)
               line (json/generate-string (sort-keys pickle-json))]
           (recur (rest remaining)
                  (+ pickle-id 1)
                  (conj lines line))))))))

(defn diff-ndjson
  "Diff two NDJSON files. Returns true if identical."
  [our-file expected-file]
  (= (slurp our-file) (slurp expected-file)))

(defn run-compliance
  "Run compliance on testdata dir. Returns report map with granular failure tracking.

   Returns:
   {:good {:total N :full-pass N :tokens-pass N :ast-pass N
           :parse-errors [{:file F :errors [...]}...]
           :token-fails [{:file F :ours S :expected S}...]
           :ast-fails [{:file F :ours S :expected S}...]
           :pickle-fails [{:file F :ours S :expected S}...]}
    :bad {:total N :passes N :fails [...]}}"
  [testdata-dir]
  (let [good-dir (fs/path testdata-dir "good")
        bad-dir (fs/path testdata-dir "bad")
        good-files (fs/glob good-dir "*.feature")
        bad-files (fs/glob bad-dir "*.feature")
        good-result (loop [gf good-files
                           full-pass 0
                           tokens-pass 0
                           ast-pass 0
                           parse-errors []
                           token-fails []
                           ast-fails []
                           pickle-fails []]
                      (if (empty? gf)
                        {:full-pass full-pass
                         :tokens-pass tokens-pass
                         :ast-pass ast-pass
                         :parse-errors parse-errors
                         :token-fails token-fails
                         :ast-fails ast-fails
                         :pickle-fails pickle-fails}
                        (let [f (first gf)
                              fname (str (fs/file-name f))
                              content (slurp (str f))
                              tokens (lexer/lex content)
                              parse-result (parser/parse tokens)
                              errors (:errors parse-result)]
                          (if (seq errors)
                            (recur (rest gf) full-pass tokens-pass ast-pass
                                   (conj parse-errors {:file fname :errors errors})
                                   token-fails ast-fails pickle-fails)
                            (let [ast (:ast parse-result)
                                  uri (str "../testdata/" (fs/relativize testdata-dir f))
                                  pickles (pickler/pre-pickles ast {} (str f))
                                  our-tokens (tokens->ndjson tokens)
                                  ;; Extract language from tokens for i18n support
                                  language (extract-language-from-tokens tokens)
                                  ;; Use ID-aware projection for AST and pickles
                                  ast-result (ast->ndjson-with-ids ast uri tokens)
                                  our-ast (:ndjson ast-result)
                                  our-pickles (pickles->ndjson-with-ids pickles (:id-map ast-result) (:next-id ast-result) uri ast language)
                                  expected-tokens-file (fs/path (fs/parent f) (str (fs/file-name f) ".tokens"))
                                  expected-ast-file (fs/path (fs/parent f) (str (fs/file-name f) ".ast.ndjson"))
                                  expected-pickles-file (fs/path (fs/parent f) (str (fs/file-name f) ".pickles.ndjson"))
                                  expected-tokens (when (fs/exists? expected-tokens-file) (slurp (str expected-tokens-file)))
                                  expected-ast (when (fs/exists? expected-ast-file) (slurp (str expected-ast-file)))
                                  expected-pickles (when (fs/exists? expected-pickles-file) (slurp (str expected-pickles-file)))
                                  tokens-match (= our-tokens expected-tokens)
                                  ;; Compare JSON semantically (ignoring whitespace differences)
                                  ast-match (= (json/parse-string our-ast)
                                               (json/parse-string expected-ast))
                                  pickles-match (= (json/parse-string our-pickles)
                                                   (json/parse-string expected-pickles))]
                              (cond
                                (not tokens-match)
                                (recur (rest gf) full-pass tokens-pass ast-pass
                                       parse-errors
                                       (conj token-fails {:file fname :ours our-tokens :expected expected-tokens})
                                       ast-fails pickle-fails)
                                (not ast-match)
                                (recur (rest gf) full-pass (inc tokens-pass) ast-pass
                                       parse-errors token-fails
                                       (conj ast-fails {:file fname :ours our-ast :expected expected-ast})
                                       pickle-fails)
                                (not pickles-match)
                                (recur (rest gf) full-pass (inc tokens-pass) (inc ast-pass)
                                       parse-errors token-fails ast-fails
                                       (conj pickle-fails {:file fname :ours our-pickles :expected expected-pickles}))
                                :else
                                (recur (rest gf) (inc full-pass) (inc tokens-pass) (inc ast-pass)
                                       parse-errors token-fails ast-fails pickle-fails)))))))
        bad-result (loop [bf bad-files bad-passes 0 bad-fails []]
                     (if (empty? bf)
                       {:passes bad-passes :fails bad-fails}
                       (let [f (first bf)
                             content (slurp (str f))
                             tokens (lexer/lex content)
                             result (parser/parse tokens)]
                         (if (seq (:errors result))
                           (recur (rest bf) (inc bad-passes) bad-fails)
                           (recur (rest bf) bad-passes (conj bad-fails (str (fs/file-name f))))))))]
    {:good (assoc good-result :total (count good-files))
     :bad {:total (count bad-files) :passes (:passes bad-result) :fails (:fails bad-result)}}))

;; -----------------------------------------------------------------------------
;; Artifact Saving - Write failure details to run directory
;; -----------------------------------------------------------------------------

(defn save-failure-artifacts!
  "Save failure artifacts to run-dir. Creates failures/ and errors/ subdirs.

   Arguments:
   - run-dir: Path to the run directory (will create subdirs)
   - report: The report map from run-compliance"
  [run-dir report]
  (let [failures-dir (fs/path run-dir "failures")
        errors-dir (fs/path run-dir "errors")
        good (:good report)]
    ;; Create subdirs
    (fs/create-dirs failures-dir)
    (fs/create-dirs errors-dir)

    ;; Save parse errors
    (doseq [{:keys [file errors]} (:parse-errors good)]
      (spit (str (fs/path errors-dir (str file ".errors.edn")))
            (with-out-str (pp/pprint errors))))

    ;; Save token failures
    (doseq [{:keys [file ours expected]} (:token-fails good)]
      (spit (str (fs/path failures-dir (str file ".our-tokens"))) (or ours ""))
      (when expected
        (spit (str (fs/path failures-dir (str file ".expected-tokens"))) expected)))

    ;; Save AST failures
    (doseq [{:keys [file ours expected]} (:ast-fails good)]
      (spit (str (fs/path failures-dir (str file ".our-ast.ndjson"))) (or ours ""))
      (when expected
        (spit (str (fs/path failures-dir (str file ".expected-ast.ndjson"))) expected)))

    ;; Save pickle failures
    (doseq [{:keys [file ours expected]} (:pickle-fails good)]
      (spit (str (fs/path failures-dir (str file ".our-pickles.ndjson"))) (or ours ""))
      (when expected
        (spit (str (fs/path failures-dir (str file ".expected-pickles.ndjson"))) expected)))

    ;; Return count of artifacts written
    {:errors-written (count (:parse-errors good))
     :failures-written (+ (count (:token-fails good))
                          (count (:ast-fails good))
                          (count (:pickle-fails good)))}))
