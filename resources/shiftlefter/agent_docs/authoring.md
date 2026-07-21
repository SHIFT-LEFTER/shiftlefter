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

`shiftlefter.edn` in the project root. Note `:step-paths` lives **under
`:runner`** — a top-level `:step-paths` is ignored (since 0.5.2 the runner
prints a `Config warning:` with the correct path; older versions ignore it
silently):

```clojure
{:runner {:step-paths ["steps/"]}

 ;; Shifted mode (optional) — enabled by the presence of :svo
 :interfaces {:web {:type :web :adapter :etaoin}}
 :glossaries {:subjects "glossary/subjects.edn"}
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

## Worked examples

For complete, runnable scenarios — including the canonical multi-actor flow
(`04-sms-2fa`, two users with separate sessions) — see the examples at
<https://github.com/SHIFT-LEFTER/shiftlefter/tree/main/examples>.
