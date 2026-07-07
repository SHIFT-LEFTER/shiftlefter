(ns shiftlefter.costume.wardrobe
  "Costume wardrobe: directory and metadata management.

   Costumes are named, durable, authenticated browser contexts stored in a
   project-scoped *wardrobe* at `<SL_USER_CWD>/.shiftlefter/wardrobe/<name>/`
   (anchored at the project the user ran `sl` from — see `shiftlefter.paths`,
   not the raw process CWD). Each costume has:
   - A Chrome user-data-dir at `chrome-profile/`
   - A metadata file `browser-meta.edn` tracking connection state

   ## Directory Structure

   ```
   .shiftlefter/wardrobe/
   └── finance/
       ├── browser-meta.edn
       └── chrome-profile/
           └── (Chrome user data)
   ```

   ## costume-meta Shape

   ```clojure
   {:debug-port 9222
    :chrome-pid 12345
    :user-data-dir \".shiftlefter/wardrobe/finance/chrome-profile\"
    :created-at \"2026-01-08T12:00:00Z\"
    :last-connected-at \"2026-01-08T12:00:00Z\"}
   ```

   The store is **project-scoped, never home-dir** — `~/.shiftlefter/subjects/` is
   no longer read. A costume holds live auth state; it must be gitignored (see
   `ensure-gitignored!`) and the tooling refuses to operate on a git-tracked
   costume dir (see `git-tracked?`)."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.java.shell :as shell]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [shiftlefter.gherkin.io :as gio]
            [shiftlefter.paths :as paths]
            [shiftlefter.project-context :as project-context])
  (:import [java.time Instant]))

;; -----------------------------------------------------------------------------
;; Path Helpers
;; -----------------------------------------------------------------------------

(def ^:dynamic *project-context*
  "Per-command project context for project-owned operational state.
   Nil keeps the legacy user-cwd fallback for direct REPL/test callers."
  nil)

(defn wardrobe-dir
  "Get the project-scoped wardrobe root: `<SL_USER_CWD>/.shiftlefter/wardrobe`.

   Anchored at the user's working directory (`shiftlefter.paths/user-cwd`) — the
   project the user ran `sl` from — NOT the raw process CWD, which is the install
   dir when `sl` runs from PATH. Returns an absolute path. This is the store root;
   individual costumes live underneath it."
  ([]
   (wardrobe-dir *project-context*))
  ([context]
   (if context
     (project-context/operational-path context ".shiftlefter" "wardrobe")
     (str (fs/path (paths/user-cwd) ".shiftlefter/wardrobe")))))

(defn costume-dir
  "Get the directory for a named costume.

   Costume names can be keywords or strings.

   Examples:
   ```clojure
   (costume-dir :finance)
   ;; => \".shiftlefter/wardrobe/finance\"

   (costume-dir \"work\")
   ;; => \".shiftlefter/wardrobe/work\"
   ```"
  [costume-name]
  (str (wardrobe-dir) "/" (name costume-name)))

(defn chrome-profile-dir
  "Get the Chrome user-data-dir for a named costume.

   This is the genuine Chrome `--user-data-dir` that lives *inside* the costume
   dir. A costume contains a chrome-profile (and later maybe a firefox-profile or
   other credentials), so this name stays Chrome-specific and correct.

   Examples:
   ```clojure
   (chrome-profile-dir :finance)
   ;; => \".shiftlefter/wardrobe/finance/chrome-profile\"
   ```"
  [costume-name]
  (str (costume-dir costume-name) "/chrome-profile"))

(defn- costume-meta-path
  "Get path to browser-meta.edn for a costume."
  [costume-name]
  (str (costume-dir costume-name) "/browser-meta.edn"))

;; -----------------------------------------------------------------------------
;; Git Safety Rail
;; -----------------------------------------------------------------------------
;; A costume holds live auth state (cookies/sessions) — treat it like `.env`;
;; committing one leaks a live session. ShiftLefter runs in arbitrary user
;; projects, so the tool self-protects.

(defn- sh-git
  "Run a git command, optionally rooted at `root` (nil = run in the CWD)."
  [root & args]
  (apply shell/sh (concat (cons "git" args) (when root [:dir root]))))

(defn- in-git-repo?
  "True if `root` (or the CWD when nil) is inside a git work tree."
  [root]
  (zero? (:exit (sh-git root "rev-parse" "--is-inside-work-tree"))))

(defn git-tracked?
  "True if `path` is tracked by git, false otherwise.

   Tolerates the non-git case: `git ls-files --error-unmatch` exits non-zero when
   the path is untracked OR when there is no git repo, both of which we treat as
   'not tracked' (fine)."
  [path]
  (zero? (:exit (shell/sh "git" "ls-files" "--error-unmatch" (str path)))))

(defn- git-ignored?
  "True if `path` is gitignored within `root` (per `git check-ignore`)."
  [root path]
  (zero? (:exit (sh-git root "check-ignore" "-q" (str path)))))

(def ^:private wardrobe-ignore-pattern
  "The pattern appended to a project .gitignore to cover the costume store."
  ".shiftlefter/wardrobe/")

(defn ensure-gitignored!
  "Ensure `.shiftlefter/wardrobe/` is gitignored in the project.

   Costumes hold live auth state, so the wardrobe must never be committed. If the
   project is a git repo and the wardrobe is not already covered, appends the
   pattern to the project `.gitignore`.

   Idempotent and a no-op when not in a git repo, when git already ignores the
   wardrobe (e.g. via a broader `.shiftlefter/` rule), or when the exact pattern
   line is already present (covers the case where the dir doesn't exist yet, for
   which `git check-ignore` of a trailing-slash pattern reports 'not ignored').

   With no arg, operates on the current project (CWD). With an explicit `root`,
   runs git there and writes `<root>/.gitignore` — used for isolated testing.

   Returns the appended pattern, or nil if nothing was done."
  ([] (ensure-gitignored! nil))
  ([root]
   (let [root (or root (:project-root *project-context*))]
     (when (in-git-repo? root)
       (let [pattern wardrobe-ignore-pattern
             gitignore (if root (str root "/.gitignore") ".gitignore")
           existing (when (fs/exists? gitignore) (gio/slurp-utf8 gitignore))
           already-listed? (boolean
                            (and existing
                                 (some #(= pattern (str/trim %))
                                       (str/split-lines existing))))]
         (when-not (or already-listed? (git-ignored? root (wardrobe-dir)))
           (let [needs-newline? (and (some? existing)
                                     (not (str/blank? existing))
                                     (not (str/ends-with? existing "\n")))]
             (spit gitignore (str (when needs-newline? "\n") pattern "\n") :append true)
             pattern)))))))

;; -----------------------------------------------------------------------------
;; Directory Management
;; -----------------------------------------------------------------------------

(defn ensure-dirs!
  "Create costume directories if they don't exist.

   Creates:
   - `.shiftlefter/wardrobe/<name>/`
   - `.shiftlefter/wardrobe/<name>/chrome-profile/`

   Returns the costume directory path.

   Examples:
   ```clojure
   (ensure-dirs! :finance)
   ;; => \".shiftlefter/wardrobe/finance\"
   ```"
  [costume-name]
  (let [dir (costume-dir costume-name)
        chrome-dir (chrome-profile-dir costume-name)]
    (fs/create-dirs chrome-dir)
    dir))

(defn delete-costume!
  "Delete an entire costume directory.

   Removes the costume directory and all contents (Chrome profile, metadata).
   Returns true if deleted, false if directory didn't exist.

   Examples:
   ```clojure
   (delete-costume! :finance)
   ;; => true
   ```"
  [costume-name]
  (let [dir (costume-dir costume-name)]
    (if (fs/exists? dir)
      (do
        (fs/delete-tree dir)
        true)
      false)))

(defn costume-exists?
  "Check if a costume directory exists."
  [costume-name]
  (fs/exists? (costume-dir costume-name)))

;; -----------------------------------------------------------------------------
;; costume-meta Spec (boundary)
;; -----------------------------------------------------------------------------

(s/def ::debug-port int?)
(s/def ::chrome-pid int?)
(s/def ::user-data-dir string?)

;; The core, durable shape of a costume's connection metadata. `s/keys` ignores
;; extra keys, so legacy metas carrying a stray `:stealth` (removed in sl-gpk)
;; and the save-time `:created-at`/`:last-connected-at` timestamps still conform.
(s/def ::costume-meta
  (s/keys :req-un [::debug-port ::chrome-pid ::user-data-dir]))

;; -----------------------------------------------------------------------------
;; Metadata Management
;; -----------------------------------------------------------------------------

(defn- now-iso
  "Get current time as ISO-8601 string."
  []
  (str (Instant/now)))

(defn load-costume-meta
  "Load connection metadata for a costume.

   Returns the metadata map, or nil if the file doesn't exist, is corrupt, or
   does not conform to `::costume-meta`. Extra keys (e.g. a legacy `:stealth`) are
   tolerated — they conform and are returned as-is.

   Examples:
   ```clojure
   (load-costume-meta :finance)
   ;; => {:debug-port 9222 :chrome-pid 12345 ...}

   (load-costume-meta :nonexistent)
   ;; => nil
   ```"
  [costume-name]
  (let [path (costume-meta-path costume-name)]
    (when (fs/exists? path)
      (let [parsed (try
                     (edn/read-string (gio/slurp-utf8 path))
                     (catch Exception _
                       nil))]
        (when (s/valid? ::costume-meta parsed)
          parsed)))))

(defn save-costume-meta!
  "Save connection metadata for a costume.

   Validates `meta` against `::costume-meta` at the boundary, then writes
   atomically (temp file + rename) to prevent corruption. Ensures parent
   directories exist.

   Automatically adds/updates :last-connected-at timestamp.
   Preserves existing :created-at if file already exists.

   Throws a structured ex-info `{:type :costume/invalid-meta ...}` if `meta` does
   not conform.

   Examples:
   ```clojure
   (save-costume-meta! :finance {:debug-port 9222
                                 :chrome-pid 12345
                                 :user-data-dir \".shiftlefter/wardrobe/finance/chrome-profile\"})
   ```"
  [costume-name meta]
  (when-not (s/valid? ::costume-meta meta)
    (throw (ex-info (str "Invalid costume-meta for " (name costume-name))
                    {:type :costume/invalid-meta
                     :message (s/explain-str ::costume-meta meta)
                     :location `save-costume-meta!
                     :explain-data (s/explain-data ::costume-meta meta)})))
  (ensure-dirs! costume-name)
  (let [path (costume-meta-path costume-name)
        temp-path (str path ".tmp")
        now (now-iso)
        ;; Load existing to preserve created-at
        existing (when (fs/exists? path)
                   (try
                     (edn/read-string (gio/slurp-utf8 path))
                     (catch Exception _ nil)))
        existing-created-at (:created-at existing)
        meta-with-timestamps (-> meta
                                 (assoc :last-connected-at now)
                                 (assoc :created-at (or existing-created-at now)))]
    ;; Write to temp file
    (spit temp-path (pr-str meta-with-timestamps))
    ;; Atomic rename
    (fs/move temp-path path {:replace-existing true})
    meta-with-timestamps))

(defn clear-costume-meta!
  "Delete the metadata file for a costume.

   Returns true if deleted, false if didn't exist."
  [costume-name]
  (let [path (costume-meta-path costume-name)]
    (if (fs/exists? path)
      (do
        (fs/delete path)
        true)
      false)))

;; -----------------------------------------------------------------------------
;; Costume Listing
;; -----------------------------------------------------------------------------

(defn list-costumes
  "List all costume names that have directories in the wardrobe.

   Returns a vector of costume name strings.

   Examples:
   ```clojure
   (list-costumes)
   ;; => [\"finance\" \"work\" \"personal\"]
   ```"
  []
  (let [dir (wardrobe-dir)]
    (if (fs/exists? dir)
      (->> (fs/list-dir dir)
           (filter fs/directory?)
           (map #(str (fs/file-name %)))
           (sort)
           (vec))
      [])))
