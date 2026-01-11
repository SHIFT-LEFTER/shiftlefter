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
   - `as`         — execute steps in named context (multi-actor sessions)
   - `free`       — alias for `as` (compatibility)
   - `ctx`        — inspect session context (or named context)
   - `ctxs`       — inspect all named contexts
   - `set-ctx!`   — set a named context to a value (e.g., attach browser)
   - `reset-ctx!` — reset session context to empty
   - `reset-ctxs!`— reset all named contexts

   ### Surfaces (opt-in browser session persistence)
   - `mark-surface!`   — mark context as surface (persist on reset)
   - `unmark-surface!` — unmark context as surface
   - `surface?`        — check if context is marked as surface
   - `list-surfaces`   — list all marked surfaces
   - `reset-surfaces!` — clear all surface markings

   ### Persistent Subjects (Chrome-owned sessions that survive JVM restarts)
   - `init-persistent-subject!`    — create new persistent subject
   - `connect-persistent-subject!` — reconnect to existing subject
   - `destroy-persistent-subject!` — kill Chrome and delete profile
   - `list-persistent-subjects`    — list all subjects with status

   ### Utilities
   - `steps`      — list registered step patterns
   - `clear!`     — clear step registry, contexts, and surfaces"
  (:require [shiftlefter.gherkin.api :as api]
            [shiftlefter.stepengine.registry :as registry]
            [shiftlefter.stepengine.bind :as bind]
            [shiftlefter.stepengine.exec :as exec]
            [shiftlefter.browser.ctx :as browser.ctx]
            [shiftlefter.subjects :as subjects]
            [shiftlefter.webdriver.etaoin.session :as session]
            [shiftlefter.webdriver.session-store :as store]))

;; -----------------------------------------------------------------------------
;; Session Context (for free mode)
;; -----------------------------------------------------------------------------

(defonce ^:private session-ctx (atom {}))
(defonce ^:private named-contexts (atom {}))
(defonce ^:private surfaces (atom #{}))

;; Session store for surface persistence (can be overridden for testing)
(defonce ^:dynamic *session-store* (delay (store/make-edn-store)))

;; Forward declarations for lifecycle functions
(declare cleanup-all-contexts! surface?)

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
  "Reset all named contexts.

   Before resetting, handles browser session cleanup:
   - Non-surface contexts: browser session is closed
   - Surface contexts: browser session is detached and persisted

   Returns vector of cleanup actions taken."
  []
  (let [actions (cleanup-all-contexts!)]
    (reset! named-contexts {})
    actions))

(defn set-ctx!
  "Set a named context to a specific value.

   Use this to attach a browser or other state to a context before running steps.

   Example:
   ```clojure
   (require '[shiftlefter.browser.ctx :as browser.ctx])

   (def my-browser ...)
   (set-ctx! :alice (browser.ctx/assoc-active-browser {} my-browser))

   ;; Now steps can use the browser
   (as :alice \"I open the browser to 'https://example.com'\")
   ```"
  [ctx-name ctx-value]
  (swap! named-contexts assoc ctx-name ctx-value)
  ctx-value)

;; -----------------------------------------------------------------------------
;; Surface Management
;; -----------------------------------------------------------------------------

(defn mark-surface!
  "Mark a context name as a surface (opt-in session persistence).

   Surfaces are browser sessions that persist across REPL reset/clear.
   When a surface ctx is reset, the session is detached (not closed)
   and persisted to disk for later reattachment.

   Note: Only named contexts can be surfaces.
   Note: Multi-process attach is not supported (no locking).

   Example:
   ```clojure
   (mark-surface! :alice)
   (as :alice \"I open the browser to 'https://example.com'\")
   (reset-ctxs!)  ; session persisted, not closed
   ```"
  [ctx-name]
  (swap! surfaces conj ctx-name)
  :marked)

(defn unmark-surface!
  "Unmark a context name as a surface.

   After unmarking, the context will behave normally:
   reset/clear will close browser sessions instead of persisting them."
  [ctx-name]
  (swap! surfaces disj ctx-name)
  :unmarked)

(defn surface?
  "Returns true if the context name is marked as a surface."
  [ctx-name]
  (contains? @surfaces ctx-name))

(defn list-surfaces
  "List all context names currently marked as surfaces."
  []
  (vec @surfaces))

(defn reset-surfaces!
  "Clear all surface markings."
  []
  (reset! surfaces #{}))

;; -----------------------------------------------------------------------------
;; Browser Lifecycle (internal)
;; -----------------------------------------------------------------------------

(defn- close-or-persist-browser!
  "Handle browser session cleanup for a named context.

   - If ctx has browser and IS a surface → detach + save handle to store
   - If ctx has browser and NOT a surface → close session

   Returns {:action :closed|:persisted|:none, ...} for observability."
  [ctx-name ctx-value]
  (if-not (browser.ctx/browser-present? ctx-value)
    {:action :none :ctx-name ctx-name}
    (let [browser (browser.ctx/get-active-browser ctx-value)
          is-surface? (surface? ctx-name)]
      (if is-surface?
        ;; Surface: detach + persist
        (let [handle {:webdriver-url (:webdriver-url browser)
                      :session-id (:session browser)}
              store @*session-store*]
          (store/save-session-handle! store ctx-name handle)
          {:action :persisted :ctx-name ctx-name :handle handle})
        ;; Non-surface: close session
        (do
          (session/close-session! browser)
          {:action :closed :ctx-name ctx-name})))))

(defn- cleanup-all-contexts!
  "Clean up browser sessions for all named contexts.

   Returns a vector of action results."
  []
  (let [all-ctxs @named-contexts]
    (mapv (fn [[ctx-name ctx-value]]
            (close-or-persist-browser! ctx-name ctx-value))
          all-ctxs)))

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
  "Clear the step registry, session context, named contexts, and surface markings.

   Before clearing, handles browser session cleanup:
   - Non-surface contexts: browser session is closed
   - Surface contexts: browser session is detached and persisted

   Useful when redefining steps in REPL."
  []
  (registry/clear-registry!)
  (reset-ctx!)
  (reset-ctxs!)  ; This now handles browser cleanup
  (reset-surfaces!)
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

(defn as
  "Execute steps in a named context; fail-fast per call.

   Each named context maintains its own accumulated state,
   allowing simulation of multiple users/actors.

   Parameters:
   - ctx-name: keyword identifying the context (e.g., :alice, :bob)
   - steps: one or more step text strings

   Returns result of last step, with :session and :ctx keys.
   Stops on first non-passing step (fail-fast).

   Example:
   ```clojure
   (as :alice \"I log in as alice\")
   (as :bob \"I log in as bob\")
   (as :alice \"I create a post\")
   (as :bob \"I see alice's post\")

   ;; Check all contexts
   (ctxs)
   ;; => {:alice {:logged-in true :posts [...]} :bob {:logged-in true}}

   ;; Check specific context
   (ctx :alice)
   ;; => {:logged-in true :posts [...]}
   ```"
  [ctx-name & steps]
  (loop [remaining steps
         current-ctx (get @named-contexts ctx-name {})
         last-result nil]
    (if (empty? remaining)
      ;; Return last result (or ok if no steps)
      (or last-result {:status :ok :session ctx-name :ctx current-ctx})
      (let [step-text (first remaining)
            result (execute-step-in-context step-text current-ctx)
            new-ctx (merge current-ctx (:ctx result))]
        (swap! named-contexts assoc ctx-name new-ctx)
        (if (= :passed (:status result))
          (recur (rest remaining) new-ctx (assoc result :session ctx-name :ctx new-ctx))
          ;; Stop on first non-passing step
          (assoc result :session ctx-name :ctx new-ctx))))))

(def free
  "Alias for `as`. See `as` docstring."
  as)

;; -----------------------------------------------------------------------------
;; Persistent Subjects (re-exports from shiftlefter.subjects)
;; -----------------------------------------------------------------------------

(defn init-persistent-subject!
  "Initialize a new persistent browser subject.

   Creates a Chrome instance with its own profile that survives JVM restarts.
   The browser auto-reconnects on session errors (e.g., after sleep/wake).

   Options:
   - `:stealth` — if true, use anti-detection flags (default: false)
   - `:chrome-path` — explicit Chrome binary path (default: auto-detect)

   Returns:
   - Success: `{:status :connected :subject <name> :port <int> :pid <long> :browser <PersistentBrowser>}`
   - Error: `{:error {:type :subject/... ...}}`

   Example:
   ```clojure
   (init-persistent-subject! :finance {:stealth true})
   ;; => {:status :connected :subject :finance :port 9222 ...}
   ```

   See `shiftlefter.subjects/init-persistent!` for full documentation."
  ([subject-name]
   (subjects/init-persistent! subject-name))
  ([subject-name opts]
   (subjects/init-persistent! subject-name opts)))

(defn connect-persistent-subject!
  "Connect to an existing persistent subject.

   Use this after JVM restart to reconnect to a Chrome instance that was
   previously initialized with `init-persistent-subject!`.

   If Chrome is still running, creates a new WebDriver session.
   If Chrome died, relaunches it automatically.

   Returns:
   - Success: `{:status :connected :subject <name> :port <int> :pid <long> :browser <PersistentBrowser>}`
   - Error: `{:error {:type :subject/... ...}}`

   Example:
   ```clojure
   ;; After JVM restart
   (connect-persistent-subject! :finance)
   ;; => {:status :connected :subject :finance :port 9222 ...}
   ```

   See `shiftlefter.subjects/connect-persistent!` for full documentation."
  [subject-name]
  (subjects/connect-persistent! subject-name))

(defn destroy-persistent-subject!
  "Destroy a persistent subject.

   Kills the Chrome process and deletes the entire profile directory
   (including Chrome user data like cookies, history, etc.).

   Returns:
   - Success: `{:status :destroyed :subject <name>}`
   - Error: `{:error {:type :subject/not-found ...}}`

   Example:
   ```clojure
   (destroy-persistent-subject! :finance)
   ;; => {:status :destroyed :subject :finance}
   ```

   See `shiftlefter.subjects/destroy-persistent!` for full documentation."
  [subject-name]
  (subjects/destroy-persistent! subject-name))

(defn list-persistent-subjects
  "List all persistent subjects with their status.

   Returns a vector of subject info maps:
   ```clojure
   [{:name \"finance\"
     :status :alive      ; or :dead, :unknown
     :port 9222
     :pid 12345
     :meta {...}}]
   ```

   Example:
   ```clojure
   (list-persistent-subjects)
   ;; => [{:name \"finance\" :status :alive :port 9222 ...}]
   ```

   See `shiftlefter.subjects/list-persistent` for full documentation."
  []
  (subjects/list-persistent))
