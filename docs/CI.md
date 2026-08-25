# Running ShiftLefter in CI

Wire `sl run` into a CI pipeline: JUnit XML into your CI's test widget,
tag-filtered subsets for merge-request pipelines, and exit codes designed for
gating. Everything on this page is Tier A — a Java 21+ image and the `sl` CLI,
no Clojure toolchain (see [What can I do?](INSTALL-TIERS.md)).

The worked examples target GitLab CI and GitHub Actions, but the pieces —
install, run, collect the report, gate on the exit code — translate to any CI
system that can run a shell command.

## The one rule: gate on the exit code, route on the record

A planning failure — undefined steps, parse errors, config errors, no features
found, an **empty selection** — exits **2** and, in a single-group run, writes
**no JUnit file at all**. In a multi-group run (`setup.clj`, N>1) the other
groups still run and the file IS written, covering only the groups that ran —
it can be entirely green beside exit 2. A job that keys only on the report's
presence or greenness (or swallows the exit code with `|| true`) will read
that missing — or partial, all-green — report as success. So:

- Let `sl run`'s exit code fail the job. Don't append `|| true`, and don't
  `allow_failure` the test job. Any nonzero exit is a failed gate.
- Treat the JUnit report as *display*, not as the gate.
- When you need to know *which kind* of red — app failure (1), plan problem
  (2), harness breakage (4), crash (3) — read the exit code's value or the
  `--edn` record, and route accordingly: page the app team on 1, fix the
  test plan on 2, look at your infrastructure on 4. The gate stays binary;
  the routing is where the distinctions pay.

In a single-group run the report never contradicts the exit code: the XML
contains at least one `<failure>`/`<error>` exactly when the run exits
nonzero. In a multi-group run that equivalence holds only for the groups that
ran — a later group's planning failure exits 2 beside a file that may be all
green (see [ERRATA E009](../ERRATA.md#e009-junit-xml-has-no-official-spec--we-target-the-consumer-subset)
D3). `sl run`'s codes:

| Code | Meaning |
|------|---------|
| 0 | Existential pass: ≥1 scenario selected, all ran to a tolerated outcome, none failed or errored (pending tolerated only under `:allow-pending?`) |
| 1 | One or more scenarios failed (valid tests, app wrong), or pending steps when not allowed |
| 2 | Planning failure (undefined steps, parse errors, config errors, no features found, bad flags, empty selection) — **single-group: no JUnit file; multi-group: the file covers only the groups that ran** — gate on the exit code |
| 3 | Runner crash (unexpected exception) |
| 4 | Run degraded: an infrastructure failure (hook throw, capability provisioning, capture, harness) — the run proves nothing about the app |

Canonical table for all commands: [README → Return Codes](../README.md#return-codes).
Background on the no-file/partial-file semantics and the JUnit format itself:
[ERRATA E009](../ERRATA.md#e009-junit-xml-has-no-official-spec--we-target-the-consumer-subset).

One forward-looking note for gate authors: today an infrastructure failure
that surfaces inside a step — a connection refused mid-run, say — is
indistinguishable from the app being wrong and reports as an ordinary
scenario failure (exit 1); only the harness's own `:error` family reaches 4.
A future release may classify such step-level infrastructure failures into
the degraded verdict — one more reason to gate on nonzero, not on a specific
value.

The HTML report behaves differently on exit 2 (since 0.5.4): late planning
failures — undefined or ambiguous steps, arity mismatches, a hooks error —
refresh the configured `--html` path with a PLANNING FAILED report, so that
path never shows a previous run's green for those exits. Earlier exits
(config errors, parse failures, setup plan-shape errors, a group's `:start`
failing) still write nothing, and a `report.html` from a prior run survives
them untouched — one more reason the exit code, not any report file, is the
gate.

## Empty selection is exit 2 — and the dry-run-first pattern

A run that selects **zero** scenarios — a tag filter matching 0 of N, a
feature path with no scenarios, CLI paths narrowing every `setup.clj` group
away — exits 2. There is no allow-empty knob, deliberately: a green light
from a run that ran nothing is the lie this contract exists to prevent, and
a testless repo wired into a shared pipeline is an upstream problem the
runner cannot fix by pretending.

What you get instead is **data**. The empty-selection record confesses the
whole story — `:selected 0`, the pre-narrowing `:total`, what was searched,
the active tag filter, the CLI paths, the group labels — in the planning
error's `:data`, and every summary (including exit-2 summaries) carries a
`:counts` map. If your pipeline legitimately produces empty selections
(e.g. a tag-sliced matrix where some slices are empty by design), put the
choice in your wrapper, not in the runner:

```bash
# Dry-run first: cheap (no browsers, no lifecycle), same planning verdicts.
if sl run --tags "$SLICE" --dry-run --edn > plan.edn; then
  sl run --tags "$SLICE" --junit-xml report.xml   # real gate
else
  rc=$?
  # exit 2 with a :selection/empty record = empty slice; YOUR call:
  grep -q ":selection/empty" plan.edn && [ "$rc" -eq 2 ] && exit 0
  exit "$rc"                                       # any other planning error
fi
```

The dry run's exit 0 is a *plan* verdict — "the selection binds" — which is
why it too requires a nonempty selection: our data, your choices.

## Consuming `--edn` output

With `--edn`, stdout carries a **sequence of EDN forms**, not always exactly
one: one run summary per suite (per *group*, under `setup.clj`
orchestration), plus occasional small supplementary diagnostics forms
(`{:run/id … :diagnostics {…}}` — e.g. a setup group's teardown failure, or
a prior run's dirty-teardown warning at startup). Read **all** forms and
**skip shapes you don't recognize** — the form vocabulary grows by release,
and unknown forms are never an error. Summaries are the forms carrying
`:run/exit-code`. A summary may carry `:dropped-events` — observe-plane
events the bus refused during that group's run-scope (absent when zero;
results are unaffected, observations may be gapped).

Building your own rendering from this record? [Build your own report](REPORTS.md)
documents the machine-record contract — summary shape, attachment refs, and a
runnable recipe.

## The run results directory

Every executing run in a configured project creates a standing output home:

```text
<config-root>/results/<stamp>/     # i.e. sl/results/…
```

- **`<stamp>`** is the run's start time (UTC), compacted filesystem-safe:
  `20260712T153042Z` — no colons, Windows-safe, sortable. Two runs in the
  same second: the second gets a short run-id suffix
  (`20260712T153042Z-a1b2c3d4`).
- **One directory per `sl run` invocation** — with a `setup.clj` declaring
  multiple groups, all groups share the invocation's directory.
- **Gitignored by itself**: the framework writes `results/.gitignore`
  containing `*` when it first creates the directory, so run outputs never
  show up in `git status` — repo hygiene, no `.gitignore` edits needed on
  your side.
- Runs without an `sl/shiftlefter.edn` (ad-hoc `sl run some.feature`) create
  nothing — the convention is a project convention. Dry runs and planning
  failures (exit 2) create nothing either.

What lands inside: the **HTML report's primary copy** (`report.html`, or
`<group-slug>/report.html` per `setup.clj` group) and per-scenario
`attachments/<scenario-slug>/` directories holding **captures and hook
attachments**, with more run artifacts to come. The report sits beside its attachments so relative
references resolve — the run directory zips and travels as one
self-contained artifact.

`--html` still writes exactly where you point it: that fixed path
receives an end-of-run byte copy, so CI configs and agents that grab a
known path keep working unchanged — but images in that copy degrade to
labeled text (the files live in the run directory, not beside the copy).
Under multi-group `setup.clj` the fixed path holds the **invocation
report**: a run-root `report.html` listing every group in declaration
order — invocation verdict up top, per-group status, exit code, counts,
and a relative link to that group's own `<group-slug>/report.html`.
Inside the run directory the links navigate; in the fixed-path copy they
degrade to labeled text, but the verdicts and counts stay visible. A
group that fails planning mid-run keeps its PLANNING FAILED page durably
at its own `<group-slug>/report.html` — a later group's report can never
overwrite it.
`--junit-xml` is unchanged; its `[[ATTACHMENT|…]]` lines use absolute
paths into the run directory. Planning failures that stop the whole run
before execution (exit 2) still write only the fixed `--html` path and
create no run directory.

In CI, archive the whole `results/<stamp>/` directory when your suite
captures attachments; the fixed `--junit-xml`/`--html` paths alone are
enough only for attachment-less runs. The usual CI capture line is
`:runner {:capture {:screenshot :on-failure :console :on-failure}}` —
default is `:never` everywhere, so capture never surprises a pipeline
that didn't ask for it. A capture-infrastructure failure on an
otherwise-green scenario flips it to `:error` (capture-attributed in the
report) — a green gate whose promised evidence vanished would be lying.

## Warm vs. cold: prefer the cold path in CI

Our data, your choices: **default to the cold path in CI** — set
`SL_NO_DAEMON=1` in the job environment (or pass `--no-daemon` per call).
A one-run pipeline gains almost nothing from warmth (the JVM tax is a
rounding error against one real run), an ephemeral runner is destroyed
before it could ever reuse a warm daemon, and fan-out containers each pay
the startup cost once regardless. Cold also leaves **zero background
residue**: no daemon JVM, no held browser state, nothing for the runner
teardown to account for. The daemon is a development-loop tool — its
constituency is the many-small-invocations edit-run cycle (see
docs/daemon.md).

The exception worth measuring: a **persistent** runner making many small
sequential `sl` invocations saves a few seconds on each warm call. If
that's your shape, measure it before switching — and remember a warm
daemon holds state between invocations (worn costumes included; `sl
daemon status` shows custody).

## Picking an image

Any image with **Java 21 or later** runs ShiftLefter — a JRE is enough, e.g.
`eclipse-temurin:21-jre`. Two caveats:

- The installer script needs `curl` and `unzip`. Slim JRE images often ship
  without them; on Debian/Ubuntu-based images,
  `apt-get update && apt-get install -y --no-install-recommends curl unzip`.
- Browser scenarios additionally need Chrome and a matching ChromeDriver in
  the image — see [Browser tests in CI](#browser-tests-in-ci) below.

## Installing `sl` in the job

The installer drops a runnable `sl` + jar into `./sl/`:

```bash
curl -fsSL https://raw.githubusercontent.com/SHIFT-LEFTER/shiftlefter/main/release/install.sh \
  | bash -s -- --version 0.5.5 --no-breadcrumb
```

- **Pin `--version`** to the release your team is on, so CI doesn't silently
  move when the default changes.
- `--no-breadcrumb` skips the agent on-ramp stanza — it's for humans pasting
  into agent files, not for CI logs.
- **Cache `./sl/`** keyed by version so the download happens once, and skip
  the install when the cache hit: `[ -x sl/sl ] || curl …`.

Then run it as `./sl/sl`, or `export PATH="$PWD/sl:$PATH"` and use `sl`.

## Worked example: GitLab CI

Merge-request pipelines run only the `@smoke` subset; the default branch runs
the full suite. Both feed GitLab's test widget via `artifacts:reports:junit`.

```yaml
# .gitlab-ci.yml
image: eclipse-temurin:21-jre

stages: [test]

cache:
  key: "sl-0.5.5"
  paths: [sl/]

.install-sl: &install-sl
  - apt-get update -qq && apt-get install -y -qq --no-install-recommends curl unzip
  - '[ -x sl/sl ] || curl -fsSL https://raw.githubusercontent.com/SHIFT-LEFTER/shiftlefter/main/release/install.sh | bash -s -- --version 0.5.5 --no-breadcrumb'

smoke:
  stage: test
  rules:
    - if: $CI_PIPELINE_SOURCE == "merge_request_event"
  script:
    - *install-sl
    # The exit code is the gate — no `|| true`, no allow_failure.
    - ./sl/sl run --tags @smoke --junit-xml report.xml
  artifacts:
    when: always          # upload the report on failure too — that's when you want it
    reports:
      junit: report.xml

full-suite:
  stage: test
  rules:
    - if: $CI_COMMIT_BRANCH == $CI_DEFAULT_BRANCH
  script:
    - *install-sl
    - ./sl/sl run --junit-xml report.xml
  artifacts:
    when: always
    reports:
      junit: report.xml
```

Notes:

- `--tags @smoke` filters at **planning time**: deselected scenarios are never
  bound, and the counts and the JUnit report cover only the selection. Results
  for the selected scenarios are identical to a full run. `--skip-tags`
  excludes by tag the same way (exclude wins over `--tags`).
- With built-in browser steps only (no custom step definitions), drop
  `--step-paths steps/`.
- On a planning failure, the job fails on exit 2 and GitLab logs a "no
  matching files" warning for the report — that's the designed behavior, not
  a collection bug. Fix the plan; don't relax the artifact rule.
- `--junit-xml PATH` can also live in config as
  `:runner {:report {:junit-xml "report.xml"}}`; the flag wins over config.

## Worked example: GitHub Actions

GitHub Actions has no built-in JUnit test widget. The report is still worth
producing: upload it as an artifact, and add a third-party reporter action
(e.g. `dorny/test-reporter` or `mikepenz/action-junit-report`) if you want
results rendered as check-run annotations — evaluate those separately; they
aren't ours.

```yaml
# .github/workflows/test.yml
name: tests
on:
  pull_request:
  push:
    branches: [main]

jobs:
  features:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'
      - name: Install sl
        run: |
          curl -fsSL https://raw.githubusercontent.com/SHIFT-LEFTER/shiftlefter/main/release/install.sh \
            | bash -s -- --version 0.5.5 --no-breadcrumb
      - name: Run features
        # PRs run the @smoke subset; main runs everything.
        run: |
          ./sl/sl run --junit-xml report.xml \
            ${{ github.event_name == 'pull_request' && '--tags @smoke' || '' }}
      - uses: actions/upload-artifact@v4
        if: always()        # keep the report from failed runs — that's the useful one
        with:
          name: junit-report
          path: report.xml
```

The exit-code rule applies unchanged: the `Run features` step fails the job on
any nonzero exit, including the report-less exit 2.

## Pending scenarios in CI

A step definition that returns `:pending` marks its scenario pending, and the
JUnit report **mirrors the exit code** — it never shows green for a run that
exits red:

| Config | Exit code | In the JUnit XML |
|--------|-----------|------------------|
| strict (default) | 1 — run fails | `<failure type="pending">` |
| `:runner {:allow-pending? true}` | 0 — run passes | `<skipped>` |

Keep CI strict (the default). `:allow-pending? true` means a half-written
scenario reports as a skip and the pipeline stays green — reasonable on a
work-in-progress branch, a footgun on main.

## Browser tests in CI

Built-in browser steps work in CI with three additions to the basic job:

1. **Headless.** Set `:headless true` on the `:web` interface. If your local
   config differs, keep a CI-specific config file and select it with `-c`:

   ```clojure
   ;; shiftlefter.ci.edn
   {:runner {:step-paths ["steps/"]}
    :interfaces
    {:web {:type :web
           :adapter :etaoin
           :config {:headless true}}}}
   ```

   ```bash
   ./sl/sl run sl/features/ -c shiftlefter.ci.edn --junit-xml report.xml
   ```

2. **Chrome + ChromeDriver in the image**, with matching major versions.
   On Debian/Ubuntu-based Java images, `apt-get install -y chromium
   chromium-driver` installs a matched pair with the driver on `PATH`. If the
   driver lives elsewhere, point at it with `:chromedriver-path` in your
   config instead of touching `PATH`. (GitHub's hosted `ubuntu-latest`
   runners ship Chrome and ChromeDriver preinstalled.)

3. **Container Chrome flags.** Chrome running as root in a container
   typically needs `--no-sandbox`, and constrained `/dev/shm` causes crashes
   fixed by `--disable-dev-shm-usage`. Pass both through `:adapter-opts`
   (merged into the backend's native options):

   ```clojure
   {:interfaces
    {:web {:type :web
           :adapter :etaoin
           :config {:headless true
                    :adapter-opts {:args ["--no-sandbox"
                                          "--disable-dev-shm-usage"]}}}}}
   ```

For the Playwright backend, add the dependency and let it fetch its own
browsers — see
[Browser Backend Configuration](INSTALL-TIERS.md#browser-backend-configuration).

## Cheap early gates: formatting and dry-run

Two fast jobs catch most breakage before any browser launches:

```bash
# Parse + canonical-formatting check on every .feature file.
# Exit 0 = clean, 1 = invalid/unformatted, 2 = no files found.
sl fmt --check features/

# Bind every step without executing — undefined steps and config
# errors surface here as a planning failure (exit 2).
sl run features/ --step-paths steps/ --dry-run
```

Both run in seconds on a bare JRE image — worth a first pipeline stage so the
browser job never starts on a suite that can't bind.

## Parallelism: inside the job, not across jobs

`--max-parallel N` runs up to N scenarios concurrently **within one `sl`
process**, with results and console output identical to a sequential run;
`@serial`, costume-wearing, and shared-interface scenarios run alone. This is
the supported way to speed up a suite in CI.

CI-level fan-out (GitLab `parallel:`, Actions `matrix:`) is a different axis:
ShiftLefter has no built-in suite sharding, so N parallel jobs would each run
the whole suite. If you must split across jobs, partition explicitly with
`--tags` / `--skip-tags` and give each job its own `--junit-xml` path.

## Concurrent invocations against one project

Two simultaneous `sl` runs against the **same project** are not a supported
pattern. What actually happens:

- **Warm (the default): the second run is rejected.** The daemon executes one
  invocation at a time per project; a second call while one is in flight exits
  2 with the structured error `:daemon/busy` (stderr one-liner; under `--edn`,
  the standard exit-2 planning summary carries the error type). Retry when the
  first run completes — nothing queues. See [docs/daemon.md](daemon.md)
  § One run at a time.
- **Cold or mixed (`--no-daemon`, `SL_NO_DAEMON`, separate machines sharing a
  checkout): at your own risk.** Timestamped run directories are
  collision-safe by design (atomic creation, unique fallback), and fixed-path
  artifacts (`--html`, `--junit-xml`) are written atomically — a reader never
  sees a torn file — but two concurrent runs still race last-writer-wins on
  those fixed paths, and your own subject-under-test (fixture ports, app
  state) collides in ways ShiftLefter cannot see.
- **Costume mode is strictly single-occupant.** A costume attaches to one
  Chrome on a fixed debug port; two runs puppeting the same browser interleave
  clicks and navigation into nonsense. Never run two costume-wearing
  invocations at once — the warm reject protects you within one project, but
  nothing can if two checkouts share a costume.

Distinct projects (including separate git worktrees) are fully isolated — each
gets its own daemon; see [docs/daemon.md](daemon.md).
