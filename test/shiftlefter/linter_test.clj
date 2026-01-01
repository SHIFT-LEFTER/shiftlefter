(ns shiftlefter.linter-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.shell :refer [sh]]))

(deftest lint-no-errors
  ;; Run clj-kondo on src and test, assert no errors (warnings ok).
  (let [result (sh "clj" "-M:kondo" "--fail-level" "error" "--lint" "src" "test")]
    (is (= 0 (:exit result)) (str "Lint failed: " (:err result)))))