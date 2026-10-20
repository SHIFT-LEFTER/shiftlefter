<!-- GENERATED — do not edit. Single-sourced from the packaged
     `sl agent-doc adoption` topic at shiftlefter/agent_docs/adoption.md
     under resources/. Edit the source, then run:
     clojure -T:build gen-agent-md -->

# Bring Existing Gherkin

ShiftLefter parses all Gherkin; the only thing it refuses to run is an untyped
step. This page is what that means for a Cucumber suite you already have: what
works the moment you point `sl` at it, what `sl run` tells you, and what
"shifting" the suite actually involves.

Starting fresh instead? Skip this page — add your first behavior with
`docs/browser-getting-started.md`.

## What works immediately: the parser layer

The parser is 100% Gherkin-compatible (it passes all 50 official Cucumber test
files and rejects all 12 invalid ones), so your `.feature` *files* come over
unchanged. With no config and no step definitions you can already:

- **Check and format** — `sl fmt --check sl/features/` validates every file;
  `sl fmt --write` reformats in place; `sl fmt --canonical` prints the
  canonical form. The roundtrip is lossless, so `--check` is safe to put in CI
  on day one.
- **Stress the grammar** — `sl gherkin fuzz` and `sl gherkin ddmin`
  (`docs/FUZZ.md`) need nothing but the files.

None of that asks you to change a line of your suite.

## What `sl run` tells you

There is no compatibility mode that runs untyped steps. Copy the suite into
`sl/features/` and run it, and the planner binds every step as it always does —
finds that nothing binds — and stops before anything executes:

```
5 binding issue(s) found. Cannot execute.

This reads as an unshifted suite: every selected step is undefined.
ShiftLefter parsed 1 feature file(s), 2 scenario(s), 5 distinct step text(s) — and executes only typed steps.
To shift this suite, run: sl agent-doc adoption
```

Exit code 2 — a planning result, not a failed run. Nothing was launched. Under
`--edn` the same inventory rides the record as a top-level
`:unshifted-suite {:features N :scenarios M :distinct-steps K}`. The distinct
step count is the honest size of the job: it is how many step texts need a
typed definition.

## What shifting means

A step runs when it binds to a **typed** step definition — one that resolves
it to a Subject–Verb–Object triple checked against your project vocabulary at
planning time. Two ways a step gets there:

- **Rewrite it onto a built-in step.** Browser and SMS behavior is covered by
  built-in typed steps; a rewritten scenario needs a subject glossary saying
  who the actors are (`sl agent-doc vocabulary`), and usually intents naming
  what they act on (`sl agent-doc locators`). No Clojure.
- **Write a `defstep` for it**, with `:svo` metadata (`sl agent-doc authoring`
  has the step authoring rules). A matched `defstep` *without* `:svo` still
  binds and runs, and is reported as `:stepdef/untyped` — a warning by
  default, `:error` if you set `:enforcement {:untyped-stepdef :error}`.

What does **not** come over is your step library. ShiftLefter runs built-in
steps or step definitions written in Clojure — not existing
Java/JavaScript/Ruby step code.

## One slice at a time

You do not have to shift the whole suite before anything runs. Once *some*
scenarios plan and others don't, the exit-2 diagnostic says so and names the
flag:

```bash
sl run sl/features/ --hold-unplannable
```

`--hold-unplannable` deselects, at planning, every scenario that an undefined
step, an ambiguous step or a binding error would fail; runs the rest with its
ordinary verdict; and counts the held ones on every surface (`N held` on the
console verdict line, `:held` records under `--edn`, `<skipped message="held:
…">` in JUnit), each held entry naming what held it. It is a per-run CLI flag,
never a config key — a suite cannot quietly configure its way out of being
finished.

Lifecycle lands the same way: global Befores, screenshot capture, and
per-scenario seeding each have a home in hooks (`sl agent-doc hooks`, its
porting note), one slice at a time.

## Where this stands

The table stakes a commercial suite leans on are in place — JUnit XML and HTML
reporting, tag filtering, scenario Before/After hooks. First-class declarative
test fixtures are not (see `docs/README.md` § What 0.5 is (and isn't)).
If you are weighing a production migration, `docs/FIT.md` is the honest read
on whether to move now or watch.
