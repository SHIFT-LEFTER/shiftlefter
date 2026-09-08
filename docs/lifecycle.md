# The Execution Lifecycle

Every `sl run` is two phases: **plan, then execute**. This page tells the
story in order — what the plan phase guarantees, how execution composes once
it starts, what each kind of failure does to the run, and where parallelism
applies. The mechanisms each have a home page ([hooks](hooks.md),
[project layout](project-layout.md), [CI](CI.md)); this page is about how
they compose.

## Phase one: the plan

Before anything executes — before a browser launches, before a `:start`
function runs, before a single hook fires — the whole run is planned.
Planning is a compilation, not a lint: every step resolves to a typed
subject–verb–object against your vocabulary, every scenario's hook firing
list is resolved from its `@hook=` tags, every consumed `{binding}` is
checked for a producer, every matcher compiles, and the plan's own shape is
linted (a `setup.clj` group matching no feature files, or two groups sharing
a label, fails the plan before anything runs). Tag filtering happens here
too: deselected scenarios are never even bound.

`sl run --dry-run` is this phase, exposed. What it promises is
**structural truth**: names resolve, frames match, data flows connect. It
does not promise any assertion passes — the run supplies the rest.

The plan phase touches nothing: no `:start`, no `:stop`, no hooks, no
capture. The one deliberate exception: `setup.clj` is `load-file`'d so the
plan is knowable, which is why its top level must stay pure — side effects
belong inside `:start`. Custom adapters stay visible to this phase because
their data half is *declared* on the group (`:adapters`) rather than locked
inside `:start` — the preview binds against the declarations, and `:start`'s
live registry is verified to match them. That rule and the declaration
contract live in [Project layout](project-layout.md#user-code--the-wall).

If you're coming from Cucumber, calibrate: Cucumber has a `--dry-run` flag
too, and it answers exactly one question — does every step match some
definition? Hooks don't run, but they don't get reported either; nothing
binds to anything typed; nobody checks whether the value a step consumes is
ever produced. It's a spell-checker: genuinely useful, and a faint shadow of
a plan. A ShiftLefter run plans first *every* time — `--dry-run` isn't a
special validation mode, it's the same first phase, stopped before the world
gets touched. And the plan is a compilation: each step resolves to a typed
subject–verb–object against your vocabulary, each scenario's hook firing
list is resolved and printed, each consumed `{binding}` is checked for a
producer, and the plan's own shape is linted before anything moves. When the
plan passes, what's left to discover at execution time is what your *system*
does — not what your *suite* meant.

## Phase two: the weave

When the plan passes, execution composes in a fixed order. With a
`setup.clj`, groups run in declared order, each an isolated sub-suite;
without one, there is a single implicit suite with no `:start`/`:stop`, and
the scenario weave is identical.

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

Three placements are deliberate. Befores run *before* capability
provisioning, so a broken seed hook fails before paying browser-launch cost
— which also means Befores see no capabilities. Afters run after steps but
*before* capability cleanup, so an After can still reach the live browser;
they unwind in strict LIFO order of the Befores that ran. And `:stop` runs
in a `finally` — a group whose scenarios failed still gets its teardown.

Nothing in the weave fires invisibly: every hook that runs is stamped in the
scenario envelope, and the dry run prints each scenario's resolved firing
list before anything executes.

## What each failure does

The weave gives every failure a place, and the place decides the semantics:

| When it fails | What happens |
|---|---|
| A name doesn't resolve, a step matches no frame, a consumed `{binding}` has no producer | Planning error (exit 2). Nothing executes — no browser, no `:start`, no hooks. |
| The selection is empty — a tag filter matching 0 of N, feature files with no scenarios, CLI paths narrowing every group away | Planning error (exit 2), the vacuity witness: a run that selects nothing cannot claim green. Checked *before* any `:start` runs (a pure selection preflight under `setup.clj`); the record carries counts and what was searched. |
| `@hook=` names an unknown hook | Planning error, reporting the tag's file and line. |
| The plan shape is broken — a group matching no features, two groups sharing a label | Planning error. (The same feature in two groups is a warning: a deliberate matrix is legitimate.) |
| `:start` throws, or returns a malformed shape | That group is reported as a planning failure and skipped — **other groups still run**. Whatever `:start` half-started is yours to clean up; the framework can't know what half-started. |
| A Before hook throws, or returns a non-map | Scenario `:error` (never `:failed`), steps skipped; only the frames whose Befores succeeded unwind. |
| Capability provisioning fails | Scenario `:error` — same family as a hook throw (broken harness, never a broken app); Afters still unwind — without capabilities, so an After checks for the capability it wants. |
| A step fails | Scenario `:failed`; an `:on-failure` capture fires at that instant, before Afters. |
| A step throws with `:error/class :observation` (the witness went dark — no answer existed) | Scenario `:error`, counted `unobserved` (distinct from the harness's `error` bucket); the step stays `:failed` and its error carries the class. Under `{:runner {:halt-on-observation-failure true}}` no further scenario starts — in-flight ones finish and keep their verdicts, the rest are `skipped`; the key is `false` \| `:group` \| `:run` (`true` = `:run`): with `setup.clj` groups, `:run` also skips every group not yet started (its `:start` never runs), `:group` halts only the group that went dark. `:sut`/`:assertion` classes are recorded and rendered; the scenario stays `:failed`. |
| An After hook throws, or its attachments claim is malformed | Scenario `:error` even when every step passed — broken cleanup is a lying suite. One failing After doesn't stop the unwind. |
| Capture infrastructure fails on an otherwise-green scenario | Scenario `:error`, capture-attributed. An already-red scenario keeps its status; the ref records the miss visibly. |
| `:stop` throws | Reported — console and envelope — but the exit code doesn't change: the exit code is this run's verdict, already earned by the time teardown runs; environment damage is the *next* run's planning input. That is what the dirty-teardown marker is — written now, announced at the next run's start, then cleared. |

Any scenario `:error` makes the run exit **4** (run degraded — the harness
broke, or the observation channel went dark, so the run proves nothing
about the app for that scenario), distinct from exit 1 (a scenario failed:
valid tests, app wrong). Across groups, exit codes aggregate worst-wins:
2 > 4 > 1 > 0 — the ladder and the error-class contract are in
[the exit-codes page](architecture/exit-codes.md). The full mechanics behind
each row: [hook payloads and failure semantics](AGENT.md#scenario-lifecycle-hooks)
(`sl agent-doc hooks`), [capture policy](hooks.md#failure-diagnostics--now-a-config-line),
and [what the framework writes](project-layout.md#what-the-framework-writes).

## Where it parallelizes

Groups are sequential by design — declared order, one at a time. Parallelism
is scenario-grain: `sl run --max-parallel N` runs up to N scenarios
concurrently within one `sl` process (config mirror
`[:runner :max-parallel]`; default 1 = sequential). Results and console
output are identical to a sequential run — only wall-clock changes.

Three things opt out of the batch: scenarios tagged `@serial` run one at a
time after it; a hook declaring `:requires-serial` auto-serializes the
scenarios that carry it (the report shows the derived `@serial` with the
hook named as the reason); and costume-wearing or shared-interface scenarios
auto-serialize with a notice line. CI-level fan-out (GitLab `parallel:`,
Actions `matrix:`) is a different axis — ShiftLefter has no built-in suite
sharding; [Running in CI](CI.md) covers the trade-off.

## Capture rides the weave

Timing is what the capture policy buys, so it belongs on this page:
`:on-failure` fires at the failing step's instant — before Afters, before
anything moves — capturing every live capability, because Bob's screen may
be exactly what explains Alice's failure. `:every-step` is the film strip,
one capture per step following the acting subject. Console capture is a
destructive drain: under `:every-step` each step's file holds exactly the
entries logged since the last drain, with a final tail sweep so nothing is
lost; under `:on-failure` the console drains once, post-steps, before any
After runs. Capture cost lands on the ref (`:attachment/duration-ms`), never
on step durations. Dry runs never capture — nothing executes. The full
policy story — kinds, adapter support, the evidence contract — is
[Hooks § Failure diagnostics](hooks.md#failure-diagnostics--now-a-config-line).

## Fixtures

ShiftLefter deliberately has no fixture format yet. What exists today are
the two lifecycle rungs this page just walked: a standing system — server,
queue, mocked service — belongs to a `setup.clj` group (the fixture HTTP
server in
[`examples/04-sms-2fa`](https://github.com/SHIFT-LEFTER/shiftlefter/tree/main/examples/04-sms-2fa)
is the canonical worked example), and per-scenario data belongs to a named
hook that declares what it `:provides`. The missing rung is declarative —
state the data a scenario needs and let the framework establish it, instead
of writing the code that does — and that's the planned contracts arc (0.6),
which is why today's seeding hooks are written to graduate: the seam where
the declarative form lands is already cut. Until then, the honest answer is
that fixtures are lifecycle code, and the evidence records them as exactly
that.

## The run seed

Every invocation gets exactly ONE seed: a positive 64-bit integer, printed
in decimal everywhere it appears. You can set it — `--seed N` on the CLI
wins over `:runner {:seed N}` in config — and when you set neither, the
framework generates one. Either way it is recorded, always: `:run/seed` in
every group's `--edn` summary, `:seed` in the run context reporters see,
the HTML report header, and one console line (`run seed: N`, stderr). A
record without a seed is an old record, never a seedless run — with one
deliberate exception: the daemon's busy-reject summary (exit 2, nothing
ran) carries no `:run/seed`, because nothing was seeded.

The framework itself draws no randomness from it — deliberately. The seed
is a datum handed to *your* code, provided before the mess exists: when a
fixture generator, a property library, or any randomized dependency needs
a seed, draw from this one instead of inventing your own, and a rerun with
`--seed N` from a report reproduces the run's randomness end to end. It
arrives everywhere user code runs:

- hook payloads (`:before`/`:after`) carry `:seed`,
- step `ctx` carries `:seed`,
- a `setup.clj` group's `:start` receives it as a `:seed` key on its
  config argument.

**Deriving sub-seeds.** One run seed, many independent consumers: derive,
don't share. The convention — one concrete formula so every project
derives identically:

```clojure
(def sub-seed (bit-xor seed (hash scenario-id)))   ; or any stable label
```

Parallel scenarios each get an independent-but-reproducible stream
(`java.util.Random`, `SplittableRandom`, and test.check all take a long
directly). The framework documents this formula but does not implement
it — the seed is data; consumers choose.

## Where this is heading

Everything on this page is the lifecycle as *code* — `:start` functions,
hooks, a capture policy. The planned 0.6 contracts arc moves rungs of it
into *declarations*: state what must be true, not the code that makes it
true, and let the plan phase check it the way it already checks bindings
today. The ladder — declarative contracts over data declarations over
lifecycle code — is climbed by making the weaker rung's weakness legible in
evidence, never by prohibition.
[Hooks — and when not to use them](hooks.md) walks it rung by rung.
