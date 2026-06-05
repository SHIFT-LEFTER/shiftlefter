(ns shiftlefter.graph
  "Graph database lifecycle for ShiftLefter.

  Thin wrapper around Asami providing DB init, connect, reset, and
  basic transact/query conveniences. The database persists to
  `.shiftlefter/graph/` (gitignored) by default.

  NOT wired into CLI or `sl compile` — this is plumbing only."
  (:require [asami.core :as d]
            [babashka.fs :as fs]
            [clojure.spec.alpha :as s]))

;; ---------------------------------------------------------------------------
;; Specs
;; ---------------------------------------------------------------------------

(s/def ::db-path string?)
(s/def ::uri string?)
(s/def ::connection some?)

;; ---------------------------------------------------------------------------
;; URI + path helpers
;; ---------------------------------------------------------------------------

(def ^{:doc "Default database directory, relative to project root."}
  default-db-path ".shiftlefter/graph")

(defn db-uri
  "Returns an Asami local-storage URI for the given path (or default)."
  ([]    (db-uri default-db-path))
  ([path] (str "asami:local://" path)))

(s/fdef db-uri
  :args (s/alt :nullary (s/cat)
               :unary   (s/cat :path ::db-path))
  :ret  ::uri)

;; ---------------------------------------------------------------------------
;; Lifecycle
;; ---------------------------------------------------------------------------

(defn init-db!
  "Ensure the DB directory exists and return an Asami connection.
  Creates the database if it doesn't already exist.

  Options:
    :path — override default-db-path (useful for tests)"
  ([]     (init-db! {}))
  ([opts]
   (let [path (get opts :path default-db-path)
         uri  (db-uri path)]
     (fs/create-dirs path)
     (d/create-database uri)
     (d/connect uri))))

(s/fdef init-db!
  :args (s/alt :nullary (s/cat)
               :unary   (s/cat :opts (s/keys :opt-un [::db-path])))
  :ret  ::connection)

(defn reset-db!
  "Delete the database and its backing directory, then re-initialize.
  Returns a fresh connection.

  Options:
    :path — override default-db-path (useful for tests)"
  ([]     (reset-db! {}))
  ([opts]
   (let [path (get opts :path default-db-path)
         uri  (db-uri path)]
     (d/delete-database uri)
     (when (fs/exists? path)
       (fs/delete-tree path))
     (init-db! opts))))

(s/fdef reset-db!
  :args (s/alt :nullary (s/cat)
               :unary   (s/cat :opts (s/keys :opt-un [::db-path])))
  :ret  ::connection)

;; ---------------------------------------------------------------------------
;; Convenience wrappers (thin — callers can use asami.core directly)
;; ---------------------------------------------------------------------------

(defn transact!
  "Transact entity maps into the database. Returns the deref'd tx result.

  `data` is a vector of entity maps, e.g.:
    [{:sl/kind :subject :sl/name :alice :subject/type :user}
     {:sl/kind :verb    :sl/name :click :verb/interface :web}]"
  [conn data]
  @(d/transact conn {:tx-data data}))

(s/fdef transact!
  :args (s/cat :conn ::connection :data (s/coll-of map?))
  :ret  map?)

(defn query
  "Run a Datalog query against the current DB snapshot.

  Example:
    (query conn '[:find ?name :where [?e :sl/kind :subject] [?e :sl/name ?name]])"
  [conn q & args]
  (apply d/q q (d/db conn) args))

(s/fdef query
  :args (s/cat :conn ::connection :q vector? :args (s/* any?))
  :ret  (s/coll-of any?))
