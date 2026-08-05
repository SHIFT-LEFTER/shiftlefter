# Hooks — and When Not to Use Them

ShiftLefter has scenario lifecycle hooks: Before/After code that runs around a
scenario, registered in `hooks.clj`, named from the feature file with
`@hook=<name>` tags. They work, they're supported, and every hook that fires is
stamped in the run's evidence.

They are also the *lowest rung* of a ladder. Most things teams reach for hooks
to do have a stronger home in ShiftLefter — some already shipped, some designed
and scheduled. The framework's stance is graded, not prohibitionist: we never
forbid the weak pattern, we make its weakness legible. A fact established by
lifecycle code is recorded as exactly that, and when a declarative mechanism
exists for the same job, the declarative form is the one that survives
summarization, review, and time.

This page routes each classic hook use-case to its better mechanism, and is
honest about which of those mechanisms exist today versus which are scheduled.

## The feature file states what runs around it

First, the part that is *not* like Cucumber: hooks here are named, not ambient.

```gherkin
@hook=reset-db
Scenario: Purchase with a fresh account
  Given alice opens the browser to 'https://shop.example.com'
```

A scenario carries `@hook=` tags (inherited from feature/rule/examples level,
Gherkin-style); registrations live in one `hooks.clj` file next to your
`shiftlefter.edn`. That inverts the usual legibility problem: instead of "grep
the support directory and guess what fires," the feature file states what runs
around it, and `sl run --dry-run` prints the resolved firing list per scenario
— which hooks, in what order — before anything executes.

There is a `:global?` flag for hooks that genuinely apply to every scenario.
It exists mostly as the porting on-ramp: a team migrating from Cucumber can
drop its global Befores in as `:global?` hooks on day one, then graduate them
to named hooks as it becomes clear which scenarios actually need them. Global
hooks are stamped and previewed exactly like named ones — nothing fires
invisibly either way.

The full mechanics — registration shape, execution order, failure semantics,
payloads — live in the [hooks reference](AGENT.md#scenario-lifecycle-hooks)
(`sl agent-doc hooks`). This page is about *whether* to write one.

## Before you write a hook

Six use-cases cover nearly every hook ever written. Here's where each one
belongs:

| You're about to write a hook that… | Use instead | Status |
|---|---|---|
| launches/quits the browser, logs in, manages sessions | capabilities & costumes | shipped — never write this hook |
| starts a server, seeds a service, runs once per suite | `setup.clj` | shipped |
| seeds or resets data per scenario | a hook, honestly | shipped — graduates later |
| screenshots or scrapes console on failure | the `:capture` config line | shipped — delete the hook |
| collects metrics or timing | nothing — it's already recorded | shipped |
| retries flaky scenarios | nothing | deliberately unsupported |

### Browser and session lifecycle — never a hook

The classic Cucumber `Before` that launches a driver and the `After` that
quits it have no equivalent here, because the framework owns that lifecycle:
capabilities are provisioned per scenario — everything the plan needs, at
scenario start (eager by default) — and cleaned up after the scenario ends.
Authenticated
sessions are [costumes](COSTUMES.md), declared per actor, not code in a hook.
If you find yourself writing a hook that touches browser lifecycle, stop — the
thing you want is already happening, and your hook will fight it.

### Run-level and group-level setup — `setup.clj`

"Start the system under test once, run these features against it, tear it
down" is *run* orchestration, not scenario lifecycle, and it has its own file:
`setup.clj`, sibling of your `shiftlefter.edn` (both in `sl/`). It defines `setups` — a vector
of groups, each with a `:start` function (returning an optional `:stop` and an
optional adapter registry) and the `:features` that group owns:

```clojure
(ns setup)

(defn setups [config]
  [{:label    "sms-2fa"
    :start    start-fixture-server   ;; (fn [config]) → {:stop .., :adapter-registry ..}
    :features ["features/password_reset_sms.feature"]}])
```

When `setup.clj` is present, the union of the `:features` vectors *is* the
test plan — undeclared feature files are ignored, so the plan can't drift from
the orchestration. One mechanism note worth knowing: `sl run --dry-run`
previews the plan without running your `:start`/`:stop` or hooks, but the
`setup.clj` file itself is loaded — so keep top-level forms pure; side effects
belong inside `:start`.

Honesty line: `setup.clj` is the sanctioned answer for run-level lifecycle
today, and it works as described — but its full polish pass (failure-semantics
documentation, a config spec, plan-shape lints, a dedicated page) is scheduled,
not shipped. Expect the surface to firm up, not change shape.

The canonical worked example is
[`examples/04-sms-2fa`](https://github.com/SHIFT-LEFTER/shiftlefter/tree/main/examples/04-sms-2fa).

### Per-scenario data seeding and reset — a hook, honestly

Resetting a database or seeding a record *per scenario* is the legitimate
Before-hook job, and hooks are the right answer today. Two things make the
data flow legible rather than ambient:

- A `:before` returning a map merges it into the scenario context, and the
  report records which keys came from which hook — "where did this value come
  from" stays a mechanical query.
- A hook can declare `:provides` — the binding names (bare keywords, a
  letter then letters/digits — lowerCamel by convention, e.g.
  `[:sessionToken]`) it seeds into the scenario's
  [data plane](across-interfaces.md#passing-a-value-between-interfaces-named-bindings).
  Declared names satisfy the dry run's consumed-without-producer check, so a
  step consuming `{sessionToken}` plans cleanly against a hook-seeded value.
  Contribution keys that fit the binding shape mirror into the data plane
  whether declared or not — `:provides` is the static declaration that lets
  the dry run see it coming.

Write these hooks knowing they graduate: the planned contracts layer adds
declarative data declarations — state what must be true, not code that makes
it true — and the seeding hooks of today become the migration fodder of that
release. That's fine. It's what the bottom rung is for.

One current limitation, stated plainly: a hook that must reach an external
system (reset a database, call an internal CLI) shells out or uses what's
already on the framework classpath. Adding your own dependencies to the hook
classpath is a planned extension, not a current feature.

### Failure diagnostics — now a config line

Screenshot-on-failure is the most-written After hook in the industry, and
here it is **configuration, not code**:

```clojure
{:runner {:capture {:screenshot :on-failure
                    :console :on-failure}}}
```

```bash
sl run --capture screenshot=every-step   # the one-off film-strip debug run
```

Kinds today (browser-contributed): `:screenshot`, `:console`,
`:page-source`. Policies: `:never` (the default — capture is always
opt-in), `:on-failure`, `:every-step`. The `--capture` flag wins over the
config mirror as a whole value. Captured artifacts land in the run's
results directory under `attachments/<scenario-slug>/` and render
everywhere attachments do.

What the policy buys over a hand-rolled After hook:

- **The instant of failure.** `:on-failure` captures at the failing
  step's moment — before After hooks, before anything moves — and
  captures **every** live capability: Bob's screen may be exactly what
  explains Alice's failure. (One honesty note: an instant-of-failure
  screenshot of an optimistic UI shows *paint*, not settled state —
  settled ≠ painted.)
- **The film strip.** `:every-step` follows the acting subject, one
  capture per step. A step with no SVO actor (vanilla mode) captures
  every live capability — which in a single-browser suite is exactly the
  one browser; multi-capability suites pay that linear cost knowingly.
- **Step-grain console provenance.** Browser console reads are
  destructive drains (read-and-clear), so under `:every-step` each
  step's drain holds exactly the entries logged since the last —
  "which step said this" for free — with a final tail sweep so nothing
  logged after the last step is lost. Under `:on-failure` the console
  drains once, post-steps, only when the scenario is red — one
  whole-scenario log.
- **Timing honesty.** Capture cost is recorded on each ref
  (`:attachment/duration-ms`) and never pollutes *step* durations.
- **The evidence contract (harsh on purpose).** If capture
  infrastructure itself fails on an otherwise-green scenario, the
  scenario goes `:error`, capture-attributed — a green run whose
  promised evidence silently vanished would be green with missing
  evidence. Already-red scenarios keep their status; the ref records the
  miss visibly.

Kind support is adapter-level: `:console` is a Chrome/Edge capability
(other drivers no-op legibly), and the Playwright backend contributes no
capture kinds yet — on Playwright, a configured `:screenshot` simply
never fires. A configured kind that *no* registered adapter provides
gets one stderr warning per run — a typo'd kind must not be a silent
no-op.

Dry runs never capture (nothing executes), and a run with no results
directory (no config file) warns once and captures nothing.

### Custom captures — the After-hook attachments claim

For evidence the framework doesn't own — DB dumps, API traces, anything
beyond the built-in kinds — the After hook remains the sanctioned
channel. Afters run before capability cleanup, so the browser is still
live; on-failure conditionality is a plain `when` on the result status.
The After return is the **attachments claim**: write your file into the
payload's `:artifacts-dir` (pre-created, absent when the run has no
results directory) and return refs to what you wrote — they ride the
scenario envelope and render in every human report surface (inline in
HTML, Jenkins `[[ATTACHMENT|…]]` lines in JUnit, plain lines in console
failures; the `--edn` summary does not carry attachment refs yet):

```clojure
{:name "failure-screenshot"
 :after (fn [{:keys [ctx result artifacts-dir]}]
          (when (and artifacts-dir (= :failed (:status result)))
            ;; capabilities are still live here — unless provisioning itself
            ;; failed, in which case Afters run WITHOUT them: check for the
            ;; capability you want, don't assume it
            (let [path (str artifacts-dir "/failure.png")]
              ;; ... write the screenshot bytes to path ...
              {:attachments [{:attachment/kind :screenshot
                              :attachment/path path
                              :attachment/media-type "image/png"}]})))}
```

The claim contract is full-strict, deliberately: return nil (no claim) or a
map holding exactly `:attachments` with well-formed refs
(`:attachment/kind` + `:attachment/path` required). Anything else —
a misspelled key, a stray sibling key, a non-map value — is
`:hook/invalid-return` and the scenario goes `:error`: a typo'd claim that
silently vanished would be evidence loss the report never confesses to.
A claimed file that doesn't exist keeps the scenario's outcome (the
reference is data; the file is world) but the ref renders with a visible
error and a stderr warning fires.

But for screenshot/console/page-source, the guidance is now: **delete the
After, set `:capture`**. Capture is runner-level and mode-agnostic, so a
team porting a Cucumber suite deletes its screenshot infrastructure and
adds one config line on day one, before adopting anything else
ShiftLefter does.

One hazard, now live: browser console reads are destructive drains — two
readers can't both see the log. With `:capture` console on, the framework
owns the read; a hook that calls the browser's log API gets whatever the
last drain left (usually nothing). Hooks that want the log read the
captured artifact, not the browser — and hooks that want the
*whole-scenario* log want the `:on-failure` policy, whose single
post-steps drain fires before any After runs.

### Metrics and timing — already recorded

Don't write timing hooks. Every step result carries its duration; every hook
that runs is stamped with `:duration-ms`; the JUnit XML and the HTML
transcript carry the numbers. A metrics hook is a second, unshareable copy
of data the runner already emits — consume the reports instead. Richer
observability is roadmap, and it will land as reporting surface, not as a
hook you maintain.

### Retry and flake control — deliberately unsupported

There is no retry hook, no rerun-on-failure knob, and none is planned. A
retried pass is a report that the suite lies at some rate — and everything
ShiftLefter does is in service of reports that don't lie. What exists instead:
`:requires-serial` on a hook auto-serializes the scenarios that carry it
(recorded in the report, with the hook named as the reason), which removes the
parallelism-induced flake class without hiding anything. Genuinely flaky
scenarios are bugs — in the suite or the system — and the transcript is built
to make them findable, not survivable.

## Porting note

If you're bringing an existing Cucumber suite: your `.feature` files parse
unchanged, and nothing forces a rewrite of features you haven't touched —
vanilla mode (built-in steps plus your own step definitions, in Clojure) is a
compatibility *guarantee*, not a migration stage you must pass through. Your
step definitions themselves don't come over; [Adoption](adoption.md) is honest
about where migration actually stands. The hook story rides each migration
slice for free: global Befores land as `:global?` hooks, screenshot
infrastructure IS a config line (`:capture`, above), and per-scenario seeding
maps onto named hooks one `@hook=` tag at a time.
