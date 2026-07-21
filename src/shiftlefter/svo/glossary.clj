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

   Legacy flat format (no `:instances`) still works — each entry is a singleton.

   Verb glossary (per interface type):
   ```clojure
   {:type :web
    :verbs
    {:click {:desc \"Click on an element\"}
     :fill {:desc \"Enter text into input\"}}}
   ```

   ## Merging Behavior

   Project glossaries extend framework defaults by default.
   Use `:override-defaults true` in project glossary to replace entirely.

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
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [shiftlefter.gherkin.io :as sio]))

;; -----------------------------------------------------------------------------
;; Specs — Glossary & Error Shapes
;; -----------------------------------------------------------------------------

;; Glossary shape: {:subjects {kw -> map} :verbs {kw -> {kw -> map}}}
;; Subject entries may contain :instances (vector of keywords) for type/instance grouping,
;; and an optional :wears (costume keyword) so the subject attaches to a persistent
;; authenticated session instead of fresh-spawning. See sl-rnm.
(s/def ::instances (s/coll-of keyword? :kind vector?))
(s/def ::wears keyword?)
(s/def ::subject-entry (s/and map?
                              #(if (contains? % :instances)
                                 (s/valid? ::instances (:instances %))
                                 true)
                              #(if (contains? % :wears)
                                 (s/valid? ::wears (:wears %))
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
(s/def ::object-kind #{:intent :location})
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
  "Classpath paths to framework default verb glossaries, by interface type.
   Add a new entry here when shipping a default glossary for a new interface."
  {:web "shiftlefter/glossaries/verbs-web.edn"
   :sms "shiftlefter/glossaries/verbs-sms.edn"})

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

(defn load-default-verbs
  "Load the framework's default verb glossary for a given interface type.

   Returns the parsed glossary map ({:type ... :verbs {...}}) or nil for
   types that ship no framework default. Throws on a malformed framework
   default — defaults must always conform to ::verb-entry (this is a
   build-time invariant, not user input). Project glossaries are validated
   by the load-verb-glossary* functions instead.

   Add a new framework default by adding to `default-verbs-resources`."
  [interface-type]
  (when-let [resource-path (get default-verbs-resources interface-type)]
    (let [result (read-edn-resource resource-path)]
      (when (:ok result)
        (let [data   (:ok result)
              errors (validate-verb-entries (:verbs data)
                                            interface-type
                                            resource-path)]
          (when (seq errors)
            (throw (ex-info (str "Framework default " (name interface-type)
                                 " glossary is invalid: "
                                 (count errors) " bad entries. First: "
                                 (-> errors first :message))
                            {:type :glossary/invalid-default-glossary
                             :interface-type interface-type
                             :errors errors})))
          data)))))

(defn load-default-glossaries
  "Load all framework default glossaries.

   Returns:
   {:subjects {}  ;; no default subjects — project must define
    :verbs {:web {...}
            :sms {...}}}  ;; one entry per shipped interface type"
  []
  {:subjects {}
   :verbs (reduce-kv (fn [acc interface-type _resource-path]
                       (if-let [verbs (load-default-verbs interface-type)]
                         (assoc acc interface-type (:verbs verbs))
                         acc))
                     {}
                     default-verbs-resources)})

;; -----------------------------------------------------------------------------
;; Merging
;; -----------------------------------------------------------------------------

(defn- merge-subjects
  "Merge project subjects into base. Project extends base."
  [base-subjects project-subjects override?]
  (if override?
    project-subjects
    (merge base-subjects project-subjects)))

(defn- merge-all-verbs
  "Merge verb glossaries for all types."
  [base-verbs project-verbs override?]
  (if override?
    project-verbs
    (merge-with (fn [base proj] (merge base proj))
                base-verbs
                project-verbs)))

(defn merge-glossaries
  "Merge project glossaries into default glossaries.

   By default, project extends defaults. If project glossary contains
   `:override-defaults true`, it replaces defaults entirely.

   Parameters:
   - defaults: {:subjects {...} :verbs {:web {...}}}
   - project: {:subjects {...} :verbs {:web {...}} :override-defaults bool?}

   Returns merged glossary."
  [defaults project]
  (let [override? (:override-defaults project)]
    {:subjects (merge-subjects (:subjects defaults)
                               (:subjects project)
                               override?)
     :verbs (merge-all-verbs (:verbs defaults)
                             (:verbs project)
                             override?)}))

;; -----------------------------------------------------------------------------
;; Public API: Loading All
;; -----------------------------------------------------------------------------

(defn- glossary-error?
  "Returns true if result is a glossary error map."
  [result]
  (and (map? result)
       (keyword? (:type result))
       (= "glossary" (namespace (:type result)))))

;; -----------------------------------------------------------------------------
;; Instance Index
;; -----------------------------------------------------------------------------

(defn- validate-instances-entry
  "Validate that :instances values are keywords. Returns error or nil."
  [type-kw instances]
  (let [bad (remove keyword? instances)]
    (when (seq bad)
      {:type :glossary/invalid-instances
       :message (str "Type :" (name type-kw)
                     " has non-keyword instances: " (pr-str (vec bad)))
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

(defn- load-subject-glossary
  "Load subject glossary from path. Returns {:subjects {...}} or empty on error."
  [path]
  (if (nil? path)
    {:subjects {}}
    (let [result (load-glossary path)]
      (if (glossary-error? result)
        (do (log/warnf "Could not load subject glossary: %s" (:message result))
            {:subjects {}})
        {:subjects (:subjects result)}))))

(defn- load-subject-glossary-strict
  "Load subject glossary strictly. Returns {:ok {:subjects ...}} or {:error ...}."
  [path]
  (if (nil? path)
    {:ok {:subjects {}}}
    (let [result (load-glossary path)]
      (if (glossary-error? result)
        {:error {:type :svo/glossary-file-not-found
                 :path path
                 :message (:message result)}}
        {:ok {:subjects (:subjects result)}}))))

(defn- load-verb-glossary
  "Load verb glossary from path for an interface type.
   Returns {:verbs {type {...}}} or empty on error.
   Bad verb entries (per ::verb-entry spec) emit warnings but the
   glossary still loads — lenient mode favors continuity."
  [interface-type path]
  (if (nil? path)
    {:verbs {}}
    (let [result (load-glossary path)]
      (cond
        (glossary-error? result)
        (do (log/warnf "Could not load verb glossary for %s: %s" interface-type (:message result))
            {:verbs {}})

        :else
        (let [errors (validate-verb-entries (:verbs result) interface-type path)]
          (doseq [e errors]
            (log/warn (:message e)))
          {:verbs {interface-type (:verbs result)}})))))

(defn- load-verb-glossary-strict
  "Load verb glossary strictly. Returns {:ok {:verbs {type ...}}} or {:error ...}.
   Fails on file errors AND on bad verb entries — Shifted mode demands
   a clean glossary."
  [interface-type path]
  (if (nil? path)
    {:ok {:verbs {}}}
    (let [result (load-glossary path)]
      (cond
        (glossary-error? result)
        {:error {:type :svo/glossary-file-not-found
                 :path path
                 :interface-type interface-type
                 :message (:message result)}}

        :else
        (let [errors (validate-verb-entries (:verbs result) interface-type path)]
          (if (seq errors)
            {:error (assoc (first errors) :all-errors errors)}
            {:ok {:verbs {interface-type (:verbs result)}}}))))))

(defn load-all-glossaries
  "Load all glossaries per config paths, merged with defaults.

   Config shape:
   {:subjects \"path/to/subjects.edn\"
    :verbs {:web \"path/to/verbs-web.edn\"
            :api \"path/to/verbs-api.edn\"}}

   Missing files produce warnings but don't fail — defaults are used.

   Returns:
   {:subjects {:alice {...} :admin {...} ...}
    :verbs {:web {:click {...} :fill {...} ...}
            :api {:get {...} :post {...} ...}}}"
  [config-paths]
  (let [defaults (load-default-glossaries)
        ;; Load subject glossary
        subject-path (:subjects config-paths)
        subject-glossary (load-subject-glossary subject-path)
        ;; Load verb glossaries for each type
        verb-paths (:verbs config-paths)
        verb-glossaries (reduce-kv
                         (fn [acc iface-type path]
                           (let [loaded (load-verb-glossary iface-type path)]
                             (update acc :verbs merge (:verbs loaded))))
                         {:verbs {}}
                         (or verb-paths {}))
        ;; Combine into project glossary
        project {:subjects (:subjects subject-glossary)
                 :verbs (:verbs verb-glossaries)}]
    (attach-instance-index (merge-glossaries defaults project) false)))

(defn load-all-glossaries-strict
  "Load all glossaries strictly for Shifted mode.

   Unlike `load-all-glossaries`, this fails on missing/invalid files instead
   of warning. Use this when :svo config is present.

   Config shape:
   {:subjects \"path/to/subjects.edn\"
    :verbs {:web \"path/to/verbs-web.edn\"
            :api \"path/to/verbs-api.edn\"}}

   Returns:
   {:ok {:subjects {...} :verbs {...}}}
   or
   {:error {:type :svo/glossary-file-not-found :path \"...\" ...}}"
  [config-paths]
  (let [defaults (load-default-glossaries)
        ;; Load subject glossary strictly
        subject-path (:subjects config-paths)
        subject-result (load-subject-glossary-strict subject-path)]
    (if (:error subject-result)
      subject-result
      ;; Subject loaded, try verb glossaries
      (let [verb-paths (or (:verbs config-paths) {})
            verb-results (reduce-kv
                          (fn [acc iface-type path]
                            (if (:error acc)
                              acc  ; short-circuit on first error
                              (let [result (load-verb-glossary-strict iface-type path)]
                                (if (:error result)
                                  result
                                  (update-in acc [:ok :verbs] merge (-> result :ok :verbs))))))
                          {:ok {:verbs {}}}
                          verb-paths)]
        (if (:error verb-results)
          verb-results
          ;; All loaded successfully, merge with defaults and build index
          (let [project {:subjects (-> subject-result :ok :subjects)
                         :verbs (-> verb-results :ok :verbs)}
                merged (merge-glossaries defaults project)
                indexed (attach-instance-index merged true)]
            (if (:error indexed)
              indexed
              {:ok indexed})))))))

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

(defn costume-for-subject
  "Resolve the costume a subject `:wears`, or nil.

   The `:wears` keyword lives on the subject *type* entry, so an instance
   (`:user/alice`) inherits its type's costume. Resolves singleton, type,
   and instance subject forms uniformly via `resolve-subject`. See sl-rnm.

   Returns the costume keyword (e.g. `:finance`) or nil if the subject is
   unknown or wears nothing."
  [glossary subject]
  (when-let [type-kw (:type (resolve-subject glossary subject))]
    (:wears (get-in glossary [:subjects type-kw]))))

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
