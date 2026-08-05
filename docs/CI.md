# Running ShiftLefter in CI

Wire `sl run` into a CI pipeline: JUnit XML into your CI's test widget,
tag-filtered subsets for merge-request pipelines, and exit codes designed for
gating. Everything on this page is Tier A — a Java 21+ image and the `sl` CLI,
no Clojure toolchain (see [What can I do?](INSTALL-TIERS.md)).

The worked examples target GitLab CI and GitHub Actions, but the pieces —
install, run, collect the report, gate on the exit code — translate to any CI
system that can run a shell command.

## The one rule: gate on the exit code, not the report file

A planning failure — undefined steps, parse errors, config errors, no features
found — exits **2** and writes **no JUnit file at all**. A job that keys only
on the report's presence (or swallows the exit code with `|| true`) will read
that missing report as green. So:

- Let `sl run`'s exit code fail the job. Don't append `|| true`, and don't
  `allow_failure` the test job.
- Treat the JUnit report as *display*, not as the gate.

The report never contradicts the exit code: the XML contains at least one
`<failure>`/`<error>` exactly when the run exits nonzero. `sl run`'s codes:

| Code | Meaning |
|------|---------|
| 0 | All scenarios passed (or pending allowed via config) |
| 1 | One or more scenarios failed or errored (a lifecycle hook threw), or pending steps when not allowed |
| 2 | Planning failure (undefined steps, parse errors, config errors, no features found) — **no JUnit file is written** |
| 3 | Runner crash (unexpected exception) |

Canonical table for all commands: [README → Return Codes](../README.md#return-codes).
Background on the no-file decision and the JUnit format itself:
[ERRATA E009](../ERRATA.md#e009-junit-xml-has-no-official-spec--we-target-the-consumer-subset).

The HTML report behaves differently on exit 2 (since 0.5.4): late planning
failures — undefined or ambiguous steps, arity mismatches, a hooks error —
refresh the configured `--html` path with a PLANNING FAILED report, so that
path never shows a previous run's green for those exits. Earlier exits
(config errors, parse failures, setup plan-shape errors, a group's `:start`
failing) still write nothing, and a `report.html` from a prior run survives
them untouched — one more reason the exit code, not any report file, is the
gate.

## Consuming `--edn` output

With `--edn`, stdout carries a **sequence of EDN forms**, not always exactly
one: one run summary per suite (per *group*, under `setup.clj`
orchestration), plus occasional small supplementary diagnostics forms
(`{:run/id … :diagnostics {…}}` — e.g. a setup group's teardown failure, or
a prior run's dirty-teardown warning at startup). Read **all** forms and
**skip shapes you don't recognize** — the form vocabulary grows by release,
and unknown forms are never an error. Summaries are the forms carrying
`:run/exit-code`.

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

`--html` still writes exactly where you point it: that fixed path now
receives an end-of-run byte copy of the report (the last group's, under
`setup.clj`), so CI configs and agents that grab a known path keep
working unchanged — but images in that copy degrade to labeled text
(the files live in the run directory, not beside the copy).
One honesty note for multi-group runs: the fixed path holds whichever
copy was written last, so a later group's report overwrites an earlier
group's — including an earlier group's PLANNING FAILED page; the run
directory keeps every group's truth.
`--junit-xml` is unchanged; its `[[ATTACHMENT|…]]` lines use absolute
paths into the run directory. Planning failures (exit 2) still write
only the fixed `--html` path and create no run directory.

In CI, archive the whole `results/<stamp>/` directory when your suite
captures attachments; the fixed `--junit-xml`/`--html` paths alone are
enough only for attachment-less runs. The usual CI capture line is
`:runner {:capture {:screenshot :on-failure :console :on-failure}}` —
default is `:never` everywhere, so capture never surprises a pipeline
that didn't ask for it. A capture-infrastructure failure on an
otherwise-green scenario flips it to `:error` (capture-attributed in the
report) — a green gate whose promised evidence vanished would be lying.

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
  | bash -s -- --version 0.5.4 --no-breadcrumb
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
  key: "sl-0.5.4"
  paths: [sl/]

.install-sl: &install-sl
  - apt-get update -qq && apt-get install -y -qq --no-install-recommends curl unzip
  - '[ -x sl/sl ] || curl -fsSL https://raw.githubusercontent.com/SHIFT-LEFTER/shiftlefter/main/release/install.sh | bash -s -- --version 0.5.4 --no-breadcrumb'

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
            | bash -s -- --version 0.5.4 --no-breadcrumb
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
