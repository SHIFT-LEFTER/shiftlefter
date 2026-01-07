(ns fixtures.steps.basic-steps
  "Sample step definitions for testing the step loader.

   Note: Step functions receive ctx as {:step ... :scenario ...}.
   Access scenario context via (:scenario ctx)."
  (:require [shiftlefter.stepengine.registry :refer [defstep]]))

(defstep #"I have (\d+) items in my cart"
  [count-str]
  {:cart-count (Integer/parseInt count-str)})

(defstep #"I add (\d+) more items"
  [count-str ctx]
  (update (:scenario ctx) :cart-count + (Integer/parseInt count-str)))

(defstep #"I should have (\d+) items total"
  [expected-str ctx]
  (let [expected (Integer/parseInt expected-str)
        actual (:cart-count (:scenario ctx))]
    (when (not= expected actual)
      (throw (ex-info "Cart count mismatch" {:expected expected :actual actual})))
    (:scenario ctx)))

(defstep #"I trigger an error"
  [_ctx]
  (throw (ex-info "Intentional test error" {:reason :test})))

(defstep #"I return pending"
  [_ctx]
  :pending)
