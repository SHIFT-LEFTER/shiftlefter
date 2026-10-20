(ns shiftlefter.stepengine.compile
  "Stepengine compilation entry point.

   Compiles pickles into executable plans by binding steps to definitions.
   This namespace provides the single entry point that the runner calls
   to produce a runnable suite.

   ## Purpose

   This module centralizes 'compile the plan' logic, providing seams for:
   - Pre-bind transforms (macro expansion)
   - Post-bind validation (SVO in Phase 0.3)

   ## Pipeline

   1. (if macros enabled) Load macro registries
   2. (if macros enabled) Expand pickles (macro pass)
   3. Bind expanded pickles to step definitions
   4. (future) Post-bind validation hook

   ## Usage

   ```clojure
   (let [stepdefs (registry/all-stepdefs)
         runner-cfg (:runner config)
         {:keys [plans runnable? diagnostics]} (compile-suite runner-cfg pickles stepdefs)]
     (if runnable?
       (exec/execute-suite plans)
       (report-diagnostics diagnostics)))
   ```"
  (:require [shiftlefter.config :as config]
            [shiftlefter.stepengine.annotations :as annotations]
            [shiftlefter.stepengine.bind :as bind]
            [shiftlefter.stepengine.macros :as macros]
            [shiftlefter.stepengine.suite-lint :as suite-lint]
            [shiftlefter.svo.glossary :as glossary]
            [shiftlefter.svo.validate :as validate]))

;; -----------------------------------------------------------------------------
;; Config Helpers
;; -----------------------------------------------------------------------------

(defn- macros-enabled?
  "Check if macros are enabled in runner-config."
  [runner-config]
  (get-in runner-config [:macros :enabled?] false))

(defn- get-registry-paths
  "Get macro registry paths from runner-config."
  [runner-config]
  (get-in runner-config [:macros :registry-paths] []))

;; -----------------------------------------------------------------------------
;; Binding Options Builder
;; -----------------------------------------------------------------------------

(defn- build-binding-opts
  "Build options for bind-suite from config. One mode (sl-6wh9): the
   glossaries always load strictly — an absent/nil :glossaries value is an
   EMPTY glossary (framework default verbs still load, so builtin steps
   bind); an explicit path that is missing is a hard :glossary error.

   Returns:
   - {:ok opts} — opts include :glossary :interfaces :costumes :enforcement
     :glossary-lints, plus :adapter-registry when supplied and :config-path
     (sl-u2r0: the def-site an unknown-interface issue names) when known
   - {:error {...}} — a glossary failed to load"
  [config adapter-registry config-path]
  (let [glossary-result (glossary/load-all-glossaries-strict (:glossaries config))]
    (if (:error glossary-result)
      glossary-result
      ;; :glossary-lints (sl-67dj, Warden P3-13): structured glossary-load
      ;; warnings ride along so compile-suite can surface them as
      ;; diagnostics — bind-suite ignores the extra key.
      {:ok (cond-> {:glossary (:ok glossary-result)
                    :interfaces (config/get-interfaces config)
                    :costumes (config/get-costumes config)
                    :enforcement (config/get-enforcement config)
                    :glossary-lints (vec (:warnings glossary-result))}
             adapter-registry (assoc :adapter-registry adapter-registry)
             config-path (assoc :config-path config-path))})))

(defn- attach-glossary-lints
  "sl-67dj (Warden P3-13): thread the structured glossary-load warnings
   (build-binding-opts' :glossary-lints) onto a compile result's
   :diagnostics for the runner to report. Applied on the post-bind paths
   only — the earlier fail-fast planning branches surface their error as
   the actionable item, matching the loader's error-drops-warnings rule.
   `warn-stepdef-issues` (sl-6wh9): the warn-level Tier-2 issues
   (:stepdef/unknown-verb at :warn) ride :stepdef-issues the same way —
   reported, counted, never blocking."
  [result binding-opts warn-stepdef-issues]
  (let [lints (:glossary-lints binding-opts)]
    (cond-> result
      (seq lints) (update :diagnostics assoc :glossary-lints lints)
      (seq warn-stepdef-issues)
      (-> (update :diagnostics assoc :stepdef-issues (vec warn-stepdef-issues))
          (update-in [:diagnostics :counts :stepdef-issue-count]
                     (fnil + 0) (count warn-stepdef-issues))
          (update-in [:diagnostics :counts :total-issues]
                     (fnil + 0) (count warn-stepdef-issues))))))

;; -----------------------------------------------------------------------------
;; Pre-bind Pass: Macro Expansion
;; -----------------------------------------------------------------------------

(defn- load-macro-registry
  "Load macro registry from configured paths.
   Returns {:registry map :errors []}."
  [runner-config]
  (let [paths (get-registry-paths runner-config)]
    (macros/load-registries paths)))

(defn- expand-pickles
  "Expand macros in all pickles.
   Returns {:pickles expanded-pickles :errors []}."
  [pickles registry]
  (let [macro-config {:enabled? true}
        results (map #(macros/expand-pickle macro-config % registry) pickles)
        expanded (mapv :pickle results)
        errors (into [] (mapcat :errors results))]
    {:pickles expanded
     :errors errors}))

;; -----------------------------------------------------------------------------
;; Suite Compilation
;; -----------------------------------------------------------------------------

(defn compile-suite
  "Compile pickles into an executable plan.

   Parameters:
   - config: full config map (not just :runner slice)
   - pickles: seq of pickles from parser/pickler
   - stepdefs: step definitions from registry
   - opts (optional): {:adapter-registry r} forwarded to binding for
     capability gating with a custom adapter registry. Defaults to nil →
     bind uses adapters.registry/default-registry.
     {:suite-lint-mode :warn} keeps the bound plans when suite-load lint
     finds issues, attaching them as :diagnostics :suite-lint-issues data
     instead of the :suite-lint/failed error (sl-hyey: the corpus-bind
     surfaces — a LISTING is not a run, so gating informs rather than
     suppresses every usage marker). Default (:error / absent) is the
     existing kill: lint issues empty the plans and block planning —
     every run-path caller is byte-identical, none passes the key.

   Returns:
   - :plans - seq of run plans (one per pickle), ready for executor
   - :runnable? - true iff all plans are executable (no errors at any phase)
   - :diagnostics - binding issues (undefined/ambiguous/invalid-arity/svo-issues)
   - :macro-errors - macro loading/expansion errors (if any)

   Pipeline:
   1. Build binding opts (load glossaries; thread adapter-registry)
   2. Annotation pass (fail fast on annotation errors)
   3. If macros enabled: load registries (fail fast on load error)
   4. If macros enabled: expand pickles (fail fast on expansion error)
   5. Bind expanded pickles to step definitions (with the enforcement opts)"
  [config pickles stepdefs & [opts]]
  (let [runner-config       (:runner config)
        adapter-registry    (:adapter-registry opts)
        binding-opts-result (build-binding-opts config adapter-registry
                                                (:config-path opts))
        binding-error       (:error binding-opts-result)
        binding-opts        (when-not binding-error
                              (:ok binding-opts-result))
        ;; Tier 2: cross-check stepdef :svo metadata against the loaded
        ;; glossary (sl-hse). Always runs (sl-6wh9) — an empty project
        ;; glossary still carries the framework default verbs.
        ;;
        ;; :stepdef/unknown-verb rides the :unknown-verb knob (sl-6wh9,
        ;; ruling #1: an unknown verb is project-owned vocabulary — it
        ;; warns by default and EXECUTES; the stepdef body is the
        ;; executor, the frame only stamps slot kinds). Before the
        ;; retirement this check ran in Shifted mode only and every issue
        ;; was a hard error; with one mode, a custom verb on a user
        ;; stepdef would otherwise fail every project without a verb
        ;; glossary. The structural kinds (a frame or args the glossary
        ;; does not declare for a verb it DOES define) stay hard errors.
        unknown-verb-level  (get (:enforcement binding-opts) :unknown-verb :warn)
        stepdef-issues      (when binding-opts
                              (->> (validate/validate-stepdefs-against-glossary
                                    stepdefs
                                    (:glossary binding-opts)
                                    (:interfaces binding-opts))
                                   (keep (fn [issue]
                                           (if (= :stepdef/unknown-verb (:type issue))
                                             (case unknown-verb-level
                                               :off nil
                                               (assoc issue :severity unknown-verb-level))
                                             (assoc issue :severity :error))))
                                   vec))
        {blocking-stepdef-issues :error
         warn-stepdef-issues :warn} (group-by :severity stepdef-issues)]
    (cond
      binding-error
      {:plans      []
       :runnable?  false
       :diagnostics {:errors        [binding-error]
                     :undefined     []
                     :ambiguous     []
                     :invalid-arity []
                     :svo-issues    []
                     :counts        {:undefined-count     0
                                     :ambiguous-count     0
                                     :invalid-arity-count 0
                                     :svo-issue-count     0
                                     :error-count         1
                                     :total-issues        1}}}

      (seq blocking-stepdef-issues)
      {:plans      []
       :runnable?  false
       :diagnostics {:errors         [{:type    :stepdef/glossary-mismatch
                                       :issues  blocking-stepdef-issues
                                       :message (str "Found "
                                                     (count blocking-stepdef-issues)
                                                     " stepdef(s) inconsistent with the glossary")}]
                     :stepdef-issues stepdef-issues
                     :undefined      []
                     :ambiguous      []
                     :invalid-arity  []
                     :svo-issues     []
                     :counts         {:undefined-count      0
                                      :ambiguous-count      0
                                      :invalid-arity-count  0
                                      :svo-issue-count      0
                                      :stepdef-issue-count  (count stepdef-issues)
                                      :total-issues         (count stepdef-issues)}}}

      :else
      (let [annotation-result (annotations/annotate-pickles
                               pickles
                               (:interfaces config)
                               (macros-enabled? runner-config))]
        (if (seq (:errors annotation-result))
          ;; Annotation pass produced errors — fail fast
          {:plans      []
           :runnable?  false
           :diagnostics {:annotation-errors (:errors annotation-result)
                         :undefined         []
                         :ambiguous         []
                         :invalid-arity     []
                         :svo-issues        []
                         :counts            {:undefined-count        0
                                             :ambiguous-count        0
                                             :invalid-arity-count    0
                                             :svo-issue-count        0
                                             :annotation-error-count (count (:errors annotation-result))
                                             :total-issues           (count (:errors annotation-result))}}}
          ;; Annotation pass OK (or skipped) — proceed
          (let [pickles (:pickles annotation-result)
                bind-result
                (if-not (macros-enabled? runner-config)
                  ;; Macros disabled: straight to binding
                  (bind/bind-suite pickles stepdefs binding-opts)
                  ;; Macros enabled: load → expand → bind
                  (let [{:keys [registry errors]} (load-macro-registry runner-config)]
                    (if (seq errors)
                      ;; Registry load failed
                      {:plans      []
                       :runnable?  false
                       :diagnostics {:macro-errors  errors
                                     :undefined     []
                                     :ambiguous     []
                                     :invalid-arity []
                                     :svo-issues    []
                                     :counts        {:undefined-count   0
                                                     :ambiguous-count   0
                                                     :invalid-arity-count 0
                                                     :svo-issue-count   0
                                                     :macro-error-count (count errors)
                                                     :total-issues      (count errors)}}}
                      ;; Registry loaded, expand pickles
                      (let [{:keys [pickles errors]} (expand-pickles pickles registry)]
                        (if (seq errors)
                          ;; Expansion failed
                          {:plans      []
                           :runnable?  false
                           :diagnostics {:macro-errors  errors
                                         :undefined     []
                                         :ambiguous     []
                                         :invalid-arity []
                                         :svo-issues    []
                                         :counts        {:undefined-count   0
                                                         :ambiguous-count   0
                                                         :invalid-arity-count 0
                                                         :svo-issue-count   0
                                                         :macro-error-count (count errors)
                                                         :total-issues      (count errors)}}}
                          ;; Expansion succeeded, bind with opts
                          (bind/bind-suite pickles stepdefs binding-opts))))))]
            ;; Suite-load lint (sl-unz): post-bind, dedup-by-stepdef pass.
            ;; Lifts sl-ewn's per-step :requires-protocols / :provides
            ;; check from per-scenario to per-stepdef and adds
            ;; :stepdef/undefined-interface + :glossary/orphan-type.
            ;; Unconditional (sl-6wh9): an absent :interfaces reads as the
            ;; empty set. Issues block planning even if bind otherwise
            ;; succeeded.
            ;;
            ;; Orphan-type lint scopes to PROJECT-DECLARED glossary types,
            ;; read from the LOADED glossary's actual mounts (:sources =
            ;; declared-type → path, built by the loader — sl-67dj: config
            ;; is a path list; the files declare their types). Framework
            ;; defaults are loaded transparently and shouldn't trip the
            ;; check.
            (if-let [iss (seq (suite-lint/lint-suite
                               (suite-lint/used-stepdef-infos (:plans bind-result))
                               (keys (get-in binding-opts [:glossary :sources :verbs]))
                               (:interfaces config)
                               adapter-registry))]
              (if (= :warn (:suite-lint-mode opts))
                ;; Corpus-bind mode (sl-hyey): a listing is not a run —
                ;; keep the plans, carry the issues as data. The caller
                ;; surfaces them (warn, never silent).
                (attach-glossary-lints
                 (assoc-in bind-result [:diagnostics :suite-lint-issues] iss)
                 binding-opts warn-stepdef-issues)
                (let [counts (suite-lint/issue-counts iss)
                      diag   (:diagnostics bind-result)]
                  (attach-glossary-lints
                   {:plans      []
                    :runnable?  false
                    :diagnostics (-> diag
                                     (assoc :suite-lint-issues iss)
                                     (update :errors (fnil conj [])
                                             {:type    :suite-lint/failed
                                              :issues  iss
                                              :message (str "Suite-load lint found "
                                                            (count iss)
                                                            " issue(s)")})
                                     (update :counts merge
                                             (assoc counts
                                                    :total-issues
                                                    (+ (or (:total-issues (:counts diag)) 0)
                                                       (:total counts)))))}
                   binding-opts warn-stepdef-issues)))
              (attach-glossary-lints bind-result binding-opts warn-stepdef-issues))))))))
