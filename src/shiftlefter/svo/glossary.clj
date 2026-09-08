(ns shiftlefter.svo.glossary
  "Glossary loading and querying for SVO validation.

   ## Glossary Formats

   Subject glossary (with types and instances):
   ```clojure
   {:subjects
    {:user  {:desc \"Standard application user\"
             :instances [:alice :bob :carol]}
     :admin {:desc \"Administrative user\"
             :instances [:pat :admin-banned]}
     :guest {:desc \"Unauthenticated visitor\"}
     :test-harness/fixture-insertion {:desc \"Creates test data fixtures\"}}}
   ```

   Types are top-level keys. Instances are session handles listed under
   `:instances`. Types without `:instances` are singletons (e.g. `:guest`).

   An `:instances` entry may also be a persona map (sl-4e91) — a user with
   an optional costume and a docstring:
   ```clojure
   {:user {:desc \"Standard user\"
           :wears :default-jacket   ; type-level default, inheritable
           :instances [{:id :alicia :desc \"Spanish-first admin reviewer\"
                        :wears :alicia}
                       {:id :bob :wears :bob-jacket}
                       :carol]}}    ; bare keyword still fine
   ```
   Loaders NORMALIZE this authored shape (`normalize-subjects`): canonical
   entries keep `:instances` as a bare keyword vector and carry the
   per-instance keys in `:instance-meta {inst-kw {:desc ... :wears ...}}`.
   Hand-built glossaries (REPL/tests) should use the canonical shape or
   call `normalize-subjects` themselves. Costume resolution is
   instance > type: a persona's own `:wears` wins; `:wears nil` on a
   persona opts out of an inherited type default.

   Legacy flat format (no `:instances`) still works — each entry is a singleton.

   Verb glossary (per interface type):
   ```clojure
   {:type :web
    :verbs
    {:click {:desc \"Click on an element\"}
     :fill {:desc \"Enter text into input\"}}}
   ```

   ## Merging Behavior

   Project glossaries EXTEND framework defaults at the set level: project
   verbs are added alongside the builtins. On a name collision the project
   entry replaces the builtin entry WHOLESALE (desc, all frames) — there is
   no field- or frame-level merge, so restating a builtin verb to add one
   frame silently drops its other frames. Verified sl-0gfc (2026-08-12).
   The `:override-defaults` flag was REMOVED (sl-h7c3; it had been
   parsed-then-dropped by every real path for its whole life, sl-j2aq) —
   glossaries ALWAYS extend. A glossary file still carrying the key draws
   a warning naming that truth; the wholesale-override want is sl-t2ht.

   ## Usage

   ```clojure
   (def g (load-all-glossaries config-paths))
   (known-subject? g :user/alice)  ;; => true (qualified)
   (known-subject? g :guest)       ;; => true (singleton)
   (resolve-subject g :user/alice)
   ;; => {:type :user :instance :alice :qualified :user/alice
   ;;     :desc \"Standard application user\"}
   (instances-of-type g :user)     ;; => [:alice :bob :carol]
   ```"
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [shiftlefter.gherkin.io :as sio]))

;; -----------------------------------------------------------------------------
;; Specs — Glossary & Error Shapes
;; -----------------------------------------------------------------------------

;; Glossary shape: {:subjects {kw -> map} :verbs {kw -> {kw -> map}}}
;; Subject entries may contain :instances for type/instance grouping and an
;; optional :wears (costume keyword) so the subject attaches to a persistent
;; authenticated session instead of fresh-spawning (sl-rnm). An :instances
;; entry is a bare keyword or a persona map {:id kw :desc str :wears kw|nil}
;; (sl-4e91); loaders normalize persona maps into the canonical
;; :instance-meta map, so ::instances covers both authored and canonical
;; forms (canonical is the all-bare-keywords subset).
(s/def ::instance-meta-entry
  (s/and map?
         #(if (contains? % :desc) (string? (:desc %)) true)
         ;; Per-instance :wears is nilable: an explicit nil opts the persona
         ;; out of an inherited type-level default (sl-4e91).
         #(if (contains? % :wears) (or (nil? (:wears %)) (keyword? (:wears %))) true)))
(s/def ::instance-entry
  (s/or :bare keyword?
        ;; Custom generator: the bare map? gen can't satisfy the
        ;; unconditional keyword-:id predicate (spec-health).
        :persona (s/with-gen
                   (s/and ::instance-meta-entry #(keyword? (:id %)))
                   (fn []
                     (gen/fmap (fn [[id desc wears wears?]]
                                 (cond-> {:id id}
                                   desc   (assoc :desc desc)
                                   wears? (assoc :wears wears)))
                               (gen/tuple (gen/keyword)
                                          (gen/one-of [(gen/return nil)
                                                       (gen/string-alphanumeric)])
                                          (gen/one-of [(gen/return nil)
                                                       (gen/keyword)])
                                          (gen/boolean)))))))
(s/def ::instances (s/coll-of ::instance-entry :kind vector?))
(s/def ::instance-meta (s/map-of keyword? ::instance-meta-entry))
(s/def ::wears keyword?)
(s/def ::subject-entry (s/and map?
                              #(if (contains? % :instances)
                                 (s/valid? ::instances (:instances %))
                                 true)
                              #(if (contains? % :wears)
                                 (s/valid? ::wears (:wears %))
                                 true)
                              #(if (contains? % :instance-meta)
                                 (s/valid? ::instance-meta (:instance-meta %))
                                 true)))
(s/def ::subjects (s/map-of keyword? ::subject-entry))

;; Verb entries declare a closed set of frames. Each frame declares the
;; arguments it accepts (beyond S-V-O) and a human-readable surface
;; pattern. See sl-hse for the rationale; tl;dr: a verb's valence is the
;; closed set of arg-shapes it can take, bound to the verb itself, not
;; sneaked in via step text. ":default" is the conventional frame name
;; for verbs with a single canonical shape.
(s/def ::desc string?)
(s/def ::arg-name keyword?)
(s/def ::args (s/coll-of ::arg-name :kind vector?))
(s/def ::pattern string?)
(s/def ::implicit-object keyword?)
;; :object-kind (sl-rlxa): the accepted value kind for the O slot. Absent
;; means :intent (validated as an intent ref); :location accepts literal URLs.
;; :location-refs? (sl-3jr4): a :location frame that also accepts bare
;; PascalCase named-location intent refs (the navigate slot today).
;; :text (sl-2sj6): a content-only free-text slot — quoted literal or
;; {binding} token, never an intent ref (the see-title slot). :key
;; (sl-2sj6): a structure slot validated against the closed W3C
;; KeyboardEvent.key set (svo.keys), plus quoted single characters.
(s/def ::object-kind #{:intent :location :text :key})
(s/def ::location-refs? boolean?)
;; :arg-kinds (sl-yh7): per-arg slot value kinds. :value = a literal-admitting
;; slot (quoted literal or {binding} token; the engine strips quotes/resolves
;; tokens by this declaration). :matcher = a regex-source slot whose named
;; groups PRODUCE bindings and whose embedded {binding} tokens interpolate as
;; regex-quoted literals. Args without a kind are plain captures — no token
;; admission, no normalization.
(s/def ::arg-kind #{:value :matcher})
(s/def ::arg-kinds (s/map-of ::arg-name ::arg-kind))

(s/def ::frame
  (s/keys :req-un [::args ::pattern]
          :opt-un [::implicit-object ::object-kind ::location-refs?
                   ::arg-kinds]))

;; A verb must declare at least one frame; a frame-less verb is
;; effectively unimplemented and shouldn't be in a loaded glossary.
(s/def ::frames (s/and (s/map-of keyword? ::frame) seq))

(s/def ::verb-entry
  (s/keys :req-un [::desc ::frames]))

(s/def ::verb-map (s/map-of keyword? ::verb-entry))
(s/def ::verbs (s/map-of keyword? ::verb-map))

(s/def ::glossary
  (s/keys :req-un [::subjects ::verbs]))

;; Glossary error shape
(s/def ::type keyword?)
(s/def ::message string?)
(s/def ::path string?)

(s/def ::glossary-error
  (s/keys :req-un [::type ::message]
          :opt-un [::path]))

;; -----------------------------------------------------------------------------
;; Constants
;; -----------------------------------------------------------------------------

(def ^:private default-verbs-resources
  "Classpath paths to framework default verb glossaries. Each file declares
   its own :type — the mount key (sl-67dj: the file's :type is authoritative;
   there is no type-keyed resource map to restate it). Add a new default by
   adding its resource path here."
  ["shiftlefter/glossaries/verbs-web.edn"
   "shiftlefter/glossaries/verbs-sms.edn"])

;; -----------------------------------------------------------------------------
;; Loading Helpers
;; -----------------------------------------------------------------------------

(defn- read-edn-file
  "Read EDN from a filesystem path.
   Returns {:ok data} or {:error {...}}."
  [path]
  (try
    (let [content (sio/slurp-utf8 path)]
      (if (:status content)
        ;; slurp-utf8 returned an error map
        {:error {:type :glossary/read-failed
                 :message (str "Failed to read glossary: " (:message content))
                 :path path}}
        {:ok (edn/read-string content)}))
    (catch Exception e
      {:error {:type :glossary/parse-failed
               :message (str "Failed to parse glossary EDN: " (ex-message e))
               :path path}})))

(defn- read-edn-resource
  "Read EDN from a classpath resource.
   Returns {:ok data} or {:error {...}}."
  [resource-path]
  (if-let [url (io/resource resource-path)]
    (try
      {:ok (-> url slurp edn/read-string)}
      (catch Exception e
        {:error {:type :glossary/parse-failed
                 :message (str "Failed to parse glossary resource: " (ex-message e))
                 :resource resource-path}}))
    {:error {:type :glossary/resource-not-found
             :message (str "Glossary resource not found: " resource-path)
             :resource resource-path}}))

;; -----------------------------------------------------------------------------
;; Verb Entry Validation (Tier 0 Enforcement) — sl-hse
;; -----------------------------------------------------------------------------

(defn- contains-pred->key
  "If a spec :pred form is `(contains? % :foo)`-shaped, return :foo;
   else nil. Spec preds reach us as LazySeqs that need pr-str to expose
   their form."
  [pred]
  (when-let [m (re-find #"contains\?\s+%\s+:([^)\s]+)\)" (pr-str pred))]
    (keyword (second m))))

(defn- in-path->subpath
  "Convert a spec-problem :in vector into a slash-joined descriptor
   suitable for an error message, or nil if the failure is at the verb
   entry itself. Drops trailing map-of indices (0/1) and the leading
   :frames key (which is implicit from context)."
  [in]
  (let [trimmed (vec (take-while #(not (#{0 1} %)) in))
        relevant (if (= :frames (first trimmed))
                   (drop 1 trimmed)
                   trimmed)]
    (when (seq relevant)
      (str/join "/" (map (fn [k] (str (when (keyword? k) ":") (name k)))
                         relevant)))))

(defn- format-problem
  "Render a single spec problem into a phrase fragment."
  [problem]
  (let [missing (contains-pred->key (:pred problem))
        sub     (in-path->subpath (:in problem))]
    (cond
      (and missing sub)
      (str "frame " sub " is missing required key :" (name missing))

      missing
      (str "is missing required key :" (name missing))

      :else
      (str "has invalid shape (in " (pr-str (:in problem)) ")"))))

(defn- format-verb-entry-error
  "Build a human-readable error message for a non-conforming verb entry.
   Surfaces the missing required key (and, for nested failures, the
   frame name) when the spec failure is a contains? predicate."
  [verb-kw interface-type path entry]
  (let [explain     (s/explain-data ::verb-entry entry)
        problems    (:clojure.spec.alpha/problems explain)
        verb-label  (str ":" (when interface-type (str (name interface-type) "/"))
                         (name verb-kw))
        path-suffix (when path (str " at " path))
        ;; Group problems by whether they're at the verb level or frame level.
        verb-level  (filter #(empty? (in-path->subpath (:in %))) problems)
        frame-level (remove #(empty? (in-path->subpath (:in %))) problems)
        verb-keys   (->> verb-level (keep #(contains-pred->key (:pred %))) distinct)
        frame-msgs  (mapv format-problem frame-level)]
    (str "Glossary error: verb " verb-label
         (cond
           (seq verb-keys)
           (str " is missing required "
                (if (= 1 (count verb-keys)) "key " "keys ")
                (str/join ", " (map #(str ":" (name %)) verb-keys)))

           (seq frame-msgs)
           (str ": " (str/join "; " frame-msgs))

           :else
           " has invalid shape")
         path-suffix
         (when (and (empty? verb-keys) (empty? frame-msgs))
           (str "\n  " (str/trim (with-out-str (s/explain ::verb-entry entry))))))))

(defn- validate-verb-entries
  "Validate each verb entry in a verbs map against ::verb-entry.
   Returns a vector of error maps; empty when all conform."
  [verbs interface-type path]
  (->> verbs
       (keep (fn [[verb-kw entry]]
               (when-not (s/valid? ::verb-entry entry)
                 {:type          :glossary/invalid-verb-entry
                  :interface-type interface-type
                  :verb          verb-kw
                  :path          path
                  :explain-data  (s/explain-data ::verb-entry entry)
                  :message       (format-verb-entry-error verb-kw
                                                          interface-type
                                                          path
                                                          entry)})))
       vec))

;; -----------------------------------------------------------------------------
;; Public API: Loading
;; -----------------------------------------------------------------------------

(defn load-glossary
  "Load a glossary from a filesystem path.

   Returns the glossary map on success, or an error map with :type on failure.
   Error types:
   - :glossary/read-failed — file couldn't be read
   - :glossary/parse-failed — file isn't valid EDN
   - :glossary/file-not-found — file doesn't exist"
  [path]
  (if (fs/exists? path)
    (let [result (read-edn-file path)]
      (if (:ok result)
        (:ok result)
        (:error result)))
    {:type :glossary/file-not-found
     :message (str "Glossary file not found: " path)
     :path path}))

(defn- read-default-verb-file
  "Read and tier-0-validate one framework default verb glossary resource.
   Returns the parsed data ({:type ... :verbs {...}}) or nil when the
   resource is unreadable. Throws :glossary/invalid-default-glossary on a
   malformed default — a shipped file must declare a keyword :type (its
   mount key, sl-67dj) and conform to ::verb-entry (build-time invariant,
   not user input). Project glossaries are validated by the
   load-verb-glossary* functions instead."
  [resource-path]
  (let [result (read-edn-resource resource-path)]
    (when (:ok result)
      (let [data  (:ok result)
            ftype (:type data)]
        (when-not (keyword? ftype)
          (throw (ex-info (str "Framework default glossary " resource-path
                               " declares no keyword :type — the file's :type"
                               " is its mount key")
                          {:type :glossary/invalid-default-glossary
                           :resource resource-path})))
        (let [errors (validate-verb-entries (:verbs data) ftype resource-path)]
          (when (seq errors)
            (throw (ex-info (str "Framework default " (pr-str ftype)
                                 " glossary is invalid: "
                                 (count errors) " bad entries. First: "
                                 (-> errors first :message))
                            {:type :glossary/invalid-default-glossary
                             :interface-type ftype
                             :errors errors})))
          data)))))

(defn load-default-verbs
  "Load the framework's default verb glossary for a given interface type.

   Looks the type up against each default file's own DECLARED :type
   (sl-67dj — the file's :type is authoritative). Returns the parsed
   glossary map ({:type ... :verbs {...}}) or nil for types no framework
   default declares. Throws on a malformed framework default (see
   read-default-verb-file).

   Add a new framework default by adding to `default-verbs-resources`."
  [interface-type]
  (->> default-verbs-resources
       (keep read-default-verb-file)
       (some #(when (= interface-type (:type %)) %))))

(defn load-default-glossaries
  "Load all framework default glossaries.

   Each file mounts under the :type IT declares (sl-67dj).

   Returns:
   {:subjects {}  ;; no default subjects — project must define
    :verbs {:web {...}
            :sms {...}}}  ;; one entry per shipped default file"
  []
  {:subjects {}
   :verbs (reduce (fn [acc resource-path]
                    (if-let [data (read-default-verb-file resource-path)]
                      (assoc acc (:type data) (:verbs data))
                      acc))
                  {}
                  default-verbs-resources)})

;; -----------------------------------------------------------------------------
;; Merging
;; -----------------------------------------------------------------------------

(defn merge-glossaries
  "Merge project glossaries into default glossaries — project ALWAYS
   extends defaults (sl-h7c3: the `:override-defaults` flag was removed;
   it had been parsed-then-dropped by both public loaders for its whole
   life, sl-j2aq — the wholesale-override want is sl-t2ht). On a name
   collision the project entry replaces the default entry WHOLESALE;
   restate an entry to replace it.

   Parameters:
   - defaults: {:subjects {...} :verbs {:web {...}}}
   - project: {:subjects {...} :verbs {:web {...}}}

   Returns merged glossary."
  [defaults project]
  {:subjects (merge (:subjects defaults) (:subjects project))
   :verbs (merge-with merge (:verbs defaults) (:verbs project))})

;; -----------------------------------------------------------------------------
;; Provenance (sl-0gfc)
;; -----------------------------------------------------------------------------

(defn- section-provenance
  "Per-key provenance markers for one merged section, computed while the
   builtin and user maps are both still in hand (the merge itself is lossy —
   a wholesale-overridden builtin is indistinguishable from a user addition
   after the fact).

   :builtin          — key only in the framework defaults
   :user-file        — key only in the project glossary
   :config-override  — key in both (the project entry replaced the builtin)"
  [builtin-map user-map]
  (merge
   (into {} (map (fn [k] [k :builtin])) (keys builtin-map))
   (into {} (map (fn [k] [k (if (contains? builtin-map k)
                              :config-override
                              :user-file)]))
         (keys user-map))))

(defn- verbs-provenance
  "Provenance markers for the verbs section, per interface type."
  [builtin-verbs user-verbs]
  (into {}
        (map (fn [iface]
               [iface (section-provenance (get builtin-verbs iface)
                                          (get user-verbs iface))]))
        (distinct (concat (keys builtin-verbs) (keys user-verbs)))))

(defn- attach-provenance
  "Attach :provenance (per-entry markers) and :sources (the files actually
   mounted: the subjects path plus declared-type → path for each project
   verb file, assembled by the load-all* loaders as they mount — actual-
   mount provenance, sl-67dj) to a merged glossary. Additive keys —
   ::glossary is req-un, so consumers that don't know about them conform
   unchanged."
  [glossary defaults project sources]
  (assoc glossary
         :provenance {:subjects (section-provenance (:subjects defaults)
                                                    (:subjects project))
                      :verbs (verbs-provenance (:verbs defaults)
                                               (:verbs project))}
         :sources {:subjects (:subjects sources)
                   :verbs (or (:verbs sources) {})}))

;; -----------------------------------------------------------------------------
;; Public API: Loading All
;; -----------------------------------------------------------------------------

(defn- glossary-error?
  "Returns true if result is a glossary error map. Requires :message as
   well as the glossary-namespaced :type: a successfully parsed glossary
   FILE also carries a :type, and type identifiers are an open namespaced
   space (sl-67dj) — the namespace check alone would false-positive on a
   file declaring :type :glossary/<anything>."
  [result]
  (and (map? result)
       (keyword? (:type result))
       (= "glossary" (namespace (:type result)))
       (string? (:message result))))

;; -----------------------------------------------------------------------------
;; Persona Normalization (sl-4e91)
;; -----------------------------------------------------------------------------

(defn- normalize-subject-entry
  "Normalize one authored subject entry: persona maps in `:instances`
   ({:id :alicia :desc ... :wears ...}) are split into a bare keyword in
   `:instances` plus their remaining keys under
   `:instance-meta {inst-kw {...}}` — the canonical shape every consumer
   reads. Bare keywords pass through; already-canonical entries are
   unchanged (normalization is idempotent). An entry that is neither a
   keyword nor a persona map with a keyword :id is left in place for
   `validate-instances-entry` to report. Inline persona keys win over a
   pre-existing `:instance-meta` entry for the same instance."
  [entry]
  (if-let [instances (:instances entry)]
    (let [split (reduce (fn [acc inst]
                          (if (and (map? inst) (keyword? (:id inst)))
                            (let [m (dissoc inst :id)]
                              (cond-> (update acc :ids conj (:id inst))
                                (seq m) (update :meta assoc (:id inst) m)))
                            (update acc :ids conj inst)))
                        {:ids [] :meta {}}
                        instances)]
      (cond-> (assoc entry :instances (:ids split))
        (seq (:meta split))
        (assoc :instance-meta (merge (:instance-meta entry) (:meta split)))))
    entry))

(defn normalize-subjects
  "Normalize every subject entry in a subjects map (see
   `normalize-subject-entry`). Applied by the glossary loaders; public so
   hand-built authored-shape glossaries (REPL) can normalize too."
  [subjects]
  (reduce-kv (fn [acc type-kw entry]
               (assoc acc type-kw (normalize-subject-entry entry)))
             {}
             subjects))

;; -----------------------------------------------------------------------------
;; Instance Index
;; -----------------------------------------------------------------------------

(defn- validate-instances-entry
  "Validate that :instances values are keywords (post-normalization; a
   persona map without a keyword :id survives normalization and lands
   here). Returns error or nil."
  [type-kw instances]
  (let [bad (remove keyword? instances)]
    (when (seq bad)
      {:type :glossary/invalid-instances
       :message (str "Type :" (name type-kw)
                     " has invalid instances: " (pr-str (vec bad))
                     " — each entry must be a keyword or a persona map"
                     " with a keyword :id (sl-4e91)")
       :subject-type type-kw
       :invalid (vec bad)})))

(defn build-instance-index
  "Build reverse index from instance keyword to type keyword.
   Scans all subject types with :instances vectors.

   Returns {:ok {instance-kw type-kw ...}} on success.
   Returns {:error {...}} if:
   - An instance appears in more than one type
   - An instance keyword collides with a type keyword
   - :instances contains non-keyword values"
  [subjects]
  (let [type-keys (set (keys subjects))]
    (reduce-kv
     (fn [acc type-kw entry]
       (if (:error acc)
         acc
         (if-let [instances (:instances entry)]
           (if-let [err (validate-instances-entry type-kw instances)]
             {:error err}
             (reduce
              (fn [acc2 inst]
                (if (:error acc2)
                  acc2
                  (cond
                    (contains? type-keys inst)
                    {:error {:type :glossary/instance-type-collision
                             :message (str "Instance :" (name inst)
                                          " under :" (name type-kw)
                                          " collides with a type keyword")
                             :instance inst
                             :subject-type type-kw}}

                    (contains? (:ok acc2) inst)
                    {:error {:type :glossary/duplicate-instance
                             :message (str "Instance :" (name inst)
                                          " appears in both :"
                                          (name (get-in acc2 [:ok inst]))
                                          " and :" (name type-kw))
                             :instance inst
                             :types [(get-in acc2 [:ok inst]) type-kw]}}

                    :else
                    (assoc-in acc2 [:ok inst] type-kw))))
              acc
              instances))
           acc)))
     {:ok {}}
     subjects)))

(defn- attach-instance-index
  "Attach computed instance index to a glossary map.
   Lenient mode: warns on error, attaches empty index.
   Strict mode: returns {:error ...}."
  [glossary strict?]
  (let [result (build-instance-index (:subjects glossary))]
    (if (:error result)
      (if strict?
        {:error (:error result)}
        (do (log/warnf "Instance index error: %s" (-> result :error :message))
            (assoc glossary :instance-index {})))
      (assoc glossary :instance-index (:ok result)))))

(defn- override-flag-warning
  "sl-h7c3: `:override-defaults` was removed — a glossary file still
   carrying the key gets the truth (both lenient AND strict: the key's
   presence doesn't make the glossary unusable). Returns the structured
   warning map or nil; computed where the parsed file map is in hand —
   the exact point the flag historically vanished. Strict loads thread
   the map onto the structured lints channel (sl-67dj, Warden P3-13);
   lenient loads log its :message on the interactive channel."
  [file-map path]
  (when (contains? file-map :override-defaults)
    {:type :glossary/override-flag-ignored
     :path path
     :message (format (str "Glossary file %s carries :override-defaults, which is"
                           " not supported — glossaries always extend the defaults;"
                           " restate an entry to replace it wholesale")
                      path)}))

(defn- log-warning!
  "Log a structured glossary warning's message (lenient/interactive channel)."
  [warning]
  (when warning
    (log/warn (:message warning))))

(defn- missing-type-message [path result]
  (str "Verb glossary " path " must declare a keyword :type — the file's"
       " :type is its mount key (e.g. :type :web)"
       (when (contains? result :type)
         (str "; found " (pr-str (:type result))))))

(defn- load-subject-glossary
  "Load subject glossary from path. Returns {:subjects {...}} or empty on error."
  [path]
  (if (nil? path)
    {:subjects {}}
    (let [result (load-glossary path)]
      (if (glossary-error? result)
        (do (log/warnf "Could not load subject glossary: %s" (:message result))
            {:subjects {}})
        (do (log-warning! (override-flag-warning result path))
            {:subjects (:subjects result)})))))

(defn- load-subject-glossary-strict
  "Load subject glossary strictly.
   Returns {:ok {:subjects ...} :warnings [...]} or {:error ...}."
  [path]
  (if (nil? path)
    {:ok {:subjects {}} :warnings []}
    (let [result (load-glossary path)]
      (if (glossary-error? result)
        {:error {:type :svo/glossary-file-not-found
                 :path path
                 :message (:message result)}}
        {:ok {:subjects (:subjects result)}
         :warnings (filterv some? [(override-flag-warning result path)])}))))

(defn- load-verb-glossary
  "Load verb glossary from path, mounting it under the type the FILE
   declares (sl-67dj — file-authoritative). Returns {:verbs {type {...}}}
   or empty on error. A file without a keyword :type is skipped with a
   warning; bad verb entries (per ::verb-entry spec) warn but the glossary
   still loads — lenient mode favors continuity."
  [path]
  (if (nil? path)
    {:verbs {}}
    (let [result (load-glossary path)]
      (cond
        (glossary-error? result)
        (do (log/warnf "Could not load verb glossary %s: %s" path (:message result))
            {:verbs {}})

        (not (keyword? (:type result)))
        (do (log/warn (str (missing-type-message path result) " — skipped"))
            {:verbs {}})

        :else
        (let [ftype  (:type result)
              errors (validate-verb-entries (:verbs result) ftype path)]
          (log-warning! (override-flag-warning result path))
          (doseq [e errors]
            (log/warn (:message e)))
          {:verbs {ftype (:verbs result)}})))))

(defn- load-verb-glossary-strict
  "Load verb glossary strictly, mounting under the type the FILE declares
   (sl-67dj — file-authoritative; ANY keyword is legal, namespaced types
   like :github/web included — type identifiers are an open space, no
   enum here). Returns {:ok {:verbs {type ...}} :warnings [...]} or
   {:error ...}. Fails on file errors, on a missing/non-keyword :type
   (a broken file — planning-class), AND on bad verb entries — Shifted
   mode demands a clean glossary."
  [path]
  (if (nil? path)
    {:ok {:verbs {}} :warnings []}
    (let [result (load-glossary path)]
      (cond
        (glossary-error? result)
        {:error {:type :svo/glossary-file-not-found
                 :path path
                 :message (:message result)}}

        (not (keyword? (:type result)))
        {:error {:type :glossary/missing-type
                 :path path
                 :message (missing-type-message path result)}}

        :else
        (let [ftype  (:type result)
              errors (validate-verb-entries (:verbs result) ftype path)]
          (if (seq errors)
            {:error (assoc (first errors) :all-errors errors)}
            {:ok {:verbs {ftype (:verbs result)}}
             :warnings (filterv some? [(override-flag-warning result path)])}))))))

(defn- mount-sources
  "Declared-type → path entries for one loaded file's mounts (actual-mount
   provenance, sl-67dj). With same-type files the later path wins in
   :sources — the mounted VERBS still extend (merge-with merge)."
  [mounted-verbs path]
  (into {} (map (fn [t] [t path])) (keys mounted-verbs)))

(defn- verb-paths
  "The :verbs path list to load, or [] when absent OR malformed. The shape
   is config/normalize's to name (:config/invalid-glossaries, sl-a7sx); the
   projection keeps loading past that diagnostic so orient can show it,
   and iterating the retired type-keyed map here was the exit-3 crash."
  [config-paths]
  (let [paths (:verbs config-paths)]
    (if (sequential? paths) paths [])))

(defn load-all-glossaries
  "Load all glossaries per config paths, merged with defaults.

   Config shape (sl-67dj — a LIST of paths; each file's own :type is its
   mount key):
   {:subjects \"path/to/subjects.edn\"
    :verbs [\"path/to/verbs-web.edn\"
            \"path/to/verbs-api.edn\"]}

   Missing files produce warnings but don't fail — defaults are used.
   Files declaring the same :type EXTEND each other (always-extend
   doctrine, sl-h7c3; restate an entry to replace it).

   Returns:
   {:subjects {:alice {...} :admin {...} ...}
    :verbs {:web {:click {...} :fill {...} ...}
            :api {:get {...} :post {...} ...}}}"
  [config-paths]
  (let [defaults (load-default-glossaries)
        ;; Load subject glossary
        subject-path (:subjects config-paths)
        subject-glossary (load-subject-glossary subject-path)
        ;; Load each verb glossary file; the file's declared :type mounts it
        verb-mounts (reduce
                     (fn [acc path]
                       (let [loaded (load-verb-glossary path)]
                         (-> acc
                             (update :verbs #(merge-with merge % (:verbs loaded)))
                             (update :sources merge
                                     (mount-sources (:verbs loaded) path)))))
                     {:verbs {} :sources {}}
                     (verb-paths config-paths))
        ;; Combine into project glossary
        project {:subjects (:subjects subject-glossary)
                 :verbs (:verbs verb-mounts)}
        sources {:subjects subject-path
                 :verbs (:sources verb-mounts)}]
    (-> (merge-glossaries defaults project)
        (update :subjects normalize-subjects)
        (attach-provenance defaults project sources)
        (attach-instance-index false))))

(defn load-all-glossaries-strict
  "Load all glossaries strictly for Shifted mode.

   Unlike `load-all-glossaries`, this fails on missing/invalid files instead
   of warning. Use this when :svo config is present.

   Config shape (sl-67dj — a LIST of paths; each file's own :type is its
   mount key, and a file without a keyword :type is a planning-class
   :glossary/missing-type error):
   {:subjects \"path/to/subjects.edn\"
    :verbs [\"path/to/verbs-web.edn\"
            \"path/to/verbs-api.edn\"]}

   Returns:
   {:ok {:subjects {...} :verbs {...}} :warnings [...]}
   or
   {:error {:type :svo/glossary-file-not-found :path \"...\" ...}}

   :warnings carries the structured glossary-load warnings (e.g.
   :glossary/override-flag-ignored) from every loaded file, subjects
   included — consumers thread them onto the lints channel (sl-67dj,
   Warden P3-13). An error short-circuits and drops pending warnings:
   the error is the actionable item."
  [config-paths]
  (let [defaults (load-default-glossaries)
        ;; Load subject glossary strictly
        subject-path (:subjects config-paths)
        subject-result (load-subject-glossary-strict subject-path)]
    (if (:error subject-result)
      subject-result
      ;; Subject loaded, try verb glossaries
      (let [verb-results (reduce
                          (fn [acc path]
                            (if (:error acc)
                              acc  ; short-circuit on first error
                              (let [result (load-verb-glossary-strict path)]
                                (if (:error result)
                                  result
                                  (-> acc
                                      (update-in [:ok :verbs]
                                                 #(merge-with merge % (-> result :ok :verbs)))
                                      (update-in [:ok :sources] merge
                                                 (mount-sources (-> result :ok :verbs) path))
                                      (update :warnings into (:warnings result)))))))
                          {:ok {:verbs {} :sources {}} :warnings []}
                          (verb-paths config-paths))]
        (if (:error verb-results)
          verb-results
          ;; All loaded successfully, merge with defaults and build index
          (let [project {:subjects (-> subject-result :ok :subjects)
                         :verbs (-> verb-results :ok :verbs)}
                sources {:subjects subject-path
                         :verbs (-> verb-results :ok :sources)}
                warnings (into (vec (:warnings subject-result))
                               (:warnings verb-results))
                merged (-> (merge-glossaries defaults project)
                           (update :subjects normalize-subjects)
                           (attach-provenance defaults project sources))
                indexed (attach-instance-index merged true)]
            (if (:error indexed)
              indexed
              {:ok indexed :warnings warnings})))))))

;; -----------------------------------------------------------------------------
;; Public API: Querying
;; -----------------------------------------------------------------------------

(defn resolve-subject
  "Resolve a subject keyword to its type/instance structure.

   For qualified subjects (`:user/alice`):
   - Extracts type from namespace, instance from name
   - Verifies type exists and instance is listed

   For bare subjects (`:guest`, `:alice`):
   - If it's a top-level type key → singleton
   - If it's in the instance index → resolved via index

   Returns:
   - `{:type :user :instance :alice :qualified :user/alice :desc \"...\"}`
   - `{:type :guest :instance :guest :qualified :guest :desc \"...\" :singleton? true}`
   - nil if unresolvable"
  [glossary subject]
  (let [subjects (:subjects glossary)
        instance-idx (or (:instance-index glossary) {})]
    (if-let [ns-part (namespace subject)]
      ;; Namespaced keyword — could be:
      ;; 1. A namespaced type (singleton): :test-harness/fixture-insertion
      ;; 2. A type/instance pair: :user/alice
      (if-let [entry (get subjects subject)]
        ;; Case 1: It's a top-level namespaced type key
        {:type subject
         :instance (keyword (name subject))
         :qualified subject
         :desc (:desc entry)
         :singleton? true}
        ;; Case 2: Try as type/instance
        (let [type-kw (keyword ns-part)
              inst-kw (keyword (name subject))
              entry (get subjects type-kw)]
          (when (and entry
                     (some #{inst-kw} (:instances entry)))
            {:type type-kw
             :instance inst-kw
             :qualified subject
             :desc (:desc entry)})))
      ;; Bare: :guest or :alice
      (if-let [entry (get subjects subject)]
        ;; It's a type key — singleton (no instances) or the type itself
        (if (:instances entry)
          ;; Type with instances — not a valid standalone subject
          nil
          ;; Singleton
          {:type subject
           :instance subject
           :qualified subject
           :desc (:desc entry)
           :singleton? true})
        ;; Not a type key — check instance index
        (when-let [type-kw (get instance-idx subject)]
          (let [entry (get subjects type-kw)]
            {:type type-kw
             :instance subject
             :qualified (keyword (name type-kw) (name subject))
             :desc (:desc entry)}))))))

(defn known-subject?
  "Returns true if subject is known in the glossary.
   Handles both qualified (`:user/alice`) and bare (`:guest`) forms."
  [glossary subject]
  (some? (resolve-subject glossary subject)))

(defn known-verb?
  "Returns true if verb is known for the given interface type."
  [glossary interface-type verb]
  (contains? (get-in glossary [:verbs interface-type]) verb))

(defn known-subjects
  "Returns all known subject keywords.
   Includes type keys (for singletons and types) but not instance keywords."
  [glossary]
  (vec (keys (:subjects glossary))))

(defn all-subject-forms
  "Returns all RESOLVABLE subject forms: singleton type keys + qualified
   instance forms. Bare type keys that have :instances are excluded —
   resolve-subject returns nil for them, so listing them as known subjects
   (or suggesting them for typos) would mislead (sl-6e7p).
   Useful for Levenshtein suggestion candidates and 'Known subjects' hints."
  [glossary]
  (let [subjects (:subjects glossary)]
    (into (vec (keep (fn [[type-kw entry]]
                       (when-not (:instances entry) type-kw))
                     subjects))
          (mapcat (fn [[type-kw entry]]
                    (map #(keyword (name type-kw) (name %))
                         (:instances entry)))
                  subjects))))

(defn known-verbs
  "Returns all known verb keywords for the given interface type."
  [glossary interface-type]
  (vec (keys (get-in glossary [:verbs interface-type]))))

(defn instances-of-type
  "Returns the instances vector for a subject type, or nil."
  [glossary type-kw]
  (:instances (get-in glossary [:subjects type-kw])))

(defn subject-type
  "Reverse-lookup: given an instance keyword, returns its type.
   Returns nil if not found in the instance index."
  [glossary instance-kw]
  (get (:instance-index glossary) instance-kw))

(defn all-types
  "Returns all subject type keywords."
  [glossary]
  (vec (keys (:subjects glossary))))

(defn instance-meta
  "The persona meta map (`{:desc ... :wears ...}`) authored on a subject
   instance (sl-4e91), or nil when the subject is unknown, a singleton, or
   a bare-keyword instance with no persona map. Accepts qualified
   (`:user/alicia`) and bare (`:alicia`) instance forms."
  [glossary subject]
  (when-let [resolved (resolve-subject glossary subject)]
    (when-not (:singleton? resolved)
      (get-in glossary
              [:subjects (:type resolved) :instance-meta (:instance resolved)]))))

(defn effective-wears
  "The costume a subject effectively wears, with its provenance:
   `{:wears <kw> :source :instance|:type-default}`, or nil when the subject
   is unknown or wears nothing.

   Resolution order (sl-4e91): the persona instance's own `:wears` (from
   `:instance-meta`) wins over the type-level `:wears`, which acts as an
   inheritable default. The override is contains?-based, so a persona
   carrying an explicit `:wears nil` opts out of an inherited default —
   that collapses to nil here, indistinguishable from wearing nothing.
   Singletons read the type entry directly."
  [glossary subject]
  (when-let [resolved (resolve-subject glossary subject)]
    (let [entry (get-in glossary [:subjects (:type resolved)])
          imeta (when-not (:singleton? resolved)
                  (get (:instance-meta entry) (:instance resolved)))
          own?  (and imeta (contains? imeta :wears))
          worn  (if own? (:wears imeta) (:wears entry))]
      (when worn
        {:wears worn :source (if own? :instance :type-default)}))))

(defn costume-for-subject
  "Resolve the costume a subject `:wears`, or nil.

   Per-instance resolution (sl-4e91): a persona instance's own `:wears`
   wins over the type-level `:wears` default — see `effective-wears` for
   the order and the `:wears nil` opt-out. See sl-rnm for what wearing a
   costume means at provisioning time.

   Returns the costume keyword (e.g. `:finance`) or nil if the subject is
   unknown or wears nothing."
  [glossary subject]
  (:wears (effective-wears glossary subject)))

(defn singleton?
  "Returns true if the subject type is a singleton (no instances)."
  [glossary type-kw]
  (let [entry (get-in glossary [:subjects type-kw])]
    (and (some? entry)
         (nil? (:instances entry)))))

(defn subject-info
  "Returns the info map for a subject, or nil if unknown.
   For qualified subjects, returns the type's info."
  [glossary subject]
  (if-let [resolved (resolve-subject glossary subject)]
    (get-in glossary [:subjects (:type resolved)])
    (get-in glossary [:subjects subject])))

(defn verb-info
  "Returns the info map for a verb, or nil if unknown."
  [glossary interface-type verb]
  (get-in glossary [:verbs interface-type verb]))
