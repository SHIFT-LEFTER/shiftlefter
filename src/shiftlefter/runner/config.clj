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
          :unknown-interface :error
          :unknown-object :off}}
   ```

   ## Usage

   ```clojure
   (load-config {:config-path \"my-config.edn\"})
   ;=> {:parser {:dialect \"en\"} :runner {...} ...}
   ```"
  (:require [clojure.edn :as edn]
            [clojure.spec.alpha :as s]
            [shiftlefter.gherkin.io :as io]
            [shiftlefter.project-context :as project-context]))

;; -----------------------------------------------------------------------------
;; Specs — Config Shape
;; -----------------------------------------------------------------------------

;; Parser sub-config
(s/def ::dialect string?)
(s/def ::parser (s/keys :req-un [::dialect]))

;; Runner sub-config
(s/def ::step-paths (s/coll-of string?))
(s/def ::allow-pending? boolean?)
(s/def ::enabled? boolean?)
(s/def ::registry-paths (s/coll-of string?))
(s/def ::macros (s/keys :req-un [::enabled?]
                        :opt-un [::registry-paths]))
;; sl-aa5: capability provisioning strategy. :eager (default, scoped-eager
;; — provision every interface the scenario touches at scenario start)
;; or :lazy (legacy, provision on first step that needs it). Opt-out
;; exists for the rare test pattern that benefits from lazy short-circuit.
(s/def ::provisioning #{:eager :lazy})
(s/def ::runner (s/keys :req-un [::step-paths ::allow-pending?]
                        :opt-un [::macros ::provisioning]))

;; Interface sub-config
(s/def ::type keyword?)
(s/def ::adapter keyword?)
;; :shared-impl? true means a single adapter impl is shared across all
;; subject-keyed entries (:cap/<iface>.alice, :cap/<iface>.bob both
;; reference the same impl). Default false: each subject gets its own
;; impl (browser pattern). Used for interfaces where one resource per
;; scenario suffices, e.g. SMS Twilio account, future shared queues.
(s/def ::shared-impl? boolean?)
(s/def ::interface-def (s/keys :req-un [::type ::adapter]
                               :opt-un [::config ::shared-impl?]))
(s/def ::interfaces (s/map-of keyword? ::interface-def))

;; SVO enforcement sub-config
(s/def ::unknown-subject #{:warn :error :off})
(s/def ::unknown-verb #{:warn :error :off})
(s/def ::unknown-interface #{:warn :error :off})
(s/def ::unknown-object #{:strict :warn :off})
(s/def ::svo (s/keys :opt-un [::unknown-subject ::unknown-verb ::unknown-interface ::unknown-object]))

;; Glossaries sub-config
(s/def ::subjects (s/nilable string?))
(s/def ::verbs (s/nilable (s/map-of keyword? string?)))
(s/def ::glossaries (s/nilable (s/keys :opt-un [::subjects ::verbs])))

;; Top-level config
(s/def ::config
  (s/keys :opt-un [::parser ::runner ::glossaries ::interfaces ::svo]))

;; load-config-safe result
(s/def ::status #{:ok :error})
(s/def ::message string?)
(s/def ::path string?)
(s/def ::config-ok (s/keys :req-un [::status ::config]))
(s/def ::config-error (s/keys :req-un [::status ::type ::message]
                              :opt-un [::path]))
(s/def ::config-result (s/or :ok ::config-ok :error ::config-error))

;; -----------------------------------------------------------------------------
;; Default Configuration
;; -----------------------------------------------------------------------------

;; Known interface types (verb vocabulary domains)
;; NOTE: closed set — gates :unknown-interface SVO validation. Add new
;; interface types here when introducing them (e.g., :cli, :queue).
(def known-interface-types
  "Set of known interface types that have verb glossaries."
  #{:web :api :sms :email})

(def default-config
  "Built-in default configuration.

   NOTE: the :svo block documents the enforcement defaults that apply once
   a user OPTS IN to Shifted mode by putting an :svo key in their config.
   `load-config` strips :svo from its result when the user's config lacks
   the key — presence of :svo in a loaded config therefore means the user
   asked for Shifted mode (sl-ieie; ARCHITECTURE.md ':svo absent = Vanilla')."
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
   ;; SVO enforcement settings (Shifted mode opt-in defaults — see docstring)
   :svo {:unknown-subject :warn
         :unknown-verb :warn
         :unknown-interface :error
         :unknown-object :off}})

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

(defn find-default-config
  "Find the default config file `shiftlefter.edn` if it exists.

   Resolved against `shiftlefter.paths/user-cwd` (the project the user ran `sl`
   from), NOT the raw process CWD — which is the install dir when `sl` runs from
   PATH. Returns the resolved absolute path string or nil. Public so the runner
   can use it to locate sibling files (notably setup.clj, see runner/setup.clj)."
  []
  (let [context (project-context/resolve)]
    (when (= :discovered (:config-source context))
      (:config-path context))))

(defn resolve-config-path
  "Resolve which shiftlefter.edn would actually be loaded for the given opts.

   Mirrors load-config's precedence:
     1. Explicit --config path
     2. ./shiftlefter.edn
     3. nil (built-in defaults, no config file)"
  [opts]
  (:config-path (or (:project-context opts)
                    (project-context/resolve
                     (cond-> {}
                       (:config-path opts) (assoc :config-path (:config-path opts)))))))

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

   Mode invariant (sl-ieie): the result contains :svo iff the USER's config
   contained :svo. Shifted mode is explicit opt-in — when the user's config
   has :svo, the default enforcement levels are deep-merged under it; when
   it doesn't (or no config file exists), :svo is absent from the result and
   the run is Vanilla. `stepengine.compile/shifted-mode?` keys on this.

   Throws on:
   - Explicit config path doesn't exist
   - Config file parse error"
  ([] (load-config {}))
  ([opts]
   (let [context (or (:project-context opts)
                     (project-context/resolve
                      (cond-> {}
                        (:config-path opts) (assoc :config-path (:config-path opts)))))
         config-path (:config-path context)
         diagnostic (first (:diagnostics context))]
     (cond
       (= :config/not-found (:type diagnostic))
       (throw (ex-info (:message diagnostic)
                       {:type :config/not-found
                        :path (:path diagnostic)}))

       (= :project-context/ambiguous-config (:type diagnostic))
       (throw (ex-info (:message diagnostic)
                       {:type (:type diagnostic)
                        :paths (:paths diagnostic)}))

       config-path
       (let [result (read-config-file config-path)]
         (if (= :ok (:status result))
           (let [user-config (:config result)
                 merged (deep-merge default-config user-config)]
             ;; Shifted mode is user opt-in: keep :svo (with defaults merged
             ;; under it) only when the user's config has the key (sl-ieie).
             (if (contains? user-config :svo)
               merged
               (dissoc merged :svo)))
           (throw (ex-info (:message result)
                           {:type (:type result)
                            :path config-path}))))

       ;; No config file, use defaults — no user :svo, so Vanilla (sl-ieie)
       :else
       (dissoc default-config :svo)))))

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

(defn provisioning-mode
  "Return the capability-provisioning strategy: `:eager` (scoped-eager,
   default) or `:lazy` (per-step on first touch). Drives the eager
   phase in `stepengine.exec/execute-scenario`. See sl-aa5."
  [config]
  (get-in config [:runner :provisioning] :eager))

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
   - :unknown-subject — :warn, :error, or :off
   - :unknown-verb — :warn, :error, or :off
   - :unknown-interface — :warn, :error, or :off
   - :unknown-object — :strict, :warn, or :off

   Returns the configured enforcement level."
  [config check]
  (get-in config [:svo check]))
