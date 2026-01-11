(ns shiftlefter.runner.config
  "Configuration loading for ShiftLefter runner.

   ## Config Precedence

   1. `--config <path>` (explicit CLI flag)
   2. `./shiftlefter.edn` (project default)
   3. Built-in defaults

   ## Config Shape

   ```clojure
   {:parser {:dialect \"en\"}
    :runner {:step-paths [\"steps/\"]
             :allow-pending? false
             :macros {:enabled? false}}
    :glossaries {:subjects \"config/glossaries/subjects.edn\"
                 :verbs {:web \"config/glossaries/verbs-web.edn\"}}
    :interfaces {:web {:type :web
                       :adapter :etaoin
                       :config {:headless true}}}
    :svo {:unknown-subject :warn
          :unknown-verb :warn
          :unknown-interface :error}}
   ```

   ## Usage

   ```clojure
   (load-config {:config-path \"my-config.edn\"})
   ;=> {:parser {:dialect \"en\"} :runner {...} ...}
   ```"
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [shiftlefter.gherkin.io :as io]))

;; -----------------------------------------------------------------------------
;; Default Configuration
;; -----------------------------------------------------------------------------

;; Known interface types (verb vocabulary domains)
(def known-interface-types
  "Set of known interface types that have verb glossaries."
  #{:web :api :sms :email})

(def default-config
  "Built-in default configuration."
  {:parser {:dialect "en"}
   :runner {:step-paths ["steps/"]
            :allow-pending? false
            :macros {:enabled? false}}
   ;; Glossary paths — nil means use framework defaults only
   :glossaries nil
   ;; Interface definitions — name -> {:type :adapter :config}
   :interfaces {:web {:type :web
                      :adapter :etaoin
                      :config {}}}
   ;; SVO enforcement settings
   :svo {:unknown-subject :warn
         :unknown-verb :warn
         :unknown-interface :error}})

;; -----------------------------------------------------------------------------
;; Config Loading
;; -----------------------------------------------------------------------------

(defn- deep-merge
  "Deep merge maps. Later values override earlier ones.
   For non-map values, later wins."
  [& maps]
  (reduce (fn [acc m]
            (reduce-kv (fn [a k v]
                         (if (and (map? (get a k)) (map? v))
                           (assoc a k (deep-merge (get a k) v))
                           (assoc a k v)))
                       acc
                       m))
          {}
          maps))

(defn- read-config-file
  "Read and parse an EDN config file.
   Returns {:status :ok :config {...}} or {:status :error ...}"
  [path]
  (try
    (let [content (io/slurp-utf8 path)]
      (if (:status content)
        ;; slurp-utf8 returned an error
        content
        {:status :ok
         :config (edn/read-string content)}))
    (catch Exception e
      {:status :error
       :type :config/parse-failed
       :message (str "Failed to parse config: " (ex-message e))
       :path path})))

(defn- find-default-config
  "Find the default config file (./shiftlefter.edn) if it exists."
  []
  (let [path "shiftlefter.edn"]
    (when (fs/exists? path)
      path)))

;; -----------------------------------------------------------------------------
;; Public API
;; -----------------------------------------------------------------------------

(defn load-config
  "Load configuration with precedence.

   Options:
   - :config-path - explicit config file path (highest precedence)

   Precedence:
   1. Explicit --config path
   2. ./shiftlefter.edn (if exists)
   3. Built-in defaults

   Returns merged config map.

   Throws on:
   - Explicit config path doesn't exist
   - Config file parse error"
  ([] (load-config {}))
  ([opts]
   (let [explicit-path (:config-path opts)
         default-path (find-default-config)]
     (cond
       ;; Explicit path specified
       explicit-path
       (if (fs/exists? explicit-path)
         (let [result (read-config-file explicit-path)]
           (if (= :ok (:status result))
             (deep-merge default-config (:config result))
             (throw (ex-info (:message result)
                             {:type (:type result)
                              :path explicit-path}))))
         (throw (ex-info (str "Config file not found: " explicit-path)
                         {:type :config/not-found
                          :path explicit-path})))

       ;; Default config exists
       default-path
       (let [result (read-config-file default-path)]
         (if (= :ok (:status result))
           (deep-merge default-config (:config result))
           (throw (ex-info (:message result)
                           {:type (:type result)
                            :path default-path}))))

       ;; No config file, use defaults
       :else
       default-config))))

(defn load-config-safe
  "Like load-config but returns error map instead of throwing.

   Returns:
   - {:status :ok :config {...}}
   - {:status :error :type ... :message ...}"
  ([] (load-config-safe {}))
  ([opts]
   (try
     {:status :ok
      :config (load-config opts)}
     (catch Exception e
       {:status :error
        :type (or (:type (ex-data e)) :config/error)
        :message (ex-message e)
        :path (:path (ex-data e))}))))

(defn get-dialect
  "Get the parser dialect from config."
  [config]
  (get-in config [:parser :dialect] "en"))

(defn get-step-paths
  "Get the step paths from config."
  [config]
  (get-in config [:runner :step-paths] ["steps/"]))

(defn allow-pending?
  "Check if pending steps are allowed (don't cause failure)."
  [config]
  (get-in config [:runner :allow-pending?] false))

(defn macros-enabled?
  "Check if macros are enabled in config."
  [config]
  (get-in config [:runner :macros :enabled?] false))

(defn get-macro-registry-paths
  "Get macro registry paths from config."
  [config]
  (get-in config [:runner :macros :registry-paths] []))

;; -----------------------------------------------------------------------------
;; Config Normalization / Validation
;; -----------------------------------------------------------------------------

(defn- validate-macro-config
  "Validate macro configuration.
   Returns nil if valid, or error map if invalid."
  [config]
  (let [macros (get-in config [:runner :macros])
        enabled? (:enabled? macros)]
    (when enabled?
      (let [paths (:registry-paths macros)]
        (when (or (nil? paths) (empty? paths))
          {:type :macro/config-missing-registry-paths
           :message "Macros enabled but :registry-paths is missing or empty"})))))

(defn- validate-single-interface
  "Validate a single interface definition.
   Returns nil if valid, or error map if invalid."
  [interface-name interface-def]
  (cond
    ;; Must have :type
    (nil? (:type interface-def))
    {:type :config/invalid-interface
     :message (str "Interface :" (name interface-name) " missing required :type key")
     :data {:interface interface-name
            :definition interface-def}}

    ;; Must have :adapter
    (nil? (:adapter interface-def))
    {:type :config/invalid-interface
     :message (str "Interface :" (name interface-name) " missing required :adapter key")
     :data {:interface interface-name
            :definition interface-def}}

    ;; :type must be known
    (not (contains? known-interface-types (:type interface-def)))
    {:type :config/unknown-interface-type
     :message (str "Interface :" (name interface-name) " has unknown type :"
                   (name (:type interface-def))
                   ". Known types: " (pr-str known-interface-types))
     :data {:interface interface-name
            :type (:type interface-def)
            :known-types known-interface-types}}

    :else nil))

(defn- validate-interfaces-config
  "Validate all interface definitions.
   Returns vector of errors (may be empty)."
  [config]
  (let [interfaces (:interfaces config)]
    (when (map? interfaces)
      (keep (fn [[name def]]
              (validate-single-interface name def))
            interfaces))))

(defn- normalize-webdriver-config
  "Normalize webdriver configuration.

   Accepts:
   - {:webdriver-url \"http://host:port\"} — used as-is
   - {:webdriver {:host \"...\" :port N}} — normalized to :webdriver-url

   Returns [config' error] where error is nil if valid."
  [config]
  (let [webdriver-url (:webdriver-url config)
        webdriver (:webdriver config)]
    (cond
      ;; Direct URL provided — use as-is
      webdriver-url
      [config nil]

      ;; No webdriver config — valid (lazy validation)
      (nil? webdriver)
      [config nil]

      ;; Map with host+port — normalize to URL
      (and (map? webdriver)
           (:host webdriver)
           (:port webdriver))
      (let [url (str "http://" (:host webdriver) ":" (:port webdriver))]
        [(assoc config :webdriver-url url) nil])

      ;; Map provided but incomplete
      (map? webdriver)
      [config {:type :webdriver/invalid-config
               :message "webdriver config requires both :host and :port, or use :webdriver-url directly"
               :data {:webdriver webdriver}}]

      ;; Invalid type
      :else
      [config {:type :webdriver/invalid-config
               :message "webdriver config must be a map with :host/:port"
               :data {:webdriver webdriver
                      :type (type webdriver)}}])))

(defn normalize
  "Normalize and validate configuration.

   Takes a raw config map (already merged with defaults) and validates it.

   Returns:
   - On success: the config map (possibly with :webdriver-url derived)
   - On failure: the config map with :errors vector added

   Validation rules:
   - If [:runner :macros :enabled?] is true, [:runner :macros :registry-paths]
     must be a non-empty vector, else error :macro/config-missing-registry-paths
   - If :webdriver is provided, must have :host and :port (or use :webdriver-url)
   - Each interface must have :type and :adapter
   - Interface :type must be known (:web, :api, :sms, :email)
   - Empty config is valid (no webdriver errors if not provided)"
  [config]
  (let [[config' webdriver-error] (normalize-webdriver-config config)
        interface-errors (validate-interfaces-config config')
        errors (concat (keep identity [(validate-macro-config config')
                                       webdriver-error])
                       interface-errors)]
    (if (seq errors)
      (assoc config' :errors (vec errors))
      config')))

;; -----------------------------------------------------------------------------
;; WebDriver Config Accessors
;; -----------------------------------------------------------------------------

(defn get-webdriver-url
  "Get the normalized webdriver URL from config, or nil if not configured."
  [config]
  (:webdriver-url config))

;; -----------------------------------------------------------------------------
;; Glossary Config Accessors
;; -----------------------------------------------------------------------------

(defn get-glossary-config
  "Get the glossary paths configuration.

   Returns map with:
   - :subjects — path to subjects glossary (or nil)
   - :verbs — map of interface-type -> path (or nil)

   Example return:
   {:subjects \"config/glossaries/subjects.edn\"
    :verbs {:web \"config/glossaries/verbs-web.edn\"}}"
  [config]
  (:glossaries config))

;; -----------------------------------------------------------------------------
;; Interface Config Accessors
;; -----------------------------------------------------------------------------

(defn get-interfaces
  "Get all interface definitions.

   Returns map of interface-name -> {:type :adapter :config}."
  [config]
  (:interfaces config))

(defn get-interface
  "Get a specific interface definition by name.

   Returns {:type :adapter :config} or nil if not found."
  [config interface-name]
  (get-in config [:interfaces interface-name]))

(defn get-interface-type
  "Get the type of an interface.

   Returns the :type keyword (e.g., :web, :api) or nil."
  [config interface-name]
  (get-in config [:interfaces interface-name :type]))

(defn get-interface-adapter
  "Get the adapter for an interface.

   Returns the :adapter keyword (e.g., :etaoin) or nil."
  [config interface-name]
  (get-in config [:interfaces interface-name :adapter]))

;; -----------------------------------------------------------------------------
;; SVO Config Accessors
;; -----------------------------------------------------------------------------

(defn get-svo-settings
  "Get SVO enforcement settings.

   Returns map with enforcement levels:
   - :unknown-subject — :warn or :error
   - :unknown-verb — :warn or :error
   - :unknown-interface — :warn or :error"
  [config]
  (:svo config))

(defn svo-enforcement
  "Get the enforcement level for a specific SVO check.

   check should be one of:
   - :unknown-subject
   - :unknown-verb
   - :unknown-interface

   Returns :warn, :error, or the configured value."
  [config check]
  (get-in config [:svo check]))
