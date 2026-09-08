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

The conventional glossary layout — `glossary/` next to your config, inside
the project's `sl/` directory:

```
my-project/
└── sl/
    ├── shiftlefter.edn
    ├── glossary/
    │   ├── subjects.edn
    │   ├── verbs/
    │   │   └── web.edn      # project verbs for the :web interface type
    │   └── intents/         # object/intent regions (optional)
    ├── features/
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

An `:instances` entry may also be a **persona map** — a user with an optional
costume and a docstring:

```clojure
{:subjects
 {:user {:desc "Standard customer"
         :instances [{:id :alicia :desc "Spanish-first admin reviewer"
                      :wears :alicia}
                     {:id :bob :wears :bob-jacket}
                     :carol]}}}       ; bare keyword still fine
```

The `:desc` shows up wherever personas are listed (`sl explain user/alicia`,
`sl glossary subjects`), so test writers can see which persona to use when.
`:wears` binds that persona to a [costume](COSTUMES.md); a type-level `:wears`
acts as an inheritable default, a persona's own `:wears` wins, and
`:wears nil` on a persona opts out of the default.

**`glossary/verbs/web.edn`** — project verbs that extend the built-in `:web`
vocabulary. The file's `:type` is REQUIRED and authoritative — it is the mount
key the loader files these verbs under (a file without `:type` is a planning
error). Any keyword works, namespaced included (`:github/web`) — type
identifiers are an open space. Each verb entry declares a `:desc` and a closed
set of **frames** (the argument shapes the verb accepts); a verb with no extra
arguments uses a single `:default` frame:

```clojure
{:type :web
 :verbs
 {:login  {:desc "Authenticate with credentials"
           :frames {:default {:args [] :pattern "S logs in"}}}
  :search {:desc "Run a search query"
           :frames {:with {:args [:query] :pattern "S searches for QUERY"}}}}}
```

**Frames type shapes, never authorization.** A frame says how a verb may be
*said* — the argument shapes it accepts — not who may invoke it. Declaring
"only admins may delete" in a verb frame would restate the application's
permission rules in test vocabulary: a shadow of the system under test that
drifts the moment the real rules change. ShiftLefter types the language used
to exercise the system; it does not duplicate the system's domain
permissions. Authorization is discovered, never declared — substitute one
actor for another and observe the real outcome. Tests discover what the
system can do; they never duplicate it.

### 2. Wire the glossaries (or let the convention do it)

Glossaries at the conventional paths above are discovered automatically —
a config with no `:glossaries` key loads them as-is. An explicit
`:glossaries` map overrides the convention wholesale (`:glossaries nil`
turns glossaries off):

```clojure
{:glossaries                           ; optional — these ARE the conventions
 {:subjects "glossary/subjects.edn"
  :verbs    ["glossary/verbs/web.edn"] ; list of files; each file's own
                                       ; :type is its mount key
  :intents  "glossary/intents"}       ; drives object validation

 :interfaces
 {:web {:type :web
        :adapter :etaoin
        :config {:headless true}}}

 :svo
 {:unknown-subject :warn       ; :warn or :error
  :unknown-verb    :warn       ; :warn or :error
  :unknown-object  :warn       ; :error | :warn | :off — defaults :warn when
                               ; :intents is configured, :off otherwise
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

### The step text is a grammar

Every marker in step text carries meaning: `'quoted'` is always a literal,
an unquoted `Capitalized.name` is always a glossary reference that must
resolve, `{name}` is a value carried from an earlier step, `/…/` is a
pattern. The complete reference — every marker, every slot kind, the
reserved forms, the key-name list, and why `should be on` takes names while
`should be on exactly` takes only URL values — is the **grammar** doc: run
`sl agent-doc grammar`, or read it inline in
[AGENT.md](AGENT.md#the-authored-grammar).

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

- **TYPE** — the verb vocabulary and protocol contract (`:web`, `:sms`, …).
  The type is welded to the verb: step definitions declare `:interface
  <type>`, and every glossary, intent binding, and named-location map is
  keyed by type.
- **NAME** — the key in your config (`:web`, `:storefront`,
  `:admin-console`): a configured **instance** of a type, carrying its own
  adapter and adapter config (base-url, proxy, headless, …). The `[name]`
  step annotation selects an instance; the instance's declared `:type` then
  scopes which step definitions can bind. The adapter is invisible behind
  the instance — nothing in the vocabulary layer ever references it.

Simple case: name `:web`, type `:web`. Advanced case — two instances sharing
one vocabulary:

```clojure
{:interfaces
 {:storefront    {:type :web :adapter :etaoin :config {...}}
  :admin-console {:type :web :adapter :etaoin :config {...}}}}
```

Both speak the full `:web` vocabulary — builtin steps included; `[:storefront]`
and `[:admin-console]` pick which world a step executes in.

### The three axes — one picture

```
               FEATURE FILE — requirements language only
┌───────────────────────────────────────────────────────────────┐
│    [:admin-console]     :user/alicia     clicks Refund.approve│
└─────────┬────────────────────┬────────────────┬───────────────┘
          │ names an           │ names a        │ a VERB,
          │ INSTANCE           │ PERSONA        │ regex-matched
~~~~~~~~~~┼~~~~~~~~ name-test waterline ~~~~~~~~┼~~~~~~~~~~~~~~~~
          │   nothing below ever appears        │
          ▼   in a feature file ▼               ▼
  WORLD — where            ACTOR — who        VOCABULARY — what
┌───────────────────┐  ┌────────────────────┐  ┌─────────────────────┐
│ shiftlefter.edn   │  │ subjects glossary  │  │ TYPE :web  ◁┄┄(t)   │
│ :admin-console    │  │ :user/alicia       │  │ owns the verb set:  │
│  :type :web (t)┄┄▷│  │  :desc "Spanish-1st│  │  opens, clicks,     │
│  :adapter :etaoin │  │   admin reviewer"  │  │  sees, …            │
│  world config —   │  │  :wears :alicia    │  │ stepdefs declare    │
│  proxy, Tor bin,  │  │       (0..1)       │  │ TYPE — never a name,│
│  flaky net,       │  └─────────┬──────────┘  │ never an adapter    │
│  headless         │            ▼             └─────────────────────┘
└────────┬──────────┘  ┌────────────────────┐
         ▼             │ COSTUME (=profile) │
┌───────────────────┐  │ recipe (declared): │
│ ADAPTER           │  │  language, plugins,│
│ etaoin|playwright │  │  theme, PDF reader │
│ factory spawns a  │  │ residue (accrued): │
│ fresh session     │  │  auth, cookies     │
└────────┬──────────┘  └─────────┬──────────┘
         │ spawn fresh           │ attach to the authed session
         │                       │ (adapter must support it — sl-bbqb)
         └───────────┬───────────┘
                     ▼
┌────────────────────────────────────────────┐
│ CAPABILITY  :cap/admin-console.alicia      │
│ one per (instance × subject): the live     │
│ browser that actually executes the verb    │
└────────────────────────────────────────────┘

binding chain:  [name] → instance → its :type (t) → stepdef candidates
                → verb executes on the (instance × subject) capability
unannotated:    one instance of the type → it; else the type-named
                entry is the default; else planning error
run matrix:     swap the ADAPTER under the same NAME for cross-driver
                coverage — features never change
```

The three tokens in the feature line are names that reach across the
waterline — an instance name, a persona name, a verb — and everything they
point at is machinery that never crosses back up. Two common confusions
dissolve here: the adapter sits two hops below the feature file behind the
instance name (why a driver name can never leak up), and the costume hangs
off the persona, not the world (why a language preference never touches the
adapter). The (t) link is the binding fix itself — the instance's declared
`:type` is what selects the vocabulary box, where the old system wired the
stepdefs to the name. The capability at the bottom is the single point where
all three axes meet, fed by exactly one of two paths: adapter-spawn (fresh
world) or costume-attach (worn identity), with sl-bbqb guarding the seam
between them.

### The name test

An interface instance name is legitimate iff it could appear in the prose
requirement. Different **systems** (`:storefront`, `:admin-console`) pass;
different **user-world conditions** (`:tor`, `:flaky-network` — the
mechanism is rig, but the condition is what the requirement is *about*)
pass; different **rigs for the same system** (`:pw`, `:wd`, `:headless`)
fail — cross-driver coverage is a run-level matrix: same features,
different config binding the same name to another adapter. Features mention
names; config binds adapters to names.

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

- `:error` — an object outside the loaded regions is a planning error, and raw
  locators are disallowed. (`:strict` is a deprecated alias — same behavior.)
- `:warn` — an unresolvable intent reference warns at planning time, with the
  name and a did-you-mean; raw locators and quoted literals stay exempt (the
  locator-policy escape hatch). The warning never blesses success — an
  unresolvable name still fails at runtime; it just fails *before* the run too.
- `:off` — no object checking.

**The default is conditional:** `:warn` when an `:intents` glossary is
configured (declared in `:glossaries` or found conventionally under
`glossary/intents/`), `:off` when none is — nothing declared means nothing to
validate against. Set `:unknown-object` explicitly to override either way.

This is what lets `Login.submit`-style intent references stand in for brittle
selectors and still be verified before a run.

### Step binding rules

How a step finds its definition — governed by whether it carries an interface
annotation.

**Rule 1 — no annotation.** The text is matched against *every* registered
stepdef. Exactly one must match: 0 → `:undefined` (planning error), 1 → bound,
2+ → `:ambiguous` (planning error). The bound step then resolves its
execution **instance** from the stepdef's type: exactly one configured
instance of that type → it; several → the type-named entry is the default;
several with no type-named entry → `:bind/ambiguous-instance` (planning
error naming the candidates — annotate the step).

```gherkin
When :user/alice clicks the submit button
```

**Rule 2 — with `[name]` annotation.** A leading keyword names a config
**instance**; the instance's declared `:type` narrows the candidate pool to
stepdefs of that type, and the same 0/1/2+ rule applies within it. The bound
step executes on the named instance's capability.

```gherkin
Then [:sms] :user/alice receives a message containing '(\d{6})'
When [:admin-console] :user/alice opens the browser to Dashboard
```

The annotation is also an assertion: if the name isn't configured under
`:interfaces`, you get `:annotation/unknown-interface` at planning time —
catching typos and missing config early. Use it to cross channel types
(`[:sms]` on a step between browser steps) and to pick one of several
same-type instances (`[:admin-console]` vs `[:storefront]` — both speak the
full `:web` vocabulary). Annotations are a Shifted-mode feature; without
Shifted's `:svo` configuration, `[:foo]` is just literal text. When the
annotation filters everything out, the planning diagnostic surfaces the
resolution chain: `No [:admin] (type :web) stepdef matches, but type :sms
does.`

---

## Configuration reference

### `:glossaries`

```clojure
{:glossaries
 {:subjects "glossary/subjects.edn"
  :verbs    ["glossary/verbs/web.edn"]  ; list of paths — each file's own
                                        ; :type is its mount key (sl-67dj)
  :intents  "glossary/intents"}}        ; path; drives object validation
```

### `:interfaces`

```clojure
{:interfaces
 {:web {:type :web :adapter :etaoin :config {:headless true}}}}
```

Each interface declares a `:type` (verb vocabulary), an `:adapter` (capability
provider, e.g. `:etaoin` for browsers), and adapter `:config`.

**Replace semantics** (since 0.5.6; announced as provisional in 0.5.5):

| Config | Meaning |
|--------|---------|
| No `:interfaces` key | The builtin defaults are mounted, unchanged (today: the `:web`/`:etaoin` instance). |
| `:interfaces` present | Your map is the **complete** set — it replaces the builtin defaults wholesale (the explicit-`:glossaries` precedent). |
| Entry value `:default` | The shipped builtin def of that name, **verbatim**. No overrides travel through the sentinel — to customize, write the full map. |
| Entry value = full map | Your definition, used **as-is**, under any name — including builtin names. `:web {…}` is legal and fully shadows the builtin; the builtin def is never consulted. A user map and a builtin def are **never merged**. |
| `:default` under a name with no builtin | Planning-time config error (`:config/unknown-default-interface`), never a silent empty. |

A full-map entry must declare its `:type` — the type is never inferred from
the name. The NAME addresses an instance; the TYPE selects its verb
vocabulary: `:admin-web {:type :web :adapter :etaoin}` is a second browser
instance under a non-builtin name, speaking the full `:web` vocabulary.
`:interfaces nil` is explicit too: zero interfaces. The `sl/` keyword
namespace is reserved for builtin interface names and types — user-defined
`:sl/*` names or `:type :sl/*` values are planning-time errors.

Keep one, bring one:

```clojure
{:interfaces
 {:web :default                        ; the builtin browser, by reference
  :api {:type :api :adapter :http}}}   ; yours, as written
```

The [`examples/04-sms-2fa`](../examples/04-sms-2fa) shape, with a second
browser instance:

```clojure
{:interfaces
 {:web       :default                            ; builtin browser, verbatim
  :sms       {:type :sms :adapter :sms-mock}     ; brought by the project
  :admin-web {:type :web :adapter :etaoin}}}     ; NAME ≠ TYPE: new name,
                                                 ; builtin :web vocabulary
```

### `:svo`

```clojure
{:svo
 {:unknown-subject :warn       ; :warn | :error
  :unknown-verb    :warn       ; :warn | :error
  :unknown-object  :warn       ; :error | :warn | :off (see below)
  :unknown-interface :error}}  ; :warn | :error
```

`:warn` logs and continues; `:error` fails at bind time, before execution.

`:unknown-object` defaults `:warn` when an `:intents` glossary is configured
and `:off` otherwise (an explicit value always wins). Its blocking level is
spelled `:error` like the siblings; `:strict` remains accepted as a deprecated
alias.

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
An `:instances` entry may be a bare keyword or a **persona map**
(`{:id :alicia :desc "…" :wears :alicia}`) carrying a docstring and a
per-persona costume — see [Lay out a glossary](#1-lay-out-a-glossary)
above and [COSTUMES.md](COSTUMES.md).

### Verbs

A verb is an **interface-level** action. A verb glossary file must declare its
`:type` — the mount key the loader files it under (file-authoritative,
sl-67dj); any keyword is legal, namespaced included. A verb entry requires
`:desc` **and** `:frames` — a closed set of argument shapes; a verb with no
extra arguments uses one `:default` frame:

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

Your verbs add to the defaults. On a name collision your entry replaces the
builtin entry **wholesale** (description and all frames — there is no
frame-level merge, so restating a builtin verb to add one frame drops its
other frames; `sl explain <verb>` shows the resolved entry and marks it
`config-override`). The `:override-defaults` flag was removed (sl-h7c3;
it never had an effect through the loaders) — a glossary file still
carrying it draws a warning: glossaries always extend the defaults, and
restating an entry is how you replace one.

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
| `:interface` | keyword | interface TYPE the step's vocabulary belongs to (`:web`, `:sms`) — never a config instance name, never an adapter |
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

When a bound step resolves an interface instance (annotation or default),
the framework provisions the capability:

1. Check the subject's context for `:cap/<instance>.<subject>` (e.g.
   `:cap/admin-console.alicia`).
2. If absent, look up the instance in `:interfaces`.
3. Call the adapter factory (`:etaoin`) with the instance's config.
4. Store it under the instance-keyed capability name.
5. Execute the step.

One capability exists per (instance × subject) — two same-type instances in
one scenario give the same subject two independent live sessions.

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

The `examples/` projects use the current `sl/glossary/` layout end-to-end —
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
