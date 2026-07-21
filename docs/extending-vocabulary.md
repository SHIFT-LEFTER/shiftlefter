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

You *can* add a verb to a project glossary, but think twice. A glossary verb only
makes the validator *accept* the word — it doesn't make anything happen. A genuinely
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
references it follows. Intent names are PascalCase; element names are lowercase
(`submit`, `add-to-cart`). Elements carry per-interface bindings (`:web`, `:mobile`,
…), and intents can nest via `:root` / `:collections` for components that repeat —
see [SVO.md](SVO.md#object-validation-against-intent-regions).

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
on one interface into another is built in: name a regex group
(`(?<code>\d{6})`) to produce a scenario binding, consume it as `{code}` in
any literal-admitting slot — see
[Test Across Interfaces](across-interfaces.md#passing-a-value-between-interfaces-named-bindings).
The 2FA demo's old 14-line handoff `defstep` is deleted.

One forward note for custom steps you do write: a step definition may read
and write ctx freely today (that's what makes the fixture escape-hatch
work), but direct ctx writes are slated to be fenced behind a declared
calling convention in the 0.6 contracts arc — prefer the documented
surfaces (capabilities, `bindings/capture!`) where one exists.

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
listed in [CAPABILITIES.md](CAPABILITIES.md#tier-b-write-custom-steps-java-only).
