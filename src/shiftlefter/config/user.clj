(ns shiftlefter.config.user
  "User-level configuration loading.

   User config is stored at `~/.shiftlefter/config.edn` and provides
   machine-specific defaults that apply across all projects.

   ## Config Shape

   ```clojure
   {:chrome-path \"/custom/path/to/chrome\"
    :chromedriver-path \"/custom/path/to/chromedriver\"}
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
   ;; => {:chrome-path \"/custom/chrome\"}

   ;; Merge with explicit opts (opts win)
   (merge-with-user-config {:chrome-path \"/other/chrome\"})
   ;; => {:chrome-path \"/other/chrome\"}
   ```"
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [shiftlefter.gherkin.io :as gio]))

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
   ;; => {:chrome-path \"/custom/chrome\"}

   ;; File doesn't exist
   (load-user-config)
   ;; => nil
   ```"
  []
  (let [path (user-config-path)]
    (when (fs/exists? path)
      (edn/read-string (gio/slurp-utf8 path)))))

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

(defn merge-with-user-config
  "Merge explicit opts with user config.

   User config provides defaults; explicit opts override.

   Relevant keys from user config:
   - :chrome-path — path to Chrome binary
   - :chromedriver-path — path to ChromeDriver binary

   Example:
   ```clojure
   ;; User config: {:chrome-path \"/custom/chrome\"}

   (merge-with-user-config {})
   ;; => {:chrome-path \"/custom/chrome\"}

   (merge-with-user-config {:chrome-path \"/other/chrome\"})
   ;; => {:chrome-path \"/other/chrome\"}
   ```"
  [opts]
  (let [user-config (load-user-config)]
    (if user-config
      (merge user-config opts)
      opts)))

;; -----------------------------------------------------------------------------
;; ChromeDriver Resolution
;; -----------------------------------------------------------------------------

(defn resolve-chromedriver-path
  "Resolve the ChromeDriver (WebDriver server) binary path.

   Single source of truth for chromedriver discovery, shared by the fresh-spawn
   adapter path (`adapters/etaoin` `create-browser`) and the costume
   launch/attach path (`shiftlefter.costume`) so they cannot drift.

   Precedence:
   1. `:path-driver` in opts (highest — explicit override)
   2. `:chromedriver-path` in `~/.shiftlefter/config.edn`
   3. nil (caller falls through to PATH)

   Parse errors in the user config are swallowed (→ nil → PATH), matching the
   adapter's prior inline behavior.

   Example:
   ```clojure
   (resolve-chromedriver-path {:path-driver \"/explicit/chromedriver\"})
   ;; => \"/explicit/chromedriver\"

   ;; config.edn {:chromedriver-path \"/cfg/chromedriver\"}, no opt
   (resolve-chromedriver-path {})
   ;; => \"/cfg/chromedriver\"
   ```"
  [opts]
  (or (:path-driver opts)
      (:chromedriver-path (:ok (load-user-config-safe)))))
