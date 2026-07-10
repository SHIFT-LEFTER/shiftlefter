# The ShiftLefter daemon (warm execution path)

ShiftLefter runs as a CLI, but starting a fresh JVM for every `sl` call costs
~0.8s — almost all of it loading Clojure, not your features. For agents (and
humans) that shell out repeatedly, `bin/sl` keeps a small per-project **daemon**
warm in the background and routes calls to it. A warm call lands in **~50–80ms**
instead of ~800ms.

You don't have to do anything to get this — it's automatic, and it degrades
cleanly when it can't run. This page exists so that when you notice a `java`
process you didn't start, you have an answer.

## What spawns, and when

The **first** warm-eligible `sl` call in a project auto-spawns one daemon for
that project and waits (≤10s) for it to come up; you'll see one line on stderr:

```
ShiftLefter daemon started — sl daemon status
```

Every later call in that project dispatches to the running daemon and is silent
and fast. The daemon reaps itself after **60 minutes idle** (configurable:
`sl daemon serve --idle-timeout-min N`).

**Warm by default; a few commands stay cold** (they own a terminal, a child
process, or the full test suite):

- `repl` — interactive
- `costume init` — opens a login browser you must see
- `daemon serve` / `status` / `stop` — the controls themselves
- `verify --ci` — runs the whole test suite (kept out of the long-lived JVM)

Everything else (`run`, `fmt`, `gherkin fuzz`/`ddmin`, `verify`, `costume
list`/`destroy`, `--help`) is warm. A **new** core command is warm automatically —
nobody edits `bin/sl` to make it fast.

## Where state lives

Per project, under the instance root (the nearest ancestor with `shiftlefter.edn`
or `.git`):

```
.shiftlefter/
  daemon.edn    # port, pid, jar identity, java path, started-at, idle timeout
  daemon.log    # the daemon's own stdout/stderr (auto-spawn only)
  spawn.lock    # transient; prevents two callers racing to spawn
```

`.shiftlefter/` is **gitignored** — it's per-checkout runtime state, like
`.nrepl-port`. If no `shiftlefter.edn` and no `.git` is found above your cwd,
`sl` runs **cold** and spawns nothing (it won't litter daemons across an
unconfigured tree).

**One daemon per project, and per worktree.** A linked git worktree has its own
`.git` file and its own built jar, so it anchors to itself and gets its own
daemon — a worktree on another branch never serves you its stale framework code.

The daemon and your **dev REPL coexist**: the daemon writes `daemon.edn`, never
`.nrepl-port`.

## Staleness is automatic

The daemon serves exactly one built jar. When you rebuild
(`clojure -T:build uberjar`), the next `sl` call notices (jar size / mtime), stops
the old daemon, and spawns a fresh one — transparently. You never get stale
framework code from a warm call. A JDK upgrade bounces it the same way.

## Escapes

- `sl --no-daemon <cmd>` — run this one call cold.
- `SL_NO_DAEMON=1` — run cold for the whole shell session (never spawn).
- `sl daemon stop` — stop this project's daemon now (graceful, then signal).
- `sl daemon status` — show the daemon's port/pid/jar/idle-timeout and whether
  it's alive.

Any `SHIFTLEFTER_*` environment variable set in your shell also forces cold for
that call — the daemon's environment was frozen when it spawned, so it can't see
a variable you exported afterward, and we'd rather run cold than lie.

## Requirements & fallbacks

The warm path needs only the jar and a JRE — the dispatch client
(`shiftlefter.client.NreplClient`) ships **inside the jar**. There is nothing
extra to install. If the daemon can't be reached for any reason, `sl` silently
falls back to a cold run (with an AppCDS archive, ~0.4–0.6s), so a call never
fails just because the warm path was unavailable.

## Output note (current limitation)

Warm output is **captured and delivered when the command finishes**, not streamed
live. A cold `sl run` prints each scenario's result as it finishes; a warm run
prints the same lines, in the same order, but all at once at the end. You'd also
notice on a long `gherkin fuzz`, whose per-trial progress appears at the end
warm. Live streaming is planned;
it's blocked on a bug in the bundled nREPL's output stream, not on the daemon
design.

## Internals

`bin/sl` ↔ daemon is a **version-internal** protocol: the wrapper and the jar
ship together and are version-matched, so `shiftlefter.daemon/dispatch!`, the
`daemon.edn` format, and the client are free to change in any release. Don't build
on them. The stable surface is the four `sl daemon …` behaviors, `--no-daemon`,
and `SL_NO_DAEMON`.
