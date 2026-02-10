# Fuzz Testing Guide

ShiftLefter includes a comprehensive fuzz testing harness for stress-testing the Gherkin parser. The fuzzer operates in two modes:

1. **Valid Generation Mode** (FZ1/FZ2) - Generates valid Gherkin and verifies the parser handles it correctly
2. **Mutation Mode** (FZ3) - Corrupts valid Gherkin and verifies the parser fails gracefully

## Quick Start

```bash
# Valid generation (default) - verify parser handles valid input
sl gherkin fuzz --preset smoke

# Mutation fuzzing - verify parser handles corrupted input gracefully
sl gherkin fuzz --mutation --preset smoke
```

## Modes

### Valid Generation Mode (FZ1/FZ2)

Generates random valid Gherkin files and verifies:
- Parse succeeds without errors
- Pickle succeeds without errors
- Lossless roundtrip (lex → print-tokens == original)
- Canonical formatting is idempotent

```bash
# Basic usage
sl gherkin fuzz --preset quick          # 100 trials
sl gherkin fuzz --preset nightly        # 10000 trials
sl gherkin fuzz --seed 12345 --trials 50

# With verbose output
sl gherkin fuzz --preset smoke -v
```

### Mutation Mode (FZ3)

Takes valid Gherkin (generated or from corpus), corrupts it with mutators, and verifies the parser fails gracefully. A trial **passes** when:
- Parser returns structured errors (not exceptions)
- Parser terminates quickly (no hangs/timeouts)

```bash
# Basic mutation fuzzing
sl gherkin fuzz --mutation --preset smoke

# Using compliance files as source
sl gherkin fuzz --mutation --sources corpus

# Both generated and corpus
sl gherkin fuzz --mutation --sources both

# Custom timeout and combo count
sl gherkin fuzz --mutation --timeout-ms 500 --combos 2
```

## CLI Options

### Common Options

| Option | Description | Default |
|--------|-------------|---------|
| `--preset PRESET` | smoke (10), quick (100), nightly (10000) | - |
| `--seed N` | Random seed for reproducibility | current time |
| `--trials N` | Number of trials | 100 |
| `--save PATH` | Directory to save artifacts | fuzz/artifacts |
| `-v, --verbose` | Print progress | false |

### Mutation Mode Options

| Option | Description | Default |
|--------|-------------|---------|
| `--mutation` | Enable mutation fuzzing mode | false |
| `--sources SOURCE` | generated, corpus, or both | generated |
| `--corpus-dir PATH` | Path to corpus files | compliance/gherkin/testdata/good |
| `--timeout-ms N` | Per-parse timeout in ms | 200 |
| `--combos N` | Combo mutations per source | 1 |

## Mutators

FZ3 includes 6 mutator types:

| Mutator | Description |
|---------|-------------|
| `:mut/indent-damage` | Remove or double leading whitespace |
| `:mut/delimiter-removal` | Remove `\|` or `"""` delimiters |
| `:mut/table-corrupt` | Add/remove cells (breaks cell count consistency) |
| `:mut/docstring-delim` | Remove opening or closing `"""` |
| `:mut/keyword-perturb` | Typos in keywords (Scenaro, Feture, etc.) |
| `:mut/colon-perturb` | Remove colon from keyword lines |

**Combo mutations**: Apply 2 different mutators in sequence to catch "survives individually, breaks together" bugs.

## Result Types

### Valid Generation Mode

| Status | Reason | Meaning |
|--------|--------|---------|
| `:ok` | - | All invariants passed |
| `:fail` | `:parse-errors` | Parser returned errors |
| `:fail` | `:pickle-errors` | Pickler returned errors |
| `:fail` | `:roundtrip-mismatch` | Roundtrip didn't match |
| `:fail` | `:canonical-not-idempotent` | Canonical format unstable |
| `:fail` | `:uncaught-exception` | Unexpected exception |

### Mutation Mode

| Status | Reason | Meaning |
|--------|--------|---------|
| `:ok` | `:graceful-errors` | Parser returned structured errors (PASS) |
| `:ok` | `:mutation-survived` | Mutation didn't break parsing (tracked) |
| `:fail` | `:timeout` | Parse exceeded timeout |
| `:fail` | `:uncaught-exception` | Parser threw exception |

## Artifact Structure

When failures occur (or new signatures are discovered), artifacts are saved:

```
fuzz/artifacts/
└── 2026-01-02T03:57:34-trial-0-mut-5-colon-perturb/
    ├── case.feature    # The mutated/generated content
    ├── meta.edn        # Seed, version, options, timestamp
    └── result.edn      # Full result with signature, timing, errors
```

### meta.edn Example

```clojure
{:seed 12345
 :trial-idx 0
 :mutation-idx 5
 :mutator-version [1 0]
 :generator-version [2 0]
 :git-sha "abc123"
 :timestamp "2026-01-02T03:57:34.123Z"
 :fuzz/mode :mutation
 :fuzz/timeout-ms 200
 :opts {:trials 10 :sources :generated}}
```

### result.edn Example

```clojure
{:status :ok
 :reason :graceful-errors
 :phase :parse
 :mutation {:mutator/type :mut/colon-perturb
            :source {:kind :generated}
            :idx 5}
 :signature {:mutator/type :mut/colon-perturb
             :phase :parse
             :error/type :gherkin/unexpected-token}
 :details {:error-count 1
           :errors [{:type :gherkin/unexpected-token ...}]
           :first-location {:line 1 :column 1}}
 :timing {:parse-ms 3
          :total-ms 5}}
```

## Signature Deduplication

Mutation mode uses signatures to avoid saving duplicate artifacts:

```clojure
{:mutator/type :mut/keyword-perturb
 :phase :parse
 :error/type :gherkin/unexpected-token}
```

Only the first occurrence of each unique signature is saved.

## Versioning

- **generator-version** `[2 0]` - Bump when valid generation logic changes
- **mutator-version** `[1 0]` - Bump when mutation logic changes

These versions are stored in meta.edn for reproducibility.

## Delta-Debugging Minimization (T1)

When fuzzing produces a failure, the resulting `.feature` file may contain irrelevant content. The `ddmin` command shrinks failing files to minimal reproducible cases while preserving the same failure signature.

### Quick Start

```bash
# Minimize a failing feature file
sl gherkin ddmin /path/to/case.feature --mode parse

# Minimize a fuzz artifact directory
sl gherkin ddmin fuzz/artifacts/2026-01-02T.../
```

### Strategies

| Strategy | Description | When Used |
|----------|-------------|-----------|
| `structured` | Removes whole constructs (scenarios, steps, examples) | Default for parse/pickles mode |
| `raw-lines` | Removes arbitrary lines | Default for lex mode, fallback if parse fails |

```bash
# Force raw-lines strategy
sl gherkin ddmin case.feature --mode parse --strategy raw-lines
```

### CLI Options

| Option | Description | Default |
|--------|-------------|---------|
| `--mode MODE` | parse, pickles, lex, or auto | auto |
| `--strategy STRATEGY` | structured or raw-lines | inferred |
| `--timeout-ms N` | Per-check timeout | 200 |
| `--budget-ms N` | Global time budget | 30000 |

### Artifact Integration

When run on a fuzz artifact directory, ddmin:
1. Reads `case.feature` and `result.edn` for baseline signature
2. Minimizes while preserving the same failure
3. Writes `min.feature` and `ddmin.edn`

```
fuzz/artifacts/2026-01-02T.../
├── case.feature      # Original mutated content
├── meta.edn          # Fuzz metadata
├── result.edn        # Original failure result
├── min.feature       # Minimized version (after ddmin)
└── ddmin.edn         # Minimization stats
```

### REPL Usage

```clojure
(require '[shiftlefter.gherkin.ddmin :as ddmin])

;; Minimize content directly
(def result (ddmin/ddmin "Feture: Test\n  Scenario: S\n    Given step\n"
                         {:mode :parse :timeout-ms 200}))
(:minimized result)  ; => "Feture: Test\n"
(:reduction-ratio result)  ; => ~0.28

;; Process a fuzz artifact
(ddmin/ddmin-artifact "fuzz/artifacts/2026-01-02T.../" {:timeout-ms 200})
```

## CI Integration

```bash
# Quick validation in CI
sl gherkin fuzz --preset smoke
sl gherkin fuzz --mutation --preset smoke

# Longer nightly runs
sl gherkin fuzz --preset nightly
sl gherkin fuzz --mutation --preset nightly --sources both
```

Exit codes:
- `0` - All trials passed
- `1` - One or more trials failed (timeouts, exceptions)

## REPL Usage

```clojure
(require '[shiftlefter.gherkin.fuzz :as fuzz])

;; Valid generation
(fuzz/run {:preset :smoke :verbose true})

;; Mutation fuzzing
(fuzz/run-mutations {:preset :smoke :verbose true})

;; Generate a single feature
(let [rng (java.util.Random. 42)]
  (fuzz/generate-feature rng 0))

;; Apply a single mutator
(fuzz/apply-mutator
  (-> fuzz/mutators first :fn)
  "Feature: Test\n  Scenario: S\n    Given step\n"
  (java.util.Random. 42))

;; Check mutation invariants directly
(fuzz/check-mutation-invariants
  "Feture: Typo\n"  ; intentional typo
  {:mutator/type :mut/keyword-perturb :idx 0}
  200)
```
