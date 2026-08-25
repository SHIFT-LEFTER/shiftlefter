# Scenario Lifecycle Hooks

Hooks are the escape hatch for lifecycle code around a scenario — seeding or
resetting external state before it runs, capturing a screenshot or scraping a
console after it. Prefer the stronger mechanisms when they exist (a step, the
`setup.clj` orchestration for run-level lifecycle); reach for hooks when the
work genuinely belongs to the scenario boundary.

## Naming: the feature file states what runs around it

Scenarios NAME their hooks with `@hook=<name>` value-tags. Nothing fires
invisibly: for any scenario, "which hooks fire, in what order" is answerable
from the feature file plus one registry file, and `sl run --dry-run` prints
the resolved per-scenario firing list.

```gherkin
@hook=reset-db
Scenario: Purchase with a fresh account
  Given alice opens the browser to 'https://shop.example.com'
```

Tag inheritance follows Gherkin: a feature-level `@hook=` applies to every
scenario in the file; Rule- and Examples-block-level tags nest the same way.
The same hook named at two levels runs once, at the outermost position.
An `@hook=` naming an unknown hook is a planning error (exit 2) reporting
the tag's file and line. Unrecognized value-tag keys (`@foo=bar`) are
ordinary inert tags.

## Registration: hooks.clj

Registrations live in `hooks.clj` next to your `shiftlefter.edn` (both in
`sl/`; discovered
exactly like `setup.clj`; no file = no hooks). It declares `(ns hooks)` and
defines `hooks` — an ordered vector, or a `(fn [config])` returning one:

```clojure
(ns hooks)

(def hooks
  [{:name "audit"                       ;; required, unique
    :global? true                       ;; applies to every scenario, visibly
    :before (fn [{:keys [ctx scenario]}]
              nil)}
   {:name "reset-db"
    :requires-serial true               ;; auto-serialize carrying scenarios
    :before (fn [{:keys [ctx scenario]}]
              {:seed/user-id 1234})     ;; map return merges into ctx
    :after  (fn [{:keys [ctx scenario result]}]
              (when (= :failed (:status result))
                ;; capabilities are still live here (before cleanup)
                nil))}])
```

- `:name` (required, unique) — what `@hook=<name>` resolves against.
  Duplicate names are a planning error.
- `:before` / `:after` — optional; either or both. A pair registered under
  one name unwinds as one frame.
- `:global?` — runs for every scenario, stamped and previewed like any named
  hook; vector order is the execution order among globals.
- `:requires-serial` — scenarios this hook applies to run exclusively under
  parallel execution; reports show the derived `@serial` annotation with the
  hook named as the reason.
- `:provides` — optional vector of binding names this hook's `:before` seeds
  into the scenario data plane. A binding name is a bare keyword, a letter
  followed by letters/digits (lowerCamel by convention, e.g.
  `[:sessionToken]`). A static declaration: it satisfies dry-run's
  consumed-without-producer check for `{name}` tokens. Contribution keys
  fitting that shape mirror into `:sl/bindings` at execution whether
  declared or not.

## Execution order

Per scenario: **Befores → capability provisioning → steps → Afters →
capability cleanup.**

- Befores run outermost-first: globals (registry order), then `@hook=` tags
  in feature → rule → scenario → examples nesting order.
- Befores run BEFORE provisioning — a broken seed hook fails before paying
  browser-launch cost. Befores therefore see no capabilities.
- Afters run AFTER steps but BEFORE cleanup — a screenshot or console-scrape
  hook can reach the live browser via `:ctx`.
- Afters unwind in strict LIFO order of the Befores that ran. If provisioning
  itself fails, every After still unwinds — WITHOUT capabilities, so an After
  must check for the capability it wants, not assume it.

## Payloads and failure semantics

- `:before` receives `{:ctx ... :scenario ...}` (scenario = name, tags,
  source file/line). A map return merges into ctx — the sanctioned way to
  hand data to steps; the report records the contributed keys. nil = no
  contribution.
- `:after` receives `{:ctx ... :scenario ... :result ...}` where `:result`
  is the in-flight scenario result (status, per-step results with durations,
  error). On-failure conditionality is a plain `when` on
  `(:status result)` — there is no separate on-failure hook kind. The
  return is the ATTACHMENTS CLAIM: nil (no claim) or
  `{:attachments [{:attachment/kind :screenshot :attachment/path "..."} ..]}`
  — refs to files the hook wrote, which then ride the scenario envelope and
  render in the human report surfaces (HTML, JUnit, console; the `--edn`
  summary carries no refs yet). The contract is full-strict: any other return
  (a map with extra or misspelled keys, a malformed ref, a non-map value)
  is `:hook/invalid-return` → scenario `:error`. A claimed file that does
  not exist keeps the scenario outcome but the ref is marked
  `:attachment/error` and a stderr warning fires — evidence loss is never
  silent.
- Both payloads carry `:artifacts-dir` (an absolute path, pre-created)
  when the run has a results directory — write attachment files there and
  claim them; refs inside the run directory render inline/portable in the
  HTML report. The key is absent (not nil) in runs without a results
  directory (no config file) — check, don't assume.
- Before writing a screenshot/console/page-source After hook: DON'T — set
  the `[:runner :capture]` policy instead (see the CLI overview's
  `--capture` entry). Hooks are for one-off evidence the framework
  doesn't own (DB dumps, API traces); evidence owned by a capability you
  provision can instead become a custom capture KIND — a `:capture` entry
  on your setup.clj adapter registry, policy machinery included (worked
  example: `examples/07-custom-capture-kind/`). With `:capture` console
  active, the framework
  owns the destructive console drain — a hook reading the browser's log
  API gets leftovers; read the captured artifact, or use `:on-failure`
  (whose single post-steps drain fires before any After).
- A `:before` throw makes the scenario `:error` (never `:failed`), skips its
  steps, and unwinds only the frames whose Befores succeeded. A `:after`
  throw makes the scenario `:error` even when all steps passed — broken
  cleanup is a lying suite. One failing After does not stop the unwind.
- A `:before` returning anything other than a map or nil is a hook error
  (`:hook/invalid-return`) with the same shape: scenario `:error`, steps
  skipped. Note the entry gets NO unwind frame even though its body fully
  ran — its own `:after` will not run, so side effects the body made before
  the bad return are not unwound by it.
- Every hook that runs is stamped in the scenario envelope and rendered in
  JUnit system-out and the HTML transcript:
  `{:name .. :phase .. :status .. :duration-ms .. :contributed [..]}`.

## Reaching external systems

A hook that needs a library beyond the bundled classpath (the flagship case:
a JDBC driver for a database-resetting `:before`) gets it by dropping the
library's jar — and its dependencies' jars — into the project's `sl/lib/`
directory. Everything in `sl/lib/*.jar` rides the classpath of every run,
cold and warm alike (the daemon restarts itself when the lib set changes).
No build tooling: bring resolved jars, not coordinates.

Shelling out — `clojure.java.shell/sh` to `psql`, `curl`, an internal CLI —
remains an option when a subprocess is genuinely the right tool; it is no
longer the only route to an external system.
