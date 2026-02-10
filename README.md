# ShiftLefter

**Gherkin test framework with vocabulary validation** — parse, format, run, and catch mistakes before execution. 100% Cucumber-compatible parser, built in Clojure.

## Status: v0.3.6

ShiftLefter provides a complete BDD testing framework with Gherkin parsing, validation, formatting, and test execution. The parser is 100% Cucumber-compatible; the runner executes scenarios with step definitions written in Clojure.

**Current:** Parser, formatter, runner, macro expansion, multi-actor browser testing (Etaoin + Playwright), SVO validation, REPL with nREPL/CIDER, tutorial examples

**Next:** Executable traceability, intent regions, supplanting what the screenplay pattern was aiming for with the data-oriented, queryable, far simpler SVOI

**Future:** Use case ↔ feature mapping & generation, observation lineage from real-world requirements through to running tests

---

## Install

### Quick Start (Java only — most users)

**Requirements:** Java 11 or later

1. Download the latest release zip from [releases](https://github.com/SHIFT-LEFTER/shiftlefter/releases)
2. Unzip and add to PATH:
   ```bash
   unzip shiftlefter-*.zip
   export PATH="$PATH:$PWD/shiftlefter-*"
   ```
3. Run:
   ```bash
   sl --help
   sl fmt --check your-file.feature
   sl run features/ --step-paths steps/
   ```

This is all you need to run features, format files, start the REPL (`sl repl`), connect your IDE (`sl repl --nrepl`), and use built-in browser steps. No Clojure toolchain needed — just Java.

### Writing Custom Step Definitions

Same Java-only install. Write `.clj` step definition files and ShiftLefter loads them automatically. 8 libraries are bundled (JSON, filesystem, browser automation, async, specs, and more) — see [What Can I Do?](docs/CAPABILITIES.md) for the full list.

### From Source (Contributors)

**Requirements:** Java 11+ and [Clojure CLI](https://clojure.org/guides/install_clojure)

```bash
git clone https://github.com/SHIFT-LEFTER/shiftlefter.git
cd shiftlefter
bin/kaocha    # run tests
```

See [CONTRIBUTING.md](CONTRIBUTING.md) for full development setup.

---

## Examples

ShiftLefter ships with working examples that cover the most common workflows. Each one is self-contained and designed to run as-is.

**Validate and format your existing feature files.** If you already have `.feature` files, start here. ShiftLefter can parse and validate any Cucumber-compatible Gherkin — no configuration, no step definitions, no Clojure. You'll also see how `sl fmt` can enforce canonical formatting in CI or reformat files in place. [→ Validate & Format](examples/01-validate-and-format/)

**Run a browser test without writing any code.** ShiftLefter includes built-in step definitions for common browser operations — navigation, clicking, filling forms, and verifying results. This example drives a real browser through a complete scenario using only a `.feature` file and a one-line config. No custom steps, no Clojure. [→ Zero-Code Browser Test](examples/02-browser-zero-code/)

**Run a multi-actor browser test.** Same built-in steps, but now Alice and Bob each get their own browser. ShiftLefter keeps their sessions and state completely separate. This example also includes a bonus section demonstrating vocabulary validation in shifted mode — where a glossary defines which actors are valid and the framework rejects unknown subjects at bind time. [→ Multi-Actor Browser Test](examples/02b-browser-multi-actor/)

**Write your first step definitions.** When you're ready to go beyond the built-in steps, this example walks through `defstep` — pattern matching, captures, context passing, and assertions. A classic cucumbers-and-counting scenario in about 20 lines of Clojure. [→ Your First Steps](examples/03-custom-steps/)

---

## CLI

The `sl` command provides test execution, file validation, and formatting.

> **Note:** Examples use `sl` assuming you installed via the release zip. Substitute `java -jar shiftlefter-v0.3.5.jar` if running the jar directly, or `bin/sl` if running from source.

### Running Tests

```bash
# Run all feature files in a directory
sl run features/ --step-paths steps/

# Run specific files
sl run features/login.feature features/checkout.feature --step-paths steps/

# Dry-run (verify bindings without executing)
sl run features/ --step-paths steps/ --dry-run

# Machine-readable EDN output
sl run features/ --step-paths steps/ --edn

# Verbose output (show each step)
sl run features/ --step-paths steps/ -v
```

**CLI Flags:**

| Flag | Config equivalent | Description |
|------|-------------------|-------------|
| `--step-paths p1,p2` | `:runner {:step-paths [...]}` | Comma-separated paths to step definition directories |
| `--config-path PATH` | — | Path to config file (default: `shiftlefter.edn` in current directory) |
| `--dry-run` | — | Bind steps but don't execute (verify all steps have definitions) |
| `--edn` | — | Output results as EDN to stdout |
| `-v, --verbose` | — | Show each step as it executes |

### Validation & Formatting

```bash
# Validate files/directories (checks parse + lossless roundtrip)
sl fmt --check path/to/file.feature
sl fmt --check features/           # recursive directory

# Format files in place (canonical style)
sl fmt --write path/to/file.feature
sl fmt --write features/           # recursive directory

# Canonical formatting to stdout (single file)
sl fmt --canonical path/to/file.feature
```

### Other Commands

```bash
# Fuzz testing (generate random valid Gherkin, verify invariants)
sl gherkin fuzz --preset smoke

# Verify repo health (fast validator checks)
sl verify

# Full CI verification (includes test suite, compliance, fuzz smoke)
sl verify --ci

# Machine-readable output
sl verify --edn
```

---

## Writing Step Definitions

Step definitions bind Gherkin steps to Clojure functions. Every step has a **subject** — a keyword like `:user`, `:alice`, `:cart` — extracted from the first word in the step text. Create `.clj` files in your step-paths directory:

```clojure
;; steps/shopping_steps.clj
(ns my-project.steps.shopping
  (:require [shiftlefter.stepengine.registry :refer [defstep]]
            [shiftlefter.step :as step]))

;; Subject-extracting step (first capture is the subject keyword)
(defstep #"(\S+) has (\d+) items in the cart"
  [ctx subject count-str]
  (assoc ctx :cart-count (Integer/parseInt count-str)))

;; Step with captures (regex groups become arguments after ctx)
(defstep #"(\S+) adds \"([^\"]+)\" to the cart"
  [ctx subject product]
  (update ctx :cart (fnil conj []) product))

;; Verification step
(defstep #"(\S+) should have (\d+) items"
  [ctx subject expected-str]
  (let [expected (Integer/parseInt expected-str)
        actual (:cart-count ctx)]
    (when (not= expected actual)
      (throw (ex-info "Count mismatch" {:expected expected :actual actual})))
    ctx))
```

Corresponding feature file:

```gherkin
Feature: Shopping cart
  Scenario: Add items
    Given :shopper has 0 items in the cart
    When :shopper adds "Cucumber" to the cart
    Then :shopper should have 1 items
```

**Convention:** `ctx` is always the **first** argument, followed by regex captures in order.

**Return values:**

- **Map** → becomes the new context for subsequent steps
- **nil** → context unchanged
- **`:pending`** → marks step as pending (scenario fails unless `allow-pending?` is true)
- **Throw** → step fails with error

**DataTable / DocString access:**
Steps with a DataTable or DocString attached access them via `(step/arguments ctx)`:

```clojure
(defstep #"(\S+) has the following items:" [ctx _subject]
  (let [{:keys [rows]} (step/arguments ctx)
        headers (first rows)
        data (rest rows)]
    (assoc ctx :items data)))
```

---

## SVO Validation (Optional)

Add type-checking to your step definitions with Subject-Verb-Object validation. See `docs/SVO.md` for full documentation.

```clojure
;; Step with SVO metadata — subject extracted from first capture
(defstep #"(\S+) clicks (.+)"
  {:interface :web
   :svo {:subject :$1 :verb :click :object :$2}}
  [ctx subject locator]
  ...)
```

Configure glossaries and enforcement in `shiftlefter.edn`:

```clojure
{:glossaries
 {:subjects "config/glossaries/subjects.edn"
  :verbs {:web "config/glossaries/verbs-web.edn"}}

 :interfaces
 {:web {:type :web :adapter :etaoin :config {:headless true}}}

 :svo
 {:unknown-subject :warn    ; or :error
  :unknown-verb :warn
  :unknown-interface :error}}
```

Benefits:

- Catch typos at bind time ("Alcie" → "Did you mean :alice?")
- Validate verbs against interface types
- Auto-provision capabilities (browsers, API clients)
- Emit `:step/svoi` events for traceability

See `examples/02b-browser-multi-actor/` for a working example with glossary validation.

---

## Configuration

ShiftLefter looks for `shiftlefter.edn` in the current directory. Copy `shiftlefter.edn.example` to get started:

```clojure
;; shiftlefter.edn
{:parser {:dialect "en"}       ;; Language dialect (en, fr, de, etc.)

 :runner {:step-paths ["steps/"]      ;; Where to find step definitions
          :allow-pending? false}}     ;; true = pending steps don't fail the run
```

**Browser backend** — choose between Etaoin (default, bundled) and Playwright (add to `lib/` or `deps.edn`):

```clojure
{:interfaces
 {:web {:type :web
        :adapter :playwright    ;; or :etaoin (default)
        :config {:headless false
                 :adapter-opts {:browser-type :firefox}}}}}
```

See [Browser Backend Configuration](docs/CAPABILITIES.md#browser-backend-configuration) for full details and option passthrough examples.

**Precedence:**
1. CLI flags (e.g., `--step-paths`) override config file
2. `./shiftlefter.edn` in current directory
3. Built-in defaults

---

## Public API

For framework integration, use `shiftlefter.gherkin.api`:

```clojure
(require '[shiftlefter.gherkin.api :as api])

;; Parse a Gherkin string (returns tokens + AST + errors)
(api/parse-string "Feature: Demo\n  Scenario: Test\n    Given a step\n")
;; => {:tokens [...] :ast [...] :errors []}

;; Generate pickles from AST (executable test cases)
(let [{:keys [ast]} (api/parse-string content)]
  (api/pickles ast "file.feature"))
;; => {:pickles [...] :errors []}

;; Lossless roundtrip (reconstructs original byte-for-byte)
(api/print-tokens (:tokens (api/lex-string content)))

;; Check roundtrip fidelity
(api/fmt-check content)
;; => {:status :ok} or {:status :error :reason :parse-errors ...}

;; Canonical formatting
(api/fmt-canonical content)
;; => {:status :ok :output "..."} or {:status :error ...}
```

**Envelope Contract:** All API functions return maps with vector values (never nil):

- `:tokens` → vector of Token records
- `:ast` → vector of Feature records
- `:pickles` → vector of pickle maps
- `:errors` → vector of error maps

---

## REPL Usage

For interactive development and experimentation, use `shiftlefter.repl`. Start it with `sl repl` (interactive) or `sl repl --nrepl` (IDE integration via CIDER/Calva):

```clojure
(require '[shiftlefter.repl :as repl])
(require '[shiftlefter.stepengine.registry :refer [defstep]])

;; Define steps — ctx is always first, then captures
(defstep #"(\S+) has (\d+) cucumbers" [ctx _subject n]
  (assoc ctx :count (Integer/parseInt n)))

(defstep #"(\S+) eats (\d+)" [ctx _subject n]
  (update ctx :count - (Integer/parseInt n)))

(defstep #"(\S+) should have (\d+) left" [ctx _subject n]
  (let [expected (Integer/parseInt n)
        actual (:count ctx)]
    (when (not= expected actual)
      (throw (ex-info "Mismatch" {:expected expected :actual actual})))
    ctx))
```

### Structured Mode (requires Feature/Scenario)

```clojure
;; Run full Gherkin text
(repl/run "
  Feature: Cucumbers
    Scenario: Eating
      Given :garden has 12 cucumbers
      When :garden eats 5
      Then :garden should have 7 left")
;; => {:status :ok :summary {:scenarios 1 :passed 1 :failed 0 :pending 0}}

;; Dry-run (bind without executing)
(repl/run-dry "Feature: X\n  Scenario: Y\n    Given :garden has 5 cucumbers")
;; => {:status :ok :plans [...]}
```

### Free Mode (no Feature/Scenario wrapper)

For quick experimentation, execute steps directly without Gherkin structure:

```clojure
;; Execute steps one at a time (repl/as prepends the subject name)
(repl/as :garden "has 12 cucumbers")
;; => {:status :passed :ctx {:count 12}}

(repl/as :garden "eats 5")
;; => {:status :passed :ctx {:count 7}}

;; Context accumulates across calls
(repl/ctx :garden)
;; => {:count 7}

;; Reset for new session
(repl/reset-ctxs!)
```

### Named Contexts (multi-actor sessions)

Each subject gets its own isolated context:

```clojure
;; Each session maintains its own context
(repl/as :alice "opens the browser to 'https://example.com'")
(repl/as :bob "opens the browser to 'https://example.com'")
(repl/as :alice "clicks {:id \"login\"}")
(repl/as :bob "fills {:id \"search\"} with 'test'")

;; Multiple steps in one call
(repl/as :alice "fills {:id \"email\"} with 'alice@test.com'" "clicks {:id \"submit\"}")

;; Inspect specific context
(repl/ctx :alice)

;; Inspect all named contexts
(repl/ctxs)
;; => {:alice {...} :bob {...}}

;; Reset all named contexts
(repl/reset-ctxs!)
```

### Utilities

```clojure
;; List registered step patterns
(repl/steps)

;; Clear registry and context (fresh start)
(repl/clear!)
```

---

## Behavioral Guarantees

### Compliance
- **100% Cucumber-compatible** (46/46 official test files passing)
- Token format, AST structure, and pickle output match Cucumber reference implementation
- 11/11 invalid files correctly rejected with appropriate errors

### File Encoding
- **UTF-8 only:** All file reads enforce strict UTF-8 decoding
- Invalid UTF-8 bytes produce hard error (`:io/utf8-decode-failed`), not mojibake
- `# encoding:` header currently ignored (no encoding switching)

### Lossless Roundtrip
- `print-tokens(lex(input)) == input` byte-for-byte for all valid Gherkin
- Preserves exact line endings (LF, CRLF, CR, mixed)
- Preserves original whitespace, indentation, and tag spacing
- Use `fmt-check` or `roundtrip-ok?` to verify

### Canonical Formatting
When using `fmt --canonical` or `api/fmt-canonical`:

- Normalizes line endings to LF (`\n`)
- Normalizes indentation to 2 spaces
- Normalizes tag spacing to single space between tags
- **Requires valid parse** — refuses to format files with parse errors (Policy B)

### Dialect Support
- 70+ language dialects via official Cucumber `i18n.json`
- `# language: <code>` header switches keyword recognition
- Keywords matched by prefix (e.g., "Сценарий" for Russian)

### Error Handling
- **Fail-fast:** Parse errors block formatting/rewriting (Policy B)
- **Structured errors:** All errors include `:type`, `:message`, `:location`
- **No silent failures:** Unknown constructs produce errors, not empty results

See [ERRATA.md](ERRATA.md) for known edge cases and workarounds.

---

## Documentation

- [docs/CAPABILITIES.md](docs/CAPABILITIES.md) — What you can do at each installation tier, bundled libraries, browser backend config
- [docs/browser-getting-started.md](docs/browser-getting-started.md) — Browser automation quick start (built-in steps)
- [docs/PERSISTENT-SUBJECTS.md](docs/PERSISTENT-SUBJECTS.md) — Multi-actor persistent browser sessions
- [API Reference](docs/api/index.html) — Generated API docs for all 51 namespaces (Codox)
- [docs/SVO.md](docs/SVO.md) — SVO validation guide (glossaries, defstep metadata, migration)
- [docs/GLOSSARY.md](docs/GLOSSARY.md) — Terminology reference
- [docs/FUZZ.md](docs/FUZZ.md) — Fuzz testing and delta debugging
- [CONTRIBUTING.md](CONTRIBUTING.md) — Development setup and contribution guide
- [CHANGELOG.md](CHANGELOG.md) — Release history
- [ERRATA.md](ERRATA.md) — Known edge cases and workarounds

---

## Repository Layout

**What ships in the JAR:**

```
src/                           # Framework source code
resources/shiftlefter/         # Runtime data and templates
├── gherkin/i18n.json         # Dialect definitions (70+ languages)
├── glossaries/               # Default glossary files
└── templates/shiftlefter.edn # Config template for `sl init` (future)
```

**Not in the JAR (but in the repo):**

```
docs/                          # User-facing documentation (SVO, glossary, fuzz, etc.)
examples/                      # Worked tutorial examples (see Examples section above)
test/                          # Test suite: fixtures, compliance snapshots, property tests
release/                       # Release packaging (sl wrapper script for distribution zip)
bin/                           # Dev scripts (kaocha, kondo, compliance, repl-eval)
```

**Configuration:**

- `shiftlefter.edn.example` — Template config (tracked, copy to `shiftlefter.edn`)
- `shiftlefter.edn` — User config (gitignored)

---

## Testing

**1029 tests, 3200+ assertions, 0 failures**

```bash
# Run full test suite
bin/kaocha

# Run compliance tests
bin/compliance

# Fuzz testing
sl gherkin fuzz --trials 1000 --seed 12345
```

---

## Return Codes

CLI commands return consistent exit codes for scripting and CI integration.

### `sl run`
| Code | Meaning |
|------|---------|
| 0 | All scenarios passed (or pending allowed via config) |
| 1 | One or more scenarios failed, or pending steps when not allowed |
| 2 | Planning failure (undefined steps, parse errors, config errors, no features found) |
| 3 | Runner crash (unexpected exception) |

### `sl fmt --check`
| Code | Meaning |
|------|---------|
| 0 | All files valid (parse OK, roundtrip OK) |
| 1 | One or more files invalid (parse errors or roundtrip mismatch) |
| 2 | No `.feature` files found, or path doesn't exist |

### `sl fmt --write`
| Code | Meaning |
|------|---------|
| 0 | All files processed successfully (some may have been reformatted) |
| 1 | One or more files had parse errors (skipped, not modified) |
| 2 | No `.feature` files found, or path doesn't exist |

### `sl fmt --canonical`
| Code | Meaning |
|------|---------|
| 0 | Success (formatted output printed to stdout) |
| 1 | File has parse errors |
| 2 | Path doesn't exist or I/O error |

### `sl gherkin fuzz`
| Code | Meaning |
|------|---------|
| 0 | All trials passed |
| 1 | One or more trials failed (failures saved to artifacts dir) |

### `sl gherkin ddmin`
| Code | Meaning |
|------|---------|
| 0 | Minimization succeeded (signatures match) |
| 1 | Minimization failed or input doesn't produce a failure |

### `sl verify`
| Code | Meaning |
|------|---------|
| 0 | All checks passed |
| 1 | One or more checks failed |
| 2 | Verify itself errored (bug/unexpected exception) |

**Default (fast):** CLI wiring, smoke fixtures

**With `--ci`:** Adds kaocha tests, compliance suite, fuzz smoke

**With `--fuzzed`:** Checks fuzz artifact integrity (opt-in, can be slow with large artifact sets)

---

## License

MIT (see LICENSE file)
