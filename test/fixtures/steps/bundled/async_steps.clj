(ns fixtures.steps.bundled.async-steps
  "Step definitions exercising core.async library."
  (:require [shiftlefter.stepengine.registry :refer [defstep]]
            [clojure.core.async :as async]))

(defstep #"I put a value on a core.async channel"
  [ctx]
  (let [ch (async/chan 1)
        val {:message "hello from core.async"}]
    (async/>!! ch val)
    (assoc ctx :async-channel ch :async-sent val)))

(defstep #"I take the value from the channel"
  [ctx]
  (let [ch (:async-channel ctx)
        received (async/<!! ch)]
    (async/close! ch)
    (assoc ctx :async-received received)))

(defstep #"the channel round-trip should preserve the value"
  [ctx]
  (let [sent (:async-sent ctx)
        received (:async-received ctx)]
    (when (not= sent received)
      (throw (ex-info "core.async round-trip failed"
                      {:sent sent :received received})))
    ctx))
