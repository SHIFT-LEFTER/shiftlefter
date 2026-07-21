(ns shiftlefter.intent.resolve
  "Intent reference parsing and resolution for ShiftLefter.

   ## Intent Reference Syntax

   ```
   Login.submit                       ;; flat reference (one segment)
   Login.submit[1]                    ;; 1-indexed (first element)
   Login.submit[-1]                   ;; negative index (last element)
   Login.submit[*]                    ;; wildcard (the whole collection)
   Bookmarks.tweet[2].quoted.author   ;; NESTED reference (collection hops + element)
   ```

   A reference is an **intent** followed by one-or-more `.name[idx]?` **segments**.
   Any segment may carry an index (`[n]`, `[-n]`, `[*]`). The last segment is
   normally the element; earlier segments are collection hops — but which is which
   is decided by the *resolver* against the loaded schema, not by syntax. The
   parser just produces a flat `:path`. At most one `[*]` per address (single-`[*]`
   MVP boundary — see `_docs/active/intent-addressing-nesting.md` §11).

   ## Naming Conventions

   - Intent names: PascalCase (starts with uppercase)
   - Segment names: lowercase (can include hyphens, underscores, digits)

   ## Usage

   ```clojure
   (parse-intent-ref \"Login.submit\")
   ;; => {:ok {:intent \"Login\"
   ;;          :path [{:name \"submit\" :index nil}]
   ;;          :raw \"Login.submit\"}}

   (parse-intent-ref \"Bookmarks.tweet[2].author\")
   ;; => {:ok {:intent \"Bookmarks\"
   ;;          :path [{:name \"tweet\" :index 2} {:name \"author\" :index nil}]
   ;;          :raw \"Bookmarks.tweet[2].author\"}}

   (parse-intent-ref \"invalid\")
   ;; => {:error {:type :intent/invalid-reference ...}}
   ```"
  (:require [shiftlefter.intent.loader :as loader]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]))

;; -----------------------------------------------------------------------------
;; Specs — parsed-reference shape (boundary between parser and resolver)
;; -----------------------------------------------------------------------------

(s/def ::intent string?)
(s/def ::raw string?)
(s/def :segment/name string?)
;; An index is absent (nil), an integer (positive/negative), or :all (a `[*]`).
(s/def :segment/index (s/nilable (s/or :int int? :all #{:all})))
(s/def ::segment (s/keys :req-un [:segment/name :segment/index]))
(s/def ::path (s/coll-of ::segment :kind vector? :min-count 1))
(s/def ::parsed-ref (s/keys :req-un [::intent ::path ::raw]))

;; -----------------------------------------------------------------------------
;; Parsing
;; -----------------------------------------------------------------------------

(def ^:private intent-name-pattern
  "Regex for the leading intent name: PascalCase."
  #"^[A-Z][A-Za-z0-9_-]*$")

(def ^:private segment-pattern
  "Regex for one `name[idx]?` segment.
   Groups: 1=segment-name (lowercase), 2=index (optional: -?\\d+ or *)."
  #"^([a-z][a-z0-9_-]*)(?:\[(-?\d+|\*)\])?$")

(defn- invalid-ref
  "Build the standard parse-failure result for `ref-str`."
  [ref-str]
  {:error {:type :intent/invalid-reference
           :message (str "Invalid intent reference: \"" ref-str
                         "\". Expected format: Intent.element, Intent.element[n], "
                         "or a nested address like Intent.collection[n].element")
           :input ref-str}})

(defn- parse-index
  "Parse an index capture string into nil | int | :all."
  [index-str]
  (cond
    (nil? index-str) nil
    (= "*" index-str) :all
    :else (parse-long index-str)))

(defn- parse-segment
  "Parse one `name[idx]?` token into `{:name :index}`, or nil if malformed."
  [token]
  (when-let [[_ nm index-str] (re-matches segment-pattern token)]
    {:name nm :index (parse-index index-str)}))

(defn parse-intent-ref
  "Parse an intent reference string into `{:intent :path :raw}`.

   Parameters:
   - ref-str: string like \"Login.submit\", \"Login.submit[1]\", or
     \"Bookmarks.tweet[2].quoted.author\"

   Returns:
   - {:ok {:intent <PascalCase>
           :path  [{:name <lowercase> :index nil|int|:all} ...]   ; >= 1 segment
           :raw   <ref-str>}}
   - {:error {:type :intent/invalid-reference ...}}    on a malformed reference
   - {:error {:type :intent/multiple-wildcards ...}}   on >1 `[*]` (single-[*] MVP)"
  [ref-str]
  (if-not (and (string? ref-str) (not (str/blank? ref-str)))
    (invalid-ref ref-str)
    (let [parts (str/split ref-str #"\." -1)
          intent-name (first parts)
          seg-tokens (rest parts)]
      (if-not (and (seq seg-tokens)
                   (re-matches intent-name-pattern intent-name))
        (invalid-ref ref-str)
        (let [segments (map parse-segment seg-tokens)]
          (cond
            (some nil? segments)
            (invalid-ref ref-str)

            (> (count (filter #(= :all (:index %)) segments)) 1)
            {:error {:type :intent/multiple-wildcards
                     :message (str "Multiple [*] wildcards in \"" ref-str
                                   "\" are not yet supported (single [*] per address).")
                     :input ref-str}}

            :else
            {:ok {:intent intent-name
                  :path (vec segments)
                  :raw ref-str}}))))))

(defn intent-ref?
  "Returns true if the string looks like an intent reference.
   Quick check: doesn't start with { (which would be raw EDN)."
  [s]
  (and (string? s)
       (not (str/blank? s))
       (not (str/starts-with? s "{"))))

;; -----------------------------------------------------------------------------
;; Flat resolution (base binding; no index, no nesting)
;; -----------------------------------------------------------------------------
;;
;; Resolution returns the **base binding** for an element — the per-interface
;; locator with NO index applied. Indexing (`[n]`/`[-n]`/`[*]`) and nested
;; collection hops are NOT handled here: those need the live DOM and are applied
;; at the browser boundary (`shiftlefter.browser.intent`). This namespace is the
;; pure layer used by callers that only need the flat element binding or a
;; browser-free structural check (e.g. SVO validation).

(defn resolve-intent-ref
  "Resolve a parsed reference to its concrete **base binding** (flat, no index).

   Parameters:
   - intents: loaded intents map from loader/load-all-intents
   - parsed-ref: parsed ref from parse-intent-ref (the :ok value)
   - interface: keyword like :web or :mobile

   Returns:
   - {:ok {:css \"...\"}} or {:ok {:xpath \"...\"}} etc. (the binding, index-free)
   - {:error {...}} if intent/element not found or no binding for interface

   The element is the LAST segment's name; any index and any earlier collection
   hops are ignored here (this is the flat path). Nested resolution lives in
   `shiftlefter.browser.intent/resolve-target`."
  [intents parsed-ref interface]
  (let [{:keys [intent path]} parsed-ref
        element (:name (last path))]
    (loader/get-binding intents intent element interface)))

(defn resolve-intent-string
  "Parse and resolve an intent reference string to its base binding (flat).

   Parameters:
   - intents: loaded intents map
   - ref-str: string like \"Login.submit[1]\"
   - interface: keyword like :web

   Returns:
   - {:ok {:css \"...\"}} on success (base binding; any [n]/[*] is NOT applied)
   - {:error {...}} on parse or resolution failure"
  [intents ref-str interface]
  (let [parse-result (parse-intent-ref ref-str)]
    (if (:error parse-result)
      parse-result
      (resolve-intent-ref intents (:ok parse-result) interface))))

;; -----------------------------------------------------------------------------
;; Static path validation (browser-free; for SVO :unknown-object)
;; -----------------------------------------------------------------------------

(defn validate-path-static
  "Statically validate a parsed multi-segment reference against the loaded
   schema, WITHOUT a browser. Walks the path: the intent must be known; each
   non-last segment must name a `:collections` entry (the walk descends into the
   referenced component intent); the last segment may be a collection (a terminal
   collection ref like `Bookmarks.tweet[2]`) or an element (checked via
   `get-binding` for `interface`).

   Returns nil when the whole path is structurally valid, else
   `{:message <human-readable> :segment <offending name>}`."
  [intents parsed-ref interface]
  (let [{:keys [intent path]} parsed-ref]
    (if-not (loader/known-intent? intents intent)
      {:message (str "Unknown intent \"" intent "\"") :segment intent}
      (loop [cur intent
             segs path]
        (let [{nm :name} (first segs)
              terminal? (nil? (next segs))
              coll (loader/get-collection intents cur nm)]
          (cond
            ;; A collection segment: terminal is fine; otherwise descend.
            coll
            (if terminal?
              nil
              (recur (:intent coll) (next segs)))

            ;; A non-terminal element segment can't have children.
            (and (not terminal?) (loader/known-element? intents cur nm))
            {:message (str "\"" nm "\" is an element in intent \"" cur
                           "\" and cannot have children")
             :segment nm}

            ;; Terminal element: confirm a binding exists for this interface.
            terminal?
            (let [r (loader/get-binding intents cur nm interface)]
              (when (:error r)
                {:message (-> r :error :message) :segment nm}))

            :else
            {:message (str "Unknown segment \"" nm "\" in intent \"" cur "\"")
             :segment nm}))))))

;; -----------------------------------------------------------------------------
;; Named locations (sl-3jr4)
;; -----------------------------------------------------------------------------
;;
;; The authored rule (sl-iseq): QUOTED = LITERAL, ALWAYS; BARE = REF — one
;; rule across the whole surface, matching element slots (addresses bare,
;; values quoted). A :location-kind slot value is an intent ref IFF it is a
;; bare PascalCase token (optionally dotted — dotted refs bind so they can
;; fail resolution with a did-you-mean instead of a confusing no-step-binds
;; error). Quoted strings, schemes, paths, and lowercase starts are literals.
;; This classifier is the one shared seam between planning-time SVO
;; validation and runtime step resolution — both must call it, or strict
;; mode drifts from execution.

(def ^:private location-ref-pattern
  "Bare location-ref token: PascalCase name, optional dotted segments."
  #"[A-Z][A-Za-z0-9_-]*(?:\.[A-Za-z0-9_-]+)*")

(defn location-ref?
  "True iff `s` is a bare (unquoted) PascalCase token — the ref form for
   :location slots. Quoted strings ('…') and anything with a scheme, slash,
   or lowercase first letter are literal URLs (sl-iseq: quoted = literal,
   always; bare = ref)."
  [s]
  (boolean (and (string? s) (re-matches location-ref-pattern s))))

(defn validate-location-static
  "Statically validate a named-location ref against the loaded schema, WITHOUT
   a browser: the intent must be known, must declare :location, and its entry
   for `interface` must carry a :path.

   Returns nil when valid, else {:type <kind> :message <human-readable>} where
   :type is :unknown-intent | :no-location | :no-location-for-interface
   (callers use it to attach did-you-mean suggestions, sl-q81m)."
  [intents intent-name interface]
  (let [location (loader/get-location intents intent-name)]
    (cond
      (not (loader/known-intent? intents intent-name))
      {:type :unknown-intent
       :message (str "Unknown intent \"" intent-name "\"")}

      (nil? location)
      {:type :no-location
       :message (str "Intent \"" intent-name "\" declares no :location")}

      (nil? (get-in location [interface :path]))
      {:type :no-location-for-interface
       :message (str "Intent \"" intent-name "\" :location has no :path for "
                     "interface " interface)}

      :else nil)))

(defn located-intents
  "Names of loaded intents whose :location carries a :path for `interface` —
   the did-you-mean candidate pool for named-location refs (sl-q81m)."
  [intents interface]
  (filterv #(some? (get-in (loader/get-location intents %) [interface :path]))
           (:intents intents)))

(defn resolve-location
  "Resolve a named-location ref to a full URL: the interface's `base-url` plus
   the intent's :location :path. The SINGLE URL-assembly point (sl-4mv8 guard) —
   every caller that turns a named location into a URL goes through here.

   Parameters:
   - intents: loaded intents map from loader/load-all-intents
   - intent-name: string, e.g. \"Feed\" (a bare name per location-ref?)
   - interface: keyword like :web
   - base-url: the interface's :config :base-url (may be nil — that's an error)

   Returns:
   - {:ok \"http://host/path\"} on success (exactly one slash at the join)
   - {:error {:type ... :message ... :intent ... :interface ...}} otherwise"
  [intents intent-name interface base-url]
  (let [location (loader/get-location intents intent-name)
        path (get-in location [interface :path])
        err (fn [type message]
              {:error {:type type :message message
                       :intent intent-name :interface interface}})]
    (cond
      (not (loader/known-intent? intents intent-name))
      (err :intent/unknown-intent
           (str "Unknown intent \"" intent-name "\""))

      (nil? location)
      (err :intent/no-location
           (str "Intent \"" intent-name "\" declares no :location"))

      (nil? path)
      (err :intent/no-location-for-interface
           (str "Intent \"" intent-name "\" :location has no :path for "
                "interface " interface
                (when (contains? location interface)
                  " (binding present but :path absent)")))

      (or (nil? base-url) (str/blank? base-url))
      (err :intent/missing-base-url
           (str "No :base-url configured for interface " interface
                " — set [:interfaces " interface " :config :base-url]"))

      :else
      {:ok (str (str/replace base-url #"/+$" "") path)})))
