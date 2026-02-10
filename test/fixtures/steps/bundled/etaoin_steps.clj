(ns fixtures.steps.bundled.etaoin-steps
  "Step definitions exercising etaoin namespace availability.
   Cannot test actual browser in CI — just verify the namespace loads."
  (:require [shiftlefter.stepengine.registry :refer [defstep]]))

(defstep #"I can require the etaoin namespace"
  [ctx]
  ;; Dynamic require — proves etaoin is on the classpath
  (require 'etaoin.api)
  (let [etaoin-ns (find-ns 'etaoin.api)
        public-vars (count (ns-publics etaoin-ns))]
    (assoc ctx :etaoin-loaded true :etaoin-var-count public-vars)))

(defstep #"etaoin should be available on the classpath"
  [ctx]
  (when-not (:etaoin-loaded ctx)
    (throw (ex-info "etaoin.api failed to load" {})))
  (when-not (pos? (:etaoin-var-count ctx))
    (throw (ex-info "etaoin.api has no public vars" {})))
  ctx)
