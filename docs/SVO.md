# SVO Validation System

Subject-Verb-Object (SVO) validation catches errors in your Gherkin steps early, at binding time rather than runtime.

## Overview

SVO validation adds optional type-checking to your step definitions:

```clojure
;; Without SVO (legacy)
(defstep #"^(\w+) clicks the (.+)$"
  [ctx subject element]
  ...)

;; With SVO validation
(defstep #"^(\w+) clicks the (.+)$"
  {:interface :web
   :svo {:subject :$1 :verb :click :object :$2}}
  [ctx subject element]
  ...)
```

The framework will:
1. **Extract** subject/verb/object from captures at bind time
2. **Validate** against glossaries (known subjects, known verbs)
3. **Report** typos with suggestions ("Did you mean :user/alice?")
4. **Auto-provision** capabilities when steps reference interfaces

---

## Quick Start

### 1. Add glossaries to your project

```
my-project/
├── shiftlefter.edn
├── config/
│   └── glossaries/
│       ├── subjects.edn
│       └── verbs-web.edn
└── steps/
```

**subjects.edn:**
```clojure
{:subjects
 {:user  {:desc "Standard customer"
          :instances [:alice :bob]}
  :admin {:desc "Administrative user"}
  :guest {:desc "Unauthenticated visitor"}}}
```

Top-level keys are **types** (roles). Types with `:instances` group multiple session handles; types without are **singletons** used directly in steps. In Gherkin, use `:user/alice` to identify both the role and the actor.

**verbs-web.edn:**
```clojure
{:type :web
 :verbs
 {:login {:desc "Authenticate"}
  :search {:desc "Execute search query"}}}
```

### 2. Configure shiftlefter.edn

```clojure
{:glossaries
 {:subjects "config/glossaries/subjects.edn"
  :verbs {:web "config/glossaries/verbs-web.edn"}}

 :interfaces
 {:web {:type :web
        :adapter :etaoin
        :config {:headless true}}}

 :svo
 {:unknown-subject :warn    ; or :error
  :unknown-verb :warn       ; or :error
  :unknown-interface :error}}
```

### 3. Add metadata to step definitions

```clojure
(defstep #"^(\w+) clicks the (.+)$"
  {:interface :web
   :svo {:subject :$1 :verb :click :object :$2}}
  [ctx subject element]
  (println (str subject " clicking " element))
  (:scenario ctx))
```

### 4. Run your features

```bash
sl run features/ --step-paths steps/
```

Unknown subjects or verbs are reported:

```
SVO validation issues:
Unknown subject :user/alcie in step "When :user/alcie clicks the button"
       at features/login.feature:12
       Known subjects: :user, :admin, :guest, :user/alice, :user/bob
       Did you mean: :user/alice?
```

---

## Concepts

### Interface NAME vs TYPE

Two distinct concepts:

- **NAME**: The key in your config (`:web`, `:customer-portal`, `:legacy-app`)
- **TYPE**: The verb vocabulary (`:web`, `:api`, `:sms`, `:email`)

Simple case: Interface name `:web` with type `:web` - they match.

Advanced case: Two interfaces sharing the same verb vocabulary:
```clojure
{:interfaces
 {:legacy-app {:type :web :adapter :etaoin :config {...}}
  :new-app {:type :web :adapter :etaoin :config {...}}}}
```

Both `:legacy-app` and `:new-app` use `:web` type verbs (click, fill, see, etc.).

### Placeholder Substitution

In the `:svo` map, use `:$1`, `:$2`, etc. to reference regex captures:

```clojure
(defstep #"^(\w+) fills the (\w+) field with \"([^\"]+)\"$"
  {:interface :web
   :svo {:subject :$1      ; First capture = subject
         :verb :fill        ; Literal verb
         :object :$2}}      ; Second capture = object
  [ctx subject field value]
  ...)
```

When step text "Alice fills the username field with \"test\"" matches:
- `:$1` → "Alice" → normalized to `:alice`
- `:$2` → "username"
- Captures: `["Alice" "username" "test"]`

### Subject Normalization

Subjects are normalized to keywords:
- `"Alice"` → `:alice`
- `"Admin"` → `:admin`
- `"System Admin"` → `:system-admin`

The recommended Gherkin form is `:type/instance` (e.g. `:user/alice`), which makes the actor's role explicit. Both `:user/alice` and bare `:alice` route to the same browser session — the instance keyword (`:alice`) is the session key.

In verbose console output, `:user/alice opens the browser` displays as `[:user] alice opens the browser`.

### Legacy vs Shifted Steps

**Legacy steps** work unchanged:
```clojure
(defstep #"^the login page is loaded$"
  [ctx]
  ;; No SVO validation
  ...)
```

**Shifted steps** include metadata:
```clojure
(defstep #"^(\w+) clicks the (.+)$"
  {:interface :web
   :svo {:subject :$1 :verb :click :object :$2}}
  [ctx subject element]
  ;; Subject/verb validated, capability auto-provisioned
  ...)
```

SVO metadata is additive — adding it to one step definition doesn't require adding it to all of them.

### Step Binding Rules

How a step in your feature file finds its definition. Two cases, governed by whether the step has an interface annotation.

**Rule 1 — No annotation.** The step text is matched against *every* registered step definition across all interfaces. Exactly one stepdef must match:

- 0 matches → `:undefined` (planning error)
- 1 match → bound
- 2+ matches → `:ambiguous` (planning error)

```gherkin
When :user/alice clicks the submit button
```

Whichever stepdef matches wins, regardless of its `:interface` metadata.

**Rule 2 — With `[:interface]` annotation.** The step text is prefixed with an explicit interface keyword, e.g. `[:sms]`. This narrows the candidate pool to stepdefs whose `:interface` metadata equals that keyword. Within that filtered set, the same 0/1/2+ rule applies.

```gherkin
Then [:sms] :user/alice receives a message containing '(\d{6})'
```

Only stepdefs registered with `:interface :sms` are considered. It's a shorthand for "bind this step to something tagged `:sms`, not anything else." Stepdefs without `:interface` metadata (the legacy escape hatch) are excluded from annotated binding.

The annotation is also an assertion: if `:sms` isn't configured under `:interfaces` in your `shiftlefter.edn`, you get `:annotation/unknown-interface` at planning time — catches typos and missing config early.

**When you'd want each:**

- Rule 1 is the default and right for most steps. Patterns are usually distinct enough that ambiguity doesn't arise.
- Rule 2 is for the case where the *same* step vocabulary legitimately applies across multiple channels. A pattern like `(\S+) receives a message` can be registered under `:sms`, `:whatsapp`, and `:email`; the annotation picks which one.

Annotations are a Shifted-mode feature. In vanilla mode `[:foo]` is treated as literal step text.

---

## Configuration Reference

### :glossaries

Paths to glossary files:

```clojure
{:glossaries
 {:subjects "config/glossaries/subjects.edn"
  :verbs {:web "config/glossaries/verbs-web.edn"
          :api "config/glossaries/verbs-api.edn"}}}
```

- `:subjects` — path to subject glossary
- `:verbs` — map of interface-type to verb glossary path

### :interfaces

Interface definitions:

```clojure
{:interfaces
 {:web {:type :web
        :adapter :etaoin
        :config {:headless true}}
  :api {:type :api
        :adapter :http
        :config {:base-url "https://api.example.com"}}}}
```

Each interface requires:
- `:type` — verb vocabulary (`:web`, `:api`, `:sms`, `:email`)
- `:adapter` — capability provider (`:etaoin` for browsers)
- `:config` — adapter-specific configuration

### :svo

Enforcement levels:

```clojure
{:svo
 {:unknown-subject :warn      ; :warn or :error
  :unknown-verb :warn         ; :warn or :error
  :unknown-interface :error}} ; :warn or :error
```

- `:warn` — log warning, continue execution
- `:error` — fail at bind time (before execution)

---

## Glossary File Formats

### Subject Glossary

```clojure
{:subjects
 {;; Types with instances — use :type/instance in Gherkin
  :user  {:desc "Standard application user"
          :instances [:alice :bob]}
  :admin {:desc "Administrative user"
          :instances [:pat]}

  ;; Singletons — types without :instances, used directly
  :guest {:desc "Unauthenticated visitor"}

  ;; Namespaced types — for categorization
  :system/test-setup {:desc "Test harness for fixture creation"}}}
```

**Types** are the top-level keys (`:user`, `:admin`, `:guest`, `:system/test-setup`). Each type has a `:desc` and optionally an `:instances` vector.

- **Types with `:instances`** — group multiple session handles under one role. In Gherkin, refer to them as `:user/alice`, `:user/bob`, `:admin/pat`.
- **Singletons** — types without `:instances` (like `:guest`). Use the type keyword directly in Gherkin steps.
- **Namespaced types** — Clojure namespaced keywords (`:system/test-setup`) are supported for organizing non-human actors.

The old flat format (each entry a standalone subject with no `:instances`) still works — each entry is treated as a singleton.

### Verb Glossary

```clojure
{:type :web    ; Which interface type these verbs extend
 :verbs
 {:login {:desc "Authenticate with credentials"}
  :logout {:desc "End authenticated session"}
  :search {:desc "Execute a search query"}
  :filter {:desc "Apply filter criteria"}}}
```

**Project glossaries extend framework defaults.** ShiftLefter ships with default verbs for `:web`:

```
:click, :fill, :see, :navigate, :submit, :hover, :select,
:check, :uncheck, :clear, :scroll, :wait, :type
```

Your glossary adds to these. To completely replace defaults:
```clojure
{:type :web
 :override-defaults true
 :verbs {...}}
```

---

## defstep Metadata Contract

The metadata map is the second argument (optional):

```clojure
(defstep <pattern>
  <metadata-map>  ; optional
  <args-vector>
  <body>)
```

### Required Keys

None - metadata is entirely optional.

### Optional Keys

| Key | Type | Description |
|-----|------|-------------|
| `:interface` | keyword | Interface name from config |
| `:svo` | map | Subject/verb/object extraction |

### :svo Map

```clojure
{:svo {:subject :$1      ; keyword or placeholder
       :verb :click       ; keyword (literal verb)
       :object :$2}}      ; keyword, placeholder, or string
```

- `:subject` — usually a placeholder (`:$1`)
- `:verb` — usually a literal keyword (`:click`, `:fill`)
- `:object` — placeholder or literal string

### Placeholder Index

Placeholders are 1-indexed and correspond to regex capture groups:
- `:$1` — first capture group
- `:$2` — second capture group
- etc.

---

## Migration Guide

### Step 1: Inventory Your Steps

Find steps that follow SVO patterns:

```clojure
;; SVO pattern (good candidate)
(defstep #"^(\w+) clicks the (.+)$" ...)  ; subject-verb-object

;; Setup step (leave as legacy)
(defstep #"^the database is seeded$" ...)  ; no subject
```

### Step 2: Create Glossaries

Start with your actors:
```clojure
;; subjects.edn
{:subjects
 {:user {:desc "Standard test user"
         :instances [:alice :bob]}
  :admin {:desc "Administrative user"}}}
```

Add domain-specific verbs:
```clojure
;; verbs-web.edn
{:type :web
 :verbs
 {:checkout {:desc "Complete purchase flow"}
  :apply-coupon {:desc "Apply discount code"}}}
```

### Step 3: Configure Enforcement

Start permissive, tighten later:
```clojure
{:svo
 {:unknown-subject :warn
  :unknown-verb :warn
  :unknown-interface :error}}
```

### Step 4: Add Metadata Incrementally

Convert one step at a time:

```clojure
;; Before
(defstep #"^(\w+) clicks the (.+)$"
  [ctx subject element]
  ...)

;; After
(defstep #"^(\w+) clicks the (.+)$"
  {:interface :web
   :svo {:subject :$1 :verb :click :object :$2}}
  [ctx subject element]
  ...)
```

### Step 5: Fix Validation Warnings

Run your tests:
```bash
sl run features/ --step-paths steps/
```

Fix reported issues:
- Add missing subjects to glossary
- Add missing verbs to glossary
- Fix typos in feature files

### Step 6: Tighten Enforcement

Once clean, switch to `:error`:
```clojure
{:svo
 {:unknown-subject :error
  :unknown-verb :error
  :unknown-interface :error}}
```

---

## Auto-Provisioning

When a step declares an interface, the framework automatically provisions the capability:

```clojure
(defstep #"^(\w+) clicks the (.+)$"
  {:interface :web ...}  ; Declares need for :web interface
  [ctx subject element]
  ;; Browser is auto-created if not present
  ...)
```

Provisioning flow:
1. Check if subject's context has `:cap/web`
2. If not, look up `:web` in interfaces config
3. Call adapter factory (`:etaoin`) with config
4. Store capability as `:cap/web` in context
5. Execute step with capability available

Capabilities are cleaned up at scenario end (ephemeral mode).

---

## Error Messages

### Unknown Subject

```
Unknown subject :user/alcie in step "When :user/alcie clicks the button"
       at features/login.feature:12
       Known subjects: :user, :admin, :guest, :user/alice, :user/bob, :admin/pat
       Did you mean: :user/alice?
```

### Unknown Verb

```
Unknown verb :smash in step "When Alice smashes the button"
       at features/login.feature:15
       Interface :web (type :web)
       Known verbs for :web: :click, :fill, :see, :navigate, :submit
```

### Unknown Interface

```
Unknown interface :foobar in step "When Alice clicks the button"
       at features/login.feature:18
       Configured interfaces: :web, :api
       Add to shiftlefter.edn: {:interfaces {:foobar {:type ... :adapter ...}}}
```

---

## Example Project

See `examples/svo-demo/` for a complete working example with:
- Configuration file
- Subject and verb glossaries
- Feature file with SVO patterns
- Step definitions showing legacy vs shifted styles

---

## Events

Steps with SVO metadata emit `:step/svoi` events:

```clojure
{:type :step/svoi
 :ts "2026-01-10T..."
 :run-id "uuid"
 :payload {:subject :user/alice
           :verb :click
           :object "the login button"
           :interface :web
           :interface-type :web
           :step-text "When :user/alice clicks the login button"
           :location {:uri "login.feature" :line 12}}}
```

Subscribe to the event bus to capture SVO data for analytics or traceability.
