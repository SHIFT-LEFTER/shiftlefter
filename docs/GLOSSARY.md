# ShiftLefter Glossary

Internal terminology reference for consistency across documentation and code.

> **Status:** Living document. Terms will be refined as usage solidifies.

---

## Cucumber/Gherkin Terms

These terms are defined by the Cucumber project. See the
[official Gherkin reference](https://cucumber.io/docs/gherkin/reference/)
for canonical definitions.

| Term | Description |
|------|-------------|
| **Background** | Steps that run before each Scenario in a Feature. Shared setup. |
| **Data Table** | Tabular data passed as an argument to a step. Pipe-delimited rows. |
| **Dialect** | Language-specific keywords (e.g., "Feature" in English, "Fonctionnalité" in French). Loaded from `i18n.json`. |
| **Docstring** | Multi-line text block argument, delimited by `"""` or `` ``` ``. |
| **Examples** | Table of values for Scenario Outline parameter substitution. |
| **Rule** | Gherkin keyword for grouping related Scenarios under a business rule. |
| **Scenario Outline** | Parameterized Scenario template. Generates N Scenarios from N Example rows. |
| **Tag** | Metadata marker (e.g., `@wip`, `@slow`). Used for filtering and configuration. |

---

## ShiftLefter-Specific Terms

### Pickle

Flattened, executable representation of a test case. Similar to Cucumber pickles but with ShiftLefter extensions:

**Cucumber pickle:** Scenario → pickle (1:1), Outline → N pickles

**ShiftLefter differences:**
- `:pickle/source-file` uses our path format
- `:step/source` includes macro provenance when expanded
- Future: `:pickle/use-case-id` from `# use-case:` comments
- SVO triples computed in Step Engine, never stored in pickle

### Macro

Domain-level step ending in ` +` (space-plus). Expands to predefined step sequences during Pass 2.

```gherkin
Given Log in as admin +
```

Macros are defined in INI/EDN files and maintain provenance—expanded steps trace back to the original macro invocation line.

---

## Parser Architecture

### AST (Abstract Syntax Tree)

Tree representation of parsed Gherkin. ShiftLefter maintains two forms:

- **Pre-expansion AST (Pass 1):** Preserves original structure, whitespace, locations. Used for roundtrip reconstruction. Macros appear as `:macro-step` nodes.
- **Expanded AST (Pass 2):** Macros fully resolved. Optimized for execution. Not reversible to source.

### Pass 1 / Pass 2

The two-pass parser architecture:

- **Pass 1:** Lexer → Parser → Pre-expansion AST. Lossless, location-aware.
- **Pass 2:** Macro expansion + Pickling. Produces runtime-ready structures.

The printer operates only on Pass 1 output to guarantee byte-for-byte roundtrip.

### Provenance

Source mapping that traces expanded steps back to their origin. Every step from macro expansion includes `:source` metadata pointing to the original ` +` line.

### Roundtrip

The guarantee that `print(parse(input)) == input` byte-for-byte. Preserves whitespace, comments, casing, line endings. Verified via `fmt --check`.

### Token

Atomic unit from the lexer. Includes `:type`, `:text`, `:location`, and preservation metadata like `:leading-ws`.

---

## Modes

### Vanilla Mode

Legacy-compatible mode. No macro expansion—pure Gherkin as-is. For teams migrating existing Cucumber suites.

### Shifted Mode

Macro expansion enabled. ` +` steps expand to their definitions with provenance tracking. The "ShiftLefter way."

### Free Mode

No validation beyond syntax. Unknown steps are allowed. Default mode—maximal flexibility for experimentation.

### Strict Mode

Full interface enforcement. Fails on undefined verbs/nouns, missing actors, SVO violations. Opt-in via config.

### Ephemeral Mode

Default browser lifecycle. Browser spawned fresh, dies when session ends. Used by test runs.

### Persistent Mode

Browser survives JVM restarts. Chrome profile and metadata persisted to disk. Used for REPL development and long-running automation.

---

## Browser/Automation (STARLINKHORSE)

### Subject

A named persistent browser instance. Subjects survive JVM restarts by persisting Chrome metadata to `~/.shiftlefter/subjects/<name>/`.

```clojure
(init-persistent! :finance {:stealth true})
(connect-persistent! :finance)  ;; After JVM restart
(destroy-persistent! :finance)
```

### Surface

Partially implemented concept for persistent session state. Intended to abstract over different persistence mechanisms. May be superseded by Subject in current design.

### Browser-meta

Persisted connection metadata for a Subject:

```clojure
{:debug-port 9222
 :chrome-pid 12345
 :user-data-dir "~/.shiftlefter/subjects/finance/chrome-profile"
 :stealth true}
```

### Debug Port

Chrome remote debugging port (default: 9222). Used for CDP connections. ShiftLefter allocates ports starting at 9222, incrementing if in use.

### Profile Directory

Chrome user data directory for a Subject. Contains cookies, history, localStorage, etc. Located at `~/.shiftlefter/subjects/<name>/chrome-profile/`.

### CDP (Chrome DevTools Protocol)

Low-level protocol for Chrome automation. ShiftLefter probes `http://127.0.0.1:<port>/json/version` to check if Chrome is alive.

### Session ID

Ephemeral WebDriver session handle. Valid only while ChromeDriver↔Chrome connection is alive. Dies on sleep/wake. Not persisted—Subject reconnection creates new sessions.

### Stealth Mode

Anti-detection flags for browser automation:

- **Tier 1 (built-in):** `--disable-blink-features=AutomationControlled`, etc.
- **Tier 2 (manual):** ChromeDriver patching to remove `$cdc_` signatures.

See `docs/STEALTH.md` for details.

---

## Architecture/Protocols

### Capability

A named feature that a context can provide. Examples: `:cap/browser`, `:cap/database`, `:cap/api`. Capabilities are checked before step execution to ensure required resources are available.

### Protocol

In Clojure sense: an interface defining operations. ShiftLefter protocols:
- `IBrowser` — browser operations
- `IEventBus` — event publishing
- `ISessionStore` — session persistence

### IBrowser

Protocol for browser operations. Methods: `go!`, `click!`, `fill!`, `query`, `screenshot!`, etc. Implementations handle resolved locators (maps with `:q` key).

### IEventBus

Protocol for event publishing. Methods: `publish!`, `subscribe!`. Used by Runner to emit lifecycle events.

### ISessionStore

Protocol for session handle persistence. Methods: `load-session-handle`, `save-session-handle!`, `delete-session-handle!`.

### PersistentBrowser

Wrapper around `IBrowser` that auto-reconnects on session errors. Detects "invalid session id" errors and transparently reconnects to the Subject's Chrome instance.

### Adapter

Polyglot extension for running steps in other languages. Examples: Jest adapter for JavaScript, future Python/Java adapters. Communicates via event bus.

---

## Step Execution

### Step Definition (defstep)

Binds a regex pattern to a Clojure function:

```clojure
(defstep #"I have (\d+) cucumbers" [n ctx]
  (assoc (:scenario ctx) :count (Integer/parseInt n)))
```

### Step Engine

Binding and execution logic. Matches step text to definitions, extracts captures, invokes functions, manages context flow.

### Runner

Orchestration layer. Loads features, plans execution, invokes Step Engine, emits events, aggregates results.

### Reporter

Presentation layer. Consumes Runner events, produces output (console, HTML). Handles virtual lines for macro expansion display.

### Context (ctx)

Accumulated state passed between steps. Structure: `{:step <current-step> :scenario <accumulated-state>}`. Step functions receive ctx and return updated state.

### Virtual Lines

Reporter-only invention for displaying expanded macros. Real line (e.g., 5) becomes 5.1, 5.2, 5.3 for expanded steps. Not stored in AST or pickles.

---

## SVO Validation Concepts

See `docs/SVO.md` for full documentation.

### SVO (Subject-Verb-Object)

Structured step syntax pattern for validation. Steps with SVO metadata are validated against glossaries at bind time.

```clojure
(defstep #"^(\w+) clicks the (.+)$"
  {:interface :web
   :svo {:subject :$1 :verb :click :object :$2}}
  [ctx subject element]
  ...)
```

- **Subject**: Actor performing the action (`:alice`, `:admin`)
- **Verb**: Action being performed (`:click`, `:fill`, `:see`)
- **Object**: Target of the action (captured from step text)

### Subject Glossary

EDN file defining known actors in your test scenarios:

```clojure
{:subjects
 {:alice {:desc "Standard customer"}
  :admin {:desc "Administrative user"}}}
```

Subjects are normalized: `"Alice"` → `:alice`, `"System Admin"` → `:system-admin`.

### Verb Glossary

EDN file defining valid actions for an interface type:

```clojure
{:type :web
 :verbs
 {:login {:desc "Authenticate"}
  :search {:desc "Execute search"}}}
```

Project glossaries extend ShiftLefter's defaults (`:click`, `:fill`, `:see`, etc.).

### Interface (SVO)

Named configuration for a system boundary. Two distinct concepts:

- **Interface NAME**: Key in config (`:web`, `:customer-portal`)
- **Interface TYPE**: Verb vocabulary (`:web`, `:api`, `:sms`, `:email`)

```clojure
{:interfaces
 {:web {:type :web :adapter :etaoin :config {...}}
  :legacy-portal {:type :web :adapter :etaoin :config {...}}}}
```

Both `:web` and `:legacy-portal` use `:web` type verbs.

### Placeholder

Reference to a regex capture group in SVO metadata:

- `:$1` — first capture
- `:$2` — second capture
- etc.

```clojure
{:svo {:subject :$1 :verb :click :object :$2}}
```

### SVOI (Subject-Verb-Object-Interface)

Internal structure produced by SVO extraction:

```clojure
{:subject :alice
 :verb :click
 :object "the button"
 :interface :web}
```

Attached to bound steps as `:svoi` key.

### Auto-Provisioning

Automatic capability creation when steps declare interfaces. If a step needs `:interface :web` and the subject's context lacks `:cap/web`, the framework creates a browser session using the configured adapter.

### Enforcement Level

Configuration controlling SVO validation behavior:

- `:warn` — log warning, continue execution
- `:error` — fail at bind time (block execution)

```clojure
{:svo {:unknown-subject :warn
       :unknown-verb :error
       :unknown-interface :error}}
```

### Coeffects

Side-effect context in Shifted mode. Tracks what external effects a step may produce. Used for advanced provenance and replay.

---

## Configuration

### shiftlefter.edn

Project configuration file in repository root. Contains parser, runner, and mode settings.

```clojure
{:parser {:dialect "en"}
 :runner {:step-paths ["steps/"]}
 :mode :vanilla
 :enforcement :free}
```

### User Config

Machine-specific defaults at `~/.shiftlefter/config.edn`. Lower priority than explicit options and project config.

```clojure
{:chrome-path "/custom/chrome"
 :chromedriver-path "/patched/chromedriver"
 :default-stealth true}
```

### Step Paths

Directories containing step definition files. Configured via `:runner {:step-paths [...]}` or `--step-paths` CLI flag.

---

## Terms Needing Definition

The following terms are used but not yet formally defined. Definitions will be added as usage solidifies.

- **Observation Lineage** — Future feature for tracing requirements to tests
- **Use-case ID** — `# use-case:` comment annotation
- **Hosted Graph** — Future Fluree-based traceability store
