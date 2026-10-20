(ns steps.plan-steps
  "sl-xvae fixture stepdefs: one untyped setup step and three typed :web
   steps (fill / click / see), modelled on the svo-demo example. Dry-run
   only — the bodies never run under the tests that use this project."
  (:require [shiftlefter.stepengine.registry :refer [defstep]]))

(defstep #"^the login page is loaded$"
  [ctx]
  (assoc ctx :page :login))

(defstep #"^(\w+) fills the (\w+) field with '([^']+)'$"
  {:interface :web
   :svo {:subject :$1
         :verb :fill
         :frame :with
         :object :$2
         :args {:value :$3}}}
  [ctx _subject field value]
  (assoc ctx :last-action {:fill field :value value}))

(defstep #"^(\w+) clicks the (.+)$"
  {:interface :web
   :svo {:subject :$1
         :verb :click
         :frame :default
         :object :$2}}
  [ctx _subject element]
  (assoc ctx :last-action {:click element}))

(defstep #"^(\w+) sees the (.+)$"
  {:interface :web
   :svo {:subject :$1
         :verb :see
         :frame :visible
         :object :$2}}
  [ctx _subject _element]
  ctx)
