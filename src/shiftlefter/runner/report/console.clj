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
;; Verbose Output
;; -----------------------------------------------------------------------------

(defn- format-step-verbose
  "Format a step for verbose output."
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
                  "")]
     (str indent
          (colorize prefix (status-color status) use-color?)
          (:step/text step)
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

(defn- format-svo-issue
  "Format an SVO issue for display with color."
  [issue use-color?]
  (let [formatted (validate/format-svo-issue issue)
        lines (str/split-lines formatted)
        ;; First line gets ERROR: prefix
        first-line (str "  " (colorize "ERROR: " :red use-color?) (first lines))
        ;; Rest are indented continuation
        rest-lines (map #(str "  " %) (rest lines))]
    (str/join "\n" (cons first-line rest-lines))))

(defn print-diagnostics!
  "Print planning diagnostics (undefined/ambiguous steps, SVO issues).
   Outputs to stderr."
  [diagnostics opts]
  (let [use-color? (colors-enabled? opts)
        {:keys [undefined ambiguous invalid-arity svo-issues counts]} diagnostics]
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

      (println)
      (println (colorize (str (:total-issues counts) " binding issue(s) found. Cannot execute.") :red use-color?)))))

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
