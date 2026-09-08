# Exit Codes

The `sl run` exit code is a claim about what the run proved. This is the
decision procedure; the reasoning lives in `docs/architecture/exit-codes.md`.

## The table

| Code | Status | Meaning |
|---|---|---|
| 0 | `:passed` | Existential pass: ≥1 scenario selected, every selected scenario ran to a tolerated outcome, none failed or errored (pending tolerated only under `:allow-pending?` — the counts still confess). |
| 1 | `:failed` | ≥1 scenario `:failed` — the app answered and the answer missed the expectation (including `:sut`-classed errors: the app answered *with* an error). Or pending under strict mode. |
| 2 | `:planning-failed` | Invalid before execution: config/parse/discovery/binding errors, an un-runnable invocation, an empty selection. Pre-execution exclusive — nothing ran. |
| 3 | `:crashed` | Runner crash — an uncaught throwable in ShiftLefter's own code. Anywhere; wins over everything. |
| 4 | `:degraded` | ≥1 scenario `:error` — either the harness's own machinery broke (hook throw, capability provisioning, capture, harness; counted `error`) or a step's adapter classified its failure `:observation` — no answer existed (counted `unobserved`). The verdict about the app is unavailable for those scenarios. |

Precedence: 3 anywhere; 2 pre-execution exclusive per `setup.clj` group;
during execution worst wins, 4 > 1 > 0; across groups the suite
aggregates worst-wins: 2 > 4 > 1 > 0. A suite-level 2 means at least one
group's request was never runnable — earlier groups' evidence is real and
in the report; gate on the exit code, not the report. Under `--edn` a
multi-group run also says this in the stream: each group's summary carries
`:group/label` + `:group/index`, and one final aggregate form carries
`:invocation/exit-code` — the suite verdict, readable without the process
exit (absent in single-group streams, where the lone summary's
`:run/exit-code` is the verdict; absent on a crash).

## What to do on each exit

- **0** — the app met every expectation checked. Done.
- **1** — the app answered and at least one answer was wrong. Debug from
  the report's failure evidence.
- **2** — nothing ran for that group. Fix the invocation, the config, or
  the spec's symbols. Absence of exit 2 is not evidence the spec is
  valid — a fresh element-not-found at runtime (exit 1) may be glossary
  rot or app regression; triage, don't assume.
- **3** — ShiftLefter itself crashed. Trust nothing in the run; file a
  framework bug.
- **4** — ≥1 scenario got no answer. Do not touch product code; check the
  environment, then rerun. Exit 4 withholds the indictment of the product
  because the evidence never reached it.

## Classifying a failure (the razor)

Did the witness channel — the channel the SUT is observed through —
answer?

- Answered, wrong → `:error/class :assertion` → scenario `:failed`, exit 1.
- Answered *with* an error (500, stack-trace page, job state `failed`) →
  `:sut` → `:failed`, exit 1.
- Never answered (refused, transport timeout, DNS) → `:observation` →
  `:error`, counted `unobserved`, exit 4. Classifying silence as
  `:observation` is a refusal to convert silence into evidence.

Give-backs: a late answer that arrives is `:assertion`; a poll that timed
out after successful answers is `:assertion` — `:last-value` present
means the witness was answering, `:last-error` only means it never
answered. An expected event that never occurs through a healthy witness
is `:assertion`; an expected response that never comes is `:observation`.
The one misclassification that lies: marking a delivered answer
`:observation`. Unclassified errors keep default behavior; declining to
classify is always safe.

## Exit-4 triage

The build is red, nonzero, blocking either way. The report names which
scenarios went unobserved — read it before rerunning. The same scenario
unobserved run after run while its siblings pass: suspect the SUT's
responsiveness, not the network.

`{:runner {:halt-on-observation-failure true}}` (run-level config,
default off): after a scenario finalizes observation-class, no further
scenario starts; in-flight scenarios finish and keep their verdicts, the
rest are `skipped`; the exit code is 4 either way. The key is a tri-state
`false` | `:group` | `:run` (`true` reads as `:run`): with `setup.clj`
groups, `:run` skips every group not yet started — its `:start` never
runs, its aggregate row says `:run/status :skipped` — while `:group`
halts only the group that went dark and lets the siblings run.

Related topics: `lifecycle` (failure semantics by stage, the execution
weave), `builtins` (the pre-classified built-in step families).
