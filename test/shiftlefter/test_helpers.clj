(ns shiftlefter.test-helpers
  "Test instrumentation helpers for spec validation during tests.
   Instruments key namespaces so spec violations are caught at test time."
  (:require [clojure.spec.test.alpha :as stest]
            [clojure.spec.alpha :as s]))

;; -----------------------------------------------------------------------------
;; Namespaces to instrument
;; -----------------------------------------------------------------------------

(def instrumented-namespaces
  "Namespaces with fdefs to instrument during tests."
  '[shiftlefter.gherkin.parser
    shiftlefter.gherkin.lexer
    shiftlefter.gherkin.pickler])

;; -----------------------------------------------------------------------------
;; Instrumentation API
;; -----------------------------------------------------------------------------

(defn instrument-all
  "Instrument all fdefs in key namespaces. Call before tests.
   Returns the list of instrumented function symbols."
  []
  (s/check-asserts true)
  (doseq [ns-sym instrumented-namespaces]
    (require ns-sym))
  (let [syms (stest/enumerate-namespace instrumented-namespaces)]
    (stest/instrument syms)))

(defn unstrument-all
  "Remove instrumentation. Call after tests."
  []
  (s/check-asserts false)
  (stest/unstrument))

(defn with-instrumentation
  "Fixture for use with clojure.test use-fixtures :once.
   Example: (use-fixtures :once test-helpers/with-instrumentation)"
  [f]
  (instrument-all)
  (try
    (f)
    (finally
      (unstrument-all))))

;; -----------------------------------------------------------------------------
;; Kaocha hook entry points
;; -----------------------------------------------------------------------------

(defn pre-run
  "Kaocha pre-run hook - instruments before test suite."
  [test-plan]
  (let [instrumented (instrument-all)]
    (binding [*out* *err*]
      (println "[spec] Instrumented:" (count instrumented) "functions")
      (flush)))
  test-plan)

(defn post-run
  "Kaocha post-run hook - unstruments after test suite."
  [result]
  (unstrument-all)
  (binding [*out* *err*]
    (println "[spec] Unstrumented.")
    (flush))
  result)
