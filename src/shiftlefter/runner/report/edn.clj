(ns shiftlefter.runner.report.edn
  "EDN reporter for ShiftLefter runner.

   Outputs machine-readable EDN to **stdout** only.
   Human-readable console output goes to stderr via the console reporter.

   ## Summary Shape

   ```clojure
   {:run/id \"uuid-string\"
    :run/seed 1234567     ;; the run's seed (sl-08f7) — positive 64-bit long,
                          ;; CORE-RECORD family: always present on runner-
                          ;; produced summaries (identical in every group
                          ;; summary of one invocation); absence only ever
                          ;; means an old record
    :run/exit-code 0|1|2|3|4
    :run/status :passed|:failed|:planning-failed|:crashed|:degraded
    :counts {:passed N :failed N :pending N :skipped N
             :error N          ;; only when positive — infrastructure-
                               ;; failure family (sl-esq, sl-7c7i)
             :scenarios N :steps N}
                        ;; counts ride EVERY summary (sl-7c7i AC):
                        ;; all-zero on exit 2/3, where nothing executed
    :dropped-events N   ;; observe-plane events the bus refused at admission
                        ;; during THIS run-scope (sl-d7ks); absent when zero.
                        ;; Results are never affected — the report plane
                        ;; doesn't ride the bus; observations may be gapped.
    :planning {...}     ;; present when exit-code 2 (may carry :config-lints)
    :failures [{...}]   ;; present when exit-code 1 or 4; entries may carry
                        ;; :attachments — the failing step's/scenario's own
                        ;; refs, inline for join-free triage
    :attachments [{:scenario/name \"..\" :scenario/id #uuid \"..\"
                   :attachments [ref ...]     ;; scenario-level claims
                   :steps [{:step/id #uuid \"..\" :step/text \"..\"
                            :attachments [ref ...]}]}]
                        ;; sl-oxiz — the AUTHORITATIVE complete evidence
                        ;; walk (the surface sl-e01m documents): one entry
                        ;; per scenario carrying any attachment refs,
                        ;; regardless of status — green-run evidence
                        ;; (:every-step policy, After-hook claims) appears
                        ;; ONLY here. A failed step's refs appear BOTH here
                        ;; and inline on its :failures entry — deliberate
                        ;; duplication, same refs verbatim. Refs are
                        ;; :attachment/* maps (:attachment/kind +
                        ;; :attachment/path required; path group-root-
                        ;; relative when portable — see shiftlefter.
                        ;; attachments for the portability contract).
                        ;; Absent when the run produced no refs.
    :diagnostics {:svo-issues [...]     ;; warn-level, non-blocking (sl-qk8l)
                  :config-lints [...]   ;; sl-lnj1 — unknown/misplaced
                                        ;; top-level config keys; shape is
                                        ;; runner.config ::lint-warnings,
                                        ;; scrubbed. Absent on clean runs.
                  :capture-lints [...]} ;; sl-3q9r — capture warns (stderr
                                        ;; suppressed under --edn; they ride
                                        ;; here instead, once per run): maps
                                        ;; of :type :capture/unknown-kind |
                                        ;; :capture/invalid-policy-value |
                                        ;; :capture/no-results-dir |
                                        ;; :capture/adapter-unsupported
                                        ;; (sl-sehc — plan-time, per group:
                                        ;; the kind exists in the registry
                                        ;; but no adapter this group uses
                                        ;; supports it; carries :adapters,
                                        ;; :group on the setup path, and
                                        ;; appears in dry-run summaries
                                        ;; too), plus :message (and :kind
                                        ;; where one applies). A machine
                                        ;; consumer reads THIS key to learn
                                        ;; promised capture evidence is not
                                        ;; coming. Absent on clean runs.
    :error {...}}       ;; present when exit-code 3
   ```

   ## The form stream (the consumer contract, sl-yvaz)

   stdout carries a SEQUENCE of EDN forms, not always exactly one:

   - classic pipeline: one summary form.
   - setup.clj orchestration: one summary form PER GROUP, in group order.
   - supplementary diagnostics forms may appear BETWEEN or BEFORE
     summaries: `{:run/id .. :group? .. :diagnostics {..}}` — producers are
     teardown surfacing (a group's :stop threw → emitted after that group's
     summary; a PRIOR run's dirty-teardown marker → emitted at startup,
     entries carrying the prior run's id) and plan-shape lint warnings
     (sl-lkla: `{:diagnostics {:setup-lints [..]}}` emitted before any
     group summary — warn-level, never exit-flipping). These forms have no
     :counts and no :run/exit-code.

   Consumers must read ALL forms and SKIP shapes they don't recognize —
   the form vocabulary grows by release; unknown forms are never an error.
   (Summary :diagnostics may also carry :cleanup-failures, sl-lbrc;
   supplementary forms carry :teardown-failures.)

   ## Usage

   ```clojure
   (prn-summary {:run/id \"abc\" :run/exit-code 0 :counts {...}})
   ;; Prints EDN map to stdout
   ```"
  (:require [shiftlefter.runner.reporter :as reporter]
            [shiftlefter.runner.verdict :as verdict]))

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
      (:tag-source error) (assoc :tag-source (:tag-source error))
      ;; File attribution for parse/step-load sub-errors (sl-0n6t).
      (:path error) (assoc :path (:path error))
      ;; Group attribution for setup :start failures (sl-1h03) — which
      ;; group's lifecycle failed, same additive lane as :path above.
      (:group error) (assoc :group (:group error))
      ;; Aggregate planning errors (intents/parse/step-load, sl-0n6t) carry
      ;; their per-file/per-entry detail as nested :errors — recurse with the
      ;; same projection so the detail survives without leaking extra keys.
      ;; The projection also SHIELDS the EDN here: parse sub-errors carry
      ;; Location/Token records that would print as unreadable tagged
      ;; literals if passed through whole.
      (:errors error) (assoc :errors (mapv serialize-error (:errors error))))))

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
      macro-info (assoc :macro macro-info)
      ;; Attachment refs (sl-oxiz) — evidence rides with the failure it
      ;; explains, join-free; the same refs also appear in the summary's
      ;; top-level :attachments index (the complete evidence walk).
      (seq (:attachments step-result))
      (assoc :attachments (vec (:attachments step-result))))))

(defn- extract-scenario-error
  "Extract a scenario-level hook failure (sl-esq) — the scenario carries
   :error directly; no step bears it (steps are :skipped or carry their
   own step-level failures)."
  [scenario]
  (let [pickle (-> scenario :plan :plan/pickle)]
    (cond-> {:scenario/name (:pickle/name pickle)
             :scenario/id (:pickle/id pickle)
             :error (serialize-error (:error scenario))}
      ;; Attachment refs (sl-oxiz) — scenario-level claims (After-hook /
      ;; capture policy) ride the entry; also indexed under the summary's
      ;; top-level :attachments.
      (seq (:attachments scenario))
      (assoc :attachments (vec (:attachments scenario))))))

(defn- extract-failures
  "Extract all failures from scenario results. :failed scenarios contribute
   their failed steps; :error scenarios (infrastructure-failure family,
   sl-esq/sl-7c7i) contribute a scenario-level entry plus any failed steps
   (the after-throw case, and the provisioning flip where the failing step
   stays :failed)."
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
;; Attachment Index
;; -----------------------------------------------------------------------------

(defn- extract-step-attachments
  "Step sub-entries for the attachment index: only steps carrying refs.
   Envelope steps hold :step (:step/id, :step/text) and seq-guarded
   :attachments (reporter.clj scenario-envelope)."
  [scenario]
  (into []
        (keep (fn [step-result]
                (when (seq (:attachments step-result))
                  {:step/id (-> step-result :step :step/id)
                   :step/text (-> step-result :step :step/text)
                   :attachments (vec (:attachments step-result))})))
        (:steps scenario)))

(defn- extract-attachments
  "The summary's top-level attachment index (sl-oxiz): one entry per
   scenario carrying any refs — scenario-level claims and/or per-step
   captures — REGARDLESS of status. This is the complete evidence walk:
   green scenarios (:every-step policy, After-hook claims) never reach
   :failures, so this index is where a machine consumer discovers their
   evidence. Empty when no scenario carries a ref (the caller seq-guards
   the key away)."
  [scenarios]
  (into []
        (keep (fn [scenario]
                (let [step-entries (extract-step-attachments scenario)
                      pickle (-> scenario :plan :plan/pickle)]
                  (when (or (seq (:attachments scenario)) (seq step-entries))
                    (cond-> {:scenario/name (:pickle/name pickle)
                             :scenario/id (:pickle/id pickle)}
                      (seq (:attachments scenario))
                      (assoc :attachments (vec (:attachments scenario)))
                      (seq step-entries)
                      (assoc :steps step-entries))))))
        scenarios))

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
      ;; Grammar hint + interface filter info on undefined steps (sl-s04n /
      ;; sl-563) — full machine-readable maps, additive to the EDN contract.
      (:hint bound-step)
      (assoc :hint (:hint bound-step))

      (:filter-info bound-step)
      (assoc :filter-info (:filter-info bound-step))

      (= :ambiguous issue-type)
      (assoc :alternatives (mapv #(select-keys % [:stepdef/id :pattern-src :source])
                                 (:alternatives bound-step)))

      (= :invalid-arity issue-type)
      (assoc :arity {:expected (:expected (:binding bound-step))
                     :actual (:actual (:binding bound-step))})

      ;; sl-s2oa: unresolved execution instance — full machine-readable map
      ;; {:type :bind/ambiguous-instance :interface-type T :candidates [...]}
      (= :ambiguous-instance issue-type)
      (assoc :instance-resolution (:instance-resolution bound-step)))))

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
    (let [{:keys [undefined ambiguous invalid-arity ambiguous-instance
                  svo-issues suite-lint-issues stepdef-issues annotation-errors
                  macro-errors errors counts]} diagnostics
          binding-issues (concat
                          (map #(extract-planning-issue % :undefined) undefined)
                          (map #(extract-planning-issue % :ambiguous) ambiguous)
                          (map #(extract-planning-issue % :invalid-arity) invalid-arity)
                          (map #(extract-planning-issue % :ambiguous-instance) ambiguous-instance))
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
    (let [{:keys [svo-issues config-lints cleanup-failures capture-lints
                  counts]} diagnostics]
      (not-empty
       (cond-> {}
         (seq svo-issues)   (assoc :svo-issues svo-issues
                                   :counts (select-keys counts [:svo-issue-count]))
         (seq config-lints) (assoc :config-lints (vec config-lints))
         ;; Capability-cleanup failures (sl-lbrc): a leaked driver on an
         ;; otherwise-green run — warn-level truth, never the exit code.
         (seq cleanup-failures) (assoc :cleanup-failures (vec cleanup-failures))
         ;; Capture warns (sl-3q9r): unknown kinds / invalid policy values
         ;; / no-results-dir — stderr is suppressed under --edn, so this is
         ;; where a machine consumer learns its evidence was never coming.
         (seq capture-lints) (assoc :capture-lints (vec capture-lints)))))))

;; -----------------------------------------------------------------------------
;; Summary Building
;; -----------------------------------------------------------------------------

(def ^:private zero-counts
  "Counts for summaries where nothing executed (exit 2) or no completed
   verdict exists (exit 3) — counts ride EVERY summary (sl-7c7i AC)."
  {:passed 0 :failed 0 :pending 0 :skipped 0 :scenarios 0 :steps 0})

(defn build-summary
  "Build a complete EDN summary map from run results.

   Parameters:
   - run-id: UUID string for this run
   - exit-code: 0, 1, 2, 3, or 4 (shiftlefter.runner.verdict, sl-7c7i)
   - result: execution result from execute-suite (or nil for exit 2/3)
   - opts: {:diagnostics ... :error ... :dropped-events ... :seed ...}
     :seed (sl-08f7) stamps :run/seed — the runner path always passes it
     (core-record family); the guard only spares direct callers.

   Returns EDN-safe map suitable for prn."
  [run-id exit-code result opts]
  (let [base (cond-> {:run/id run-id
                      :run/exit-code exit-code
                      :run/status (verdict/exit-code->status exit-code)}
               (:seed opts) (assoc :run/seed (:seed opts)))]
    (case exit-code
      ;; Executed (pass, fail, or degraded) — include counts, failures,
      ;; and diagnostics if present. Exit 4 (degraded, sl-7c7i) carries
      ;; :failures like exit 1: the :error scenarios' attributions live
      ;; there.
      (0 1 4) (let [scenarios (:scenarios result)
                    step-count (reduce + (map #(count (:steps %)) scenarios))
                    exec-diagnostics (execution-diagnostics (:diagnostics opts))
                    attachment-index (extract-attachments scenarios)]
                (cond-> (assoc base
                               :counts (assoc (:counts result)
                                              :scenarios (count scenarios)
                                              :steps step-count))
                  (pos? exit-code)
                  (assoc :failures (vec (extract-failures scenarios)))

                  ;; Attachment index (sl-oxiz): absent when no scenario
                  ;; carries a ref — attachment-less summaries stay
                  ;; byte-identical (default capture policy is :never).
                  (seq attachment-index)
                  (assoc :attachments attachment-index)

                  ;; Include :diagnostics only if non-empty (no noise on clean runs)
                  exec-diagnostics
                  (assoc :diagnostics exec-diagnostics)

                  ;; Observe-plane drop count for this run-scope (sl-d7ks):
                  ;; absent when zero (anomaly-marker convention — golden
                  ;; safety). Executed summaries only; exits 2/3 publish
                  ;; nothing / keep bus-close!'s warn.
                  (:dropped-events opts)
                  (assoc :dropped-events (:dropped-events opts))))

      ;; Planning failure - include diagnostics. Counts ride EVERY summary
      ;; (sl-7c7i AC): nothing executed, so all zeros — a consumer can read
      ;; :counts off any summary without branching on the exit code. The
      ;; empty-selection record's searched/filter facts ride its planning
      ;; error's :data.
      2 (assoc base
               :counts zero-counts
               :planning (extract-planning-diagnostics (:diagnostics opts)))

      ;; Crash - include error. Zero counts (sl-7c7i): no completed verdict
      ;; exists; per-scenario truth for a mid-run crash is unknowable here.
      3 (assoc base
               :counts zero-counts
               :error (serialize-error (:error opts))))))

;; -----------------------------------------------------------------------------
;; Output
;; -----------------------------------------------------------------------------

(defn prn-summary
  "Print EDN summary to stdout.
   This is the only output that should go to stdout.

   Serialized via `reporter/pr-edn-str`, never bare `prn`: ambient printer
   bindings (clojure.main binds *print-namespace-maps* true on every CLI
   run) can make the output unreadable or truncated (sl-3ub4)."
  [summary]
  (println (reporter/pr-edn-str summary)))

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
                    {:diagnostics (:diagnostics @state)
                     ;; sl-08f7: the invocation seed rides the :run-end
                     ;; payload — core-record family, always present.
                     :seed (:seed run-summary)
                     ;; nil when absent — build-summary's cond-> skips it,
                     ;; so absent-when-zero survives the threading (sl-d7ks).
                     :dropped-events (:dropped-events run-summary)}))
    nil))

(defn make-reporter
  "Construct an EdnReporter."
  []
  (->EdnReporter (atom {:scenarios [] :diagnostics nil})))
