(ns shiftlefter.version
  "Runtime version lookup for ShiftLefter."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]))

(defn- version-resource []
  (some-> (io/resource "shiftlefter/version.edn")
          slurp
          edn/read-string
          :version))

(defn- read-form [reader]
  (read {:eof ::eof :read-cond :allow} reader))

(defn- build-version []
  (when (.exists (io/file "build.clj"))
    (with-open [reader (java.io.PushbackReader. (io/reader "build.clj"))]
      (binding [*read-eval* false]
        (loop [form (read-form reader)]
          (cond
            (= ::eof form) nil
            (and (seq? form)
                 (= 'def (first form))
                 (= 'version (second form))) (nth form 2 nil)
            :else (recur (read-form reader))))))))

(defn version
  "Return the runtime ShiftLefter version."
  []
  (or (version-resource)
      (build-version)
      "unknown"))
