# Add Domain Language

Most of what you need is built in — driving a browser or SMS interface takes zero
custom code. When you want your *own* domain language, it comes from **data you
author** — glossaries, macros, and intents — not step code you write.

Hold onto one distinction, because it's the source of most confusion: **declaring
vocabulary types it; it does not give it behavior.** A glossary entry tells the
validator a word is legal. What actually *runs* is always a step — a built-in one, a
macro that expands to built-ins, or (rarely) a `defstep` you wrote. Keep "name a
thing" and "make it run" separate and the rest of this page follows.

The vocabulary has three layers, in rough order of how often you'll touch them.

## Interface verbs — the built-in set (you rarely add to it)

A verb is an **interface-level** action: `click`, `fill`, `see`, `navigate`,
`select`, `scroll`, `hover`. The adapter ships a closed set; each verb declares a
`:desc` and a closed set of **frames** (the argument shapes it accepts). The
authoritative current list — each verb with its frames and step patterns — is
`sl agent-doc builtins`.

You *can* add a verb to a project glossary, but think twice. A verb glossary
file must declare its `:type` — the mount key the loader files it under
(file-authoritative, sl-67dj; the config's `:glossaries :verbs` is just a list
of file paths, and any keyword type works, namespaced included). A glossary
verb only makes the validator *accept* the word — it doesn't make anything
happen. A genuinely
new interface-level verb also needs a `defstep` to back it (see
[the escape hatch](#when-you-actually-write-a-step-definition)), and that's
adapter-author territory, not the normal path.

**If you're reaching for a custom verb to express a domain action like "checks out"
or "logs in" — stop.** That isn't an interface verb; it's a contraction of several,
and it belongs in a macro. (Next section.)

## Domain actions — macros

A domain action like *"checks out"* or *"logs in"* isn't one interface action; it's a
*contraction* of several — navigate, fill, fill, click. You express that as a
**macro**: a registry entry that expands to built-in steps, invoked with a trailing
` +` (space-plus) that keeps provenance back to the call site.

Macros are authored as registry files (INI), not code. The `name` is matched against
the whole step text:

```ini
name = alice logs in
description = Log alice in through the UI
steps =
  Given :user/alice opens the browser to 'http://localhost:9090/login'
  When :user/alice fills {:id "user"} with 'alice'
  And :user/alice fills {:id "pass"} with 'secret'
  And :user/alice clicks {:css "button[type=\"submit\"]"}
```

Then call it — the step text must match the macro's `name` exactly, with ` +`
appended:

```gherkin
Given alice logs in +
```

Enable macros and point at your registry:

```clojure
:runner {:macros {:enabled? true :registry-paths ["macros/"]}}
```

Each entry is a path relative to your config: a directory loads every `.ini`
file under it (recursively, in sorted order), and a direct file path like
`"macros/login.ini"` works too.

Macros are how you get domain shorthand **without writing code** — but be honest that
they're a stopgap right now: the expansion is a fixed text match (no clean
subject/argument parameterization yet), and the polished "domain verb" form — where
one domain verb can expand *differently across interfaces* — is on the roadmap.

## Intents — name objects, not selectors

This is the highest-leverage layer. Instead of pinning a step to a brittle selector,
name the element semantically — `Login.submit` — and define what that name points to
once, in `glossary/intents/`:

```edn
;; glossary/intents/login.edn
{:intent "Login"
 :elements
 {:email  {:bindings {:web {:css "#email"}}}
  :submit {:bindings {:web {:css "button[type=submit]"}}}}}
```

A step's **Object** is then the intent reference `Login.submit`, validated before the
run. When the page moves, you update the binding in one place and every feature that
references it follows. Intent names are PascalCase; element names start lowercase,
kebab or lowerCamel (`submit`, `add-to-cart`, `smsCode`). Elements carry
per-interface bindings (`:web`, `:mobile`, …), and intents can nest via `:root` /
`:collections` for components that repeat — see
[SVO.md](SVO.md#object-validation-against-intent-regions).

An element whose text carries a value can also declare the **shape** of that
text — `:shape "Order (?<orderNumber>[A-Z0-9-]+)"` — so a step reads
`captures Checkout.confirmation` and the regex lives with the element, not in
the scenario. See [Test Across Interfaces](across-interfaces.md#passing-a-value-between-interfaces-a-declared-shape-and-a-token).

## When you actually write a step definition

Writing your own Clojure `defstep` is **not the normal path** — for ordinary web and
SMS actions, built-in steps plus macros are meant to cover you. Reach for `defstep`
only here:

- **Test fixtures / data setup** — seeding a database, standing up state. A real gap
  today; first-class fixtures are planned, and until then a custom step is the honest
  stopgap.
- **Genuinely unusual web** — driving a canvas, running custom JavaScript, niche
  interactions the built-ins don't cover.
- **A new interface verb or adapter** — you're extending ShiftLefter itself. That's a
  contribution, and you're the best kind of user.

**Composing interfaces is no longer on this list.** Passing a value captured
on one interface into another is built in: declare the shape of the text on
the intent element (`:shape "verification code is: (\\d{6})"` on
`Reset.smsCode`), match it (`receives an SMS … matching Reset.smsCode`) to
produce a scenario binding, consume it as `{smsCode}` in any literal-admitting
slot — an inline `/regex/` with a named group is the escape hatch. See
[Test Across Interfaces](across-interfaces.md#passing-a-value-between-interfaces-a-declared-shape-and-a-token).
The 2FA demo's old 14-line handoff `defstep` is deleted.

One forward note for custom steps you do write: a step definition may read
and write ctx freely today (that's what makes the fixture escape-hatch
work), but direct ctx writes are slated to be fenced behind a declared
calling convention in the 0.6 contracts arc — prefer the documented
surfaces (capabilities, `bindings/capture!`) where one exists. Ctx
*metadata* is not yours at all: the framework restamps `(meta ctx)` with
the `:step/*` keys before every step, so metadata you attach to a
returned ctx does not survive to the next one (see the
[control loop](architecture/control-loop.md) § ctx contract).

If you're reaching for `defstep` to do an ordinary browser or SMS action, step back —
there's almost certainly a built-in step or a macro that fits. When you do need it,
no Clojure toolchain is required (the jar loads `.clj` files directly):

```clojure
(ns my.steps
  (:require [shiftlefter.stepengine.registry :refer [defstep]]
            [cheshire.core :as json]))

;; a fixture step: seed state before a scenario
(defstep #"^the catalog is seeded from \"([^\"]+)\"$"
  [ctx path]
  (assoc ctx :catalog (json/parse-string (slurp path) true)))
```

The bundled libraries available to step definitions (JSON, HTTP, filesystem, …) are
listed in [INSTALL-TIERS.md](INSTALL-TIERS.md#tier-b-write-custom-steps-java-only).

## Evidence in a custom adapter: log the attempt, then the outcome

If your adapter feeds an evidence log — a transcript a capture kind will dump
when a scenario fails (see [hooks.md § Contributing a capture
kind](hooks.md#contributing-a-capture-kind--custom-adapters)) — record the
attempted operation **before** executing it, then record the outcome. A helper
that logs only after a response arrives has a structural blind spot: the fatal
request — connection refused, timeout, DNS failure — is exactly the one that
never gets a response, so the evidence trail ends one line before the moment of
death. This bit the first real `:api` adapter we built: the service was killed
mid-run, the capture fired faithfully, and the transcript's last entry was the
final *healthy* request.

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

The capture fn itself stays dumb — it dumps whatever the log holds
(`examples/07-custom-capture-kind` is the ten-line contract in the flesh);
whether the moment of death is *in* that log is decided here, at the helper,
by ordering alone.

## Timing in a custom step: call the kernel, don't reimplement the doctrine

Built-in steps follow one timing doctrine, and it has three clauses:

- **Oracles retry patiently.** A step that *observes* (`should see`, `waits for`,
  `receive`) polls until the condition holds or a deadline elapses.
- **Mutations never retry.** A step that *acts* (click, type, send) runs exactly
  once — mutations are not idempotent, and a retried click is a second click.
- **Negation never waits.** `should not see` checks once, now. Waiting for
  something to *disappear* is a different operation: an oracle over a negated
  predicate (`waits until gone` = poll until "not visible" is true) — ask for
  that explicitly.

A custom step that hand-rolls a `Thread/sleep` loop silently diverges from all
three. Instead, call the doctrine — `poll-until` in `shiftlefter.step` (the
namespace your steps already require for ctx accessors) is the oracle kernel:

```clojure
(ns my.steps
  (:require [shiftlefter.stepengine.registry :refer [defstep]]
            [shiftlefter.step :as step]))

;; an oracle, observe-and-judge: the predicate RETURNS what it observed
;; (a job snapshot), and :until judges whether that observation is "done"
(defstep #"^the export job (\S+) has finished$"
  [ctx job-id]
  (step/poll-until ctx #(fetch-job job-id)            ; => {:state "running" :pct 85}
                   {:until #(= "done" (:state %))})
  ctx)
```

`poll-until` polls the predicate at the framework's resolved interval and
deadline until `:until` accepts its return. On timeout it throws a
structured, legible failure through normal step reporting: elapsed time,
the deadline, **where the deadline came from**, and the last attempt
through exactly one of two channels — `:last-value`, the last thing the
predicate returned (`{:state "running" :pct 85}` is the diagnosis, not
just "timed out"), or `:last-error`, the last exception a `:retry-error?`
predicate accepted as a not-yet (the built-in browser oracles ride that
channel). Any observation works — a count, a payload:

```clojure
(step/poll-until ctx #(count (fetch-rows)) {:until #(<= 5 %)})
```

The nil-to-continue form (`(step/poll-until ctx #(when done? …))`, no
`:until`) still works — the default test is truthiness — but it leaves
`:last-value` empty on timeout: a predicate that returns nil to keep
polling has thrown away what it saw. The timeout record says so (`:hint`).
Return the observation instead.

Never wrap a mutation in `poll-until`, and never poll a bare negation — those
clauses are prohibitions, not missing helpers.

### Where the deadline comes from

Deadlines and intervals resolve down a ladder — first rung that answers, wins:

1. explicit opts to `poll-until` (or a rebound builtin dynamic var)
2. `:timing` on the interface **instance** entry in `shiftlefter.edn`
3. `:timing` on the entry named by the instance's **type**
4. the top-level `:timing` map — `{:timing {:deadline-ms 30000 :interval-ms 500}}`
5. defaults (bare `poll-until`: 30s / 500ms; built-in browser verification:
   3s / 100ms; `:wait` frames: 5s / 100ms; SMS receive: 30s / 500ms)

Built-in steps ride the same ladder, so one `:timing` entry in config slows or
tightens the whole suite coherently — and every timeout report names the rung
that decided it. `(step/timing ctx)` returns the resolved budget if a step needs
to read it without polling.

One forward note, same shape as the ctx-writes note above: declared operation
classes (a `defstep` stating "I am an oracle / a mutation / a negation" and the
framework wrapping it accordingly) are slated for the 0.6 contracts arc. Until
then the doctrine is yours to follow — `poll-until` makes following it the
shortest path.
