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
  (:require [shiftlefter.stepengine.annotations :as annotations]
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

(defn- shifted-mode?
  "Check if config indicates Shifted mode (SVO validation enabled).
   Per A1: Shifted mode is indicated by presence of :svo key."
  [config]
  (contains? config :svo))

;; -----------------------------------------------------------------------------
;; Binding Options Builder (Shifted Mode)
;; -----------------------------------------------------------------------------

(defn- build-binding-opts
  "Build options for bind-suite based on config.

   In vanilla mode (no :svo key): returns either nil (no SVO validation)
   or `{:ok {:adapter-registry r}}` if a custom registry was supplied —
   capability gating still needs the registry even without SVO.

   In Shifted mode (:svo present): loads glossaries strictly and returns
   binding opts including `:adapter-registry` if supplied.

   Returns:
   - nil — vanilla mode, no SVO, no custom registry
   - {:ok opts} — opts include :glossary :interfaces :svo :adapter-registry
   - {:error {...}} — Shifted mode, configuration or loading failed"
  [config adapter-registry]
  (if-not (shifted-mode? config)
    ;; Vanilla mode: SVO off; only build opts if a custom registry was given.
    (when adapter-registry
      {:ok {:adapter-registry adapter-registry}})
    ;; Shifted mode: require glossaries, load strictly
    (if-not (contains? config :glossaries)
      {:error {:type :svo/missing-glossaries-config
               :message "Shifted mode requires :glossaries config"}}
      (let [glossary-result (glossary/load-all-glossaries-strict (:glossaries config))]
        (if (:error glossary-result)
          glossary-result
          {:ok (cond-> {:glossary (:ok glossary-result)
                        :interfaces (:interfaces config)
                        :svo (:svo config)}
                 adapter-registry (assoc :adapter-registry adapter-registry))})))))

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

   Returns:
   - :plans - seq of run plans (one per pickle), ready for executor
   - :runnable? - true iff all plans are executable (no errors at any phase)
   - :diagnostics - binding issues (undefined/ambiguous/invalid-arity/svo-issues)
   - :macro-errors - macro loading/expansion errors (if any)

   Pipeline:
   1. Build binding opts (load glossaries if Shifted mode; thread adapter-registry)
   2. If Shifted mode: annotation pass (fail fast on annotation errors)
   3. If macros enabled: load registries (fail fast on load error)
   4. If macros enabled: expand pickles (fail fast on expansion error)
   5. Bind expanded pickles to step definitions (with SVO opts if Shifted)"
  [config pickles stepdefs & [opts]]
  (let [runner-config       (:runner config)
        adapter-registry    (:adapter-registry opts)
        binding-opts-result (build-binding-opts config adapter-registry)
        binding-error       (and binding-opts-result (:error binding-opts-result))
        binding-opts        (when (and binding-opts-result (not binding-error))
                              (:ok binding-opts-result))
        ;; Tier 2: cross-check stepdef :svo metadata against the loaded
        ;; glossary (sl-hse). Runs in Shifted mode only — without a glossary
        ;; there's nothing to cross-check against.
        stepdef-issues      (when binding-opts
                              (validate/validate-stepdefs-against-glossary
                               stepdefs (:glossary binding-opts)))]
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
                                     :total-issues        0}}}

      (seq stepdef-issues)
      {:plans      []
       :runnable?  false
       :diagnostics {:errors         [{:type    :stepdef/glossary-mismatch
                                       :issues  stepdef-issues
                                       :message (str "Found "
                                                     (count stepdef-issues)
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
      (let [annotation-result (if (shifted-mode? config)
                                (annotations/annotate-pickles
                                 pickles
                                 (:interfaces config)
                                 (macros-enabled? runner-config))
                                {:pickles pickles :errors []})]
        (if (seq (:errors annotation-result))
          ;; Annotation pass produced errors — fail fast
          {:plans      []
           :runnable?  false
           :diagnostics {:annotation-errors (:errors annotation-result)
                         :undefined         []
                         :ambiguous         []
                         :invalid-arity     []
                         :svo-issues        []
                         :counts            {:undefined-count     0
                                             :ambiguous-count     0
                                             :invalid-arity-count 0
                                             :svo-issue-count     0
                                             :total-issues        0}}}
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
                                     :counts        {:undefined-count     0
                                                     :ambiguous-count     0
                                                     :invalid-arity-count 0
                                                     :svo-issue-count     0
                                                     :total-issues        0}}}
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
                                         :counts        {:undefined-count     0
                                                         :ambiguous-count     0
                                                         :invalid-arity-count 0
                                                         :svo-issue-count     0
                                                         :total-issues        0}}}
                          ;; Expansion succeeded, bind with opts
                          (bind/bind-suite pickles stepdefs binding-opts))))))]
            ;; Suite-load lint (sl-unz): post-bind, dedup-by-stepdef pass.
            ;; Lifts sl-ewn's per-step :requires-protocols / :provides
            ;; check from per-scenario to per-stepdef and adds
            ;; :stepdef/undefined-interface + :glossary/orphan-type. Only
            ;; engages when :interfaces config is present (vanilla without
            ;; interfaces is exempt). Issues block planning even if bind
            ;; otherwise succeeded.
            ;;
            ;; Orphan-type lint scopes to PROJECT-DECLARED glossary types
            ;; (`config :glossaries :verbs` keys) — framework defaults
            ;; are loaded transparently and shouldn't trip the check.
            (if-let [iss (and (:interfaces config)
                              (seq (suite-lint/lint-suite
                                    (suite-lint/used-stepdef-infos (:plans bind-result))
                                    (keys (get-in config [:glossaries :verbs]))
                                    (:interfaces config)
                                    adapter-registry)))]
              (let [counts (suite-lint/issue-counts iss)
                    diag   (:diagnostics bind-result)]
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
                                                    (:total counts)))))})
              bind-result)))))))
