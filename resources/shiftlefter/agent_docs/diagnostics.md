# ShiftLefter Diagnostics

Diagnostics describe the gap between authored steps and accepted project truth.
Treat them as first-class feedback, not noise.

Unknown subject means the actor or instance is not accepted. Unknown interface
means the step points at a capability vocabulary the project has not declared.
Unknown verb means that interface does not own the action phrase. Unknown object
means the step refers to a target the accepted intents or glossary do not know.

Respond by preserving the distinction:

- If the feature text is wrong, fix the feature text.
- If the accepted vocabulary is incomplete, propose vocabulary changes through
  the documented bootstrap/reconciliation path.
- If the implementation is missing for accepted vocabulary, add or fix the step
  definition.

Do not silence diagnostics by inventing accepted subjects, verbs, interfaces, or
objects in place.
