(ns fixtures.steps.bundled.gentest-steps
  "Step definitions exercising test.check generators."
  (:require [shiftlefter.stepengine.registry :refer [defstep]]
            [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]))

(defstep #"I generate values from a spec"
  [ctx]
  (let [generated (gen/sample (s/gen (s/and int? pos?)) 5)]
    (assoc ctx :generated-values generated)))

(defstep #"the generated values should be valid"
  [ctx]
  (let [values (:generated-values ctx)]
    (when-not (seq values)
      (throw (ex-info "No values generated" {})))
    (when-not (every? pos-int? values)
      (throw (ex-info "Generated values don't match spec"
                      {:values values})))
    ctx))
