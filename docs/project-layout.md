# Project Layout — What You Bring, What ShiftLefter Writes

This page is the project-contents contract, both directions: every artifact a
consumer project brings to ShiftLefter (how required, where it's found, what
overrides), and every file ShiftLefter writes back into your project (what it
holds, who owns it in git). It describes an installed project — launcher + jar
— which is also exactly what the dev repo's `examples/` look like. Every claim
below is verified against the code this page ships with; the
[verification appendix](#verification-appendix) says where.

Everything ShiftLefter lives behind one door:

```text
<project>/
  AGENTS.md              ← the one deliberate root touch (breadcrumb; yours, optional)
  .shiftlefter/          ← framework operational state ("ours" — gitignore it, see below)
  sl/                    ← the ShiftLefter project ("yours", committed)
    sl                   ← launcher script        ┐ installer convention
    shiftlefter-*.jar    ← the framework jar      ┘
    shiftlefter.edn      ← the ONLY discovered config location
    setup.clj            ← run/group lifecycle + feature-plan owner (optional, fixed name)
    hooks.clj            ← scenario-lifecycle registry (optional, fixed name)
    features/            ← feature corpus (conventional default)
    glossary/            ← subjects.edn, verbs/, intents/ (probed defaults)
    steps/               ← dev-step definitions (conventional default)
    lib/                 ← user jars — drop them in, they're on the classpath
    results/             ← written by runs (self-ignoring)
```

## What you bring

Paths in the **Default** column are relative to the config root (the directory
holding `shiftlefter.edn` — `sl/` in the discovered layout) unless marked
otherwise.

| Artifact | Required? | When it matters | Default | Override |
|---|---|---|---|---|
| `shiftlefter.edn` | effectively yes | any real run (`sl orient` works without) | `sl/shiftlefter.edn` — the only discovered location; discovery walks parent dirs up to the git boundary | `-c FILE` (exotic layouts) |
| launcher + jar | yes, to run | always | `sl/` (installer default) | installer `--dir`; the wrapper finds any sibling `shiftlefter*.jar` |
| feature files | yes | always | **no `setup.clj`:** `[:runner :features]`, default `["features/"]` → `sl/features/`; bare `sl run` runs the corpus. **with `setup.clj`:** the declared plan IS the corpus; bare `sl run` runs the full plan | config key, or CLI paths (under `setup.clj` they must be a subset of the declared plan) |
| `glossary/subjects.edn` | yes in Shifted mode (`:svo` in config) | actors exist | probed at `glossary/subjects.edn` | `:glossaries {:subjects …}` — an explicit `:glossaries`, *including nil*, is used as written |
| intents | yes in practice, in Shifted mode | any intent-addressed step (all real web use) | probed at `glossary/intents/` | `:glossaries {:intents …}` |
| verb glossaries | no — built-ins cover the normal path | extending vocabulary | probed at `glossary/verbs/<iface>.edn` (`web` `api` `sms` `email`) | `:glossaries {:verbs …}` |
| dev steps (`.clj`) | no | custom step definitions | `[:runner :step-paths]`, default `["steps/"]` | config key, or `--step-paths` (resolved against your shell's cwd) |
| `setup.clj` | no | orchestration: standing SUT, groups, custom adapter registries | sibling of the active config, fixed name | none — fixed by design |
| `hooks.clj` | no | scenario Before/After lifecycle | sibling of the active config, fixed name | none — fixed by design |
| macro registries | no | only with `:macros {:enabled? true}` | none — `:registry-paths` must be set when enabled; entries are INI files **or directories** (a directory loads every `.ini` under it, recursively, sorted) | configured paths |
| `lib/*.jar` | no | run-time user dependencies (a JDBC driver for a hook, adapter deps) | `sl/lib/` | none — fixed convention, wrapper-owned |
| `AGENTS.md` breadcrumb | no | agent discoverability | project root; the installer *prints* the stanza and never writes your files | yours entirely |
| machine config | no | ChromeDriver/Chrome paths for this machine | `~/.shiftlefter/config.edn` — home-dir, per-machine, never per-project | contents yes; location no (fixed by design) |

Notes the table can't hold:

- **Features, both modes.** Without `setup.clj`, the corpus is config-pointed
  with a conventional default — bare `sl run` is well-defined and runs
  `sl/features/`. With `setup.clj`, the union of the groups' `:features` IS
  the plan: files on disk but not declared are ignored, CLI paths outside the
  declared union are a planning error, and the plan shape itself is linted
  before anything runs (a group matching no files, or two groups sharing a
  label, fails the plan; the same feature in two groups warns — a deliberate
  matrix is legitimate). There is no default-features fallback when no config
  was found at all: no config, no corpus.
- **Macro registries carry a planned change:** the 0.6 contracts arc replaces
  the INI format with typed EDN as a hard cut. The location contract above
  (paths, file-or-directory) is the part expected to survive.
- **The wardrobe is not in this table** because you don't bring it — costumes
  are created by `sl costume init` and live in framework-written state. See
  the next section.

## User code — the wall

User-supplied Clojure enters a run at **three named attachment points**, each
optional, each with a fixed discovery rule, each validated at load, each
visible in the evidence: **steps** (the authored corpus, config-pointed),
**hooks.clj** (the scenario-lifecycle registry, sibling-of-config), and
**setup.clj** (the run/group lifecycle and feature-plan owner,
sibling-of-config) — with a fourth point *reserved* for custom adapter
implementations, to be designed when the first real one materializes. All user
code runs trusted and in-process on one classpath; `sl/lib/*.jar` extends that
classpath with no build tooling — drop a jar in, it rides every run, and the
warm daemon bounces when the lib set changes exactly as it does when the jar
changes. The framework's promise is legibility, not restriction: what ran,
when, from where, and why is always a mechanical query — and guidance flows
down a ladder (declarative contracts over data declarations over lifecycle
code) by making weakness legible in evidence, never by prohibition.

**`shiftlefter.edn` is data, only.** Code never arrives through config — no
inline functions, no `:handler` keys, no eval. Every piece of user code
enters through one of the named species files above. This is a property, not
a gap: it keeps "which user code runs" answerable by listing files.

**There is no orchestration DSL, and there never will be.** `setup.clj`'s
`:start` is arbitrary user Clojure: sequencing, waiting, and branching are
your code or your scripts. The framework promises exactly one thing — a
`:start` that throws means that group is skipped and honestly reported, and
the other groups still run. Anything `:start` half-started before throwing is
yours to clean up (wrap your own try/catch). There is likewise no cross-group
product flow: groups don't feed each other, ever.

Truths about `setup.clj` worth knowing before you write one:

- **Keep the top level pure.** `setup.clj` is `load-file`'d to make the plan
  knowable — its top-level forms evaluate even on `--dry-run`. Side effects
  belong inside `:start`.
- **Dry-run starts nothing.** `sl run --dry-run` previews the full plan —
  groups, features, hooks — and never invokes `:start`, `:stop`, or hooks.
  One caveat, stated rather than softened: the preview binds steps against
  the *default* adapter registry, so a project whose adapters exist only in a
  custom registry from `:start` can bind differently in preview than in a
  real run.
- **The warm daemon re-load-files `setup.clj` on every dispatch.** The
  framework re-derives everything per dispatch and sees your file fresh, the
  same way a jar bounce would give it to a cold JVM — but state your code
  establishes *outside* the file (spawned threads, system properties, open
  connections, static caches) is yours to manage.

How all of this composes at run time — the plan-then-execute model, the
scenario weave, and what each failure does — is
[The execution lifecycle](lifecycle.md).

**Bringing your own reporters or observers** is not a shipped surface today,
and the honest current answers are: custom reporting = consume the run's
machine record (the EDN summary stream on stdout under `--edn`, or the full
record embedded in `report.html`); live in-run reaction = hooks, the one
sanctioned tap; bus observers have no user path. Three things are already
true and will bind any future mechanism: **the data contracts are locked
now** — the EDN summary/form-stream shape and the bus event envelope
(`:type`, timestamp, run-id, scenario id, payload) — so code written against
the data survives any attachment mechanism; **observers are truth-ordered
fire-and-forget** — they will never block a run, never touch an exit code,
and the shipped reporters stay deterministic, with no plugin protocol frozen;
and **the attachment grammar is reserved** — an in-process mechanism, if one
arrives, enters as the fourth species (a fixed-name sibling file or a
config-pointed path, deps via `sl/lib`), never inline in config. Deliberately
open, out loud: the file layout, whether reporters and observers are one
mechanism or two, and the timing — demand-driven, unscheduled.

## What the framework writes

**Run results — `results/<STAMP>/` under the config root** (`sl/results/`).
One directory per runner invocation, named by a UTC stamp
(`20260712T153042Z`; collision appends a run-id fragment). Inside: the HTML
report (`report.html`) at the group root, per-scenario
`attachments/<scenario-slug>/` directories holding captures and hook
attachments, and — only for explicit `setup.clj` groups — one subdirectory
per group. The results root **self-ignores**: a `results/.gitignore`
containing `*` is written at creation (the Maven `target/` pattern). Runs
without a real config write nothing.

**Configured report paths.** `--junit-xml PATH` and `--html PATH` resolve
against your shell's cwd; their config mirrors
(`[:runner :report :junit-xml]`, `[:runner :report :html]`) resolve against
the config root; the flag wins when both are present. The HTML report is
written twice when configured — the primary copy in the run dir, a byte-copy
at your configured path. In multi-group runs the configured path holds
whichever group's copy was written last (a later group's report overwrites
an earlier group's — even a PLANNING FAILED page); the run dir keeps every
group's report. JUnit-XML writes only the configured path.

**`.shiftlefter/` — the operational directory** at the project root. Ours,
not yours: state the framework and launcher need between runs.

| Path | What it holds | Written when |
|---|---|---|
| `wardrobe/<name>/` | costume browser profiles + `browser-meta.edn` | `sl costume init` / wear; **fixed location by design** — no config key, project-scoped, never home-dir |
| `daemon.edn` | warm-daemon coordinates (port, pid, jar identity) | daemon spawn; deleted on stop/idle-reap |
| `daemon.log` | daemon stdout/stderr, append-only (never rotated) | daemon spawn |
| `lib-sig` | the `sl/lib` fingerprint this daemon spawned with (mismatch → bounce) | daemon spawn |
| `spawn.lock` | spawn election lock (a directory, transient) | daemon spawn |
| `dirty-teardown.edn` | record of a failed group `:stop` — announced at the next run's start, then cleared (dry-run announces but keeps it) | on `:stop` throw |
| `sieve/` | SIEVE drafts and promoted fixtures | the SIEVE workflow |

(One dormant resident: `graph/` is reserved by internal plumbing not yet
wired to any command — you should never see it appear.)

**Git posture — one action to take:** add `.shiftlefter/` to your
`.gitignore`. The framework self-ignores only `results/` (always) and
`wardrobe/` (a line appended to your project `.gitignore` on first
`sl costume init`); everything else in `.shiftlefter/` relies on you having
the directory ignored.

## Environment variables

| Variable | Effect |
|---|---|
| `SL_NO_DAEMON=1` | run cold — no warm daemon — for the whole shell session (env twin of `--no-daemon`) |
| any `SHIFTLEFTER_*` set | forces the cold path: daemon env is frozen at spawn, and the wrapper won't pretend otherwise |
| `NO_COLOR` | disable ANSI colors (twin of `--no-color`) |
| `TWILIO_ACCOUNT_SID` / `TWILIO_AUTH_TOKEN` | Twilio adapter credentials when config omits them |

`SL_USER_CWD` is internal wrapper→JVM plumbing — not a knob, don't set it.
The dev repo's test gates (`SHIFTLEFTER_LIVE_WEBDRIVER` etc.) are not
consumer surface, but note they match the `SHIFTLEFTER_*` prefix rule: if
exported, they too force the cold path.

## Coming — annotated so this page stays honest

- `glossary/expectations.edn` — planned for 0.5.5.
- Use-case artifacts — the 0.6 contracts arc; they'll get a home under `sl/`.
- Macro registries move INI → typed EDN in 0.6 (hard cut; noted on the row).
- Reporters/observers as an attachment point — demand-driven, unscheduled;
  the invariants in the wall section above already bind whatever ships.

## Verification appendix

Every table row above, verified at write time (2026-08-04; cites re-pinned
2026-08-05) against the code this page is versioned with. `file:line` references are into this repo;
command entries are user-runnable demonstrations.

| Claim | Evidence |
|---|---|
| config discovery: `sl/shiftlefter.edn` only, git-bounded walk-up | `src/shiftlefter/project_context.clj:61-74` (single layout), `:41-59` (git boundary), `:181-197` (walk) |
| `-c FILE` escape | `src/shiftlefter/core.clj:54`; explicit branch `project_context.clj:165-180` |
| launcher/jar convention; installer target `sl/`, `--dir` override; jar glob | `release/install.sh:29,40,73-84`; `bin/sl:48-56` |
| features default `["features/"]`, config-root-relative; no fallback without real config | `src/shiftlefter/runner/config.clj:167,373-378`; `src/shiftlefter/runner/core.clj:568-600` |
| setup.clj owns the plan; bare `sl run` = full declared plan; CLI subset rule | `src/shiftlefter/runner/setup.clj:44-55` (pure-mode invariant), `:240-267` (`validate-cli-paths`); demonstrated: `sl run` / `sl run --dry-run` in `examples/04-sms-2fa` |
| plan-shape lints (dead group, duplicate label, shared-feature warning) | `src/shiftlefter/runner/setup.clj:377` (`lint-setups`); wired `src/shiftlefter/runner/core.clj:1320-1351` |
| glossary probes + explicit-wins-including-nil | `src/shiftlefter/runner/config.clj:246-273,325-332` |
| step-paths default `["steps/"]`; `--step-paths` cwd-anchored | `src/shiftlefter/runner/config.clj:166,368-371`; `src/shiftlefter/runner/core.clj:525-527` |
| setup.clj / hooks.clj sibling-of-config, fixed names | `src/shiftlefter/runner/setup.clj:133-149`; `src/shiftlefter/runner/hooks.clj:92-113` |
| macro registry paths: required-when-enabled; file-or-directory form | `src/shiftlefter/runner/config.clj:543-546,457-460`; `src/shiftlefter/stepengine/macros.clj:64-75` |
| `sl/lib/*.jar` on the classpath, cold and warm; lib-sig bounce | `bin/sl:564-578` (classpath), `:294-306,349-360,410-412` (lib-sig) |
| AGENTS.md stanza printed, never written | `release/install.sh:2-9,90-106`; stanza source `resources/shiftlefter/onramp/agents-breadcrumb.md` |
| machine config fixed at `~/.shiftlefter/config.edn` | `src/shiftlefter/config/user.clj:40-55` (no override path exists) |
| `shiftlefter.edn` is data-only (no code-bearing config keys) | `src/shiftlefter/runner/config.clj:466` (`known-top-level-keys`) — every species file cited above is the only code entry |
| results dir contract: stamp, per-group dirs, self-ignoring, defaults-run opt-out | `src/shiftlefter/runner/results.clj:1-33,55-60,71-83,85-112,127-142` |
| attachments per-scenario dirs + path portability | `src/shiftlefter/attachments.clj:10-21,194-214` |
| report path anchoring (flag = cwd, config = config-root, flag wins) | `src/shiftlefter/runner/core.clj:192-201,225-243`; `src/shiftlefter/runner/config.clj:68-75` |
| HTML dual-write; JUnit configured-path-only | `src/shiftlefter/runner/report/html.clj:239-280`; `src/shiftlefter/runner/report/junit.clj:438-447` |
| wardrobe fixed path, no config key, gitignore line on init | `src/shiftlefter/costume/wardrobe.clj:54-66,135-171`; absent from `known-top-level-keys` |
| daemon.edn / daemon.log / lib-sig / spawn.lock lifecycles | `src/shiftlefter/daemon.clj:348-357,239-260`; `bin/sl:393-425` |
| dirty-teardown.edn append/announce/clear (dry-run keeps) | `src/shiftlefter/runner/teardown_marker.clj:1-21,33-76`; `src/shiftlefter/runner/core.clj:1174-1195` |
| sieve store location | `src/shiftlefter/sieve/store.clj:9,20-33` |
| env vars incl. the `SHIFTLEFTER_*` prefix rule | `bin/sl:479-480`; `docs/daemon.md` § env; `src/shiftlefter/runner/report/console.clj:47-50`; `src/shiftlefter/adapters/twilio.clj:26-27` |
| EDN summary + form-stream contract (the locked data contract) | `src/shiftlefter/runner/report/edn.clj:7-46` |
| bus event envelope spec | `src/shiftlefter/runner/events.clj:54-67` |

Related pages: [Hooks](hooks.md) · [Costumes](COSTUMES.md) ·
[Install tiers](INSTALL-TIERS.md) · [Daemon](daemon.md) ·
[Extend the vocabulary](extending-vocabulary.md)
