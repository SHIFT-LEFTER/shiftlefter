# ShiftLefter Intro

ShiftLefter is a Gherkin/BDD test framework in Clojure: a Gherkin parser,
formatter, and runner (Gherkin-compliant — 46/46 official Cucumber test files), plus a
typed Subject–Verb–Object vocabulary — "Shifted" mode, the normal way to use
ShiftLefter — that validates steps before any browser or SMS run. It
drives real browser sessions (one per actor) and other interfaces from plain
feature files, and it is built for agents to author and repair behavior against
durable project vocabulary rather than one-shot generated scripts.

You are reading the agent surface. Two commands orient you: `sl orient` reports
this project's resolved context and what would fail, and `sl agent-doc --list`
names the doctrine topics below.

To judge whether ShiftLefter is a fit for a particular project — "would this work
for us here?" — read `docs/FIT.md`, the practical fit check. Hand it to the user's
agent alongside their repo/URL; it carries an explicit fit checklist and the
honest capability boundaries.
