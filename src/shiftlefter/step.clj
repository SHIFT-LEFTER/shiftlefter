(ns shiftlefter.step
  "Helper functions for accessing step metadata from ctx.

   Step metadata (DataTable, DocString, step text, keyword) is stored in
   Clojure metadata on the ctx map, not in the ctx itself. This keeps the
   ctx clean and focused on scenario state.

   ## Usage

   ```clojure
   (require '[shiftlefter.step :as step])
   (require '[shiftlefter.stepengine.registry :refer [defstep]])

   ;; Access DataTable in a step
   (defstep #\"the following users exist:\" [ctx]
     (when-let [table (step/arguments ctx)]
       (doseq [[name email] (rest (:rows table))]
         (create-user! name email)))
     ctx)

   ;; Access step text (for debugging/logging)
   (defstep #\"I do something\" [ctx]
     (println \"Executing:\" (step/text ctx))
     ctx)
   ```

   ## Power User Access

   Step metadata is stored in `(meta ctx)` with `:step/*` keys:
   - `:step/arguments` — DataTable or DocString
   - `:step/text` — step text after placeholder substitution
   - `:step/keyword` — Given/When/Then/And/But
   - `:step/interface` — the resolved interface INSTANCE the step executes
     on (sl-s2oa); absent when the step has no bound SVO (REPL free mode,
     legacy stepdefs)

   Direct access: `(:step/arguments (meta ctx))`

   ## Timing (sl-99u2 — the deadline substrate)

   The framework's timing doctrine: oracles retry patiently, mutations
   never retry, negation doesn't wait. A custom step that needs to wait
   for a condition calls the doctrine instead of reimplementing it:

   - `timing` — the effective deadline/interval for this step, resolved
     instance > interface > global > default from `:timing` config, with
     per-key SOURCE provenance.
   - `poll-until` — the oracle kernel: polls a predicate at the resolved
     interval until the resolved deadline; TIMEOUT throws a structured,
     legible `:step/poll-timeout` (elapsed, deadline, deadline source,
     last observed value) through normal step-failure reporting.

   Never wrap a mutation in `poll-until` — mutations are not idempotent
   and never retry. Negation never waits — `should not see` checks once;
   waiting for disappearance is an oracle over a negated predicate."
  (:require [clojure.spec.alpha :as s]
            [shiftlefter.capabilities.ctx :as cap]))

(defn arguments
  "Returns the DataTable or DocString attached to this step, or nil.

   DataTable shape:
   ```clojure
   {:rows [[\"name\" \"email\"]
           [\"Alice\" \"alice@example.com\"]
           [\"Bob\" \"bob@example.com\"]]}
   ```

   DocString shape:
   ```clojure
   {:content \"multi-line\\nstring content\"
    :media-type \"application/json\"}  ; optional
   ```"
  [ctx]
  (:step/arguments (meta ctx)))

(defn text
  "Returns the step text (after placeholder substitution).

   For a step like `Given I have 5 cucumbers`, returns \"I have 5 cucumbers\"."
  [ctx]
  (:step/text (meta ctx)))

(defn step-keyword
  "Returns the step keyword: Given, When, Then, And, But, or *.

   Note: Returns the original keyword from the feature file.
   And/But inherit meaning from their preceding Given/When/Then."
  [ctx]
  (:step/keyword (meta ctx)))

(defn interface-instance
  "Returns the resolved interface INSTANCE name the executing step targets
   (sl-s2oa): the binder stamps the bound SVO's :interface — annotation
   name, or the defaulted instance of the stepdef's type — into ctx
   metadata as :step/interface. Falls back to `default-type` on paths with
   no bound svo (REPL free mode, direct test invocation, legacy stepdefs),
   where instance and type coincide."
  [ctx default-type]
  (or (:step/interface (meta ctx)) default-type))

;; -----------------------------------------------------------------------------
;; Timing substrate (sl-99u2)
;; -----------------------------------------------------------------------------

(def default-timing
  "Framework-default timing for custom steps that call `poll-until` bare:
   30s deadline, 500ms poll interval. Builtins carry their own class
   defaults via the :defaults opt; every config rung overrides both."
  {:deadline-ms 30000 :interval-ms 500})

(s/def ::deadline-ms pos-int?)
(s/def ::interval-ms pos-int?)
(s/def ::timing-source #{:override :instance :interface :global :default})
(s/def ::deadline-source ::timing-source)
(s/def ::interval-source ::timing-source)
(s/def ::resolved-timing
  (s/keys :req-un [::deadline-ms ::interval-ms
                   ::deadline-source ::interval-source]))

(defn- resolve-timing-key
  "Resolve one timing key (:deadline-ms or :interval-ms) down the ladder.
   Returns [value source]."
  [ctx opts instance iface-type k]
  (or (when-some [v (get opts k)] [v :override])
      (when-some [v (get-in ctx [cap/run-interfaces-key instance :timing k])]
        [v :instance])
      (when (not= iface-type instance)
        (when-some [v (get-in ctx [cap/run-interfaces-key iface-type :timing k])]
          [v :interface]))
      (when-some [v (get (cap/get-run-timing ctx) k)] [v :global])
      [(or (get-in opts [:defaults k]) (get default-timing k)) :default]))

(defn timing
  "The effective timing budget for this step: `{:deadline-ms N :interval-ms N
   :deadline-source kw :interval-source kw}`.

   Each key resolves independently down the ladder (first rung that carries
   the key wins); the source keyword names the winning rung:

   1. :override  — a non-nil :deadline-ms / :interval-ms in `opts` (how the
                   builtins' dynamic vars and explicit caller opts enter)
   2. :instance  — :timing on the resolved interface INSTANCE entry in config
                   (instance via `interface-instance`; opts :default-type
                   supplies the fallback type, as builtins do)
   3. :interface — :timing on the entry named by the instance's TYPE, when
                   that names a different entry (sl-s2oa ladder)
   4. :global    — the top-level :timing map in shiftlefter.edn
   5. :default   — opts {:defaults {...}} (builtin class defaults), else
                   `default-timing`

   Degrades cleanly on bare/REPL ctx (no run stash, no :step/interface):
   rungs 2-4 skip and the default rung answers."
  ([ctx] (timing ctx {}))
  ([ctx opts]
   (let [instance (interface-instance ctx (:default-type opts))
         iface-type (cap/get-run-interface-type ctx instance)
         [deadline dsrc] (resolve-timing-key ctx opts instance iface-type :deadline-ms)
         [interval isrc] (resolve-timing-key ctx opts instance iface-type :interval-ms)]
     {:deadline-ms deadline :interval-ms interval
      :deadline-source dsrc :interval-source isrc})))

(s/fdef timing
  :args (s/cat :ctx map? :opts (s/? map?))
  :ret ::resolved-timing)

(def ^:private observed-value-max-chars
  "Cap on the printed length of :last-value / :last-error data in a timeout
   record — an arbitrary pred result (driver object, huge DOM dump) must
   never poison the EDN report plane."
  500)

(defn- sanitize-observed
  "EDN-safe, size-bounded rendering of an observed poll value. Small
   self-representing scalars pass through; strings truncate; everything
   else becomes a truncated pr-str string."
  [v]
  (cond
    (or (nil? v) (boolean? v) (number? v) (keyword? v)) v
    :else
    (let [s (if (string? v) v (pr-str v))]
      (if (> (count s) observed-value-max-chars)
        (str (subs s 0 observed-value-max-chars) "…[truncated]")
        s))))

(defn- sanitize-error-data
  "Sanitize a retryable error's ex-data for the timeout record. A plain
   keyword-keyed map keeps its structure (values bounded individually —
   the domain fields like :expected/:actual/:locator stay queryable);
   anything else collapses to a bounded string."
  [d]
  (if (and (map? d) (every? keyword? (keys d)))
    (update-vals d sanitize-observed)
    (sanitize-observed d)))

(defn- timeout-record
  "Build the structured :step/poll-timeout ex-info (sl-99u2 AC: elapsed,
   deadline, deadline SOURCE, last observed value — legible everywhere)."
  [{:keys [deadline-ms interval-ms deadline-source]} elapsed attempt opts]
  (let [last-error (:error attempt)
        description (:description opts)
        msg (str "Timed out after " elapsed "ms"
                 " (deadline " deadline-ms "ms via " (name deadline-source)
                 ", interval " interval-ms "ms)"
                 (when description (str " — " description))
                 (when last-error (str ". Last error: " (ex-message last-error))))]
    (ex-info msg
             (merge {:type :step/poll-timeout
                     :elapsed-ms elapsed
                     :deadline-ms deadline-ms
                     :deadline-source deadline-source
                     :interval-ms interval-ms}
                    (if last-error
                      {:last-error {:message (ex-message last-error)
                                    :type (:type (ex-data last-error))
                                    :data (sanitize-error-data (ex-data last-error))}}
                      {:last-value (sanitize-observed (:value attempt))})
                    (:context opts)))))

(defn- poll-attempt
  "One pred call. Returns {:value v} on any result, {:error e} when the
   pred threw an exception `retry-error?` classifies as a failed poll.
   Other exceptions propagate — a throwing predicate is a real error,
   not a not-yet."
  [pred retry-error?]
  (try
    {:value (pred)}
    (catch Exception e
      (if (and retry-error? (retry-error? e))
        {:error e}
        (throw e)))))

(defn poll-until
  "The oracle kernel: call `pred` (no-arg) at the resolved poll interval
   until it returns truthy or the resolved deadline elapses. Returns pred's
   truthy result. ORACLES ONLY — never wrap a mutation (mutations don't
   retry) and never wait on a bare negation (waiting for disappearance is
   an oracle over a negated predicate).

   Timing resolves via `timing` (instance > interface > global > default);
   opts pass through, plus:

   - :until         — success test over pred's return value; default
                      truthiness. Set it when the predicate OBSERVES a
                      value (a count, a payload) so the timeout record can
                      carry the last observation; poll-until then returns
                      the observed value that satisfied it.
   - :retry-error?  — predicate over exceptions; matches count as a failed
                      poll (recorded as :last-error), everything else
                      propagates immediately. Default: none.
   - :description   — string for the timeout message; defaults to the
                      executing step's text when available.
   - :context       — map merged into the timeout ex-data.

   On timeout throws :step/poll-timeout ex-info carrying :elapsed-ms,
   :deadline-ms, :deadline-source, :interval-ms, and the sanitized last
   observed value (:last-value) or last retryable error (:last-error),
   flowing through normal step-failure reporting. At least one attempt
   always runs; the final attempt lands at the deadline edge."
  ([ctx pred] (poll-until ctx pred {}))
  ([ctx pred opts]
   (let [resolved (timing ctx opts)
         opts (update opts :description #(or % (text ctx)))
         until (or (:until opts) identity)
         {:keys [deadline-ms interval-ms]} resolved
         start (System/currentTimeMillis)]
     (loop []
       (let [attempt (poll-attempt pred (:retry-error? opts))
             elapsed (- (System/currentTimeMillis) start)]
         (if (and (contains? attempt :value) (until (:value attempt)))
           (:value attempt)
           (if (>= elapsed deadline-ms)
             (throw (timeout-record resolved elapsed attempt opts))
             (do (Thread/sleep (long (min interval-ms (- deadline-ms elapsed))))
                 (recur)))))))))

(s/fdef poll-until
  :args (s/cat :ctx map? :pred ifn? :opts (s/? map?)))
