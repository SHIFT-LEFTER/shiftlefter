# Why ShiftLefter is built this way

This is the optional read. If you want to *use* ShiftLefter, start with
[Would ShiftLefter work for my project?](FIT.md) and the
[getting-started guide](browser-getting-started.md) — you never need this page to
write a test. This is for the reader whose next question is *"why is it shaped like
this, and should I align my own design with it?"*

Short version: the features aren't a pile of separate ideas bolted together. They fall
out of one, and the one is a reaction to a single chronic disease.

## The disease: most testing pain is underspecification

Spend enough years with test suites and the failures start to rhyme. The selector
that worked Tuesday and rots Thursday. The scenario that passes while testing the
wrong thing. The word "user" meaning a different actor in every other step. The pull
request that's two thousand lines of churn nobody can actually review. The suite that
gets *slower* to trust the more people touch it.

These look like different problems. They're one problem wearing different clothes:
**the test under-specifies what it means.** The values are anonymous. The actor is
implied. The element is identified by where it happens to sit in the DOM rather than
what it *is*. Nothing in the test pins meaning down, so meaning drifts — and every
drift is a future failure already written, just not yet triggered.

The agent era makes this acute rather than new. An agent can write you a passing
browser test in one shot today; that stopped being the hard part. The hard part is
the second shot, and the tenth — because each regeneration re-derives the app's
meaning from scratch. Its own selectors, its own names, its own guess at who "the
user" is. Run that loop across a few different models and the tests drift apart from
each other and from the app, *faster* the more agents touch it. Underspecification is
the substrate; non-determinism just pours in through the gap.

## The cure: behavior as a typed vocabulary you own, validated before you run

So the one idea is the direct inverse of the disease. Treat behavior as a **typed
vocabulary you own**, and **validate it before you run it.**

"Strongly type your tests and a lot of the chronic pain stops happening" sounds like
a slogan; it's mechanical. Typing is the discipline that refuses anonymous,
underspecified values — and underspecified values are where the rot lives. Every
feature below is that one move applied to a different axis of a test. Read them as
consequences, not features.

## Subjects are never anonymous

The classic step says *"I click login."* Who is *I*? In the next scenario it's
someone else, and nothing notices. ShiftLefter bans the anonymous first person — not
as a style rule, as a typing rule. Every step has a named, typed **subject**:
`:user/alice`, `:shopper`, `:auditor`. The subject is the first value the test refuses
to leave implied.

This is the no-anonymous-values rule applied to the *actor*. It reads like a small
syntactic nag and pays off as something larger: once actors are first-class typed
identities rather than an implied ambient "user," the test can finally say things that
were previously unsayable — *which* actor, *whose* session, *who* is denied and how.

And these aren't just blessed strings. A subject is a real *type*, defined once in your
glossary — a single source of truth the rest of your system can point at and that can
point back: the `:auditor` your tests name is the one your role reviews and
access-control docs can refer to. (Where that leads — *reasoning over* those types, not
just matching them — is [below](#where-this-is-going).)

It also keeps the unavoidable honest. When a test genuinely must reach past the
interface — seed a database, wake a sleeping service, initialize a fixture — you name
that actor for what it is (`:test-harness`, `:audit`) instead of smuggling it in as the
user. The boundary crossing gets *labeled*, not hidden.

## One subject, one session

Because subjects are real identities and not a shared ambient context,
multi-actor scenarios fall out almost for free. `:user/alice` and `:user/bob` may
have identical capabilities, but they get **separate, isolated sessions** — distinct
cookies, distinct logins, distinct state. That distinctness *is* the point of
multi-actor testing: alice does something, and you assert what bob can now see.

Durable per-subject state has a home too: a **costume** is the state an actor *wears*
into a scenario — not just an authenticated session, but the whole interface get-up:
profile, extensions, language and locale, settings. (Costume is a generic interface
concept; the browser is today's focus.) Authored once, it persists across runs, so
you're not rebuilding it every scenario. (See [Costumes](COSTUMES.md).)

None of this needed a separate "multi-user feature." It's what you get once the actor
is a typed value instead of a pronoun.

## Steps speak at the interface

A step's object is the next place tests usually under-specify. `click(".btn-primary >
div:nth-child(3)")` says where an element sits, not what it is — so it breaks the
moment the page moves. ShiftLefter's steps speak at the **interface** instead.

Two mechanisms carry that:

- **Verbs declare a closed set of frames.** A verb (`clicks`, `fills`, `sees`) isn't
  open-ended text matching; it accepts a *fixed* set of argument shapes — its frames.
  One frame is one accepted way to say it. Closed, because an open vocabulary is just
  underspecification with extra steps; the closure is what lets the framework *check*
  a step instead of hoping a regex caught it. The authoritative built-in set is
  `sl agent-doc builtins`.
- **Intents name objects, not selectors.** You write `Login.submit`, a semantic
  **intent** defined once in your glossary, and the step validates that intent as its
  Object. When the page moves you update the intent in one place; the features that
  reference it don't change. The brittle selector is demoted from "the thing the test
  is about" to an implementation detail behind a name.

So a step is a Subject–Verb–Object triple where all three are typed: a named actor,
a verb with declared frames, an object resolved through a named intent. That's the
SVO discipline, and it's the same anti-underspecification move pointed at the object.

> **Where this points (not shipped):** a domain verb like *"checks out"* is really a
> *contraction* of a sequence of interface steps. Today you can approximate that with
> macros, which work but are a stopgap (see
> [Add Domain Language](extending-vocabulary.md)). The reason the polished form
> matters: because a domain verb contracts *interface* verbs, the same domain verb can
> expand differently across different interfaces — *"checks out"* over the web and over
> SMS — which makes your domain language portable across the surfaces it runs on. That
> portability is the destination, not a 0.5 capability.

## Caught before the browser opens

If meaning is typed, it can be *checked* — and the cheapest time to check is before
anything expensive runs. A typo'd actor, an undefined verb, an intent that doesn't
resolve: these fail at **bind time**, with Levenshtein "did you mean?" suggestions —
not three minutes into a live browser run, not as a flake you re-run twice and shrug
at. You can dry-run the binding, lint a whole suite at load, and read structured
diagnostics without launching a browser at all.

This is where the agent-era payoff lands. You can't make a language model
deterministic, but you can give it a typed surface that **rejects its mistakes early
and legibly.** The glossary is shared ground truth: any model — this one, the one you
switch to next quarter — reads the same definitions and means the same thing by
`:shopper`. The non-determinism doesn't disappear; it gets contained at a boundary
that fails loud and cheap instead of quiet and expensive. Validation-before-execution
is the typing discipline collecting its dividend.

And containment cuts cost, not just drift. When the typed surface and sensible defaults
supply the determinism, the model has to supply less of it — so work that would
otherwise need a frontier model can often run on a smaller, cheaper one, at fewer
tokens, for the same result.

## The step is the quantum

The deepest "why" is about what a step *is*, and therefore what ShiftLefter
deliberately refuses to talk about. Its unit — its quantum — is the **step**: one
subject acts, the world settles, the subject observes a stable state. Three refusals
define that quantum, and each refusal does real work.

- **Boundary by preference (space).** Tests observe the system at its declared
  interfaces. You *can* reach inside — sometimes you must — but ShiftLefter never
  prefers it, and the subject rule means even a white-box step is captured honestly:
  `:test-harness` touched the database, not "the user." The boundary stays visible even
  when you cross it. The preference still does real work as a forcing function: if an
  invariant ("money is conserved across all accounts") can't be seen by any actor at
  the boundary, the honest move isn't a silent peek — it's to grow the subject who
  *should* see it (an auditor reading a reconciliation surface) and the surface they
  read. The goal is to make the correct thing easy, not to insist on purity.
- **Ordinal time, not metric (time).** Steps are *ordered*; duration carries no
  meaning. Time is turn-based: you act, the world reaches quiescence, you assert on
  the settled state — never on the path between turns. Built-in polling exists only to
  *reach* quiescence; a sleep is itself a step. This is why ShiftLefter doesn't claim
  to test real-time behavior: the cursor tracking a target within 16ms lives *below*
  the turn, and the framework is deliberately blind there. It tests the turn-based
  shell of any system — including the menus and settings of a real-time one.
- **Decidable oracles (judgment).** Every assertion has to be a predicate a machine
  can settle at the boundary: present or absent, equal or not, inside a threshold or
  outside it. "The animation feels good" has no such predicate, so it's out — not
  because it doesn't matter, but because no oracle exists. The framework verifies what
  can be verified, so that human attention is freed for the judgments only humans can
  make. Where the oracle is missing, the framework still earns its keep *around* it: it
  can drive the system to the exact moment the judgment is needed, set the stage, and
  surface just that point to a human — so scarce human attention goes only where no
  machine can stand in.

One sentence: below the turn in time, behind the boundary in space, beyond the oracle
in judgment — deliberately blind. Everything it *does* accept lives on one side of
that quantum, which is why the model keeps absorbing new kinds of behavior without
deforming. The refusals aren't limitations bolted on; they're what makes the rest
coherent.

## Where this is going

This page describes the shipped surface and the reasoning under it. There is a larger
arc, and honesty requires keeping it clearly separated from what runs today.

The typed vocabulary is worth building now because of where it leads: toward
**executable traceability** — your requirements and use cases linked to the features
that realize them, the tests that exercise them, and the runs that prove them, with
observation lineage from the real world back to the spec. The glossary and intents you
write today are exactly the foundation that graph is built on.

The same typing runs deeper than validation. Today a subject is a real type; the
destination is a subject as a *node* you can reason over — substitute one role for
another and check what should break. Run the power user's whole suite as a guest and
assert every privileged step is denied: role auditing that falls out of treating actors
as types, not strings. The core is already true; using it this way is ahead.

And the vocabulary is meant to be the join key to the rest of your stack — the same
identities your tests name eventually lining up with the use cases you spec, the
requirements in your wiki, the screens in your design prototypes. One source of truth
for what things *mean*, across tools that today each reinvent it. There's no mechanism
for that yet, and maybe never a single one — it's the direction the single-sourcing is
*for*.

That is **roadmap, not shipped.** Don't adopt 0.5 *for* the graph; adopt it for the
semantic layer, and know the discipline you put in early is what makes the rest
possible. For the precise line between what you can build on, what's preview, and
what's still ahead, see the
[stability contract](README.md#whats-solid-whats-preview-whats-roadmap).

## Where this stops

This is a read, not the whole philosophy — and it's meant to end. The point was to
show the one idea and watch the shipped features fall out of it; that's done. If you
want more depth, it lives in the published docs, each owning its own corner:

- [Would ShiftLefter work for my project?](FIT.md) — the practical fit check.
- [SVO](SVO.md) — the Subject–Verb–Object model and glossary formats in detail.
- [Add Domain Language](extending-vocabulary.md) — verbs, frames, intents, macros.
- [Capabilities](CAPABILITIES.md) — exactly what each install tier unlocks.

You don't need any of it to use the tool. You needed the *why*, and that was the why.
