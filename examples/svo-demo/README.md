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
└── sl/                       # The ShiftLefter project — everything in one dir
    ├── shiftlefter.edn       # Configuration with interfaces and glossaries
    ├── glossary/
    │   └── subjects.edn      # Who can perform actions (types and instances)
    ├── macros/
    │   └── login.ini         # Domain actions as macros (INI registry)
    ├── features/
    │   └── login.feature     # Feature file using SVO patterns
    └── steps/
        └── web.clj           # Step definitions with SVO metadata
```

This is the canonical layout — everything ShiftLefter lives in `sl/`, with
`glossary/` next to the config — the same convention examples 04–06 and
the docs use. The glossary path is spelled explicitly in this example's
config for teaching; it matches the conventional path, so omitting
`:glossaries` would discover the same file.

Note what is *absent*: a project verbs glossary. The built-in interface
verbs (fill, click, see, navigate, …) cover this example completely, and
that's the normal case — see the next section for where domain actions
live instead.

## Running the Example

This is a **structure reference**, not an executable demo — the step
definitions are deliberate stubs (`println`, no real browser). But the
*planning* half runs in place: from this directory, the dry-run parses,
binds, and SVO-validates all four scenarios against the glossaries.
(Release zip installs `sl`; in a checkout of this repo substitute
`bin/sl`.)

```bash
sl run --dry-run sl/features/
```

That dry-run is where every validation experiment below happens — no
browser, no copying required. (Older versions of this README said the
example was "not runnable in place"; that predated the jar runner — a
claim true in one deployment instantiation and false in another. See the
project wiki's deployment-instantiations page for the doctrine.)

For a real project, your directory would look like:

```
my-project/
└── sl/
    ├── shiftlefter.edn       # Copy from this example
    ├── glossary/             # Your glossaries (subjects.edn, intents/)
    ├── macros/               # Your domain actions (INI registries)
    ├── features/             # Your feature files
    └── steps/                # Your step definitions (if any — built-ins first)
```

## Domain actions are macros, not interface verbs

An earlier version of this example declared `:login`, `:logout`, `:search`,
and `:filter` as project verbs in a `glossary/verbs/web.edn`. That's an
anti-pattern, and it's gone. The vocabulary model has three tiers:

1. **Built-in interface verbs** — `fill`, `click`, `see`, `navigate`, …
   Interface-level actions; a closed set the adapter ships. You almost
   never add to it.
2. **Macros** — domain actions. "Logs in" isn't one interface action; it's
   a *contraction* of several (fill, fill, click) wearing a domain name.
   You author it as data (an INI registry entry), not code.
3. **defstep-backed new interface verbs** — rare, adapter-author territory.

A glossary entry only *types* a word — it never gives it behavior. Declaring
`:login` as a verb made the validator accept a word that nothing could ever
run. The macro does what that verb entry pretended to:

```ini
name = Alice logs in
description = Contract the three-step UI login into one domain action
steps =
  When Alice fills the username field with 'alice@example.com'
  And Alice fills the password field with 'correct-password'
  And Alice clicks the login button
```

And the feature invokes it with a trailing ` +` — the provenance marker
that says "this step expands":

```gherkin
When Alice logs in +
```

Macros are enabled in `shiftlefter.edn`:

```clojure
:runner {:macros {:enabled? true :registry-paths ["macros/login.ini"]}}
```

**The honest limitation:** today a macro is a fixed text match — `Alice
logs in +` logs in Alice, and Bob can't borrow it (his scenario spells the
steps out, deliberately, as the contrast). Named-argument macros — where
one macro serves any subject — are the planned upgrade in the 0.6
contracts arc. Use macros for domain shorthand now; expect them to get
more powerful, not different.

## Key Concepts

### Interface NAME vs TYPE

- **NAME**: The key in config (`:web`, `:customer-portal`) — a configured
  INSTANCE of a type. Feature steps select one with a leading `[name]`
  annotation; step definitions never reference names.
- **TYPE**: The verb vocabulary (`:web`, `:api`). Stepdef `:interface`
  metadata declares the TYPE.

Simple case: `:web` interface with `:web` type - name and type match.

Advanced case: `:storefront` and `:admin-console` both `:type :web` — each
gets the full built-in `:web` vocabulary; steps pick the instance with
`[:storefront]` / `[:admin-console]`, and unannotated steps default to the
type-named entry when several exist.

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
