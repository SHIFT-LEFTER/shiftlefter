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
top level pure; side effects belong inside `:start`. One caveat: the preview
binds against the default adapter registry, so a project whose adapters
exist only in a custom registry returned by `:start` can bind differently in
preview than in a real run.

## The execution weave

```text
group (declared order, one at a time)
  :start                      ← the world starts here, not earlier
  parse + compile             ← against the adapter registry :start returned
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
- `:start` throws or returns a malformed shape → group-local planning
  failure; the group is skipped, other groups still run. Half-started state
  is user-owned — the framework can't know what half-started.
- Before hook throws or returns a non-map → scenario `:error` (never
  `:failed`), steps skipped, only succeeded frames unwind.
- Capability provisioning fails → scenario errors; Afters still unwind,
  WITHOUT capabilities.
- Step fails → scenario `:failed`; `:on-failure` capture fires at that
  instant, before Afters.
- After hook throws or its attachments claim is malformed → scenario
  `:error` even when every step passed. One failing After doesn't stop the
  unwind.
- Capture infrastructure fails on an otherwise-green scenario → scenario
  `:error`, capture-attributed; an already-red scenario keeps its status and
  the ref records the miss.
- `:stop` throws → reported (console + envelope), exit code unchanged; a
  dirty-teardown marker is written, announced at the next run's start, then
  cleared.
- Exit codes aggregate worst-wins across groups.

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

Related topics: `hooks` (registration, payloads, attachments claim),
`layout` (which files attach user code, what the framework writes).
