(ns fixtures.steps.bundled.json-steps
  "Step definitions exercising Cheshire JSON library."
  (:require [shiftlefter.stepengine.registry :refer [defstep]]
            [cheshire.core :as json]))

(defstep #"I encode a map to JSON"
  [ctx]
  (let [data {:name "ShiftLefter" :version "0.3.6" :tags ["gherkin" "testing"]}
        encoded (json/generate-string data)]
    (assoc ctx :json-encoded encoded :original-data data)))

(defstep #"I decode the JSON back"
  [ctx]
  (let [decoded (json/parse-string (:json-encoded ctx) true)]
    (assoc ctx :json-decoded decoded)))

(defstep #"the JSON round-trip should preserve data"
  [ctx]
  (let [original (:original-data ctx)
        decoded (:json-decoded ctx)]
    (when (not= original decoded)
      (throw (ex-info "JSON round-trip failed"
                      {:original original :decoded decoded})))
    ctx))
