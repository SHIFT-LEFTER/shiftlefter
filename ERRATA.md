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

## E002: ~~Canonical Formatter Does Not Support Rule: Blocks~~ (RESOLVED)

**Date discovered**: 2025-12-29
**Date resolved**: 2026-01-01

**Source**: ShiftLefter canonical formatter (`shiftlefter.gherkin.printer/canonical`)

**Resolution**:
Canonical formatting now fully supports `Rule:` blocks. All 46 Cucumber compliance files (including those with Rules) format correctly.

**Original issue (historical)**:
The canonical formatter previously returned an error for files containing `Rule:` blocks. This was a temporary limitation during development, now lifted.

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

## E006: Pickle Extensions (ShiftLefter-only)

**Date discovered**: 2026-01-05

**Source**: ShiftLefter pickler (`shiftlefter.gherkin.pickler`)

**Description**:
ShiftLefter adds several fields to pickles that are **not part of the Cucumber Gherkin standard**. These are ShiftLefter extensions for enhanced traceability, particularly for Scenario Outline provenance.

**Pickle-level extensions:**
- `:pickle/template-name` — For outline-generated pickles, the name of the originating Scenario Outline (nil for regular scenarios)
- `:pickle/row-index` — 0-based index of the Examples row that generated this pickle (nil for regular scenarios)
- `:pickle/row-values` — Map of placeholder names to substituted values for this row (nil for regular scenarios)

**Step-level extensions:**
- `:step/template-text` — For outline-generated steps, the pre-substitution text with `<placeholders>` intact (nil for regular steps)
- `:step/origin` — Source of the step: `:feature-background`, `:rule-background`, or `:scenario`

**Impact**:
Tools consuming ShiftLefter pickle output should be aware these fields are non-standard. Downstream processors that expect strict Cucumber pickle format should ignore these fields or strip them before forwarding.

**Our workaround**:
The compliance projection layer (`shiftlefter.gherkin.compliance`) strips these fields when generating Cucumber-compatible output for compliance testing.

**Upstream status**: By design. These extensions enable ShiftLefter's traceability features (macro provenance, outline debugging, requirement linking).

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
