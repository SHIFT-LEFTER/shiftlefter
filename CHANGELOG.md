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
- **Browser stability** — PersistentBrowser reconnect handles more error types; Chrome window verified before navigation; stealth options cleaned from WebDriver capabilities
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

Stealth mode available for sites with anti-bot detection.

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
