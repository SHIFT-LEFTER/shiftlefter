(ns fixtures.steps.basic-steps
  "Sample step definitions for testing the step loader.

   Note: Step functions receive ctx as first argument.
   ctx is flat scenario state (not nested {:step :scenario}).
   Access accumulated state directly: (:cart-count ctx)"
  (:require [shiftlefter.stepengine.registry :refer [defstep]]))

(defstep #"I have (\d+) items in my cart"
  [ctx count-str]
  (assoc ctx :cart-count (Integer/parseInt count-str)))

(defstep #"I add (\d+) more items"
  [ctx count-str]
  (update ctx :cart-count + (Integer/parseInt count-str)))

(defstep #"I should have (\d+) items total"
  [ctx expected-str]
  (let [expected (Integer/parseInt expected-str)
        actual (:cart-count ctx)]
    (when (not= expected actual)
      (throw (ex-info "Cart count mismatch" {:expected expected :actual actual})))
    ctx))

(defstep #"I trigger an error"
  [_ctx]
  (throw (ex-info "Intentional test error" {:reason :test})))

(defstep #"I return pending"
  [_ctx]
  :pending)
