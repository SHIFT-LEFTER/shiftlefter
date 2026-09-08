# The Execution Lifecycle

Every run is two phases: PLAN, then EXECUTE. `sl run --dry-run` is phase one
exposed — the same plan every run computes, stopped before anything executes.

## The plan phase

Guarantees (structural truth): every structural name resolves; every step is
a known frame; every consumed `{binding}` has a producer; every matcher
compiles; each scenario's hook firing list is resolved from its `@hook=`
tags (and printed under `--dry-run`); plan-shape lints run (a `setup.clj`
group matching no features = error, duplicate group labels = error, the same
feature in two groups = warning). Tag filtering happens at planning time —
deselected scenarios are never bound. Planning does not promise any
assertion passes.

The plan phase never invokes `:start`, `:stop`, or hooks, and never
captures. `setup.clj` IS `load-file`'d so the plan is knowable — keep its
top level pure; side effects belong inside `:start`. Custom adapters stay
visible to this phase via the group's `:adapters` declaration (pure data,
the delta vs the default registry): the preview binds against the declared
shapes, and the registry `:start` returns is verified to match them
(mismatch = planning error `:setup/registry-mismatch`; an undeclared
returned registry is a deprecated shape that warns).

## The execution weave

```text
group (declared order, one at a time)
  :start                      ← the world starts here, not earlier
                                (its registry verified against :adapters)
  parse + compile             ← against the group's adapter registry
                                (declared data, live factories)
  scenarios (parallel-capable)
    Before hooks              ← no capabilities yet
    capability provisioning   ← browsers launch here
    steps
    After hooks               ← capabilities still live
    capability cleanup
  :stop                       ← in a finally — even when scenarios failed
```

Without `setup.clj` there is one implicit suite, no `:start`/`:stop`, same
scenario weave. Befores run before provisioning (they see no capabilities; a
broken seed hook fails before browser-launch cost). Afters run after steps,
before cleanup (capabilities still live), unwinding in strict LIFO order of
the Befores that ran. Every hook that runs is stamped in the scenario
envelope.

## Failure semantics by stage

- Planning failure (unresolved name, unknown frame, consumed binding without
  a producer, unknown `@hook=`, broken plan shape) → exit 2, nothing executes.
- Empty selection (a tag filter matching 0 of N, feature files with no
  scenarios, CLI paths narrowing every group away) → exit 2, checked before
  any `:start` runs; the record carries counts and what was searched. A dry
  run's exit 0 requires a nonempty selection too.
- `:start` throws or returns a malformed shape → group-local planning
  failure; the group is skipped, other groups still run. Half-started state
  is user-owned — the framework can't know what half-started.
- Before hook throws or returns a non-map → scenario `:error` (never
  `:failed`), steps skipped, only succeeded frames unwind.
- Capability provisioning fails → scenario `:error` — same family as a hook
  throw (broken harness ≠ broken app); Afters still unwind, WITHOUT
  capabilities.
- Step fails → scenario `:failed`; `:on-failure` capture fires at that
  instant, before Afters.
- Step throws with `:error/class :observation` (the witness went dark — no
  answer existed: refused, transport timeout, DNS) → scenario `:error`,
  counted `unobserved` (disjoint from the harness's `error` bucket), exit
  4; the step stays `:failed` with the class on its error. The other two
  classes (`:sut` = the app answered WITH an error, `:assertion` = the app
  answered and missed) are recorded and rendered only — scenario `:failed`,
  exit 1. Absent key = unchanged behavior. Builtin SMS/browser adapters
  ship classified; tag your own adapter's throw at its catch site — the
  class is read when that throw reaches the STEP; hook, provisioning and
  capture throws are the harness bucket (`error`), never `unobserved`.
- `{:runner {:halt-on-observation-failure true}}` (default off) → after an
  observation-class scenario, no further scenario STARTS; in-flight ones
  (`--max-parallel`) finish and keep their verdicts, everything unstarted
  (incl. the `@serial` tail) is `skipped`; the summary says `halted after
  scenario N (<name>): observation failure` and the EDN summary carries
  `:halted {:after-scenario N :scenario "<name>"}`. Exit 4 either way.
  The key is `false` | `:group` | `:run` (`true` = `:run`): with `setup.clj`
  groups, `:run` skips every group not yet started (its `:start` never
  runs; the invocation aggregate carries the `:halted` record and the
  skipped groups' rows say `:run/status :skipped`), `:group` halts only the
  group that went dark.
- After hook throws or its attachments claim is malformed → scenario
  `:error` even when every step passed. One failing After doesn't stop the
  unwind.
- Capture infrastructure fails on an otherwise-green scenario → scenario
  `:error`, capture-attributed; an already-red scenario keeps its status and
  the ref records the miss.
- `:stop` throws → reported (console + envelope), exit code unchanged: the
  exit code is this run's verdict, already earned by the time teardown runs;
  environment damage is the NEXT run's planning input — which is what the
  dirty-teardown marker is (written now, announced at the next run's start,
  then cleared).
- Any scenario `:error` → exit 4 (run degraded: the harness broke or the
  observation channel went dark — the run proves nothing about the app for
  that scenario), distinct from exit 1 (a scenario failed). Exit 4 is not
  an acquittal: red, blocking; only the indictment of the app is withheld.
- Exit codes aggregate worst-wins across groups: 2 > 4 > 1 > 0. 0/1 are
  verdicts about the app; 2/3/4 are three ways the run failed to be a
  measurement (your request / SL's code / the observation channel).

## Parallelism

Groups are sequential (declared order). `--max-parallel N` is
scenario-grain within one process (default 1; results identical to a
sequential run — only wall-clock changes). Exclusive scenarios run one at a
time after the parallel batch: `@serial`-tagged, scenarios carrying a
`:requires-serial` hook (reports show the derived `@serial` with the hook
named), and costume-wearing or shared-impl-interface scenarios (notice line
says so). There is no built-in CI-level suite sharding.

## Capture timing

`:on-failure` captures at the failing step's instant — before Afters —
across every live capability. `:every-step` captures once per step,
following the acting subject. Console capture is a destructive drain: per
step under `:every-step` (each file holds exactly the entries since the
last drain, plus a final tail sweep), once post-steps under `:on-failure`,
before any After runs. Capture cost is recorded on the ref
(`:attachment/duration-ms`) and never pollutes step durations. Dry runs
never capture.

## The run seed

Every invocation gets ONE seed (positive 64-bit long, decimal): `--seed N`
beats config `:runner {:seed N}` beats generated. Recorded always —
`:run/seed` in every group's `--edn` summary, `:seed` in run-ctx, the HTML
header, one stderr line (`run seed: N`). One deliberate exception: the
daemon's busy-reject summary (exit 2, nothing ran) carries no `:run/seed`
— nothing was seeded. The framework draws no randomness
from it; it is handed to user code: hook payloads carry `:seed`, step
`ctx` carries `:seed`, and `:start` receives a `:seed` key on its config
argument. Need several independent streams? Derive:
`(bit-xor seed (hash scenario-id))` — the documented convention, not
framework code.

## Consuming the run record

The run record = the `--edn` stdout form stream (redirect it — no summary
file is written) plus the run-directory bytes. The summary's top-level
`:attachments` index is the authoritative evidence walk (green-run evidence
appears only there); `:failures` entries carry their own refs inline for
join-free triage — same refs, deliberate duplication. `:run/seed` on every
summary is the run's seed — rerun with `--seed <that value>` to reproduce
user-code randomness. In a multi-group run each group's summary carries
`:group/label` + `:group/index` (join by index, not stream order — the
index is the group's DECLARED position, so under CLI narrowing the numbers
keep their declared values and may have gaps), and one
final `:invocation/exit-code` form carries the suite verdict — read it if
present, else the lone summary's `:run/exit-code`. Building a custom
rendering from the record: `docs/REPORTS.md` (contract + runnable recipe).

Related topics: `hooks` (registration, payloads, attachments claim),
`layout` (which files attach user code, what the framework writes).
