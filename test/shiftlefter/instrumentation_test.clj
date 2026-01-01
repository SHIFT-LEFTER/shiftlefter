(ns shiftlefter.instrumentation-test
  "Tests that verify spec instrumentation is active during test runs."
  (:require [clojure.test :refer [deftest is testing]]
            [shiftlefter.gherkin.parser :as parser]))

(deftest instrumentation-active
  (testing "Spec instrumentation should catch violations"
    ;; If instrumented, calling parse with wrong arg type throws ExceptionInfo
    ;; If NOT instrumented, it would throw a different error (or behave unexpectedly)
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"did not conform to spec"
                          (parser/parse "not-a-token-seq"))
        "parser/parse should be instrumented and reject non-seq input")))

(deftest instrumentation-allows-valid-calls
  (testing "Valid calls should pass instrumentation"
    (let [result (parser/parse [])]
      (is (map? result))
      (is (contains? result :ast))
      (is (contains? result :errors)))))
