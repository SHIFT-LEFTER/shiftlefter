# ShiftLefter SIEVE Bootstrap

SIEVE is the workflow for turning observed or proposed interface facts into
reviewable project vocabulary. It is not a license to silently accept guesses.

**Status in 0.5: preview.** SIEVE's interactive tooling is a development/REPL
surface; there is no `sl` subcommand for it yet. What you can do today: name the
missing vocabulary, draft the glossary/intent edits as an ordinary reviewable
change (a patch or PR against the project's `glossary/` files), and let a human
accept it. The discipline below describes the workflow's shape either way.

When accepted vocabulary is missing, collect evidence, draft candidate subjects,
verbs, intents, objects, and bindings, then reconcile the proposal. Applying a
proposal writes selected accepted changes into the project. A later projection or
agent prompt can then report those changes as accepted truth.

Before apply, drafts remain drafts. Agents should name the gap, explain the
candidate evidence, and leave unresolved or conflicting claims unresolved.

Use this path when authoring needs vocabulary that is not already present. Do not
hide missing vocabulary by writing raw locators or custom phrases that bypass the
project language.
