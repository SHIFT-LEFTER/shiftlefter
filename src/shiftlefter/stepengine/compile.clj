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
            [shiftlefter.stepengine.macros :as macros]))

;; -----------------------------------------------------------------------------
;; Config Helpers (work with runner-config slice)
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
;; Post-bind Hook (Placeholder for 0.3 SVO)
;; -----------------------------------------------------------------------------

(defn- post-bind-validate
  "Post-bind validation hook.
   Currently a no-op — will be used in 0.3 for SVO validation.
   Returns {:valid? true :errors []}."
  [_runner-config _plans]
  {:valid? true
   :errors []})

;; -----------------------------------------------------------------------------
;; Suite Compilation
;; -----------------------------------------------------------------------------

(defn compile-suite
  "Compile pickles into an executable plan.

   Parameters:
   - runner-config: runner config map (the [:runner] slice of full config)
   - pickles: seq of pickles from parser/pickler
   - stepdefs: step definitions from registry

   Returns:
   - :plans - seq of run plans (one per pickle), ready for executor
   - :runnable? - true iff all plans are executable (no errors at any phase)
   - :diagnostics - binding issues (undefined/ambiguous/invalid-arity)
   - :macro-errors - macro loading/expansion errors (if any)

   Pipeline:
   1. If macros enabled: load registries (fail fast on load error)
   2. If macros enabled: expand pickles (fail fast on expansion error)
   3. Bind expanded pickles to step definitions
   4. Post-bind validation (future SVO hook)"
  [runner-config pickles stepdefs]
  (if-not (macros-enabled? runner-config)
    ;; Macros disabled: straight to binding
    (bind/bind-suite pickles stepdefs)
    ;; Macros enabled: load → expand → bind
    (let [{:keys [registry errors]} (load-macro-registry runner-config)]
      (if (seq errors)
        ;; Registry load failed
        {:plans []
         :runnable? false
         :diagnostics {:macro-errors errors
                       :undefined []
                       :ambiguous []
                       :invalid-arity []}}
        ;; Registry loaded, expand pickles
        (let [{:keys [pickles errors]} (expand-pickles pickles registry)]
          (if (seq errors)
            ;; Expansion failed
            {:plans []
             :runnable? false
             :diagnostics {:macro-errors errors
                           :undefined []
                           :ambiguous []
                           :invalid-arity []}}
            ;; Expansion succeeded, bind
            (let [bind-result (bind/bind-suite pickles stepdefs)
                  ;; Post-bind validation (no-op for now)
                  _ (post-bind-validate runner-config (:plans bind-result))]
              bind-result)))))))
