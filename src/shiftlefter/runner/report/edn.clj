(ns shiftlefter.runner.report.edn
  "EDN reporter for ShiftLefter runner.

   Outputs machine-readable EDN to **stdout** only.
   Human-readable console output goes to stderr via the console reporter.

   ## Summary Shape

   ```clojure
   {:run/id \"uuid-string\"
    :run/exit-code 0|1|2|3
    :run/status :passed|:failed|:planning-failed|:crashed
    :counts {:passed N :failed N :pending N :skipped N
             :error N          ;; only when positive (hook threw, sl-esq)
             :scenarios N :steps N}
    :planning {...}     ;; present when exit-code 2 (may carry :config-lints)
    :failures [{...}]   ;; present when exit-code 1
    :diagnostics {:svo-issues [...]     ;; warn-level, non-blocking (sl-qk8l)
                  :config-lints [...]}  ;; sl-lnj1 — unknown/misplaced
                                        ;; top-level config keys; shape is
                                        ;; runner.config ::lint-warnings,
                                        ;; scrubbed. Absent on clean runs.
    :error {...}}       ;; present when exit-code 3
   ```

   ## Usage

   ```clojure
   (prn-summary {:run/id \"abc\" :run/exit-code 0 :counts {...}})
   ;; Prints EDN map to stdout
   ```"
  (:require [shiftlefter.runner.reporter :as reporter]))

;; -----------------------------------------------------------------------------
;; Error Serialization
;; -----------------------------------------------------------------------------

(defn- serialize-error
  "Project an error map onto the EDN output shape.

   This is a KEY PROJECTION, not an identity: planning and crash errors reach
   it un-enveloped and carry extra keys that must not leak into `--edn`.

   `:value` is passed through verbatim — `reporter/error-envelope` already
   `pr-str`'d it exactly once at the envelope seam (sl-21z R2). Re-encoding
   here would double-quote it."
  [error]
  (when error
    (cond-> {:type (:type error)
             :message (:message error)}
      (:exception-class error) (assoc :exception-class (:exception-class error))
      (:data error) (assoc :data (:data error))
      (:value error) (assoc :value (:value error))
      ;; Hook failure attribution (sl-esq): hook name + registration home
      ;; + the @hook= tag's file:line.
      (:hook error) (assoc :hook (:hook error))
      (:registration error) (assoc :registration (:registration error))
      (:tag-source error) (assoc :tag-source (:tag-source error)))))

;; -----------------------------------------------------------------------------
;; Failure Extraction
;; -----------------------------------------------------------------------------

(defn- extract-macro-provenance
  "Extract macro provenance for EDN output.
   Returns nil if step has no macro metadata."
  [step source-file]
  (when-let [macro (:step/macro step)]
    (let [role (:role macro)
          call-site (:call-site macro)
          def-step (:definition-step macro)]
      (cond-> {:key (:key macro)}
        ;; Call-site with assembled file path
        call-site
        (assoc :call-site {:file source-file
                           :line (:line call-site)
                           :column (:column call-site)})
        ;; Definition-step (expanded steps only)
        (and (= :expanded role) def-step)
        (assoc :definition-step def-step)))))

(defn- extract-step-failure
  "Extract failure info from a failed step result."
  [step-result scenario-result]
  (let [step (:step step-result)
        binding (:binding step-result)
        pickle (-> scenario-result :plan :plan/pickle)
        source-file (:pickle/source-file pickle)
        macro-info (extract-macro-provenance step source-file)]
    (cond-> {:step/text (:step/text step)
             :step/id (:step/id step)
             :scenario/name (:pickle/name pickle)
             :scenario/id (:pickle/id pickle)
             :location (:source binding)
             :error (serialize-error (:error step-result))}
      macro-info (assoc :macro macro-info))))

(defn- extract-scenario-error
  "Extract a scenario-level hook failure (sl-esq) — the scenario carries
   :error directly; no step bears it (steps are :skipped or carry their
   own step-level failures)."
  [scenario]
  (let [pickle (-> scenario :plan :plan/pickle)]
    {:scenario/name (:pickle/name pickle)
     :scenario/id (:pickle/id pickle)
     :error (serialize-error (:error scenario))}))

(defn- extract-failures
  "Extract all failures from scenario results. :failed scenarios contribute
   their failed steps; :error scenarios (hook threw, sl-esq) contribute a
   scenario-level entry plus any failed steps (the after-throw case)."
  [scenarios]
  (concat
   (for [scenario scenarios
         :when (= :error (:status scenario))]
     (extract-scenario-error scenario))
   (for [scenario scenarios
         :when (#{:failed :error} (:status scenario))
         step (:steps scenario)
         :when (= :failed (:status step))]
     (extract-step-failure step scenario))))

;; -----------------------------------------------------------------------------
;; Planning Diagnostics
;; -----------------------------------------------------------------------------

(defn- extract-planning-issue
  "Extract a planning issue (undefined/ambiguous/invalid-arity) for EDN.
   Includes full location: :uri, :line, :column from enriched bound-step."
  [bound-step issue-type]
  (let [step (:step bound-step)
        step-loc (:step/location step)]
    (cond-> {:type issue-type
             :step/text (:step/text step)
             :step/id (:step/id step)
             :uri (:source-file bound-step)
             :line (:line step-loc)
             :column (:column step-loc)}
      (= :ambiguous issue-type)
      (assoc :alternatives (mapv #(select-keys % [:stepdef/id :pattern-src :source])
                                 (:alternatives bound-step)))

      (= :invalid-arity issue-type)
      (assoc :arity {:expected (:expected (:binding bound-step))
                     :actual (:actual (:binding bound-step))}))))

(def ^:private wrapper-error-types
  "Error types under :diagnostics :errors whose payload is already emitted
   under a dedicated key (:stepdef-issues / :suite-lint-issues) — excluded
   from :errors to avoid duplication."
  #{:stepdef/glossary-mismatch :suite-lint/failed})

(defn- extract-planning-diagnostics
  "Extract planning diagnostics for EDN output (exit code 2).
   Includes binding issues, SVO issues, suite-load lint issues, stepdef
   glossary issues, annotation/macro errors, and generic planning errors."
  [diagnostics]
  (when diagnostics
    (let [{:keys [undefined ambiguous invalid-arity svo-issues
                  suite-lint-issues stepdef-issues annotation-errors
                  macro-errors errors counts]} diagnostics
          binding-issues (concat
                          (map #(extract-planning-issue % :undefined) undefined)
                          (map #(extract-planning-issue % :ambiguous) ambiguous)
                          (map #(extract-planning-issue % :invalid-arity) invalid-arity))
          other-errors (->> errors
                            (remove #(wrapper-error-types (:type %)))
                            (mapv serialize-error))]
      (cond-> {:issues (vec binding-issues)
               :counts counts}
        (seq svo-issues)        (assoc :svo-issues svo-issues)
        (seq suite-lint-issues) (assoc :suite-lint-issues suite-lint-issues)
        (seq stepdef-issues)    (assoc :stepdef-issues (vec stepdef-issues))
        (seq annotation-errors) (assoc :annotation-errors (vec annotation-errors))
        (seq macro-errors)      (assoc :macro-errors (vec macro-errors))
        (seq other-errors)      (assoc :errors other-errors)
        ;; sl-lnj1: config-lint warnings ride the planning block too — a run
        ;; that fails planning still had a lintable config.
        (seq (:config-lints diagnostics))
        (assoc :config-lints (vec (:config-lints diagnostics)))))))

(defn execution-diagnostics
  "Extract diagnostics for EDN output on non-blocked runs (exit code 0/1
   and dry-run success): SVO issues and config-lint warnings (sl-lnj1) —
   the warn-level signals that didn't block execution. Public so runner
   core can attach diagnostics to the dry-run summaries it builds inline
   (sl-qk8l).
   Returns nil if no diagnostics to report (clean-run summaries stay
   byte-identical)."
  [diagnostics]
  (when diagnostics
    (let [{:keys [svo-issues config-lints counts]} diagnostics]
      (not-empty
       (cond-> {}
         (seq svo-issues)   (assoc :svo-issues svo-issues
                                   :counts (select-keys counts [:svo-issue-count]))
         (seq config-lints) (assoc :config-lints (vec config-lints)))))))

;; -----------------------------------------------------------------------------
;; Summary Building
;; -----------------------------------------------------------------------------

(defn build-summary
  "Build a complete EDN summary map from run results.

   Parameters:
   - run-id: UUID string for this run
   - exit-code: 0, 1, 2, or 3
   - result: execution result from execute-suite (or nil for exit 2/3)
   - opts: {:diagnostics ... :error ...}

   Returns EDN-safe map suitable for prn."
  [run-id exit-code result opts]
  (let [base {:run/id run-id
              :run/exit-code exit-code
              :run/status (case exit-code
                            0 :passed
                            1 :failed
                            2 :planning-failed
                            3 :crashed)}]
    (case exit-code
      ;; Success or failure - include counts, failures, and diagnostics if present
      (0 1) (let [scenarios (:scenarios result)
                  step-count (reduce + (map #(count (:steps %)) scenarios))
                  exec-diagnostics (execution-diagnostics (:diagnostics opts))]
              (cond-> (assoc base
                             :counts (assoc (:counts result)
                                            :scenarios (count scenarios)
                                            :steps step-count))
                (= 1 exit-code)
                (assoc :failures (vec (extract-failures scenarios)))

                ;; Include :diagnostics only if non-empty (no noise on clean runs)
                exec-diagnostics
                (assoc :diagnostics exec-diagnostics)))

      ;; Planning failure - include diagnostics
      2 (assoc base
               :planning (extract-planning-diagnostics (:diagnostics opts)))

      ;; Crash - include error
      3 (assoc base
               :error (serialize-error (:error opts))))))

;; -----------------------------------------------------------------------------
;; Output
;; -----------------------------------------------------------------------------

(defn prn-summary
  "Print EDN summary to stdout.
   This is the only output that should go to stdout."
  [summary]
  (prn summary))

(defn print-edn-summary!
  "Build and print EDN summary to stdout.

   Convenience function that combines build-summary and prn-summary."
  [run-id exit-code result opts]
  (prn-summary (build-summary run-id exit-code result opts)))

;; -----------------------------------------------------------------------------
;; Reporter (sl-21z)
;; -----------------------------------------------------------------------------

(defrecord EdnReporter [state]
  reporter/Reporter
  (on-run-start [_this _run-ctx] nil)

  (on-scenario-complete [_this scenario-result]
    ;; Accumulating: the EDN summary is a single map `prn`'d at run end, so
    ;; scenarios are collected in plan order and rendered together.
    (swap! state update :scenarios conj scenario-result)
    nil)

  (on-diagnostics [_this diagnostics]
    (swap! state assoc :diagnostics diagnostics)
    nil)

  (on-run-end [_this run-summary]
    ;; `build-summary` reads only :scenarios and :counts off the result map,
    ;; so the accumulated envelopes stand in for the raw exec-result.
    (prn-summary
     (build-summary (:run-id run-summary)
                    (:exit-code run-summary)
                    {:scenarios (:scenarios @state)
                     :counts (:counts run-summary)}
                    {:diagnostics (:diagnostics @state)}))
    nil))

(defn make-reporter
  "Construct an EdnReporter."
  []
  (->EdnReporter (atom {:scenarios [] :diagnostics nil})))
