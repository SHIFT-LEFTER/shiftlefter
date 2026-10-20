# Resolution Queries: sl glossary and sl explain

The files are the DECLARED state; these two commands expose the RESOLVED
state — what a run will believe, before the run. Both resolve through the
run's own config and glossary loaders (never a parallel path), so their
answers cannot disagree with the run they predict. Both accept
`-c/--config` exactly as `sl run` does, and both take `--edn`.

**EDN is the contract.** `--edn` output is the stable machine surface —
one reader-readable map per invocation. The human format is EXPLICITLY
UNSTABLE; do not parse it.

## sl glossary

```
sl glossary [subjects|verbs|objects|duplicates] [path ...]
            [--tags T] [--skip-tags T] [--edn] [-c FILE]
```

Bare `sl glossary` prints three sections; a narrowing argument selects
one. Every entry carries a **provenance marker**:

- `builtin` — shipped with the framework (default verb glossaries)
- `user-file` — declared in a project glossary file
- `config-override` — a project entry that replaced a builtin of the
  same name
- `interface-contributed` — vocabulary an interface family supplies
  (the closed W3C key-name set for frames with `:object-kind :key`;
  implicit objects such as `:sms-message`)

Sections: **subjects** (types + instances), **verbs** (grouped per
interface type, each frame shown as schema — frames are NOT step text),
**objects** (intents plus the interface-contributed vocabulary above).

The output header states the substrate the listing was computed against:
the resolved config path and source, the git sha with a dirty marker
(or an honest "not a repository"), and the EFFECTIVE `:svo` strictness
levels — including the conditional `:unknown-object` default that flips
to `:warn` when intents are configured, which is not readable from any
file.

## Usage markers and dead vocabulary

Listings also bind the feature corpus (parse + bind, never execute — the
same binder FUNCTION a run uses, over a different corpus: the config's
feature tree, not the union a `setup.clj`'s groups would actually
execute, so usage counts can include scenarios no run selects) and mark
every entry with its usage: subjects
and user vocabulary that nothing references show `[UNUSED]`; used
entries show `[used ×N]`. **Builtin vocabulary is never dead** — it is
globally provided, so builtins carry counts but never a deadness
verdict. Deadness is a **full-corpus** fact at a named tree state: the
header states the corpus (file/scenario counts, the git sha, the config
path), and if any feature file fails to parse or any step fails to bind,
the caveat is printed rather than silently under-counting. No corpus at
all → markers are suppressed entirely (never "everything is unused").

**Scoped selection**: positional paths and `--tags`/`--skip-tags` (the
run's own selection grammar) restrict the corpus. Under a selection the
wording weakens to `[not referenced within this selection]` and the EDN
key changes from `:unused?` to `:referenced-in-selection?` — a scoped
verdict is an impact-analysis fact, NEVER a deletion license. Only the
full-corpus voice may say "unused".

## sl glossary duplicates

The on-demand locator-collision report: locator values owned by two or
more intent addresses within one interface, grouped by confidence —
same-intent collisions (`Login.foo` vs `Login.bar`) are high-confidence
real duplicates; cross-intent collisions are likely page-scoped
coincidence (the same `#submit` on different pages is the norm, which is
why this report is never always-on). Equality is structural over the
locator EDN value; semantic aliasing (two XPaths, one element) is out of
scope. Owners cite their defining intent file.

## sl explain NAME

```
sl explain NAME [path ...] [--tags T] [--skip-tags T] [--edn] [-c FILE]
sl explain '{:css "#submit"}' [--edn]
```

One name in, full resolution out. Accepts `click`, `:click`,
`user/alice`, `Login` — every matching kind is reported (subject type,
subject instance, verb per interface type, intent, key name). Per match:
kind, provenance, definition site, frames (schema), and **per-interface
bindings including missing ones** — a frame with no stepdef on a
configured interface is reported as MISSING, not silently omitted.

Definition sites are file-precise for glossary and intent entries (EDN
carries no line info) and file:line-precise where that truly exists —
stepdef bindings and their `bind with:` examples.

Every match also carries its corpus **USAGE**: subjects get the full
actor view — per-instance hits (feature file:line, scenario, the step
text and its pattern — authoring by example), the type rollup, a
multi-actor flag, and co-occurrence pairs (which other actors share
scenarios); verbs and intents get their reference hits. The same
selection parameters apply, with the same wording discipline.

**Explain a locator**: an argument that starts with `{` is read as a
locator EDN value and reverse-looked-up — which intent addresses own it
(element binding, region root, or collection selector), per interface.
Check before minting a new intent binding: a hit means the element is
already named; a miss says "free to mint". Structural equality only.

On a miss: exit 1 with did-you-mean suggestions (edit distance plus
surface-token lookup — `sl explain clicks` suggests `:click` from the
frame pattern "S clicks O"). EDN mode reports `{:status :miss}` with
`:suggestions` on stdout. A malformed invocation (no NAME) exits 2.

## Merge semantics (verified, sl-0gfc)

Project verb glossaries EXTEND the builtin set. On a name collision the
project entry replaces the builtin entry **wholesale** — description and
all frames; there is no frame-level merge, so restating a builtin verb
to add one frame silently drops its other frames. `sl explain <verb>`
shows exactly what survived and marks the entry `config-override`.
`defstep` contributes step BINDINGS, never vocabulary — a stepdef naming
a verb absent from the glossary is reported (`:unknown-verb`, `:warn` by
default — it warns and runs; unknown frames or args on a KNOWN verb stay
hard errors), never an addition.

## Scope

Both commands always resolve: a project with no glossary of its own lists
the framework defaults, and steps that did not bind are named in a `Caveat:`
line because they contribute no usage facts. Macros
contribute steps, not vocabulary — they do not appear here. The
coverage-matrix renders (vocabulary × interface grids) are a future
surface pending a second interface type in real use; the per-intent
`missing bindings:` markers in the objects listing carry the hole data
today.
