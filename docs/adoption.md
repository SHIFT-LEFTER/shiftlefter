# Bring Existing Gherkin (Preview)

**Migrating an existing suite is not the 0.5 story.** ShiftLefter today is for
authoring *new* behavior in Shifted mode; full migration support comes in a later
release. This page is here so you know where things stand — not to walk you
through a migration.

What's true now: the parser is 100% Gherkin-compatible (it passes all 46 official
Gherkin test files), so your `.feature` *files* parse unchanged — you can point
`sl fmt --check` and `sl run` at them.

What does **not** come over: your step definitions. ShiftLefter runs built-in
steps (browser, SMS) or step definitions written in Clojure — not an existing
Java/JavaScript/Ruby step library. And the table stakes a commercial suite leans
on — xUnit/HTML reporting, test fixtures, hooks, tag filtering — aren't here yet
(see [What 0.5 is](README.md#what-05-is-and-isnt)).

So:

- **Evaluating a migration?** Treat 0.5 as a preview of where this is going. The
  discipline is real and the destination — typed, traceable behavior — is worth
  watching. But don't move a production suite onto it yet.
- **Starting fresh?** Skip this page — go
  [add your first behavior](browser-getting-started.md). Greenfield is what 0.5 is
  built for.
