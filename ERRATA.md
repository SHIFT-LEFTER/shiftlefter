# ERRATA.md — Known Oddities and Workarounds

This file documents deviations, inconsistencies, and workarounds we've encountered—particularly in upstream dependencies or specifications. Each entry explains what we found, why it matters, and how we handle it.

---

## E001: Inconsistent JSON Formatting in Cucumber Compliance Files

**Date discovered**: 2025-12-28

**Source**: `cucumber/gherkin` testdata (compliance suite)

**Description**:
The official Cucumber compliance test files (`.ast.ndjson`, `.pickles.ndjson`) have inconsistent JSON formatting:

- Some files use compact format (no spaces after separators):
  ```json
  {"gherkinDocument":{"comments":[],"feature":{...}}}
  ```

- Some files use spaced format (space after `:` and `,`):
  ```json
  {"gherkinDocument": {"comments": [], "feature": {...}}}
  ```

Examples:
- `background.feature.ast.ndjson` → compact
- `conjunctions.feature.ast.ndjson` → spaced
- `descriptions.feature.ast.ndjson` → compact

**Impact**:
Byte-for-byte string comparison fails even when JSON is semantically identical.

**Our workaround**:
We compare AST and pickle output using parsed JSON equality (`json/parse-string`) instead of string equality. This is semantically correct but means we're not doing strict byte-for-byte compliance.

**Upstream status**: Not reported. This appears to be an artifact of different tool versions generating the expected files at different times.

---

## E002: Canonical Formatter Does Not Support Rule: Blocks

**Date discovered**: 2025-12-29

**Source**: ShiftLefter canonical formatter (`shiftlefter.gherkin.printer/canonical`)

**Description**:
The canonical formatter (which produces deterministic, prettified Gherkin output) does not yet support `Rule:` blocks. When a file contains `Rule:` blocks, the canonical formatter returns an error instead of silently producing incorrect output.

Functions affected:
- `format-canonical` returns `{:status :error :reason :canonical/rules-unsupported ...}`
- `fmt-canonical` returns the same error map with `:path`
- `canonical` throws `ExceptionInfo` with the error details

**Impact**:
Files containing `Rule:` blocks cannot be canonically formatted. This is a known limitation, not a bug.

**Our workaround**:
Use the lossless roundtrip functions instead for files with rules:
- `print-tokens` — concatenates token `:raw` fields for byte-perfect roundtrip
- `roundtrip` / `roundtrip-ok?` — string-based roundtrip helpers
- `fmt-check` — file-based roundtrip verification

These functions work with all Gherkin constructs including `Rule:` blocks.

**Upstream status**: Internal limitation. May be lifted in future when canonical formatting for rules is implemented.

---

## E003: Canonical Formatter Normalizes Line Endings to LF

**Date discovered**: 2025-12-29

**Source**: ShiftLefter canonical formatter (`shiftlefter.gherkin.printer/canonical`)

**Description**:
The canonical formatter normalizes all line endings to LF (`\n`), regardless of the original file's line endings. This means:
- CRLF (`\r\n`) → LF (`\n`)
- CR (`\r`) → LF (`\n`)
- Mixed line endings → all LF (`\n`)

This is intentional behavior for deterministic output.

**Impact**:
After canonical formatting, a file's line endings will all be LF. This may cause diff noise when formatting files with CRLF line endings (common on Windows).

**Our workaround**:
For byte-perfect roundtrip that preserves original line endings, use the lossless functions instead:
- `print-tokens` — preserves exact line endings from the original file
- `roundtrip` / `roundtrip-ok?` — string-based lossless roundtrip

The lexer (`lex`) preserves all line ending types (LF, CRLF, CR, mixed) in token `:raw` fields.

**Upstream status**: By design. Canonical formatting is meant to produce deterministic output; LF is the standard Unix line ending.

---

## E004: Canonical Formatter Normalizes Tag Spacing

**Date discovered**: 2025-12-29

**Source**: ShiftLefter canonical formatter (`shiftlefter.gherkin.printer/canonical`)

**Description**:
The canonical formatter normalizes tag spacing to a single space between tags, regardless of the original spacing. This means:
- Multiple spaces: `@smoke   @slow` → `@smoke @slow`
- Tabs: `@smoke\t@slow` → `@smoke @slow`
- Joined tags: `@tag1@tag2` → `@tag1 @tag2`

This is intentional behavior for deterministic output.

**Impact**:
After canonical formatting, unusual tag spacing will be normalized. This is generally desirable for consistency but means the canonical output won't match the original byte-for-byte if the original had unusual spacing.

**Our workaround**:
For byte-perfect roundtrip that preserves original tag spacing, use the lossless functions instead:
- `print-tokens` — preserves exact spacing from the original file
- `roundtrip` / `roundtrip-ok?` — string-based lossless roundtrip

The lexer preserves all original spacing in token `:raw` fields.

**Upstream status**: By design. Canonical formatting is meant to produce normalized, consistent output.

---

## E005: Encoding Header (`# encoding:`) Is Ignored

**Date discovered**: 2025-12-30

**Source**: ShiftLefter parser (`shiftlefter.gherkin.lexer`)

**Description**:
The Gherkin specification allows an optional `# encoding: <charset>` comment at the start of a file to declare the file's character encoding. ShiftLefter currently ignores this header and always assumes UTF-8.

```gherkin
# encoding: iso-8859-1
Feature: This file claims to be ISO-8859-1
```

The parser will:
1. Parse the line as a regular comment
2. Continue assuming UTF-8 encoding
3. Fail with `:io/utf8-decode-failed` if the file contains non-UTF-8 bytes

**Impact**:
Files that declare a non-UTF-8 encoding and contain bytes invalid in UTF-8 will fail to parse. This is rare in practice since most Gherkin files are UTF-8.

**Our workaround**:
Convert files to UTF-8 before processing. Most editors and `iconv` can do this:
```bash
iconv -f ISO-8859-1 -t UTF-8 input.feature > output.feature
```

**Upstream status**: By design for now. UTF-8 is the de facto standard for source files. Supporting legacy encodings adds complexity with minimal benefit. May revisit if real-world demand emerges.

---

## Template for New Entries

```markdown
## EXXX: Short Title

**Date discovered**: YYYY-MM-DD

**Source**: Where did this come from?

**Description**:
What's the issue?

**Impact**:
How does it affect us?

**Our workaround**:
What did we do about it?

**Upstream status**: Reported? Fixed? Wontfix?
```
