(ns shiftlefter.stepengine.bind
  "Step binding / planning pass for ShiftLefter runner.

   Binds pickle steps to step definitions before execution, producing
   run plans with full binding information. This enables:
   - Early detection of undefined/ambiguous steps (exit 2, no execution)
   - Complete plan visibility before any side effects
   - Clear diagnostics for all binding issues

   ## Binding Rules

   - Full-string match only (`re-matches`, not `re-find`)
   - 0 matches → `:status :undefined`
   - 1 match → `:status :matched`
   - 2+ matches → `:status :ambiguous`

   ## Arity Validation

   For matched steps, validates that step function arity is compatible:
   - `C` = capture count (from regex groups)
   - `A` = declared arity (from stepdef)
   - Valid if `A == C` (captures only) or `A == C+1` (captures + ctx)

   ## SVO Extraction

   For matched steps with `:metadata` containing `:svo`, extracts SVOI:
   - `:svoi` key added to binding with extracted subject/verb/object/interface
   - Legacy steps (no metadata) get `:svoi nil`

   ## SVO Validation

   When validation options are provided to `bind-suite`, extracted SVOIs are
   validated against glossaries and interface config:
   - `:unknown-subject :warn` — log warning, don't block
   - `:unknown-subject :error` — log error, block execution
   - Same for `:unknown-verb` and `:unknown-interface`

   ## Usage

   ```clojure
   (let [stepdefs (registry/all-stepdefs)
         {:keys [plans diagnostics]} (bind-suite pickles stepdefs)]
     (if (seq (:issues diagnostics))
       (report-and-exit-2 diagnostics)
       (execute-plans plans)))
   ```"
  (:require [shiftlefter.svo.extract :as extract]
            [shiftlefter.svo.validate :as validate]))

;; -----------------------------------------------------------------------------
;; Step Matching
;; -----------------------------------------------------------------------------

(defn match-step
  "Match step text against a stepdef using full-string match.

   Returns nil if no match, or map with:
   - :stepdef - the matching stepdef
   - :captures - vector of captured groups (may include nils for optional groups)"
  [step-text stepdef]
  (when-let [match (re-matches (:pattern stepdef) step-text)]
    {:stepdef stepdef
     :captures (if (string? match)
                 []  ;; No capture groups
                 (vec (rest match)))}))

(defn- find-all-matches
  "Find all stepdefs that match a step text.
   Returns seq of {:stepdef ... :captures ...} maps."
  [step-text stepdefs]
  (keep #(match-step step-text %) stepdefs))

;; -----------------------------------------------------------------------------
;; Arity Validation
;; -----------------------------------------------------------------------------

(defn validate-arity
  "Validate that stepdef arity is compatible with capture count.

   - C = capture count
   - A = declared arity
   - Valid if A == C (captures only) or A == C+1 (captures + ctx)

   Returns:
   - :arity-ok? - boolean
   - :expected - set of valid arities #{C (C+1)}
   - :actual - declared arity A"
  [capture-count declared-arity]
  (let [expected #{capture-count (inc capture-count)}]
    {:arity-ok? (contains? expected declared-arity)
     :expected expected
     :actual declared-arity}))

;; -----------------------------------------------------------------------------
;; Step Binding
;; -----------------------------------------------------------------------------

(defn- stepdef-summary
  "Extract summary info from stepdef for alternatives/diagnostics."
  [stepdef]
  (select-keys stepdef [:stepdef/id :pattern-src :source]))

(defn bind-step
  "Bind a pickle step to stepdefs.

   Returns:
   - :status - :matched | :undefined | :ambiguous | :synthetic
   - :step - the original pickle step
   - :binding - for matched: stepdef info + captures + arity validation
   - :alternatives - for ambiguous: all matching stepdefs (summaries)

   Synthetic steps (macro wrappers with :step/synthetic? true) are auto-bound
   with :status :synthetic and no binding - they exist for events/reporting only."
  [pickle-step stepdefs]
  ;; Synthetic steps (macro wrappers) don't need binding
  (if (:step/synthetic? pickle-step)
    {:status :synthetic
     :step pickle-step
     :binding nil
     :alternatives []}
    ;; Regular steps need stepdef matching
    (let [step-text (:step/text pickle-step)
          matches (find-all-matches step-text stepdefs)]
      (case (count matches)
        ;; No matches - undefined
        0 {:status :undefined
           :step pickle-step
           :binding nil
           :alternatives []}

        ;; Single match - check arity and extract SVOI
        1 (let [{:keys [stepdef captures]} (first matches)
                arity-info (validate-arity (count captures) (:arity stepdef))
                metadata (:metadata stepdef)
                svoi (extract/extract-svoi metadata captures)]
            {:status :matched
             :step pickle-step
             :binding (merge (stepdef-summary stepdef)
                             {:captures captures
                              :fn (:fn stepdef)
                              :arity (:arity stepdef)
                              :svoi svoi}
                             arity-info)
             :alternatives []})

        ;; Multiple matches - ambiguous
        (let [summaries (mapv #(stepdef-summary (:stepdef %)) matches)]
          {:status :ambiguous
           :step pickle-step
           :binding nil
           :alternatives summaries})))))

;; -----------------------------------------------------------------------------
;; Pickle Binding (Run Plan Generation)
;; -----------------------------------------------------------------------------

(defn bind-pickle
  "Generate a run plan for a pickle by binding all its steps.

   Returns run plan:
   - :plan/id - UUID for this plan
   - :plan/pickle - the original pickle
   - :plan/steps - seq of bound steps
   - :plan/runnable? - true iff all steps matched with valid arity (or synthetic)"
  [pickle stepdefs]
  (let [bound-steps (mapv #(bind-step % stepdefs) (:pickle/steps pickle))
        runnable? (every? (fn [bs]
                            (or (= :synthetic (:status bs))  ; macro wrappers
                                (and (= :matched (:status bs))
                                     (:arity-ok? (:binding bs)))))
                          bound-steps)]
    {:plan/id (java.util.UUID/randomUUID)
     :plan/pickle pickle
     :plan/steps bound-steps
     :plan/runnable? runnable?}))

;; -----------------------------------------------------------------------------
;; Suite Binding (All Pickles + Diagnostics)
;; -----------------------------------------------------------------------------

(defn- collect-issues
  "Collect all binding issues from plans for diagnostics.
   Attaches :source-file and :severity to each bound-step for location reporting.
   Binding issues are always :error severity (they block execution)."
  [plans]
  (let [;; Enrich each step with source-file from its plan's pickle
        all-steps (mapcat (fn [plan]
                            (let [source-file (-> plan :plan/pickle :pickle/source-file)]
                              (map #(assoc % :source-file source-file)
                                   (:plan/steps plan))))
                          plans)
        ;; Binding issues always have :error severity (blocking)
        undefined (->> (filter #(= :undefined (:status %)) all-steps)
                       (mapv #(assoc % :severity :error)))
        ambiguous (->> (filter #(= :ambiguous (:status %)) all-steps)
                       (mapv #(assoc % :severity :error)))
        invalid-arity (->> (filter #(and (= :matched (:status %))
                                         (not (:arity-ok? (:binding %))))
                                   all-steps)
                           (mapv #(assoc % :severity :error)))]
    {:undefined undefined
     :ambiguous ambiguous
     :invalid-arity invalid-arity}))

(defn- issue-counts
  "Count issues by type."
  [{:keys [undefined ambiguous invalid-arity svo-issues]}]
  (let [svo-count (count svo-issues)]
    {:undefined-count (count undefined)
     :ambiguous-count (count ambiguous)
     :invalid-arity-count (count invalid-arity)
     :svo-issue-count svo-count
     :total-issues (+ (count undefined)
                      (count ambiguous)
                      (count invalid-arity))}))

;; -----------------------------------------------------------------------------
;; SVO Validation
;; -----------------------------------------------------------------------------

(def ^:private issue-type->config-key
  "Map SVO issue types to their config keys."
  {:svo/unknown-subject :unknown-subject
   :svo/unknown-verb :unknown-verb
   :svo/unknown-interface :unknown-interface})

(defn- compute-severity
  "Compute severity for an SVO issue based on config.
   Returns :error or :warn (default :warn if not configured)."
  [issue svo-config]
  (let [config-key (issue-type->config-key (:type issue))]
    (get svo-config config-key :warn)))

(defn- collect-svo-issues
  "Collect SVO validation issues from all bound steps.

   For each matched step with a non-nil :svoi, validates against glossary
   and interfaces. Returns vector of issues with full location and severity:
   :step-text, :step-id, :uri, :line, :column, :severity."
  [plans glossary interfaces svo-config]
  (when (and glossary interfaces)
    (->> plans
         (mapcat (fn [plan]
                   (let [source-file (-> plan :plan/pickle :pickle/source-file)]
                     (->> (:plan/steps plan)
                          (filter #(= :matched (:status %)))
                          (keep (fn [bound-step]
                                  (when-let [svoi (-> bound-step :binding :svoi)]
                                    (let [result (validate/validate-svoi glossary interfaces svoi)]
                                      (when-not (:valid? result)
                                        ;; Attach full location and severity to each issue
                                        (let [step (:step bound-step)
                                              step-loc (:step/location step)
                                              location {:step-text (:step/text step)
                                                        :step-id (:step/id step)
                                                        :uri source-file
                                                        :line (:line step-loc)
                                                        :column (:column step-loc)}]
                                          (mapv (fn [issue]
                                                  (assoc issue
                                                         :location location
                                                         :severity (compute-severity issue svo-config)))
                                                (:issues result))))))))
                          (apply concat)))))
         vec)))

(defn- svo-issues-blocking?
  "Check if any SVO issues should block execution.

   Returns true if any issue has :severity :error."
  [svo-issues]
  (some #(= :error (:severity %)) svo-issues))

(defn bind-suite
  "Bind all pickles to stepdefs, producing plans and diagnostics.

   Parameters:
   - pickles: Seq of pickles to bind
   - stepdefs: Seq of step definitions
   - opts: Optional map with SVO validation settings:
     - :glossary — loaded glossary for SVO validation
     - :interfaces — interface config map
     - :svo — enforcement config {:unknown-subject :warn|:error ...}

   Returns:
   - :plans - seq of run plans (one per pickle)
   - :runnable? - true iff all plans are runnable and no blocking SVO issues
   - :diagnostics - summary of binding issues

   Diagnostics structure:
   - :undefined - steps with no matching stepdef
   - :ambiguous - steps matching 2+ stepdefs
   - :invalid-arity - matched steps with arity mismatch
   - :svo-issues - SVO validation issues (when opts provided)
   - :counts - {:undefined-count N :ambiguous-count N :svo-issue-count N ...}"
  ([pickles stepdefs]
   (bind-suite pickles stepdefs nil))
  ([pickles stepdefs opts]
   (let [plans (mapv #(bind-pickle % stepdefs) pickles)
         binding-issues (collect-issues plans)
         ;; SVO validation (only if opts provided)
         {:keys [glossary interfaces svo]} opts
         svo-issues (collect-svo-issues plans glossary interfaces svo)
         all-issues (assoc binding-issues :svo-issues (or svo-issues []))
         counts (issue-counts all-issues)
         ;; Runnable if no binding issues AND no blocking SVO issues
         binding-ok? (zero? (:total-issues counts))
         svo-ok? (not (svo-issues-blocking? svo-issues))
         runnable? (and binding-ok? svo-ok?)]
     {:plans plans
      :runnable? runnable?
      :diagnostics (merge all-issues {:counts counts})})))
