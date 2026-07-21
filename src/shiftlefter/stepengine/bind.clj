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

   For matched steps with `:metadata` containing `:svo`, extracts SVO:
   - `:svo` key added to binding with extracted subject/verb/object/interface
   - Legacy steps (no metadata) get `:svo nil`

   ## SVO Validation

   When validation options are provided to `bind-suite`, extracted SVOs are
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
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [clojure.string :as str]
            [shiftlefter.stepengine.annotations :as annotations]
            [shiftlefter.svo.extract :as extract]
            [shiftlefter.svo.glossary :as glossary]
            [shiftlefter.svo.validate :as validate]))

;; -----------------------------------------------------------------------------
;; Specs — Binding & Plan Shapes
;; -----------------------------------------------------------------------------

;; Bound step binding map (for matched steps)
(s/def ::fn (s/with-gen ifn? #(gen/return identity)))
(s/def ::arity nat-int?)
(s/def ::captures vector?)
(s/def ::arity-ok? boolean?)
(s/def ::stepdef-source (s/nilable string?))
(s/def ::svo (s/nilable map?))

(s/def ::slot-kinds (s/coll-of (s/nilable #{:value :matcher :location})
                               :kind vector?))

(s/def ::binding-map
  (s/keys :opt-un [::fn ::arity ::captures ::arity-ok? ::stepdef-source ::svo
                   ::slot-kinds]))

;; Bound step
(s/def ::status #{:matched :undefined :ambiguous :synthetic})
(s/def ::step map?)
(s/def ::binding (s/nilable ::binding-map))
(s/def ::alternatives (s/coll-of map?))

(s/def ::bound-step
  (s/keys :req-un [::status ::step]
          :opt-un [::binding ::alternatives]))

;; Run plan
(s/def :plan/id uuid?)
(s/def :plan/pickle map?)
(s/def :plan/steps (s/coll-of map?))
(s/def :plan/runnable? boolean?)

(s/def ::run-plan
  (s/keys :req [:plan/id :plan/pickle :plan/steps :plan/runnable?]))

;; Diagnostics
(s/def ::undefined (s/coll-of map?))
(s/def ::ambiguous (s/coll-of map?))
(s/def ::invalid-arity (s/coll-of map?))
(s/def ::svo-issues (s/coll-of map?))

(s/def ::undefined-count nat-int?)
(s/def ::ambiguous-count nat-int?)
(s/def ::invalid-arity-count nat-int?)
(s/def ::svo-issue-count nat-int?)
(s/def ::total-issues nat-int?)

(s/def ::diagnostics-counts
  (s/keys :req-un [::undefined-count ::ambiguous-count
                   ::invalid-arity-count ::svo-issue-count
                   ::total-issues]))

(s/def ::counts ::diagnostics-counts)

(s/def ::diagnostics
  (s/keys :req-un [::undefined ::ambiguous ::invalid-arity
                   ::svo-issues ::counts]))

;; bind-suite result
(s/def ::plans (s/coll-of ::run-plan))
(s/def ::runnable? boolean?)

(s/def ::bind-suite-result
  (s/keys :req-un [::plans ::runnable? ::diagnostics]))

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

(defn- stepdef-interface
  "Return the declared :interface of a stepdef, or nil if legacy/unspecified."
  [stepdef]
  (-> stepdef :metadata :interface))

(defn bind-step
  "Bind a pickle step to stepdefs.

   Returns:
   - :status - :matched | :undefined | :ambiguous | :synthetic
   - :step - the original pickle step (with :step/text preserved intact)
   - :binding - for matched: stepdef info + captures + arity validation
   - :alternatives - for ambiguous: all matching stepdefs (summaries)
   - :filter-info - when :step/declared-interface is present, diagnostic info
     about the annotation filter:
       {:declared-interface :sms
        :other-interface-match-count N   ;; only on :undefined
        :other-interfaces [:web ...]     ;; only on :undefined
        :suggestion \"...\"}             ;; only when :other-interfaces present (sl-563)

   Synthetic steps (macro wrappers with :step/synthetic? true) are auto-bound
   with :status :synthetic and no binding - they exist for events/reporting only.

   If the step has :step/declared-interface (from the annotation pass), stepdef
   candidates are narrowed to those whose metadata :interface matches. The
   annotation prefix is stripped from step text only at the moment of regex
   matching; the step map itself retains :step/text with annotation intact so
   reporters and events can surface it."
  [pickle-step stepdefs]
  ;; Synthetic steps (macro wrappers) don't need binding
  (if (:step/synthetic? pickle-step)
    {:status :synthetic
     :step pickle-step
     :binding nil
     :alternatives []}
    ;; Regular steps need stepdef matching
    (let [raw-text (:step/text pickle-step)
          declared (:step/declared-interface pickle-step)
          ;; Strip annotation for regex matching when declared
          match-text (if declared
                       (annotations/strip-annotation raw-text)
                       raw-text)
          ;; Narrow candidates when an interface is declared.
          ;; Stepdefs without :interface metadata are excluded from annotated
          ;; binding — the annotation is an assertion, and an interface-less
          ;; stepdef has no claim to any particular interface.
          candidates (if declared
                       (filter #(= declared (stepdef-interface %)) stepdefs)
                       stepdefs)
          matches (find-all-matches match-text candidates)
          ;; Level-2 diagnostic: when annotated and undefined, surface
          ;; matches under other interfaces (they were filtered out).
          ;; sl-563: include an explicit human-readable :suggestion when
          ;; other interfaces had matches.
          filter-info
          (when declared
            (let [others-matching
                  (when (zero? (count matches))
                    (find-all-matches
                     match-text
                     (remove #(= declared (stepdef-interface %)) stepdefs)))
                  other-ifaces (vec (distinct
                                     (keep #(stepdef-interface (:stepdef %))
                                           others-matching)))]
              (cond-> {:declared-interface declared}
                (seq others-matching)
                (assoc :other-interface-match-count (count others-matching)
                       :other-interfaces other-ifaces
                       :suggestion
                       (let [verb (if (= 1 (count other-ifaces)) "does" "do")
                             listed (str/join " "
                                              (map #(str "[" % "]") other-ifaces))]
                         (str "No [" declared "] stepdef matches, but "
                              listed " " verb "."))))))]
      (case (count matches)
        ;; No matches - undefined
        0 (cond-> {:status :undefined
                   :step pickle-step
                   :binding nil
                   :alternatives []}
            filter-info (assoc :filter-info filter-info))

        ;; Single match - check arity and extract SVO
        1 (let [{:keys [stepdef captures]} (first matches)
                arity-info (validate-arity (count captures) (:arity stepdef))
                metadata (:metadata stepdef)
                svo (extract/extract-svo metadata captures)]
            (cond-> {:status :matched
                     :step pickle-step
                     :binding (merge (stepdef-summary stepdef)
                                     {:captures captures
                                      :fn (:fn stepdef)
                                      :arity (:arity stepdef)
                                      :metadata metadata
                                      :svo svo}
                                     arity-info)
                     :alternatives []}
              filter-info (assoc :filter-info filter-info)))

        ;; Multiple matches - ambiguous
        (let [summaries (mapv #(stepdef-summary (:stepdef %)) matches)]
          (cond-> {:status :ambiguous
                   :step pickle-step
                   :binding nil
                   :alternatives summaries}
            filter-info (assoc :filter-info filter-info)))))))

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
  "Count issues by type. :total-issues includes every issue family —
   per-family counts must sum to it (sl-89ii); a machine consumer gating
   on :total-issues must never read 0 when any family is nonzero. Blocking
   is judged separately (see bind-suite): warn-level SVO issues count here
   without blocking."
  [{:keys [undefined ambiguous invalid-arity svo-issues]}]
  {:undefined-count     (count undefined)
   :ambiguous-count     (count ambiguous)
   :invalid-arity-count (count invalid-arity)
   :svo-issue-count     (count svo-issues)
   :total-issues        (+ (count undefined)
                           (count ambiguous)
                           (count invalid-arity)
                           (count svo-issues))})

;; -----------------------------------------------------------------------------
;; SVO Validation
;; -----------------------------------------------------------------------------

(def ^:private issue-type->config-key
  "Map SVO issue types to their config keys."
  {:svo/missing-subject :unknown-subject
   :svo/unknown-subject :unknown-subject
   :svo/unknown-verb :unknown-verb
   :svo/unknown-interface :unknown-interface
   :svo/unknown-object :unknown-object
   :svo/raw-locator-disallowed :unknown-object})

(defn- compute-severity
  "Compute severity for an SVO issue based on config.
   Returns :error or :warn (default :warn if not configured).

   For object enforcement, maps :strict to :error."
  [issue svo-config]
  (let [config-key (issue-type->config-key (:type issue))
        level (get svo-config config-key :warn)]
    ;; Map :strict to :error for object enforcement
    (if (= level :strict) :error level)))

(defn- collect-svo-issues
  "Collect SVO validation issues from all bound steps.

   For each matched step with a non-nil :svo, validates against glossary
   and interfaces. Returns vector of issues with full location and severity:
   :step-text, :step-id, :uri, :line, :column, :severity."
  [plans glossary interfaces svo-config]
  (when (and glossary interfaces)
    (let [;; Extract object enforcement for validate-svo
          validate-opts {:unknown-object (:unknown-object svo-config :off)}]
      (->> plans
           (mapcat (fn [plan]
                     (let [source-file (-> plan :plan/pickle :pickle/source-file)]
                       (->> (:plan/steps plan)
                            (filter #(= :matched (:status %)))
                            (keep (fn [bound-step]
                                    (when-let [svo (-> bound-step :binding :svo)]
                                      (let [result (validate/validate-svo glossary interfaces svo validate-opts)]
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
           vec))))

(defn- svo-issues-blocking?
  "Check if any SVO issues should block execution.

   Returns true if any issue has :severity :error."
  [svo-issues]
  (some #(= :error (:severity %)) svo-issues))

(defn- stamp-costumes
  "Stamp each bound step's SVO with the costume its subject `:wears` (sl-rnm).

   Resolves the costume here — at bind time, the one place the glossary is in
   scope — so the runtime provisioning path can read `(:wears svo)` without
   the glossary being threaded to it. No-op in vanilla mode (no glossary) or
   when a subject wears nothing."
  [plans glossary]
  (if-not glossary
    plans
    (letfn [(stamp-step [bound-step]
              (if-let [subject (-> bound-step :binding :svo :subject)]
                (if-let [costume (glossary/costume-for-subject glossary subject)]
                  (assoc-in bound-step [:binding :svo :wears] costume)
                  bound-step)
                bound-step))]
      (mapv #(update % :plan/steps (partial mapv stamp-step)) plans))))

(defn frame-slot-kinds
  "Vector aligned with a bound step's captures: the slot kind governing
   each capture position, nil for plain captures (sl-yh7).

   Kinds come from the verb glossary frame: :arg-kinds maps frame arg
   names to #{:value :matcher}; an :object-kind :location O slot marks
   its capture :location. The stepdef's :svo metadata (:$N placeholders)
   maps frame args back to capture positions. Returns nil when the frame
   can't be resolved — plain-capture behavior, no token admission."
  [metadata glossary interfaces capture-count]
  (let [{:keys [interface svo]} metadata
        {:keys [verb frame object args]} svo
        iface-type (or (get-in interfaces [interface :type]) interface)
        frame-map (get-in glossary [:verbs iface-type verb :frames frame])]
    (when frame-map
      (let [base (vec (repeat capture-count nil))
            in-range? (fn [idx] (and (some? idx) (<= 0 idx) (< idx capture-count)))
            with-args (reduce-kv
                       (fn [acc arg-name capture-ref]
                         (let [kind (get-in frame-map [:arg-kinds arg-name])
                               idx (when (and kind (extract/placeholder? capture-ref))
                                     (extract/placeholder-index capture-ref))]
                           (if (in-range? idx) (assoc acc idx kind) acc)))
                       base (or args {}))
            obj-idx (when (and (= :location (:object-kind frame-map))
                               (extract/placeholder? object))
                      (extract/placeholder-index object))]
        (if (in-range? obj-idx)
          (assoc with-args obj-idx :location)
          with-args)))))

(def ^:private default-stamp-glossary
  "Framework default glossaries, for slot-kind stamping in vanilla mode.
   Builtin value slots capture WITH their quote delimiters (sl-yh7), so
   the exec normalization that strips them must run in EVERY mode — the
   builtin frames' :arg-kinds are compiled-in truth, not an SVO opt-in."
  (delay (glossary/load-default-glossaries)))

(defn default-slot-kinds
  "Slot kinds for a stepdef's metadata against the FRAMEWORK DEFAULT
   glossaries — the same kinds vanilla-mode stamping produces. For test
   harnesses that invoke stepdef fns directly and must replicate the
   engine's capture normalization (bindings/normalize-captures)."
  [metadata capture-count]
  (frame-slot-kinds metadata @default-stamp-glossary nil capture-count))

(defn- stamp-slot-kinds
  "Stamp each matched bound step's binding with :slot-kinds (sl-yh7) —
   at bind time, the one place the glossary is in scope — so the exec
   path can normalize value/location captures (strip quotes, resolve
   {binding} tokens) without the glossary being threaded to it. Vanilla
   mode (no project glossary) stamps from the framework defaults. No-op
   for steps without a resolvable frame. Plan-side only; never enters
   the pickle."
  [plans glossary interfaces]
  (let [glossary (or glossary @default-stamp-glossary)]
    (letfn [(stamp-step [bound-step]
              (let [metadata (-> bound-step :binding :metadata)
                    captures (-> bound-step :binding :captures)]
                (if-let [kinds (and (= :matched (:status bound-step))
                                    (:svo metadata)
                                    (frame-slot-kinds metadata glossary interfaces
                                                      (count captures)))]
                  (assoc-in bound-step [:binding :slot-kinds] kinds)
                  bound-step)))]
      (mapv #(update % :plan/steps (partial mapv stamp-step)) plans))))

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
   (let [{:keys [glossary interfaces svo]} opts
         ;; Bind, then stamp each SVO with the costume its subject :wears
         ;; (sl-rnm) — the runtime provisioning path reads (:wears svo) —
         ;; and each binding with its :slot-kinds (sl-yh7) — the exec path
         ;; reads them to normalize value/location captures.
         plans (-> (mapv #(bind-pickle % stepdefs) pickles)
                   (stamp-costumes glossary)
                   (stamp-slot-kinds glossary interfaces))
         binding-issues (collect-issues plans)
         ;; SVO validation (only if opts provided)
         svo-issues (collect-svo-issues plans glossary interfaces svo)
         all-issues (assoc binding-issues :svo-issues (or svo-issues []))
         counts (issue-counts all-issues)
         ;; Runnable if no binding issues AND no blocking SVO issues.
         ;; sl-unz: stepdef-level capability gating moved to suite-load
         ;; (shiftlefter.stepengine.suite-lint), called from compile-suite
         ;; before bind-suite — so reaching this point implies the suite
         ;; already passed those static checks.
         ;; Gate on binding issues only — :total-issues now also counts SVO
         ;; issues (sl-89ii), and warn-level SVO issues must not block.
         binding-ok? (zero? (+ (:undefined-count counts)
                               (:ambiguous-count counts)
                               (:invalid-arity-count counts)))
         svo-ok? (not (svo-issues-blocking? svo-issues))
         runnable? (and binding-ok? svo-ok?)]
     {:plans plans
      :runnable? runnable?
      :diagnostics (merge all-issues {:counts counts})})))
