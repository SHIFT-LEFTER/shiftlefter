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