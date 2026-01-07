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
             :allow-pending? false}}
   ```

   ## Usage

   ```clojure
   (load-config {:config-path \"my-config.edn\"})
   ;=> {:parser {:dialect \"en\"} :runner {...}}
   ```"
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [shiftlefter.gherkin.io :as io]))

;; -----------------------------------------------------------------------------
;; Default Configuration
;; -----------------------------------------------------------------------------

(def default-config
  "Built-in default configuration."
  {:parser {:dialect "en"}
   :runner {:step-paths ["steps/"]
            :allow-pending? false
            :macros {:enabled? false}}})

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

(defn normalize
  "Normalize and validate configuration.

   Takes a raw config map (already merged with defaults) and validates it.

   Returns:
   - On success: the config map (unchanged or with defaults filled in)
   - On failure: the config map with :errors vector added

   Validation rules:
   - If [:runner :macros :enabled?] is true, [:runner :macros :registry-paths]
     must be a non-empty vector, else error :macro/config-missing-registry-paths"
  [config]
  (let [errors (keep identity [(validate-macro-config config)])]
    (if (seq errors)
      (assoc config :errors (vec errors))
      config)))
