(ns shiftlefter.subjects.profile
  "Subject profile directory and metadata management.

   Subjects are named browser profiles stored at `~/.shiftlefter/subjects/<name>/`.
   Each subject has:
   - A Chrome user-data-dir at `chrome-profile/`
   - A metadata file `browser-meta.edn` tracking connection state

   ## Directory Structure

   ```
   ~/.shiftlefter/subjects/
   └── finance/
       ├── browser-meta.edn
       └── chrome-profile/
           └── (Chrome user data)
   ```

   ## browser-meta.edn Shape

   ```clojure
   {:debug-port 9222
    :user-data-dir \"/Users/x/.shiftlefter/subjects/finance/chrome-profile\"
    :chrome-pid 12345
    :stealth true
    :created-at \"2026-01-08T12:00:00Z\"
    :last-connected-at \"2026-01-08T12:00:00Z\"}
   ```"
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn])
  (:import [java.time Instant]))

;; -----------------------------------------------------------------------------
;; Path Helpers
;; -----------------------------------------------------------------------------

(defn- home-dir
  "Get user's home directory."
  []
  (System/getProperty "user.home"))

(defn- shiftlefter-dir
  "Get ShiftLefter data directory: ~/.shiftlefter"
  []
  (str (home-dir) "/.shiftlefter"))

(defn- subjects-dir
  "Get subjects root directory: ~/.shiftlefter/subjects"
  []
  (str (shiftlefter-dir) "/subjects"))

(defn profile-dir
  "Get profile directory for a named subject.

   Subject names can be keywords or strings.

   Examples:
   ```clojure
   (profile-dir :finance)
   ;; => \"/Users/x/.shiftlefter/subjects/finance\"

   (profile-dir \"work\")
   ;; => \"/Users/x/.shiftlefter/subjects/work\"
   ```"
  [subject-name]
  (str (subjects-dir) "/" (name subject-name)))

(defn chrome-profile-dir
  "Get Chrome user-data-dir for a named subject.

   Examples:
   ```clojure
   (chrome-profile-dir :finance)
   ;; => \"/Users/x/.shiftlefter/subjects/finance/chrome-profile\"
   ```"
  [subject-name]
  (str (profile-dir subject-name) "/chrome-profile"))

(defn- browser-meta-path
  "Get path to browser-meta.edn for a subject."
  [subject-name]
  (str (profile-dir subject-name) "/browser-meta.edn"))

;; -----------------------------------------------------------------------------
;; Directory Management
;; -----------------------------------------------------------------------------

(defn ensure-dirs!
  "Create subject profile directories if they don't exist.

   Creates:
   - `~/.shiftlefter/subjects/<name>/`
   - `~/.shiftlefter/subjects/<name>/chrome-profile/`

   Returns the profile directory path.

   Examples:
   ```clojure
   (ensure-dirs! :finance)
   ;; => \"/Users/x/.shiftlefter/subjects/finance\"
   ```"
  [subject-name]
  (let [profile (profile-dir subject-name)
        chrome-dir (chrome-profile-dir subject-name)]
    (fs/create-dirs chrome-dir)
    profile))

(defn delete-profile!
  "Delete an entire subject profile directory.

   Removes the subject directory and all contents (Chrome profile, metadata).
   Returns true if deleted, false if directory didn't exist.

   Examples:
   ```clojure
   (delete-profile! :finance)
   ;; => true
   ```"
  [subject-name]
  (let [profile (profile-dir subject-name)]
    (if (fs/exists? profile)
      (do
        (fs/delete-tree profile)
        true)
      false)))

(defn profile-exists?
  "Check if a subject profile directory exists."
  [subject-name]
  (fs/exists? (profile-dir subject-name)))

;; -----------------------------------------------------------------------------
;; Metadata Management
;; -----------------------------------------------------------------------------

(defn- now-iso
  "Get current time as ISO-8601 string."
  []
  (str (Instant/now)))

(defn load-browser-meta
  "Load browser metadata for a subject.

   Returns the metadata map, or nil if file doesn't exist.

   Examples:
   ```clojure
   (load-browser-meta :finance)
   ;; => {:debug-port 9222 :chrome-pid 12345 ...}

   (load-browser-meta :nonexistent)
   ;; => nil
   ```"
  [subject-name]
  (let [path (browser-meta-path subject-name)]
    (when (fs/exists? path)
      (edn/read-string (slurp path)))))

(defn save-browser-meta!
  "Save browser metadata for a subject.

   Uses atomic write (temp file + rename) to prevent corruption.
   Ensures parent directories exist.

   Automatically adds/updates :last-connected-at timestamp.
   Preserves existing :created-at if file already exists.

   Examples:
   ```clojure
   (save-browser-meta! :finance {:debug-port 9222
                                  :chrome-pid 12345
                                  :stealth true})
   ```"
  [subject-name meta]
  (ensure-dirs! subject-name)
  (let [path (browser-meta-path subject-name)
        temp-path (str path ".tmp")
        now (now-iso)
        ;; Load existing to preserve created-at
        existing (when (fs/exists? path)
                   (edn/read-string (slurp path)))
        existing-created-at (:created-at existing)
        meta-with-timestamps (-> meta
                                 (assoc :last-connected-at now)
                                 (assoc :created-at (or existing-created-at now)))]
    ;; Write to temp file
    (spit temp-path (pr-str meta-with-timestamps))
    ;; Atomic rename
    (fs/move temp-path path {:replace-existing true})
    meta-with-timestamps))

(defn clear-browser-meta!
  "Delete browser metadata file for a subject.

   Returns true if deleted, false if didn't exist."
  [subject-name]
  (let [path (browser-meta-path subject-name)]
    (if (fs/exists? path)
      (do
        (fs/delete path)
        true)
      false)))

;; -----------------------------------------------------------------------------
;; Subject Listing
;; -----------------------------------------------------------------------------

(defn list-subjects
  "List all subject names that have profile directories.

   Returns a vector of subject name strings.

   Examples:
   ```clojure
   (list-subjects)
   ;; => [\"finance\" \"work\" \"personal\"]
   ```"
  []
  (let [dir (subjects-dir)]
    (if (fs/exists? dir)
      (->> (fs/list-dir dir)
           (filter fs/directory?)
           (map #(str (fs/file-name %)))
           (sort)
           (vec))
      [])))
