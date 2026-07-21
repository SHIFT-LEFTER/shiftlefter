(ns shiftlefter.stepengine.exec
  "Step execution engine for ShiftLefter runner — public facade.

   The engine's implementation lives in three sibling namespaces and is
   re-exported from here. Existing callers can keep doing
   `[shiftlefter.stepengine.exec :as exec]`; new code that wants to
   reach internal helpers should require the sibling ns directly.

   Implementation split (sl-uul):
   - `shiftlefter.stepengine.exec.provisioning` — auto-provisioning and
     the sl-aa5 scoped-eager phase
   - `shiftlefter.stepengine.exec.step-loop`    — per-step invocation,
     scenario loop, wrapper rollup. See `invoke-step` and
     `execute-scenario` for ctx shape, dispatch, and return semantics.
   - `shiftlefter.stepengine.exec.cleanup`      — ephemeral cleanup and
     suite-level orchestration

   ## Execution Semantics

   - Scenario: fail-fast — on failure/pending, skip remaining steps
   - Suite: continue — after scenario fails, proceed to next scenario
   - CLI runs are safe-by-default: ephemeral capabilities always close
     after their scenario, even on failure"
  (:require [clojure.spec.alpha :as s]
            [shiftlefter.stepengine.exec.cleanup :as cleanup]
            [shiftlefter.stepengine.exec.step-loop :as step-loop]))

;; -----------------------------------------------------------------------------
;; Specs — Step & Scenario Execution Results
;; -----------------------------------------------------------------------------

;; invoke-step result — keys are :status, :scenario, :error
;; :error (sl-esq) is scenario-level only: a lifecycle hook threw. Steps
;; never produce it — a hook failure is infrastructure, not a step outcome.
(s/def ::status #{:passed :pending :failed :skipped :error})
(s/def ::type keyword?)
(s/def ::message string?)
(s/def ::error (s/keys :req-un [::type ::message]))
(s/def ::scenario map?)

(s/def ::invoke-result
  (s/keys :req-un [::status ::scenario]
          :opt-un [::error]))

;; execute-scenario result — keys are :status, :plan, :steps, :scenario-ctx
(s/def ::steps (s/coll-of map?))
(s/def ::plan map?)
(s/def ::scenario-ctx map?)
;; Wall-clock duration in milliseconds (D5, sl-40to). Per-step on regular
;; steps; per-scenario on the suite-execution choke point; summed onto macro
;; wrappers. Absent on skipped/synthetic steps and REPL-path scenarios.
(s/def ::duration-ms (s/and number? (complement neg?)))

(s/def ::scenario-exec-result
  (s/keys :req-un [::status ::steps]
          :opt-un [::plan ::scenario-ctx ::duration-ms]))

;; execute-suite result — keys are :scenarios, :counts, :status
(s/def ::passed nat-int?)
(s/def ::failed nat-int?)
(s/def ::pending nat-int?)
(s/def ::skipped nat-int?)
;; :error count is ABSENT unless positive (sl-esq) — hook-less runs keep the
;; historical four-key counts map byte-identical in --edn output.
(s/def :shiftlefter.counts/error nat-int?)

(s/def ::counts
  (s/keys :req-un [::passed ::failed ::pending ::skipped]
          :opt-un [:shiftlefter.counts/error]))

(s/def ::scenarios (s/coll-of map?))

(s/def ::suite-result
  (s/keys :req-un [::scenarios ::status]))

;; -----------------------------------------------------------------------------
;; Public API — re-exports
;; -----------------------------------------------------------------------------

(def invoke-step      step-loop/invoke-step)
(def execute-scenario step-loop/execute-scenario)
(def execute-suite    cleanup/execute-suite)
