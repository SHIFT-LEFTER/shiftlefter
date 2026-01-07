# ShiftLefter Gherkin Parser

**100% Cucumber-compatible Gherkin parser and formatter** with lossless roundtrip guarantees, built in Clojure.

## Status: v0.1.1 - Runner Complete

ShiftLefter provides a complete BDD testing framework with Gherkin parsing, validation, formatting, and test execution. The parser is 100% Cucumber-compatible; the runner executes scenarios with step definitions written in Clojure.

**Current:** Parser, formatter, runner with step binding and execution
**Next:** Macro expansion (`+` syntax), reporter plugins, IDE integration
**Future:** Traceability graph, cross-language step executors

---

## Install

### Binary Release (Recommended)

**Requirements:** Java 11 or later

1. Download `shiftlefter-v0.1.0.jar` from [releases](https://github.com/YOU/shiftlefter/releases)
2. Verify Java version: `java -version`
3. Run the jar:
   ```bash
   java -jar shiftlefter-v0.1.0.jar fmt --check your-file.feature
   ```

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

## CLI

The `bin/sl` command provides test execution, file validation, and formatting.

### Running Tests

```bash
# Run all feature files in a directory
bin/sl run features/ --step-paths steps/

# Run specific files
bin/sl run features/login.feature features/checkout.feature --step-paths steps/

# Dry-run (verify bindings without executing)
bin/sl run features/ --step-paths steps/ --dry-run

# Machine-readable EDN output
bin/sl run features/ --step-paths steps/ --edn

# Verbose output (show each step)
bin/sl run features/ --step-paths steps/ -v
```

**CLI Flags:**

| Flag | Config equivalent | Description |
|------|-------------------|-------------|
| `--step-paths p1,p2` | `:runner {:step-paths [...]}` | Comma-separated paths to step definition directories |
| `--dry-run` | — | Bind steps but don't execute (verify all steps have definitions) |
| `--edn` | — | Output results as EDN to stdout |
| `-v, --verbose` | — | Show each step as it executes |

### Validation & Formatting

```bash
# Validate files/directories (checks parse + lossless roundtrip)
bin/sl fmt --check path/to/file.feature
bin/sl fmt --check features/           # recursive directory

# Format files in place (canonical style)
bin/sl fmt --write path/to/file.feature
bin/sl fmt --write features/           # recursive directory

# Canonical formatting to stdout (single file)
bin/sl fmt --canonical path/to/file.feature
```

### Other Commands

```bash
# Fuzz testing (generate random valid Gherkin, verify invariants)
bin/sl gherkin fuzz --preset smoke

# Verify repo health (fast validator checks)
bin/sl verify

# Full CI verification (includes test suite, compliance, fuzz smoke)
bin/sl verify --ci

# Machine-readable output
bin/sl verify --edn
```

---

## Writing Step Definitions

Step definitions bind Gherkin steps to Clojure functions. Create `.clj` files in your step-paths directory:

```clojure
;; steps/login_steps.clj
(ns my-project.steps.login
  (:require [shiftlefter.stepengine.registry :refer [defstep]]))

;; Simple step (no context needed)
(defstep #"I am on the login page"
  []
  {:page :login})

;; Step with captures (regex groups become arguments)
(defstep #"I enter \"([^\"]+)\" and \"([^\"]+)\""
  [username password]
  {:username username :password password})

;; Step with context (receives accumulated state from prior steps)
(defstep #"I should see the dashboard"
  [ctx]
  (when-not (:logged-in ctx)
    (throw (ex-info "Not logged in!" {:ctx ctx})))
  ctx)

;; Step with captures AND context (ctx is always last)
(defstep #"I have (\d+) items in my cart"
  [count-str ctx]
  (assoc ctx :cart-count (Integer/parseInt count-str)))
```

**Return values:**
- **Map** → becomes the new context for subsequent steps
- **nil** → context unchanged
- **`:pending`** → marks step as pending (scenario fails unless `allow-pending?` is true)
- **Throw** → step fails with error

**Context structure:**
The `ctx` argument passed to step functions is `{:step <current-step> :scenario <accumulated-state>}`. Access your accumulated state via `(:scenario ctx)`:

```clojure
(defstep #"I should have (\d+) items"
  [expected-str ctx]
  (let [expected (Integer/parseInt expected-str)
        actual (:cart-count (:scenario ctx))]
    (when (not= expected actual)
      (throw (ex-info "Count mismatch" {:expected expected :actual actual})))
    (:scenario ctx)))
```

---

## Configuration

ShiftLefter looks for `shiftlefter.edn` in the current directory. Copy `shiftlefter.edn.example` to get started:

```clojure
;; shiftlefter.edn
{:parser {:dialect "en"}       ;; Language dialect (en, fr, de, etc.)

 :runner {:step-paths ["steps/"]      ;; Where to find step definitions
          :allow-pending? false}}     ;; Exit 0 even with pending steps?
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

;; Define steps
(defstep #"I have (\d+) cucumbers" [n]
  {:count (Integer/parseInt n)})

(defstep #"I eat (\d+)" [n ctx]
  (update (:scenario ctx) :count - (Integer/parseInt n)))

(defstep #"I should have (\d+) left" [n ctx]
  (let [expected (Integer/parseInt n)
        actual (:count (:scenario ctx))]
    (when (not= expected actual)
      (throw (ex-info "Mismatch" {:expected expected :actual actual})))
    (:scenario ctx)))
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

## Roadmap

### Future: Macros
ShiftLefter will support `+` macro syntax for executable domain requirements:

```gherkin
Feature: Demo
  Scenario: Login
    Given Log in as admin +
```

Expands to pre-defined step sequences with provenance tracking. This bridges the gap between stakeholder language and executable tests—inspired by Christopher Alexander's *Notes on the Synthesis of Form* and the concept of separating concerns across requirement/implementation planes.

### Future: Runner & Traceability
- Event-native test runner
- Requirements traceability graph
- Language-agnostic step executors

---

## Repository Layout

**What ships in the JAR:**
```
src/                           # Framework source code
resources/shiftlefter/         # Runtime data and templates
├── gherkin/i18n.json         # Dialect definitions (70+ languages)
└── templates/shiftlefter.edn # Config template for `sl init` (future)
```

**What doesn't ship:**
```
test/                          # Framework tests
├── fixtures/gherkin/         # Parser test fixtures
│   ├── invalid/              # Curated invalid files (error snapshots)
│   ├── stress/               # Edge case torture tests
│   ├── encoding/             # UTF-8 fixtures
│   ├── eol-types/            # Line ending fixtures
│   └── outlines/             # Scenario outline fixtures
├── fixtures/macros/          # Macro test fixtures
└── shiftlefter-test.edn      # Test configuration

examples/quickstart/           # User-facing demo (not in JAR)
└── features/                 # Sample .feature files

compliance/                    # Cucumber official test suite (git submodule)
fuzz/artifacts/               # Generated fuzz failures (gitignored)
```

**Configuration:**
- `shiftlefter.edn.example` — Template config (tracked, copy to `shiftlefter.edn`)
- `shiftlefter.edn` — User config (gitignored)

---

## Testing

**474 tests, 1424 assertions, 0 failures**

```bash
# Run full test suite
bin/kaocha

# Run compliance tests
bin/compliance

# Fuzz testing
bin/sl gherkin fuzz --trials 1000 --seed 12345
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
| 1 | File has parse errors, or contains unsupported constructs (e.g., `Rule:`) |
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

**Default (fast):** CLI wiring, smoke fixtures, fuzz artifact integrity
**With `--ci`:** Adds kaocha tests, compliance suite, fuzz smoke

---

## License

MIT (see LICENSE file)