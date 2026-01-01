(ns shiftlefter.gherkin.pickler
  "Pickler for ShiftLefter Gherkin parser — turns AST into flat pickles.

   ## Pipeline Architecture

   The pickle pipeline has two named phases:

   1. `ast->pickle-plan`: AST → Pickle Plan (intermediate, inspectable)
      - Extracts structure from AST without generating UUIDs
      - Captures feature/scenario/step hierarchy
      - Preserves macro provenance and outline examples

   2. `pickle-plan->pickles`: Pickle Plan → Final Pickles
      - Generates UUIDs for each pickle and step
      - Expands Scenario Outlines into multiple pickles
      - Produces the final executable pickle format

   This separation enables:
   - Debugging: Inspect the plan before final expansion
   - Testing: Verify structure without UUID randomness
   - Extension: Add transformations between phases"
  (:require [clojure.string :as str]
            [cheshire.core :as json]
            [clojure.spec.alpha :as s]
            [shiftlefter.gherkin.parser :as parser]))

;; -----------------------------------------------------------------------------
;; Helpers
;; -----------------------------------------------------------------------------

(defn- argument->pickle-arguments
  "Extract argument data (table or docstring) in pickle format."
  [argument]
  (cond
    (instance? shiftlefter.gherkin.parser.DataTable argument)
    (mapv :cells (:rows argument))
    (instance? shiftlefter.gherkin.parser.Docstring argument)
    {:content (:content argument), :mediaType (:mediaType argument)}
    :else []))

;; -----------------------------------------------------------------------------
;; Specs - Pipeline Data Structures
;; -----------------------------------------------------------------------------

;; Input specs
(s/def ::ast (s/coll-of map?))
(s/def ::errors (s/coll-of map?))
(s/def ::registry map?)
(s/def ::source-file string?)

;; Pickle Plan specs (intermediate representation)
(s/def ::step-plan
  (s/keys :req-un [::keyword ::text ::location]
          :opt-un [::source ::arguments]))

(s/def ::scenario-plan
  (s/keys :req-un [::name ::location ::tags ::steps ::type]
          :opt-un [::examples]))

(s/def ::feature-plan
  (s/keys :req-un [::name ::tags ::scenarios]))

(s/def ::pickle-plan
  (s/keys :req-un [::features ::source-file]))

;; Final pickle specs
(s/def ::pickles (s/coll-of map?))

(defn- parse-step-keyword-and-text
  "Extract keyword and text from a step map.
   For expanded steps, :keyword and :text are already present.
   For original steps, parse from :text."
  [step]
  (if (:keyword step)
    [(:keyword step) (:text step)]
    (let [text (str/trim (:text step))
          [_ keyword text-part] (re-matches #"^(Given|When|Then|And|But) (.+)" text)]
      [keyword text-part])))

(defn- replace-placeholders
  "Replace <placeholder> with values from row-map in text."
  [text row-map]
  (if (and text row-map)
    (reduce (fn [txt [k v]]
              (str/replace txt (str "<" k ">") v))
            text row-map)
    text))

(defn- substitute-arguments
  "Substitute placeholders in step arguments (DataTable cells, DocString content/mediaType)."
  [arguments row-map]
  (cond
    ;; No row-map, return as-is
    (nil? row-map) arguments

    ;; DataTable: vector of cell vectors
    (and (vector? arguments) (seq arguments) (vector? (first arguments)))
    (mapv (fn [cells]
            (mapv #(replace-placeholders % row-map) cells))
          arguments)

    ;; DocString: map with :content
    (and (map? arguments) (:content arguments))
    (cond-> {:content (replace-placeholders (:content arguments) row-map)}
      (:mediaType arguments) (assoc :mediaType (replace-placeholders (:mediaType arguments) row-map)))

    ;; Otherwise return as-is
    :else arguments))

;; -----------------------------------------------------------------------------
;; Phase 1: AST → Pickle Plan
;; -----------------------------------------------------------------------------

(defn- step->step-plan
  "Convert an AST step to a step-plan (no UUID generation).

   Handles both:
   - Pre-expansion steps (:macro-step type → create provenance)
   - Post-expansion steps (already have :source from macro expansion)

   The `origin` parameter indicates where this step comes from:
   - :feature-background - from the Feature's Background block
   - :rule-background - from a Rule's Background block
   - :scenario - from the Scenario/Outline itself (default)"
  ([step] (step->step-plan step :scenario))
  ([step origin]
   (let [is-macro (= (:type step) :macro-step)
         [kw txt] (parse-step-keyword-and-text step)
         ;; Use existing :source (from macro expansion) or create new one (for macro-steps)
         source (or (:source step)
                    (when is-macro
                      {:macro-name (:text step)
                       :original-location (:location step)}))]
     {:keyword kw
      :text txt
      :location (:location step)
      :source source
      :origin origin
      :arguments (argument->pickle-arguments (:argument step))})))

(defn- scenario->scenario-plan
  "Convert an AST scenario to a scenario-plan.

   Parameters:
   - scenario: The AST scenario node
   - feature-bg-steps: Step-plans from feature background (already converted with origin)
   - rule-bg-steps: Step-plans from rule background (already converted with origin), or nil
   - rule-tags: Tags from the parent Rule (if scenario is inside a rule), or nil

   Note: Backgrounds are only prepended if the scenario has steps.
   Cucumber doesn't inject background steps into empty scenarios."
  ([scenario] (scenario->scenario-plan scenario nil nil nil))
  ([scenario feature-bg-steps rule-bg-steps rule-tags]
   (let [scenario-steps (mapv step->step-plan (:steps scenario))
         ;; Only prepend backgrounds if scenario has steps
         ;; (Cucumber doesn't inject background into empty scenarios)
         all-steps (if (seq scenario-steps)
                     (vec (concat (or feature-bg-steps [])
                                  (or rule-bg-steps [])
                                  scenario-steps))
                     [])]
     {:name (:name scenario)
      :location (:location scenario)
      :tags (or (:tags scenario) [])
      :rule-tags (or rule-tags [])
      :type (:type scenario)
      :steps all-steps
      :examples (when (= (:type scenario) :scenario-outline)
                  (:examples scenario))})))

(defn- feature->feature-plan
  "Convert an AST feature to a feature-plan.
   Collects scenarios from both feature level AND rules (if present).

   Background injection:
   - Feature-level scenarios get feature background steps prepended
   - Rule scenarios get feature background + rule background steps prepended

   Tag inheritance:
   - Feature-level scenarios: feature tags + scenario tags
   - Rule scenarios: feature tags + rule tags + scenario tags

   Each background step carries :origin metadata (:feature-background or :rule-background)."
  [feature]
  (let [;; Extract feature-level background steps
        feature-bg (parser/get-background feature)
        feature-bg-steps (when feature-bg
                           (mapv #(step->step-plan % :feature-background)
                                 (:steps feature-bg)))

        ;; Get feature-level scenarios (not inside rules)
        ;; These have no rule-tags (nil)
        feature-scenarios (parser/get-scenarios feature)
        feature-scenario-plans (mapv #(scenario->scenario-plan % feature-bg-steps nil nil)
                                     feature-scenarios)

        ;; Get scenarios from each rule, with rule backgrounds and rule tags
        rules (parser/get-rules feature)
        rule-scenario-plans (mapcat
                             (fn [rule]
                               (let [rule-bg (parser/get-background rule)
                                     rule-bg-steps (when rule-bg
                                                     (mapv #(step->step-plan % :rule-background)
                                                           (:steps rule-bg)))
                                     rule-tags (or (:tags rule) [])
                                     rule-scenarios (parser/get-scenarios rule)]
                                 (mapv #(scenario->scenario-plan % feature-bg-steps rule-bg-steps rule-tags)
                                       rule-scenarios)))
                             rules)

        ;; Combine both (feature-level first, then rule scenarios)
        all-scenario-plans (vec (concat feature-scenario-plans rule-scenario-plans))]
    {:name (:name feature)
     :tags (or (:tags feature) [])
     :scenarios all-scenario-plans}))

(defn ast->pickle-plan
  "Phase 1: Transform AST into an intermediate pickle-plan.

   The pickle-plan captures all structure needed for pickle generation
   without generating UUIDs. This enables inspection and testing of
   the extraction logic independently from the final pickle format.

   Returns: {:features [...] :source-file string}"
  [ast source-file]
  {:features (mapv feature->feature-plan
                   (filter #(= :feature (:type %)) ast))
   :source-file source-file})

;; -----------------------------------------------------------------------------
;; Phase 2: Pickle Plan → Final Pickles
;; -----------------------------------------------------------------------------

(defn- dedupe-tags
  "Remove duplicate tags while preserving first occurrence order.
   Tags are compared by :name."
  [tags]
  (let [seen (volatile! #{})]
    (filterv (fn [tag]
               (let [name (:name tag)]
                 (if (@seen name)
                   false
                   (do (vswap! seen conj name)
                       true))))
             tags)))

(defn- step-plan->pickle-step
  "Convert a step-plan to a final pickle step with UUID."
  [step-plan row-map]
  {:step/id (java.util.UUID/randomUUID)
   :step/text (if row-map
                (replace-placeholders (:text step-plan) row-map)
                (:text step-plan))
   :step/keyword (:keyword step-plan)
   :step/location (:location step-plan)
   :step/source (:source step-plan)
   :step/origin (:origin step-plan)
   ;; Substitute placeholders in arguments too (DataTable cells, DocString content/mediaType)
   :step/arguments (substitute-arguments (:arguments step-plan) row-map)})

(defn- expand-outline-examples
  "Expand a scenario outline into multiple pickles (one per example row).
   Tag order: feature → rule → scenario → examples (with deduplication)."
  [scenario-plan feature-tags source-file]
  (let [rule-tags (:rule-tags scenario-plan)
        scenario-tags (:tags scenario-plan)]
    (mapcat (fn [example]
              (let [header (:table-header example)
                    ;; Use full TableRow records if available (for compliance), fall back to cell values
                    body-rows (or (:table-body-rows example) [])
                    body (or (:table-body example) [])
                    example-tags (or (:tags example) [])
                    ;; Combine all tags: feature → rule → scenario → examples
                    all-tags (dedupe-tags (vec (concat feature-tags rule-tags scenario-tags example-tags)))]
                ;; If we have full rows with locations, use them
                (if (seq body-rows)
                  (map (fn [table-row]
                         (let [row-values (:cells table-row)
                               row-map (zipmap header row-values)
                               row-location (:location table-row)]
                           {:pickle/id (java.util.UUID/randomUUID)
                            :pickle/name (replace-placeholders (:name scenario-plan) row-map)
                            :pickle/source-file source-file
                            :pickle/location row-location  ;; Use row location, not scenario location
                            :pickle/tags all-tags
                            :pickle/scenario-location (:location scenario-plan)  ;; Store scenario location for pickle astNodeIds
                            :pickle/row-location row-location  ;; Store for compliance projection
                            :pickle/steps (mapv #(step-plan->pickle-step % row-map)
                                                (:steps scenario-plan))}))
                       body-rows)
                  ;; Fallback for old format without TableRow records
                  (map (fn [row-values]
                         (let [row-map (zipmap header row-values)]
                           {:pickle/id (java.util.UUID/randomUUID)
                            :pickle/name (replace-placeholders (:name scenario-plan) row-map)
                            :pickle/source-file source-file
                            :pickle/location (:location scenario-plan)
                            :pickle/tags all-tags
                            :pickle/scenario-location (:location scenario-plan)
                            :pickle/steps (mapv #(step-plan->pickle-step % row-map)
                                                (:steps scenario-plan))}))
                       body))))
            (:examples scenario-plan))))

(defn- scenario-plan->pickles
  "Convert a scenario-plan to pickle(s). Outlines expand to multiple pickles.
   Scenario Outlines without Examples are treated as regular scenarios.
   Tag order: feature → rule → scenario (with deduplication)."
  [scenario-plan feature-tags source-file]
  (if (and (= (:type scenario-plan) :scenario-outline)
           (seq (:examples scenario-plan)))
    ;; Outline with examples: expand to multiple pickles
    (expand-outline-examples scenario-plan feature-tags source-file)
    ;; Regular scenario OR outline without examples: single pickle
    (let [rule-tags (:rule-tags scenario-plan)
          scenario-tags (:tags scenario-plan)
          all-tags (dedupe-tags (vec (concat feature-tags rule-tags scenario-tags)))]
      [{:pickle/id (java.util.UUID/randomUUID)
        :pickle/name (:name scenario-plan)
        :pickle/source-file source-file
        :pickle/location (:location scenario-plan)
        :pickle/tags all-tags
        :pickle/steps (mapv #(step-plan->pickle-step % nil)
                            (:steps scenario-plan))}])))

(defn pickle-plan->pickles
  "Phase 2: Transform pickle-plan into final pickles with UUIDs.

   Expands Scenario Outlines into multiple pickles (one per example row).
   Generates UUIDs for each pickle and step.

   Returns: {:pickles seq :errors []}"
  [pickle-plan]
  (let [source-file (:source-file pickle-plan)]
    {:pickles (mapcat (fn [feature-plan]
                        (mapcat #(scenario-plan->pickles % (:tags feature-plan) source-file)
                                (:scenarios feature-plan)))
                      (:features pickle-plan))
     :errors []}))

;; -----------------------------------------------------------------------------
;; Public API (uses pipeline internally)
;; -----------------------------------------------------------------------------

(defn pickles
  "Turn AST into flat pickle maps using the two-phase pipeline.

   This is the primary public API. For debugging/inspection, use
   `ast->pickle-plan` and `pickle-plan->pickles` separately.

   (pickles ast registry source-file) → {:pickles vec :errors vec}

   Note: `registry` param kept for API compatibility but unused
   (macro expansion happens before pickling)."
  [ast _registry source-file]
  (-> (ast->pickle-plan ast source-file)
      (pickle-plan->pickles)))

(defn pre-pickles
  "Transform AST into flat pickle maps using the two-phase pipeline.

   Returns a seq of pickles (not wrapped in {:pickles ...}).
   For the wrapped version, use `pickles`.

   (pre-pickles pre-ast registry source-file) → seq of pickle maps

   Note: `registry` param kept for API compatibility but unused."
  [pre-ast _registry source-file]
  (:pickles (-> (ast->pickle-plan pre-ast source-file)
                (pickle-plan->pickles))))

;; Pipeline function specs
(s/fdef ast->pickle-plan
  :args (s/cat :ast ::ast :source-file ::source-file)
  :ret ::pickle-plan)

(s/fdef pickle-plan->pickles
  :args (s/cat :pickle-plan ::pickle-plan)
  :ret (s/keys :req-un [::pickles ::errors]))

;; Public API specs
(s/fdef pickles
  :args (s/cat :ast ::ast :registry ::registry :source-file ::source-file)
  :ret (s/keys :req-un [::pickles ::errors]))

(s/fdef pre-pickles
  :args (s/cat :pre-ast ::ast :registry ::registry :source-file ::source-file)
  :ret ::pickles)

(defn pickles->edn [pickles]
  (pr-str pickles))

(defn pickles->json [pickles]
  (json/generate-string pickles))

(defn pickles->ndjson [pickles]
  (str/join "\n" (map json/generate-string pickles)))
