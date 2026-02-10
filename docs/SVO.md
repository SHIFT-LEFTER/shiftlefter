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
3. **Report** typos with suggestions ("Did you mean :alice?")
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
 {:alice {:desc "Standard customer"}
  :bob {:desc "New customer in onboarding"}
  :admin {:desc "Administrative user"}
  :guest {:desc "Unauthenticated visitor"}}}
```

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
Unknown subject :alcie in step "When Alcie clicks the button"
       at features/login.feature:12
       Known subjects: :alice, :admin, :guest, :bob
       Did you mean: :alice?
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

You can migrate incrementally - mix legacy and shifted steps in the same project.

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
 {;; Human actors
  :alice {:desc "Standard customer with verified account"}
  :bob {:desc "New customer in onboarding"}
  :admin {:desc "Administrative user with elevated privileges"}
  :guest {:desc "Unauthenticated visitor"}

  ;; System actors (for setup/teardown)
  :system/test-setup {:desc "Test harness for fixture creation"}
  :system/database {:desc "Database operations actor"}}}
```

Namespaced keywords (`:system/test-setup`) are supported for categorization.

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
 {:alice {:desc "Test user 1"}
  :bob {:desc "Test user 2"}}}
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
Unknown subject :alcie in step "When Alcie clicks the button"
       at features/login.feature:12
       Known subjects: :alice, :admin, :guest, :bob
       Did you mean: :alice?
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
 :payload {:subject :alice
           :verb :click
           :object "the login button"
           :interface :web
           :interface-type :web
           :step-text "When Alice clicks the login button"
           :location {:uri "login.feature" :line 12}}}
```

Subscribe to the event bus to capture SVO data for analytics or traceability.
