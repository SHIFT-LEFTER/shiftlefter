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

   Direct access: `(:step/arguments (meta ctx))`")

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
