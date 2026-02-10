# SVO Demo Example

This example demonstrates ShiftLefter's Subject-Verb-Object (SVO) validation system.

## What is SVO?

SVO validation helps catch errors in your Gherkin step definitions by:

1. **Subject validation** - Ensures actors (Alice, Bob, Admin) are known
2. **Verb validation** - Ensures actions (click, fill, see) are valid for the interface
3. **Interface validation** - Ensures interfaces are configured

## Directory Structure

```
svo-demo/
├── shiftlefter.edn           # Configuration with interfaces and glossaries
├── config/
│   └── glossaries/
│       ├── subjects.edn      # Who can perform actions (Alice, Bob, Admin)
│       └── verbs-web.edn     # Project-specific verbs (extends defaults)
├── features/
│   └── login.feature         # Feature file using SVO patterns
└── src/
    └── stepdefs/
        └── web.clj           # Step definitions with SVO metadata
```

## Running the Example

**Note**: This is a reference example showing the file structure and SVO patterns.
To run it, you would copy this directory to be your project root.

For a real project, your directory would look like:

```
my-project/
├── shiftlefter.edn           # Copy from this example
├── config/glossaries/        # Your glossaries
├── features/                 # Your feature files
└── src/stepdefs/             # Your step definitions
```

Then run from your project root:

```bash
sl run --dry-run features/
```

## Key Concepts

### Interface NAME vs TYPE

- **NAME**: The key you use in config (`:web`, `:customer-portal`)
- **TYPE**: The verb vocabulary (`:web`, `:api`)

Simple case: `:web` interface with `:web` type - name and type match.

Advanced case: `:legacy-app` and `:new-app` both use `:web` type verbs.

### Legacy vs Shifted Steps

**Legacy (no metadata):**
```clojure
(defstep #"the login page is loaded"
  [ctx]
  ;; No SVO validation
  ...)
```

**Shifted (with metadata):**
```clojure
(defstep #"(\w+) clicks the (.+)"
  {:interface :web
   :svo {:subject :$1 :verb :click :object :$2}}
  [ctx subject element]
  ;; Subject/verb validated, capability auto-provisioned
  ...)
```

### SVO Enforcement Levels

In `shiftlefter.edn`:

```clojure
:svo {:unknown-subject :warn    ; Log warning, continue
      :unknown-verb :warn       ; Log warning, continue
      :unknown-interface :error} ; Fail at bind time
```

## Trying Validation Errors

Edit `login.feature` to try these:

1. **Unknown subject**: Change "Alice" to "Alcie" - you'll get a suggestion
2. **Unknown verb**: Change a step to use "smashes" instead of "clicks"
3. **Unknown interface**: Add `:interface :foobar` to a stepdef
