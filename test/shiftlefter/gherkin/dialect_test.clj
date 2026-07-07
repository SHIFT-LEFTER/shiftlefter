(ns shiftlefter.gherkin.dialect-test
  (:require [clojure.test :refer [deftest is testing]]
            [shiftlefter.gherkin.dialect :as dialect]))

(deftest step-text-trims-surrounding-whitespace
  (testing "aligned (multi-space) Gherkin yields the same step text as single-spaced"
    (let [en (dialect/get-dialect "en")
          single (dialect/match-step-keyword "And :user/x clicks {:id \"go\"}" en)
          aligned (dialect/match-step-keyword "And  :user/x clicks {:id \"go\"}" en)]
      (is (= :and (:keyword single)))
      (is (= ":user/x clicks {:id \"go\"}" (:text single))
          "single space: step text starts at the addressed subject")
      (is (= :and (:keyword aligned)))
      (is (= ":user/x clicks {:id \"go\"}" (:text aligned))
          "double space must not leave a leading space that breaks anchored matching"))))
