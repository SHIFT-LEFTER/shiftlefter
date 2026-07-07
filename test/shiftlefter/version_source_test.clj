(ns shiftlefter.version-source-test
  (:require
   [clojure.edn :as edn]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(deftest codox-version-comes-from-build-task
  (testing "deps.edn does not carry a second hardcoded Codox release version"
    (let [deps (edn/read-string (slurp "deps.edn"))]
      (is (nil? (get-in deps [:aliases :codox :exec-args :version])))
      (is (= 'codox.main/generate-docs
             (get-in deps [:aliases :codox :exec-fn])))))

  (testing "bin/docs routes documentation generation through build.clj"
    (let [script (slurp "bin/docs")]
      (is (str/includes? script "clj -T:build docs"))
      (is (not (str/includes? script "clj -X:codox"))))))
