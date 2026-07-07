## ShiftLefter

This project uses **ShiftLefter** for executable BDD/Gherkin requirements. If you
are an AI coding agent working here, ShiftLefter ships an agent surface that grep
cannot give you. Use it before authoring or editing features and step definitions.

You cannot run a command to discover that the command exists — ShiftLefter has to
be advertised here, outside the tool itself (the bootstrap paradox). This stanza is
that entry point: it is the documented way in. Start with:

- `sl agent-doc` — authoring rules and the operating model (`sl agent-doc --list` lists every topic).
- `sl agent-doc builtins` — the built-in vocabulary (verbs, frames, step patterns, adapters) that lives in the jar and cannot be grepped from this repo.
- `sl orient` — resolved project context and static validation: what exists and "what would fail", with no side effects.

For a planning or disposable research sub-agent that needs the whole project shape
at once (coverage, negative space), `sl orient --edn` emits the entire project
projection as one machine-readable value.
