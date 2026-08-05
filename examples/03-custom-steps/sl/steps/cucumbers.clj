(ns steps.cucumbers
  "Step definitions for the cucumber basket example."
  (:require [shiftlefter.stepengine.registry :refer [defstep]]))

(defstep #"I have (\d+) cucumbers"
  [ctx n]
  (assoc ctx :cucumbers (parse-long n)))

(defstep #"I eat (\d+) cucumbers"
  [ctx n]
  (update ctx :cucumbers - (parse-long n)))

(defstep #"I should have (-?\d+) cucumbers"
  [ctx n]
  (let [expected (parse-long n)
        actual (:cucumbers ctx)]
    (when-not (= expected actual)
      (throw (ex-info (str "Expected " expected " cucumbers but had " actual)
                       {:expected expected :actual actual})))
    ctx))
