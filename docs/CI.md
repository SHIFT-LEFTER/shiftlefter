# Running ShiftLefter in CI

Wire `sl run` into a CI pipeline: JUnit XML into your CI's test widget,
tag-filtered subsets for merge-request pipelines, and exit codes designed for
gating. Everything on this page is Tier A — a Java 11+ image and the `sl` CLI,
no Clojure toolchain (see [What can I do?](CAPABILITIES.md)).

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
| 2 | Planning failure (undefined steps, parse errors, config errors, no features found) — **no report file is written** |
| 3 | Runner crash (unexpected exception) |

Canonical table for all commands: [README → Return Codes](../README.md#return-codes).
Background on the no-file decision and the JUnit format itself:
[ERRATA E009](../ERRATA.md#e009-junit-xml-has-no-official-spec--we-target-the-consumer-subset).

## Picking an image

Any image with **Java 11 or later** runs ShiftLefter — a JRE is enough, e.g.
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
  | bash -s -- --version 0.5.1 --no-breadcrumb
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
  key: "sl-0.5.1"
  paths: [sl/]

.install-sl: &install-sl
  - apt-get update -qq && apt-get install -y -qq --no-install-recommends curl unzip
  - '[ -x sl/sl ] || curl -fsSL https://raw.githubusercontent.com/SHIFT-LEFTER/shiftlefter/main/release/install.sh | bash -s -- --version 0.5.1 --no-breadcrumb'

smoke:
  stage: test
  rules:
    - if: $CI_PIPELINE_SOURCE == "merge_request_event"
  script:
    - *install-sl
    # The exit code is the gate — no `|| true`, no allow_failure.
    - ./sl/sl run features/ --step-paths steps/ --tags @smoke --junit-xml report.xml
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
    - ./sl/sl run features/ --step-paths steps/ --junit-xml report.xml
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
            | bash -s -- --version 0.5.1 --no-breadcrumb
      - name: Run features
        # PRs run the @smoke subset; main runs everything.
        run: |
          ./sl/sl run features/ --step-paths steps/ --junit-xml report.xml \
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
   ./sl/sl run features/ -c shiftlefter.ci.edn --junit-xml report.xml
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
[Browser Backend Configuration](CAPABILITIES.md#browser-backend-configuration).

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
