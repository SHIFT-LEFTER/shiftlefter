(ns fixtures.bad-stepdefs.bad-require-steps
  "Step definition that intentionally fails — requires a nonexistent library.
   Used to test that error messages are actionable."
  (:require [shiftlefter.stepengine.registry :refer [defstep]]
            [some.nonexistent.lib :as nope]))

(defstep #"this step should never register"
  [ctx]
  (nope/do-thing ctx))
