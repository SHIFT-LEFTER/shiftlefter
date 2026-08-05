# ShiftLefter Authoring

Subjects are actors. A subject instance is one actor session, such as
`:user/alice`, not just a label. Keep that distinction when writing scenarios:
the subject tells ShiftLefter who is acting and which session owns capability
state. Different instances (`:user/alice`, `:user/bob`) get separate browser
sessions automatically — the instance name is the session key, no setup code.

Shifted syntax may annotate a step with an interface, for example `[:web]`.
Interfaces own verb vocabularies. Do not assume a verb valid for one interface is
valid for another.

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
`:$1`, `:$2` reference capture groups by position:

```clojure
(defstep #"^(\S+) clicks (.+)$"
  {:interface :web
   :svo {:subject :$1 :verb :click :object :$2}}
  [ctx subject object]
  ...)
```

## Minimal config

`sl/shiftlefter.edn` — the ONLY discovered location; everything ShiftLefter
(config, features, glossaries, steps) lives in the project's `sl/` directory,
and config-declared paths resolve from it (so `"steps/"` means `sl/steps/`).
Note `:step-paths` lives **under `:runner`** — a top-level `:step-paths` is
ignored (since 0.5.2 the runner prints a `Config warning:` with the correct
path; older versions ignore it silently):

```clojure
{:runner {:step-paths ["steps/"]}

 ;; Shifted mode (optional) — enabled by the presence of :svo
 :interfaces {:web {:type :web :adapter :etaoin}}
 ;; Glossaries: sl/glossary/{subjects.edn,verbs/<type>.edn,intents/} are
 ;; discovered automatically when :glossaries is unset; an explicit map
 ;; overrides the convention wholesale.
 :svo {:unknown-subject :warn}}
```

## Common mistakes

| DON'T | DO |
|-------|-----|
| `(defstep #"..." [n] ...)` | `(defstep #"..." [ctx n] ...)` — `ctx` is always first |
| Forget to return `ctx` | Return `ctx` or `(assoc ctx ...)` from every step |
| `:step-paths` at config top level (warned since 0.5.2, ignored either way) | Nest it under `:runner` |
| `When :login-form submits` | Subjects are actors: `When :user/alice clicks Login.submit` |
| Compare a capture as a number | `(parse-long n)` first — captures are strings |

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
