(ns shiftlefter.intent.loader
  "Intent file loading and validation for ShiftLefter.

   ## Intent File Format

   ```edn
   {:intent \"Login\"
    :description \"User authentication flow\"

    :elements
    {:email
     {:description \"Email input field\"
      :bindings {:web {:css \"#email\"}
                 :mobile {:accessibility-id \"email-input\"}}}

     :submit
     {:description \"Submit button\"
      :collection true  ;; optional: element can have multiple matches
      :bindings {:web {:css \"button[type=submit]\"}}}}}
   ```

   ## Naming Conventions

   - Intent names: PascalCase (e.g., Login, BuyBox, FrequentlyBoughtTogether)
   - Element names: lowercase with hyphens (e.g., submit, email-input, add-to-cart)

   ## Usage

   ```clojure
   (let [result (load-all-intents \"glossary/intents\")]
     (if (:ok result)
       (let [intents (:ok result)]
         (get-binding intents \"Login\" \"submit\" :web))
       (handle-errors (:errors result))))
   ```"
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [shiftlefter.gherkin.io :as sio]))

;; -----------------------------------------------------------------------------
;; Casing Validation
;; -----------------------------------------------------------------------------

(def ^:private pascal-case-pattern
  "Regex for PascalCase: starts with uppercase, then alphanumeric/hyphen/underscore."
  #"^[A-Z][A-Za-z0-9_-]*$")

(def ^:private lowercase-pattern
  "Regex for lowercase element names: starts with lowercase, then lowercase/digits/hyphen/underscore."
  #"^[a-z][a-z0-9_-]*$")

(defn- pascal-case?
  "Returns true if string is PascalCase."
  [s]
  (and (string? s)
       (re-matches pascal-case-pattern s)))

(defn- lowercase-element?
  "Returns true if keyword name is valid lowercase element format."
  [kw]
  (and (keyword? kw)
       (re-matches lowercase-pattern (name kw))))

;; -----------------------------------------------------------------------------
;; Validation Helpers
;; -----------------------------------------------------------------------------

(defn- validate-intent-name
  "Validate intent name is PascalCase. Returns error or nil."
  [intent-name file-path]
  (when-not (pascal-case? intent-name)
    {:type :intent/invalid-name
     :message (str "Intent name must be PascalCase: \"" intent-name "\"")
     :intent intent-name
     :path file-path}))

(defn- validate-element-name
  "Validate element name is lowercase. Returns error or nil."
  [element-key intent-name file-path]
  (when-not (lowercase-element? element-key)
    {:type :intent/invalid-element-name
     :message (str "Element name must be lowercase: " element-key
                   " in intent \"" intent-name "\"")
     :intent intent-name
     :element element-key
     :path file-path}))

(defn- validate-element-bindings
  "Validate element has bindings map. Returns error or nil."
  [element-key element-def intent-name file-path]
  (when-not (and (map? (:bindings element-def))
                 (seq (:bindings element-def)))
    {:type :intent/missing-bindings
     :message (str "Element " element-key " in intent \"" intent-name
                   "\" must have non-empty :bindings map")
     :intent intent-name
     :element element-key
     :path file-path}))

(defn- validate-element
  "Validate a single element. Returns vector of errors (may be empty)."
  [element-key element-def intent-name file-path]
  (filterv some?
           [(validate-element-name element-key intent-name file-path)
            (validate-element-bindings element-key element-def intent-name file-path)]))

(defn- validate-elements
  "Validate all elements in an intent. Returns vector of errors."
  [elements intent-name file-path]
  (if-not (map? elements)
    [{:type :intent/invalid-elements
      :message (str "Intent \"" intent-name "\" :elements must be a map")
      :intent intent-name
      :path file-path}]
    (into []
          (mapcat (fn [[k v]]
                    (validate-element k v intent-name file-path)))
          elements)))

(defn- validate-intent-structure
  "Validate a parsed intent map. Returns vector of errors (may be empty)."
  [intent-data file-path]
  (let [intent-name (:intent intent-data)
        elements (:elements intent-data)]
    (cond
      ;; Missing :intent key
      (nil? intent-name)
      [{:type :intent/missing-intent-key
        :message "Intent file must have :intent key with string value"
        :path file-path}]

      ;; :intent not a string
      (not (string? intent-name))
      [{:type :intent/invalid-intent-key
        :message (str ":intent must be a string, got: " (type intent-name))
        :path file-path}]

      ;; Missing :elements
      (nil? elements)
      [{:type :intent/missing-elements
        :message (str "Intent \"" intent-name "\" must have :elements map")
        :intent intent-name
        :path file-path}]

      ;; Validate name casing and elements
      :else
      (into (filterv some? [(validate-intent-name intent-name file-path)])
            (validate-elements elements intent-name file-path)))))

;; -----------------------------------------------------------------------------
;; File Loading
;; -----------------------------------------------------------------------------

(defn- read-edn-file
  "Read EDN from a filesystem path.
   Returns {:ok data} or {:error {...}}."
  [path]
  (try
    (let [content (sio/slurp-utf8 path)]
      (if (:status content)
        ;; slurp-utf8 returned an error map
        {:error {:type :intent/read-failed
                 :message (str "Failed to read intent file: " (:message content))
                 :path path}}
        {:ok (edn/read-string content)}))
    (catch Exception e
      {:error {:type :intent/parse-failed
               :message (str "Failed to parse intent EDN: " (ex-message e))
               :path path}})))

(defn- load-intent-file
  "Load and validate a single intent file.
   Returns {:ok intent-data} or {:errors [...]}."
  [file-path]
  (let [path-str (str file-path)
        read-result (read-edn-file path-str)]
    (if (:error read-result)
      {:errors [(:error read-result)]}
      (let [intent-data (:ok read-result)
            validation-errors (validate-intent-structure intent-data path-str)]
        (if (seq validation-errors)
          {:errors validation-errors}
          {:ok (assoc intent-data :source-file path-str)})))))

;; -----------------------------------------------------------------------------
;; Building the Lookup Structure
;; -----------------------------------------------------------------------------

(defn- build-element-lookup
  "Build lookup entries for all elements in an intent.
   Returns map of {[intent-name element-name] {:bindings {...} ...}}."
  [intent-data]
  (let [intent-name (:intent intent-data)]
    (reduce-kv
     (fn [acc element-key element-def]
       (assoc acc
              [intent-name (name element-key)]
              (merge (select-keys element-def [:description :collection])
                     {:bindings (:bindings element-def)
                      :intent intent-name
                      :element (name element-key)})))
     {}
     (:elements intent-data))))

(defn- merge-intent-lookups
  "Merge multiple intent lookups, checking for duplicate intent names.
   Returns {:ok merged-lookup} or {:errors [...]}."
  [intent-results]
  (reduce
   (fn [acc {:keys [ok errors]}]
     (cond
       ;; Propagate existing errors
       (seq (:errors acc))
       acc

       ;; New errors from this file
       (seq errors)
       {:errors (into (or (:errors acc) []) errors)}

       ;; Check for duplicate intent name
       :else
       (let [intent-name (:intent ok)
             existing-file (get-in acc [:intent-files intent-name])]
         (if existing-file
           {:errors [{:type :intent/duplicate-intent
                      :message (str "Duplicate intent name \"" intent-name
                                    "\" found in " (:source-file ok)
                                    " and " existing-file)
                      :intent intent-name
                      :files [existing-file (:source-file ok)]}]}
           (-> acc
               (update :ok merge (build-element-lookup ok))
               (assoc-in [:intent-files intent-name] (:source-file ok))
               (update :intents (fnil conj []) intent-name))))))
   {:ok {} :intent-files {} :intents []}
   intent-results))

;; -----------------------------------------------------------------------------
;; Public API
;; -----------------------------------------------------------------------------

(defn load-all-intents
  "Load all intent files from the specified directory.

   Parameters:
   - intents-dir: path to directory containing intent .edn files

   Returns:
   - {:ok {:lookup {...} :intents [...]}} on success
     - :lookup is {[intent element] {:bindings {...} ...}}
     - :intents is list of loaded intent names
   - {:errors [...]} on any validation or loading failure

   If the directory doesn't exist, returns {:ok {:lookup {} :intents []}}
   (intents are optional)."
  [intents-dir]
  (if-not (fs/exists? intents-dir)
    ;; Directory doesn't exist — intents are optional
    {:ok {:lookup {} :intents []}}
    (let [edn-files (fs/glob intents-dir "*.edn")
          file-paths (map str edn-files)]
      (if (empty? file-paths)
        ;; No files — empty but valid
        {:ok {:lookup {} :intents []}}
        ;; Load and merge all files
        (let [results (map load-intent-file file-paths)
              merged (merge-intent-lookups results)]
          (if (seq (:errors merged))
            {:errors (:errors merged)}
            {:ok {:lookup (:ok merged)
                  :intents (:intents merged)}}))))))

(defn get-binding
  "Look up the binding for an intent/element/interface combination.

   Parameters:
   - intents: the loaded intents map from load-all-intents
   - intent-name: string, e.g., \"Login\"
   - element-name: string, e.g., \"submit\"
   - interface: keyword, e.g., :web

   Returns:
   - {:ok {:css \"...\"}} or similar on success
   - {:error {...}} if intent, element, or binding not found"
  [intents intent-name element-name interface]
  (let [lookup (:lookup intents)
        key [intent-name element-name]]
    (if-let [entry (get lookup key)]
      (if-let [binding (get-in entry [:bindings interface])]
        {:ok binding}
        {:error {:type :intent/no-binding-for-interface
                 :message (str "No " (name interface) " binding for "
                              intent-name "." element-name)
                 :intent intent-name
                 :element element-name
                 :interface interface
                 :available-interfaces (vec (keys (:bindings entry)))}})
      {:error {:type :intent/unknown-element
               :message (str "Unknown intent/element: " intent-name "." element-name)
               :intent intent-name
               :element element-name}})))

(defn known-intent?
  "Returns true if the intent name is loaded."
  [intents intent-name]
  (some #{intent-name} (:intents intents)))

(defn known-element?
  "Returns true if the intent/element combination exists."
  [intents intent-name element-name]
  (contains? (:lookup intents) [intent-name element-name]))

(defn all-intents
  "Returns list of all loaded intent names."
  [intents]
  (:intents intents))

(defn elements-of-intent
  "Returns list of element names for an intent."
  [intents intent-name]
  (->> (:lookup intents)
       (keys)
       (filter #(= intent-name (first %)))
       (map second)
       (vec)))
