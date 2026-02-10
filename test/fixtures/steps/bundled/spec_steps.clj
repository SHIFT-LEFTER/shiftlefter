(ns fixtures.steps.bundled.spec-steps
  "Step definitions exercising clojure.spec.alpha library."
  (:require [shiftlefter.stepengine.registry :refer [defstep]]
            [clojure.spec.alpha :as s]))

;; Define test specs
(s/def ::name string?)
(s/def ::age (s/and int? pos?))
(s/def ::person (s/keys :req-un [::name ::age]))

(defstep #"I validate data against a spec"
  [ctx]
  (let [valid-data {:name "Alice" :age 30}
        invalid-data {:name "Bob" :age -5}]
    (assoc ctx
           :spec-valid (s/valid? ::person valid-data)
           :spec-invalid (not (s/valid? ::person invalid-data))
           :spec-explain (s/explain-str ::person invalid-data))))

(defstep #"the spec validation should work correctly"
  [ctx]
  (when-not (:spec-valid ctx)
    (throw (ex-info "Valid data failed spec" {})))
  (when-not (:spec-invalid ctx)
    (throw (ex-info "Invalid data passed spec" {})))
  (when-not (string? (:spec-explain ctx))
    (throw (ex-info "Explain did not return string" {})))
  ctx)
