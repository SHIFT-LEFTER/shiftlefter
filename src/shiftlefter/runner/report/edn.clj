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
    :run/results-path \"results/<stamp>[/<group-slug>]\"
                          ;; this run-scope's results dir (sl-7dq5 F11b),
                          ;; config-root-relative, forward slashes — the
                          ;; pointer from envelope to artifacts; resolve
                          ;; relative attachment refs beneath it. Absent
                          ;; when the run created no results dir (dry-run,
                          ;; exit 2/3, config-less :defaults contexts).
    :group/label \"..\"    ;; + :group/index N (0-based declared order) —
                          ;; group identity, present on per-group summaries
                          ;; of MULTI-group runs only (sl-pwbp); see the
                          ;; form stream section below
    :counts {:passed N :failed N :pending N :skipped N
             :error N          ;; only when positive — infrastructure-
                               ;; failure family (sl-esq, sl-7c7i)
             :unobserved N     ;; only when positive — :error scenarios of
                               ;; observation class (sl-kowi); disjoint
                               ;; from :error, both exit 4
             :scenarios N :steps N}
                        ;; counts ride EVERY summary (sl-7c7i AC):
                        ;; all-zero on exit 2/3, where nothing executed
    :halted {:after-scenario N :scenario \"name\"}
                        ;; only when :halt-on-observation-failure fired
                        ;; (sl-kowi): unstarted scenarios are :skipped
    :held [{:scenario \"name\" :feature \"..\" :uri \"..\" :line N
            :reason :unplannable :undefined N :ambiguous N}
           {... :reason :wip|:skip :tag \"@skip=flaky\" :note \"flaky\"}
           {... :reason :blocked :tag \"@blocked=REF\" :ref \"REF\"
            :ref-url \"https://…/REF\"
            :outcome {:status :failed|:unobserved :at-step k :message \"..\"}}
           {... :reason :aborted :after-step k}]
                        ;; THE ONE HOLD RECORD — every scenario the run did
                        ;; not give an unexcused verdict, with its reason:
                        ;; --hold-unplannable's (sl-6wh9) and the @wip/@skip
                        ;; tags' (sl-190x) never ran; a @blocked scenario
                        ;; (sl-190x) RAN and failed as its tag claims —
                        ;; :outcome says what was measured; the abort's
                        ;; (sl-617t) stopped it. :note/:ref absent when the
                        ;; tag was bare; :ref-url only when [:runner
                        ;; :ref-url] renders one. :counts also carries
                        ;; :held N (and :xpass N — @blocked scenarios that
                        ;; PASSED: :failed, exit 1, a :failures entry of
                        ;; :type :xpass with the imperative). Absent unless
                        ;; something was held.
    :unshifted-suite {:features N :scenarios M :distinct-steps K}
                        ;; sl-6wh9, exit 2 only, ADDITIVE-WHEN-TRIGGERED:
                        ;; present iff EVERY selected step was undefined —
                        ;; the suite reads as unshifted; the inventory is
                        ;; the migration work list. Absent on every other
                        ;; exit 2 (the :planning block still says why).
    :dropped-events N   ;; observe-plane events the bus refused at admission
                        ;; during THIS run-scope (sl-d7ks); absent when zero.
                        ;; Results are never affected — the report plane
                        ;; doesn't ride the bus; observations may be gapped.
    :plan [{:scenario/name \"..\" :uri \"..\" :line N
            :steps [{:step/text \"..\" :line N :column N
                     :step/resolved {:pattern-src \"..\"
                                     :stepdef-source {:ns .. :file .. :line N}
                                     :subject :alice :verb :fill :frame :with
                                     :object \"username\"
                                     :interface :web        ;; resolved INSTANCE
                                     :interface-type :web}  ;; stepdef TYPE —
                                                            ;; joins sl explain
                     :step/macro {:key \"..\" :role :call|:expanded
                                  :call-site {:file :line :column}
                                  :step-count N        ;; :call only
                                  :definition {:file}  ;; :call only (sl-mk0t)
                                  :index N             ;; :expanded only
                                  :definition-step {..}}}]}]
                        ;; sl-xvae — DRY-RUN summaries only, always present
                        ;; there: the machine plan, scenario → step → what it
                        ;; bound to, computed once at bind time. :step/resolved
                        ;; on every step that bound (an untyped stepdef yields
                        ;; the stepdef half only; SVO keys absent, never nil),
                        ;; none on macro wrappers or unbound steps;
                        ;; :step/macro only on wrappers/expanded steps.
    :planning {...}     ;; present when exit-code 2 (may carry :config-lints
                        ;; and :glossary-lints)
    :failures [{...}]   ;; present when exit-code 1 or 4; entries may carry
                        ;; :attachments — the failing step's/scenario's own
                        ;; refs, inline for join-free triage — and
                        ;; :poll-counts {:attempts :answered :threw
                        ;; :observation-throws} when the failing step's
                        ;; oracle polled (sl-tcsq); absent otherwise
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
                                        ;; shiftlefter.config ::lint-warnings,
                                        ;; scrubbed. Absent on clean runs.
                  :glossary-lints [...] ;; sl-67dj (Warden P3-13) — structured
                                        ;; glossary-load warnings from the
                                        ;; strict loaders (:type/:path/
                                        ;; :message maps, e.g. :glossary/
                                        ;; override-flag-ignored), scrubbed.
                                        ;; Absent on clean runs.
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
   - MULTI-group runs only (sl-pwbp, absent-unless-multi): each per-group
     summary form carries top-level `:group/label` + `:group/index`
     (0-based declared order — the durable join key when labels collide;
     under CLI narrowing the indexes keep their declared values and may
     have gaps, sl-baup), and ONE final invocation aggregate form follows
     all group forms:
     `{:run/id .. :run/seed .. :invocation/exit-code N :invocation/status
     kw :invocation/groups [{:group/label .. :group/index .. :run/exit-code
     .. :run/status ..} ..]}` — the suite verdict readable from the stream.
     Deliberately `:invocation/exit-code`, never `:run/exit-code`: the
     published consumer discriminator \"summaries are the forms carrying
     :run/exit-code\" (docs/REPORTS.md) must not match the aggregate.
     Consumer verdict rule: the `:invocation/exit-code` form if present,
     else the lone summary's `:run/exit-code`. The aggregate is ABSENT in
     single-group and classic streams (byte-stable — sl-esq law), absent
     on crash (exit 3 unwinds past aggregation; the stream ends where the
     crash cut it), and absent on suite-level exit-2s that precede the
     group loop (no groups ran; the lone summary IS the verdict).
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
      ;; sl-io05: the scenario a runner crash escaped from — plain data
      ;; {:name :source-file :line}, present only on that crash shape.
      (:scenario error) (assoc :scenario (:scenario error))
      (:data error) (assoc :data (:data error))
      (:value error) (assoc :value (:value error))
      ;; sl-kowi: the step/adapter error class, already EDN-native.
      (:error/class error) (assoc :error/class (:error/class error))
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
      (cond-> {:key (:key macro)
               ;; sl-xvae: :role (:call | :expanded) and, on expanded steps,
               ;; :index — so the dry-run plan's consumers (the 0.6
               ;; macro-expansion audit) tell a wrapper from its children
               ;; without inferring it from key presence. Additive on the
               ;; failure path too.
               :role role}
        ;; Call-site with assembled file path
        call-site
        (assoc :call-site {:file source-file
                           :line (:line call-site)
                           :column (:column call-site)})
        (some? (:index macro))
        (assoc :index (:index macro))
        ;; Wrapper: how many steps the call expands to
        (some? (:step-count macro))
        (assoc :step-count (:step-count macro))
        ;; Wrapper: the macro's definition file (sl-mk0t, Warden W5) — the
        ;; same fact console -v prints beside the wrapper line, so the two
        ;; plan renderings agree. Additive; the expanded children carry it
        ;; inside :definition-step already.
        (and (= :call role) (get-in macro [:definition :file]))
        (assoc :definition {:file (get-in macro [:definition :file])})
        ;; Definition-step (expanded steps only)
        (and (= :expanded role) def-step)
        (assoc :definition-step def-step)))))

;; -----------------------------------------------------------------------------
;; Dry-run plan (sl-xvae) — the machine plan: scenario → step → what it bound to
;; -----------------------------------------------------------------------------

(defn- plan-step-entry
  "One dry-run plan step: text + location, the bind-time :step/resolved record
   when the step bound (bind/resolved-record — absent on wrappers and unbound
   steps), and :step/macro provenance when the step came from a macro."
  [bound-step source-file]
  (let [step (:step bound-step)
        loc (:step/location step)]
    (cond-> {:step/text (:step/text step)
             :line (:line loc)
             :column (:column loc)}
      (:resolved bound-step)
      (assoc :step/resolved (:resolved bound-step))
      (:step/macro step)
      (assoc :step/macro (extract-macro-provenance step source-file)))))

(defn dry-run-plan
  "The dry-run summary's :plan (sl-xvae): one entry per selected scenario in
   plan order, each with its steps in execution order (macro wrappers
   followed by their expanded steps, as bound). Always present on a dry-run
   summary — a dry run always has a plan."
  [plans]
  (mapv (fn [plan]
          (let [pickle (:plan/pickle plan)
                uri (:pickle/source-file pickle)]
            {:scenario/name (:pickle/name pickle)
             :uri uri
             :line (get-in pickle [:pickle/location :line])
             :steps (mapv #(plan-step-entry % uri) (:plan/steps plan))}))
        plans))

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
      (assoc :attachments (vec (:attachments step-result)))
      ;; Poll digest (sl-tcsq): the failing step's own summed counts,
      ;; top-level beside :error (a timeout's :error :data carries the
      ;; last poll's digest too — same convention as attachments, the
      ;; duplication is deliberate). Absent when the step never polled.
      (:poll-counts step-result)
      (assoc :poll-counts (:poll-counts step-result)))))

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

(defn- extract-xpass
  "The XPASS entry (sl-190x): a @blocked scenario that passed — no step
   failed, so the entry is the tag, the ref (and link) and the imperative."
  [scenario]
  (let [pickle (-> scenario :plan :plan/pickle)
        {:keys [tag ref ref-url message]} (:xpass scenario)]
    (cond-> {:scenario/name (:pickle/name pickle)
             :scenario/id (:pickle/id pickle)
             :type :xpass
             :tag tag
             :message message}
      ref (assoc :ref ref)
      ref-url (assoc :ref-url ref-url))))

(defn- extract-failures
  "Extract all failures from scenario results. :failed scenarios contribute
   their failed steps; :error scenarios (infrastructure-failure family,
   sl-esq/sl-7c7i) contribute a scenario-level entry plus any failed steps
   (the after-throw case, and the provisioning flip where the failing step
   stays :failed); an XPASS (sl-190x) contributes its own entry. A
   @blocked scenario held as claimed contributes nothing — it is not a
   failure of this run; the :held record carries what was measured."
  [scenarios]
  (let [scenarios (remove :held scenarios)]
    (concat
     (for [scenario scenarios
           :when (:xpass scenario)]
       (extract-xpass scenario))
     (for [scenario scenarios
           :when (= :error (:status scenario))]
       (extract-scenario-error scenario))
     (for [scenario scenarios
           :when (#{:failed :error} (:status scenario))
           step (:steps scenario)
           :when (= :failed (:status step))]
       (extract-step-failure step scenario)))))

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

      ;; sl-u2r0 (Fable 5): the step-text did-you-mean + def-site.
      (:verb-suggestion bound-step)
      (assoc :verb-suggestion (:verb-suggestion bound-step))

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
        (assoc :config-lints (vec (:config-lints diagnostics)))
        ;; sl-67dj (Warden P3-13): glossary-load warnings likewise.
        (seq (:glossary-lints diagnostics))
        (assoc :glossary-lints (vec (:glossary-lints diagnostics)))
        ;; sl-190x: tag lints likewise.
        (seq (:tag-lints diagnostics))
        (assoc :tag-lints (vec (:tag-lints diagnostics)))
        ;; sl-6wh9: the mixed-state fact behind the --hold-unplannable
        ;; hint — present only when some scenarios plan and some do not.
        (:plannability diagnostics)
        (assoc :plannability (:plannability diagnostics))))))

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
    (let [{:keys [svo-issues stepdef-issues config-lints glossary-lints
                  cleanup-failures capture-lints tag-lints counts]} diagnostics]
      (not-empty
       (cond-> {}
         (seq svo-issues)   (assoc :svo-issues svo-issues
                                   :counts (select-keys counts [:svo-issue-count]))
         ;; Tag lints (sl-190x): a bare @skip / @blocked, a valued @serial,
         ;; a malformed tag — warn-level, stderr suppressed under --edn.
         (seq tag-lints)    (assoc :tag-lints (vec tag-lints))
         ;; sl-6wh9: warn-level Tier-2 stepdef issues (:stepdef/unknown-verb
         ;; at :warn) — reported, never blocking.
         (seq stepdef-issues) (assoc :stepdef-issues (vec stepdef-issues))
         (seq config-lints) (assoc :config-lints (vec config-lints))
         ;; Glossary-load warnings (sl-67dj, Warden P3-13): warn-level truth
         ;; from the strict loaders (e.g. :glossary/override-flag-ignored) —
         ;; stderr is suppressed under --edn, so this is the machine channel.
         (seq glossary-lints) (assoc :glossary-lints (vec glossary-lints))
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
               (:seed opts) (assoc :run/seed (:seed opts))
               ;; sl-7dq5 F11b: envelope → artifacts pointer. Absent when
               ;; the run created no results dir (dry-run, exit 2/3,
               ;; :defaults contexts) — absent-when-unknown, golden-safe.
               (:results-path opts) (assoc :run/results-path (:results-path opts))
               ;; sl-pwbp: group identity — pure projection; callers pass
               ;; these only in multi-group invocations (absent-unless-
               ;; multi, golden-safe), so single-group summaries stay
               ;; byte-identical.
               (:group-label opts) (assoc :group/label (:group-label opts))
               (some? (:group-index opts)) (assoc :group/index (:group-index opts)))]
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

                  ;; sl-kowi: the halt record — absent unless the policy
                  ;; fired (golden-safe).
                  (:halted opts)
                  (assoc :halted (:halted opts))

                  ;; sl-6wh9: the held record — absent unless
                  ;; --hold-unplannable held something (golden-safe).
                  (seq (:held opts))
                  (assoc :held (vec (:held opts)))

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
      2 (cond-> (assoc base
                       :counts zero-counts
                       :planning (extract-planning-diagnostics (:diagnostics opts)))
          ;; sl-6wh9: the unshifted-suite inventory, top-level beside
          ;; :planning (the :halted precedent for machine-decided facts).
          ;; Additive-when-triggered: absent on every other exit 2.
          (:unshifted-suite (:diagnostics opts))
          (assoc :unshifted-suite (:unshifted-suite (:diagnostics opts))))

      ;; Crash - include error. Zero counts (sl-7c7i): no completed verdict
      ;; exists; per-scenario truth for a mid-run crash is unknowable here.
      3 (assoc base
               :counts zero-counts
               :error (serialize-error (:error opts))))))

(defn invocation-summary
  "Build the ONE invocation aggregate form for a multi-group run (sl-pwbp)
   — emitted after every group's summary so a machine consumer reads the
   suite verdict from the stream instead of only the process exit.

   `group-results` are run-with-setups!'s per-group result maps, in
   declared order; rows echo the per-group summary key vocabulary verbatim
   (`:group/index` is the join key — each result's DECLARED position,
   carried, so a narrowed run's rows keep their declared numbers with
   gaps; sl-baup). Top-level key is `:invocation/exit-code`
   ON PURPOSE — the published discriminator \"summaries are the forms
   carrying :run/exit-code\" (docs/REPORTS.md) must not match this form.

   `opts` (the 5-arity):
   - `:dry-run?` (sl-a7sx, Warden W6): dry-run is invocation-wide (no
     mixed case), so a clean dry run's aggregate says `:invocation/status
     :dry-run` with exit code 0 — a plan verdict, never `:passed` for a run
     that measured nothing. A dry run whose exit code is NONZERO (a group
     failed planning, exit 2) keeps the exit-derived status —
     `:planning-failed` beside exit 2 is the truth the rows already tell.
   - `:halted` (sl-78u4, Warden W3): under `:halt-on-observation-failure
     :run` the gate's record — the group summary's `{:after-scenario N
     :scenario name}` plus `:group/label` of the group that went dark —
     rides the aggregate; absent otherwise. Groups the halt SKIPPED (never
     started, their :start never ran) have rows with `:run/status :skipped`
     and NO `:run/exit-code`: no verdict exists, and absence is how this
     very form already says so.
   - `:results-path` (sl-alha, Warden W32): the INVOCATION-level run dir,
     config-root-relative (`results/<stamp>`) — rides as
     `:invocation/results-path`, additive, absent when no results dir
     exists (dry-run, :defaults contexts). Group rows keep their own
     `:run/results-path` on the per-group summaries; a consumer no longer
     derives the invocation dir by slug-stripping.
   The 4-arity is the plain live-run form."
  ([run-id seed exit-code group-results]
   (invocation-summary run-id seed exit-code group-results {}))
  ([run-id seed exit-code group-results {:keys [dry-run? halted results-path]}]
   (let [status (if (and dry-run? (zero? exit-code))
                  :dry-run
                  (verdict/exit-code->status exit-code))
         rows (vec (map-indexed
                    (fn [position r]
                      ;; sl-baup: the DECLARED index carried on the group
                      ;; result — never recomputed over the rows, so under
                      ;; CLI narrowing the numbers keep their declared
                      ;; values and may have gaps.
                      (cond-> {:group/label (:group-label r)
                               :group/index (:group-index r position)
                               :run/status (:status r)}
                        (contains? r :exit-code)
                        (assoc :run/exit-code (:exit-code r))))
                    group-results))]
     (cond-> {:run/id run-id
              :invocation/exit-code exit-code
              :invocation/status status
              :invocation/groups rows}
       ;; sl-08f7 core-record family: the runner path always passes the
       ;; seed; the guard only spares direct callers (build-summary's
       ;; discipline).
       seed (assoc :run/seed seed)
       halted (assoc :halted halted)
       results-path (assoc :invocation/results-path results-path)))))

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
                     :dropped-events (:dropped-events run-summary)
                     ;; sl-7dq5 F11b: same nil-threads-as-absent discipline.
                     :results-path (:results-path run-summary)
                     ;; sl-kowi: the halt record, same discipline.
                     :halted (:halted run-summary)
                     ;; sl-6wh9: the held record, same discipline.
                     :held (:held run-summary)
                     ;; sl-pwbp: group identity rides the :run-end payload
                     ;; only in multi-group invocations — nil threads as
                     ;; absent, same discipline.
                     :group-label (:group-label run-summary)
                     :group-index (:group-index run-summary)}))
    nil))

(defn make-reporter
  "Construct an EdnReporter."
  []
  (->EdnReporter (atom {:scenarios [] :diagnostics nil})))
