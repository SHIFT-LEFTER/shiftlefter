(ns steps.cucumbers
  "Step definitions for the cucumber basket example.

   Two TYPED steps and one deliberately UNTYPED one:

   - A typed step carries :svo metadata — the subject capture (:$1) is
     checked against sl/glossary/subjects.edn at planning time, so a typo
     like `:usr` is caught before anything runs. These steps declare no
     :interface (a basket is not a browser), so their verbs are not
     checked against any verb glossary — subject checked, verb unchecked.
   - The last step has no :svo at all. It still binds and runs, but the
     planner reports it as `:stepdef/untyped` (a warning by default):
     nothing about it went through the type system. That warning is the
     lesson; a real step library types every step."
  (:require [shiftlefter.stepengine.registry :refer [defstep]]))

;; The subject capture sits AFTER the colon (`:(\S+)`), like the builtins —
;; the capture "user" becomes the subject :user.
(defstep #"^:(\S+) has (\d+) cucumbers$"
  {:svo {:subject :$1 :verb :have :frame :count}}
  [ctx _subject n]
  (assoc ctx :cucumbers (parse-long n)))

(defstep #"^:(\S+) eats (\d+) cucumbers$"
  {:svo {:subject :$1 :verb :eat :frame :count}}
  [ctx _subject n]
  (update ctx :cucumbers - (parse-long n)))

;; Untyped on purpose — see the namespace docstring.
(defstep #"I should have (-?\d+) cucumbers"
  [ctx n]
  (let [expected (parse-long n)
        actual (:cucumbers ctx)]
    (when-not (= expected actual)
      (throw (ex-info (str "Expected " expected " cucumbers but had " actual)
                      {:expected expected :actual actual})))
    ctx))
