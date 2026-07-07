# ShiftLefter Agent Overview

This is packaged agent doctrine for ShiftLefter: concise operating guidance an
agent can read straight from the release artifact, offline, without the project's
private docs. Each topic is a focused subread; `sl agent-doc --list` names them.

ShiftLefter turns Gherkin requirements into executable checks. The stable loop is:
author accepted vocabulary, write feature steps against that vocabulary, validate,
then run. Do not silently invent accepted project truth.

Core commands:

- `sl agent-doc --list` lists packaged doctrine topics.
- `sl agent-doc <topic>` prints one topic.
- `sl orient` orients you in a project: resolved context, what would fail
  (static validation), validate/run commands, and pointers to these topics.
- `sl run <path>` executes features.
- `sl run <path> --dry-run` binds every step without executing — the cheap
  validation pass to run before a real browser run.
- `sl fmt --check <path>` checks that features parse and match canonical formatting.
- `sl verify` is a framework-development tool: in a consumer project it prints a
  notice and does nothing. Use `sl orient` and `--dry-run` to validate a project.

`sl orient --edn` dumps the entire project projection as one EDN value — the
machine-readable feed for a planning or disposable sub-agent reasoning over the
project's whole shape (coverage, negative space) rather than editing it.

Read next:

- `sl agent-doc authoring`
- `sl agent-doc vocabulary`
- `sl agent-doc builtins`
- `sl agent-doc locators`
- `sl agent-doc diagnostics`
- `sl agent-doc sieve`
