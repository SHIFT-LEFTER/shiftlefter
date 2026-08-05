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

   ;; Or use step mode (no Feature/Scenario wrapper needed)
   (repl/step \"I have 12 cucumbers\")
   (repl/step \"I eat 5\")
   (repl/step \"I should have 7 left\")
   ```

   ## Functions

   ### Structured Mode (requires Feature/Scenario)
   - `run`        — parse and execute Gherkin text
   - `run-dry`    — parse and bind only (no execution)
   - `parse-only` — just parse, return pickles

   ### Step Mode (steps only, no structure)
   - `step`       — execute a single step, maintains session context
   - `as`         — execute steps in named context (multi-actor sessions)
   - `ctx`        — inspect session context (or named context)
   - `ctxs`       — inspect all named contexts
   - `set-ctx!`   — set a named context to a value (e.g., attach browser)
   - `reset-ctx!` — reset session context to empty
   - `reset-ctxs!`— reset all named contexts

   ### Costumes (Chrome-owned authenticated sessions that survive JVM restarts)
   - `init-costume!`    — create new costume
   - `connect-costume!` — reconnect to existing costume
   - `destroy-costume!` — kill Chrome and delete costume
   - `list-costumes`    — list all costumes with status

   ### Utilities
   - `steps`      — list registered step patterns
   - `clear!`     — clear step registry and contexts"
  (:require [shiftlefter.gherkin.api :as api]
            [shiftlefter.stepengine.registry :as registry]
            [shiftlefter.stepengine.bind :as bind]
            [shiftlefter.stepengine.compile :as compile]
            [shiftlefter.stepengine.exec :as exec]
            [shiftlefter.runner.config :as config]
            [shiftlefter.svo.glossary :as glossary]
            [shiftlefter.capabilities.ctx :as cap]
            [shiftlefter.costume :as costume]
            [shiftlefter.costume.browser :as cbrowser]
            [shiftlefter.webdriver.etaoin.browser :as ebrowser]
            [shiftlefter.webdriver.etaoin.session :as session]))

;; -----------------------------------------------------------------------------
;; Session Context (for step mode)
;; -----------------------------------------------------------------------------

(defonce ^:private session-ctx (atom {}))
(defonce ^:private named-contexts (atom {}))
(defonce ^:private connected-subjects (atom {}))

;; -----------------------------------------------------------------------------
;; Shifted Mode (SVO validation toggle)
;; -----------------------------------------------------------------------------

(defonce ^:private repl-config (atom nil))

;; Forward declarations for lifecycle functions
(declare cleanup-all-contexts!)

(defn ctx
  "Get the current session context (or a named context).

   In step mode, context accumulates across step calls.
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
  "Reset all named contexts, closing each context's browser sessions first.

   Persistent (costume) capabilities are deliberately skipped — a costume's
   Chrome outlives the REPL session; use `destroy-costume!` to kill one.

   Returns a vector of cleanup actions, one per :web capability:
   :closed, :skipped-persistent, :close-failed (with :error), or :none."
  []
  (let [actions (cleanup-all-contexts!)]
    (reset! named-contexts {})
    actions))

(defn set-ctx!
  "Set a named context to a specific value.

   Use this to attach a browser or other state to a context before running steps.

   Example:
   ```clojure
   (require '[shiftlefter.capabilities.ctx :as cap])

   (def my-browser ...)
   (set-ctx! :alice (cap/assoc-capability {} :web my-browser :ephemeral :alice))

   ;; Now steps can use the browser
   (as :alice \"opens the browser to 'https://example.com'\")
   ```"
  [ctx-name ctx-value]
  (swap! named-contexts assoc ctx-name ctx-value)
  ctx-value)

;; -----------------------------------------------------------------------------
;; Shifted Mode API
;; -----------------------------------------------------------------------------

(defn shifted?
  "Returns true if REPL is in Shifted mode (SVO validation enabled)."
  []
  (some? @repl-config))

(defn shifted!
  "Enable Shifted mode with SVO validation.

   With no argument, loads config from `shiftlefter.edn`.
   With a config map, uses that config directly.

   In Shifted mode:
   - `run` and `run-dry` use SVO validation
   - `as` warns on unknown actor names

   Returns:
   - {:status :ok :config {...}} on success
   - {:status :error ...} if config loading fails

   Example:
   ```clojure
   ;; Load from shiftlefter.edn
   (shifted!)

   ;; Use custom config
   (shifted! {:svo {:unknown-subject :warn}
              :glossaries {:subjects \"my/subjects.edn\"}})

   ;; Check mode
   (shifted?) ;; => true

   ;; Back to vanilla
   (vanilla!)
   ```"
  ([]
   (let [result (config/load-config-safe)]
     (if (= :ok (:status result))
       (do
         (reset! repl-config (:config result))
         {:status :ok :config (:config result)})
       result)))
  ([cfg]
   (if-not (map? cfg)
     {:status :error
      :type :repl/invalid-input
      :message (str "Expected a config map, got " (if (nil? cfg) "nil" (type cfg)))}
     (do
       (reset! repl-config cfg)
       {:status :ok :config cfg}))))

(defn vanilla!
  "Disable Shifted mode, returning to vanilla mode.

   In vanilla mode, SVO validation is disabled.
   Step execution proceeds without subject/verb/interface checks."
  []
  (reset! repl-config nil)
  :vanilla)

(defn get-repl-config
  "Get the current REPL config (nil if vanilla mode)."
  []
  @repl-config)

(def config
  "Alias for `get-repl-config`. Returns current REPL config (nil if vanilla mode)."
  get-repl-config)

(defn glossary
  "Get the loaded glossary for the current Shifted mode config.

   Returns the merged glossary map (defaults + project glossaries),
   or nil if not in Shifted mode or no glossaries configured.

   Example:
   ```clojure
   (shifted! {:glossaries {:subjects \"path/to/subjects.edn\"}})
   (glossary)
   ;; => {:subjects {:alice {...} :admin {...}} :verbs {:web {...}}}
   ```"
  []
  (when-let [cfg @repl-config]
    (when-let [glossary-config (:glossaries cfg)]
      (glossary/load-all-glossaries glossary-config))))

(defn subjects
  "Get known subject names from the glossary.

   Returns a vector of subject keywords, or nil if not in Shifted mode.

   Example:
   ```clojure
   (shifted! {:glossaries {:subjects \"path/to/subjects.edn\"}})
   (subjects)
   ;; => [:alice :admin :guest]
   ```"
  []
  (when-let [g (glossary)]
    (glossary/known-subjects g)))

(defn verbs
  "Get known verb names from the glossary.

   With no argument, returns a map of all interfaces to their verbs.
   With an interface-type argument, returns verbs for that interface.

   Returns nil if not in Shifted mode.

   Example:
   ```clojure
   (shifted! {:glossaries {:verbs {:web \"path/to/verbs-web.edn\"}}})
   (verbs)
   ;; => {:web [:click :fill :see]}
   (verbs :web)
   ;; => [:click :fill :see]
   ```"
  ([]
   (when-let [g (glossary)]
     (into {} (map (fn [[iface _]] [iface (glossary/known-verbs g iface)])
                   (:verbs g)))))
  ([interface-type]
   (when-let [g (glossary)]
     (glossary/known-verbs g interface-type))))

(defn interfaces
  "Get known interface types from the glossary.

   Returns a vector of interface keywords (e.g., [:web :api]),
   or nil if not in Shifted mode.

   Example:
   ```clojure
   (shifted! {:glossaries {:verbs {:web \"...\" :api \"...\"}}})
   (interfaces)
   ;; => [:web :api]
   ```"
  []
  (when-let [g (glossary)]
    (vec (keys (:verbs g)))))

;; -----------------------------------------------------------------------------
;; Browser Lifecycle (internal)
;; -----------------------------------------------------------------------------

(defn- unwrap-driver
  "Reach the closable raw driver map inside a :web capability impl:
   CostumeBrowser → its current underlying EtaoinBrowser → the raw driver
   map. Plain driver maps pass through unchanged. The session id lives
   inside the driver map, which is why closing a record directly used to
   no-op (sl-tss7)."
  [impl]
  (cond
    (instance? shiftlefter.costume.browser.CostumeBrowser impl)
    (recur (cbrowser/underlying-browser impl))

    (instance? shiftlefter.webdriver.etaoin.browser.EtaoinBrowser impl)
    (ebrowser/etaoin-driver impl)

    :else impl))

(defn- close-context-browsers!
  "Close the :web browser sessions of a named context (sl-tss7).

   Mode-aware: a `:persistent` capability (a worn costume) is deliberately
   SKIPPED — a costume's Chrome outlives the REPL session, and only
   `destroy-costume!` kills it. Everything else is unwrapped to its raw
   driver map and actually closed; a failed close is reported as such,
   never as :closed.

   Returns a vector of action maps, one per :web capability:
   {:action :closed|:skipped-persistent|:close-failed :ctx-name .. :capability ..}
   (+ :error on :close-failed); [{:action :none :ctx-name ..}] when the ctx
   has no :web capability."
  [ctx-name ctx-value]
  (let [entries (->> (cap/all-capabilities ctx-value)
                     (filter (fn [[k _]]
                               (= :web (:interface (cap/parse-capability-name k))))))]
    (if (empty? entries)
      [{:action :none :ctx-name ctx-name}]
      (mapv (fn [[k {:keys [impl mode]}]]
              (if (= :persistent mode)
                {:action :skipped-persistent :ctx-name ctx-name :capability k}
                (let [r (session/close-session! (unwrap-driver impl))]
                  (if (:error r)
                    {:action :close-failed :ctx-name ctx-name :capability k
                     :error (:error r)}
                    {:action :closed :ctx-name ctx-name :capability k}))))
            entries))))

(defn- cleanup-all-contexts!
  "Clean up browser sessions for all named contexts.

   Returns a vector of action results (one per :web capability per ctx)."
  []
  (let [all-ctxs @named-contexts]
    (into []
          (mapcat (fn [[ctx-name ctx-value]]
                    (close-context-browsers! ctx-name ctx-value)))
          all-ctxs)))

;; -----------------------------------------------------------------------------
;; Core Functions
;; -----------------------------------------------------------------------------

(defn parse-only
  "Parse Gherkin text and return pickles.

   Returns:
   - {:status :ok :pickles [...]}
   - {:status :parse-error :errors [...]}
   - {:status :error :type :repl/invalid-input ...} if text is not a string"
  [text]
  (if-not (string? text)
    {:status :error
     :type :repl/invalid-input
     :message (str "Expected a string, got " (if (nil? text) "nil" (type text)))}
    (let [{:keys [ast errors]} (api/parse-string text)]
      (if (seq errors)
        {:status :parse-error :errors errors}
        (let [{:keys [pickles]} (api/pickles ast "repl://")]
          {:status :ok :pickles pickles})))))

(defn run-dry
  "Parse and bind Gherkin text without executing.

   Useful for checking step definitions match.

   In Shifted mode (see `shifted!`), SVO validation is performed.

   Returns:
   - {:status :ok :plans [...] :diagnostics {...}}
   - {:status :parse-error :errors [...]}
   - {:status :bind-error :diagnostics {...}}
   - {:status :error :type :repl/invalid-input ...} if text is not a string"
  [text]
  (if-not (string? text)
    {:status :error
     :type :repl/invalid-input
     :message (str "Expected a string, got " (if (nil? text) "nil" (type text)))}
    (let [{:keys [ast errors]} (api/parse-string text)]
      (if (seq errors)
        {:status :parse-error :errors errors}
        (let [{:keys [pickles]} (api/pickles ast "repl://")
              stepdefs (registry/all-stepdefs)
              config @repl-config
              {:keys [plans runnable? diagnostics]}
              (if config
                ;; Shifted mode: use compile-suite for full validation
                (compile/compile-suite config pickles stepdefs)
                ;; Vanilla mode: direct bind
                (bind/bind-suite pickles stepdefs))]
          (if runnable?
            {:status :ok :plans plans :diagnostics diagnostics}
            {:status :bind-error :diagnostics diagnostics}))))))

(defn run
  "Parse and execute Gherkin text.

   Assumes step definitions are already loaded via `defstep`.

   In Shifted mode (see `shifted!`), SVO validation is performed.

   Returns:
   - {:status :ok :results {...} :summary {...}}
   - {:status :parse-error :errors [...]}
   - {:status :bind-error :diagnostics {...}}
   - {:status :error :type :repl/invalid-input ...} if text is not a string

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
  (if-not (string? text)
    {:status :error
     :type :repl/invalid-input
     :message (str "Expected a string, got " (if (nil? text) "nil" (type text)))}
    (let [{:keys [ast errors]} (api/parse-string text)]
      (if (seq errors)
        {:status :parse-error :errors errors}
        (let [{:keys [pickles]} (api/pickles ast "repl://")
              stepdefs (registry/all-stepdefs)
              config @repl-config
              {:keys [plans runnable? diagnostics]}
              (if config
                ;; Shifted mode: use compile-suite for full validation
                (compile/compile-suite config pickles stepdefs)
                ;; Vanilla mode: direct bind
                (bind/bind-suite pickles stepdefs))]
          (if-not runnable?
            {:status :bind-error :diagnostics diagnostics}
            (let [results (exec/execute-suite plans)]
              {:status :ok
               :results results
               :diagnostics diagnostics  ; Include any SVO warnings
               :summary {:scenarios (count (:scenarios results))
                         :passed (count (filter #(= :passed (:status %)) (:scenarios results)))
                         :failed (count (filter #(= :failed (:status %)) (:scenarios results)))
                         :pending (count (filter #(= :pending (:status %)) (:scenarios results)))}})))))))

(defn clear!
  "Clear the step registry, session context, and named contexts (closing
   their ephemeral browser sessions; persistent/costume capabilities are
   skipped).

   Note: Does NOT destroy costumes. Use `destroy-costume!` for that.

   Useful when redefining steps in REPL."
  []
  (registry/clear-registry!)
  (reset-ctx!)
  (reset-ctxs!)  ; This now handles browser cleanup
  (reset! connected-subjects {})
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
  "Execute a single step in step mode.

   No Feature/Scenario structure required — just provide step text.
   Context accumulates across calls within a session.

   Returns:
   - {:status :passed :ctx {...}}
   - {:status :failed :error {...} :ctx {...}}
   - {:status :pending :ctx {...}}
   - {:status :undefined :text \"...\"}
   - {:status :ambiguous :text \"...\" :matches [...]}
   - {:status :error :type :repl/invalid-input ...} if text is not a string

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
  (if-not (string? text)
    {:status :error
     :type :repl/invalid-input
     :message (str "Expected a string, got " (if (nil? text) "nil" (type text)))}
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
         :matches (mapv #(-> % :stepdef :pattern-src) matches)}))))

(defn- validate-actor
  "In Shifted mode, validate actor name against subject glossary.

   Returns:
   - nil if valid (known subject, no config, or warn mode with warning printed)
   - {:status :error :type :svo/unknown-subject ...} if invalid in error mode

   In :warn mode, prints warning but returns nil (allows execution).
   In :error mode, returns error map (blocks execution)."
  [ctx-name]
  (when-let [config @repl-config]
    (when (contains? config :svo)
      (when-let [glossary-config (:glossaries config)]
        (let [glossary (glossary/load-all-glossaries glossary-config)
              mode (get-in config [:svo :unknown-subject] :warn)]
          (when-not (glossary/known-subject? glossary ctx-name)
            (let [known (glossary/known-subjects glossary)
                  msg (str ":" (name ctx-name) " not in subject glossary. Known: " (pr-str known))]
              (case mode
                :error {:status :error
                        :type :svo/unknown-subject
                        :message msg
                        :subject ctx-name
                        :known-subjects known}
                ;; :warn or default
                (do (binding [*out* *err*]
                      (println (str "WARNING: " msg)))
                    nil)))))))))

(defn- inject-subject-browser
  "If ctx-name matches a connected persistent subject, inject its browser into ctx.

   Stored under the subject-keyed slot `:cap/web.<ctx-name>` so subject-routed
   step lookups (e.g. `(repl/as :alice \"clicks Login.submit\")`) can find it.
   Only injects if no `:web` capability is already present for this subject."
  [ctx-name current-ctx]
  (if (cap/get-capability current-ctx :web ctx-name)
    current-ctx
    (if-let [subject-info (get @connected-subjects ctx-name)]
      (cap/assoc-capability current-ctx :web (:browser subject-info) :persistent ctx-name)
      current-ctx)))

(defn- execute-step-in-context
  "Execute a step with a given context, returning updated context and result.
   If ctx-name matches a connected persistent subject, its browser is injected."
  [text current-ctx ctx-name]
  (let [ctx-with-browser (inject-subject-browser ctx-name current-ctx)
        stepdefs (registry/all-stepdefs)
        matches (keep #(bind/match-step text %) stepdefs)]
    (case (count matches)
      0 {:status :undefined :text text :ctx current-ctx}

      1 (let [{:keys [stepdef captures]} (first matches)
              step-map {:step/text text}
              exec-ctx {:step step-map :scenario ctx-with-browser}
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

   Automatically prepends `:ctx-name` to each step text, so the step
   matches subject-extracting patterns. For example:

   ```clojure
   (as :alice \"opens the browser to 'https://example.com'\")
   ```

   becomes step text `:alice opens the browser to 'https://example.com'`
   which matches the built-in browser step pattern.

   If the step text already starts with `:ctx-name`, no prepending occurs
   (idempotent — you can also write the full step text explicitly).

   In Shifted mode with `:unknown-subject :warn`, warns but proceeds.
   In Shifted mode with `:unknown-subject :error`, returns error without executing.

   Parameters:
   - ctx-name: keyword identifying the context (e.g., :alice, :bob)
   - steps: one or more step text strings

   Returns result of last step, with :session and :ctx keys.
   Stops on first non-passing step (fail-fast).

   Example:
   ```clojure
   (as :alice \"opens the browser to 'https://example.com'\")
   (as :alice \"clicks {:id \\\"login\\\"}\")
   (as :alice \"should see 'Welcome'\")

   ;; Also works with explicit subject (idempotent):
   (as :alice \":alice opens the browser to 'https://example.com'\")

   ;; Check all contexts
   (ctxs)

   ;; Check specific context
   (ctx :alice)
   ```"
  [ctx-name & steps]
  (if-let [validation-error (validate-actor ctx-name)]
    ;; Actor validation failed in error mode — don't execute
    (assoc validation-error :session ctx-name :ctx (get @named-contexts ctx-name {}))
    ;; Actor valid or warn mode — proceed with execution
    (let [subject-prefix (str ctx-name " ")]
      (loop [remaining steps
             current-ctx (get @named-contexts ctx-name {})
             last-result nil]
        (if (empty? remaining)
          ;; Return last result (or ok if no steps)
          (or last-result {:status :ok :session ctx-name :ctx current-ctx})
          (let [raw-text (first remaining)
                ;; Prepend :subject if not already present (idempotent).
                ;; repl/as is shifted-mode: every step gets a subject.
                step-text (if (.startsWith raw-text subject-prefix)
                            raw-text
                            (str subject-prefix raw-text))
                result (execute-step-in-context step-text current-ctx ctx-name)
                new-ctx (merge current-ctx (:ctx result))]
            (swap! named-contexts assoc ctx-name new-ctx)
            (if (= :passed (:status result))
              (recur (rest remaining) new-ctx (assoc result :session ctx-name :ctx new-ctx))
              ;; Stop on first non-passing step
              (assoc result :session ctx-name :ctx new-ctx))))))))

;; -----------------------------------------------------------------------------
;; Costumes (re-exports from shiftlefter.costume)
;; -----------------------------------------------------------------------------

(defn init-costume!
  "Initialize a new costume (launch-only).

   Launches a PRISTINE Chrome instance with its own profile for the human to log
   into. It does NOT attach a WebDriver session — call `connect-costume!` after
   logging in to attach and get a live browser.

   Options:
   - `:chrome-path` — explicit Chrome binary path (default: auto-detect)

   Returns:
   - Success: `{:status :launched :costume <name> :port <int> :pid <long>}`
   - Error: `{:error {:type :costume/... ...}}`

   Example:
   ```clojure
   (init-costume! :finance)
   ;; => {:status :launched :costume :finance :port 9222 :pid 12345}
   ;; Log in, then (connect-costume! :finance) to attach.
   ```

   See `shiftlefter.costume/init-costume!` for full documentation."
  ([costume-name]
   (init-costume! costume-name {}))
  ([costume-name opts]
   ;; Launch-only: nothing to register in connected-subjects until connect-costume!.
   (costume/init-costume! costume-name opts)))

(defn connect-costume!
  "Connect to an existing costume.

   Use this after JVM restart to reconnect to a Chrome instance that was
   previously initialized with `init-costume!`.

   If Chrome is still running, creates a new WebDriver session.
   If Chrome died, relaunches it automatically.

   Returns:
   - Success: `{:status :connected :costume <name> :port <int> :pid <long> :browser <CostumeBrowser>}`
   - Error: `{:error {:type :costume/... ...}}`

   Example:
   ```clojure
   ;; After JVM restart
   (connect-costume! :finance)
   ;; => {:status :connected :costume :finance :port 9222 ...}
   ```

   See `shiftlefter.costume/connect-costume!` for full documentation."
  [costume-name]
  (let [result (costume/connect-costume! costume-name)]
    (when (= :connected (:status result))
      (swap! connected-subjects assoc costume-name {:browser (:browser result)}))
    result))

(defn destroy-costume!
  "Destroy a costume.

   Kills the Chrome process and deletes the entire costume directory
   (including Chrome user data like cookies, history, etc.).

   Returns:
   - Success: `{:status :destroyed :costume <name>}`
   - Error: `{:error {:type :costume/not-found ...}}`

   Example:
   ```clojure
   (destroy-costume! :finance)
   ;; => {:status :destroyed :costume :finance}
   ```

   See `shiftlefter.costume/destroy-costume!` for full documentation."
  [costume-name]
  (let [result (costume/destroy-costume! costume-name)]
    (when (= :destroyed (:status result))
      (swap! connected-subjects dissoc costume-name))
    result))

(defn list-costumes
  "List all costumes with their status.

   Returns a vector of costume info maps:
   ```clojure
   [{:name \"finance\"
     :status :alive      ; or :dead, :unknown
     :port 9222
     :pid 12345
     :meta {...}}]
   ```

   Example:
   ```clojure
   (list-costumes)
   ;; => [{:name \"finance\" :status :alive :port 9222 ...}]
   ```

   See `shiftlefter.costume/list-costumes` for full documentation."
  []
  (costume/list-costumes))
