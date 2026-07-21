# Changelog: 0.5.2

**Release Date:** 2026-07-21

---

**ShiftLefter 0.5.2 is the standard-test-features release: scenario lifecycle hooks, a scenario data plane that carries captured values across steps and interfaces with zero custom code, named locations with region-vs-exact URL assertions, and configs that warn instead of silently shrugging.**

v0.5.1 made `sl run` a CI citizen. v0.5.2 fills in the features every test suite eventually reaches for — setup/teardown, passing a value from one step to a later one, asserting where the browser landed — each built the ShiftLefter way: declared in the feature file, checked at planning time, previewable with `--dry-run`.

<!-- no-entry: sl-qzhn (internal Java-floor fix — hand-rolled named-group scanner replaces a Java 20+ API; no user-visible change) -->
<!-- no-entry: sl-22h8 (internal: dead helper deleted) -->
<!-- no-entry: sl-i9hn (research/falsifier — no shipped surface) -->
<!-- no-entry: sl-hx8j (audit bead) -->
<!-- no-entry: sl-wwuq (test hygiene: run-to-run assertion-count variance; no user surface) -->
<!-- no-entry: sl-beag (folded into the sl-esq hooks entry — docs/hooks.md) -->
<!-- no-entry: sl-lhsn, sl-zy5r, sl-asam (release-process beads: Herald pass, Warden delta-pass, cut & tag) -->
<!-- no-entry: sl-anru (internal: the changelog drift-guard test itself; no user surface) -->
<!-- The no-entry allowlist above is the sl-anru convention: every closed 0-5-2
     bead must appear either as a changelog entry (with an adjacent
     `entry:` marker comment) or here, machine-enforced by
     test/shiftlefter/changelog_guard_test.clj. -->

## What's New

<!-- entry: sl-esq -->
### Scenario lifecycle hooks

Scenarios can now name the Before/After work that runs around them — and the
feature file says so, right where you read it:

```gherkin
@hook=reset-db
Scenario: Seeded scenario
```

Registrations live in `hooks.clj`, a sibling of your `shiftlefter.edn`
(discovered exactly like `setup.clj`): an ordered vector of named maps, each
with optional `:before` and `:after` fns; `:global? true` applies a hook to
every scenario. Befores run *before* capability provisioning — a broken seed
hook fails before you pay for a browser launch. Afters run after the steps
but *before* cleanup, so a screenshot-on-failure hook still reaches the live
browser. The unwind is LIFO, try-with-resources style: Afters run in reverse
order of the Befores that succeeded, and one failing After never stops the
rest.

Hook failure is loud (see `:error` under Behavior Changes), every hook that
ran is stamped into the scenario envelope under `:hooks`, and `--dry-run`
prints each scenario's firing list — console and EDN — without running
anything. A hook can also declare `:requires-serial` (the carrying scenario
auto-serializes under `--max-parallel`) and `:provides` (binding names it
contributes, feeding the data plane's static check below).

Before writing one, read `docs/hooks.md` — *Hooks, and When Not to Use
Them*. Hooks are the lowest rung of a ladder; that page routes the classic
hook use-cases to their stronger mechanisms. Full mechanics reference:
`sl agent-doc hooks`.

<!-- entry: sl-yh7 -->
### Scenario data plane: named bindings

A scenario can carry a value from the step where it's born to the step that
needs it — across interfaces — with no custom code. Name a regex group where
the value appears; reference it as `{name}` where it's consumed:

```gherkin
And [:sms] :user/alice receives a message matching /code is: (?<code>\d{6})/
And :user/alice fills Login.code with {code}
```

Named groups in a matcher bind into the scenario's data plane
(`:sl/bindings`); `{name}` tokens in value, location, and matcher slots
resolve against it. Two rules keep it predictable:

- **Quoted is always literal.** `'{code}'` is the six-character text
  `{code}`, never a lookup — the same rule as every other slot.
- **A binding is a value, never a regex fragment.** A `{name}` consumed
  inside a matcher is regex-quoted before the pattern compiles.

The flow is checked statically at planning time: consuming a `{name}` no
earlier step or hook produces is a planning error with a did-you-mean, and a
matcher whose groups are all unnamed gets a notice (it binds nothing).
`--dry-run` previews all of it without touching an interface.
`docs/across-interfaces.md` has the worked cross-interface examples,
including the magic-link pattern — capture a URL out of a message, then
`opens the browser to {resetLink}`.

<!-- entry: sl-zgna -->
### Web capture builtin

The step that makes the data plane general rather than an SMS feature:

```gherkin
And :user/alice captures Checkout.confirmation matching /Order (?<orderNumber>[A-Z0-9-]+)/
```

Capture is assert-plus-bind with should-see semantics: it polls the element
like a `should see`, and no match is a step failure — it fails as though you
could not see that text. With it, the 2FA example's 14-line custom handoff
stepdef is deleted; `examples/04-sms-2fa` now runs on zero custom steps.

<!-- entry: sl-3jr4 -->
### Named locations

An intent may now declare its own address: `:location {:web {:path "/login"}}`
— the semantic PATH lives on the intent, the environmental HOST lives in the
interface config as `:base-url`. `opens the browser to Login` and
`should be on Login` resolve the bare intent name; quoted literal URLs keep
working. Strict SVO validation catches typo'd names with a did-you-mean.

<!-- entry: sl-q81m -->
### `should be on exactly`

A second location-assertion frame for when every part of the URL matters:
full-URL structural equality — path and fragment exact, query compared as a
multimap (cross-key parameter order is insignificant, duplicate-key value
order is significant). The expectation is a quoted literal or a captured
`{binding}` token — `should be on exactly {resetLink}` asserts the browser
landed on precisely the URL captured earlier in the scenario (the magic-link
pattern). Named-location refs stay region-only (`should be on Feed`).

<!-- entry: sl-hlkz -->
### Config lint

Config problems now make noise. An unknown top-level config key, or a known
key at the wrong nesting level, prints a warning to stderr at load time:

```
Config warning: config key :step-paths is not read at the top level and was ignored — did you mean [:runner :step-paths]? [shiftlefter.edn]
```

Warnings only, never errors — a newer config on an older version still runs,
and the exit code is never affected. `sl orient` surfaces the same lints as
`:warn` diagnostics. (An example config once carried a top-level
`:step-paths` for its entire life and nothing ever said so; that class of
silent shrug is gone.)

<!-- entry: sl-lnj1 -->
In machine mode the diagnostics ride the data instead of stderr: the
runner's `--edn` summary carries them under `[:diagnostics :config-lints]`
(under `:planning` on a planning failure) — each a map of
`:type`/`:key`/`:message`, plus `:suggested-path` when there's a better
nesting. Additive and absent on clean runs: existing consumers see
byte-identical output.

<!-- entry: sl-gh75 -->
### CI integration guide

`docs/CI.md` assembles the pipeline story in one place: worked GitLab CI and
GitHub Actions examples, image and install guidance, headless-browser setup,
pending-scenario policy, and the one rule — gate on the exit code, not the
report file.

## Breaking Changes

<!-- entry: sl-yh7 -->
- **`:sms/captures` is removed from the scenario context.** The SMS receive
  matcher no longer stores positional groups at `[:sms/captures :groups]`;
  a custom stepdef that reads that key will find it absent. Name the groups
  instead — `(?<code>\d{6})` — and consume `{code}` directly in step text,
  or read `:sl/bindings` from the stepdef. The machinery keys
  (`:sms/last-message`, `:sms/last-receive-ts`) and the poll/timeout
  behavior are unchanged.

## Behavior Changes

<!-- entry: sl-esq -->
- **New `:error` scenario status: a failing hook turns the run red as an
  infrastructure failure, distinct from a step failure.** A Before that
  throws marks the scenario `:error` and skips its steps; an After that
  throws marks it `:error` even when every step passed — broken cleanup is
  a lying suite. The exit code is 1, like any red run (never 2, which stays
  reserved for planning failures). JUnit XML renders these as `<error>` —
  not `<failure>` — with hook attribution, and console, EDN, and the HTML
  report gain an error count. Additive when absent: hook-less runs keep the
  four-key counts map and byte-identical output.
- **`should be on` is now a region assertion, not a substring test.** It
  compares the normalized path (plus fragment) of the browser URL against the
  expectation and ignores the query string and host. Path-only expectations
  like `'/secure'` behave as before; expectations that relied on substring
  matching of hosts or query strings (`'example.com/dash'`, `'?tab=home'`)
  must switch to a path form or to `should be on exactly '<url>'`.
  Percent-encoding normalization, host-case rules, and byte-exact matching
  are deliberately out of scope — write a custom step assertion for those.
<!-- entry: sl-iseq -->
- **Quoted values never classify as refs in location slots.** One rule across
  the authored surface, matching element slots: quoted = literal, always;
  a bare PascalCase token = named-location ref. (`opens the browser to Feed`
  resolves the `Feed` intent; `opens the browser to 'Feed'` navigates to the
  literal string `Feed`.)

## Bug Fixes

<!-- entry: sl-ka80 -->
- **`should be on exactly '{token}'` no longer resolves the quoted text.**
  The at-exactly pattern captured inside its quotes, so a quoted `'{myUrl}'`
  reached the engine's capture normalization already unquoted, resolved as a
  binding token (violating quoted = literal, always), and produced an
  unparseable expected URL; the static lint also mis-registered the quoted
  text as a binding consumption. The slot now captures whole like every
  other value/location slot: quoted stays literal, and the same change is
  what admits the bare `{token}` consumption described under
  `should be on exactly` above.

<!-- entry: sl-uu7x -->
- **Partial provisioning failure no longer leaks browser processes.** When
  scenario-start provisioning failed partway (Alice's browser up, Bob's
  failed), capabilities provisioned before the failure were invisible to
  scenario-end cleanup — the adapter's `:cleanup` never ran and a
  Chrome/driver process leaked per failing scenario. The failure path now
  carries the partially-provisioned context, so every impl that came up is
  closed. Side effect worth knowing: After hooks on such failures now see
  the live partially-provisioned capabilities (they run before cleanup), so
  screenshot-on-failure hooks can reach a browser that did come up.

<!-- entry: sl-ev0b -->
- **`--dry-run` in setup mode no longer executes user lifecycle code.**
  `sl run --dry-run` against a `setup.clj` project used to call each group's
  `:start` fn — fixture servers spawned, ports bound — during what should be
  a pure plan preview. Dry-run now skips `:start`/`:stop` and every
  Before/After entirely: the preview lists each group's plan and its hook
  firing lists without running any of it.

<!-- entry: sl-27uh -->
- **Report data with non-round-tripping keyword or symbol names is now
  stringified.** `edn-safe?` admitted keywords and symbols whose names don't
  survive an EDN round-trip (`(keyword "a b")`, `(symbol "-1")`), letting
  them embed raw in the `--edn` summary and the HTML report's EDN island —
  where a hostile name could break the island's readability. Such tokens are
  now scrubbed to their `pr-str` string form, and the HTML island gained an
  integrity guard that aborts loudly rather than emit an unreadable island.
  Well-formed data is untouched.

## Stats

```
1882 tests, 6271 assertions, 0 failures
Cucumber compliance: 46/46 good, 11/11 bad (100%)
```

## Installation

**One-line installer (recommended):**

```bash
curl -fsSL https://raw.githubusercontent.com/SHIFT-LEFTER/shiftlefter/main/release/install.sh | bash
export PATH="$PWD/sl:$PATH"
sl --version
```

**Manual:** download `shiftlefter-v0.5.2.zip` from releases, unzip, add to PATH. Java 11+ is the only requirement — no Clojure toolchain needed.

## Links

- [README](https://github.com/SHIFT-LEFTER/shiftlefter/blob/v0.5.2/README.md)
- [Running in CI](https://github.com/SHIFT-LEFTER/shiftlefter/blob/v0.5.2/docs/CI.md)
- [Fit check — would ShiftLefter work for my project?](https://github.com/SHIFT-LEFTER/shiftlefter/blob/v0.5.2/docs/FIT.md)

---

# Changelog: 0.5.1

**Release Date:** 2026-07-10

---

**ShiftLefter 0.5.1 makes `sl run` a first-class citizen of your CI pipeline: JUnit XML your CI ingests natively, tag-based subset selection, live per-scenario output, parallel execution, and a self-contained HTML report — one release, one theme.**

v0.5.0 locked the core surface and launched publicly. v0.5.1 is about everything *around* the run that a pipeline needs: machine-readable results, picking the subset that matters, seeing progress as it happens, and honest wall-clock speed.

---

## What's New

### JUnit XML for CI

`sl run --junit-xml PATH` (config mirror `[:runner :report :junit-xml]`; the flag wins) writes a CI-ingestible JUnit XML report alongside the console/EDN output — verified live against GitLab's `artifacts:reports:junit` ingest. Test-case identity is stable across runs, failures are attributed through macro expansion to the step that actually failed, and per-scenario and per-step durations are captured.

Two behaviors to know (full detail in [ERRATA E009](ERRATA.md)):

- The XML contains a `<failure>`/`<error>` **iff** the run's exit code is nonzero — the report never contradicts the exit code.
- A planning error (exit 2) writes **no** file at all. Gate CI on the process exit code, not on the report's presence.

### Tag-Subset Selection

`sl run --tags TAGS` / `--skip-tags TAGS` runs a tagged subset of the suite: comma-separated, repeatable, `@` optional; include is OR, exclude wins; feature/Rule/Examples-block tags inherit per the Gherkin spec.

Filtering happens at **planning time** — deselected scenarios are never bound, and counts, JUnit, and EDN output reflect only the selection. `--dry-run` with a filter previews it: the console appends `; M filtered out by tags`, and the EDN gains an additive `:filtered-out` key. No filter = behavior unchanged.

### Live Per-Scenario Output

`sl run` now prints each scenario's result as it finishes, instead of staying silent until the whole suite completes. Same lines, same order — you just see them while the run is still going.

### Parallel Scenario Execution

`sl run --max-parallel N` (config mirror `[:runner :max-parallel]`; the flag wins; default 1 = sequential) runs scenarios on a bounded pool. Report output is identical in content **and order** to a sequential run at any N.

- `@serial` marks a scenario as **exclusive**: it runs alone, after the pool drains. It is an exclusivity mechanism, not an ordering one; a feature-level `@serial` marks each scenario individually, not the feature as an atomic block.
- Scenarios that wear costumes or touch a shared-impl interface auto-serialize the same way, with a notice line saying so.

### Self-Contained HTML Report

`sl run --html PATH` (config mirror `[:runner :report :html]`; the flag wins) writes a single-file HTML run report: works from a double-click, no network requests. Failures come expanded by default, with step-by-step transcripts, durations, tag chips, and dark/light rendering.

The report embeds its own run data as a machine-readable EDN island (`<script type="application/edn">`) — agents read the data, humans read the page, one artifact. Runs with thousands of scenarios build a big DOM and will scroll sluggishly.

### CLI Polish & Fixes

- `sl --help` now lists the daemon subcommands and `repl --clj` (previously undocumented in the help text).
- `sl verify` outside a ShiftLefter framework checkout now exits 2 with a notice (was: exit 0 — a consumer CI script could silently pass). Use `sl orient` and `--dry-run` to validate a project.
- `close-browser` handed a wrong-shape capability now returns a structured error instead of silently claiming `:closed` (both browser adapters).

### Internal

- Two-plane reporting architecture: a synchronous `Reporter` protocol for user output, an observer event bus for everything else — the groundwork the features above stand on. A throwing reporter fails the run immediately.
- Run-event envelopes gain `:scenario/id` and `:seq`; the observer bus is `offer!`-based (a slow observer can never stall or crash a run; drops are counted and logged) and drains on close, so accepted events are always delivered — killing a long-standing race that dropped each run's final events.
- A generated runner-mechanics corpus (seeded generator + JUnit-vs-manifest verifier) now exercises tags × parallel acceptance, and generative round-trip properties lock the envelope purity contract.

## Behavior Changes

- `sl verify` in a directory that isn't a ShiftLefter framework checkout exits 2 (was 0). A CI step that relied on the old silent green must switch to `sl orient` / `sl run --dry-run`.
- `sl run` console output now appears per-scenario during the run (content and order unchanged).

## Stats

```
1710 tests, 5593 assertions, 0 failures
Cucumber compliance: 46/46 good, 11/11 bad (100%)
```

## Installation

**One-line installer (recommended):**

```bash
curl -fsSL https://raw.githubusercontent.com/SHIFT-LEFTER/shiftlefter/main/release/install.sh | bash
export PATH="$PWD/sl:$PATH"
sl --version
```

**Manual:** download `shiftlefter-v0.5.1.zip` from releases, unzip, add to PATH. Java 11+ is the only requirement — no Clojure toolchain needed.

## Links

- [README](https://github.com/SHIFT-LEFTER/shiftlefter/blob/v0.5.1/README.md)
- [Fit check — would ShiftLefter work for my project?](https://github.com/SHIFT-LEFTER/shiftlefter/blob/v0.5.1/docs/FIT.md)
- [Why it's built this way](https://github.com/SHIFT-LEFTER/shiftlefter/blob/v0.5.1/docs/PHILOSOPHY.md)

# Changelog: 0.5.0

**Release Date:** 2026-07-07

---

**ShiftLefter 0.5.0 is the public launch: the core surface — a typed behavioral vocabulary driving real multi-actor browser sessions — is locked and ready to build on, and the whole project now treats an AI coding agent as a first-class author.**

v0.4.5 proved the multi-interface promise (web + SMS in one scenario). v0.5.0 pins the foundation those proofs stand on: a stability contract for the core, an agent front door, deterministic project discovery, and a formatter you can trust as a CI gate. Agents write good E2E tests against this surface today; the traceability graph that maps them to requirements and use cases builds upward from here.

---

## What's New

### Agent-First Front Door

`sl orient` (replacing `sl agent-prompt`) is the one command an agent — or a human — runs first in any ShiftLefter project. It states which mode the project is in (**Shifted** with a validated vocabulary, or **Vanilla** plain Gherkin), points at the key artifacts, and suggests next steps. `sl orient --edn` dumps the full machine-readable project projection — accepted working-tree truth an agent can consume directly, including validation commands phrased for the *consumer's* environment, not ours.

The doctrine now travels with the jar: `sl agent-doc` serves topic guides (locator strategy, SIEVE doctrine, orientation) from inside the packaged release, and the built-in step vocabulary reference is *generated* from the actual registered steps, so it cannot drift from the code. A one-line installer drops a runnable `sl` into `./sl/` and prints the `AGENTS.md` breadcrumb that tells a coding agent the surface exists.

### Deterministic Project Context

Project root and config discovery is now a single, deterministic resolution used everywhere — CLI, daemon, and examples resolve paths identically. The warm-path daemon wrapper that makes repeat `sl` calls fast is now the wrapper actually packaged in the release, and config-less directories no longer suffer a 10-second stall, a cold fallback, and a leaked background daemon.

### Two Modes, Stated Plainly

**Vanilla** mode (plain Gherkin, no vocabulary) is now genuinely reachable — previously the default config injected a glossary key and made every project look Shifted. `sl orient` names the mode explicitly, and planning-time validation checks exactly what the runner will run — no more validating steps the current mode ignores.

### A Formatter You Can Gate CI On

`sl fmt --check` / `--write` is advertised as a CI gate, so 0.5.0 makes it trustworthy:

- **Formatting is idempotent** — `fmt(fmt(x)) == fmt(x)` is now a property test in the generative suite, not an assumption. Multi-line descriptions previously drifted two spaces per pass and could never satisfy `--check`; they now normalize to a fixed column while preserving intentional relative indentation.
- **Descriptions are never deleted** — Scenario, Background, and Examples descriptions are emitted in canonical output (they were previously dropped), with a preservation property test guarding all positions.
- **Comments are never silently destroyed** — canonical formatting does not yet re-emit interior comments, so `--write` now *refuses* to reformat a file where comments would be lost, names the exact lines, and leaves the file untouched. `--check` reports such files as a third state — `CONTAINS COMMENTS` — distinct from OK and NEEDS FORMATTING, exiting 0 so a CI gate never goes red on a file `--write` won't fix. Full comment preservation is on the roadmap.

### Sharper Diagnostics

- Planning diagnostics (step-definition/glossary mismatches) now appear in **both** the console and EDN reporters — previously they were invisible in both.
- `sl <unknown-command>` is now a proper CLI usage error: non-zero exit (was: exit 0 with output on stdout).
- Provisioning rejects a second wearer of the same costume up front, instead of failing mysteriously mid-run.
- Browser step retry logic recognizes more Chrome error variants and waits out post-navigation races.

### Docs for Humans and Agents

The public docs got a positioning-and-honesty pass, validated by blind cold-reads against a first-day-adopter persona:

- [Would ShiftLefter work for my project?](docs/FIT.md) — a practical fit check you can hand to your agent.
- [Why it's built this way](docs/PHILOSOPHY.md) — the design-philosophy read behind the typed-vocabulary idea.
- The locator-strategy doctrine — why intent references beat brittle selectors — published for end users, with a slim agent-facing cut in `sl agent-doc`.
- Reference pages for the glossary, SVO validation, and costumes, plus a tutorial on-ramp through the numbered `examples/`.

### Agent Evaluation Corpus

`evals/` ships two self-contained agent evaluation tasks — a cross-interface authoring task (browser + SMS) and a multi-user session-isolation repair task — each with a start state, a solution, and a verifier. The 0.5 acceptance drive ran an agent through the real dogfood loop (N=3, all green through the built jar).

### Preview: SIEVE

The SIEVE browser-inspection server — live perception of a running page, two-observation reconciliation, semantic diffing — ships in the codebase as a **dev/REPL preview**: invocable from the REPL, not yet wired to the CLI. Its doctrine (`sl agent-doc sieve`) ships now, backed by the acceptance-drive evidence.

## Breaking Changes

- `sl agent-prompt` is now `sl orient` (same front-door role, richer output).
- `sl <unknown-command>` exits non-zero (was 0).
- `sl fmt` EDN output gains per-file `:comments` and summary `:with-comments` / `:skipped-comments` keys; comment-bearing files report the new `CONTAINS COMMENTS` state instead of NEEDS FORMATTING.

## Stats

```
1595 tests, 5069 assertions, 0 failures
Cucumber compliance: 46/46 good, 11/11 bad (100%)
```

## Installation

**One-line installer (recommended):**

```bash
curl -fsSL https://raw.githubusercontent.com/SHIFT-LEFTER/shiftlefter/main/release/install.sh | bash
export PATH="$PWD/sl:$PATH"
sl --version
```

**Manual:** download `shiftlefter-v0.5.0.zip` from releases, unzip, add to PATH. Java 11+ is the only requirement — no Clojure toolchain needed.

## Links

- [README](https://github.com/SHIFT-LEFTER/shiftlefter/blob/v0.5.0/README.md)
- [Fit check — would ShiftLefter work for my project?](https://github.com/SHIFT-LEFTER/shiftlefter/blob/v0.5.0/docs/FIT.md)
- [Why it's built this way](https://github.com/SHIFT-LEFTER/shiftlefter/blob/v0.5.0/docs/PHILOSOPHY.md)

# Changelog: 0.4.5

**Release Date:** 2026-06-05

---

**ShiftLefter now drives SMS as a first-class interface — the same zero-code step definitions that ran browsers send and receive real text messages — and locks its verb vocabulary behind frame-validated glossaries.**

v0.4.0 added the intent layer and typed subjects. v0.4.5 cashes in the multi-interface promise: a 2FA password-reset scenario that spans web *and* SMS, with every capability provisioned up front and validated against the protocols each adapter claims.

---

## What's New

### SMS Interface

A second real interface alongside the browser. The `ISMS` protocol covers production sending; `ISMSInbound` adds the test-seam read path. A Twilio adapter implements `ISMS` against the live API; a MockSMS adapter implements both for deterministic tests. Built-in step definitions — `:send`, `:send-media`, `:receive`, `:see`, `:count` — need no custom code:

```gherkin
When :user/alice sends "RESET" to the service
Then :user/alice should receive a message containing "code"
And :service should see 1 message from :user/alice
```

Configure it like any other interface:

```clojure
;; shiftlefter.edn
{:interfaces {:sms {:type :sms :adapter :twilio
                    :config {:account-sid "..." :auth-token "..." :from "+1..."}}}}
```

The Twilio read path was validated against the real API.

### Multi-Interface 2FA Demo

`examples/04-sms-2fa/` drives a password-reset flow across web and SMS in a single scenario — the canonical example of two interfaces cooperating, with a `setup.clj` wiring shared state.

### `setup.clj` Test Orchestration

A convention for scenarios that need shared setup across interfaces. A `setup.clj` next to your feature files runs once, plumbing an adapter registry and any shared state into the run — see `examples/04-sms-2fa/setup.clj` for the canonical pattern.

### Subject-Aware Verb Glossaries & Frames

Verbs are now declared in glossaries (`verbs-web`, `verbs-sms`) with a frame-based valence schema — each verb states the arguments (frames) it accepts, cross-checked against step definitions at compile time. `:hover` and `:wait` returned to the web glossary; `:count`, `:upload`, `:check`, `:uncheck`, and `:submit` were removed as defaults (rationale in the decisions record). See Breaking Changes.

### Capability Gating

Adapters declare what they `:provides`; scenarios (and the stepdefs they bind) declare `:requires-protocols`. The adapter registry validates these claims at suite-load, so a scenario that needs an inbound-capable SMS adapter fails fast at load if you wired a send-only one — not mid-run.

### `:be` Verb (URL assertions)

The `:url` frame moved out of `:see` into a dedicated `:be` verb with an `:at` frame. The surface pattern is unchanged — existing URL assertions keep working — but the model is cleaner.

---

## Behavior change: scoped-eager capability provisioning is now the default

**Affects:** Multi-interface scenarios under `sl run`.

Capabilities (browsers, SMS clients, API clients) are now provisioned at
**scenario start** for every interface the scenario's bound steps will
touch — not lazily on the first step that needs them.

**Why:** Bad credentials, missing config, and adapter errors now fail
fast — before any step runs — instead of mid-scenario after a partial
prefix has already executed. Per-interface timing markers (e.g.
`:sms/scenario-start-ts`) become honest: set when the scenario starts,
not when the first step happens to touch the interface.

**Behavior change to expect:** A scenario that previously short-circuited
before reaching an SMS step (e.g., a web step failed first) now pays the
SMS bring-up cost up front. Every `:on-provision` hook fires earlier in
the scenario than it used to.

**Opt-out:**

```clojure
;; shiftlefter.edn
{:runner {:provisioning :lazy}}   ;; revert to per-step on-first-touch
```

`:provisioning :eager` is the default; the key may be omitted. Valid
values: `:eager` | `:lazy`.

**Unaffected:**

- The REPL (`shiftlefter.repl/run` and direct `execute-scenario` calls
  with no `:provisioning` opt) keeps lazy semantics — exploratory work
  doesn't pay bring-up for unused interfaces.
- The per-step `ensure-capability` primitive is unchanged. Eager is
  layered on top: the scenario-start phase calls the same primitive once
  per `(interface, subject)` target, then per-step calls no-op.
- `:shared-impl?` interfaces (e.g., SMS) still provision their impl
  exactly once per scenario — Bob's eager target reuses Alice's via
  `find-existing-shared-impl`.

**Multi-interface parallelism:** When a scenario touches more than one
interface, eager bring-up runs the per-interface groups in parallel
(futures). Within an interface, sequentially so shared-impl handoff is
deterministic.

---

## Minor Improvements

- **Did-you-mean for `[:iface]` failures** — an unknown interface annotation now suggests the closest valid one instead of a bare error.
- **ChromeDriver retry** — "Node with given id does not belong to the document" joins the retryable-error set, smoothing over a transient DOM race.
- **Logging facade** — operational `stderr`/`println` output migrated to `clojure.tools.logging`; bring your own SLF4J binding to route it.
- **Fixture server promoted** — the test fixture server moved into `src/shiftlefter/demo/fixture/`, retiring the `:demo` deps alias the 2FA demo previously needed.
- **`exec.clj` split** — the runner core was split along provisioning / step-loop / cleanup seams (internal, no surface change).

---

## Breaking Changes

- **Default web verbs removed** — `:count`, `:upload`, `:check`, `:uncheck`, and `:submit` are no longer in the default web glossary. Declare them in a project glossary if you need them.
- **`SVOI` → `SVO`** — the subject-verb-object-instrument nomenclature collapsed to subject-verb-object across event keywords and namespaces. Code that referenced `:svoi/*` event keys or `shiftlefter.svoi.*` namespaces must use `:svo/*` and `shiftlefter.svo.*`.
- **`browser.ctx` bridge removed** — the legacy `shiftlefter.browser.ctx` namespace is gone; the unified capability/ctx path is the only one.

---

## Test Results

```
1359 tests, 4160 assertions, 0 failures
Compliance: 46/46 good, 11/11 bad (100%)
```

---

# Changelog: 0.4.0

**Release Date:** 2026-04-04

---

**v0.4.0 adds an intent layer — bind a business action like `Login.submit` to a real locator per interface — plus typed subjects with isolated sessions and runtime spec enforcement (45 fdefs).**

v0.3.x made browser tests run with zero custom code. v0.4.0 adds the structural layer beneath them: a vocabulary for intent, a type system for subjects, and specs that enforce the contracts at runtime.

---

## What's New

### Intent System

Declare *what* an action means once, bind it to *how* each interface performs it. Flat intent regions hold per-interface element bindings, so `Login.submit` resolves to a real locator at run time:

```clojure
;; intent/login.edn
{:region :Login
 :intents {:submit {:web {:css "button[type=submit]"}}}}
```

```gherkin
When :alice submits Login
```

Object enforcement is configurable — `:strict` (every intent reference must resolve), `:warn`, or `:off`.

### Subject Types & Instances

Subjects are now typed. `:user/alice` names an instance `alice` of type `user`, resolved through a two-level type/instance glossary. Each instance gets an isolated session, so multi-user scenarios don't bleed state:

```gherkin
Given :user/alice is logged in
And :user/bob is logged in
Then :user/alice should see her own dashboard
```

Display format is `[:type] instance` for readable reports.

### Spec Instrumentation

45 `fdef` specs across all namespaces, active during dev and test runs — so specs are runtime enforcement, not just documentation. Boundary guards validate external input where it enters, REPL interactions get type safety, and UTF-8 output is enforced at the stream boundary.

### Test Fixture Server

A configurable Ring harness (`with-fixture-server`) ships with login, dashboard, and logout pages and real multi-user session isolation — so browser scenarios have a deterministic target to drive without standing up an external app:

```clojure
(with-fixture-server [server {:port 0}]
  ;; login / dashboard / logout pages, per-user sessions
  (run-scenarios))
```

### Agent Documentation

- **`docs/AGENT.md`** — LLM-agent instructions covering the CLI, the SVO model, step definitions, multi-user examples, intent references, and the dual browser adapters (Etaoin + Playwright).

### Public Mirror Convention

Local agent primers and role prompts are not part of the public release spine. The public mirror ships user-facing documentation only.

---

## Notes

- **Source-only retroactive release.** v0.4.0 records a drop that was published to the public mirror but never tagged or released at the time. No binary artifact was built for it — binary distribution resumes at v0.4.5.
- **Spec coverage** rather than a fresh test count: this entry reconstructs an already-public source tree, so it reports the 45 `fdef` contracts added rather than a re-run pass total.

---

# Changelog: 0.3.6

**Release Date:** 2026-02-09

---

**ShiftLefter now supports Playwright, bundles nREPL, and ships with full API docs.**

v0.3.5 made browser testing work. v0.3.6 hardens the developer experience — alternative browser backends, a bundled REPL server, 36 browser steps, CLI safety, and comprehensive documentation.

---

## What's New

### Playwright Adapter (Experimental)

Alternative browser backend alongside Etaoin. Same step definitions, different engine:

```clojure
;; shiftlefter.edn
{:interfaces {:web {:type :web :adapter :playwright :config {:headless true}}}}
```

Supports Chromium, Firefox, and WebKit. All 31 IBrowser protocol methods implemented. Auto-waiting and string-based selectors map naturally. Requires the Playwright Java dependency (not bundled) — see `docs/CAPABILITIES.md` for setup.

### Browser Backend Configuration

Choose your browser engine per-interface via config. Generic `:adapter-opts` passthrough for backend-specific settings:

```clojure
{:interfaces {:web {:type :web
                    :adapter :etaoin
                    :adapter-opts {:load-timeout 30000}}}}
```

### 36 Browser Step Definitions

19 new protocol methods and 22 new step definitions (36 total browser steps). Covers scrolling, keyboard chords, window/tab management, frames, dialogs, and advanced form interactions. Etaoin (default, bundled) requires ChromeDriver on your system; see `docs/CAPABILITIES.md` for browser setup tiers.

```gherkin
When :alice scrolls down by 500 pixels
And :alice presses 'shift+control+t'
And :alice switches to window 2
And :alice accepts the dialog
Then :alice should see 3 {:css "li.result"} elements
```

### Bundled nREPL Server

`sl repl` now works with Java-only — no Clojure CLI required. CIDER middleware included for IDE integration:

```bash
sl repl              # interactive REPL
sl repl --nrepl      # start nREPL server (default port 7888)
sl repl --nrepl --port 9000
```

### API Documentation

Generated API docs for all 51 namespaces via Codox. [Browse on GitHub](https://github.com/SHIFT-LEFTER/shiftlefter/tree/v0.3.6/docs/api).

### Installation & Capabilities Docs

New `docs/CAPABILITIES.md` with tiered capability guide (Java-only → Java+ChromeDriver → Java+Clojure CLI). New `CONTRIBUTING.md` for developers.

---

## Bug Fixes

- **Inline comments** — `#` comments inside scenario bodies no longer cause parse errors.
- **CLI flag handling** — Unknown flags now rejected with usage message instead of silently ignored. Fixed `--config-path` not reaching the runner.
- **i18n keywords** — Formatter now preserves original language keywords instead of normalizing to English.

---

## Minor Improvements

- **Browser step retry** — Configurable `*retry-timeout-ms*` dynamic var (default 3s). Element count verification now retries.
- **CLI flags** — Added `--no-color` and `--mode` flags.
- **Bundled capabilities** — 8 libraries available in uberjar stepdefs (Cheshire, babashka.fs, core.async, spec.alpha, test.check, etaoin, Java stdlib, clojure.test). Classpath extension via `lib/*.jar`.

---

## Breaking Changes

- **"I" step patterns removed** — `I click`, `I fill`, etc. no longer exist. All steps use subject-extracting patterns (`:alice clicks`, `:bob fills`).
- **`repl/free` removed** — Use `repl/as` instead (identical behavior).

---

## Test Results

```
1029 tests, 3200+ assertions, 0 failures
Compliance: 46/46 good, 11/11 bad (100%)
```

---

# Changelog: 0.3.5

**Release Date:** 2026-02-05

---

**ShiftLefter now runs browser tests with zero custom code.**

v0.3.0 added browser automation and SVO validation. v0.3.5 makes them usable — multi-actor browser testing with auto-provisioning, tutorial examples, and a distributable release zip.

---

## What's New

### Multi-Actor Browser Steps

13 new built-in steps with named-subject routing. Each subject gets its own browser session:

```gherkin
Scenario: Two users collaborate
  When :alice opens the browser to 'https://app.com'
  And :alice fills {:id "message"} with 'Hello Bob'
  And :alice clicks {:css "button.send"}
  Then :bob should see 'Hello Bob'
  And :bob should see the title 'App - Messages'
```

7 action steps (navigate, click, double-click, right-click, move, drag, fill) and 6 verification steps (see text, see element, not see element, element count, URL check, title check). All include SVO metadata for shifted-mode validation.

### Zero-Code Browser Tests

Browser sessions auto-provision from `shiftlefter.edn`. No setup steps, no custom Clojure — just a feature file and a config:

```clojure
;; shiftlefter.edn
{:interfaces {:web {:type :web :adapter :etaoin :config {:headless true}}}}
```

The framework sees `:alice` needs a `:web` capability, looks up the interface config, and provisions a browser keyed to that subject on first use.

### Auto-Retry on Browser Steps

Action and verification steps automatically retry on transient browser errors — stale element references, elements not yet in the DOM, assertion mismatches during page transitions. 3-second timeout with 100ms backoff. No configuration needed.

### Tutorial Examples

Four new worked examples in `examples/`:

| Example | What it covers |
|---------|---------------|
| `01-validate-and-format` | Parse, validate, and format feature files |
| `02-browser-zero-code` | Browser test with zero custom Clojure |
| `02b-browser-multi-actor` | Two-actor browser test with SVO |
| `03-custom-steps` | Custom step definitions with `defstep` |

Each has a README with step-by-step instructions.

### Release Distribution Zip

Ship as a single zip. Users unzip, add to PATH, done:

```bash
unzip shiftlefter-v0.3.5.zip
export PATH="$PATH:$PWD/shiftlefter-v0.3.5"
sl fmt --check myfile.feature
```

No Clojure toolchain required — just Java 11+.

### REPL Shifted Mode

Toggle SVO validation interactively:

```clojure
(shifted!)   ;; enable SVO validation against glossaries
(vanilla!)   ;; back to vanilla mode
```

---

## Bug Fixes

- **`sl fmt --check`** — Previously always reported "OK". Now correctly exits 1 when files need formatting.
- **`sl gherkin ddmin`** — No longer crashes with "no matching clause" when called without `--mode`.
- **`sl gherkin fuzz --mutation --sources corpus`** — Clear error message when run outside the project directory instead of cryptic "bound must be positive".
- **`sl verify`** — Fuzz artifact scanning removed from default mode (was slow/crashy with large artifact sets). Use `--fuzzed` to opt in.
- **CLI path resolution** — `sl` installed on PATH now resolves relative arguments against the user's working directory, not the install directory.

---

## Minor Improvements

- **Undefined step locations** — Console output includes `file:line:column` for undefined steps
- **SVO diagnostics in `--edn` output** — SVO issues include full location info; macro-expanded steps show both call site and definition location in failure messages
- **Browser stability** — PersistentBrowser reconnect handles more error types; Chrome window verified before navigation; browser session options cleaned from WebDriver capabilities
- **Spec health** — All specs in the registry can now generate sample data; spec health test catches dangling references and missing generators in CI

---

## Breaking Changes

- **ctx-first step convention** — Step functions now receive `ctx` as the first argument with flat context shape. Old: `[subject element ctx]` → New: `[ctx subject element]`. DataTable/DocString accessible via `(step/arguments ctx)`.

---

## Test Results

```
945 tests, 2869 assertions, 0 failures
Compliance: 46/46 good, 11/11 bad (100%)
```

---

# Changelog: 0.3.0

**Release Date:** 2026-01-10

---

**ShiftLefter now catches mistakes before they run.**

v0.2.0 ran tests. v0.3.0 validates them — checking that your actors and actions are known before execution begins.

---

## What's New

### SVO Validation

Define who can do what in your test suite:

```clojure
;; config/glossaries/subjects.edn
{:subjects
 {:alice {:desc "Standard customer"}
  :admin {:desc "Administrative user"}
  :guest {:desc "Unauthenticated visitor"}}}
```

Add metadata to step definitions:

```clojure
(defstep #"^(\w+) clicks the (.+)$"
  {:interface :web
   :svo {:subject :$1 :verb :click :object :$2}}
  [ctx subject element]
  ...)
```

Typos are caught at bind time with suggestions:

```
Unknown subject :alcie in step "When Alcie clicks the button"
       at features/login.feature:12
       Known subjects: :alice, :admin, :guest
       Did you mean: :alice?
```

Configure enforcement levels in `shiftlefter.edn`:

```clojure
{:glossaries
 {:subjects "config/glossaries/subjects.edn"
  :verbs {:web "config/glossaries/verbs-web.edn"}}

 :interfaces
 {:web {:type :web :adapter :etaoin :config {:headless true}}}

 :svo
 {:unknown-subject :warn    ; or :error to block execution
  :unknown-verb :warn
  :unknown-interface :error}}
```

### Browser Automation

Built-in browser support via IBrowser protocol:

```clojure
(defstep #"^(\w+) clicks the (.+)$"
  {:interface :web ...}
  [ctx subject element]
  (let [browser (get-capability ctx :web)]
    (browser/click browser element)
    ctx))
```

Capabilities are auto-provisioned — declare the interface, get a browser.

### REPL-First Development

Interactive step development with named contexts:

```clojure
(require '[shiftlefter.repl :as repl])

(repl/as :alice)                    ; create/switch to Alice's browser
(repl/step "I click the login button")
(repl/as :bob)                      ; separate browser for Bob
```

Multi-actor scenarios use separate browser sessions per subject.

### Persistent Browser Sessions

Browsers survive JVM restarts and macOS sleep/wake cycles. Subject profiles stored in `~/.shiftlefter/subjects/` enable exploratory testing without session loss.

---

## Minor Improvements

- **Auto-provisioning**: Steps declaring `:interface :web` get browsers automatically
- **Capability cleanup**: Ephemeral capabilities cleaned up after scenarios; persistent ones survive
- **Error formatting**: Clear messages with location info and suggestions
- **Example project**: See `examples/svo-demo/` for a complete working setup

---

## Documentation

- `docs/SVO.md` — Full SVO validation guide with migration steps
- `docs/GLOSSARY.md` — Updated with SVO terminology
- `examples/svo-demo/` — Working example with glossaries and shifted stepdefs

---

## Test Results

```
865 tests, 2582 assertions, 0 failures
Compliance: 46/46 good, 11/11 bad (100%)
```

---

# Changelog: 0.2.0

**Release Date:** 2026-01-07

---

**ShiftLefter now runs tests.**

v0.1.0 was a parser. v0.2.0 is a test framework.

---

## What's New

### Test Runner (`sl run`)

Execute Gherkin features against Clojure step definitions:

```bash
sl run features/ --step-paths steps/
sl run features/login.feature --dry-run    # verify binding without execution
sl run features/ --edn                      # machine-readable output for CI
```

Exit codes: 0=passed, 1=failed/pending, 2=planning-failed, 3=crash

### Step Definitions

Define steps with the `defstep` macro:

```clojure
(defstep #"I have (\d+) cukes" [ctx count]
  (assoc ctx :cukes (parse-long count)))
```

Context threads between steps with fail-fast-per-scenario semantics.

### Macro Expansion

Reusable step sequences, defined in INI files:

```ini
# macros/auth.ini
name = login as alice
steps =
  Given I visit the login page
  When I enter username "alice"
  And I enter password "secret123"
  Then I click the login button
```

```gherkin
Scenario: User sees dashboard
  Given login as alice +       # expands to 4 steps
  Then I should see the dashboard
```

Enable in `shiftlefter.edn`:
```clojure
{:runner {:macros {:enabled? true
                   :registry-paths ["macros/"]}}}
```

### Internationalization (70+ Languages)

Full i18n support using Cucumber's official language definitions:

```gherkin
# language: fr
Fonctionnalité: Connexion
  Scénario: Utilisateur valide
    Soit un utilisateur "alice"
    Quand je me connecte
    Alors je vois le tableau de bord
```

### Multi-File Formatting

`sl fmt` now supports directories:

```bash
sl fmt --check dir/               # validate recursively
sl fmt --write dir/               # reformat in place
```

### Standalone Distribution

No Clojure toolchain required to run — just Java:

```bash
./sl fmt --check myfile.feature
```

---

## Minor Improvements

- **Error diagnostics**: Standard `path:line:col: type: message` format, `--edn` flag for machine-readable output
- **Validator command**: `sl verify` for quick repo health checks, `sl verify --ci` for full suite
- **Fuzzing tools**: `sl gherkin fuzz` and `sl gherkin ddmin` for parser testing
- **Rule keyword**: Full `Rule:` block support in parser and formatter

---

## Test Results

```
591 tests, 1704 assertions, 0 failures
Compliance: 46/46 good, 11/11 bad (100%)
```

# Changelog: 0.1.0

**Release Date:** 2025-12-31

First public release of ShiftLefter's Gherkin parser.

## Highlights
- **100% Cucumber-compatible** (46/46 test files passing)
- **Lossless roundtrip guarantees** (byte-for-byte reconstruction)
- **70+ language dialect support** (official Cucumber i18n)
- **Clojure API** for framework integration
- **268 tests, 0 failures**

## Installation

**Binary (recommended):**
1. Download `shiftlefter-v0.1.0.jar` below
2. Ensure Java 11+: `java -version`
3. Run: `java -jar shiftlefter-v0.1.0.jar fmt --check your-file.feature`

**From source:** See README for Clojure setup instructions.

## Links
- [README](https://github.com/SHIFT-LEFTER/shiftlefter/blob/v0.1.0/README.md)
- [ERRATA (known edge cases)](https://github.com/SHIFT-LEFTER/shiftlefter/blob/v0.1.0/ERRATA.md)
