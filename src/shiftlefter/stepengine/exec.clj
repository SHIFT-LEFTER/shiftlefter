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
            [shiftlefter.attachments :as attachments]
            [shiftlefter.step :as step]
            [shiftlefter.stepengine.exec.cleanup :as cleanup]
            [shiftlefter.stepengine.exec.provisioning :as provisioning]
            [shiftlefter.stepengine.exec.step-loop :as step-loop]))

;; -----------------------------------------------------------------------------
;; Specs — Step & Scenario Execution Results
;; -----------------------------------------------------------------------------

;; invoke-step result — keys are :status, :scenario, :error
;; :error (sl-esq, family widened sl-7c7i) is scenario-level only — the
;; infrastructure-failure family: a lifecycle hook threw, capability
;; provisioning failed, capture infrastructure failed, or the harness
;; crashed. Steps never produce it — infrastructure failure is not a step
;; outcome (a provisioning-failed step stays :failed; finalize flips the
;; scenario).
(s/def ::status #{:passed :pending :failed :skipped :error})
(s/def ::type keyword?)
(s/def ::message string?)
;; :error/class (sl-kowi, F15) — the step/adapter error-and-observation
;; contract: ONE recognized ex-data key on any exception a step or adapter
;; throws, pre-classifying the failure at the site that did the I/O.
;;   :assertion   — the witness answered; the answer missed the expectation
;;   :sut         — the witness answered WITH an error (500, failed job);
;;                  still product evidence
;;   :observation — no answer existed (refused, transport timeout, DNS);
;;                  proves nothing about the app, cause-agnostic by design
;; Absent (or unrecognized) = today's behavior. The class rides the step's
;; :error map; steps still never carry the :error STATUS — finalize flips an
;; :observation-classed scenario to :error and counts it :unobserved.
;; :assertion/:sut are record-and-render only (scenario stays :failed).
;; The value set is `shiftlefter.step/error-classes` — the one literal the
;; kernel (Rider C inheritance, sl-afs9), this spec and the lift share.
(s/def :error/class step/error-classes)
(s/def ::error (s/keys :req-un [::type ::message]
                       :opt [:error/class]))
(s/def ::scenario map?)

;; :poll-counts (sl-tcsq) — the step's summed poll digest
;; ({:attempts :answered :threw :observation-throws}); present only when
;; the step fn ran at least one `poll-until`, pass or timeout.
(s/def ::poll-counts ::step/poll-counts)

(s/def ::invoke-result
  (s/keys :req-un [::status ::scenario]
          :opt-un [::error ::poll-counts]))

;; execute-scenario result — keys are :status, :plan, :steps, :scenario-ctx
(s/def ::steps (s/coll-of map?))
(s/def ::plan map?)
(s/def ::scenario-ctx map?)
;; Wall-clock duration in milliseconds (D5, sl-40to). Per-step on regular
;; steps; per-scenario on the suite-execution choke point; summed onto macro
;; wrappers. Absent on skipped/synthetic steps and REPL-path scenarios.
(s/def ::duration-ms (s/and number? (complement neg?)))

;; Finalized attachment refs (sl-l9nj) — scenario-level; the per-step
;; vector arrives with the capture policy (sl-3q9r).
(s/def ::attachments ::attachments/attachments)

;; The hold record (sl-617t abort, sl-190x @blocked): `{:reason :aborted
;; :after-step k}` on a scenario the abort kept from a verdict, or
;; `{:reason :blocked :tag "@blocked=REF" :ref "REF" :outcome {:status
;; :failed|:unobserved :at-step k :message ..}}` on one that failed as its
;; tag claims. Open map: the runner adds :ref-url. Absent on every
;; scenario that ran to an unexcused verdict.
(s/def :shiftlefter.held/reason #{:aborted :blocked})
(s/def ::held (s/keys :req-un [:shiftlefter.held/reason]))
;; The XPASS record (sl-190x): a @blocked scenario that passed — the
;; scenario is :failed and this carries the tag, the ref and the imperative.
(s/def :shiftlefter.xpass/tag string?)
(s/def ::xpass (s/keys :req-un [:shiftlefter.xpass/tag ::message]))

(s/def ::scenario-exec-result
  (s/keys :req-un [::status ::steps]
          :opt-un [::plan ::scenario-ctx ::duration-ms ::attachments
                   ::held ::xpass]))

;; execute-suite result — keys are :scenarios, :counts, :status
(s/def ::passed nat-int?)
(s/def ::failed nat-int?)
(s/def ::pending nat-int?)
(s/def ::skipped nat-int?)
;; :error count is ABSENT unless positive (sl-esq) — error-free runs keep the
;; historical four-key counts map byte-identical in --edn output. Counts the
;; infrastructure-failure family (hook/provisioning/capture, sl-7c7i).
(s/def :shiftlefter.counts/error nat-int?)
;; :unobserved (sl-kowi): :error scenarios whose attribution class is
;; :observation — the witness between spec and app went dark. Disjoint from
;; :error (which keeps the SL-machinery family only); same absent-unless-
;; positive rule. Two rendered buckets under one exit code (4).
(s/def :shiftlefter.counts/unobserved nat-int?)
;; :held (sl-617t, sl-190x): scenarios carrying a :held record, carved out
;; of every verdict bucket; :xpass (sl-190x): @blocked scenarios that
;; passed (also counted :failed). Both absent-unless-positive.
(s/def :shiftlefter.counts/held nat-int?)
(s/def :shiftlefter.counts/xpass nat-int?)

(s/def ::counts
  (s/keys :req-un [::passed ::failed ::pending ::skipped]
          :opt-un [:shiftlefter.counts/error :shiftlefter.counts/unobserved
                   :shiftlefter.counts/held :shiftlefter.counts/xpass]))

(s/def ::scenarios (s/coll-of map?))

(s/def ::suite-result
  (s/keys :req-un [::scenarios ::status]))

;; -----------------------------------------------------------------------------
;; Public API — re-exports
;; -----------------------------------------------------------------------------

(def invoke-step      step-loop/invoke-step)
(def execute-scenario step-loop/execute-scenario)
(def execute-suite    cleanup/execute-suite)
;; The lazy-provisioning primitive, exposed for bare callers with no plan
;; to scan — the REPL step path (sl-vmc5). Runner paths keep reaching it
;; through step_loop/the eager phase, unchanged.
(def ensure-capability-for-svo provisioning/ensure-capability-for-svo)
