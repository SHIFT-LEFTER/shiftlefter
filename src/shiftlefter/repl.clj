(ns shiftlefter.repl
  "REPL utilities for interactive Gherkin execution.

   ## Quick Start

   ```clojure
   (require '[shiftlefter.repl :as repl])
   (require '[shiftlefter.stepengine.registry :refer [defstep]])

   ;; Define steps
   (defstep #\"I have (\\d+) cucumbers\" [n]
     (println \"Starting with\" n \"cucumbers\"))

   (defstep #\"I eat (\\d+)\" [n]
     (println \"Eating\" n))

   (defstep #\"I should have (\\d+) left\" [n]
     (println \"Checking for\" n \"left\"))

   ;; Run Gherkin text (full Feature/Scenario structure)
   (repl/run \"
     Feature: Cucumbers
       Scenario: Eating
         Given I have 12 cucumbers
         When I eat 5
         Then I should have 7 left\")

   ;; Or use free mode (no Feature/Scenario wrapper needed)
   (repl/step \"I have 12 cucumbers\")
   (repl/step \"I eat 5\")
   (repl/step \"I should have 7 left\")
   ```

   ## Functions

   ### Structured Mode (requires Feature/Scenario)
   - `run`        — parse and execute Gherkin text
   - `run-dry`    — parse and bind only (no execution)
   - `parse-only` — just parse, return pickles

   ### Free Mode (steps only, no structure)
   - `step`       — execute a single step, maintains session context
   - `free`       — execute steps in named context (multi-actor sessions)
   - `ctx`        — inspect session context (or named context)
   - `ctxs`       — inspect all named contexts
   - `reset-ctx!` — reset session context to empty
   - `reset-ctxs!`— reset all named contexts

   ### Utilities
   - `steps`      — list registered step patterns
   - `clear!`     — clear step registry and all contexts"
  (:require [shiftlefter.gherkin.api :as api]
            [shiftlefter.stepengine.registry :as registry]
            [shiftlefter.stepengine.bind :as bind]
            [shiftlefter.stepengine.exec :as exec]))

;; -----------------------------------------------------------------------------
;; Session Context (for free mode)
;; -----------------------------------------------------------------------------

(defonce ^:private session-ctx (atom {}))
(defonce ^:private named-contexts (atom {}))

(defn ctx
  "Get the current session context (or a named context).

   In free mode, context accumulates across step calls.
   Step functions returning maps merge into this context."
  ([] @session-ctx)
  ([name] (get @named-contexts name {})))

(defn ctxs
  "Get all named contexts as a map."
  []
  @named-contexts)

(defn reset-ctx!
  "Reset session context to empty map (or provided value).

   Returns the new context."
  ([] (reset! session-ctx {}))
  ([new-ctx] (reset! session-ctx new-ctx)))

(defn reset-ctxs!
  "Reset all named contexts."
  []
  (reset! named-contexts {}))

;; -----------------------------------------------------------------------------
;; Core Functions
;; -----------------------------------------------------------------------------

(defn parse-only
  "Parse Gherkin text and return pickles.

   Returns:
   - {:status :ok :pickles [...]}
   - {:status :parse-error :errors [...]}"
  [text]
  (let [{:keys [ast errors]} (api/parse-string text)]
    (if (seq errors)
      {:status :parse-error :errors errors}
      (let [{:keys [pickles]} (api/pickles ast "repl://")]
        {:status :ok :pickles pickles}))))

(defn run-dry
  "Parse and bind Gherkin text without executing.

   Useful for checking step definitions match.

   Returns:
   - {:status :ok :plans [...] :diagnostics {...}}
   - {:status :parse-error :errors [...]}
   - {:status :bind-error :diagnostics {...}}"
  [text]
  (let [{:keys [ast errors]} (api/parse-string text)]
    (if (seq errors)
      {:status :parse-error :errors errors}
      (let [{:keys [pickles]} (api/pickles ast "repl://")
            stepdefs (registry/all-stepdefs)
            {:keys [plans runnable? diagnostics]} (bind/bind-suite pickles stepdefs)]
        (if runnable?
          {:status :ok :plans plans :diagnostics diagnostics}
          {:status :bind-error :diagnostics diagnostics})))))

(defn run
  "Parse and execute Gherkin text.

   Assumes step definitions are already loaded via `defstep`.

   Returns:
   - {:status :ok :results {...} :summary {...}}
   - {:status :parse-error :errors [...]}
   - {:status :bind-error :diagnostics {...}}

   Example:
   ```clojure
   (run \"
     Feature: Math
       Scenario: Addition
         Given I have 5
         When I add 3
         Then I get 8\")
   ```"
  [text]
  (let [{:keys [ast errors]} (api/parse-string text)]
    (if (seq errors)
      {:status :parse-error :errors errors}
      (let [{:keys [pickles]} (api/pickles ast "repl://")
            stepdefs (registry/all-stepdefs)
            {:keys [plans runnable? diagnostics]} (bind/bind-suite pickles stepdefs)]
        (if-not runnable?
          {:status :bind-error :diagnostics diagnostics}
          (let [results (exec/execute-suite plans)]
            {:status :ok
             :results results
             :summary {:scenarios (count (:scenarios results))
                       :passed (count (filter #(= :passed (:status %)) (:scenarios results)))
                       :failed (count (filter #(= :failed (:status %)) (:scenarios results)))
                       :pending (count (filter #(= :pending (:status %)) (:scenarios results)))}}))))))

(defn clear!
  "Clear the step registry, session context, and named contexts.

   Useful when redefining steps in REPL."
  []
  (registry/clear-registry!)
  (reset-ctx!)
  (reset-ctxs!)
  :cleared)

;; -----------------------------------------------------------------------------
;; Convenience
;; -----------------------------------------------------------------------------

(defn steps
  "List all registered step patterns."
  []
  (mapv :pattern-src (registry/all-stepdefs)))

;; -----------------------------------------------------------------------------
;; Free Mode (no Feature/Scenario structure required)
;; -----------------------------------------------------------------------------

(defn step
  "Execute a single step in free mode.

   No Feature/Scenario structure required — just provide step text.
   Context accumulates across calls within a session.

   Returns:
   - {:status :passed :ctx {...}}
   - {:status :failed :error {...} :ctx {...}}
   - {:status :pending :ctx {...}}
   - {:status :undefined :text \"...\"}
   - {:status :ambiguous :text \"...\" :matches [...]}

   Example:
   ```clojure
   (step \"I have 12 cucumbers\")
   (step \"I eat 5\")
   (step \"I should have 7 left\")

   ;; Check accumulated context
   (ctx)

   ;; Reset for new session
   (reset-ctx!)
   ```"
  [text]
  (let [stepdefs (registry/all-stepdefs)
        matches (keep #(bind/match-step text %) stepdefs)]
    (case (count matches)
      0 {:status :undefined :text text}

      1 (let [{:keys [stepdef captures]} (first matches)
              current-ctx @session-ctx
              step-map {:step/text text}
              exec-ctx {:step step-map :scenario current-ctx}
              result (exec/invoke-step {:fn (:fn stepdef)
                                        :arity (:arity stepdef)
                                        :captures captures}
                                       captures
                                       exec-ctx)]
          (when (= :passed (:status result))
            (swap! session-ctx merge (:scenario result)))
          {:status (:status result)
           :ctx @session-ctx
           :error (:error result)})

      ;; 2+ matches = ambiguous
      {:status :ambiguous
       :text text
       :matches (mapv #(-> % :stepdef :pattern-src) matches)})))

(defn- execute-step-in-context
  "Execute a step with a given context, returning updated context and result."
  [text current-ctx]
  (let [stepdefs (registry/all-stepdefs)
        matches (keep #(bind/match-step text %) stepdefs)]
    (case (count matches)
      0 {:status :undefined :text text :ctx current-ctx}

      1 (let [{:keys [stepdef captures]} (first matches)
              step-map {:step/text text}
              exec-ctx {:step step-map :scenario current-ctx}
              result (exec/invoke-step {:fn (:fn stepdef)
                                        :arity (:arity stepdef)
                                        :captures captures}
                                       captures
                                       exec-ctx)
              new-ctx (if (= :passed (:status result))
                        (or (:scenario result) current-ctx)
                        current-ctx)]
          {:status (:status result)
           :ctx new-ctx
           :error (:error result)})

      {:status :ambiguous
       :text text
       :ctx current-ctx
       :matches (mapv #(-> % :stepdef :pattern-src) matches)})))

(defn free
  "Execute steps in a named context (multi-actor sessions).

   Each named context maintains its own accumulated state,
   allowing simulation of multiple users/actors.

   Parameters:
   - session-name: keyword identifying the session (e.g., :alice, :bob)
   - steps: one or more step text strings

   Returns result of last step, with :session and :ctx keys.

   Example:
   ```clojure
   (free :alice \"I log in as alice\")
   (free :bob \"I log in as bob\")
   (free :alice \"I create a post\")
   (free :bob \"I see alice's post\")

   ;; Check all contexts
   (ctxs)
   ;; => {:alice {:logged-in true :posts [...]} :bob {:logged-in true}}

   ;; Check specific context
   (ctx :alice)
   ;; => {:logged-in true :posts [...]}
   ```"
  [session-name & steps]
  (loop [remaining steps
         current-ctx (get @named-contexts session-name {})
         last-result nil]
    (if (empty? remaining)
      ;; Return last result (or ok if no steps)
      (or last-result {:status :ok :session session-name :ctx current-ctx})
      (let [step-text (first remaining)
            result (execute-step-in-context step-text current-ctx)
            new-ctx (merge current-ctx (:ctx result))]
        (swap! named-contexts assoc session-name new-ctx)
        (if (= :passed (:status result))
          (recur (rest remaining) new-ctx (assoc result :session session-name :ctx new-ctx))
          ;; Stop on first non-passing step
          (assoc result :session session-name :ctx new-ctx))))))
