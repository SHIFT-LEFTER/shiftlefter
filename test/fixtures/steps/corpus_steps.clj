(ns fixtures.steps.corpus-steps
  "Corpus vocabulary for the sl-vs9m runner-mechanics corpus.

   Deliberately FAKE step names (D5b): 'corpus ...' never shadows a real
   step, so a corpus scenario can never bind a real browser/SMS step, and
   grepping for real steps never hits corpus noise. Realistic-names-at-scale
   is the parked Sylius transplant's job (sl-vs9m D8).

   Outcome levers (see stepengine.exec.step-loop):
   - map/nil return        -> passed
   - :pending return       -> pending
   - throw                 -> failed with :exception-class  -> JUnit <error>
   - invalid return value  -> failed :step/invalid-return   -> JUnit <failure>"
  (:require [shiftlefter.stepengine.registry :refer [defstep]]))

(defstep #"corpus no-op"
  [ctx]
  ctx)

(defstep #"corpus sleeps (\d+) ms"
  [ctx ms]
  (Thread/sleep (Long/parseLong ms))
  ctx)

(defstep #"corpus fails deliberately"
  [_ctx]
  ;; Invalid return type is the one non-exception failure path the step
  ;; contract offers -- it renders as a JUnit <failure> (vs <error>).
  :corpus/deliberate-failure)

(defstep #"corpus errors deliberately"
  [_ctx]
  (throw (ex-info "corpus deliberate error" {:corpus/deliberate true})))

(defstep #"corpus is pending"
  [_ctx]
  :pending)

(defstep #"corpus marks (begin|end) of \"([^\"]+)\" in group \"([^\"]+)\" at \"(.+)\""
  [ctx which scenario group path]
  ;; One whole-line append per call: safe under today's serial execution and
  ;; atomic enough per-line under future parallel execution (sl-q9wp).
  (spit path (str which " " group " " scenario "\n") :append true)
  ctx)
