# ShiftLefter Gherkin Parser

**100% Cucumber-compatible Gherkin parser and formatter** with lossless roundtrip guarantees, built in Clojure.

## Status: v0.1.0 - Parser Core

ShiftLefter provides a solid foundation for BDD tooling with complete Gherkin parsing, validation, and formatting. This parser is the first component of a larger vision for executable domain requirements and traceability—but v0.1.0 focuses on getting the parsing layer bulletproof.

**Current:** Single-file validation, lossless roundtrip, canonical formatting  
**Next (v0.2.0, this week):** Multi-file validation, in-place formatting, production CLI  
**Future:** Macro expansion (`+` syntax), executable runner, traceability graph

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

The `bin/sl` command provides file validation and formatting:

```bash
# Validate a feature file (checks parse + lossless roundtrip)
bin/sl fmt --check path/to/file.feature

# Canonical formatting (normalizes whitespace, prints to stdout)
bin/sl fmt --canonical path/to/file.feature

# Fuzz testing (generate random valid Gherkin, verify invariants)
bin/sl gherkin fuzz --preset smoke
```

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
- **Does NOT support `Rule:` blocks** — returns error instead of silent data loss
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

### v0.2.0 (This Week)
- Multi-file validation (`bin/sl fmt --check dir/`)
- In-place formatting (`bin/sl fmt --write`)
- Production CLI (progress indicators, summary stats, colorized output)

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

## Testing

**268 tests, 829 assertions, 0 failures**

```bash
# Run full test suite
bin/kaocha

# Run compliance tests
bin/compliance

# Fuzz testing
bin/sl gherkin fuzz --trials 1000 --seed 12345
```

---

## License

MIT (see LICENSE file)