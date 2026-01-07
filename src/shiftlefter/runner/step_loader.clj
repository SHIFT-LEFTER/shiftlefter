(ns shiftlefter.runner.step-loader
  "Step definition loader for ShiftLefter runner.

   Loads step definition files from configured paths, populating the registry.

   ## Loading Behavior

   - Directories: recursively loads all `*.clj` and `*.cljc` files in sorted path order
   - Files: loads directly via `load-file`
   - Before loading, clears the registry (fresh state per run)
   - Any load error is captured and returned (planning/setup failure)

   ## Usage

   ```clojure
   (load-step-paths! [\"steps/\" \"more_steps.clj\"])
   ;; => {:status :ok :loaded [\"steps/a.clj\" \"steps/b.clj\" \"more_steps.clj\"]}
   ;; or
   ;; => {:status :error :errors [...] :loaded [...]}
   ```"
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [shiftlefter.stepengine.registry :as registry]))

;; -----------------------------------------------------------------------------
;; File Discovery
;; -----------------------------------------------------------------------------

(defn- find-step-files
  "Find all Clojure files in a directory, recursively, sorted by path.
   Returns seq of absolute path strings."
  [dir]
  (->> (fs/glob dir "**.{clj,cljc}")
       (map str)
       (sort)))

;; -----------------------------------------------------------------------------
;; File Loading
;; -----------------------------------------------------------------------------

(defn- load-step-file!
  "Load a single step file. Returns result map.

   Success: {:status :ok :path \"...\" :file \"...\"}
   Failure: {:status :error :path \"...\" :error {:type :message :cause}}"
  [path]
  (let [path-str (str path)]
    (try
      (load-file path-str)
      {:status :ok
       :path path-str
       :file (fs/file-name path)}
      (catch Exception e
        {:status :error
         :path path-str
         :error {:type :step-load/failed
                 :message (ex-message e)
                 :cause (some-> (ex-cause e) ex-message)}}))))

;; -----------------------------------------------------------------------------
;; Public API
;; -----------------------------------------------------------------------------

(defn load-step-paths!
  "Load step definitions from the given paths.

   Each path can be:
   - A directory: recursively loads all *.clj and *.cljc files (sorted)
   - A file: loads directly

   Before loading, clears the registry for fresh state.

   Returns:
   - {:status :ok :loaded [paths...]} on success
   - {:status :error :errors [...] :loaded [...]} if any file failed

   Any load error should be treated as a planning/setup failure (exit 2)."
  [paths]
  (registry/clear-registry!)
  (let [;; Expand directories to individual files, keep files as-is
        ;; Non-existent directories are silently skipped (common for defaults)
        ;; Non-existent files are kept and will error at load time
        all-files (mapcat (fn [path]
                            (cond
                              (fs/directory? path)
                              (find-step-files path)

                              (fs/exists? path)
                              [(str path)]

                              ;; Path doesn't exist - skip if looks like directory, else keep for error
                              (or (str/ends-with? (str path) "/")
                                  (not (str/includes? (str path) ".")))
                              [] ;; Skip non-existent directories silently

                              :else
                              ;; Non-existent file - will fail at load time
                              [(str path)]))
                          paths)
        ;; Sort all files for deterministic order
        sorted-files (sort all-files)
        ;; Load each file
        results (mapv load-step-file! sorted-files)
        ;; Partition by status
        {oks :ok errs :error} (group-by :status results)
        loaded-paths (mapv :path oks)
        errors (mapv #(select-keys % [:path :error]) errs)]
    (if (seq errors)
      {:status :error
       :errors errors
       :loaded loaded-paths}
      {:status :ok
       :loaded loaded-paths})))

(defn load-step-paths-or-throw!
  "Like load-step-paths! but throws on any error.
   Useful when caller wants exception-based error handling."
  [paths]
  (let [result (load-step-paths! paths)]
    (when (= :error (:status result))
      (throw (ex-info (str "Failed to load step definitions: "
                           (count (:errors result)) " error(s)")
                      {:type :step-load/failed
                       :errors (:errors result)
                       :loaded (:loaded result)})))
    result))
