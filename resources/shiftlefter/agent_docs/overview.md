# ShiftLefter Agent Overview

This is packaged agent doctrine for ShiftLefter: concise operating guidance an
agent can read straight from the release artifact, offline, without the project's
private docs. Each topic is a focused subread; `sl agent-doc --list` names them.

ShiftLefter turns Gherkin requirements into executable checks. The stable loop is:
author accepted vocabulary, write feature steps against that vocabulary, validate,
then run. Do not silently invent accepted project truth.

Core commands:

- `sl agent-doc --list` lists packaged doctrine topics.
- `sl agent-doc <topic>` prints one topic.
- `sl orient` orients you in a project: resolved context, what would fail
  (static validation), validate/run commands, and pointers to these topics.
- `sl doctor` probes the MACHINE (orient's machine-side counterpart):
  chromedriver↔Chrome version lockstep, `~/.shiftlefter/config.edn` validity,
  extra-dependency adapters present when configured, daemon health, orphaned
  chromedriver processes, `sl/lib` jar sanity. `sl doctor --list` prints
  exactly what is and isn't checked; `--edn` is machine-readable. Exit 0 =
  healthy, 1 = issues found. When runs fail for machine-shaped reasons
  (browser won't start, versions drifted), run doctor before debugging.
- `sl glossary` lists the RESOLVED vocabulary (subjects, verbs, objects) with
  per-entry provenance; `sl explain <name>` resolves one name — kind, def
  site, frames, per-interface bindings including missing ones. See the
  `resolution` topic.
- `sl run <path>` executes features.
- `sl run <path> --dry-run` binds every step without executing — the cheap
  validation pass to run before a real browser run.
- `sl run <path> --tags TAGS` / `--skip-tags TAGS` runs a tagged subset
  (comma-separated, repeatable; `@` optional; include is OR, exclude wins;
  feature/Rule/Examples-block tags inherit per Gherkin). Filtering happens at
  planning time: deselected scenarios are never bound, and counts/JUnit/EDN
  reflect only the selection. `--dry-run` plus a filter previews the selection
  (console appends `; M filtered out by tags`; EDN gains a `:filtered-out` key).
- `sl run <path> --junit-xml PATH` also writes a CI-ingestible JUnit XML report
  (config mirror `[:runner :report :junit-xml]`; the flag wins). It rides
  alongside console/EDN. In a single-group run the XML holds a
  `<failure>`/`<error>` iff the run's exit code is nonzero, and a planning
  error (exit 2) writes no file; in a multi-group run (setup.clj, N>1) the
  file covers only the groups that ran and can be all green beside exit 2 —
  so gate CI on the process exit code, not the report's presence or
  greenness (see ERRATA E009).
- `sl run <path> --html PATH` also writes a self-contained HTML run report
  (config mirror `[:runner :report :html]`; the flag wins). One file, no
  external requests — open it via double-click/`file://`, attach it to CI
  artifacts. Its embedded EDN data island (script type `application/edn`) is
  machine-readable: the full run-ctx + per-scenario envelopes, greppable and
  `edn/read-string`-able straight out of the file. Renders with JavaScript;
  without JS the island is still readable via view-source. Very large runs
  (thousands of scenarios) build a big DOM and will scroll sluggishly.
- `sl run <path> --capture kind=policy,…` captures artifacts into the run's
  results directory (config mirror `[:runner :capture]`; the flag wins as a
  whole value). Kinds are adapter-contributed — the WebDriver backend
  (`:etaoin`, the default) provides `screenshot`, `console`, `page-source`;
  the Playwright backend provides `screenshot` and `page-source` but not
  `console` (no browser-side buffer to drain there — Playwright console is
  listener events; the subscription-shape design is sl-lam0). A configured
  kind supported by none of a group's adapters gets a per-group plan-time
  warning (`:capture/adapter-unsupported`, visible in dry-run previews);
  policies are `never` (default),
  `on-failure` (fires at the failing step's instant, capturing ALL live
  browsers), `every-step` (the film strip: one capture per step, following
  the acting subject; no-SVO steps capture all live). Console is a
  destructive drain: per-step under `every-step` (each file holds exactly
  that step's entries, plus a final tail sweep), once post-steps when red
  under `on-failure`; Chrome/Edge only, other drivers no-op. Refs render as
  attachments in the human report surfaces (HTML, JUnit, console); the
  `--edn` summary carries no refs yet. A capture-infrastructure failure on an
  otherwise-green scenario flips it to `:error`, capture-attributed —
  check `:data {:kind … :capability …}` to triage "all N errors are
  capture-attributed; ship, fix harness". Capture time never pollutes step
  durations. Dry runs and config-less runs never capture.
- `sl run <path> --max-parallel N` runs up to N scenarios concurrently (config
  mirror `[:runner :max-parallel]`; the flag wins; default 1 = sequential).
  Results and console output are identical to a sequential run — only
  wall-clock changes. Scenarios tagged `@serial` are exclusive: they run one
  at a time after the parallel batch (a feature-level `@serial` marks each
  scenario individually, NOT the feature as an atomic block). Scenarios that
  wear costumes or touch a shared-impl interface are auto-serialized the same
  way (a notice line says so).
- `sl fmt --check <path>` checks that features parse and match canonical formatting.
- `sl verify` is a framework-development tool: in a consumer project it prints a
  notice to stderr and exits 2 (the un-runnable-invocation code — never a silent
  green in CI). Use `sl orient` and `--dry-run` to validate a project.

`sl orient --edn` dumps the entire project projection as one EDN value — the
machine-readable feed for a planning or disposable sub-agent reasoning over the
project's whole shape (coverage, negative space) rather than editing it.

Costume custody (projects using `:wears`): the wardrobe
(`.shiftlefter/wardrobe/`) holds live credentials — a costume is as sensitive
as the user's own browser profile. It is gitignored; never commit, copy, or
surface its contents, and it should be excluded from sync/backup tools. While
a costume is worn, its session is drivable by any local same-user process
(attachment is Chrome's debug port — structural). Recommend `sl costume
destroy` for costumes no longer in use.

Read next:

- `sl agent-doc authoring`
- `sl agent-doc grammar`
- `sl agent-doc vocabulary`
- `sl agent-doc examples`
- `sl agent-doc builtins`
- `sl agent-doc locators`
- `sl agent-doc diagnostics`
- `sl agent-doc sieve`
