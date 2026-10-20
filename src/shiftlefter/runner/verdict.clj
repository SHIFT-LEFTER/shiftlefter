(ns shiftlefter.runner.verdict
  "The verdict contract (sl-7c7i): the single canonical exit-code table.
   Every surface that speaks exit codes derives from THIS ns — the daemon
   ::exit wire spec, EDN summary :run/status, the reporter ::run-summary
   status spec — and the doc surfaces (README § Return Codes, docs/CI.md,
   docs/architecture/exit-codes.md, docs/architecture/invocation-map.md, the lifecycle
   pages' prose, and this docstring's own table) are held in agreement by
   exit-contract-guard-test (eight surfaces since sl-r54o).

   THE LADDER (sl-kowi, Chair-ratified 08-30): 0 and 1 are VERDICTS ABOUT
   THE APP. 2, 3 and 4 are three ways the run failed to be a measurement,
   located by whose side gave way — 2 = your request (pre-flight, nothing
   ran); 3 = SL's own code (crash parity, anywhere); 4 = the observation
   machinery between spec and app, mid-run. Exit 4 has two disjoint
   buckets under one code: the harness's own :error family (fix the
   harness) and steps classified :observation by their adapter — the
   witness went dark (check the environment). Neither is an acquittal:
   the build is red and the run must be rerun; only the INDICTMENT of the
   product is withheld, because the evidence never reached it.

   | Code | Status            | Meaning                                      |
   |------|-------------------|----------------------------------------------|
   | 0    | :passed           | Existential pass: >=1 scenario selected, all |
   |      |                   | ran to a tolerated outcome, none failed or   |
   |      |                   | errored. Tolerated = pending under           |
   |      |                   | :allow-pending? (counts confess).            |
   | 1    | :failed           | >=1 scenario :failed (valid tests, app       |
   |      |                   | wrong — the app answered and the answer      |
   |      |                   | missed, incl. :sut-classed errors), or       |
   |      |                   | pending under strict mode, or a stale        |
   |      |                   | @blocked claim (XPASS, sl-190x: the test's   |
   |      |                   | claim is what is wrong; a failing corpus).   |
   | 2    | :planning-failed  | Invalid before execution: config/parse/      |
   |      |                   | discovery/binding errors, un-runnable        |
   |      |                   | invocation, empty selection. Pre-execution   |
   |      |                   | exclusive — nothing ran.                     |
   | 3    | :crashed          | Runner crash. Anywhere, wins over all.       |
   | 4    | :degraded         | Run degraded: >=1 scenario :error — the      |
   |      |                   | harness family (hook throw, capability       |
   |      |                   | provisioning, capture; counted :error)       |
   |      |                   | OR a step whose adapter classified           |
   |      |                   | its failure :observation — no answer existed |
   |      |                   | (counted :unobserved). Verdict about the app |
   |      |                   | unavailable for those scenarios.             |

   Precedence: 3 anywhere; 2 pre-execution exclusive; execution
   worst-wins 4 > 1 > 0 — 4's imperative (fix observation, rerun; this
   run is not the measurement you asked for) subsumes 1's, and reporting
   1 on a degraded run would claim a completeness the run lacks. Suite
   aggregation across setup groups: 2 > 4 > 1 > 0 (a crash throws past
   aggregation to execute!'s handler).

   The 0/1/2/3 contract was locked at 0.5.1; Chair reopened it
   STRENGTHEN-ONLY for the 0.5.5 window (sl-7c7i) — it re-locks at first
   users. Dry-run's exit 0 is a PLAN-verdict (the selection binds), not a
   run-verdict, and requires a nonempty selection.")

(def exit-code->status
  "Exit code -> :run/status keyword. The authoritative mapping."
  {0 :passed
   1 :failed
   2 :planning-failed
   3 :crashed
   4 :degraded})

(def exit-codes
  "The set of legal `sl` process exit codes (and the daemon wire ::exit)."
  (set (keys exit-code->status)))

(def run-statuses
  "The set of legal run-level :status values projected from exit codes.
   (:dry-run is separate — dry-run summaries carry their own status and
   never flow through the run-summary seam.)"
  (set (vals exit-code->status)))
