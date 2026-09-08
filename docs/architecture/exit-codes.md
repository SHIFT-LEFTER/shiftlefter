<!-- GENERATED — DO NOT EDIT. Derived from the internal source
     notes/architecture/exit-codes.md (development repo) by
     scripts/derive-arch-docs.bb. Edit the source, then re-run:
     bb scripts/derive-arch-docs.bb -->

# Exit Codes

The `sl run` exit code is a claim about what the run **proved**, not a
description of what broke. This page is the ladder — the five codes, the
axis that orders them, and the error-class contract that lets a step or
adapter keep the exit code honest. The mechanical table is the same one
`shiftlefter.runner.verdict` owns; a drift guard keeps every copy in
agreement.

If you are holding a nonzero exit right now: **1** means the app answered
and at least one answer was wrong — start from the report's failure
evidence. **2** means nothing ran — fix the invocation, the config, or the
spec's symbols. **3** means ShiftLefter itself crashed — trust nothing in
the run and file a bug. **4** means at least one scenario never got an
answer — don't touch product code until triage says why. The rest of this
page is the reasoning behind those imperatives.

## The table

| Code | Status | Meaning |
|---|---|---|
| 0 | `:passed` | Existential pass: ≥1 scenario selected, every selected scenario ran to a tolerated outcome, none failed or errored (pending tolerated only under `:allow-pending?` — the counts still confess). |
| 1 | `:failed` | ≥1 scenario `:failed` — the app answered and the answer missed the expectation (including [`:sut`-classed](#the-three-error-classes-and-the-witness-relation) errors: the app answered *with* an error). Or pending under strict mode. |
| 2 | `:planning-failed` | Invalid before execution: config/parse/discovery/binding errors, an un-runnable invocation, an empty selection. Pre-execution exclusive — nothing ran. |
| 3 | `:crashed` | Runner crash — an uncaught throwable in ShiftLefter's own code. Anywhere; wins over everything. |
| 4 | `:degraded` | ≥1 scenario `:error` — either the harness's own machinery broke (hook throw, capability provisioning, capture, harness; counted `error`) or a step's adapter classified its failure `:observation` — no answer existed (counted `unobserved`). The verdict about the app is unavailable for those scenarios. |

Precedence: 3 anywhere; 2 pre-execution exclusive; during execution worst
wins, 4 > 1 > 0. Across `setup.clj` groups the suite aggregates worst-wins:
2 > 4 > 1 > 0.

One refinement at suite scope: the table's "nothing ran" is true per
group. Exit 2 is pre-execution exclusive *per [`setup.clj`
group](../lifecycle.md)* — some planning checks are decidable only at a
group's turn (on the setup pipeline, the registry to bind against exists
only after that group's `:start`), so a late group can plan-fail after
earlier groups already ran. A suite-level 2 therefore means at least one
group's request was never runnable: the suite-as-asked was never fully
specified, and no suite verdict exists — which is why 2 outranks 4 and 1
in the aggregate. The earlier groups' evidence is real and sits in the
report; gate on the exit code, not the report.

Aborts sit outside the table: an interrupted run exits by platform
convention (Ctrl-C = 130) and carries no verdict — and on the warm path
an interrupted client does not necessarily stop the run; the daemon may
finish it server-side.

## Two verdicts, three failures to measure

Exits **0 and 1 are verdicts about the app**. Exits **2, 3 and 4 are three
different ways the run failed to be a measurement**, distinguished by whose
side of the line gave way: 2 — your request (pre-flight, total: nothing
ran); 3 — ShiftLefter's own code (anywhere, total: trust nothing, file a
bug); 4 — the machinery of observation between your spec and the app,
mid-run (partial: those scenarios have no verdict).

Why 4 trumps 1: exit codes route the next action; the report carries the
evidence. 4's imperative — *fix observation, rerun; this run is not the
measurement you asked for* — subsumes 1's. Reporting 1 on a degraded run
would claim a completeness the run lacks. The counts confess the failures
either way — the summary line reads like `6 scenario(s): 3 passed, 2
failed, 1 unobserved`.

Read the two verdicts precisely. 0 is an existential pass: the suite ran
whole and the app met every expectation *checked* — it says nothing about
expectations you didn't write. 1 is the only code that indicts the
product, and it doesn't claim every test ran; it claims every selected
scenario reached a verdict and at least one verdict was failed. 3 is the
code you should never see: reaching it requires a ShiftLefter bug that
escaped every structured handler, so its absence is the system working —
it exists so that when it fires it cannot masquerade as anything else.

Hooks sit on the measurement side of the line, and one question settles
every hook case: did this failure show the app being wrong? A hook never
does — it sets and strikes the stage. If the stage collapses mid-scene,
the review of that scene is void: not a bad play, no performance. The
boundary is drawn at verdict time — a teardown throw after the verdict is
earned does not touch the exit code.

## Exit 2 is a proof, not a certificate

The symbol space ([glossary](../GLOSSARY.md), registries, config) is closed-world: ShiftLefter
owns the whole universe, so exit 2 is sound *and* complete over it. The
spec's claims about the world ("this locator finds its element") are
open-world: the planner has a proof procedure for malformation only, so
*not refuted* is not *valid*. Validation happens only by running, one run
at a time — a green run is tested-and-not-falsified for this world-state,
not proven. Consequence: a locator matching nothing at runtime is exit 1 /
`:assertion`, never `:observation` and never a planning error — the
spec-as-requirement was observed violated; glossary rot vs app regression
is triage, downstream.

The asymmetry matters mid-debug. When exit 2 fires, your code is wrong —
that half is proven. When it doesn't fire, that is not the same as knowing
your code is right; the planner can only refute, never certify. A green
run is Popper, not Euclid.

So "the planner accepted my glossary" is never evidence the glossary is
current. When an element-not-found shows up fresh, it is a fork to triage
— did the glossary rot, or did the app regress? — not a verdict to relay.
Both hypotheses are live at the failure site, which is exactly why the
run reports the observation and leaves the attribution to you.

## The three error classes and the witness relation

Every scenario has three parties: the **expectation**, the **SUT**, and the
**witness channel** through which the SUT is observed (HTTP is only the most
common one). A step or adapter may pre-classify the failure it throws with
one recognized ex-data key, `:error/class`:

| Class | The witness… | Scenario | Exit |
|---|---|---|---|
| `:assertion` | delivered an observation; it missed the expectation (wrong body, absent element, a *late* answer that arrived) | `:failed` | 1 |
| `:sut` | delivered the SUT answering **with an error** (500, stack-trace page, job state `failed`) — misbehavior observed is evidence | `:failed` | 1 |
| `:observation` | itself failed to deliver (refused, transport timeout, DNS, garbled transcript) — proves nothing about the app, cause-agnostic by design | `:error`, counted `unobserved` | 4 |

Absent (or unrecognized) = today's behavior. The only behavioral cliff (1
vs 4) sits on the only decidable edge: did the witness answer, or not? An
expected **event** that never occurs through a healthy witness is
`:assertion` (the mailbox polled fine; the message never came); an expected
**response** that never comes is `:observation` (silence is undecidable).

The class that tempts misuse is `:observation` on a timeout, so take the
hard case: an endpoint that normally answers in 200ms times out at 10s,
and you're sure it's the app's bug. "Definitely a failure" is hindsight
talking. At the timeout the run possesses ten seconds of silence, and
that silence is observationally identical between would-have-answered-at-45s,
deadlocked-forever, the network ate the request, and the NIC died. You
"know" it's the app only from the server's vantage point, which the run
never gets. Classifying silence as `:observation` is not a claim that
nothing failed — see the next section — it is a refusal to convert
silence into evidence.

Two give-backs keep most real slow-app cases out of `:observation`. A
late answer that *arrives* is `:assertion` — lateness you observed is
evidence. And polling-with-answers is `:assertion`: an await that times
out after successful polls (a job stuck at 85%) had a witness that
answered every time; the answers just never satisfied the expectation in
time. The timeout record carries the structural tell — `:last-value`
present means the witness was answering (assertion territory);
`:last-error` means an attempt threw — and the two ride side by side when
both happened. Precedence is *ever-answered wins*: a poll that never
returned a non-nil value and saw at least one classified dark throw, at
any attempt, inherits `:observation` on its timeout; any answer at any
attempt keeps the timeout unclassified (the witness talked — assertion
territory). The record's `:poll-counts` digest — `{:attempts n :answered
n :threw n :observation-throws n}`, four integers, never a history — shows
which case you are reading.

The `:assertion`/`:sut` boundary, by contrast, carries zero behavioral
stakes — both are `:failed`, exit 1; misfiling costs a rendering hint.
`:sut` earns its place anyway: it routes
debugging (start from the app's own error — the 500 body, the server
logs — instead of diffing expected against actual), and it gives the
author holding a 500 who feels "this request FAILED" somewhere honest to
put it that isn't `:observation`. A 500 that turns out to be intended
behavior doesn't retroactively misclassify — `:sut` describes what was
observed, an error-shaped answer; stale expectation vs regression is
triage, same as the locator case. An adapter that never checks status
degrades gracefully to `:assertion`; the low-effort path is always safe.

## Exit 4 is not an acquittal — triage

The build is red, nonzero, blocking. What is withheld is only the
*indictment of the product*, because the evidence never reached it. A
transport timeout with pure silence is `:observation` even when a perf bug
is suspected — silence proves silence; the product finding is made in
triage with the environment ruled out, instead of guessed by an exit code.

Recurrence signature: the same scenario `unobserved` run after run while
its siblings pass says *suspect the SUT's responsiveness, not the network*.

Why the withholding is worth it: a false "exit 1: product broken" is most
dangerous when it's rare, because it gets believed. An agent handed a
lying exit 1 does the worst possible thing — confidently fixes product
code against phantom evidence. Exit 4 is the machine-legible stop sign:
don't touch the code; this run proved nothing about it; check the
environment first.

The failure mode on the other side is rerun purgatory: mechanically
treating every 4 as "rerun it" can park a chronically slow endpoint there
indefinitely. The report names which scenarios went unobserved — read it
before rerunning, and watch for the recurrence signature above.

## Halt policy

`{:runner {:halt-on-observation-failure …}}` — config-only, default
**off**; the value is a tri-state `false` | `:group` | `:run`, and `true`
reads as `:run`. When on: after any scenario finalizes observation-class,
no further scenario **starts**. Scenarios already in flight (under
[`--max-parallel`](../../README.md#running-tests)) run to completion and keep their verdicts; every
unstarted scenario — the rest of the queue and the `@serial` tail — is
`skipped`. The report says `halted after scenario N (<name>): observation
failure` (console, HTML header line, and the `--edn` summary's `:halted
{:after-scenario N :scenario "<name>"}`). The exit code is 4 either way —
the halt changes wall-clock and report noise, never the verdict. Off by
default because skipping is a form of not-running what was asked and the
environment can recover mid-run; CI is where it earns its keep.

The scope names your group topology. With `setup.clj` groups as
partitions of **one** environment — the CI case, one deploy that never
came up — `:run` is the honest setting: the halt is invocation-wide, groups
not yet started are skipped entirely (their `:start` never runs; a dark
environment gets no more setups), their aggregate rows say `:run/status
:skipped` with no exit code, the invocation aggregate carries the same
`:halted` record plus `:group/label`, and the invocation exits with the
halted group's 4. With groups as **different** environments — a dark
channel in one says nothing about the others — `:group` halts only the
group whose scenario went dark; its siblings run to completion. Status is
the outcome axis (`skipped` = did not run); the `:halted` record is the
cause axis — no new status word exists for it.

The wall-clock case for turning it on there: once the environment is
gone, every remaining scenario pays its full timeout budget to learn
nothing. The halt converts a long march of doomed scenarios into one
observed failure and a short report.

## Classifying your own adapters

Classification lives at the one place per transport that does I/O — the
adapter's catch site — never per step. One optional key, three values,
absent = unchanged; declining to classify loses nothing. ShiftLefter's own
builtins ship pre-classified: the SMS family tags a failed provider query
`:observation` (the provider is the witness); the browser family tags a
WebDriver transport throw (`:etaoin/http-ex` — the request itself failed, no
response existed) `:observation`, plus one driver *answer*, at one site:
when the network refuses a navigation, the `opens the browser to`
navigation step throws `SUT unreachable: <code> for <url>`, classified
`:observation` — a closed set of Chromium `net::ERR_*` codes (connection
refused/reset/timed out/closed, name not resolved, address unreachable,
internet disconnected, the generic `ERR_TIMED_OUT`, `ERR_EMPTY_RESPONSE`) —
and a page-load timeout there, the driver's `timeout` answer to a server
that accepted and never answered, throws `SUT silent: navigation timed out
for <url>`, classified the same way (silence proves silence; the etaoin
adapter's `:config {:page-load-timeout-ms N}` sets how long the wait is —
absent, Chrome's default is five minutes). Every other driver
answer — element-not-found included — stays product evidence. Known
hole: a server that accepts and closes without a byte is nondeterministic
in Chrome — sometimes it reports `ERR_CONNECTION_RESET` (classified, in
the set), sometimes it *returns* its own error page, so the navigation
succeeds and the scenario fails at the first element lookup as
`:assertion` — no message-based signal exists for that second case. On a
costume, a refused navigation first
rebuilds the session and retries once; the classification lands on the
retry's throw.

The class is read from **step** throws only: a throw from a Before/After
hook, from adapter provisioning (`:start` / `create-capability`) or from a
capture fn is always the harness bucket (`error`), never `unobserved`, and
never arms the halt gate — classify at the catch site whose throw reaches
the step. (The bundled custom-capture example,
`examples/07-custom-capture-kind`, tags nothing on its capture fn for
exactly this reason.)

The contract in one sentence: opt-in pre-classification of failure kinds,
authored once at the adapter's catch site, fired at the failure site. It
moves an *honest subset* of exit-1s to exit 4. It never promises to
detect all infrastructure failure — an unclassified error keeps today's
behavior exactly, which is what makes adopting it zero-breakage.

The cost stays flat because classification lives where the I/O lives. If
your adapter wraps its HTTP calls, the catch block that already turns a
`ConnectException` into ex-info gains one key; steps change zero. Start
by naming the witness — the channel you observe *through*, which is not
always the system you're testing (a provider API you poll is the witness;
the app that should have sent the message is the SUT). Then apply the one
razor: did the witness answer? Answered wrong → `:assertion`; answered
with an error → `:sut`; didn't answer → `:observation`. When a case feels
too strange to classify, don't — declining loses nothing, and the only
misfiling that lies is calling a delivered answer `:observation`.

## How this page stays true

This page is a mechanical projection of a live-maintained internal source,
regenerated — never hand-edited — by the derivation pipeline and
drift-guarded by the test suite: a hand edit here fails a test, and so
does a page that no longer matches its source.
