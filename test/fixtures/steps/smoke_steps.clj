(ns fixtures.steps.smoke-steps
  "Step definitions for macro integration testing."
  (:require [shiftlefter.stepengine.registry :refer [defstep]]))

;; Auth macro steps (from auth.ini)
(defstep #"I visit the login page"
  []
  nil)

(defstep #"I enter username \"([^\"]+)\""
  [username]
  {:username username})

(defstep #"I enter password \"([^\"]+)\""
  [password ctx]
  (assoc (:scenario ctx) :password password))

(defstep #"I click the login button"
  []
  nil)

(defstep #"I click the logout button"
  []
  nil)

(defstep #"I should see the login page"
  []
  nil)

(defstep #"I should see the dashboard"
  []
  nil)

(defstep #"I should see the welcome message"
  []
  nil)

;; Literal plus test steps (for macros-off test)
(defstep #"login as alice \+"
  []
  {:user "alice+"})

(defstep #"I check the username"
  []
  nil)

(defstep #"the username should be \"([^\"]+)\""
  [expected]
  nil)

;; Recursion test steps
(defstep #"I do something"
  []
  nil)

(defstep #"I finish"
  []
  nil)
