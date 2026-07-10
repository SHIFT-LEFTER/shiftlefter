# ShiftLefter Docs

ShiftLefter is a disciplined, typed way to drive real software behavior from
Gherkin — multiple users, multiple interfaces, checked before anything runs. Each
step resolves to a Subject–Verb–Object triple validated against a vocabulary you
control, so a typo'd actor or an undefined verb fails at bind time — not several
minutes into a browser run.

It's early and openly scoped (see [What 0.5 is](#what-05-is-and-isnt) below). The
core — driving a browser with multiple independent actors over a typed glossary —
is solid and meant to be built on. The surrounding test-suite machinery and the
traceability graph are on the way.

## See it work

The fastest way in is [Add your first browser behavior](browser-getting-started.md)
— a real multi-actor browser test with zero custom code. Watch the discipline
hold, then come back for the why.

## What 0.5 is (and isn't)

ShiftLefter today is for **building new (greenfield) behavior** — especially if you
want a disciplined, agent-authorable test vocabulary. It is **not yet** a drop-in
for a commercial Cucumber/Selenium suite.

**Solid — build on it**

- Multi-actor browser driving — multiple independent sessions with real isolation.
- The typed SVO/glossary discipline — your vocabulary, validated before execution.
- The `sl` CLI — run, format, dry-run, diagnose; CI-ready output (JUnit XML, HTML, EDN), tag filtering, parallel scenarios.

**Preview — works, expect change**

- Glossary *authoring* — the glossary is the heart; writing one works, and the
  tooling to bootstrap it from an existing app is early.
- Macros as domain verbs — they work, but they're a stopgap; the polished form is
  coming.

**Not here yet — roadmap**

- Test fixtures and hooks as first-class features.
- Brownfield migration of an existing suite.
- The traceability graph — the destination.

If your team needs first-class fixtures and hooks *today*, it isn't ready
for you yet — check back. If you're starting something new and the discipline
appeals, you're exactly who it's for.

## Do I need to write Clojure?

For the normal path, **no** — you (and/or your agent) author a glossary (plain EDN) and write features
against built-in steps. Clojure enters in only three cases: setting up test
fixtures/data (a current gap), genuinely unusual web work (canvas, custom JS), or
contributing a new interface. See [Add domain language](extending-vocabulary.md).

## Pick a path

| I want to… | Go to |
|---|---|
| decide whether it fits my project | [Would ShiftLefter work for my project?](FIT.md) |
| get a browser test running | [Add your first browser behavior](browser-getting-started.md) |
| choose a locator that survives refactors | [Choosing web locators](LOCATORS.md) |
| understand the validation model | [SVO](SVO.md) |
| model multiple users & sessions | [Multiple actors](multiple-actors.md) |
| test across web + SMS | [Across interfaces](across-interfaces.md) |
| add my own domain language | [Extend the vocabulary](extending-vocabulary.md) |
| drive authenticated / real-account sessions | [Costumes](COSTUMES.md) |
| know what I can run (Java vs Clojure) | [What can I do?](CAPABILITIES.md) |
| look up a term | [Glossary](GLOSSARY.md) |
| bring existing Gherkin (preview) | [Adoption](adoption.md) |

Runnable projects live in [`examples/`](https://github.com/SHIFT-LEFTER/shiftlefter/tree/main/examples)
— [`examples/README.md`](https://github.com/SHIFT-LEFTER/shiftlefter/tree/main/examples/README.md)
is the ordered learning path. For agents and contributors: [AGENT.md](AGENT.md)
(or `sl agent-doc --list`).
