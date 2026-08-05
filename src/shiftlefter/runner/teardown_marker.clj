(ns shiftlefter.runner.teardown-marker
  "Cross-run dirty-teardown marker (sl-yvaz).

   When a setup group's :stop throws, the environment it managed may be left
   dirty — and the person who suffers is whoever runs the suite NEXT, staring
   at a mystery failure. The marker closes that gap: each :stop throw appends
   an entry to `.shiftlefter/dirty-teardown.edn` (project-scoped, under the
   instance root like the wardrobe); the next run announces the entries at
   startup and clears the file. Dry-run announces but never clears — a
   preview must not consume the warning.

   Clearing discipline (Chair-ratified at the sl-yvaz plan approval,
   correcting one clause of the sl-mmfy locked design): startup
   announce-then-clear is the ONLY clear; failures only ever APPEND. A clean
   :stop deliberately never touches the file — group A's failure must
   survive group B's healthy teardown in the same run.

   Every file operation FAILS OPEN: a diagnostics mechanism must never fail
   the run it is diagnosing. Malformed marker content reads as no entries
   (and is then overwritten by the next append/clear)."
  (:require [clojure.edn :as edn]
            [clojure.spec.alpha :as s]
            [clojure.tools.logging :as log]
            [babashka.fs :as fs]
            [shiftlefter.project-context :as project-context]))

(s/def ::group string?)
(s/def ::message (s/nilable string?))
(s/def ::run-id string?)
(s/def ::at string?)
(s/def ::entry (s/keys :req-un [::group ::message ::run-id ::at]))
(s/def ::entries (s/coll-of ::entry :kind vector?))

(defn marker-path
  "Absolute path of the marker file under the instance root."
  [context]
  (project-context/operational-path context ".shiftlefter" "dirty-teardown.edn"))

(defn read-entries
  "Vector of marker entries, or nil when absent/unreadable/malformed.
   Never throws."
  [context]
  (try
    (let [path (marker-path context)]
      (when (fs/exists? path)
        (let [data (edn/read-string (slurp path))]
          (when (s/valid? ::entries data)
            data))))
    (catch Exception e
      (log/warnf e "dirty-teardown marker unreadable (ignored): %s"
                 (ex-message e))
      nil)))

(defn append!
  "Append one entry to the marker file, creating it (and .shiftlefter/) as
   needed. Swallows I/O errors with a log warning — marker writing must
   never fail a run."
  [context entry]
  (try
    (let [path (marker-path context)
          entries (conj (or (read-entries context) []) entry)]
      (fs/create-dirs (fs/parent path))
      (spit path (pr-str entries)))
    (catch Exception e
      (log/warnf e "could not write dirty-teardown marker (ignored): %s"
                 (ex-message e)))))

(defn clear!
  "Delete the marker file. Swallows I/O errors. Never throws."
  [context]
  (try
    (fs/delete-if-exists (marker-path context))
    (catch Exception e
      (log/warnf e "could not clear dirty-teardown marker (ignored): %s"
                 (ex-message e)))))
