# SVO Demo — Reference

This is a **reference example**, not part of the numbered on-ramp (see
[the examples index](../README.md)). It demonstrates ShiftLefter's
Subject-Verb-Object (SVO) validation system, and specifically the
**migration path from legacy plain-name steps to Shifted steps**: its
feature file deliberately uses bare English names ("Alice", "Bob") with
`(\w+)` subject captures — the *legacy* style — while its glossaries and
config show the `:type/instance` model (`:user/alice`) that the on-ramp
examples teach. If you're starting fresh, write `:type/instance` steps
from day one (see [`02b`](../02b-browser-multi-actor/)); this example is
for teams bringing an existing plain-name suite under validation.

Note the `:svo` levels here are deliberately permissive (`:warn` for
subjects and verbs) — that's the migration posture: surface unknowns
without breaking the legacy suite, then ratchet to `:error` as the
glossary fills in.

## What is SVO?

SVO validation helps catch errors in your Gherkin step definitions by:

1. **Subject validation** - Ensures actors (`:user/alice`, `:admin`) are known
2. **Verb validation** - Ensures actions (click, fill, see) are valid for the interface
3. **Interface validation** - Ensures interfaces are configured

## Directory Structure

```
svo-demo/
├── shiftlefter.edn           # Configuration with interfaces and glossaries
├── glossary/
│   ├── subjects.edn          # Who can perform actions (types and instances)
│   └── verbs/
│       └── web.edn           # Project-specific verbs (extends defaults)
├── features/
│   └── login.feature         # Feature file using SVO patterns
└── src/
    └── stepdefs/
        └── web.clj           # Step definitions with SVO metadata
```

This is the canonical glossary layout — `glossary/` at the project root,
`glossary/verbs/<interface>.edn` per interface — the same convention
examples 04–06 and the docs use.

## Running the Example

This is a **structure reference**, not an executable demo — the step
definitions are deliberate stubs (`println`, no real browser). But the
*planning* half runs in place: from this directory, the dry-run parses,
binds, and SVO-validates all four scenarios against the glossaries.
(Release zip installs `sl`; in a checkout of this repo substitute
`bin/sl`.)

```bash
sl run --dry-run features/
```

That dry-run is where every validation experiment below happens — no
browser, no copying required. (Older versions of this README said the
example was "not runnable in place"; that predated the jar runner — a
claim true in one deployment instantiation and false in another. See the
project wiki's deployment-instantiations page for the doctrine.)

For a real project, your directory would look like:

```
my-project/
├── shiftlefter.edn           # Copy from this example
├── glossary/                 # Your glossaries (subjects.edn, verbs/, intents/)
├── features/                 # Your feature files
└── src/stepdefs/             # Your step definitions
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
