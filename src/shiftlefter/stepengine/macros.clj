(ns shiftlefter.stepengine.macros
  "Macro registry loading and management for ShiftLefter stepengine.

   Macros allow users to define reusable step sequences that expand
   during compilation. This module handles loading macro definitions
   from INI files into a registry.

   ## Usage

   ```clojure
   (let [result (load-registries [\"macros/auth.ini\" \"macros/common.ini\"])]
     (if (seq (:errors result))
       (handle-errors result)
       (use-registry (:registry result))))
   ```

   ## Registry Structure

   The registry is a map from macro key to macro definition:
   ```clojure
   {\"login as alice\" {:macro/key \"login as alice\"
                        :macro/definition {:file \"auth.ini\"
                                           :location {:line 1 :column 1}}
                        :macro/description \"Login flow for alice\"
                        :macro/steps [{:step/keyword \"Given\"
                                       :step/text \"I am on login page\"
                                       :step/location {:line 5 :column 3}}
                                      ...]}}
   ```"
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [shiftlefter.stepengine.macros.ini :as ini]))

;; -----------------------------------------------------------------------------
;; Registry Building
;; -----------------------------------------------------------------------------

(defn- resolve-path
  "Resolve a path relative to CWD.
   Returns the resolved path string."
  [path]
  (str (fs/absolutize path)))

(defn- merge-into-registry
  "Merge a macro into the registry, checking for duplicates.
   Returns {:registry updated-registry :error nil-or-error}."
  [registry macro]
  (let [key (:macro/key macro)]
    (if-let [existing (get registry key)]
      {:registry registry
       :error {:type :macro/duplicate-key
               :message (str "Duplicate macro key: '" key "'")
               :macro-key key
               :first-definition (:macro/definition existing)
               :second-definition (:macro/definition macro)}}
      {:registry (assoc registry key macro)
       :error nil})))

(defn- process-file
  "Process a single file, returning macros and errors."
  [path]
  (let [resolved (resolve-path path)]
    (if (fs/exists? resolved)
      (ini/parse-file resolved)
      {:macros []
       :errors [{:type :macro/file-not-found
                 :message (str "Macro file not found: " path)
                 :file path}]})))

;; -----------------------------------------------------------------------------
;; Public API
;; -----------------------------------------------------------------------------

(defn load-registries
  "Load macro registries from a list of INI file paths.

   Paths are resolved relative to CWD. Files are loaded in order.
   Duplicate macro keys across any files result in a :macro/duplicate-key error.

   Parameters:
   - registry-paths: vector of paths to INI files

   Returns:
   {:registry {\"macro-key\" {:macro/key ... :macro/definition ... :macro/steps ...} ...}
    :errors [{:type ... :message ... :file ...} ...]}

   On success: :errors is empty, :registry contains all macros.
   On failure: :errors contains issues, :registry may be partial."
  [registry-paths]
  (loop [paths registry-paths
         registry {}
         all-errors []]
    (if (empty? paths)
      {:registry registry
       :errors all-errors}
      (let [path (first paths)
            {:keys [macros errors]} (process-file path)
            ;; Merge macros into registry, collecting duplicate errors
            merge-results (reduce
                           (fn [{:keys [reg errs]} macro]
                             (let [{:keys [registry error]} (merge-into-registry reg macro)]
                               {:reg registry
                                :errs (if error (conj errs error) errs)}))
                           {:reg registry :errs []}
                           macros)]
        (recur (rest paths)
               (:reg merge-results)
               (into (into all-errors errors) (:errs merge-results)))))))

(defn get-macro
  "Get a macro from the registry by key.
   Returns the macro map or nil if not found."
  [registry key]
  (get registry key))

(defn macro-keys
  "Get all macro keys in the registry."
  [registry]
  (keys registry))

;; -----------------------------------------------------------------------------
;; Macro Detection
;; -----------------------------------------------------------------------------

(def ^:private macro-suffix " +")

(defn- extract-macro-key
  "Extract macro key from step text by removing ' +' suffix and trimming.
   Returns the key string."
  [step-text]
  (-> step-text
      (subs 0 (- (count step-text) (count macro-suffix)))
      str/trim))

(defn detect-call
  "Detect if a step is a macro call.

   A step is a macro call iff:
   - macro-config has :enabled? true, AND
   - step's :step/text ends with literal \" +\"

   Parameters:
   - macro-config: map with :enabled? boolean
   - step: map with :step/text string

   Returns:
   - {:is-macro? true :key \"macro-key\"} if macro call detected
   - {:is-macro? false} if not a macro call"
  [macro-config step]
  (let [enabled? (:enabled? macro-config)
        step-text (:step/text step)]
    (if (and enabled?
             (string? step-text)
             (str/ends-with? step-text macro-suffix))
      {:is-macro? true
       :key (extract-macro-key step-text)}
      {:is-macro? false})))

;; -----------------------------------------------------------------------------
;; Context Validation
;; -----------------------------------------------------------------------------

(defn- outline-pickle?
  "Check if pickle is from a Scenario Outline (has row-location)."
  [pickle]
  (some? (:pickle/row-location pickle)))

(defn- step-has-arguments?
  "Check if step has DocString or DataTable arguments."
  [step]
  (let [args (:step/arguments step)]
    (and (some? args)
         (if (sequential? args)
           (seq args)
           true))))

(defn- validate-macro-context
  "Validate that a macro call is in a supported context.
   Returns nil if valid, or an error map if invalid."
  [pickle step detection]
  (let [key (:key detection)
        step-location (:step/location step)]
    (cond
      ;; Check 1: Macro in Scenario Outline
      (outline-pickle? pickle)
      {:type :macro/scenario-outline-not-supported
       :message (str "Macro calls are not supported in Scenario Outlines: '" key " +'")
       :macro-key key
       :location step-location
       :pickle-location (:pickle/row-location pickle)}

      ;; Check 2: Macro with DocString/DataTable
      (step-has-arguments? step)
      {:type :macro/argument-not-supported
       :message (str "Macro calls cannot have DocString or DataTable arguments: '" key " +'")
       :macro-key key
       :location step-location}

      ;; Valid context
      :else nil)))

;; -----------------------------------------------------------------------------
;; Pickle Expansion
;; -----------------------------------------------------------------------------

(defn- make-call-site
  "Create call-site provenance from step location."
  [step]
  {:line (-> step :step/location :line)
   :column (-> step :step/location :column)})

(defn- make-wrapper-step
  "Create synthetic wrapper step for macro call."
  [original-step macro-def key]
  {:step/keyword (:step/keyword original-step)
   :step/text (:step/text original-step)
   :step/location (:step/location original-step)
   :step/arguments (or (:step/arguments original-step) [])
   :step/synthetic? true
   :step/macro {:role :call
                :key key
                :call-site (make-call-site original-step)
                :definition (:macro/definition macro-def)
                :step-count (count (:macro/steps macro-def))}})

(defn- make-expanded-step
  "Create expanded child step with provenance."
  [macro-step index key call-site macro-def]
  {:step/keyword (:step/keyword macro-step)
   :step/text (:step/text macro-step)
   :step/location (:step/location macro-step)
   :step/arguments (or (:step/arguments macro-step) [])
   :step/macro {:role :expanded
                :key key
                :call-site call-site
                :definition (:macro/definition macro-def)
                :index index
                :definition-step {:file (-> macro-def :macro/definition :file)
                                  :line (-> macro-step :step/location :line)
                                  :column (-> macro-step :step/location :column)}}})

(defn- check-recursion
  "Check if any expanded step is itself a macro call.
   Returns error if recursion detected, nil otherwise."
  [macro-steps key call-site]
  (when-let [recursive-step (first (filter #(str/ends-with? (or (:step/text %) "") macro-suffix)
                                           macro-steps))]
    {:type :macro/recursion-disallowed
     :message (str "Macro '" key "' contains nested macro call '" (:step/text recursive-step)
                   "' - recursion is not supported")
     :macro-key key
     :nested-call (:step/text recursive-step)
     :location call-site}))

(defn- expand-macro-step
  "Expand a single macro call step.
   Returns {:steps [...] :error nil :macro-summary {...}} or {:steps [] :error {...}}."
  [step detection registry]
  (let [key (:key detection)
        call-site (make-call-site step)
        macro-def (get registry key)]
    (cond
      ;; Undefined macro
      (nil? macro-def)
      {:steps []
       :error {:type :macro/undefined
               :message (str "Undefined macro: '" key "'")
               :macro-key key
               :location call-site}
       :macro-summary nil}

      ;; Empty expansion
      (empty? (:macro/steps macro-def))
      {:steps []
       :error {:type :macro/empty-expansion
               :message (str "Macro '" key "' has no steps")
               :macro-key key
               :location call-site
               :definition (:macro/definition macro-def)}
       :macro-summary nil}

      ;; Check for recursion
      :else
      (if-let [recursion-err (check-recursion (:macro/steps macro-def) key call-site)]
        {:steps []
         :error recursion-err
         :macro-summary nil}
        ;; Valid expansion
        (let [wrapper (make-wrapper-step step macro-def key)
              children (map-indexed
                        (fn [idx macro-step]
                          (make-expanded-step macro-step idx key call-site macro-def))
                        (:macro/steps macro-def))]
          {:steps (vec (cons wrapper children))
           :error nil
           :macro-summary {:key key
                           :step-count (count (:macro/steps macro-def))
                           :call-site call-site
                           :definition (:macro/definition macro-def)}})))))

(defn expand-pickle
  "Expand macro calls in a pickle.

   Validates macro call contexts and expands macro steps.
   Phase 2 restrictions:
   - Macro calls in Scenario Outlines → :macro/scenario-outline-not-supported
   - Macro calls with DocString/DataTable → :macro/argument-not-supported
   - Undefined macros → :macro/undefined
   - Empty macros → :macro/empty-expansion
   - Nested macro calls → :macro/recursion-disallowed

   Parameters:
   - macro-config: map with :enabled? boolean
   - pickle: pickle map with :pickle/steps
   - registry: macro registry from load-registries

   Returns:
   {:pickle <expanded-pickle>
    :errors [<error-maps>]}

   The expanded pickle includes:
   - :pickle/steps with wrapper + expanded steps
   - :pickle/macros summary of macros used (first-use order)

   If macros disabled, returns pickle unchanged with no errors."
  [macro-config pickle registry]
  (if-not (:enabled? macro-config)
    ;; Macros disabled - pass through unchanged
    {:pickle pickle
     :errors []}
    ;; Macros enabled - validate and expand
    (let [steps (:pickle/steps pickle)
          ;; Process each step, collecting expanded steps, errors, and macro summaries
          result (reduce
                  (fn [{:keys [expanded-steps errors macro-summaries seen-keys]} step]
                    (let [detection (detect-call macro-config step)]
                      (if-not (:is-macro? detection)
                        ;; Regular step - pass through
                        {:expanded-steps (conj expanded-steps step)
                         :errors errors
                         :macro-summaries macro-summaries
                         :seen-keys seen-keys}
                        ;; Macro call - validate context first
                        (if-let [ctx-err (validate-macro-context pickle step detection)]
                          {:expanded-steps expanded-steps
                           :errors (conj errors ctx-err)
                           :macro-summaries macro-summaries
                           :seen-keys seen-keys}
                          ;; Context valid - expand
                          (let [{:keys [steps error macro-summary]} (expand-macro-step step detection registry)]
                            (if error
                              {:expanded-steps expanded-steps
                               :errors (conj errors error)
                               :macro-summaries macro-summaries
                               :seen-keys seen-keys}
                              ;; Track macro summary (first-use order)
                              (let [key (:key detection)
                                    new-summaries (if (contains? seen-keys key)
                                                    macro-summaries
                                                    (conj macro-summaries macro-summary))]
                                {:expanded-steps (into expanded-steps steps)
                                 :errors errors
                                 :macro-summaries new-summaries
                                 :seen-keys (conj seen-keys key)})))))))
                  {:expanded-steps []
                   :errors []
                   :macro-summaries []
                   :seen-keys #{}}
                  steps)
          expanded-pickle (cond-> (assoc pickle :pickle/steps (:expanded-steps result))
                            (seq (:macro-summaries result))
                            (assoc :pickle/macros (:macro-summaries result)))]
      {:pickle expanded-pickle
       :errors (:errors result)})))
