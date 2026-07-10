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
    :pending :yellow
    :skipped :cyan
    :gray))

(defn- format-status
  "Format a status keyword for display."
  [status use-color?]
  (colorize (str/upper-case (name status)) (status-color status) use-color?))

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
         (when-let [data (:data error)]
           (str "     " (colorize (str "Data: " (pr-str data)) :gray use-color?) "\n")))))

(defn- format-scenario-failure
  "Format a scenario with its failed steps."
  [scenario-result use-color?]
  (let [pickle (-> scenario-result :plan :plan/pickle)
        source-file (:pickle/source-file pickle)
        failed-steps (filter #(= :failed (:status %)) (:steps scenario-result))]
    (str (colorize (str "Scenario: " (:pickle/name pickle)) :bold use-color?) "\n"
         (str/join "\n" (map-indexed #(format-step-failure %2 %1 source-file use-color?) failed-steps)))))

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

(defn- format-step-verbose
  "Format a step for verbose output.
   Transforms subject display: `:user/alice` → `[:user] alice`."
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
      (println (str (format-status status use-color?) " "
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
  "Format an undefined step for diagnostics with location."
  [bound-step use-color?]
  (let [step (:step bound-step)
        step-loc (:step/location step)
        loc {:file (:source-file bound-step)
             :line (:line step-loc)
             :column (:column step-loc)}]
    (str "  - " (colorize (:step/text step) :yellow use-color?)
         " (" (format-location loc) ")")))

(defn- format-ambiguous-step
  "Format an ambiguous step for diagnostics."
  [bound-step use-color?]
  (let [step (:step bound-step)
        alts (:alternatives bound-step)]
    (str "  - " (colorize (:step/text step) :yellow use-color?) "\n"
         "    Matches:\n"
         (str/join "\n" (map #(str "      • " (:pattern-src %) " at " (format-location (:source %))) alts)))))

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
        {:keys [undefined ambiguous invalid-arity svo-issues
                suite-lint-issues stepdef-issues annotation-errors
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
                        (count invalid-arity) (count suite-lint-issues)
                        (count stepdef-issues) (count annotation-errors)
                        (count macro-errors) (count other-errors)
                        (count svo-issues))
            total    (max (or (:total-issues counts) 0) rendered)]
        (println)
        (println (colorize (str total " binding issue(s) found. Cannot execute.") :red use-color?))))))

(defn print-warnings!
  "Print warn-level SVO issues that did not block the run (success paths:
   dry-run and execute). No-op when diagnostics carry none — no noise on
   clean runs.
   Outputs to stderr."
  [diagnostics opts]
  (let [use-color? (colors-enabled? opts)
        warns (filter #(= :warn (:severity %)) (:svo-issues diagnostics))]
    (when (seq warns)
      (binding [*out* *err*]
        (println (colorize "\nSVO validation warnings:" :yellow use-color?))
        (doseq [issue warns]
          (println (format-svo-issue issue use-color?))
          (println))))))

;; -----------------------------------------------------------------------------
;; Summary Output
;; -----------------------------------------------------------------------------

(defn print-failures!
  "Print failure details.
   Outputs to stderr."
  [scenarios opts]
  (let [use-color? (colors-enabled? opts)
        failed (filter #(= :failed (:status %)) scenarios)]
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
   - opts: {:no-color bool :verbose bool :duration-ms N}"
  [result opts]
  (let [use-color? (colors-enabled? opts)
        {:keys [passed failed pending skipped]} (:counts result)
        total (+ (or passed 0) (or failed 0) (or pending 0) (or skipped 0))]
    (binding [*out* *err*]
      (println)
      (println (str total " scenario(s): "
                    (when (pos? (or passed 0))
                      (str (colorize (str passed " passed") :green use-color?)
                           (when (pos? (+ (or failed 0) (or pending 0) (or skipped 0))) ", ")))
                    (when (pos? (or failed 0))
                      (str (colorize (str failed " failed") :red use-color?)
                           (when (pos? (+ (or pending 0) (or skipped 0))) ", ")))
                    (when (pos? (or pending 0))
                      (str (colorize (str pending " pending") :yellow use-color?)
                           (when (pos? (or skipped 0)) ", ")))
                    (when (pos? (or skipped 0))
                      (colorize (str skipped " skipped") :cyan use-color?))))
      (when-let [duration (:duration-ms opts)]
        (println (colorize (format "Completed in %.2fs" (/ duration 1000.0)) :gray use-color?))))))

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
    (print-warnings! (:diagnostics @state) opts)
    nil))

(defn make-reporter
  "Construct a ConsoleReporter. `opts` is the report-opts map
   ({:verbose :no-color})."
  [opts]
  (->ConsoleReporter opts (atom {:scenarios [] :diagnostics nil})))
