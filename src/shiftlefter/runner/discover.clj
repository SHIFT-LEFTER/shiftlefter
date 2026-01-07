(ns shiftlefter.runner.discover
  "Feature file discovery for ShiftLefter runner.

   Discovers `.feature` files from paths, directories, and globs.

   ## Discovery Rules

   - Files: used directly if they exist and end with `.feature`
   - Directories: recursively find all `**/*.feature`
   - Globs: expanded (don't rely on shell)
   - Dedup: by realpath (same file via different paths = one entry)
   - Ordering: stable by relative path, then realpath

   ## Usage

   ```clojure
   (discover-feature-files [\"features/\" \"extra.feature\" \"more/**/*.feature\"])
   ;=> [\"extra.feature\" \"features/a.feature\" \"features/sub/b.feature\" ...]
   ```"
  (:require [babashka.fs :as fs]
            [clojure.string :as str]))

;; -----------------------------------------------------------------------------
;; Path Classification
;; -----------------------------------------------------------------------------

(defn- glob-pattern?
  "Check if a path string contains glob wildcards."
  [path]
  (boolean (re-find #"[*?\[\]]" path)))

(defn- feature-file?
  "Check if a path is a .feature file."
  [path]
  (str/ends-with? (str path) ".feature"))

;; -----------------------------------------------------------------------------
;; Path Expansion
;; -----------------------------------------------------------------------------

(defn- expand-glob
  "Expand a glob pattern to matching files.
   Returns seq of absolute path strings."
  [pattern]
  (let [;; Split pattern into base dir and glob part
        ;; e.g., \"foo/bar/**/*.feature\" -> base=\"foo/bar\", glob=\"**/*.feature\"
        parts (str/split pattern #"/")
        ;; Find first part with wildcards
        glob-idx (first (keep-indexed #(when (glob-pattern? %2) %1) parts))
        [base-parts glob-parts] (if glob-idx
                                  [(take glob-idx parts) (drop glob-idx parts)]
                                  [parts []])
        base-dir (if (seq base-parts)
                   (str/join "/" base-parts)
                   ".")
        glob-pattern (str/join "/" glob-parts)]
    (if (and (fs/exists? base-dir) (seq glob-pattern))
      (->> (fs/glob base-dir glob-pattern)
           (map str)
           (filter feature-file?))
      [])))

(defn- expand-directory
  "Find all .feature files in a directory recursively.
   Returns seq of absolute path strings, sorted.
   Note: Uses **.feature (not **/*.feature) to match root AND subdirs."
  [dir]
  (->> (fs/glob dir "**.feature")
       (map str)
       (sort)))

(defn- expand-path
  "Expand a single path to feature files.
   Returns seq of path strings."
  [path]
  (cond
    ;; Glob pattern
    (glob-pattern? path)
    (expand-glob path)

    ;; Directory
    (fs/directory? path)
    (expand-directory path)

    ;; Single file
    (and (fs/exists? path) (feature-file? path))
    [(str (fs/absolutize path))]

    ;; Non-existent or non-feature file
    :else
    []))

;; -----------------------------------------------------------------------------
;; Deduplication and Ordering
;; -----------------------------------------------------------------------------

(defn- normalize-path
  "Normalize a path to its canonical (real) form for deduplication."
  [path]
  (try
    (str (fs/real-path path))
    (catch Exception _
      (str (fs/absolutize path)))))

(defn- relative-path
  "Get relative path from current directory."
  [path]
  (let [cwd (str (fs/cwd))]
    (if (str/starts-with? path cwd)
      (subs path (inc (count cwd)))
      path)))

;; -----------------------------------------------------------------------------
;; Public API
;; -----------------------------------------------------------------------------

(defn discover-feature-files
  "Discover feature files from the given paths.

   Each path can be:
   - A `.feature` file path
   - A directory (recursively finds `**/*.feature`)
   - A glob pattern (e.g., `features/**/*.feature`)

   Returns:
   - Vector of absolute path strings
   - Deduplicated by realpath
   - Ordered by relative path (stable, deterministic)

   Returns empty vector if no files found."
  [paths]
  (let [;; Expand all paths
        all-files (mapcat expand-path paths)
        ;; Normalize for deduplication
        normalized (map (fn [p] {:path p :real (normalize-path p)}) all-files)
        ;; Dedup by realpath, keeping first occurrence
        seen (atom #{})
        deduped (filter (fn [{:keys [real]}]
                          (when-not (@seen real)
                            (swap! seen conj real)
                            true))
                        normalized)
        ;; Sort by relative path for stable ordering
        sorted (sort-by #(relative-path (:real %)) deduped)]
    (mapv :real sorted)))

(defn discover-feature-files-or-error
  "Like discover-feature-files but returns error map if no files found.

   Returns:
   - {:status :ok :files [...]} on success
   - {:status :error :message \"...\"} if no files found or path doesn't exist"
  [paths]
  (let [;; Check for non-existent paths first
        missing (filter #(and (not (glob-pattern? %))
                              (not (fs/exists? %)))
                        paths)]
    (cond
      (seq missing)
      {:status :error
       :type :discover/path-not-found
       :message (str "Path not found: " (first missing))
       :paths missing}

      (empty? paths)
      {:status :error
       :type :discover/no-paths
       :message "No paths specified"}

      :else
      (let [files (discover-feature-files paths)]
        (if (seq files)
          {:status :ok :files files}
          {:status :error
           :type :discover/no-features
           :message (str "No .feature files found in: " (str/join ", " paths))
           :paths paths})))))
