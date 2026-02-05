# ShiftLefter

**Gherkin test framework with vocabulary validation** — parse, format, run, and catch mistakes before execution. 100% Cucumber-compatible parser, built in Clojure.

## Status: v0.3.5 - Browser Testing & Polish

ShiftLefter provides a complete BDD testing framework with Gherkin parsing, validation, formatting, and test execution. The parser is 100% Cucumber-compatible; the runner executes scenarios with step definitions written in Clojure.

**Current:** Parser, formatter, runner, macro expansion, multi-actor browser testing, SVO validation, tutorial examples

**Next:** Executable traceability, intent regions, supplanting what the screenplay pattern was aiming for with the data-oriented, queryable, far simpler SVOI

**Future:** Use case ↔ feature mapping & generation, observation lineage from real-world requirements through to running tests

---

## Install

### Binary Release (Recommended)

**Requirements:** Java 11 or later

1. Download `shiftlefter-v0.3.5.zip` from [releases](https://github.com/SHIFT-LEFTER/shiftlefter/releases)
2. Unzip and add to PATH:
   ```bash
   unzip shiftlefter-v0.3.5.zip
   export PATH="$PATH:$PWD/shiftlefter-v0.3.5"
   ```
3. Run:
   ```bash
   sl fmt --check your-file.feature
   sl run features/ --step-paths steps/
   ```

The zip contains the `sl` wrapper script and a versioned JAR. No Clojure toolchain needed — just Java.

### From Source (Development)

**Requirements:** Clojure CLI tools

1. Install Clojure: [Official guide](https://clojure.org/guides/install_clojure) or `brew install clojure`
2. Clone this repository
3. Run from source:
   ```bash
   bin/sl fmt --check your-file.feature
   ```
4. Or build the uberjar yourself:
   ```bash
   clojure -T:build uberjar
   java -jar target/shiftlefter.jar fmt --check your-file.feature
   ```

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

Step definitions bind Gherkin steps to Clojure functions. Create `.clj` files in your step-paths directory:

```clojure
;; steps/login_steps.clj
(ns my-project.steps.login
  (:require [shiftlefter.stepengine.registry :refer [defstep]]
            [shiftlefter.step :as step]))

;; Simple step (no captures)
(defstep #"I am on the login page"
  [ctx]
  (assoc ctx :page :login))

;; Step with captures (regex groups become arguments)
(defstep #"I enter \"([^\"]+)\" and \"([^\"]+)\""
  [ctx username password]
  (assoc ctx :username username :password password))

;; Step with context only (no captures)
(defstep #"I should see the dashboard"
  [ctx]
  (when-not (:logged-in ctx)
    (throw (ex-info "Not logged in!" {:ctx ctx})))
  ctx)

;; Step with captures AND context (ctx is always first)
(defstep #"I have (\d+) items in my cart"
  [ctx count-str]
  (assoc ctx :cart-count (Integer/parseInt count-str)))
```

**Convention:** `ctx` is always the **first** argument, followed by regex captures in order. Steps that don't need context can omit it entirely (`[]`).

**Return values:**

- **Map** → becomes the new context for subsequent steps
- **nil** → context unchanged
- **`:pending`** → marks step as pending (scenario fails unless `allow-pending?` is true)
- **Throw** → step fails with error

**Context structure:**
The `ctx` argument is a flat map — your accumulated state is directly on it, not nested:

```clojure
(defstep #"I should have (\d+) items"
  [ctx expected-str]
  (let [expected (Integer/parseInt expected-str)
        actual (:cart-count ctx)]
    (when (not= expected actual)
      (throw (ex-info "Count mismatch" {:expected expected :actual actual})))
    ctx))
```

**DataTable / DocString access:**
Steps with a DataTable or DocString attached access them via `(step/arguments ctx)`:

```clojure
(defstep #"the following users exist:" [ctx]
  (let [{:keys [rows]} (step/arguments ctx)
        headers (first rows)
        data (rest rows)]
    (assoc ctx :users data)))
```

---

## SVO Validation (Optional)

Add type-checking to your step definitions with Subject-Verb-Object validation. See `docs/SVO.md` for full documentation.

```clojure
;; Step with SVO metadata
(defstep #"^(\w+) clicks the (.+)$"
  {:interface :web
   :svo {:subject :$1 :verb :click :object :$2}}
  [ctx subject element]
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

For interactive development and experimentation, use `shiftlefter.repl`:

```clojure
(require '[shiftlefter.repl :as repl])
(require '[shiftlefter.stepengine.registry :refer [defstep]])

;; Define steps — ctx is always first, followed by captures
(defstep #"I have (\d+) cucumbers" [ctx n]
  (assoc ctx :count (Integer/parseInt n)))

(defstep #"I eat (\d+)" [ctx n]
  (update ctx :count - (Integer/parseInt n)))

(defstep #"I should have (\d+) left" [ctx n]
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
      Given I have 12 cucumbers
      When I eat 5
      Then I should have 7 left")
;; => {:status :ok :summary {:scenarios 1 :passed 1 :failed 0 :pending 0}}

;; Dry-run (bind without executing)
(repl/run-dry "Feature: X\n  Scenario: Y\n    Given I have 5 cucumbers")
;; => {:status :ok :plans [...]}
```

### Free Mode (no Feature/Scenario wrapper)

For quick experimentation, execute steps directly without Gherkin structure:

```clojure
;; Execute steps one at a time
(repl/step "I have 12 cucumbers")
;; => {:status :passed :ctx {:count 12}}

(repl/step "I eat 5")
;; => {:status :passed :ctx {:count 7}}

;; Context accumulates across calls
(repl/ctx)
;; => {:count 7}

;; Reset for new session
(repl/reset-ctx!)
```

### Named Contexts (multi-actor sessions)

Use `free` for multi-user scenarios where each actor has separate state:

```clojure
;; Each session maintains its own context
(repl/free :alice "I log in as alice")
(repl/free :bob "I log in as bob")
(repl/free :alice "I create a post")
(repl/free :bob "I see alice's post")

;; Multiple steps in one call
(repl/free :alice "I write a comment" "I submit")

;; Inspect specific context
(repl/ctx :alice)
;; => {:user "alice" :posts [...]}

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
;; => ["I have (\\d+) cucumbers" "I eat (\\d+)" ...]

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

- [docs/SVO.md](docs/SVO.md) — SVO validation guide (glossaries, defstep metadata, migration)
- [docs/GLOSSARY.md](docs/GLOSSARY.md) — Terminology reference
- [docs/FUZZ.md](docs/FUZZ.md) — Fuzz testing and delta debugging
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

**945 tests, 2869 assertions, 0 failures**

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