(ns shiftlefter.test-helpers
  "Test instrumentation helpers for spec validation during tests.
   Instruments key namespaces so spec violations are caught at test time.

   Also the loud self-skip for tests whose prerequisite is not in the public
   tree (sl-v6x3): see `with-prerequisite`."
  (:require [babashka.fs :as fs]
            [clojure.spec.test.alpha :as stest]
            [clojure.spec.alpha :as s]
            [clojure.test :as t]))

;; -----------------------------------------------------------------------------
;; Namespaces to instrument
;; -----------------------------------------------------------------------------

(def instrumented-namespaces
  "Namespaces with fdefs to instrument during tests."
  '[shiftlefter.adapters.registry
    shiftlefter.doctor
    shiftlefter.gherkin.api
    shiftlefter.gherkin.compliance
    shiftlefter.gherkin.dialect
    shiftlefter.gherkin.io
    shiftlefter.gherkin.lexer
    shiftlefter.gherkin.parser
    shiftlefter.gherkin.pickler
    shiftlefter.gherkin.printer
    shiftlefter.graph
    ;; sl-hlkz: first fdef in shiftlefter.config (lint-config)
    shiftlefter.config
    ;; sl-yvaz: load-setup's fdef (::config — the resolved map :start gets)
    shiftlefter.runner.setup
    ;; sl-hse note: shiftlefter.stepengine.registry has an s/fdef on
    ;; register! that validates :svo structure (capture-refs are :$N,
    ;; :frame is required, etc.). Activation is held back: ~37 existing
    ;; test fixtures use old-shape :svo with literal objects and no
    ;; :frame, and Tier 0/2 already cover the load- and compile-time
    ;; failure paths. Activate via a follow-on after migrating those
    ;; fixtures.
    ])

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
;; Loud self-skip for a missing prerequisite (sl-v6x3)
;;
;; A cold `git clone` of the public tree ships neither the Cucumber corpus
;; (a gitignored cucumber/gherkin clone) nor a built jar. The tests that need
;; one must never run red for a missing prerequisite — and never pass
;; silently either. Kaocha has a runtime pending event: reporting
;; `{:type :kaocha/pending}` from a test body prints a yellow P, counts as
;; "N pending" in the summary line and lists the test under PENDING. Kaocha
;; does not render the event's :message and its output capture swallows a
;; plain println, so the reason (naming the one fetch command) is printed
;; through clojure.test's *test-out*, which the capture plugin leaves alone.
;; -----------------------------------------------------------------------------

(defn skip-pending!
  "Record a VISIBLE kaocha skip: one `SKIP <reason>` line on the test
   output stream plus a :kaocha/pending event (counted and listed by kaocha;
   under plain clojure.test the event falls to the default reporter). Never a
   passing no-op — a skipped test shows up in the summary as pending. The
   event carries the skipping test's own file:line (kaocha would otherwise
   cite this helper's), so the PENDING line points at the test."
  [reason]
  (let [{:keys [file line]} (some-> t/*testing-vars* first meta)]
    (t/with-test-out
      (println (str "\nSKIP " reason))
      (flush))
    (t/do-report (cond-> {:type :kaocha/pending :message reason}
                   file (assoc :file file)
                   line (assoc :line line)))))

(def corpus-prerequisite
  "The official Cucumber compliance corpus, cloned by `bin/compliance` into
   compliance/gherkin (the compliance suite and the fuzz :corpus source both
   read compliance/gherkin/testdata). Not shipped in the public tree."
  {:present? #(fs/directory? "compliance/gherkin/testdata")
   :reason (str "Cucumber compliance corpus absent at compliance/gherkin/testdata "
                "— fetch it with bin/compliance")})

(def jar-prerequisite
  "A built jar next to bin/sl. Mirrors bin/sl's flat discovery: the first
   regular file matching bin/shiftlefter*.jar (the dev tree links
   bin/shiftlefter.jar; a release zip ships shiftlefter-vX.Y.Z.jar)."
  {:present? #(some fs/regular-file? (fs/glob "bin" "shiftlefter*.jar"))
   :reason (str "no built jar next to bin/sl (bin/shiftlefter*.jar) "
                "— build it with clojure -T:build uberjar")})

(defn with-prerequisite
  "Run `body-thunk` when the prerequisite is present; otherwise record a
   visible pending skip naming the fetch (`skip-pending!`)."
  [{:keys [present? reason]} body-thunk]
  (if (present?)
    (body-thunk)
    (skip-pending! reason)))

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
