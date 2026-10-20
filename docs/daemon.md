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

## One run at a time

The daemon executes **one invocation at a time per project**. A second `sl`
call while one is in flight is **rejected immediately** — exit 2 with the
structured error `:daemon/busy` and one stderr line:

```
sl: another run is in progress for this project; retry when it completes
```

Under `--edn`, the rejection is the standard exit-2 planning summary
(`:run/status :planning-failed`, the `:daemon/busy` error in the `:planning`
block) on stdout. Retry when the in-flight run completes; nothing queues. The
reject applies to every warm command, not just `run` — but `sl daemon status`
and `sl daemon stop` are handled by the wrapper without dispatching, so a busy
daemon can always be inspected or stopped.

## Where state lives

Per project, under the instance root (the nearest ancestor with
`sl/shiftlefter.edn` or `.git`):

```
.shiftlefter/
  daemon.edn    # port, pid, jar identity, java path, started-at, idle timeout
  daemon.log    # the daemon's own stdout/stderr (auto-spawn only): 0 bytes = healthy;
                # a crash trace, or — rarely, a minor sign of ill health — one
                # `reaped orphaned session <id>` line per client that died
                # mid-run without closing its session (a second Ctrl-C, a
                # killed process, a cancelled CI job)
  spawn.lock    # transient; prevents two callers racing to spawn
```

`.shiftlefter/` is **gitignored** — it's per-checkout runtime state, like
`.nrepl-port`. If no `sl/shiftlefter.edn` and no `.git` is found above your cwd,
`sl` runs **cold** and spawns nothing (it won't litter daemons across an
unconfigured tree).

**One daemon per project, and per worktree.** A linked git worktree has its own
`.git` file and its own built jar, so it anchors to itself and gets its own
daemon — a worktree on another branch never serves you its stale framework code.

The daemon and your **dev REPL coexist**: the daemon writes `daemon.edn`, never
`.nrepl-port`.

**Costume custody:** a warm run's worn costume stays alive inside the daemon
between invocations — costume persistence working, same design as the dev
REPL JVM (see COSTUMES.md § Custody). `sl daemon status` lists the live
costumes and their drivers; `sl daemon stop` releases them.

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

`sl doctor` also includes a daemon-health probe (`daemon.edn` vs live pid,
plus jar/lib-sig staleness) as part of its machine pre-flight. It complements
rather than replaces `sl daemon status`: status is wrapper-owned bash (<5ms,
no JVM, always prints the file), while doctor's probe runs in the jar,
deliberately routed cold so it observes the daemon from outside, and folds
the answer into doctor's healthy/issues verdict (sl-afsp).

## Aborting a run

A run can be aborted without stopping the daemon (sl-617t). The contract
is the same cold and warm: **an aborted run is exit 4** with
`:halted {:reason :aborted :after-scenario N}` in every report, never
130, never 0, never 3.

- **Ctrl-C aborts the run.** The client traps it and sends the daemon an
  abort. The instant the trap fires your terminal prints one line —
  `aborting: N scenario(s) finishing their current step, then Afters,
  cleanup, :stop; reports will land at <dir>` — and then **waits**. Inside
  the daemon no not-yet-started scenario starts; a scenario already running
  stops at its **next step boundary** (a step is never interrupted, and no
  nREPL interrupt is involved); every After hook, capability cleanup and
  `setup.clj` `:stop` runs; every report is written. The scenarios that ran
  keep their verdicts, the rest are **held** (`HELD (aborted)` on the
  console, `<skipped message="held: aborted …">` in JUnit, `:held {:reason
  :aborted}` in EDN and the HTML page). When the wind-down is done the
  client prints the verdict and exits 4. The daemon survives — `sl daemon
  status` still shows it, the next run is warm.
- **`sl daemon abort`** does the same from a second terminal or a CI
  wrapper: it asks the daemon to abort the run in flight, prints the same
  immediate line (naming the results directory), and returns as soon as the
  request is accepted — exit 0; exit 1 when no run is in flight, no daemon
  is running, or it is unreachable. The attached client is the one that
  waits and prints the verdict.
- **A second Ctrl-C detaches.** The client prints `sl: detached; the daemon
  is finishing the unwind; reports will land at <dir>` and exits **130**;
  the daemon finishes the unwind on its own — teardowns and reports still
  land. An immediate retry gets the `:daemon/busy` rejection until it has.
- **If the abort cannot be delivered** (the daemon unreachable, the abort
  op failed) the client prints the honest hint — the run continues in the
  daemon; `sl daemon status` shows it, `sl daemon stop` aborts it and takes
  the whole daemon — and exits **130**. This is the only path on which a
  run keeps executing invisibly after Ctrl-C, and it is the pre-sl-617t
  behavior: nREPL sessions persist across a dead transport and output
  writes to the dead session are swallowed rather than aborting the eval,
  so the run completes server-side and its end-of-run artifacts are still
  written. The abandoned nREPL session is reaped daemon-side by a periodic
  orphan sweep (sl-ntlx) — within ~10 minutes of its run completing, never
  while the run executes. Each reap writes one `sl daemon: reaped orphaned
  session <id>` line to `daemon.log`. Any other nREPL session on the daemon
  port — a hand-connected REPL included — is reaped the same way once it
  has sat through two idle sweep ticks, mid-eval or not: the port is the
  warm client's, not a REPL host.
- **130 is the platform's "interrupted" status**, outside the exit-code
  table by design: it is produced only when the process was interrupted
  before it could reach a verdict — the two warm cases above, and a cold
  Ctrl-C before execution began. Everything that reaches a verdict is in
  the table (the [exit-codes page](architecture/exit-codes.md)): an abort
  is 4, an abandoned unwind is 3.
- **Cold runs (`--no-daemon`)** abort the same way: one Ctrl-C prints the
  immediate line, winds down, writes the reports, exits 4. A **second
  Ctrl-C during the unwind takes the crash row** — exit 3, the `sl-crash`
  markers in the multi-group report surfaces — you accepted the mess and
  the report says so. Once the final report writes have begun a second
  Ctrl-C is a no-op (one line; the exit-4 verdict lands moments later).
- **`sl daemon stop`** still exists and still takes the whole daemon: the
  graceful half waits for the in-flight run to finish; the wrapper then
  escalates SIGTERM → SIGKILL, which does not wait. A killed run keeps its
  run directory with whatever evidence was captured (nothing auto-cleans
  it) but gets no reports and no `:stop` teardowns. Prefer `sl daemon
  abort`.

## CI

Prefer the **cold path** in CI (`SL_NO_DAEMON=1`, or `--no-daemon` per
call) — one-run pipelines gain almost nothing from warmth and cold leaves
zero background residue; the daemon's constituency is the development
loop. The reasoning and the one measured exception live in docs/CI.md
§ Warm vs. cold.

## Requirements & fallbacks

The warm path needs only the jar and a JRE — the dispatch client
(`shiftlefter.client.NreplClient`) ships **inside the jar**. There is nothing
extra to install. If the daemon can't be reached for any reason, `sl` silently
falls back to a cold run (with an AppCDS archive, ~0.4–0.6s; projects with
`sl/lib/` jars run cold without the archive, so their fallback is somewhat
slower), so a call never fails just because the warm path was unavailable.

## Output

Warm output **streams live**, exactly like cold: `sl run` prints each
scenario's result as it finishes, and `gherkin fuzz -v` reports each failure
as it is saved (passing trials print nothing — fuzz's mid-run output is
failures-only, and only under `-v`). (Historical: through 0.5.4 warm output
was captured and delivered all at once at command end, a workaround for a
multibyte crash in the bundled nREPL 1.5.0's output stream; nREPL 1.7.0
fixed it and sl-qwf restored streaming in 0.5.5.)

One cost of live streaming, recorded as such (sl-7c7i): output is no
longer delivered atomically at command end, so a client killed mid-stream
can leave a truncated `--edn` form on a consumer's stdout, and the client
can exit 3 on a transport failure even though the daemon-side run
completed. Machine consumers should treat a form that doesn't `read` as
exactly what it is — an interrupted invocation, not a verdict.

## Internals

`bin/sl` ↔ daemon is a **version-internal** protocol: the wrapper and the jar
ship together and are version-matched, so `shiftlefter.daemon/dispatch!`, the
`daemon.edn` format, and the client are free to change in any release. Don't build
on them. The match is enforced mechanically for wrapper-routed calls: the
wrapper bounces any daemon whose recorded jar size differs from the jar it is
about to load the client from, or whose breadcrumb predates a jar rebuild. A
daemon reached outside that path (or somehow surviving a jar swap) may garble;
the remedy is `sl daemon stop`. The stable surface is the four `sl daemon …`
behaviors, `--no-daemon`, and `SL_NO_DAEMON`.
