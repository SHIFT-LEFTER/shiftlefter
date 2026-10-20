(ns fixtures.steps.polling-steps
  "Polling fixtures (sl-tcsq): an oracle step whose predicate answers on a
   scripted attempt, so the goldens can pin the `(N polls)` rendering on a
   passing oracle — and prove a single-answer poll renders byte-identically
   to a step that never polled."
  (:require [shiftlefter.step :as step]
            [shiftlefter.stepengine.registry :refer [defstep]]))

(defstep #"a counter that answers on attempt (\d+)"
  [ctx n-str]
  (assoc ctx :answers-on (Long/parseLong n-str)))

(defstep #"I wait for the counter to answer"
  [ctx]
  (let [attempts (atom 0)
        answers-on (:answers-on ctx)
        answer (step/poll-until ctx
                                (fn [] (let [n (swap! attempts inc)]
                                         (when (>= n answers-on) n)))
                                {:deadline-ms 5000 :interval-ms 1})]
    (assoc ctx :answer answer)))

(defstep #"the counter answered on attempt (\d+)"
  [ctx n-str]
  (let [expected (Long/parseLong n-str)
        actual (:answer ctx)]
    (when (not= expected actual)
      (throw (ex-info "Counter answered on the wrong attempt"
                      {:expected expected :actual actual})))
    ctx))
