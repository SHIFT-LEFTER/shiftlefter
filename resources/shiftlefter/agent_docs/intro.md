# ShiftLefter Intro

ShiftLefter is a Gherkin/BDD test framework in Clojure: a Gherkin parser,
formatter, and runner (Gherkin-compliant — 50/50 official Cucumber test files), plus a
typed Subject–Verb–Object vocabulary that validates steps before any browser
or SMS run. There is one mode: ShiftLefter parses all Gherkin and executes
only typed steps — a suite whose steps bind to nothing is a planning result
(exit 2), not a way of running. It
drives real browser sessions (one per actor) and other interfaces from plain
feature files, and it is built for agents to author and repair behavior against
durable project vocabulary rather than one-shot generated scripts.

You are reading the agent surface. Two commands orient you: `sl orient` reports
this project's resolved context and what is wrong with the project itself
(config, glossaries, steps — not your features; `sl run --dry-run` checks
those), and `sl agent-doc --list` names the doctrine topics below.

To judge whether ShiftLefter is a fit for a particular project — "would this work
for us here?" — read `docs/FIT.md`, the practical fit check. Hand it to the user's
agent alongside their repo/URL; it carries an explicit fit checklist and the
honest capability boundaries.
