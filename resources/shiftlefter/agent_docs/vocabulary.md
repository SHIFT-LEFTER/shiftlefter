# ShiftLefter Vocabulary

Accepted vocabulary is project truth. Glossaries define subjects and verbs.
Intent files define named objects, regions, and bindings that steps can address.

Subjects are actors. Subject instances are sessions. Interface TYPES own
verbs and their frames; configured interface instances of one type all share
its vocabulary. Frames type argument shapes, never authorization — who may
do what is discovered by substituting actors and observing the system's real
response, not declared in vocabulary. Intents name objects or regions that an interface type can
target.

The subjects glossary is a plain EDN file. Types with `:instances` group
sessions under a role (`:user/alice`, `:user/bob`); a type without `:instances`
is a singleton, used directly:

```clojure
;; glossary/subjects.edn
{:subjects
 {:user  {:desc "Standard user" :instances [:alice :bob]}
  :admin {:desc "Administrator"}}}
```

An `:instances` entry may also be a persona map — a user with an optional
costume and a docstring: `{:id :alicia :desc "Spanish-first admin reviewer"
:wears :alicia}`. The `:desc` surfaces in `sl explain` and `sl glossary
subjects` (use it to pick the right persona); `:wears` binds that persona to
a costume, resolving instance > type — a type-level `:wears` is an
inheritable default, a persona's own `:wears` wins, `:wears nil` opts out.

Declare each costume's interface INSTANCE in `shiftlefter.edn`:
`:costumes {:finance {:interface :web}}`. Plan-time lints then enforce the
costume doctrine statically: wearing a costume on a different instance than
it is declared for is an error (with a did-you-mean), two targets sharing a
`:wears` in one scenario is an error, and a worn-but-undeclared costume is
a warning that teaches the declaration to add.

Project verbs extend the built-in set in `glossary/verbs/*.edn` files; each
file declares its own `:type` — the required, authoritative mount key, any
keyword including namespaced ones (a same-named project verb replaces the
builtin entry wholesale, frames and all — `sl glossary` marks such entries
`config-override`); intents live under `glossary/intents/` (all under
`sl/`, next to the config — these conventional paths are discovered
automatically when `:glossaries` is unset). Once an intents glossary is
configured (declared or discovered), object checking defaults on at `:warn`:
an unresolvable intent reference is reported at planning time with the name
and a did-you-mean (`:svo {:unknown-object …}` — `:error` blocks, `:off`
silences; raw locators and quoted literals are exempt). The full file formats
and validation model are in `docs/SVO.md`.

Partial vocabulary is normal. A project may have accepted subjects but missing
verbs, or accepted verbs but incomplete intents. Treat that as useful diagnostic
state, not permission to invent the rest.

When vocabulary is incomplete, say what is known, say what is missing, and route
new claims through SIEVE/bootstrap and proposal reconciliation before treating
them as accepted.
