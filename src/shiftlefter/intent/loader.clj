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
   - Element names: lowercase first letter, kebab or lowerCamel (e.g., submit,
     email-input, add-to-cart, smsCode). An element with a single-group
     `:shape` binds to its own name and must be lowerCamel (sl-ectw).

   ## :shape (sl-ectw)

   An element may declare the content pattern of the text it owns:

   ```edn
   :smsCode {:bindings {:sms {}}
             :shape \"verification code is: (\\\\d{6})\"}
   ```

   Analyzed at load by `shiftlefter.intent.shape` (compiles; ≥1 group; no
   {token}; a single group binds to the element's own name; several groups
   must all be named). The lookup entry carries `:shape {:source :pattern
   :names :group-count}`; `get-shape` reads it.

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
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [shiftlefter.browser.locators :as locators]
            [shiftlefter.edn-lines :as edn-lines]
            [shiftlefter.gherkin.io :as sio]
            [shiftlefter.intent.shape :as shape]))

;; -----------------------------------------------------------------------------
;; Specs — :collections / :root schema (acceptance #6; boundary = EDN file load)
;; -----------------------------------------------------------------------------

;; A per-interface locator map, e.g. {:web {:css "..."} :mobile {...}}.
(s/def ::interface-locator (s/map-of keyword? map? :min-count 1))

;; An intent's self-locating root: the :unrooted sentinel or a located map.
(s/def :region/root (s/or :unrooted #{:unrooted}
                          :located ::interface-locator))

;; An intent's optional named location (sl-3jr4): per-interface, each value an
;; OPEN map — never a bare string — with an optional :path (leading "/").
;; Open-map + optional :path are the forward-compat guards for named bases
;; (sl-4mv8).
(s/def :location/path
  (s/with-gen (s/and string? #(str/starts-with? % "/"))
    #(gen/fmap (fn [s] (str "/" s)) (gen/string-alphanumeric))))
(s/def ::location-binding (s/keys :opt-un [:location/path]))
(s/def :intent/location (s/map-of keyword? ::location-binding :min-count 1))

;; One :collections entry. :intent (the referenced component) is required; the
;; parent :selector, :cardinality, :optional and :count are all optional.
(s/def :collection/intent string?)
(s/def :collection/selector ::interface-locator)
(s/def :collection/cardinality #{:many :one})
(s/def :collection/optional boolean?)
(s/def :collection/count map?)
(s/def ::collection
  (s/keys :req-un [:collection/intent]
          :opt-un [:collection/selector :collection/cardinality
                   :collection/optional :collection/count]))

(s/def :region/collections (s/map-of keyword? ::collection))

;; -----------------------------------------------------------------------------
;; Casing Validation
;; -----------------------------------------------------------------------------

(def ^:private pascal-case-pattern
  "Regex for PascalCase: starts with uppercase, then alphanumeric/hyphen/underscore."
  #"^[A-Z][A-Za-z0-9_-]*$")

(def ^:private lowercase-pattern
  "Regex for element and collection names: a lowercase first letter (what
   keeps them distinct from PascalCase intents), then letters, digits,
   hyphen or underscore — kebab (`add-to-cart`) and lowerCamel (`smsCode`)
   both read (sl-ectw). An element with a single-group `:shape` binds to
   its own name and must therefore be lowerCamel; `intent.shape` enforces
   that where the shape is analyzed."
  #"^[a-z][A-Za-z0-9_-]*$")

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
     :message (str "Element name must start with a lowercase letter "
                   "(kebab or lowerCamel): " element-key
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

(defn- validate-element-shape
  "Validate an element's optional `:shape` (sl-ectw) via
   `intent.shape/analyze-shape`. Returns error or nil; the error carries
   the element's def-site line when the index pass vouched for the file."
  [element-key element-def intent-name file-path element-line]
  (when (and (map? element-def) (contains? element-def :shape))
    (when-let [e (:error (shape/analyze-shape (name element-key)
                                              (:shape element-def)))]
      (cond-> (assoc e
                     :message (str (:message e) " (intent \"" intent-name "\")")
                     :intent intent-name
                     :element element-key
                     :path file-path)
        element-line (assoc :line element-line)))))

(defn- validate-element
  "Validate a single element. Returns vector of errors (may be empty)."
  [element-key element-def intent-name file-path element-line]
  (filterv some?
           [(validate-element-name element-key intent-name file-path)
            (validate-element-bindings element-key element-def intent-name file-path)
            (validate-element-shape element-key element-def intent-name file-path
                                    element-line)]))

(defn- validate-elements
  "Validate all elements in an intent. Returns vector of errors.
   `element-lines` is the def-site `{name line}` map (sl-u2r0), may be nil."
  [elements intent-name file-path element-lines]
  (if-not (map? elements)
    [{:type :intent/invalid-elements
      :message (str "Intent \"" intent-name "\" :elements must be a map")
      :intent intent-name
      :path file-path}]
    (into []
          (mapcat (fn [[k v]]
                    (validate-element k v intent-name file-path
                                      (get element-lines (name k)))))
          elements)))

;; -----------------------------------------------------------------------------
;; :collections / :root validation (per-file; nesting phase, sl-tl9)
;; -----------------------------------------------------------------------------

(defn- validate-root
  "Validate an intent's optional :root. Returns error or nil."
  [root intent-name file-path]
  (when (and (some? root) (not (s/valid? :region/root root)))
    {:type :intent/invalid-root
     :message (str "Intent \"" intent-name "\" :root must be :unrooted or a "
                   "per-interface locator map, got: " (pr-str root))
     :intent intent-name
     :path file-path}))

(defn- validate-location
  "Validate an intent's optional :location. Returns error or nil."
  [location intent-name file-path]
  (when (and (some? location) (not (s/valid? :intent/location location)))
    {:type :intent/invalid-location
     :message (str "Intent \"" intent-name "\" :location must be a per-interface "
                   "map of maps (each with an optional :path starting with \"/\"), "
                   "got: " (pr-str location))
     :intent intent-name
     :path file-path}))

(defn- validate-collection
  "Validate one :collections entry. Returns vector of errors (may be empty)."
  [coll-key coll-def intent-name file-path]
  (let [base [(when-not (lowercase-element? coll-key)
                {:type :intent/invalid-collection-name
                 :message (str "Collection name must be lowercase: " coll-key
                               " in intent \"" intent-name "\"")
                 :intent intent-name :collection coll-key :path file-path})
              (when-not (and (map? coll-def) (string? (:intent coll-def)))
                {:type :intent/missing-collection-intent
                 :message (str "Collection " coll-key " in intent \"" intent-name
                               "\" must reference a component via a string :intent")
                 :intent intent-name :collection coll-key :path file-path})]]
    (filterv some?
             (if (and (map? coll-def) (string? (:intent coll-def))
                      (not (s/valid? ::collection coll-def)))
               (conj base {:type :intent/invalid-collection
                           :message (str "Collection " coll-key " in intent \""
                                         intent-name "\" has an invalid shape: "
                                         (s/explain-str ::collection coll-def))
                           :intent intent-name :collection coll-key :path file-path})
               base))))

(defn- validate-collections
  "Validate an intent's optional :collections map. Returns vector of errors."
  [collections intent-name file-path]
  (cond
    (nil? collections) []
    (not (map? collections))
    [{:type :intent/invalid-collections
      :message (str "Intent \"" intent-name "\" :collections must be a map")
      :intent intent-name :path file-path}]
    :else
    (into [] (mapcat (fn [[k v]] (validate-collection k v intent-name file-path)))
          collections)))

(defn- validate-no-name-collision
  "A name may not be both an element and a collection in one intent — the
   resolver checks collections before elements, so a collision would shadow.
   Returns vector of errors."
  [elements collections intent-name file-path]
  (let [el-names (when (map? elements) (set (map name (keys elements))))
        coll-names (when (map? collections) (set (map name (keys collections))))
        clashes (vec (sort (set/intersection (or el-names #{})
                                             (or coll-names #{}))))]
    (mapv (fn [nm]
            {:type :intent/name-collision
             :message (str "Name \"" nm "\" in intent \"" intent-name
                           "\" is both an element and a collection — rename one")
             :intent intent-name :name nm :path file-path})
          clashes)))

(defn- validate-intent-structure
  "Validate a parsed intent map. Returns vector of errors (may be empty).
   `def-site` is the intent's sl-u2r0 def-site map (element lines feed the
   :shape errors); may be nil."
  [intent-data file-path def-site]
  (let [intent-name (:intent intent-data)
        elements (:elements intent-data)
        collections (:collections intent-data)
        root (:root intent-data)
        location (:location intent-data)]
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

      ;; Missing both :elements and :collections — an empty intent is meaningless.
      ;; A collections-only intent legitimately omits :elements (empty by default).
      (and (nil? elements) (nil? collections))
      [{:type :intent/missing-elements
        :message (str "Intent \"" intent-name "\" must declare :elements and/or :collections")
        :intent intent-name
        :path file-path}]

      ;; Validate name casing, elements, and (optional) :collections / :root
      :else
      (let [elements (or elements {})]
        (-> (filterv some? [(validate-intent-name intent-name file-path)
                            (validate-root root intent-name file-path)
                            (validate-location location intent-name file-path)])
            (into (validate-elements elements intent-name file-path
                                     (:elements def-site)))
            (into (validate-collections collections intent-name file-path))
            (into (validate-no-name-collision elements collections
                                              intent-name file-path)))))))

;; -----------------------------------------------------------------------------
;; File Loading
;; -----------------------------------------------------------------------------

(defn- intent-def-site
  "sl-u2r0: where this intent and its parts are declared —
   `{:file path :line n :elements {name line} :collections {name line}}`,
   lines from the index metadata when the pass vouched for the file, else
   file-only (every line key absent)."
  [intent-data path]
  (let [indexed (::lines (meta intent-data))
        names (fn [m] (into {} (map (fn [[k line]] [(name k) line])) m))
        elements (names (edn-lines/entry-lines indexed [:elements]))
        collections (names (edn-lines/entry-lines indexed [:collections]))]
    (cond-> {:file path}
      (:line (meta indexed)) (assoc :line (:line (meta indexed)))
      (seq elements) (assoc :elements elements)
      (seq collections) (assoc :collections collections))))

(defn- read-edn-file
  "Read EDN from a filesystem path.
   Returns {:ok data} or {:error {...}}. The data carries the def-site
   line index as `::lines` metadata (sl-u2r0)."
  [path]
  (try
    (let [content (sio/slurp-utf8 path)]
      (if (:status content)
        ;; slurp-utf8 returned an error map
        {:error {:type :intent/read-failed
                 :message (str "Failed to read intent file: " (:message content))
                 :path path}}
        (let [data (edn/read-string content)]
          ;; sl-u2r0: the def-site line index rides as metadata — the
          ;; position-tracking read of the same text, or nil when that
          ;; pass could not vouch for itself (shiftlefter.edn-lines/index).
          {:ok (if (instance? clojure.lang.IObj data)
                 (vary-meta data assoc ::lines (edn-lines/index content data))
                 data)})))
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
            ;; sl-ectw: the def-site is computed BEFORE validation so a
            ;; :shape error can cite the element's line.
            def-site (intent-def-site intent-data path-str)
            validation-errors (validate-intent-structure intent-data path-str
                                                         def-site)]
        (if (seq validation-errors)
          {:errors validation-errors}
          {:ok (assoc intent-data
                      :source-file path-str
                      :def-site def-site)})))))

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
              (cond-> (merge (select-keys element-def [:description :collection])
                             {:bindings (:bindings element-def)
                              :intent intent-name
                              :element (name element-key)})
                ;; sl-ectw: the analyzed shape (validated upstream, so :ok).
                (contains? element-def :shape)
                (assoc :shape (:ok (shape/analyze-shape (name element-key)
                                                        (:shape element-def)))))))
     {}
     (:elements intent-data))))

(defn- build-region
  "Build the per-intent region entry (parallel to the element lookup): the
   intent's :root, :location, :collections, :reusable marker, and the set of
   element names. This is the schema the nested resolver and the static path
   check consult."
  [intent-data]
  {:root (:root intent-data)
   :location (:location intent-data)
   :collections (or (:collections intent-data) {})
   :reusable (boolean (:reusable intent-data))
   :elements (set (map name (keys (:elements intent-data))))})

(defn- merge-intent-lookups
  "Merge multiple intent lookups, checking for duplicate intent names.
   Returns {:ok merged-lookup :regions {...} :intents [...]} or {:errors [...]}."
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
               (assoc-in [:regions intent-name] (build-region ok))
               (assoc-in [:intent-files intent-name] (:source-file ok))
               ;; sl-u2r0: per-intent def-sites (file + entry lines).
               (assoc-in [:def-sites intent-name] (:def-site ok))
               (update :intents (fnil conj []) intent-name))))))
   {:ok {} :regions {} :intent-files {} :def-sites {} :intents []}
   intent-results))

;; -----------------------------------------------------------------------------
;; Cross-intent fallback validation (§7.5) — runs once, after all files merge
;; -----------------------------------------------------------------------------

(defn- concrete-root?
  "True if a region's :root can self-locate the component — a located map, not
   the :unrooted sentinel and not absent."
  [root]
  (and (map? root) (seq root)))

(defn- validate-collection-anchor
  "Validate one collection's :root/:selector anchor (§7.5). `regions` is the full
   per-intent map. Returns an error map or nil.

   Rules: (1) the collection has its own :selector → ok; (2) else the referenced
   component declares a concrete :root → ok; (3) else a loud load error. A
   reference to an unknown component intent is its own distinct error."
  [regions parent-name coll-key coll-def]
  (let [component (:intent coll-def)
        component-region (get regions component)]
    (cond
      (some? (:selector coll-def)) nil

      (nil? component-region)
      {:type :intent/unknown-component
       :message (str parent-name "." (name coll-key) " references " component
                     ", but no intent named " component " is loaded.")
       :intent parent-name :collection coll-key :component component}

      (concrete-root? (:root component-region)) nil

      :else
      {:type :intent/missing-anchor
       :message (str parent-name "." (name coll-key) " references " component
                     ", which declares no :root and was given no :selector"
                     " — add one or the other.")
       :intent parent-name :collection coll-key :component component})))

(defn- validate-fallbacks
  "Walk every collection in every region; collect anchor errors (§7.5)."
  [regions]
  (into []
        (mapcat (fn [[parent-name region]]
                  (keep (fn [[coll-key coll-def]]
                          (validate-collection-anchor regions parent-name
                                                       coll-key coll-def))
                        (:collections region))))
        regions))

;; -----------------------------------------------------------------------------
;; Instance-boundary precompute (§8.1 nearest-enclosing-instance, sl-h7h)
;;
;; For each region K, the :web boundary set is the CSS union of its declared
;; collections' effective instance selectors (each collection's :selector else
;; its component's :root — the §7.5 fallback). The resolver passes this union to
;; `query-all-pruned` so a match inside a nested instance is excluded. Computed
;; once at load; a :web boundary selector that is not CSS-expressible (e.g.
;; XPath — `closest()` can't use it) is a loud load error (acceptance #4).
;; Web-only for 0.5; mobile (XPath ancestor axes) defers.
;; -----------------------------------------------------------------------------

(defn- effective-web-boundary-css
  "The :web boundary CSS for one collection: its `:selector` :web binding else
   the referenced component's concrete `:root` :web binding. Returns
   `{:ok css-or-nil}` (nil = no :web instance selector, contributes nothing) or
   `{:errors [...]}` when a present :web selector is not CSS-expressible."
  [regions parent-name coll-key coll-def]
  (let [selector  (get-in coll-def [:selector :web])
        comp-root (get-in regions [(:intent coll-def) :root])
        root-web  (when (concrete-root? comp-root) (:web comp-root))
        locator   (or selector root-web)]
    (if (nil? locator)
      {:ok nil}
      (let [r (locators/locator->css locator)]
        (if (:ok r)
          {:ok (:ok r)}
          {:errors [{:type :intent/boundary-not-css
                     :message (str parent-name "." (name coll-key)
                                   " is an instance boundary whose :web selector "
                                   "is not expressible as CSS (required for "
                                   "nearest-enclosing-instance pruning): "
                                   (pr-str locator))
                     :intent parent-name :collection coll-key
                     :component (:intent coll-def)}]})))))

(defn- region-web-boundary
  "The :web boundary union for parent K. Returns `{:ok css-string}` (the
   comma-joined union, possibly \"\") or `{:errors [...]}`."
  [regions parent-name region]
  (let [results (map (fn [[coll-key coll-def]]
                       (effective-web-boundary-css regions parent-name coll-key coll-def))
                     (:collections region))
        errors  (mapcat :errors results)]
    (if (seq errors)
      {:errors (vec errors)}
      {:ok (str/join ", " (keep :ok results))})))

(defn- compute-boundaries
  "Build `{intent-name {:web css-union}}` for every region, validating :web
   boundary selectors as CSS. Returns `{:ok boundaries}` or `{:errors [...]}`."
  [regions]
  (reduce (fn [acc [parent-name region]]
            (let [r (region-web-boundary regions parent-name region)]
              (if (:errors r)
                (update acc :errors into (:errors r))
                (assoc-in acc [:ok parent-name :web] (:ok r)))))
          {:ok {} :errors []}
          regions))

;; -----------------------------------------------------------------------------
;; Public API
;; -----------------------------------------------------------------------------

(declare load-all-intents*)

(defn load-all-intents
  "Load all intent files from the specified directory.

   Parameters:
   - intents-dir: path to directory containing intent .edn files

   Returns:
   - {:ok {:lookup {...} :regions {...} :intents [...]}} on success
     - :lookup is {[intent element] {:bindings {...} ...}}  (element-keyed)
     - :regions is {intent-name {:root ... :collections {...} :reusable bool
       :elements #{...}}}  (per-intent; the nesting schema)
     - :intents is list of loaded intent names
   - {:errors [...]} on any validation or loading failure (including the
     cross-intent :root/:selector anchor check, §7.5)

   If the directory doesn't exist, returns {:ok {:lookup {} :regions {} :intents []}}
   (intents are optional). Every :ok carries `:intents-dir` (sl-u2r0), so a
   did-you-mean with nothing to suggest can still say where intents are
   declared (a scaffold whose only intent is still an .example)."
  [intents-dir]
  (let [result (load-all-intents* intents-dir)]
    (cond-> result
      (:ok result) (assoc-in [:ok :intents-dir] (str intents-dir)))))

(defn- load-all-intents*
  "load-all-intents without the :intents-dir stamp."
  [intents-dir]
  (if-not (fs/exists? intents-dir)
    ;; Directory doesn't exist — intents are optional
    {:ok {:lookup {} :regions {} :intents [] :intent-files {}}}
    (let [edn-files (fs/glob intents-dir "*.edn")
          file-paths (map str edn-files)]
      (if (empty? file-paths)
        ;; No files — empty but valid
        {:ok {:lookup {} :regions {} :intents [] :intent-files {}}}
        ;; Load and merge all files
        (let [results (map load-intent-file file-paths)
              merged (merge-intent-lookups results)]
          (if (seq (:errors merged))
            {:errors (:errors merged)}
            ;; All files structurally valid — now the cross-intent anchor check.
            (let [anchor-errors (validate-fallbacks (:regions merged))]
              (if (seq anchor-errors)
                {:errors anchor-errors}
                ;; …then precompute :web instance boundaries (§8.1), which also
                ;; surfaces non-CSS boundary selectors as a loud load error.
                (let [boundaries (compute-boundaries (:regions merged))]
                  (if (seq (:errors boundaries))
                    {:errors (:errors boundaries)}
                    {:ok {:lookup (:ok merged)
                          :regions (:regions merged)
                          :boundaries (:ok boundaries)
                          :intents (:intents merged)
                          ;; sl-mpu5: per-intent def-site files (built during
                          ;; merge, previously dropped) — duplicates report and
                          ;; explain :def-site cite files, not the directory.
                          :intent-files (:intent-files merged)
                          ;; sl-u2r0: `{intent-name {:file :line :elements
                          ;; {name line} :collections {name line}}}` — the
                          ;; def-site every object did-you-mean names.
                          :def-sites (:def-sites merged)}}))))))))))

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

(defn element-def-site
  "Where `intent-name.element-name` is declared (sl-u2r0 lines, sl-ectw
   consumer): `{:file path :line n}` (line absent when the index pass could
   not vouch for the file), or nil for an unknown intent."
  [intents intent-name element-name]
  (when-let [site (get-in intents [:def-sites intent-name])]
    (cond-> {:file (:file site)}
      (get-in site [:elements element-name])
      (assoc :line (get-in site [:elements element-name])))))

(defn get-shape
  "The analyzed `:shape` of `intent-name.element-name` (sl-ectw):
   `{:ok {:source :pattern :names :group-count}}`, or `{:error {...}}` with
   `:intent/unknown-element` or `:intent/element-has-no-shape` (the latter
   carrying `:def-site`)."
  [intents intent-name element-name]
  (if-let [entry (get (:lookup intents) [intent-name element-name])]
    (if-let [shape (:shape entry)]
      {:ok shape}
      {:error (cond-> {:type :intent/element-has-no-shape
                       :message (str intent-name "." element-name
                                     " declares no :shape")
                       :intent intent-name
                       :element element-name}
                (element-def-site intents intent-name element-name)
                (assoc :def-site (element-def-site intents intent-name element-name)))})
    {:error {:type :intent/unknown-element
             :message (str "Unknown intent/element: " intent-name "." element-name)
             :intent intent-name
             :element element-name}}))

(defn get-region
  "Return the per-intent region entry (`{:root :location :collections :reusable
   :elements}`) for `intent-name`, or nil if unknown. The nesting schema the
   resolver consults."
  [intents intent-name]
  (get-in intents [:regions intent-name]))

(defn get-root
  "Return the :root of `intent-name` (a per-interface locator map, the :unrooted
   sentinel, or nil if none/unknown)."
  [intents intent-name]
  (get-in intents [:regions intent-name :root]))

(defn get-location
  "Return the :location of `intent-name` (a per-interface map of open maps with
   an optional :path, or nil if none/unknown). :location is OPTIONAL — component
   intents legitimately carry none (sl-3jr4)."
  [intents intent-name]
  (get-in intents [:regions intent-name :location]))

(defn get-collection
  "Return the :collections entry named `coll-name` (string) within `intent-name`,
   or nil if `intent-name` has no such collection. `coll-name` is looked up as a
   keyword (collections are keyword-keyed)."
  [intents intent-name coll-name]
  (get-in intents [:regions intent-name :collections (keyword coll-name)]))

(defn get-boundary-css
  "Return the precomputed instance-boundary CSS union for resolving WITHIN an
   instance of `intent-name` at `interface` (§8.1, sl-h7h) — the CSS union of
   the component's declared collections' effective instance selectors.

   Returns \"\" when the component declares no collections, the interface has no
   boundaries (only :web is precomputed for 0.5), or the intent is unknown — in
   every case the resolver does no pruning (`query-all-pruned` with a blank
   boundary == `query-all`)."
  [intents intent-name interface]
  (or (get-in intents [:boundaries intent-name interface]) ""))

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
