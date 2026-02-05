(ns shiftlefter.svo.glossary
  "Glossary loading and querying for SVO validation.

   ## Glossary Formats

   Subject glossary:
   ```clojure
   {:subjects
    {:alice {:desc \"Standard customer account\"}
     :admin {:desc \"Administrative user\"}}}
   ```

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
   (known-subject? g :alice)      ;; => true
   (known-verb? g :web :click)    ;; => true
   (known-subjects g)             ;; => [:alice :admin ...]
   (known-verbs g :web)           ;; => [:click :fill ...]
   ```"
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [shiftlefter.gherkin.io :as sio]))

;; -----------------------------------------------------------------------------
;; Constants
;; -----------------------------------------------------------------------------

(def ^:private default-verbs-resource
  "Classpath path to default verb glossaries."
  "shiftlefter/glossaries/verbs-web.edn")

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

   Currently only :web is shipped. Returns nil for unknown types."
  [interface-type]
  (case interface-type
    :web (let [result (read-edn-resource default-verbs-resource)]
           (when (:ok result)
             (:ok result)))
    nil))

(defn load-default-glossaries
  "Load all framework default glossaries.

   Returns:
   {:subjects {}  ;; no default subjects — project must define
    :verbs {:web {...}}}"
  []
  {:subjects {}
   :verbs (if-let [web-verbs (load-default-verbs :web)]
            {:web (:verbs web-verbs)}
            {})})

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

(defn- warn
  "Print warning to stderr."
  [& args]
  (binding [*out* *err*]
    (apply println "WARNING:" args)))

(defn- glossary-error?
  "Returns true if result is a glossary error map."
  [result]
  (and (map? result)
       (keyword? (:type result))
       (= "glossary" (namespace (:type result)))))

(defn- load-subject-glossary
  "Load subject glossary from path. Returns {:subjects {...}} or empty on error."
  [path]
  (if (nil? path)
    {:subjects {}}
    (let [result (load-glossary path)]
      (if (glossary-error? result)
        (do (warn "Could not load subject glossary:" (:message result))
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
   Returns {:verbs {type {...}}} or empty on error."
  [interface-type path]
  (if (nil? path)
    {:verbs {}}
    (let [result (load-glossary path)]
      (if (glossary-error? result)
        (do (warn "Could not load verb glossary for" interface-type ":" (:message result))
            {:verbs {}})
        {:verbs {interface-type (:verbs result)}}))))

(defn- load-verb-glossary-strict
  "Load verb glossary strictly. Returns {:ok {:verbs {type ...}}} or {:error ...}."
  [interface-type path]
  (if (nil? path)
    {:ok {:verbs {}}}
    (let [result (load-glossary path)]
      (if (glossary-error? result)
        {:error {:type :svo/glossary-file-not-found
                 :path path
                 :interface-type interface-type
                 :message (:message result)}}
        {:ok {:verbs {interface-type (:verbs result)}}}))))

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
    (merge-glossaries defaults project)))

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
          ;; All loaded successfully, merge with defaults
          (let [project {:subjects (-> subject-result :ok :subjects)
                         :verbs (-> verb-results :ok :verbs)}]
            {:ok (merge-glossaries defaults project)}))))))

;; -----------------------------------------------------------------------------
;; Public API: Querying
;; -----------------------------------------------------------------------------

(defn known-subject?
  "Returns true if subject is in the glossary."
  [glossary subject]
  (contains? (:subjects glossary) subject))

(defn known-verb?
  "Returns true if verb is known for the given interface type."
  [glossary interface-type verb]
  (contains? (get-in glossary [:verbs interface-type]) verb))

(defn known-subjects
  "Returns all known subject keywords."
  [glossary]
  (vec (keys (:subjects glossary))))

(defn known-verbs
  "Returns all known verb keywords for the given interface type."
  [glossary interface-type]
  (vec (keys (get-in glossary [:verbs interface-type]))))

(defn subject-info
  "Returns the info map for a subject, or nil if unknown."
  [glossary subject]
  (get-in glossary [:subjects subject]))

(defn verb-info
  "Returns the info map for a verb, or nil if unknown."
  [glossary interface-type verb]
  (get-in glossary [:verbs interface-type verb]))
