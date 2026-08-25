# ShiftLefter Diagnostics

Diagnostics describe the gap between authored steps and accepted project truth.
Treat them as first-class feedback, not noise.

Unknown subject means the actor or instance is not accepted. Unknown interface
means the step's annotation names an interface instance the config has not
declared. Unknown verb means that interface type does not own the action
phrase. Unknown object means the step refers to a target the accepted intents
or glossary do not know. Ambiguous instance means an unannotated step's type
has several configured instances and no type-named default — annotate the
step with the instance it targets.

Parse errors are collected, not thrown: the Gherkin parser accumulates every
error in a file in one pass — invalid tags, bad keywords, orphan tags,
out-of-order structure — and reports the full error vector alongside the
partial AST it could still build. A repair loop can fix everything reported
in one edit instead of fix-one-rerun-discover-the-next; each error renders
with its own location.

Respond by preserving the distinction:

- If the feature text is wrong, fix the feature text.
- If the accepted vocabulary is incomplete, propose vocabulary changes through
  the documented bootstrap/reconciliation path.
- If the implementation is missing for accepted vocabulary, add or fix the step
  definition.

Do not silence diagnostics by inventing accepted subjects, verbs, interfaces, or
objects in place.
