(ns shiftlefter.config.user
  "User-level configuration loading.

   User config is stored at `~/.shiftlefter/config.edn` and provides
   machine-specific defaults that apply across all projects.

   ## Config Shape

   ```clojure
   {:chrome-path \"/custom/path/to/chrome\"
    :chromedriver-path \"/custom/path/to/chromedriver\"
    :default-stealth true}
   ```

   ## Precedence

   User config has LOWER priority than explicit options:
   1. Explicit opts (highest)
   2. User config (~/.shiftlefter/config.edn)
   3. Built-in defaults (lowest)

   ## Usage

   ```clojure
   ;; Load user config
   (load-user-config)
   ;; => {:chrome-path \"/custom/chrome\" :default-stealth true}

   ;; Merge with explicit opts (opts win)
   (merge-with-user-config {:stealth false})
   ;; => {:chrome-path \"/custom/chrome\" :stealth false}
   ```"
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]))

;; -----------------------------------------------------------------------------
;; Path Helpers
;; -----------------------------------------------------------------------------

(defn- home-dir
  "Get user's home directory."
  []
  (System/getProperty "user.home"))

(defn- shiftlefter-dir
  "Get ShiftLefter config directory: ~/.shiftlefter"
  []
  (str (home-dir) "/.shiftlefter"))

(defn user-config-path
  "Get the path to user config file.

   Returns `~/.shiftlefter/config.edn`."
  []
  (str (shiftlefter-dir) "/config.edn"))

;; -----------------------------------------------------------------------------
;; Config Loading
;; -----------------------------------------------------------------------------

(defn load-user-config
  "Load user configuration from ~/.shiftlefter/config.edn.

   Returns the config map if file exists and is valid EDN,
   or nil if file doesn't exist.

   Throws on parse errors (malformed EDN).

   Example:
   ```clojure
   (load-user-config)
   ;; => {:chrome-path \"/custom/chrome\" :default-stealth true}

   ;; File doesn't exist
   (load-user-config)
   ;; => nil
   ```"
  []
  (let [path (user-config-path)]
    (when (fs/exists? path)
      (edn/read-string (slurp path)))))

(defn load-user-config-safe
  "Like load-user-config but catches parse errors.

   Returns:
   - {:ok config} on success (config may be nil if file missing)
   - {:error {:type :user-config/parse-failed :message ...}} on parse error"
  []
  (try
    {:ok (load-user-config)}
    (catch Exception e
      {:error {:type :user-config/parse-failed
               :message (str "Failed to parse user config: " (ex-message e))
               :path (user-config-path)}})))

;; -----------------------------------------------------------------------------
;; Config Merging
;; -----------------------------------------------------------------------------

(defn- translate-user-keys
  "Translate user config keys to opts keys.

   User config uses different key names for clarity:
   - :default-stealth -> :stealth (only if :stealth not in opts)"
  [user-config opts]
  (cond-> user-config
    ;; Only apply default-stealth if :stealth not explicitly set
    (and (contains? user-config :default-stealth)
         (not (contains? opts :stealth)))
    (assoc :stealth (:default-stealth user-config))))

(defn merge-with-user-config
  "Merge explicit opts with user config.

   User config provides defaults; explicit opts override.

   Relevant keys from user config:
   - :chrome-path — path to Chrome binary
   - :chromedriver-path — path to ChromeDriver binary
   - :default-stealth — default value for :stealth option

   Example:
   ```clojure
   ;; User config: {:chrome-path \"/custom/chrome\" :default-stealth true}

   (merge-with-user-config {})
   ;; => {:chrome-path \"/custom/chrome\" :stealth true}

   (merge-with-user-config {:stealth false})
   ;; => {:chrome-path \"/custom/chrome\" :stealth false}

   (merge-with-user-config {:chrome-path \"/other/chrome\"})
   ;; => {:chrome-path \"/other/chrome\" :stealth true}
   ```"
  [opts]
  (let [user-config (load-user-config)]
    (if user-config
      (let [translated (translate-user-keys user-config opts)]
        (merge translated opts))
      opts)))
