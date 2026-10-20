(ns shiftlefter.runner.report.console
  "Console reporter for ShiftLefter runner.

   All output goes to **stderr** (human-readable).
   Machine-readable EDN goes to stdout via the edn reporter.

   ## Usage

   ```clojure
   (print-summary! {:counts {:passed 5 :failed 1 :pending 0 :skipped 0}
                    :failures [{:step/text \"I click button\" :error {...}}]})
   ```

   ## Color Support

   Colors enabled by default unless:
   - `--no-color` flag passed (via opts)
   - `NO_COLOR` env var set
   - Not a TTY (future enhancement)"
  (:require [clojure.string :as str]
            [shiftlefter.runner.reporter :as reporter]
            [shiftlefter.svo.validate :as validate]))

;; -----------------------------------------------------------------------------
;; ANSI Color Codes
;; -----------------------------------------------------------------------------

(def ^:private ansi-codes
  {:reset   "\u001b[0m"
   :bold    "\u001b[1m"
   :red     "\u001b[31m"
   :green   "\u001b[32m"
   :yellow  "\u001b[33m"
   :blue    "\u001b[34m"
   :magenta "\u001b[35m"
   :cyan    "\u001b[36m"
   :gray    "\u001b[90m"})

(defn- colorize
  "Apply ANSI color to text if colors enabled."
  [text color use-color?]
  (if use-color?
    (str (get ansi-codes color "") text (:reset ansi-codes))
    text))

(defn- colors-enabled?
  "Check if colors should be used based on opts and environment."
  [opts]
  (and (not (:no-color opts))
       (not (System/getenv "NO_COLOR"))))

;; -----------------------------------------------------------------------------
;; Status Formatting
;; -----------------------------------------------------------------------------

(defn- status-color [status]
  (case status
    :passed :green
    :failed :red
    :error :red
    :pending :yellow
    :skipped :cyan
    :gray))

(defn- format-status
  "Format a status keyword for display."
  [status use-color?]
  (colorize (str/upper-case (name status)) (status-color status) use-color?))

(def ^:private blocked-held-phrase
  "sl-190x: the shared phrase (reporter.clj) — one wording on every surface."
  reporter/blocked-held-phrase)

(defn- format-scenario-status
  "The status token for a scenario line (sl-kowi): an observation-class
   :error renders `UNOBSERVED` (the witness went dark — distinct from
   ERROR, the harness family, and from FAILED, a verdict); a :failed
   scenario whose failing step is :sut-classed renders `FAILED (SUT
   error)`. Everything else is `format-status` verbatim."
  [scenario-result status use-color?]
  (cond
    ;; sl-190x: a @blocked scenario that failed as its tag claims is
    ;; `HELD (blocked by REF — <what was measured>)` — checked FIRST, before
    ;; the measured status's own token (an unobserved one keeps :error).
    (= :blocked (-> scenario-result :held :reason))
    (str (colorize "HELD" :cyan use-color?) " "
         (colorize (str "(" (blocked-held-phrase (:held scenario-result)) ")")
                   :cyan use-color?))

    (and (= :error status)
         (= :observation (-> scenario-result :error :error/class)))
    (colorize "UNOBSERVED" :red use-color?)

    ;; sl-617t: a scenario the run abort held — never started, or stopped
    ;; at a step boundary (`:after-step k`) — is `HELD (aborted)`: not a
    ;; verdict, and not the plain SKIPPED of a non-runnable plan.
    (and (= :skipped status) (= :aborted (-> scenario-result :held :reason)))
    (str (colorize "HELD" :cyan use-color?) " "
         (colorize (if-let [k (-> scenario-result :held :after-step)]
                     (str "(aborted after step " k ")")
                     "(aborted)")
                   :cyan use-color?))

    ;; sl-190x: XPASS — the @blocked claim is false; a wrong test, so the
    ;; row is FAILED with the reason.
    (:xpass scenario-result)
    (str (format-status :failed use-color?) " "
         (colorize "(blocked scenario passed)" :red use-color?))

    (and (= :failed status)
         (some #(and (= :failed (:status %)) (= :sut (-> % :error :error/class)))
               (:steps scenario-result)))
    (str (format-status status use-color?) " "
         (colorize "(SUT error)" :red use-color?))

    :else (format-status status use-color?)))

;; -----------------------------------------------------------------------------
;; Failure Formatting
;; -----------------------------------------------------------------------------

(defn- format-location
  "Format a location map as file:line:col string."
  [{:keys [file line column]}]
  (cond
    (and file line column) (str file ":" line ":" column)
    (and file line) (str file ":" line)
    file file
    :else "<unknown>"))

(defn- format-macro-provenance
  "Format macro provenance lines for a failed step."
  [step source-file use-color?]
  (when-let [macro (:step/macro step)]
    (let [role (:role macro)
          call-site (:call-site macro)
          def-step (:definition-step macro)]
      (str
       ;; Call-site: where the macro was invoked in the feature file
       (when call-site
         (str "     " (colorize "macro call-site: " :gray use-color?)
              source-file ":" (:line call-site) ":" (:column call-site) "\n"))
       ;; Definition-step: where this step is defined in the INI file (expanded steps only)
       (when (and (= :expanded role) def-step)
         (str "     " (colorize "definition-step: " :gray use-color?)
              (:file def-step) ":" (:line def-step) ":" (:column def-step) "\n"))))))

(def ^:private error-class-gloss
  "One-line gloss per sl-kowi error class — the witness relation, stated
   at the failure so the reader routes without opening the ladder page."
  {:observation "no answer existed; this step proves nothing about the app"
   :sut "the app answered with an error"
   :assertion "the app answered; the answer missed the expectation"})

(defn- format-error-class-line
  "The gray `class:` continuation line for an error map carrying
   `:error/class` (sl-kowi); nil when unclassified — unclassified output is
   byte-identical to before."
  [error use-color?]
  (when-let [c (:error/class error)]
    (str "     " (colorize (str "class: " (name c) " — " (error-class-gloss c))
                           :gray use-color?) "\n")))

(defn- format-step-failure
  "Format a single step failure for display."
  [step-result idx source-file use-color?]
  (let [step (:step step-result)
        error (:error step-result)
        binding (:binding step-result)]
    (str "  " (inc idx) ") "
         (colorize (:step/text step) :bold use-color?) "\n"
         (when-let [src (:source binding)]
           (str "     at " (format-location src) "\n"))
         (format-macro-provenance step source-file use-color?)
         (when error
           (str "     " (colorize (str "Error: " (:message error)) :red use-color?) "\n"))
         (format-error-class-line error use-color?)
         (when-let [data (:data error)]
           (str "     " (colorize (str "Data: " (pr-str data)) :gray use-color?) "\n")))))

(defn- format-scenario-error
  "Format a scenario-level :error (the infrastructure-failure family,
   sl-esq/sl-7c7i: hook throw, capability provisioning, capture;
   plus the observation-class flip, sl-kowi — headed `Unobserved`).
   Hook-attributed errors keep the dual attribution: the hook's name +
   registration home, and the @hook= tag's file:line."
  [error use-color?]
  (str "  " (colorize (str (cond
                             (:hook error) (str "Hook '" (:hook error) "' failed")
                             (= :observation (:error/class error)) "Unobserved"
                             :else "Scenario error")
                           (when-let [t (:type error)] (str " (" t ")"))
                           ": " (:message error))
                      :red use-color?) "\n"
       (format-error-class-line error use-color?)
       (when-let [reg (:path (:registration error))]
         (str "     " (colorize "registered at: " :gray use-color?) reg "\n"))
       (when-let [ts (:tag-source error)]
         (str "     " (colorize "tagged at: " :gray use-color?)
              (format-location ts) "\n"))
       (when-let [data (:data error)]
         (str "     " (colorize (str "Data: " (pr-str data)) :gray use-color?) "\n"))))

(defn- format-attachment-line
  "One plain ASCII continuation line per attachment ref (sl-l9nj, design
   §4): `     attachment: screenshot -> attachments/<slug>/x.png` on the
   5-space ladder; errored refs show the failure message instead of the
   path — the message is the triage signal (the path's .err file holds
   the same text, sl-yynw F7)."
  [ref use-color?]
  (let [kind (name (:attachment/kind ref))]
    (if-let [err (:attachment/error ref)]
      (str "     " (colorize (str "attachment: " kind " FAILED: " err)
                             :red use-color?) "\n")
      (str "     " (colorize "attachment: " :gray use-color?)
           kind " -> " (:attachment/path ref) "\n"))))

(defn- format-scenario-failure
  "Format a scenario with its failed steps. A scenario-level :error (the
   infrastructure-failure family, sl-esq/sl-7c7i) renders its own
   attributed block; any failed steps still follow (the
   after-throw-plus-step-failure case). When a failed step already bears
   the identical error map (the provisioning flip: step :failed, scenario
   :error, one error), the scenario-level block is suppressed — the step
   block carries the location and the message; printing both would say
   the same thing twice. Attachment refs (sl-l9nj) close the block —
   evidence lines under the failure they explain; attachment-less
   scenarios are byte-identical."
  [scenario-result use-color?]
  (let [pickle (-> scenario-result :plan :plan/pickle)
        source-file (:pickle/source-file pickle)
        failed-steps (filter #(= :failed (:status %)) (:steps scenario-result))
        attachments (concat (mapcat :attachments (:steps scenario-result))
                            (:attachments scenario-result))]
    (str (colorize (str "Scenario: " (:pickle/name pickle)) :bold use-color?) "\n"
         ;; XPASS (sl-190x): no step failed — the block is the imperative
         ;; and, when the config renders one, the tracker link.
         (when-let [{:keys [message ref-url]} (:xpass scenario-result)]
           (str "  " (colorize message :red use-color?) "\n"
                (when ref-url (str "  " ref-url "\n"))))
         (when-let [error (:error scenario-result)]
           (when-not (some #(= error (:error %)) failed-steps)
             (format-scenario-error error use-color?)))
         (str/join "\n" (map-indexed #(format-step-failure %2 %1 source-file use-color?) failed-steps))
         (when (seq attachments)
           (apply str (map #(format-attachment-line % use-color?) attachments))))))

;; -----------------------------------------------------------------------------
;; Macro Step Helpers
;; -----------------------------------------------------------------------------

(defn- wrapper-step?
  "Check if a step result is a macro wrapper."
  [step-result]
  (true? (-> step-result :step :step/synthetic?)))

;; -----------------------------------------------------------------------------
;; Subject Display
;; -----------------------------------------------------------------------------

(defn- format-subject-display
  "Transform a step text's leading subject for display.
   `:user/alice opens the browser` → `[:user] alice opens the browser`
   `:guest opens the browser` → `[:guest] guest opens the browser`
   Non-subject steps pass through unchanged."
  [step-text]
  (if-let [[_ subject rest-of-text] (re-matches #"^:(\S+)\s+(.*)" step-text)]
    (let [kw (keyword subject)]
      (if-let [ns-part (namespace kw)]
        ;; Qualified: :user/alice → [:user] alice
        (str "[:" ns-part "] " (name kw) " " rest-of-text)
        ;; Bare: :guest → [:guest] guest
        (str "[:" subject "] " subject " " rest-of-text)))
    step-text))

;; -----------------------------------------------------------------------------
;; Verbose Output
;; -----------------------------------------------------------------------------

(defn- format-poll-suffix
  "The gray ` (N polls)` suffix for a step whose oracle polled more than
   once (sl-tcsq); nil otherwise, so zero-poll and single-answer lines
   stay byte-identical."
  [step-result use-color?]
  (let [n (get-in step-result [:poll-counts :attempts] 0)]
    (when (> n 1)
      (str " " (colorize (str "(" n " polls)") :gray use-color?)))))

(defn- format-step-verbose
  "Format a step for verbose output.
   Transforms subject display: `:user/alice` → `[:user] alice`. A step
   that polled more than once carries its poll count right after the
   text, before any failure message."
  ([step-result use-color?]
   (format-step-verbose step-result use-color? "  "))
  ([step-result use-color? indent]
   (let [step (:step step-result)
         status (:status step-result)
         prefix (case status
                  :passed "✓ "
                  :failed "✗ "
                  :pending "? "
                  :skipped "- "
                  "")
         display-text (format-subject-display (:step/text step))]
     (str indent
          (colorize prefix (status-color status) use-color?)
          display-text
          (format-poll-suffix step-result use-color?)
          (when (= :failed status)
            (str " — " (-> step-result :error :message)))))))

(defn- format-wrapper-step
  "Format a wrapper step, optionally with its children.
   In collapsed mode: show wrapper only.
   In expanded mode: show wrapper + indented children."
  [wrapper-result children use-color? expanded?]
  (let [wrapper-line (format-step-verbose wrapper-result use-color? "  ")]
    (if (or (not expanded?) (empty? children))
      wrapper-line
      ;; Expanded: show wrapper + children
      (str wrapper-line "\n"
           (str/join "\n"
                     (map #(format-step-verbose % use-color? "    ") children))))))

(defn- group-steps-for-display
  "Group steps into display units.
   Returns seq of {:type :wrapper/:regular :result step-result :children [...]}."
  [step-results]
  (loop [remaining step-results
         groups []]
    (if (empty? remaining)
      groups
      (let [step (first remaining)]
        (if (wrapper-step? step)
          ;; Wrapper: grab its children
          (let [child-count (-> step :step :step/macro :step-count)
                children (take child-count (rest remaining))]
            (recur (drop (inc child-count) remaining)
                   (conj groups {:type :wrapper
                                 :result step
                                 :children (vec children)})))
          ;; Regular step
          (recur (rest remaining)
                 (conj groups {:type :regular
                               :result step
                               :children []})))))))

(defn print-scenario!
  "Print a scenario result.
   Outputs to stderr.

   Step display depends on :verbose and :expand-macros opts:
   - No :verbose → scenario name and status only (no steps)
   - :verbose true → show steps with macros collapsed (wrapper only)
   - :verbose true + :expand-macros true → show wrapper + children"
  [scenario-result opts]
  (let [use-color? (colors-enabled? opts)
        pickle (-> scenario-result :plan :plan/pickle)
        status (:status scenario-result)
        verbose? (:verbose opts)
        expand-macros? (:expand-macros opts)
        groups (group-steps-for-display (:steps scenario-result))]
    (binding [*out* *err*]
      (println (str (format-scenario-status scenario-result status use-color?) " "
                    (colorize (:pickle/name pickle) :bold use-color?)))
      (when verbose?
        (doseq [{:keys [type result children]} groups]
          (case type
            :wrapper (println (format-wrapper-step result children use-color? expand-macros?))
            :regular (println (format-step-verbose result use-color?))))
        (println)))))

;; Alias for print-scenario! to match AC naming
(def print-pickle! print-scenario!)

;; -----------------------------------------------------------------------------
;; Planning Diagnostics
;; -----------------------------------------------------------------------------

(defn- format-undefined-step
  "Format an undefined step for diagnostics with location, followed by the
   grammar hint (sl-s04n) and/or interface suggestion (sl-563) when present."
  [bound-step use-color?]
  (let [step (:step bound-step)
        step-loc (:step/location step)
        loc {:file (:source-file bound-step)
             :line (:line step-loc)
             :column (:column step-loc)}
        hint (-> bound-step :hint :suggestion)
        filter-suggestion (-> bound-step :filter-info :suggestion)
        ;; sl-u2r0 (Fable 5): the step-text did-you-mean and its def-site,
        ;; one line — `did you mean "clicks"? — <pattern> (steps.clj:12)`.
        verb-suggestion (:verb-suggestion bound-step)
        verb-line (when-let [{:keys [suggestion pattern-src def-site]} verb-suggestion]
                    (str "did you mean \"" suggestion "\"?"
                         (when pattern-src (str " — " pattern-src))
                         (when-let [{:keys [file line]} def-site]
                           (str " (" file (when line (str ":" line)) ")"))))]
    (cond-> (str "  - " (colorize (:step/text step) :yellow use-color?)
                 " (" (format-location loc) ")")
      verb-line (str "\n      " (colorize verb-line :cyan use-color?))
      hint (str "\n      hint: " (colorize hint :cyan use-color?))
      filter-suggestion
      (str "\n      hint: " (colorize filter-suggestion :cyan use-color?)))))

(defn- format-ambiguous-step
  "Format an ambiguous step for diagnostics."
  [bound-step use-color?]
  (let [step (:step bound-step)
        alts (:alternatives bound-step)]
    (str "  - " (colorize (:step/text step) :yellow use-color?) "\n"
         "    Matches:\n"
         (str/join "\n" (map #(str "      • " (:pattern-src %) " at " (format-location (:source %))) alts)))))

(defn- format-ambiguous-instance-step
  "Format a matched step whose execution instance could not be resolved
   (sl-s2oa): the stepdef's TYPE has 2+ configured instances, no type-named
   default, and the step carries no [instance] annotation."
  [bound-step use-color?]
  (let [step (:step bound-step)
        step-loc (:step/location step)
        loc {:file (:source-file bound-step)
             :line (:line step-loc)
             :column (:column step-loc)}
        {:keys [message]} (:instance-resolution bound-step)]
    (str "  - " (colorize (:step/text step) :yellow use-color?)
         " (" (format-location loc) ")"
         "\n      " (colorize message :cyan use-color?))))

(defn- format-issue-label
  "Severity label for a diagnostic issue: yellow WARNING for :warn,
   red ERROR otherwise (missing :severity defaults to :error — blocking
   issues and legacy shapes without the key keep their current look)."
  [issue use-color?]
  (if (= :warn (:severity issue))
    (colorize "WARNING: " :yellow use-color?)
    (colorize "ERROR: " :red use-color?)))

(defn- format-svo-issue
  "Format an SVO issue for display with color, labeled per :severity."
  [issue use-color?]
  (let [formatted (validate/format-svo-issue issue)
        lines (str/split-lines formatted)
        ;; First line gets the severity label prefix
        first-line (str "  " (format-issue-label issue use-color?) (first lines))
        ;; Rest are indented continuation
        rest-lines (map #(str "  " %) (rest lines))]
    (str/join "\n" (cons first-line rest-lines))))

(defn- format-message-issue
  "Format a diagnostic that carries its detail in `:message` (suite-load
   lint issues, stepdef glossary issues, annotation/macro errors, generic
   planning errors). The `:message` already includes source file:line
   where applicable; we just prefix with the severity label and indent."
  [issue use-color?]
  (let [lines      (str/split-lines (or (:message issue) ""))
        first-line (str "  " (format-issue-label issue use-color?) (first lines))
        rest-lines (map #(str "         " %) (rest lines))]
    (str/join "\n" (cons first-line rest-lines))))

(def ^:private wrapper-error-types
  "Error types under :diagnostics :errors whose payload is already rendered
   by a dedicated section — skipped by the generic errors section to avoid
   double-printing."
  #{:stepdef/glossary-mismatch :suite-lint/failed})

(defn print-diagnostics!
  "Print planning diagnostics (undefined/ambiguous steps, SVO issues,
   suite-load lint issues, stepdef glossary issues, annotation/macro
   errors, generic planning errors).
   Outputs to stderr."
  [diagnostics opts]
  (let [use-color? (colors-enabled? opts)
        {:keys [undefined ambiguous invalid-arity ambiguous-instance
                svo-issues suite-lint-issues stepdef-issues annotation-errors
                macro-errors errors counts]} diagnostics
        ;; Generic errors not already covered by a dedicated section
        other-errors (remove #(wrapper-error-types (:type %)) errors)]
    (binding [*out* *err*]
      (when (seq undefined)
        (println (colorize "\nUndefined steps:" :yellow use-color?))
        (doseq [step undefined]
          (println (format-undefined-step step use-color?))))

      (when (seq ambiguous)
        (println (colorize "\nAmbiguous steps:" :yellow use-color?))
        (doseq [step ambiguous]
          (println (format-ambiguous-step step use-color?))))

      (when (seq invalid-arity)
        (println (colorize "\nInvalid arity:" :yellow use-color?))
        (doseq [step invalid-arity]
          (let [binding (:binding step)]
            (println (str "  - " (:step/text (:step step))
                          " (expected " (:expected binding) ", got " (:actual binding) ")")))))

      (when (seq ambiguous-instance)
        (println (colorize "\nAmbiguous interface instance:" :yellow use-color?))
        (doseq [step ambiguous-instance]
          (println (format-ambiguous-instance-step step use-color?))))

      (when (seq svo-issues)
        (println (colorize "\nSVO validation issues:" :yellow use-color?))
        (doseq [issue svo-issues]
          (println (format-svo-issue issue use-color?))
          (println)))

      (when (seq suite-lint-issues)
        (println (colorize "\nSuite-load lint issues:" :yellow use-color?))
        (doseq [issue suite-lint-issues]
          (println (format-message-issue issue use-color?))
          (println)))

      (when (seq stepdef-issues)
        (println (colorize "\nStepdef glossary issues:" :yellow use-color?))
        (doseq [issue stepdef-issues]
          (println (format-message-issue issue use-color?))
          (println)))

      (when (seq annotation-errors)
        (println (colorize "\nAnnotation errors:" :yellow use-color?))
        (doseq [err annotation-errors]
          (println (format-message-issue err use-color?))
          (println)))

      (when (seq macro-errors)
        (println (colorize "\nMacro errors:" :yellow use-color?))
        (doseq [err macro-errors]
          (println (format-message-issue err use-color?))
          (println)))

      (when (seq other-errors)
        (println (colorize "\nPlanning errors:" :yellow use-color?))
        (doseq [err other-errors]
          (println (format-message-issue err use-color?))
          (println)))

      ;; :total-issues covers every issue family (sl-89ii), but legacy
      ;; diagnostics shapes may omit it — never claim "0 issues" above a
      ;; Cannot execute; fall back to the number of items rendered above
      ;; (warn-level SVO issues included: they were printed too).
      (let [rendered (+ (count undefined) (count ambiguous)
                        (count invalid-arity) (count ambiguous-instance)
                        (count suite-lint-issues)
                        (count stepdef-issues) (count annotation-errors)
                        (count macro-errors) (count other-errors)
                        (count svo-issues))
            total    (max (or (:total-issues counts) 0) rendered)]
        (println)
        (println (colorize (str total " binding issue(s) found. Cannot execute.") :red use-color?))))))

(defn print-unshifted-suite!
  "sl-6wh9: the summary line for the degenerate planning result — every
   selected step undefined. Reads :unshifted-suite off the diagnostics
   (absent → prints nothing). Outputs to stderr. The migration pointer
   names the packaged `adoption` topic (sl-d4pf) — the same text as
   docs/adoption.md, reachable from the jar alone, which a repo path is not."
  [diagnostics opts]
  (when-let [{:keys [features scenarios distinct-steps]} (:unshifted-suite diagnostics)]
    (let [use-color? (colors-enabled? opts)]
      (binding [*out* *err*]
        (println)
        (println (colorize "This reads as an unshifted suite: every selected step is undefined."
                           :yellow use-color?))
        (println (str "ShiftLefter parsed " features " feature file(s), " scenarios
                      " scenario(s), " distinct-steps " distinct step text(s)"
                      " — and executes only typed steps."))
        (println "To shift this suite, run: sl agent-doc adoption")))))

(defn print-plannability-hint!
  "sl-6wh9: the mixed-state hint on an exit-2 diagnostic — some scenarios
   plan, some do not. Reads :plannability off the diagnostics (absent →
   prints nothing). Outputs to stderr. PLACEHOLDER PROSE — Herald words it
   at sl-02cl."
  [diagnostics opts]
  (when-let [{:keys [plannable unplannable total]} (:plannability diagnostics)]
    (let [use-color? (colors-enabled? opts)]
      (binding [*out* *err*]
        (println)
        (println (colorize (str plannable " of " total " scenario(s) plan; "
                                unplannable " are held by undefined, ambiguous or binding-error steps.")
                           :cyan use-color?))
        (println (str "To run the " plannable " now and list the " unplannable
                      ", pass --hold-unplannable."))))))

(defn held-reason-phrase
  "The reason phrase for one run-level :held entry, by kind (sl-6wh9,
   sl-617t, sl-190x): `not plannable (2 undefined, 0 ambiguous)` — with
   `, 1 binding error(s): element-has-no-shape` appended when lint errors
   held it (sl-8ezu) — `@wip`, `@skip=flaky-on-ci`, `blocked by REF — failed
   at step 2: …`, `aborted after step 1` / `aborted`."
  [{:keys [reason tag undefined ambiguous after-step] :as entry}]
  (case reason
    :unplannable (str "not plannable (" undefined " undefined, " ambiguous " ambiguous"
                      (when-let [b (reporter/binding-errors-phrase entry)] (str ", " b))
                      ")")
    :blocked (blocked-held-phrase entry)
    :aborted (if after-step (str "aborted after step " after-step) "aborted")
    tag))

(defn print-held-notice!
  "One stderr line per scenario held at planning (dry-run preview; the
   live run's verdict line carries the count): --hold-unplannable's
   (sl-6wh9) and the @wip/@skip tags' (sl-190x), each with its reason.
   Reads :held off the diagnostics; absent → prints nothing."
  [diagnostics opts]
  (when-let [held (seq (:held diagnostics))]
    (let [use-color? (colors-enabled? opts)]
      (binding [*out* *err*]
        (println (colorize (str (count held) " scenario(s) held:") :cyan use-color?))
        (doseq [{:keys [scenario uri line] :as entry} held]
          (println (str "  - " scenario " (" uri ":" line "; "
                        (held-reason-phrase entry) ")")))))))

(defn held-breakdown
  "`(2 wip, 1 skip, 1 blocked)` — the per-kind breakdown of a run-level
   :held vector in the verdict line (sl-190x), kinds in first-seen order;
   nil when the vector is empty."
  [held]
  (when (seq held)
    (let [kinds (distinct (map :reason held))
          counts (frequencies (map :reason held))]
      (str "(" (str/join ", " (map #(str (counts %) " " (name %)) kinds)) ")"))))

;; -----------------------------------------------------------------------------
;; Dry-run plan listing (sl-xvae) — under -v only
;; -----------------------------------------------------------------------------

(defn- format-resolved-triple
  "The bound SVO in SL's own notation: `:subject :verb [:frame] object via
   :interface`; nil when the stepdef is untyped (no verb)."
  [{:keys [subject verb frame object interface]}]
  (when verb
    (str/join " " (remove nil?
                          [subject verb
                           (when frame (str "[" frame "]"))
                           (when (some? object) (pr-str object))
                           (when interface (str "via " interface))]))))

(defn- verbose-level
  "The -v count: 0 when :verbose is absent, 1 for a bare `true` (programmatic
   callers), n for the CLI's counted flag (sl-jima)."
  [opts]
  (let [v (:verbose opts)]
    (cond (number? v) v
          v 1
          :else 0)))

(defn- format-plan-step
  "One listing line: the step text, then what it bound to — the resolved
   triple or `(untyped)`, then the stepdef's def site `(file:line)`
   (sl-jima: the triple leads; the pattern source is a debugging detail).
   Under `show-pattern?` (-vv) a bound step gains one sub-line beneath it,
   `pattern: <src>`, so the -v lines stay byte-identical inside -vv output.
   A macro wrapper names its macro and expansion size; a step that did not
   bind (holds only) shows its status."
  [bound-step indent show-pattern?]
  (let [step (:step bound-step)
        text (format-subject-display (:step/text step))
        r (:resolved bound-step)
        m (:step/macro step)
        {:keys [file line]} (:stepdef-source r)]
    (str indent text "  →  "
         (cond
           (= :synthetic (:status bound-step))
           (str "macro \"" (:key m) "\""
                (when-let [f (get-in m [:definition :file])] (str " (" f ")"))
                (when-let [n (:step-count m)] (str ", " n " step(s)")))

           r
           (str (or (format-resolved-triple r) "(untyped)")
                (when file (str "  (" file ":" line ")"))
                (when show-pattern?
                  (str "\n" indent "  pattern: " (or (:pattern-src r) "(pattern)"))))

           :else
           (str "(" (name (:status bound-step)) ")")))))

(defn print-dry-run-plan!
  "stderr block under the dry-run summary line, under :verbose only: the
   step plan — per scenario, one line per step naming the resolved triple
   and the stepdef it bound to; expanded macro steps indent under their
   wrapper. At verbose level 2 (-vv) each bound step also prints its
   stepdef's pattern source on a sub-line. Byte-identical output without
   :verbose (prints nothing)."
  [plans opts]
  (when (:verbose opts)
    (let [show-pattern? (>= (verbose-level opts) 2)]
      (binding [*out* *err*]
        (println "plan:")
        (doseq [plan plans]
          (println (str "  " (get-in plan [:plan/pickle :pickle/name])))
          (doseq [bound-step (:plan/steps plan)]
            (let [expanded? (= :expanded (-> bound-step :step :step/macro :role))]
              (println (format-plan-step bound-step
                                         (if expanded? "      " "    ")
                                         show-pattern?)))))))))

(defn print-warnings!
  "Print warn-level diagnostics that did not block the run (success paths:
   dry-run and execute): SVO issues, and capability-cleanup failures
   (sl-lbrc — a leaked driver on an otherwise-green run). No-op when
   diagnostics carry none — no noise on clean runs.
   Outputs to stderr."
  [diagnostics opts]
  (let [use-color? (colors-enabled? opts)
        warns (filter #(= :warn (:severity %)) (:svo-issues diagnostics))
        ;; sl-6wh9: warn-level Tier-2 issues (:stepdef/unknown-verb at :warn)
        stepdef-warns (filter #(= :warn (:severity %)) (:stepdef-issues diagnostics))
        cleanup-failures (:cleanup-failures diagnostics)]
    (when (seq warns)
      (binding [*out* *err*]
        (println (colorize "\nSVO validation warnings:" :yellow use-color?))
        (doseq [issue warns]
          (println (format-svo-issue issue use-color?))
          (println))))
    (when (seq stepdef-warns)
      (binding [*out* *err*]
        (println (colorize "\nStepdef glossary warnings:" :yellow use-color?))
        (doseq [issue stepdef-warns]
          (println (format-message-issue issue use-color?))
          (println))))
    (when (seq cleanup-failures)
      (binding [*out* *err*]
        (println (colorize "\nCapability cleanup failures (resources may have leaked):"
                           :yellow use-color?))
        (doseq [{:keys [scenario capability adapter error]} cleanup-failures]
          (println (str "  " scenario " — " capability
                        " (" adapter "): " error)))))))

;; -----------------------------------------------------------------------------
;; Summary Output
;; -----------------------------------------------------------------------------

(defn print-failures!
  "Print failure details — :failed scenarios and :error scenarios (a
   lifecycle hook threw, sl-esq) both belong here.
   Outputs to stderr."
  [scenarios opts]
  (let [use-color? (colors-enabled? opts)
        ;; A @blocked scenario held as claimed (sl-190x) keeps its measured
        ;; :failed/:error status but is not a failure of this run — its row
        ;; already says what was measured.
        failed (filter #(and (#{:failed :error} (:status %)) (not (:held %))) scenarios)]
    (when (seq failed)
      (binding [*out* *err*]
        (println)
        (println (colorize "Failures:" :red use-color?))
        (println)
        (doseq [scenario failed]
          (println (format-scenario-failure scenario use-color?)))))))

(defn print-summary!
  "Print run summary.
   Outputs to stderr.

   Parameters:
   - result: execution result from execute-suite
   - opts: {:no-color bool :verbose bool-or-count :duration-ms N} — :verbose is
     `true` from programmatic callers or the CLI's -v count (sl-jima)"
  [result opts]
  (let [use-color? (colors-enabled? opts)
        {:keys [passed failed error unobserved pending skipped held]} (:counts result)
        total (+ (or passed 0) (or failed 0) (or error 0) (or unobserved 0)
                 (or pending 0) (or skipped 0) (or held 0))
        ;; Segments joined with ", " — identical output to the historical
        ;; nested-when chain for the four original statuses; :error (sl-esq),
        ;; :unobserved (sl-kowi) and :held (sl-6wh9, --hold-unplannable)
        ;; appear only when positive. The held segment carries its per-kind
        ;; breakdown (sl-190x: `3 held (2 wip, 1 skip)`) read off the run's
        ;; :held record; an XPASS count rides the failed segment.
        held-label (str "held" (when-let [b (held-breakdown (:held result))] (str " " b)))
        xpass (:xpass (:counts result))
        failed-label (str "failed" (when (pos? (or xpass 0)) (str " (" xpass " XPASS)")))
        segments (keep (fn [[n label color]]
                         (when (pos? (or n 0))
                           (colorize (str n " " label) color use-color?)))
                       [[passed "passed" :green]
                        [failed failed-label :red]
                        [unobserved "unobserved" :red]
                        [error "error" :red]
                        [pending "pending" :yellow]
                        [skipped "skipped" :cyan]
                        [held held-label :cyan]])]
    (binding [*out* *err*]
      (println)
      (println (str total " scenario(s): " (str/join ", " segments)))
      ;; sl-kowi (Ruling 4): the halt line — only when the policy fired.
      ;; sl-617t: the same slot carries the abort — the run stopped on
      ;; request; the held count says how many never got a verdict.
      (when-let [{:keys [after-scenario scenario reason]} (:halted result)]
        (println (colorize (if (= :aborted reason)
                             (str "aborted after scenario " after-scenario
                                  ": " (or held 0) " scenario(s) held")
                             (str "halted after scenario " after-scenario
                                  (when scenario (str " (" scenario ")"))
                                  ": observation failure"))
                           :red use-color?)))
      (when-let [duration (:duration-ms opts)]
        (println (colorize (format "Completed in %.2fs" (/ duration 1000.0)) :gray use-color?))))))

(defn print-group-verdict!
  "One per-group verdict line for the multi-group console epilogue
   (sl-kvn6, riding sl-pwbp): the console analog of the HTML invocation
   TOC row — until now numeric per-group codes rendered ONLY there.
   Printed by the runner's group loop right after each group completes
   (the one seam that also sees planning-failed groups, which never reach
   a reporter's on-run-end). Outputs to stderr.

   `group-result` is run-with-setups!'s per-group result map
   (:group-label :exit-code :status). A group the :run-scope halt SKIPPED
   (sl-78u4, Warden W3) has no exit code — it never started — and renders
   `skipped (halted)`; one the run abort skipped (sl-617t) renders
   `skipped (aborted)`."
  [group-result opts]
  (let [{:keys [group-label exit-code status halted-by]} group-result
        use-color? (colors-enabled? opts)
        skipped? (nil? exit-code)
        color (cond skipped? :gray (zero? exit-code) :green :else :red)
        verdict (cond
                  (and skipped? (= :aborted (:reason halted-by))) "skipped (aborted)"
                  skipped? "skipped (halted)"
                  :else (str "exit code " exit-code " (" (name status) ")"))]
    (binding [*out* *err*]
      (println (str "Setup group " (pr-str group-label) ": "
                    (colorize verdict color use-color?))))))

;; -----------------------------------------------------------------------------
;; Reporter (sl-21z)
;; -----------------------------------------------------------------------------

(defrecord ConsoleReporter [opts state]
  reporter/Reporter
  (on-run-start [_this _run-ctx]
    ;; Nothing is printed at run start today.
    nil)

  (on-scenario-complete [_this scenario-result]
    ;; Accumulated for the end-of-run failure section, AND printed now (sl-dgk):
    ;; the status line lands as each scenario finishes, so a long e2e suite shows
    ;; live progress instead of silence-then-flood. `print-scenario!` prints just
    ;; the status+name line in non-verbose mode and adds the per-step breakdown
    ;; under :verbose — so verbose output is byte-identical to the pre-dgk
    ;; post-hoc pass; only non-verbose gains the per-scenario line.
    (swap! state update :scenarios conj scenario-result)
    (print-scenario! scenario-result opts)
    nil)

  (on-diagnostics [_this diagnostics]
    ;; STASHED, not printed: warn-level SVO issues appear AFTER the summary,
    ;; so they cannot be rendered at call time without reordering output.
    (swap! state assoc :diagnostics diagnostics)
    nil)

  (on-run-end [_this run-summary]
    (print-failures! (:scenarios @state) opts)
    (print-summary! run-summary opts)
    ;; sl-d7ks: drop honesty on the human plane — the bus-close log/warn is
    ;; logger-config-dependent; this line is not. Absent key = zero = silence.
    (when-let [dropped (:dropped-events run-summary)]
      (binding [*out* *err*]
        (println (colorize (str dropped " observe-plane event(s) dropped (bus full);"
                                " results unaffected — observations may be gapped")
                           :yellow (colors-enabled? opts)))))
    (print-warnings! (:diagnostics @state) opts)
    nil))

(defn make-reporter
  "Construct a ConsoleReporter. `opts` is the report-opts map
   ({:verbose :no-color})."
  [opts]
  (->ConsoleReporter opts (atom {:scenarios [] :diagnostics nil})))
