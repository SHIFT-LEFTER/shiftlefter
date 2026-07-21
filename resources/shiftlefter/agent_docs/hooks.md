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
  Given alice opens the shop
```

Tag inheritance follows Gherkin: a feature-level `@hook=` applies to every
scenario in the file; Rule- and Examples-block-level tags nest the same way.
The same hook named at two levels runs once, at the outermost position.
An `@hook=` naming an unknown hook is a planning error (exit 2) reporting
the tag's file and line. Unrecognized value-tag keys (`@foo=bar`) are
ordinary inert tags.

## Registration: hooks.clj

Registrations live in `hooks.clj` next to your `shiftlefter.edn` (discovered
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
- `:provides` — optional vector of binding names (bare lowerCamel keywords,
  e.g. `[:sessionToken]`) this hook's `:before` seeds into the scenario data
  plane. A static declaration: it satisfies dry-run's
  consumed-without-producer check for `{name}` tokens. Conforming
  bare-lowerCamel contribution keys mirror into `:sl/bindings` at execution
  whether declared or not.

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
  return value is ignored (reserved).
- A `:before` throw makes the scenario `:error` (never `:failed`), skips its
  steps, and unwinds only the frames whose Befores succeeded. A `:after`
  throw makes the scenario `:error` even when all steps passed — broken
  cleanup is a lying suite. One failing After does not stop the unwind.
- Every hook that runs is stamped in the scenario envelope and rendered in
  JUnit system-out and the HTML transcript:
  `{:name .. :phase .. :status .. :duration-ms .. :contributed [..]}`.

## Reaching external systems

In this release a hook that must touch an external system (reset a database,
call an internal CLI) shells out — `clojure.java.shell/sh` — or uses what is
already on the framework classpath. Adding project dependencies onto the
hook classpath is a planned extension, not a current feature.
