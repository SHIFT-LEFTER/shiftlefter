# SVO Validation

Subject–Verb–Object (SVO) validation catches mistakes in your Gherkin steps
**at bind time** — before any browser launches or scenario runs. In Shifted
mode, a step's metadata resolves it to a `(Subject, Verb, Object)` triple, and
the framework checks that triple against your project vocabulary while it loads
the suite.

```clojure
;; Without SVO (plain step)
(defstep #"^(\S+) clicks the (.+)$"
  [ctx subject element]
  ...)

;; With SVO validation
(defstep #"^(\S+) clicks the (.+)$"
  {:interface :web
   :svo {:subject :$1 :verb :click :object :$2}}
  [ctx subject element]
  ...)
```

At suite load, the framework will:

1. **Extract** subject / verb / object from the captures.
2. **Validate** them against your glossaries (known subjects, known verbs, and —
   when configured — known objects).
3. **Report** typos with suggestions ("did you mean `:user/alice`?").
4. **Auto-provision** the capability a step's interface needs.

SVO metadata is **additive** — add it to one step definition without touching
the rest.

---

## Quick start

### 1. Lay out a glossary

The default glossary path layout (see your `shiftlefter.edn`):

```
my-project/
├── shiftlefter.edn
├── glossary/
│   ├── subjects.edn
│   ├── verbs/
│   │   └── web.edn          # project verbs for the :web interface type
│   └── intents/             # object/intent regions (optional)
└── steps/
```

**`glossary/subjects.edn`** — who can act:

```clojure
{:subjects
 {:user  {:desc "Standard customer" :instances [:alice :bob]}
  :admin {:desc "Administrative user"}
  :guest {:desc "Unauthenticated visitor"}}}
```

Top-level keys are **types** (roles). A type with `:instances` groups several
session handles under one role — refer to them in Gherkin as `:user/alice`,
`:user/bob`. A type without `:instances` is a **singleton**, used directly
(`:guest`).

**`glossary/verbs/web.edn`** — project verbs that extend the built-in `:web`
vocabulary. Each verb entry declares a `:desc` and a closed set of **frames**
(the argument shapes the verb accepts); a verb with no extra arguments uses a
single `:default` frame:

```clojure
{:type :web
 :verbs
 {:login  {:desc "Authenticate with credentials"
           :frames {:default {:args [] :pattern "S logs in"}}}
  :search {:desc "Run a search query"
           :frames {:with {:args [:query] :pattern "S searches for QUERY"}}}}}
```

### 2. Point `shiftlefter.edn` at the glossaries

```clojure
{:glossaries
 {:subjects "glossary/subjects.edn"
  :verbs    {:web "glossary/verbs/web.edn"}
  :intents  "glossary/intents"}      ; optional — drives object validation

 :interfaces
 {:web {:type :web
        :adapter :etaoin
        :config {:headless true}}}

 :svo
 {:unknown-subject :warn       ; :warn or :error
  :unknown-verb    :warn       ; :warn or :error
  :unknown-object  :off        ; :strict | :warn | :off
  :unknown-interface :error}}
```

### 3. Annotate step definitions

```clojure
(defstep #"^(\S+) clicks the (.+)$"
  {:interface :web
   :svo {:subject :$1 :verb :click :object :$2}}
  [ctx subject element]
  ...)
```

### 4. Run

```bash
sl run features/ --step-paths steps/
```

Unknown subjects or verbs are reported before execution:

```
SVO validation issues:
Unknown subject :user/alcie in step "When :user/alcie clicks the button"
       at features/login.feature:12
       Known subjects: :user, :admin, :guest, :user/alice, :user/bob
       Did you mean: :user/alice?
```

---

## Concepts

### The three roles, three homes

| Role | Lives in | Vocabulary |
|---|---|---|
| **Subject** | `glossary/subjects.edn` | who acts (`:user/alice`, `:admin`) |
| **Verb** | `glossary/verbs/<iface>.edn` (+ built-in defaults) | what they do (`:click`, `:fill`) |
| **Object** | `glossary/intents/<region>.edn` (optional) | what they act on |

**Interface is metadata on the verb, not a fourth role.** Every verb belongs to
an interface type's verb bag; the bag is how vocabularies are swapped or
extended.

### Interface NAME vs TYPE

- **NAME** — the key in your config (`:web`, `:customer-portal`, `:legacy-app`).
- **TYPE** — the verb vocabulary (`:web`, `:sms`, …).

Simple case: name `:web`, type `:web`. Advanced case — two interfaces sharing
one vocabulary:

```clojure
{:interfaces
 {:legacy-app {:type :web :adapter :etaoin :config {...}}
  :new-app    {:type :web :adapter :etaoin :config {...}}}}
```

Both use `:web`-type verbs.

### Placeholder substitution

In the `:svo` map, `:$1`, `:$2`, … reference regex capture groups:

```clojure
(defstep #"^(\S+) fills the (\w+) field with \"([^\"]+)\"$"
  {:interface :web
   :svo {:subject :$1     ; first capture
         :verb :fill       ; literal verb
         :object :$2}}      ; second capture
  [ctx subject field value]
  ...)
```

For `Alice fills the username field with "test"`: `:$1` → `:alice`,
`:$2` → `"username"`.

### Subject normalization

Subjects normalize to keywords: `"Alice"` → `:alice`, `"System Admin"` →
`:system-admin`. The recommended Gherkin form is `:type/instance`
(`:user/alice`), which makes the role explicit; both `:user/alice` and bare
`:alice` route to the same session — the instance keyword is the session key.

### Object validation against intent regions

When you configure `:intents`, the **Object** is validated too. Intent regions
(under `glossary/intents/`) name the things a subject can act on — and
`:svo {:unknown-object …}` controls how strictly the object in a step is checked
against them:

- `:strict` — an object outside the loaded regions is a planning error.
- `:warn` — log and continue.
- `:off` — no object checking (the default).

This is what lets `Login.submit`-style intent references stand in for brittle
selectors and still be verified before a run.

### Step binding rules

How a step finds its definition — governed by whether it carries an interface
annotation.

**Rule 1 — no annotation.** The text is matched against *every* registered
stepdef. Exactly one must match: 0 → `:undefined` (planning error), 1 → bound,
2+ → `:ambiguous` (planning error).

```gherkin
When :user/alice clicks the submit button
```

**Rule 2 — with `[:interface]` annotation.** A leading interface keyword narrows
the candidate pool to stepdefs registered for that interface; the same 0/1/2+
rule applies within it.

```gherkin
Then [:sms] :user/alice receives a message containing '(\d{6})'
```

The annotation is also an assertion: if `:sms` isn't configured under
`:interfaces`, you get `:annotation/unknown-interface` at planning time —
catching typos and missing config early. Use it when the *same* step vocabulary
legitimately spans channels (e.g. `(\S+) receives a message` under `:sms`,
`:whatsapp`, and `:email`). Annotations are a Shifted-mode feature; without
Shifted's `:svo` configuration, `[:foo]` is just literal text.

---

## Configuration reference

### `:glossaries`

```clojure
{:glossaries
 {:subjects "glossary/subjects.edn"
  :verbs    {:web "glossary/verbs/web.edn"}   ; interface type → path
  :intents  "glossary/intents"}}              ; path; drives object validation
```

### `:interfaces`

```clojure
{:interfaces
 {:web {:type :web :adapter :etaoin :config {:headless true}}}}
```

Each interface declares a `:type` (verb vocabulary), an `:adapter` (capability
provider, e.g. `:etaoin` for browsers), and adapter `:config`.

### `:svo`

```clojure
{:svo
 {:unknown-subject :warn       ; :warn | :error
  :unknown-verb    :warn       ; :warn | :error
  :unknown-object  :off        ; :strict | :warn | :off
  :unknown-interface :error}}  ; :warn | :error
```

`:warn` logs and continues; `:error` fails at bind time, before execution.

---

## Glossary file formats

### Subjects

```clojure
{:subjects
 {:user  {:desc "Standard application user" :instances [:alice :bob]}
  :admin {:desc "Administrative user" :instances [:pat]}
  :guest {:desc "Unauthenticated visitor"}                 ; singleton
  :system/test-setup {:desc "Test harness for fixtures"}}} ; namespaced, non-human
```

Types with `:instances` group session handles (`:user/alice`, `:admin/pat`);
singletons are used directly; namespaced keywords organize non-human actors.

### Verbs

A verb is an **interface-level** action. A verb entry requires `:desc` **and**
`:frames` — a closed set of argument shapes; a verb with no extra arguments uses one
`:default` frame:

```clojure
{:type :web
 :verbs
 {:upload {:desc "Attach a file to a file input"
           :frames {:with-path {:args [:path] :pattern "S uploads PATH"}}}}}
```

**You rarely add verbs.** Interface verbs are built in (`:click`, `:fill`, `:see`,
`:navigate`, `:clear`, `:select`, `:scroll`, `:wait`, `:hover`, …); the authoritative
current list — each verb with its frames and step patterns — is `sl agent-doc
builtins`, regenerated from the framework's own glossaries so it never drifts (this
page deliberately doesn't reproduce it). Two cautions before you add one:

- A glossary verb only makes the validator *accept* the word — it does **not** create
  behavior. A genuinely new interface verb (like `:upload` above) also needs a
  `defstep` to run; that's adapter-author territory, not the normal path.
- A **domain action** like "checks out" or "logs in" is *not* a verb. It's a
  contraction of interface steps — a **macro**. See
  [Add Domain Language](extending-vocabulary.md).

Your verbs add to the defaults; `:override-defaults true` replaces them.

---

## `defstep` metadata contract

```clojure
(defstep <pattern>
  <metadata-map>   ; optional
  <args-vector>
  <body>)
```

Metadata is entirely optional. Recognized keys:

| Key | Type | Description |
|---|---|---|
| `:interface` | keyword | interface name from config |
| `:svo` | map | `{:subject … :verb … :object …}` extraction |

In the `:svo` map, `:subject` is usually a placeholder (`:$1`), `:verb` a literal
keyword, and `:object` a placeholder or string.

---

## Migration guide

Adopt SVO incrementally:

1. **Inventory.** Find steps shaped subject-verb-object (`^(\S+) clicks the
   (.+)$`); leave setup steps (`^the database is seeded$`) as plain steps.
2. **Create glossaries.** Start with your actors in `glossary/subjects.edn`. Use the
   built-in interface verbs as-is; turn recurring domain actions ("checks out") into
   macros, not verbs (see [Add Domain Language](extending-vocabulary.md)).
3. **Configure enforcement permissively** — `:warn` for subjects and verbs.
4. **Annotate one step at a time.** Add the `:svo` map; nothing forces you to
   convert the rest.
5. **Fix what the validator reports** — missing subjects/verbs, typos.
6. **Tighten** to `:error` once clean.

---

## Auto-provisioning

When a step declares an interface, the framework provisions the capability:

1. Check the subject's context for `:cap/web`.
2. If absent, look up `:web` in `:interfaces`.
3. Call the adapter factory (`:etaoin`) with the config.
4. Store it as `:cap/web`.
5. Execute the step.

Capabilities are cleaned up at scenario end — **ephemeral mode**, the default: a
capability is provisioned for the scenario and closed when it ends. A subject that
`:wears` a costume attaches to durable state instead of being provisioned ephemerally
(see [COSTUMES.md](COSTUMES.md)).

---

## Error messages

```
Unknown verb :smash in step "When Alice smashes the button"
       at features/login.feature:15
       Interface :web (type :web)
       Known verbs for :web: :click, :fill, :see, :navigate, :clear, ...
       Did you mean: ...?
```

```
Unknown interface :foobar in step "When Alice clicks the button"
       at features/login.feature:18
       Configured interfaces: :web
       Add to shiftlefter.edn: {:interfaces {:foobar {:type ... :adapter ...}}}
```

---

## Working examples

The `examples/` projects use the current `glossary/` layout end-to-end —
`examples/04-sms-2fa` (subjects across web + SMS) and the nested-addressing
examples `examples/05-nested-self-rooted` / `examples/06-nested-parent-anchored`
(subjects plus intent regions) are good starting points.

---

## Events

Steps with SVO metadata emit a `:step/svo` event:

```clojure
{:type :step/svo
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
