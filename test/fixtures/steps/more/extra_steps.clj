(ns fixtures.steps.more.extra-steps
  "Additional step definitions for testing recursive loading."
  (:require [shiftlefter.stepengine.registry :refer [defstep]]))

(defstep #"I am logged in as \"([^\"]+)\""
  [ctx username]
  (assoc ctx :user username :logged-in true))

(defstep #"I log out"
  [ctx]
  (dissoc ctx :user :logged-in))
