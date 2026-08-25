# ShiftLefter Authoring

Subjects are actors. A subject instance is one actor session, such as
`:user/alice`, not just a label. Keep that distinction when writing scenarios:
the subject tells ShiftLefter who is acting and which session owns capability
state. Different instances (`:user/alice`, `:user/bob`) get separate browser
sessions automatically — the instance name is the session key, no setup code.
A persona instance can carry a docstring and its own costume in the subjects
glossary (`{:id :alice :desc "…" :wears :alice-chrome}`); run
`sl explain user/alice` to see which persona to use in which circumstances.

Shifted syntax may annotate a step with an interface instance, for example
`[:web]` or `[:admin-console]`. The annotation names an entry in the config's
`:interfaces` map — an execution target; the entry's `:type` selects which
verb vocabulary applies. Interface TYPES own verb vocabularies; every
configured instance of a type shares that vocabulary. Do not assume a verb
valid for one type is valid for another. Unannotated steps default to the
type-named instance when a type has several instances.

Step definitions bind text to code. Prefer steps that describe user intent and
project vocabulary over implementation detail. Custom steps should match the
accepted subject, verb, and object language already present in the project.

The step text itself follows one marker grammar — quotes mean literal,
unquoted Capitalized names mean glossary, `{name}` means a carried value,
`/…/` means a pattern. The full rule set, slot by slot, with every reserved
form: `sl agent-doc grammar`.

If a needed subject, verb, object, or intent is missing, report the gap and use
the bootstrap/reconciliation workflow. Do not make the vocabulary look accepted
just because a plausible phrase exists.

## Writing a step definition

`defstep` binds a regex to a function. The first argument is always `ctx` (the
scenario context map); the remaining arguments are the regex capture groups, and
**captures are always strings**. The function must **return `ctx`** (or an updated
copy) — forgetting to return it breaks the chain. Throw to fail the step.

```clojure
(ns steps.cucumbers
  (:require [shiftlefter.stepengine.registry :refer [defstep]]))

(defstep #"I have (\d+) cucumbers"
  [ctx n]
  (assoc ctx :cucumbers (parse-long n)))   ; captures are strings — parse them

(defstep #"I should have (\d+) cucumbers"
  [ctx n]
  (assert (= (parse-long n) (:cucumbers ctx)))
  ctx)                                       ; return ctx
```

In Shifted mode, attach `:svo` metadata so the step resolves to a typed triple.
`:$1`, `:$2` reference capture groups by position. `:interface` names the
interface TYPE the verb belongs to — never a config instance name or an
adapter:

```clojure
(defstep #"^(\S+) clicks (.+)$"
  {:interface :web
   :svo {:subject :$1 :verb :click :object :$2}}
  [ctx subject object]
  ...)
```

### Timing: the three-clause doctrine

Oracles retry patiently; mutations never retry; negation never waits. A custom
step that OBSERVES something asynchronous never hand-rolls a sleep/poll loop —
it calls the kernel in `shiftlefter.step`:

```clojure
(require '[shiftlefter.step :as step])

(defstep #"the nightly report has landed"
  [ctx]
  (step/poll-until ctx #(.exists (java.io.File. "out/report.csv")))
  ctx)
```

`poll-until` polls until truthy at the resolved interval/deadline (`:timing`
config, instance > interface > global > default — 30s/500ms bare). On timeout
it throws a structured `:step/poll-timeout` through normal step reporting:
elapsed, deadline, deadline SOURCE, last observed value (pass `:until` when
the predicate observes a value, e.g. `{:until #(<= 5 %)}`). Never wrap a
mutation in it; never poll a bare negation (waiting-for-gone = poll a negated
predicate, explicitly). `(step/timing ctx)` reads the budget without polling.

## Minimal config

`sl/shiftlefter.edn` — the ONLY discovered location; everything ShiftLefter
(config, features, glossaries, steps) lives in the project's `sl/` directory,
and config-declared paths resolve from it (so `"steps/"` means `sl/steps/`).
Note `:step-paths` lives **under `:runner`** — a top-level `:step-paths` is
ignored. This is one instance of a general lint: any plausible key placed at
the top level (`:step-paths`, `:junit-xml`, `:dialect`, `:unknown-subject`,
…) is ignored by every reader, and the runner warns with the correct nested
path (`did you mean [:runner :step-paths]?`); a key it cannot place gets a
plain unknown-key warning. Warnings, never errors — a newer config must
still run on an older version. Under `--edn` the same lints ride the record
as `:config-lints`, and `sl orient` reports them too. (Warned since 0.5.2;
older versions ignore silently.)

```clojure
{:runner {:step-paths ["steps/"]}

 ;; Shifted mode (optional) — enabled by the presence of :svo
 :interfaces {:web {:type :web :adapter :etaoin}}
 ;; Glossaries: sl/glossary/{subjects.edn,verbs/<type>.edn,intents/} are
 ;; discovered automatically when :glossaries is unset; an explicit map
 ;; overrides the convention wholesale.
 :svo {:unknown-subject :warn}}
```

The built-in default `:web` instance deep-merges **under** your
`:interfaces` map rather than being replaced by it — a project that
declares only `:sms` still has the default `:web` instance, and
`sl orient` shows it. These merge semantics are provisional: a future
release may make a user `:interfaces` map replace the defaults wholesale.

## Common mistakes

| DON'T | DO |
|-------|-----|
| `(defstep #"..." [n] ...)` | `(defstep #"..." [ctx n] ...)` — `ctx` is always first |
| Forget to return `ctx` | Return `ctx` or `(assoc ctx ...)` from every step |
| `:step-paths` at config top level (warned since 0.5.2, ignored either way) | Nest it under `:runner` |
| `When :login-form submits` | Subjects are actors: `When :user/alice clicks Login.submit` |
| Compare a capture as a number | `(parse-long n)` first — captures are strings |
| Hand-roll `Thread/sleep` or a polling loop in a step | `(step/poll-until ctx pred)` — rides the `:timing` ladder, fails legibly |
| Retry a click/type/send that "flakes" | Mutations never retry — fix the oracle before it, or the app |

## URL assertions beyond the built-in frames

`should be on <X>` asserts the REGION: normalized path + fragment, query
string ignored (query is state, not region), host ignored, trailing slash
normalized. **Quoted = literal, always; bare = ref**: a bare intent name
(`should be on Feed`) resolves via the intent's `:location`; a quoted value
(`should be on '/feed'`) is a literal. `should be on exactly '<url>'` asserts the exact resource + state
structurally: full URL required, path and fragment exact, query compared as a
multimap — cross-key order insignificant, duplicate-key value order
significant. It takes a quoted literal or a captured `{binding}` token
(`should be on exactly {resetLink}`); bare intent names stay region-only.

Anything more exotic — percent-encoding normalization, host-case rules,
byte-exact string comparison — is deliberately not built in. Write a custom
step assertion (`defstep` + the browser's current URL) for the case at hand,
or petition for a third frame if the need is general.

## Recording why: the provenance convention

A scenario or glossary entry may carry a pointer to the reason it exists —
the ticket, decision, or conversation that motivated it. Two forms:

- a value-shaped tag: `@because=CHK-4412`
- a comment on its own line, directly above the thing it explains:
  `# why: <free text>`

```gherkin
@because=CHK-4412
Scenario: Guest checkout survives an expired session
  # why: carts were lost on session expiry twice in 2025 — CHK-4412
  Given alice opens the browser to Checkout
```

In EDN glossary files, the same convention is an EDN comment above the
entry: `;; why: renamed from "basket" — PROD-88`.

The pointer is opaque. `@because=` is an ordinary tag and `# why:` is an
ordinary comment: nothing parses, validates, or resolves either one, and
planning never checks the pointer against anything. This is a documented
convention, not a schema — it exists so rationale accretes in the corpus
from day one, and it may be promoted to a first-class annotation when
use-case artifacts land. Until then, write the pointer for the next
reader, not for the machine.

Comment placement matters: put `# why:` on the line above the step or
scenario it explains. The canonical formatter attaches every comment to the
construct that *follows* it, so a comment above a step travels with that
step through `sl fmt` — while a comment after a scenario's last step
belongs to the next scenario. One current limit: don't place a comment
between a step and its data-table rows — the parser rejects it there.

## Worked examples

For complete, runnable scenarios — including the canonical multi-actor flow
(`04-sms-2fa`, two users with separate sessions) — see the examples at
<https://github.com/SHIFT-LEFTER/shiftlefter/tree/main/examples>.
