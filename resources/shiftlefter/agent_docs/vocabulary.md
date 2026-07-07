# ShiftLefter Vocabulary

Accepted vocabulary is project truth. Glossaries define subjects and verbs.
Intent files define named objects, regions, and bindings that steps can address.

Subjects are actors. Subject instances are sessions. Interfaces own verbs and
their frames. Intents name objects or regions that an interface can target.

The subjects glossary is a plain EDN file. Types with `:instances` group
sessions under a role (`:user/alice`, `:user/bob`); a type without `:instances`
is a singleton, used directly:

```clojure
;; glossary/subjects.edn
{:subjects
 {:user  {:desc "Standard user" :instances [:alice :bob]}
  :admin {:desc "Administrator"}}}
```

Project verbs extend the built-in set per interface type in
`glossary/verbs/<type>.edn`; intents live under `glossary/intents/`. The full
file formats and validation model are in `docs/SVO.md`.

Partial vocabulary is normal. A project may have accepted subjects but missing
verbs, or accepted verbs but incomplete intents. Treat that as useful diagnostic
state, not permission to invent the rest.

When vocabulary is incomplete, say what is known, say what is missing, and route
new claims through SIEVE/bootstrap and proposal reconciliation before treating
them as accepted.
