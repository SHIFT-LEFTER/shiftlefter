# Project Layout

The project-contents contract, both directions: what a project brings to
ShiftLefter, and what ShiftLefter writes back. Everything lives behind one
door:

```text
<project>/
  AGENTS.md              breadcrumb (yours, optional)
  .shiftlefter/          framework operational state — gitignore it
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
| verb glossaries | no (built-ins cover) | probed at `glossary/verbs/<iface>.edn` | `:glossaries {:verbs …}` |
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
but binds against the default adapter registry, so custom-registry projects
can bind differently in preview. The warm daemon re-load-files `setup.clj`
per dispatch — the framework re-derives everything and sees your file fresh;
state your code establishes outside the file (spawned threads, system
properties, open connections) is yours to manage.

Custom reporting today = consume the machine record (`--edn` stdout stream,
or the full record in `report.html`); live in-run reaction = hooks; bus
observers have no user path. The data contracts (EDN summary shape, bus
event envelope) are locked — code written against the data survives any
future attachment mechanism.

## What the framework writes

- `sl/results/<STAMP>/` per invocation: `report.html`, per-scenario
  `attachments/<slug>/`, per-group subdirs under `setup.clj`. Self-ignoring
  (`results/.gitignore` = `*`).
- `--junit-xml`/`--html` flags resolve against the shell cwd; their config
  mirrors resolve against the config root; the flag wins.
- `.shiftlefter/` at the project root: `wardrobe/<name>/` (costumes — fixed
  location, no config key), `daemon.edn`, `daemon.log`, `lib-sig`,
  `spawn.lock`, `dirty-teardown.edn` (failed `:stop` marker, announced next
  run), `sieve/`. **Add `.shiftlefter/` to your `.gitignore`** — the
  framework only self-ignores `results/` and (on `sl costume init`)
  `wardrobe/`.

## Environment variables

`SL_NO_DAEMON=1` runs cold for the shell session; **any** `SHIFTLEFTER_*`
variable also forces the cold path (daemon env is frozen at spawn);
`NO_COLOR` disables ANSI; `TWILIO_ACCOUNT_SID`/`TWILIO_AUTH_TOKEN` are the
Twilio adapter's credential fallback. `SL_USER_CWD` is internal — don't set
it.
