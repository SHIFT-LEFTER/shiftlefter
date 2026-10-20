(ns shiftlefter.edn-lines
  "Line positions for the entries of an EDN file — the def-site index
   (sl-u2r0, judgment call J-a).

   The DATA a glossary or intents file carries is read by `clojure.edn`
   exactly as before; this namespace is a second, guarded pass over the
   same text that answers one question the EDN reader cannot: on which
   line does each entry start? The answer feeds `:def-site` on every
   did-you-mean, so a suggestion names where the suggested thing is
   declared (`Declared in: sl/glossary/subjects.edn:4`).

   ## Why the full reader, and how it is kept honest

   tools.reader's EDN reader attaches no positional metadata to
   collections (verified: only ^meta forms and reader errors carry
   :line). The full Clojure reader (`clojure.tools.reader/read`) does —
   every nested map, vector, list and set of an indexing reader gets
   :line/:column. So the index pass uses the full reader with eval OFF
   and every tagged literal read as data (`tagged-literal`, so any tag the
   EDN reader accepted is accepted here, unevaluated). It is guarded three
   ways: any exception degrades to no lines; a value that does not equal
   the EDN-read value (metadata stripped) degrades to no lines, so the
   index can never disagree with the data; and callers treat a missing
   line as file-only precision. A def-site is never an error.

   Ruled (Chair, 2026-09-16, sl-u2r0 fold-in C1 amended): this mechanism —
   clojure.edn stays the ONE data reader; the index pass uses the full
   reader with eval off and every tag read as data; the structural guard is
   what C1 was for (the index is used only when it agrees with the data);
   a text scan was rejected. Both guard branches and the happy path are
   pinned in svo/glossary_test."
  (:require [clojure.tools.reader :as reader]
            [clojure.tools.reader.reader-types :as reader-types]))

(defn- strip-meta
  "The value with every collection's metadata removed, for the equality
   guard against the EDN-read value."
  [v]
  (cond
    (map? v) (into {} (map (fn [[k x]] [(strip-meta k) (strip-meta x)])) v)
    (vector? v) (mapv strip-meta v)
    (set? v) (into #{} (map strip-meta) v)
    (seq? v) (map strip-meta v)
    :else v))

(defn index
  "Read `content` (the file's text) with the position-tracking reader and
   return the value with :line/:column metadata on every collection, or
   nil when the pass cannot vouch for itself: a read failure, or a value
   that differs from `edn-value` (the `clojure.edn` read of the same
   text). Pure; never throws."
  [content edn-value]
  (try
    (let [rdr (reader-types/indexing-push-back-reader content)
          v (binding [reader/*read-eval* false
                      reader/*default-data-reader-fn* tagged-literal]
              (reader/read {:eof ::eof} rdr))]
      (when (and (not= ::eof v)
                 (= (strip-meta v) edn-value))
        v))
    (catch Throwable _ nil)))

(defn line-of
  "The start line of the collection at `path` inside an indexed value, or
   nil (no index, path absent, or a scalar — scalars carry no position)."
  [indexed path]
  (some-> (get-in indexed path) meta :line))

(defn entry-lines
  "For the map at `path`: `{key line}` for every entry whose VALUE is a
   collection (its own start line). Scalar-valued entries get the map's
   line — the closest honest position. nil map → {}."
  [indexed path]
  (let [m (get-in indexed path)]
    (if (map? m)
      (let [fallback (:line (meta m))]
        (into {} (map (fn [[k v]] [k (or (:line (meta v)) fallback)])) m))
      {})))
