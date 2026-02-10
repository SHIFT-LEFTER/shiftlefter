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
  (:require [shiftlefter.stepengine.bind :as bind]
            [shiftlefter.stepengine.macros :as macros]
            [shiftlefter.svo.glossary :as glossary]))

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

   In vanilla mode (no :svo key): returns nil (no SVO validation).
   In Shifted mode (:svo present): loads glossaries strictly and returns opts.

   Returns:
   - nil — vanilla mode, no SVO validation
   - {:ok opts} — Shifted mode, glossaries loaded successfully
   - {:error {...}} — Shifted mode, configuration or loading failed"
  [config]
  (if-not (shifted-mode? config)
    ;; Vanilla mode: no SVO validation
    nil
    ;; Shifted mode: require glossaries, load strictly
    (if-not (contains? config :glossaries)
      {:error {:type :svo/missing-glossaries-config
               :message "Shifted mode requires :glossaries config"}}
      (let [glossary-result (glossary/load-all-glossaries-strict (:glossaries config))]
        (if (:error glossary-result)
          glossary-result
          {:ok {:glossary (:ok glossary-result)
                :interfaces (:interfaces config)
                :svo (:svo config)}})))))

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

   Returns:
   - :plans - seq of run plans (one per pickle), ready for executor
   - :runnable? - true iff all plans are executable (no errors at any phase)
   - :diagnostics - binding issues (undefined/ambiguous/invalid-arity/svo-issues)
   - :macro-errors - macro loading/expansion errors (if any)

   Pipeline:
   1. Build binding opts (load glossaries if Shifted mode)
   2. If macros enabled: load registries (fail fast on load error)
   3. If macros enabled: expand pickles (fail fast on expansion error)
   4. Bind expanded pickles to step definitions (with SVO opts if Shifted)"
  [config pickles stepdefs]
  (let [runner-config (:runner config)
        ;; Build binding opts (handles Shifted mode glossary loading)
        binding-opts-result (build-binding-opts config)]
    ;; Check for Shifted mode configuration errors
    (if (and binding-opts-result (:error binding-opts-result))
      ;; Glossary config/load error
      {:plans []
       :runnable? false
       :diagnostics {:errors [(:error binding-opts-result)]
                     :undefined []
                     :ambiguous []
                     :invalid-arity []
                     :svo-issues []
                     :counts {:undefined-count 0
                              :ambiguous-count 0
                              :invalid-arity-count 0
                              :svo-issue-count 0
                              :total-issues 0}}}
      ;; Config OK, proceed with compilation
      (let [binding-opts (when binding-opts-result (:ok binding-opts-result))]
        (if-not (macros-enabled? runner-config)
          ;; Macros disabled: straight to binding
          (bind/bind-suite pickles stepdefs binding-opts)
          ;; Macros enabled: load → expand → bind
          (let [{:keys [registry errors]} (load-macro-registry runner-config)]
            (if (seq errors)
              ;; Registry load failed
              {:plans []
               :runnable? false
               :diagnostics {:macro-errors errors
                             :undefined []
                             :ambiguous []
                             :invalid-arity []
                             :svo-issues []
                             :counts {:undefined-count 0
                                      :ambiguous-count 0
                                      :invalid-arity-count 0
                                      :svo-issue-count 0
                                      :total-issues 0}}}
              ;; Registry loaded, expand pickles
              (let [{:keys [pickles errors]} (expand-pickles pickles registry)]
                (if (seq errors)
                  ;; Expansion failed
                  {:plans []
                   :runnable? false
                   :diagnostics {:macro-errors errors
                                 :undefined []
                                 :ambiguous []
                                 :invalid-arity []
                                 :svo-issues []
                                 :counts {:undefined-count 0
                                          :ambiguous-count 0
                                          :invalid-arity-count 0
                                          :svo-issue-count 0
                                          :total-issues 0}}}
                  ;; Expansion succeeded, bind with opts
                  (bind/bind-suite pickles stepdefs binding-opts))))))))))
