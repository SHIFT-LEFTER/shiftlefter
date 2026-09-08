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

;; observe-and-judge: the predicate RETURNS the observation, :until judges it
(defstep #"the export job (\S+) has finished"
  [ctx job-id]
  (step/poll-until ctx #(fetch-job job-id)            ; => {:state "running" :pct 85}
                   {:until #(= "done" (:state %))})
  ctx)
```

`poll-until` polls at the resolved interval/deadline (`:timing` config,
instance > interface > global > default — 30s/500ms bare) until `:until`
accepts the predicate's return. **Return the observation; let `:until`
judge it.** On timeout it throws a structured `:step/poll-timeout` through
normal step reporting — elapsed, deadline, deadline SOURCE, and the last
attempt through exactly one of two channels: `:last-value`, the last thing
the predicate returned (here `{:state "running" :pct 85}` — the diagnosis,
not just "timed out"), or `:last-error`, the last exception a
`:retry-error?` predicate accepted as a not-yet (the built-in browser
oracles ride this channel; your own steps rarely need it). `poll-until`
returns the observation that satisfied `:until`, so the step can use it.
Never wrap a mutation in it; never poll a bare negation (waiting-for-gone =
poll a negated predicate, explicitly). `(step/timing ctx)` reads the budget
without polling.

Footnote — the nil-to-continue form (`(step/poll-until ctx #(when done? …))`,
no `:until`) still works, since the default test is truthiness, but it
**leaves `:last-value` empty on timeout**: a predicate that returns nil to
keep polling has thrown away what it saw. The timeout record says so
(`:hint "pred returned nil to continue; return the observation and pass
:until to populate :last-value"`). Prefer the form above.

### Evidence in a custom adapter: log the attempt, then the outcome

If your adapter feeds an evidence log — a transcript a capture kind dumps
when a scenario fails — record the attempted operation **before** executing
it, then record the outcome. A helper that logs only after a response
arrives has a structural blind spot: the fatal request (connection refused,
timeout, DNS failure) is exactly the one that never gets a response, so the
trail ends one line before the moment of death. The first real `:api`
adapter we built hit this: the service was killed mid-run, the capture fired
faithfully, and the transcript's last entry was the final *healthy* request.

```clojure
;; BLIND: the fatal request never reaches the log
(defn request! [impl req]
  (let [resp (http/request req)]
    (log-entry! impl req resp)          ; never runs when http/request throws
    resp))

;; HONEST: attempt first, outcome second — a refused request appears
;; in the capture with its failure noted
(defn request! [impl req]
  (let [entry (log-attempt! impl req)]
    (try
      (let [resp (http/request req)]
        (log-outcome! impl entry resp)
        resp)
      (catch java.io.IOException e
        (log-outcome! impl entry {:failed (ex-message e)})
        (throw e)))))
```

The capture fn stays dumb — it dumps whatever the log holds; whether the
moment of death is *in* that log is decided at the helper, by ordering
alone.

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

A user `:interfaces` map is the **complete** set — it replaces the builtin
defaults wholesale (no `:interfaces` key = builtin defaults, today the
`:web`/`:etaoin` instance; `:interfaces nil` = explicitly zero). Per entry:
`:default` includes the shipped builtin def of that name verbatim (no
overrides through the sentinel), and a full map is used as-is under any
name — including `:web`, which then fully shadows the builtin. A user map
and a builtin def are **never merged**, and a full map must declare its
`:type` (never inferred from the name — `:admin-web {:type :web …}` is a
second browser instance). `:default` under a name with no builtin is a
planning-time config error, and the `sl/` namespace is reserved for
builtin interface names and types. So a project that declares only `:sms`
has exactly one interface, and `sl orient` says so:

```clojure
:interfaces {:web :default                      ; keep the builtin browser
             :sms {:type :sms :adapter :sms-mock}} ; bring your own
```

## Common mistakes

| DON'T | DO |
|-------|-----|
| `(defstep #"..." [n] ...)` | `(defstep #"..." [ctx n] ...)` — `ctx` is always first |
| Forget to return `ctx` | Return `ctx` or `(assoc ctx ...)` from every step |
| `:step-paths` at config top level (warned since 0.5.2, ignored either way) | Nest it under `:runner` |
| `When :login-form submits` | Subjects are actors: `When :user/alice clicks Login.submit` |
| Compare a capture as a number | `(parse-long n)` first — captures are strings |
| Hand-roll `Thread/sleep` or a polling loop in a step | `(step/poll-until ctx observe {:until done?})` — rides the `:timing` ladder, fails legibly with the last observation aboard |
| Poll with a predicate that returns nil to keep going | Return what you observed and pass `:until` — a nil-idiom timeout carries no `:last-value` (the record's `:hint` says so) |
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
