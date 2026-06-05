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

Browser session persistence available for repeatable local testing.

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
