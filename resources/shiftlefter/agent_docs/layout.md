# Project Layout

The project-contents contract, both directions: what a project brings to
ShiftLefter, and what ShiftLefter writes back. Everything lives behind one
door:

```text
<project>/
  AGENTS.md              agent on-ramp stanza (only via `sl init --write-agents`, marker-guarded)
  .shiftlefter/          framework operational state — self-ignoring under sl init
  sl/                    the ShiftLefter project (yours, committed)
    sl, shiftlefter-*.jar  launcher + jar (installer convention)
    shiftlefter.edn      the ONLY discovered config location
    setup.clj            run/group lifecycle + feature-plan owner (optional, fixed name)
    hooks.clj            scenario-lifecycle registry (optional, fixed name)
    features/            feature corpus default
    glossary/            subjects.edn, verbs/, intents/ (probed defaults)
    steps/               dev-step default
    lib/                 user jars — drop in, they're on the classpath
    results/             written by runs (self-ignoring)
```

## What you bring

Defaults are relative to the config root (`sl/`). Explicit config always wins.

| Artifact | Required? | Default | Override |
|---|---|---|---|
| `shiftlefter.edn` | effectively yes | `sl/shiftlefter.edn`, the only discovered location (git-bounded walk-up) | `-c FILE` |
| feature files | yes | no `setup.clj`: `[:runner :features]` default `["features/"]`; with `setup.clj`: the declared plan IS the corpus | config key / CLI paths (subset of the plan under setup) |
| `glossary/subjects.edn` | in Shifted mode | probed at `glossary/subjects.edn` | `:glossaries` — explicit value, *including nil*, used as written |
| intents | in practice, Shifted web | probed at `glossary/intents/` | `:glossaries {:intents …}` |
| verb glossaries | no (built-ins cover) | probed at `glossary/verbs/*.edn`; each file's `:type` is its mount key | `:glossaries {:verbs [paths]}` |
| dev steps | no | `[:runner :step-paths]` default `["steps/"]` | config / `--step-paths` |
| `setup.clj`, `hooks.clj` | no | sibling of the active config, fixed names | none — fixed by design |
| macro registries | with `:macros {:enabled? true}` | `:registry-paths` required; INI files or directories (dir loads every `.ini`, recursive, sorted) | configured paths (0.6 moves INI → typed EDN, hard cut) |
| `lib/*.jar` | no | `sl/lib/` | none — wrapper-owned convention |
| machine config | no | `~/.shiftlefter/config.edn` (chromedriver/chrome paths) | contents yes, location no |

Bare `sl run`: without `setup.clj` it runs the corpus default; with
`setup.clj` it runs the full declared plan. Plan shape is linted before
anything runs — a group matching no files or two groups sharing a label
fails the plan; the same feature in two groups warns.

## User code — the wall

User Clojure enters at three named attachment points, each optional, each
with a fixed discovery rule, each validated at load: **steps**
(config-pointed), **hooks.clj** (sibling-of-config), **setup.clj**
(sibling-of-config, owns the feature plan). A fourth point is reserved for
custom adapters. All user code is trusted, in-process, one classpath;
`sl/lib/*.jar` extends it with no build tooling. `shiftlefter.edn` is
**data only** — code never arrives through config. There is no orchestration
DSL and never will be: `:start` is arbitrary user Clojure, a throw means the
group is skipped honestly and other groups run, and cleanup of half-started
state is yours. Keep `setup.clj`'s top level pure (it is load-file'd even on
`--dry-run`; side effects belong inside `:start`). Dry-run starts nothing —
and binds honestly: a group declares its custom adapters as pure data
(`:adapters [{:name .. :provides [..]}]`, the delta vs the default registry);
the preview binds against the declarations and the registry `:start` returns
is verified to match them (mismatch = planning error
`:setup/registry-mismatch`). An undeclared `:start`-returned registry still
works this release with a preview-blind warning (deprecated shape). The warm
daemon re-load-files `setup.clj`
per dispatch — the framework re-derives everything and sees your file fresh;
state your code establishes outside the file (spawned threads, system
properties, open connections) is yours to manage.

Custom reporting today = consume the machine record (`--edn` stdout stream,
or the full record in `report.html`) — contract and runnable recipe in
`docs/REPORTS.md`; live in-run reaction = hooks; bus observers have no user
path. The data contracts (EDN summary shape, bus
event envelope) are locked — code written against the data survives any
future attachment mechanism.

## What the framework writes

The framework writes only into places it owns (below); files the user owns
are never written unprompted — changes arrive as proposals (SIEVE proposes,
never applies): proposed, never written without an explicit yes. Two
named, marker-guarded exceptions, both the direct object of an explicit
user command (the sl-7dq5 ownership boundary): `sl init --write-agents`
writes the agent on-ramp stanza into `AGENTS.md` (create-or-append between
markers; an existing stanza is never edited; plain `sl init` never touches
it — it prints the offer instead), and `sl costume init` appends the
protective wardrobe line to `.gitignore`.

- `sl/results/<STAMP>/` per invocation: `report.html`, per-scenario
  `attachments/<slug>/`, per-group subdirs under `setup.clj`. Self-ignoring
  (`results/.gitignore` = `*`).
- `--junit-xml`/`--html` flags resolve against the shell cwd; their config
  mirrors resolve against the config root; the flag wins.
- `.shiftlefter/` at the project root: `wardrobe/<name>/` (costumes — fixed
  location, no config key), `daemon.edn`, `daemon.log`, `lib-sig`,
  `spawn.lock`, `dirty-teardown.edn` (failed `:stop` marker, announced next
  run), `sieve/`. Self-ignoring: `sl init` writes `.shiftlefter/.gitignore`
  (`*`) at scaffold time, so a scaffolded project never commits it. A
  project that never ran `sl init` adds `.shiftlefter/` to its own
  `.gitignore` — the framework otherwise self-ignores only `results/`,
  (on `sl costume init`) `wardrobe/`, and the installed binaries: the
  installer writes an `sl/.gitignore` (if absent) covering the jar, the
  `.jsa` CDS archive, and `lib/`, so authored files in `sl/` stay
  committable.

## Environment variables

`SL_NO_DAEMON=1` runs cold for the shell session; **any** `SHIFTLEFTER_*`
variable also forces the cold path (daemon env is frozen at spawn);
`NO_COLOR` disables ANSI; `TWILIO_ACCOUNT_SID`/`TWILIO_AUTH_TOKEN` are the
Twilio adapter's credential fallback. `SL_USER_CWD` is internal — don't set
it.
