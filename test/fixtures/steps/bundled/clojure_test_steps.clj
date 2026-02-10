(ns fixtures.steps.bundled.clojure-test-steps
  "Step definitions that use clojure.test assertions.
   Proves clojure.test works from within stepdefs."
  (:require [shiftlefter.stepengine.registry :refer [defstep]]
            [clojure.test :refer [is]]))

(defstep #"I use clojure.test assertions"
  [ctx]
  ;; These assertions should pass silently
  (is (= 1 1))
  (is (string? "hello"))
  (is (pos? 42))
  (assoc ctx :clojure-test-works true))
