(ns shiftlefter.intent.resolve
  "Intent reference parsing and resolution for ShiftLefter.

   ## Intent Reference Syntax

   ```
   Login.submit          ;; basic reference
   Login.submit[1]       ;; 1-indexed (first element)
   Login.submit[-1]      ;; negative index (last element)
   Login.submit[-2]      ;; second from last
   Login.submit[*]       ;; wildcard (all matches, for counting)
   ```

   ## Naming Conventions

   - Intent names: PascalCase (starts with uppercase)
   - Element names: lowercase (can include hyphens, underscores, digits)

   ## Usage

   ```clojure
   (parse-intent-ref \"Login.submit\")
   ;; => {:ok {:intent \"Login\" :element \"submit\" :index nil}}

   (parse-intent-ref \"Login.submit[1]\")
   ;; => {:ok {:intent \"Login\" :element \"submit\" :index 1}}

   (parse-intent-ref \"Login.submit[*]\")
   ;; => {:ok {:intent \"Login\" :element \"submit\" :index :all}}

   (parse-intent-ref \"invalid\")
   ;; => {:error {:type :intent/invalid-reference ...}}
   ```"
  (:require [shiftlefter.intent.loader :as loader]
            [clojure.string :as str]))

;; -----------------------------------------------------------------------------
;; Parsing
;; -----------------------------------------------------------------------------

(def ^:private intent-ref-pattern
  "Regex pattern for intent references.
   Groups: 1=intent-name, 2=element-name, 3=index (optional, may be nil)"
  #"^([A-Z][A-Za-z0-9_-]*)\.([a-z][a-z0-9_-]*)(?:\[(-?\d+|\*)\])?$")

(defn parse-intent-ref
  "Parse an intent reference string into its components.

   Parameters:
   - ref-str: string like \"Login.submit\" or \"Login.submit[1]\"

   Returns:
   - {:ok {:intent \"Login\" :element \"submit\" :index nil|int|:all}}
   - {:error {:type :intent/invalid-reference ...}}"
  [ref-str]
  (if-let [match (re-matches intent-ref-pattern ref-str)]
    (let [[_ intent-name element-name index-str] match
          index (cond
                  (nil? index-str) nil
                  (= "*" index-str) :all
                  :else (parse-long index-str))]
      {:ok {:intent intent-name
            :element element-name
            :index index
            :raw ref-str}})
    {:error {:type :intent/invalid-reference
             :message (str "Invalid intent reference: \"" ref-str
                           "\". Expected format: Intent.element or Intent.element[n]")
             :input ref-str}}))

(defn intent-ref?
  "Returns true if the string looks like an intent reference.
   Quick check: doesn't start with { (which would be raw EDN)."
  [s]
  (and (string? s)
       (not (str/blank? s))
       (not (str/starts-with? s "{"))))

;; -----------------------------------------------------------------------------
;; Resolution
;; -----------------------------------------------------------------------------

(defn- apply-css-index
  "Apply index to a CSS selector.
   - [n] positive → :nth-child(n)
   - [-n] negative → :nth-last-child(n)
   - [*] or nil → no modification"
  [css-selector index]
  (cond
    (nil? index) css-selector
    (= :all index) css-selector
    (pos? index) (str css-selector ":nth-child(" index ")")
    (neg? index) (str css-selector ":nth-last-child(" (abs index) ")")))

(defn- apply-xpath-index
  "Apply index to an XPath selector.
   - [n] positive → [n]
   - [-n] negative → [last()-n+1] (xpath is 1-indexed)
   - [*] or nil → no modification"
  [xpath-selector index]
  (cond
    (nil? index) xpath-selector
    (= :all index) xpath-selector
    (pos? index) (str "(" xpath-selector ")[" index "]")
    (neg? index) (str "(" xpath-selector ")[last()" (when (< index -1) (str "-" (dec (abs index)))) "]")))

(defn- apply-index-to-binding
  "Apply index to a locator binding based on its type."
  [binding index]
  (cond
    (:css binding)
    (update binding :css apply-css-index index)

    (:xpath binding)
    (update binding :xpath apply-xpath-index index)

    ;; For other types (id, accessibility-id, etc.), indexing doesn't apply
    ;; Return as-is and let the caller handle if needed
    :else
    binding))

(defn resolve-intent-ref
  "Resolve a parsed intent reference to a concrete locator.

   Parameters:
   - intents: loaded intents map from loader/load-all-intents
   - parsed-ref: parsed ref from parse-intent-ref (the :ok value)
   - interface: keyword like :web or :mobile

   Returns:
   - {:ok {:css \"...\"}} or {:ok {:xpath \"...\"}} etc.
   - {:error {...}} if intent/element not found or no binding for interface"
  [intents parsed-ref interface]
  (let [{:keys [intent element index]} parsed-ref
        binding-result (loader/get-binding intents intent element interface)]
    (if (:error binding-result)
      binding-result
      {:ok (apply-index-to-binding (:ok binding-result) index)})))

(defn resolve-intent-string
  "Parse and resolve an intent reference string in one step.

   Parameters:
   - intents: loaded intents map
   - ref-str: string like \"Login.submit[1]\"
   - interface: keyword like :web

   Returns:
   - {:ok {:css \"...\"}} on success
   - {:error {...}} on parse or resolution failure"
  [intents ref-str interface]
  (let [parse-result (parse-intent-ref ref-str)]
    (if (:error parse-result)
      parse-result
      (resolve-intent-ref intents (:ok parse-result) interface))))
