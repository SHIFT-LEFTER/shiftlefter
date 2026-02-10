# Contributing to ShiftLefter

## Prerequisites

- **Java 11+** — `java -version`
- **Clojure CLI** — `clj --version` ([install guide](https://clojure.org/guides/install_clojure) or `brew install clojure`)
- **Git**

## Setup

```bash
git clone https://github.com/SHIFT-LEFTER/shiftlefter.git
cd shiftlefter
cp shiftlefter.edn.example shiftlefter.edn
bin/kaocha        # run tests — should pass
bin/kondo         # lint — should report 0 errors
```

## Development Workflow

### Start the REPL

```bash
bin/bounce-repl
```

This starts an nREPL server with CIDER middleware. Connect your IDE (VS Code/Calva, Emacs/CIDER) to the port printed at startup.

The dev REPL reads directly from `src/` — no JAR rebuild needed.

### Run Tests

```bash
bin/kaocha                    # full test suite
bin/kaocha --focus ns-name    # single namespace
bin/compliance                # Cucumber compliance tests
bin/kondo                     # linter (0 errors required)
```

### Build the JAR

```bash
clj -T:build uberjar
java -jar target/shiftlefter.jar --help
```

## Test Commands

| Command | What it does |
|---|---|
| `bin/kaocha` | Full test suite (1000+ tests) |
| `bin/compliance` | Cucumber parser compliance (46 good, 11 bad) |
| `bin/kondo` | clj-kondo linter |
| `sl gherkin fuzz` | Fuzz testing with generated inputs |
| `sl verify --ci` | Full CI validation suite |

## Project Structure

```
src/shiftlefter/           # Framework source
  gherkin/                 #   Parser, tokenizer, pickler, formatter
  runner/                  #   Step loader, executor
  stepengine/              #   Step registry, defstep macro
  repl.clj                #   REPL utilities
  core.clj                #   CLI entry point
test/                      # Tests (fixtures, compliance, property-based)
docs/                      # User-facing documentation
examples/                  # Tutorial examples (01-validate, 02-browser, 03-custom)
bin/                       # Dev scripts (kaocha, kondo, compliance, bounce-repl)
release/                   # Release packaging (sl wrapper for distribution zip)
```

## Conventions

- **Branch naming:** `wi-NNN-NNN-slug--YYYYMMDD`
- **Commit messages:** Include WI ID (e.g., `WI-033.010: Description`)
- **Style:** Pure functions, immutability, specs (`clojure.spec.alpha`), `defstep` for step definitions
- **Tests required:** Add or update tests for any code change. Run `bin/kaocha` before committing.

## What's Bundled

ShiftLefter bundles 8 third-party Clojure libraries (Cheshire, babashka.fs, etaoin, core.async, spec.alpha, test.check, nREPL, CIDER middleware). See [docs/CAPABILITIES.md](docs/CAPABILITIES.md) for the full list with versions and examples.
