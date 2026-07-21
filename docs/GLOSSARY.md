# ShiftLefter Glossary

Reach for this as a **decoder**, not a tutorial. You probably arrived from a link
in another page, or you're reading the docs with this open alongside to look a
term up as you hit it. It's organized for lookup, not front-to-back reading — to
*learn* ShiftLefter, start with the [getting-started guide](browser-getting-started.md).

**Looking to *author* your project's glossary** (the `glossary/` EDN files that
declare your subjects, verbs, and intents)? That's [SVO.md](SVO.md) — this page
only defines terms.

## Where richer terms live

- **SVO, subjects, verbs, objects, intent references, enforcement** → [SVO.md](SVO.md)
- **Costumes, wardrobe, `:wears`, sessions** → [COSTUMES.md](COSTUMES.md)
- **Install tiers, browser backends, bundled libraries** → [CAPABILITIES.md](CAPABILITIES.md)

Interfaces, adapters, and capabilities are defined just below.

## Cucumber / Gherkin terms

Standard Gherkin vocabulary, defined by the Cucumber project — see the
[official Gherkin reference](https://cucumber.io/docs/gherkin/reference/). The
parser is 100% Gherkin-compatible, so these mean what they mean upstream.

| Term | Meaning |
|---|---|
| **Background** | Steps run before each scenario in a feature. Shared setup. |
| **Data Table** | Pipe-delimited tabular argument to a step. |
| **Docstring** | Multi-line text argument, delimited by `"""` or ` ``` `. |
| **Examples** | Value table driving Scenario Outline substitution. |
| **Rule** | Keyword grouping scenarios under one business rule. |
| **Scenario Outline** | Parameterized scenario template; N examples → N scenarios. |
| **Tag** | Metadata marker (`@wip`, `@slow`). Parsed and preserved; `sl run --tags` / `--skip-tags` selects a tagged subset at planning time. `@serial` marks a scenario as exclusive under parallel runs. Value-tags (`@<key>=<value>`) attach machinery by name: `@hook=<name>` runs the named lifecycle hook from `hooks.clj` around the scenario (unknown hook names are planning errors; unrecognized keys are ordinary tags). |

## Modes

- **Shifted** — the normal ShiftLefter mode: extensions on (macros, SVO
  validation, intent references). Turned on by an `:svo` block in
  `shiftlefter.edn` — explicit configuration, never auto-detected from file
  contents.
- **Vanilla** — plain Gherkin with no ShiftLefter extensions. Technically
  supported, but Shifted is the only path ShiftLefter advertises and supports
  day-to-day; reach for Vanilla only if you have a specific reason to.

Validation strictness is **fine-grained**, not a global switch: each check in the
`:svo` map (`:unknown-subject`, `:unknown-verb`, `:unknown-object`,
`:unknown-interface`) is independently `:warn`, `:error`, or `:off`. See
[SVO.md](SVO.md) for the enforcement model.

## Core vocabulary

- **ShiftLefter (SL)** — the framework: a Gherkin-compatible parser, runner,
  reporter, and optional Shifted extensions.
- **Parser / Stepengine / Runner / Reporter** — the four component boundaries.
  The parser turns text into structure; the stepengine binds steps to definitions
  and validates SVO; the runner orchestrates execution and emits events; the
  reporter turns events into output. (You'll see these names in diagnostics.)
- **Pre-expansion AST** — the Pass-1 syntax tree that preserves the source
  faithfully (whitespace, comments, locations) and drives byte-for-byte
  formatting.
- **Pickles** — execution-ready, macro-expanded representations of scenarios; not
  reversible to the original source.
- **Macro** — a domain-level step ending in ` +` (space-plus) that expands to a
  predefined sequence of steps during compilation. Macros keep **provenance** —
  every expanded step traces back to the original ` +` line.
- **shiftlefter.edn** — the single project configuration file (parser, runner,
  glossaries, interfaces, modes).
- **Lifecycle hooks / `hooks.clj`** — scenario Before/After work, registered
  in `hooks.clj` (a sibling of `shiftlefter.edn`) and named per-scenario with
  `@hook=<name>` tags. Read [hooks.md](hooks.md) before writing one.
- **Named bindings (the scenario data plane)** — values captured by named
  regex groups (`(?<code>\d{6})`) during a scenario, stored at
  `:sl/bindings`, and consumed as `{code}` in later step text — including
  across interfaces. Worked examples:
  [across-interfaces.md](across-interfaces.md).

## Interfaces, adapters, capabilities

The runtime model behind a step actually doing something:

- **Interface** — a named slot in `shiftlefter.edn` (`:web`, `:sms`) that a step
  targets. Its `:type` selects the verb vocabulary; its `:adapter` selects the
  backend. See [SVO.md](SVO.md#interface-name-vs-type).
- **Adapter** — the backend that does an interface's work: `:etaoin` / `:playwright`
  for browsers, `:sms-twilio` / `:sms-mock` for SMS.
- **Capability** — the live, materialized implementation of an interface inside a
  running scenario (stored as `:cap/<interface>`), **auto-provisioned** the first
  time a subject acts over that interface.
- **Intent reference** — a semantic element name like `Login.submit` (defined in
  `glossary/intents/`) used in place of a brittle selector; validated as the
  **Object** of a step. See [SVO.md](SVO.md#object-validation-against-intent-regions).

## Internals

> Implementation-level terms you might meet in the API docs, diagnostics, or a
> stack trace. You don't need any of these to write features — they're here so the
> names are decodable. Method-level detail lives in the [API docs](api/index.html).

- **IBrowser** — the browser protocol behind every browser step. Two
  interchangeable adapters back it: `:etaoin` (bundled WebDriver) and
  `:playwright` (user-supplied). Same protocol, same feature files — switch with
  one config line.
- **CostumeBrowser** — an `IBrowser` wrapper that transparently reconnects when a
  costume's browser session drops.
- **IEventBus** — the publish/subscribe seam the runner emits lifecycle events on.
- **ISessionStore** — the persistence seam for session handles.
- **Session handle (SessionId)** — an ephemeral WebDriver session identifier; not
  persisted (a costume reconnect creates a fresh one).
- **Virtual lines** — a reporter device for displaying expanded macro steps (line
  5 shows as 5.1, 5.2, …); display-only, not stored in the AST or pickles.

## On the roadmap

Not yet shipped — named here only so the terms are recognizable when you meet
them: **observation lineage** (tracing real-world requirements through to running
tests) and a **hosted traceability graph**. Direction, not current capability.
