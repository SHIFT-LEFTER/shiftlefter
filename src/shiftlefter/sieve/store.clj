(ns shiftlefter.sieve.store
  "Persistence helpers for SIEVE draft state and promoted fixtures."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [shiftlefter.sieve.contract :as contract])
  (:import [java.io PushbackReader]))

(def default-root ".shiftlefter/sieve")

(defn root-path
  "Return the SIEVE storage root for opts or the project default."
  [opts]
  (fs/path (or (:root opts) default-root)))

(defn- ensure-parent! [path]
  (fs/create-dirs (fs/parent path)))

(defn- write-edn! [path value]
  (ensure-parent! path)
  (spit (str path) (str (pr-str value) "\n"))
  value)

(defn read-edn-file
  "Read an EDN artifact from path."
  [path]
  (with-open [reader (PushbackReader. (io/reader (str path)))]
    (edn/read reader)))

(defn artifact-path
  "Return the artifact path for a store kind and ID."
  [root kind id]
  (fs/path root (name kind) (str id ".edn")))

(defn save-evidence-snapshot!
  "Persist an Evidence Snapshot and return it."
  [root snapshot]
  (let [snapshot (contract/with-evidence-identity snapshot)]
    (write-edn! (artifact-path root :evidence-snapshots (:evidence/id snapshot))
                snapshot)))

(defn load-evidence-snapshot
  "Load an Evidence Snapshot from a store root and ID."
  [root id]
  (contract/with-evidence-identity
    (read-edn-file (artifact-path root :evidence-snapshots id))))

(defn save-analysis-result!
  "Persist an Analysis Result and return it."
  [root analysis]
  (let [analysis (contract/with-analysis-identity analysis)]
    (write-edn! (artifact-path root :analysis-results (:analysis/id analysis))
                analysis)))

(defn load-analysis-result
  "Load an Analysis Result from a store root and ID."
  [root id]
  (contract/with-analysis-identity
    (read-edn-file (artifact-path root :analysis-results id))))

(defn save-interpretation!
  "Persist revisable interpretation state and return it."
  [root interpretation]
  (write-edn! (artifact-path root :interpretations (:interpretation/id interpretation))
              interpretation))

(defn load-interpretation
  "Load revisable interpretation state from a store root and ID."
  [root id]
  (read-edn-file (artifact-path root :interpretations id)))

(defn save-proposal-result!
  "Persist a Proposal Result and return it."
  [root proposal]
  (write-edn! (artifact-path root :proposal-results (:proposal/id proposal))
              proposal))

(defn load-proposal-result
  "Load a Proposal Result from a store root and ID."
  [root id]
  (read-edn-file (artifact-path root :proposal-results id)))

(defn make-session
  "Build a session index with separable cross-references to SIEVE artifacts."
  [{:keys [id evidence-snapshots analysis-results interpretations proposal-results
           created-at updated-at]}]
  {:schema/version 1
   :session/id (or id (contract/new-id "sieve-session"))
   :created-at (or created-at (contract/now-iso))
   :updated-at (or updated-at (contract/now-iso))
   :evidence-snapshots (vec evidence-snapshots)
   :analysis-results (vec analysis-results)
   :interpretations (vec interpretations)
   :proposal-results (vec proposal-results)})

(defn save-session!
  "Persist a session index and return it."
  [root session]
  (write-edn! (artifact-path root :sessions (:session/id session))
              (assoc session :updated-at (contract/now-iso))))

(defn load-session
  "Load a session index from a store root and ID."
  [root id]
  (read-edn-file (artifact-path root :sessions id)))

(defn load-fixture
  "Load a promoted fixture artifact from an explicit path."
  [path]
  (read-edn-file path))
