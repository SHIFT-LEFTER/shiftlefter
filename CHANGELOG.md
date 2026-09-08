# Changelog: 0.5.6

**Release Date:** 2026-09-08

The theme of this release is **never lie after the run** — the other half
of 0.5.5's line. A step or adapter can now say *no answer existed*, and the
run reports exactly that (exit 4, `unobserved`) instead of indicting the
app; a poll timeout carries what it saw; a dry run names what it did not
write; every config shape the docs advertise reaches the advertised
planning error instead of a crash or a silent downgrade; and the
architecture pages are generated from the code's own extraction, so the
map is regenerated, not remembered.

## Stats

```
2491 tests, 9676 assertions, 0 failures (./bin/kaocha unit — the fast suite)
27 tests, 139 assertions, 0 failures (./bin/kaocha daemon — warm-path shell E2E through the built jar)
13 tests, 55 assertions, 0 failures (SHIFTLEFTER_LIVE_WEBDRIVER=1 ./bin/kaocha e2e — live-browser example validators)
Cucumber compliance: 46/46 good, 11/11 bad (100%)
Verified against Chrome 152.0.7977.76 / ChromeDriver 152.0.7977.82
```

## Highlights

- **A dead service is exit 4, not "the app is wrong."** A step may tag
  the exception it throws with `:error/class` (the built-in SMS and browser
  families do); `:observation` means no answer existed, counts the scenario
  `unobserved`, and a server that accepts and never answers is classified
  at the navigation site.
- **Poll timeouts carry the observation.** `:until` is the await idiom;
  the timeout record says what the predicate last saw, what it last threw,
  and how many attempts answered.
- **Halt the run when it goes dark.** `:halt-on-observation-failure` is
  `false` | `:group` | `:run` — stop starting scenarios, never kill one in
  flight.
- **Config shapes reach the advertised error.** `:interfaces` replaces the
  builtins wholesale; `:verbs` is a path list and each glossary file
  declares its own `:type`; a nil or non-map `:interfaces`, a retired
  `:verbs` map, or a typo'd `:svo` level is a planning error, never a
  crash and never a quiet downgrade.
- **The multi-group machine record joins by identity.** Every per-group
  `--edn` summary carries `:group/label` and a declared `:group/index`;
  one invocation aggregate carries the verdict and the run directory.
- **The architecture pages are generated projections** — six of them now,
  the exit-codes ladder included, drift-guarded so a hand edit fails the
  suite. And `AGENTS.md` is yours: `sl init` offers, and writes only on
  `--write-agents`.

<!-- no-entry: sl-1ru0 (release prep, steps 6+7 pre-tag: version bumps, dated header, Stats block, pin sweep, post-bump codox regen and runs; process record, no shipped behavior) -->
<!-- no-entry: sl-jaoq (Warden M1: ChromeDriver 152 lockstep on the dev machine + the CLAUDE.md pointer; machine state, no shipped surface) -->
<!-- no-entry: sl-r1bv (release-tail step 4 mechanical: site-sync doc set +exit-codes +index, codox regen, CI.md install recipes pinned to the v0.5.6 tag; no shipped behavior) -->
<!-- no-entry: sl-g1y8 (the v0.5.6 release gate bead — process record; the release itself is this section) -->
<!-- no-entry: sl-q1q6 (docs + example honesty + a costume test — the class is read from step throws only; example 07's inert capture-fn tag removed; no shipped behavior change) -->
<!-- no-entry: sl-nm9o (docs, canon rewords, and guards — project-layout pin guard, derived-page wordlist, explicit public-export skips, F15b into the packaged authoring topic; no shipped behavior) -->
<!-- no-entry: sl-6vz8 (tests only: crash-under-edn aggregate absence, the four self-ignore creation sites + the bin/sl twin, install.sh's interactive consent arm; no shipped surface) -->
<!-- no-entry: sl-gq9n (instrument run bead retired unrun — folded into the f04q arm menu; board state only, no shipped surface) -->
<!-- no-entry: sl-f04q (instrument protocol + wiki corpora; no shipped surface) -->
<!-- no-entry: sl-xwwv (test-only: pins the exit-code tournament; no shipped surface) -->
<!-- no-entry: sl-czs5 (dev-repo review tooling — the mo registration hook; no shipped surface) -->
<!-- no-entry: sl-f9y6 (instrument run — f04q Arm A regrade; notes + wiki archive only, no shipped surface) -->
<!-- no-entry: sl-wi69 (dev-repo instrument tooling — the scripts/coldread/ cold-read runner; no shipped surface) -->
<!-- no-entry: sl-6hz4 (instrument run — f04q Arm B dual-model inversion + Stage 3 grading; notes/coldread-runs + wiki archive only, no shipped surface) -->
<!-- no-entry: sl-wti5 (the v0.5.5 release gate bead — process record; the release itself is the section below) -->
<!-- no-entry: sl-hz0x (site-repo artifact refresh — /report samples regenerated through the tagged v0.5.5 jar + the five human-doc pages re-synced; the SL-side change is the dev-only sync script's slug-subset filter, no shipped SL surface) -->

## Features

<!-- entry: sl-kowi -->
- **The step/adapter error-class contract: `:error/class`, `unobserved`
  counting, and `:halt-on-observation-failure`.** A dead service mid-run
  used to report exit 1 "scenario failed" — evidence conflated with claim.
  Now a step or adapter may tag the exception it throws with one recognized
  ex-data key, `:error/class` (`:assertion` — the app answered and missed;
  `:sut` — the app answered *with* an error; `:observation` — no answer
  existed). `:observation` flips the scenario to `:error` and counts it
  `unobserved` — a bucket disjoint from the harness's `error` — so the run
  exits 4 (degraded) instead of indicting the app; `:sut`/`:assertion` are
  recorded and rendered (`FAILED (SUT error)`, a `class:` line under the
  failure, the key in every envelope, a JUnit message prefix) and change
  nothing else. Absent key = unchanged behavior; an error-free run's counts
  are byte-identical. The built-in SMS family ships classified (a failed
  provider query is `:observation`; a message that never arrives through a
  healthy provider stays product evidence), as does the browser family (a
  WebDriver transport throw, `:etaoin/http-ex`, is `:observation`, and so is
  a navigation the network refused — `opens the browser to` a dead port or
  unresolvable host throws `SUT unreachable: ERR_CONNECTION_REFUSED for
  <url>` from a closed set of Chromium `net::ERR_*` codes; every other
  driver *answer* — element-not-found included — stays exit 1). New
  run-level config `{:runner {:halt-on-observation-failure :run}}`
  (`:group` halts one setup group; `true` reads as `:run`; default `false`;
  no flag): after a scenario goes dark, no further scenario starts —
  in-flight scenarios under `--max-parallel` finish and keep their verdicts,
  everything unstarted (including the `@serial` tail) is `skipped`, the
  summary says `halted after scenario N (<name>): observation failure` and
  the `--edn` summary carries `:halted`. Exit is 4 either way. The exit-code
  table is unchanged; its prose now states the ladder — 0/1 are verdicts
  about the app, 2/3/4 are three ways the run failed to be a measurement —
  and a new exit-codes ladder page (`docs/architecture/exit-codes.md`;
  skeleton first, prose pass below) joins the exit-contract drift guard.

<!-- entry: sl-afs9 -->
- **Poll timeouts carry the last observation: `:until` is the canonical
  await idiom.** The taught polling idiom returned nil to keep going, so
  every timeout record's `:last-value` was structurally empty — the
  observed state (a job at 85%, the three messages that DID arrive) was
  fetched and discarded. The kernel always had the channel; the idioms on
  top of it were the gap. Now: the docs teach observe-and-judge first (the
  predicate returns the observation, `:until` judges it) and name both
  timeout channels — `:last-value` (the predicate returned) and
  `:last-error` (a `:retry-error?` not-yet) — with the nil form demoted to
  a footnote; a nil-idiom timeout carries `:hint "pred returned nil to
  continue; return the observation and pass :until to populate
  :last-value"`, so the empty record teaches the fix; the built-in SMS
  receive family models the idiom (a receive timeout's `:last-value` now
  says `{:seen 2 :bodies [...]}` instead of nil) and its polling wrapper
  forwards caller opts so `:until` reaches the kernel from any layer. Also
  (Rider C, the error-class seam in the kernel): a poll timeout inherits
  `:error/class :observation` when the witness never answered — no attempt
  returned a non-nil value and some attempt threw an observation-class
  error — leaving the scenario `unobserved` (exit 4); any answer at any
  attempt keeps the timeout an ordinary failure (the precise precedence
  rule and the `:poll-counts` digest are in the Fixes entry that settled
  it). No `:observe` option; the kernel's shape is unchanged otherwise.

<!-- entry: sl-od2v -->
- **A server that accepts and never answers is now exit 4, and the
  browser adapter takes a page-load timeout.** The navigation step's
  classification read only Chromium `net::ERR_*` codes, so a page-load
  timeout — the driver's `timeout` answer to a silent server — fell
  through as an unclassified failure, exit 1: the product indicted for
  silence. That answer is now classified `:observation` at the same single
  navigation site (`SUT silent: navigation timed out for <url>`), and
  `ERR_TIMED_OUT` / `ERR_EMPTY_RESPONSE` join the unreachable set. Because
  Chrome's default page-load timeout is five minutes, the etaoin adapter
  gains `:config {:page-load-timeout-ms N}` on the interface entry (absent
  = the driver's default), so that exit 4 arrives in seconds. Known hole,
  recorded on the ladder page: a server that accepts and closes without a
  byte may make Chrome return its own error page, and the scenario then
  fails at the first element lookup as `:assertion` — or, depending on the
  Chrome build, yield `ERR_CONNECTION_RESET`, which the closed set
  classifies.

<!-- entry: sl-pwbp -->
<!-- entry: sl-kvn6 -->
- **Multi-group `--edn` streams gained group identity and a suite-verdict
  form; the console shows per-group exit codes.** A machine consumer of a
  multi-group run had to join summary forms to groups by stream order
  alone, and the suite verdict lived only in the process exit. Now each
  per-group summary form carries `:group/label` + `:group/index` (the
  group's declared position — the durable join key), and after all groups
  ONE invocation aggregate form carries the verdict:
  `{:invocation/exit-code N :invocation/status kw :invocation/groups
  [{:group/label … :group/index … :run/exit-code … :run/status …} …]}` —
  deliberately not `:run/exit-code`, so the documented summary
  discriminator never matches it. Absent-unless-multi: single-group and
  classic streams are byte-identical to before; absent on a crash and on
  planning failures that precede the group loop. The console gains one
  stderr verdict line per group in multi-group runs — `Setup group
  "alpha": exit code 0 (passed)` — including planning-failed groups, which
  previously rendered their numeric code in the HTML TOC alone.

<!-- entry: sl-alha -->
- **The multi-group `--edn` aggregate names the invocation's run
  directory, and the HTML report's `UNOBSERVED` vocabulary is documented.**
  The aggregate form gains the additive `:invocation/results-path`
  (config-root-relative `results/<stamp>`; group rows keep their own
  `:run/results-path`), so a consumer reads the invocation dir instead of
  stripping group slugs. `docs/REPORTS.md` now describes the report's
  UNOBSERVED status word, its `st-unobserved` modifier, the `N unobserved`
  counts row and the halt line, which shipped with the exit-4 contract.

<!-- entry: sl-zzrx -->
- **The exit-codes ladder page joins the derived architecture family, and
  the family gains a front door.** The ladder page now lives at
  `docs/architecture/exit-codes.md` as a generated projection of a
  canonical source, drift-guarded like the five architecture maps — a
  hand edit to the published page fails the suite, and the exit-code
  table on it stays pinned to the canonical table in the code by the
  same guard that covers every other surface stating the contract. A new
  `docs/architecture/index.md` lists all six pages and explains how a
  page earns its place: mechanical derivation from a canonical source,
  regeneration scripted, drift a test failure — the pages are
  regenerated, not remembered. The old `docs/EXIT-CODES.md` path is
  retired; every reference now points at the new location.

<!-- entry: sl-r54o -->
- **The exit-codes ladder page has its prose, and `sl agent-doc
  exit-codes` is new.** The ladder page shipped as a skeleton; the reasoning now rides
  along — why 4 trumps 1, what exit 2 does and doesn't prove at group and
  suite scope, the witness razor and its give-backs, exit-4 triage, and
  how to classify your own adapters. A packaged `exit-codes` agent-doc
  topic distills it to the bare decision procedure (also inlined in
  docs/AGENT.md), and its table joins the exit-contract drift guard as
  the eighth surface.

<!-- entry: sl-9uuv -->
- **The architecture pages become generated projections.** The five
  `docs/architecture/` maps (module map, invocation map, control loop,
  data-shape ledger, REPL lifetimes) are now GENERATED — mechanical
  projections of canonical internal sources, produced by
  `scripts/derive-arch-docs.bb` and drift-guarded like `docs/AGENT.md`
  (a hand edit fails the suite). The public pages keep the architecture —
  diagrams, tables, file:line pins, extraction dates — and drop the
  internal working apparatus (findings ledgers, issue-tracker references,
  process vocabulary); each ends with a standard note on how the map
  stays true. Spine anchors are now release-line eras and full-ISO dates
  rather than development-repo SHAs, so every reference on a public page
  resolves for a public reader. This lands the change the architecture
  docs needed to do their public job — adoption-facing credibility:
  the project knows its own shape, and the map is regenerated, not
  remembered.

<!-- entry: sl-tx33 -->
- **First-publish read of the derived architecture pages: seven
  unresolvable-reference sites fixed at the source.** A cold read of the
  architecture pages as first published found seven places where a
  public reader hit a reference that does not resolve outside the
  development repo. Fixed in the canonical `notes/architecture/` sources
  and re-derived, so the public `docs/architecture/` projections carry
  only references a public reader can follow.

<!-- entry: sl-zxt5 -->
- **The architecture maps' extracted counts are now freshness-guarded.**
  The module map's require-edge count and the invocation map's
  dispatch-branch count are stamped in the pages as machine-readable
  phrases, and the test suite re-runs the extraction behind each stamp
  every run — a stale stamp fails naming the stamp to update and the
  command to re-run. The module-map extraction now reads each file's `ns`
  form as data and counts real `(:require …)` edges only (316 at this
  stamp), retiring the earlier token grep whose 525 rows included every
  file's own namespace line and prose mentions in docstrings; a docstring
  edit cannot move the guarded number. The extraction is runnable from a
  checkout (`shiftlefter.arch-doc.freshness`; the module map's § Method
  carries the one-liner). Alongside, the release process gains a standing,
  non-waivable docs pass: every release includes an audience-fitness read
  of anything that became public or changed audience since the previous
  tag — including the derived architecture pages' diff — narrowable by an
  explicit recorded ruling, never skipped whole.

<!-- entry: sl-7dq5 -->
- **Five smalls from the first real-world drive.** (1) AGENTS.md
  is yours: `sl init` no longer writes it by default — it prints the offer,
  and `sl init --write-agents` performs the marker-guarded write on an
  explicit ask (it also works after first init: the one narrow exception to
  the coarse bail). `install.sh` gains tri-state consent — `--write-agents` /
  `--no-write-agents` pre-answer, an interactive install prompts once, a
  non-interactive install defaults to no-write and prints the stanza instead.
  `--no-breadcrumb` is removed (it suppressed only the print, never the
  write). (2) `sl run --dry-run` with `--html`/`--junit-xml` — the flag or
  the `[:runner :report …]` config mirror — used to print a success line and
  silently write no file; the missing reports are now noticed, not written:
  the dry run proceeds, one stderr line names the reports it did not write,
  and the `--edn` summary carries `:run/reports-not-written`. (3)
  `.shiftlefter/` self-ignores at every creation site (wrapper and runtime),
  so a hand-copied jar's first run leaves `git status` clean — no longer
  only when the installer ran `sl init`. (4) The `--edn` summary carries
  `:run/results-path` (the group run dir, config-root-relative): the pointer
  from envelope to artifacts, replacing mtime guessing; absent when no
  results dir exists, so existing consumers and goldens are unaffected. (5)
  Adapter-authoring docs teach log-attempt-then-outcome, so a
  refused/timed-out request still appears in the `:http-log`-style evidence
  capture. (Also diagnosed, no code change: `daemon.log` is the daemon JVM's
  crash sink — 0 bytes means healthy.)

## Breaking Changes

<!-- entry: sl-67dj -->
- **Verb glossary `:type` is now file-authoritative; config `:verbs` becomes
  a path list.** A verb glossary file's `:type` key is REQUIRED and is the
  mount key the loader files it under — a file without one is a planning
  error (`:glossary/missing-type`, exit 2). Config's `:glossaries :verbs`
  stops restating the type: the type-keyed map (`:verbs {:web "path"}`)
  becomes a plain list of paths (`:verbs ["path"]`), with no transition shim
  — the old map form is a planning error naming the list form (see the
  config-shapes fix below). Conventional discovery now picks up every
  `glossary/verbs/*.edn` instead of probing a closed name set, and type
  identifiers are an open namespaced space — a bundle's `:github/web`
  mounts without colliding with anyone's `:web`. The framework's own
  default loader is cleaned the same way (each shipped file mounts under
  its declared type), `sl glossary` provenance now reports actual mounts,
  and the `:glossary/orphan-type` lint reads the loaded glossary's declared
  types. Glossary-load warnings (e.g. the dead `:override-defaults` flag)
  now also reach the structured channels — `:warn` diagnostics in `sl
  orient`/`--edn` and `:glossary-lints` in the runner's EDN envelope —
  instead of stderr logging only (a finding of the 0.5.5 release audit).
  **Migration:** rewrite `:verbs {:web "glossary/verbs/web.edn"}` as
  `:verbs ["glossary/verbs/web.edn"]` and add `:type :web` to the file —
  or drop the key and let discovery find `glossary/verbs/*.edn`.

<!-- entry: sl-g2lj -->
- **A user `:interfaces` map now replaces the builtin defaults wholesale.**
  The change 0.5.5's provisional-semantics note announced. No `:interfaces`
  key = the builtin defaults, unchanged (today: the `:web`/`:etaoin`
  instance). `:interfaces` present = your map is the COMPLETE set — a
  browser-less project finally has zero phantom interfaces and `sl orient`
  says so. Per entry: the value `:default` includes
  the shipped builtin def of that name VERBATIM (no overrides through the
  sentinel), and a full map is used as-is under any name — including `:web`,
  which then fully shadows the builtin; a user map and a builtin def are
  never merged, and a full map must declare its `:type` (never inferred from
  the name). New planning-class config errors: `:default` under a name with
  no builtin (`:config/unknown-default-interface`), a present-but-non-map
  `:interfaces` value (`:config/invalid-interfaces`, previously silently
  ignored), and — reserving room for future builtin aliasing — `sl/`-
  namespaced interface names or types (`:config/reserved-interface-name`,
  `:config/reserved-interface-type`). `:interfaces nil` is explicit zero.
  The `sl init` scaffold now writes `:interfaces {:web :default}`, and the
  config reference (docs/SVO.md § `:interfaces`) carries the full semantics
  table. The closed interface TYPE set is untouched (opening it is the
  bundle design bead). **Migration:** an `:interfaces` map that only ADDED
  an interface beside the builtin `:web` — an SMS-only project's `:sms`
  entry, say — now loses `:web`; add `:web :default` to keep it.

- **Two removals on the install/init surface, and one moved page.**
  `sl init` no longer writes `AGENTS.md` by default, and `install.sh
  --no-breadcrumb` is gone: pass `--write-agents` (to either tool) to get
  the file, `--no-write-agents` to say no in CI — the five-smalls entry
  above carries the whole consent story. The exit-codes ladder page moved
  from `docs/EXIT-CODES.md` to `docs/architecture/exit-codes.md`; update
  bookmarks and links. **Behavior changes that are not breaking:**
  `:halt-on-observation-failure true` still means `:run` under the new
  tri-state, and `sl run --dry-run` with `--html`/`--junit-xml` now
  proceeds and names what it did not write instead of being refused — both
  described in their entries.

## Fixes

<!-- entry: sl-78u4 -->
- **Three shipped contract claims made true: the halt policy is a
  tri-state that really halts the run, the poll rider decides on whether
  the witness ever answered, and `daemon.log` stays empty on healthy warm
  runs.** `:halt-on-observation-failure` now takes `false` | `:group` |
  `:run` (`true` reads as `:run`, so nothing written against the earlier
  entry changes meaning): under `:run` a `setup.clj` group that goes dark
  halts the whole invocation — groups not yet started are skipped outright,
  their `:start` never runs, their `--edn` aggregate rows say `:run/status
  :skipped` with no `:run/exit-code`, and the aggregate carries the
  `:halted` record (with `:group/label`); `:group` halts only the group
  that went dark. The HTML report and the invocation TOC now show the halt
  as one header line. A poll timeout's `:error/class :observation` is
  inherited only when no attempt ever returned a non-nil value and some
  attempt threw a dark error — any answer at any attempt is the witness
  talking, exit 1 — and the record carries `:last-value` and `:last-error`
  side by side plus a `:poll-counts` digest `{:attempts :answered :threw
  :observation-throws}`. Internally, the warm-path dispatch client now
  reads the session-close acknowledgement before hanging up, so nREPL no
  longer logs a "Socket closed" handler trace into the daemon's crash sink
  on every healthy warm run; a daemon-suite guard pins it at 0 bytes.

<!-- entry: sl-a7sx -->
- **Config shapes this release's entries advertise now reach the
  advertised planning error — and a dry run tells the truth about
  reports.** The release audit found one root under five findings: a
  shape the entries above describe reached a crash (exit 3) or a lie
  instead of the promised exit 2. Now `:interfaces nil` (explicitly zero
  interfaces) is normalized to `{}` at load, so a browser suite fails
  suite-lint under `--dry-run` exactly like `{}` does instead of passing
  green and exiting 1 live; a present-but-non-map `:interfaces` (`[:web]`,
  `"web"`, `:default`) reports `:config/invalid-interfaces` on both `sl run`
  and `sl orient` instead of crashing; and the retired type-keyed
  `:glossaries {:verbs {:web "…"}}` map (or any non-list `:verbs`) reports
  `:config/invalid-glossaries` naming the path-list form, while a list
  literal loads like a vector. **Behavior change:** `sl run --dry-run` with
  `--html`/`--junit-xml` is no longer refused at invocation, as it briefly
  was inside this release's span — flag and the `[:runner :report …]`
  config mirror that `sl init` scaffolds now behave the same: the dry run
  proceeds, exit 0, one stderr line names what was not written, and the
  `--edn` summary carries the additive `:run/reports-not-written` vector of
  kinds. The multi-group `--dry-run --edn` invocation aggregate is emitted
  with `:invocation/status :dry-run` rather than claiming `:passed`.

<!-- entry: sl-baup -->
- **`:group/index` is the group's declared position, an index-name that
  survives CLI narrowing.** It was assigned over the groups that survived
  path narrowing, so `sl run features/bravo.feature --edn` against three
  declared groups reported bravo as index 0 and two invocations with
  different narrowing joined by index to different groups — the documented
  "durable join key" joined wrong. The index is now stamped from
  `setup.clj`'s declared order before narrowing drops anything and carried
  to every surface (per-group summaries, dry-run summaries, the invocation
  aggregate rows, the HTML TOC): under narrowing the numbers keep their
  declared values and may have gaps (`alpha` + `charlie` of three ⇒ `0`
  and `2`). Unnarrowed runs are unchanged.

<!-- entry: sl-k1pv -->
- **The no-setup.clj pipeline now runs the exact bound-adapter registry
  check.** A config adapter typo (or any bound scenario whose interface's
  adapter is absent from the registry) on a project without `setup.clj` is
  now a plan-time planning error (exit 2, `:setup/bound-adapter-missing`) —
  it previously survived planning and died at first provisioning with
  `:adapter/unknown` (exit 4). Same check, same error key, same
  selection-exactness as the setup pipeline's check: an adapter no
  selected, bound scenario touches stays silent. One divergence, by design:
  on this pipeline the check also fires under `--dry-run` — with no `:start`
  the registry is fully knowable at plan time, and a PLAN-verdict exit 0
  must mean a runnable plan (the setup pipeline's dry-run keeps deferring,
  since only its live `:start` builds the registry). Housekeeping riding
  the same pass: `lossy-override-warnings` loses its caller-less
  `:setup/default-adapter-removed` removal arm (registry membership belongs
  to the exact check on both pipelines now) and drops to 2 arguments.

<!-- entry: sl-3bqv -->
- **Two framework-owned closed sets now error instead of ignoring.** An
  `:svo` enforcement level outside `:warn` / `:error` / `:off` (a typo like
  `:warm`) was accepted at load and then treated as non-blocking — the
  strict setting you wrote silently became a warning. It is now a
  planning-time config error (`:config/invalid-svo-level`) with a
  did-you-mean over the enum, on `sl run` and `sl orient` alike
  (`:unknown-object` still accepts the deprecated `:strict` alias). An
  unknown key in a `hooks.clj` entry was stripped without a word; it is
  now a hooks error naming the key and the accepted keys.

<!-- entry: sl-q4cb -->
- **Two config edges stop misbehaving quietly.** A string-keyed `:timing`
  map (`{"deadline-ms" 5}`) crashed the loader inside the did-you-mean
  suggester; it now reaches the timing planning error naming the key. A
  `:default` key written INSIDE a full interface map
  (`{:web {:default true :type :web …}}`) was silently ignored; config lint
  now warns that `:default` is an entry value, not a key, and that a full
  map is never merged with the builtin.

<!-- entry: sl-vjve -->
- **The daemon's orphan-session sweep no longer competes with your run for
  the dispatch lock, and every reap is named.** A sweep tick used to
  try-acquire the same lock a warm dispatch try-acquires, so an invocation
  landing on a tick was rejected with "another run is in progress" when no
  run was in progress (a spurious reject in 3000 back-to-back dispatches
  under a 1 ms sweep). The sweep now takes a separate admission gate,
  reads the run lock's state without acquiring it, and does nothing at all
  while no session is ledgered — a dispatch is busy-rejected only when
  another run really holds the lock. Each reaped session now writes one
  `sl daemon: reaped orphaned session <id>` line to the daemon's stderr
  (`daemon.log` under the wrapper): rare, and a minor sign of ill health —
  a client died mid-run without closing its session. `docs/daemon.md` now
  says that any other session on the daemon port, a hand-connected REPL
  included, is reaped by the same two-strike rule, mid-eval or not.

<!-- entry: sl-ntlx -->
- **Internal: the warm daemon reaps sessions leaked by killed clients.** A
  warm `sl` invocation killed mid-run (Ctrl-C) never sends its nREPL session
  close, and nREPL retains the session — plus a parked thread — until daemon
  shutdown. The daemon now runs a periodic orphan-session sweep (every 5
  minutes, two-strike, only while no dispatch is in flight) that closes such
  sessions once their abandoned run completes. Abort semantics are unchanged:
  Ctrl-C still lets the in-flight run finish inside the daemon (the abort
  story remains open design work). No user-facing surface changes.

## Internal

<!-- entry: sl-raf4 -->
- **Config becomes its own top-level module.** The config-language
  namespace (specs, loading, lint, validation, accessors for
  `shiftlefter.edn`) moves from `shiftlefter.runner.config` to
  `shiftlefter.config`, beside the existing `shiftlefter.config.user` —
  substrate fed TO the runner, not owned by it. The did-you-mean helpers
  (Levenshtein + `suggest-similar`), previously two private copies, dedupe
  into a new neutral `shiftlefter.text` namespace. No behavior change; no
  user-facing surface moves — `shiftlefter.edn` keys, CLI flags, and all
  outputs are untouched.

---

# Changelog: 0.5.5

**Release Date:** 2026-08-25

The theme of this release is **never guess before the run** — ask the tool
what the run will believe: the resolved state has commands, provenance
rides every entry, declarations are verified against the live world, and
what the tool cannot know it marks — never guesses.

## Stats

```
2372 tests, 8841 assertions, 0 failures (./bin/kaocha unit — the fast suite)
23 tests, 110 assertions, 0 failures (./bin/kaocha daemon — warm-path shell E2E through the built jar)
11 tests, 42 assertions, 0 failures (SHIFTLEFTER_LIVE_WEBDRIVER=1 ./bin/kaocha e2e — live-browser example validators)
Cucumber compliance: 46/46 good, 11/11 bad (100%)
Verified against Chrome 151 / ChromeDriver 151
```

## Highlights

- **`sl init` scaffolds a working project** — install to first green
  `--dry-run` in one command.
- **Ask the tool what the run will believe:** `sl glossary` and
  `sl explain NAME` — the resolved vocabulary with provenance, usage
  counts, and did-you-mean, before anything runs.
- **The timing doctrine is now callable** — `poll-until` plus a config
  `:timing` ladder; timeouts report which config rung decided them, and
  the anti-flaky machinery stops being something you hand-roll.
- **Exit codes that mean something:** 0 is existential, 1 is "the app is
  wrong," 2 is "nothing ran," 4 is "the run proves nothing" — and the
  `--edn` envelope carries evidence pointers for every one.
- **Custom adapters are a spec'd contract** — declare the data, bind the
  factories; dry-run sees your registry, and a scenario that cannot
  provision refuses up front.
- **Every run has a seed, streams live, and writes one aggregated
  report** — reproduction handles, warm-path output, multi-group
  JUnit/HTML that never eats a red group.

<!-- no-entry: sl-t8bj (docs-only mechanism correction: costume Chrome is a JVM child, durability = profile + relaunch; docstrings/COSTUMES.md rewording, no behavioral surface) -->
<!-- no-entry: sl-0m4q (chew/process bead: positioning-doctrine dispositions — beads, rulings, doc homes only; no shipped surface) -->
<!-- no-entry: sl-y5qw (shiftlefter.com homepage Java-floor correction — site repo, no SL shipped surface) -->
<!-- no-entry: sl-8yfe (shiftlefter.com Argument theme + analytics snippet — site repo, no SL shipped surface) -->
<!-- no-entry: sl-hshb (homepage known-false clause struck in the sl-8yfe theme pass — site repo, no SL shipped surface) -->
<!-- no-entry: sl-aoya (design-doc errata fold for the derivation engine — grooming prerequisite, no shipped surface) -->
<!-- no-entry: sl-245y (folded into sl-sl-init-scaffold-cn7 at the batch-2 sitting rulings 08-09; the .shiftlefter/.gitignore ships via sl init and rides cn7's entry) -->
<!-- no-entry: sl-3kto (changelog head-format fix so the version guard reads 0.5.5 as unreleased; process file only, no shipped surface) -->
<!-- no-entry: sl-xy18 (notesview doc server: external tooling at ~/bin/notesview, lives outside the SL repo per its charter; no shipped surface) -->
<!-- no-entry: sl-lskf (Chair's machine remediation: chromedriver 150->151 fetch to restore Chrome lockstep; the automated lockstep probe itself shipped with sl-afsp's doctor entry; no additional shipped surface — line added by trench-zebra to clear the guard after the parallel close, flagged for Tower review) -->
<!-- no-entry: sl-dix8 (architecture-series docs artifact: docs/architecture/invocation-map.md + ARCHITECTURE.md pointer — repo docs only, no shipped surface) -->
<!-- no-entry: sl-cdnt (architecture-series docs artifact: docs/architecture/repl-lifetimes.md + pointers — repo docs only, no shipped surface) -->
<!-- no-entry: sl-37xz (architecture-series docs artifact: docs/architecture/data-shapes.md + pointers — repo docs only, no shipped surface) -->
<!-- no-entry: sl-78md (docs-pass collector: 13 dispositions — index rows, non-goal statements, under-taught-feature paragraphs across docs/ + agent_docs; teaches existing behavior, changes none) -->
<!-- no-entry: sl-ygtj (architecture-series docs artifact: docs/architecture/control-loop.md + sibling cross-links + ARCHITECTURE.md index — repo docs only, no shipped surface) -->
<!-- no-entry: sl-2rhl (propose-only notes/ re-groom: inventory + disposition proposal file, zero movements — process record, no shipped surface) -->
<!-- no-entry: sl-uo0v (process/docs-only: roadmap process-law promoted to durable homes, roadmap shrunk to charter + pointers, gen-roadmap.bb + release-roadmap.md moved to board/ — no shipped surface) -->
<!-- no-entry: sl-2b2k (changelog-guard hardening: label-blind span arm, current-section coverage scoping, marker-anchors-prose, exactly-one-gate assertion — dev-repo test infra, self-skips in the public export, no shipped runtime surface) -->
<!-- no-entry: sl-j2aq (closed deduped into sl-h7c3: the :override-defaults removal ships under h7c3's Breaking Changes entry; no separate surface — the future wholesale-override want is sl-t2ht) -->
<!-- no-entry: sl-xzom (shiftlefter.com /docs/ section — curated human docs + architecture series synced to the site; sync tooling is dev-repo scripts/, no SL shipped surface) -->
<!-- no-entry: sl-cvow (shiftlefter.com light/dark toggle + llms.txt additions — site repo only, no SL shipped surface) -->
<!-- no-entry: sl-73lb (website /report sample regen through the 0.5.5-preview jar — exit-4 DEGRADED + whole-vocabulary badge — plus the planning-pair homepage link; the report.js blank-page regression it caught was introduced and fixed inside the unreleased span, so no release reader ever saw it; site-repo artifact refresh, no SL shipped surface) -->
<!-- no-entry: sl-rktz (pass-2 docs-truth batch: JUnit no-file/iff claims scoped to single-group with multi-group partial-file semantics stated at all five surfaces, W8 MIRROR NOTEs completed, data-shapes ex04 re-worked vs HEAD, html/resolution docstring truth, plus ONE shipped cosmetic string — the bin/sl custody message reworded to unreachable-only; no behavior change, nothing a release reader could act on) -->
<!-- no-entry: sl-8cvd (Warden pass-2 condition batch A: suite-lint unknown-adapter deferral to the exact bound-adapter check, config-stage --edn :config-lints, real two-group JUnit pin, guard blank-line lost-bullet arm, dead accessor removal — corrections to unreleased 0-5-5-span behavior already described by the sl-kkoh/sl-xjx0/sl-74w8/sl-2b2k entries and the shipped sl-lnj1 contract; no separate feature surface) -->
<!-- no-entry: sl-t0r9 (report.js runtime smoke: node+jsdom renders every verdict status in the unit suite; dev/test infra closing the t86d escape class — the regression itself never shipped and its fix was already ruled no-entry, so an entry would tell release readers about a bug no release ever had) -->
<!-- no-entry: sl-8i5r (the release-tail hands-on drive: Chair + fresh agents drove install, first-ever :api interface authoring, and live runs against a real service — validation/process bead; findings ledger at notes/sl-8i5r-live-drive-findings.md; the gate fixes it produced ship as sl-zjf5; no shipped surface of its own) -->

## Features

<!-- entry: sl-qwf -->
- **Warm runs stream their output live.** Through 0.5.4 the warm path
  delivered all output at command end (a workaround for a multibyte crash in
  the bundled nREPL 1.5.0's output stream, fixed by the 0.5.3 nREPL 1.7.0
  bump). The dispatch client now runs commands under the nREPL session's own
  output streams, so warm invocations print exactly like cold: per-scenario
  results as each scenario finishes, fuzz progress as it happens, and partial
  output stays visible on Ctrl-C. Multibyte output (emoji, CJK, `—`, `✓`)
  streams clean and remains byte-identical to cold; exit-code parity
  (0/1/2/3/4) is unchanged, and a mid-stream transport failure now exits 3
  instead of silently re-running the command cold (which would duplicate the
  already-printed output).
<!-- entry: sl-1206 -->
- **Costumes declare their interface, and wrong-world wear fails at plan
  time.** A new committed-config key — `:costumes {:finance {:interface
  :storefront}}` in `shiftlefter.edn` — binds each costume to the interface
  *instance* it belongs to. Two static lints ride validation and
  `--dry-run` (human and `--edn` output alike): wearing a costume on a
  different instance than it is declared for is a plan-time error with a
  did-you-mean, and two personas sharing a `:wears` in one scenario is a
  plan-time error (one logged-in identity cannot be two actors). A
  worn-but-undeclared costume warns and teaches the declaration to add, so
  existing wardrobes and REPL ad-hoc flows keep working; the runtime
  refusals stay as the backstop for paths that bypass planning. The `sl
  init` starter config gains a commented `:costumes` exemplar.

<!-- entry: sl-sl-init-scaffold-cn7 -->
- **The installer now scaffolds a working project (`sl init`).** A fresh
  `release/install.sh` run creates `sl/` with a commented starter
  `shiftlefter.edn` (Shifted mode from birth, explicit conventional glossary
  paths, the off-by-default capture/report/timing knobs shown), a subjects
  glossary seeding two documented personas (alice — the ordinary path, bob —
  the counterparty), a loader-invisible worked intent
  (`glossary/intents/login.edn.example`), and a starter feature that proves
  the whole pipeline: `sl run --dry-run` exits 0 on a fresh scaffold, pinned
  by a test. The agent on-ramp stanza is now WRITTEN into `AGENTS.md`
  (marker-guarded create-or-append, reusing the installer's single stanza
  source) instead of only printed. `sl init` is a low-profile entrypoint (not
  in `--help`; the public surface waits for sl-f1ir) and bails untouched when
  `sl/shiftlefter.edn` already exists. Folds sl-245y: the scaffold writes a
  self-ignoring `.shiftlefter/.gitignore` (`*`) so the runtime state dir never
  shows up as git litter — one write moment, at init.
<!-- entry: sl-mpu5 -->
- **`sl glossary` and `sl explain` now answer usage questions, not just
  resolution.** The listings bind the feature corpus (parse + bind, never
  execute — the same binder a run uses) and mark every entry: subjects
  and user vocabulary nothing references show `[UNUSED]`, used entries
  show `[used ×N]`, and builtin vocabulary is never dead (it carries
  counts, never a verdict). `sl explain <name>` gains a usage section —
  per-hit feature `file:line`, scenario, the step text and its pattern
  (authoring by example), per-instance and per-type rollups, a
  multi-actor flag, and co-occurrence pairs. Positional paths and
  `--tags`/`--skip-tags` (the run's own selection grammar) scope any of
  it, and the wording weakens accordingly: a selection says
  `not referenced within this selection` — never "unused" — with the EDN
  key changing from `:unused?` to `:referenced-in-selection?` so a
  machine consumer cannot mistake an impact partition for a deletion
  license. Deadness is a full-corpus fact at a named tree state (the
  substrate header already says which); no corpus means markers are
  suppressed, never "everything is unused". Two locator surfaces join:
  `sl explain '{:css "#submit"}'` reverse-looks-up which intent
  addresses own a locator value (check before you mint a duplicate), and
  `sl glossary duplicates` is the on-demand collision report, grouped by
  confidence (same-intent collisions are high-confidence real; the same
  `#submit` across page-scoped intents is the norm, which is why the
  report is never always-on). Intent listings also name their
  per-interface-type binding holes (`missing bindings: :sms`), and
  `sl explain <intent>` now reports those holes on the interface TYPE
  axis (previously instance names — a mis-report for renamed instances).
  The engine underneath is the SVO reverse index over bound plan steps:
  (S,V,O) + file:line tuples — prepaid vocabulary for the graph era.

<!-- entry: sl-99u2 -->
- **Custom steps can now call the timing doctrine instead of
  reimplementing it.** The doctrine — oracles retry patiently, mutations
  never retry, negation never waits — used to live only inside the
  built-in steps; a custom `defstep` that needed to wait hand-rolled its
  own sleep loop and silently diverged. Now `shiftlefter.step/poll-until`
  is the public oracle kernel: poll a predicate until truthy (or pass
  `:until` when the predicate observes a value worth reporting), and on
  deadline get a structured, legible timeout through normal step
  reporting — elapsed time, the deadline, **which config rung decided
  it**, and the last observed value. Deadlines and intervals resolve down
  one ladder from the new `:timing` config key (`{:timing {:deadline-ms
  30000 :interval-ms 500}}`, lint-aware, with a `:timing` sibling on any
  `:interfaces` entry for per-interface overrides): explicit override >
  interface instance > interface type > global > per-class defaults. The
  built-in browser verification, `:wait`, and SMS receive steps all ride
  the same substrate now (their old defaults — 3s/100ms, 5s/100ms,
  30s/500ms — are the ladder's bottom rung, and the old dynamic vars
  still rebind as top-rung overrides), so one config entry retunes the
  whole suite's patience coherently — and browser timeouts, which
  previously rethrew the last assertion error with no timing information
  at all, now say they were timeouts. The doctrine itself is taught in
  [extending-vocabulary](docs/extending-vocabulary.md); declared
  operation classes on `defstep` are the 0.6 contracts arc.

<!-- entry: sl-4e91 -->
- **Costumes now bind per persona instance, and personas carry
  docstrings.** An `:instances` entry in the subjects glossary may be a
  persona map — a user with an optional costume and a docstring:
  `{:id :alicia :desc "Spanish-first admin reviewer" :wears :alicia}`
  (bare keywords still work; the shape change is additive, no
  migration). Costume resolution is instance > type: a persona's own
  `:wears` wins, a type-level `:wears` becomes an inheritable default,
  and an explicit `:wears nil` on a persona opts out of it. This is
  what lets `:user/alicia` and `:user/bob` act in one scenario wearing
  different jackets — previously `:wears` bound only at the subject
  TYPE, so with the one-wearer-per-costume guard, type-level wear was
  usable only by effectively-singleton types (the already-worn error
  now teaches the per-instance fix). Persona docstrings and effective
  wear surface everywhere personas are listed: `sl explain
  user/alicia` shows the description and `Wears: :alicia` (inherited
  defaults are marked), `sl glossary subjects` renders per-persona
  sub-lines, and the `--edn` instance rows carry
  `:description`/`:wears`/`:wears-source`.

<!-- entry: sl-wntk -->
- **Custom adapter registries are now a spec'd, boundary-validated
  contract.** The registry entry shape — `:factory`/`:cleanup`/`:provides`
  required (an explicit `[]` is a truthful "provides no protocols"),
  `:impl-key`, `:on-provision`, `:capture`, and the not-bundled trio
  optional — has been informal prose since the adapter registry landed;
  now it's clojure.spec (`::adapter-entry` in `adapters/registry.clj`,
  the single source of truth, with the shipped `default-registry` as the
  spec's own first conformance test). A custom registry handed over by
  `setup.clj` `:start` is external input (house boundary-validation
  rule), so it's validated at the install boundary: a malformed entry —
  a `:factory` that isn't invokable, an unqualified `:provides` keyword,
  a typo'd key like `:implkey` (caught by an unknown-key check alongside
  the spec, since open `s/keys` can't see it) — fails as the per-group
  planning error `:setup/invalid-adapter-registry` carrying the spec's
  own `s/explain` output plus the known-key vocabulary, before any
  factory runs, instead of an incidental throw at first provisioning.
  The check deliberately precedes the sl-e0ml declaration-mismatch
  verify (whose data comparison would itself trip over a malformed
  `:provides`) and never touches dry-run's data-only preview registries.
  The factory contract (`{:ok …} | {:error …}`) is registered as
  `s/fdef`s on `create-capability`/`cleanup-capability` and instrumented
  in the test suite, so every custom registry flowing through tests is
  shape-checked on each call. No behavior change for valid registries.
  The symmetric typo-catch for `:adapters` declarations is recorded as
  sl-k15d (found, not built — outside this bead's boundary scope).

<!-- entry: sl-s2oa -->
- **Two same-type interfaces now share the builtin vocabulary: stepdefs
  bind by interface TYPE, and the `[name]` annotation selects the
  instance.** A config like `{:web {:type :web :adapter :etaoin} :admin
  {:type :web :adapter :playwright}}` previously could not drive the
  second entry with builtin steps — builtins pinned `:interface :web`
  and the binder compared the `[:admin]` annotation against that
  metadata raw, so the step went `:undefined` ("No [:admin] stepdef
  matches, but [:web] does"). The model is now split along its real
  joints: stepdef `:interface` metadata names the TYPE (the vocabulary a
  verb belongs to — welded to the verb, never an adapter, never a config
  name), the annotation names a configured INSTANCE, and binding
  resolves name → config entry → its `:type` narrows candidates → the
  verb executes on the named instance's capability
  (`:cap/<instance>.<subject>`, per-instance `:base-url` honored).
  Unannotated steps of a multi-instance type default to the type-named
  entry; with no such default the plan fails loudly
  (`:bind/ambiguous-instance`, naming the candidate instances) instead
  of guessing. Planning diagnostics surface the resolution chain
  (`No [:admin] (type :web) stepdef matches, but type :sms does.`), the
  `--edn` planning counts gain `:ambiguous-instance-count`, and
  suite-lint's undefined-interface check now validates stepdef TYPES
  against configured types — which is also the migration signal: a
  custom stepdef that pinned an interface NAME in `:interface` metadata
  (the only way to drive a renamed interface before this) now fails that
  lint and should declare its TYPE instead, with the `[name]` annotation
  doing the instance selection in the feature. Intent bindings, named
  locations, and glossaries stay TYPE-keyed throughout.

<!-- entry: sl-afsp -->
- **`sl doctor`: machine pre-flight as a declarative probe registry —
  when a machine misbehaves, run it and paste the output.** Doctor
  validates the MACHINE; `sl orient` validates the project; the lanes
  deliberately don't overlap. The design is a single-source-of-truth
  registry of named probes (`{:probe … :desc … :scar … :fn …}`): the
  runner iterates it, `sl doctor --list` prints it, and nothing is
  checked that isn't declared there — so what doctor does and does NOT
  check is always discoverable. Scope is bitten-things-only: every seed
  probe carries its `:scar`, the documented failure that earned it.
  The six seeds: chromedriver presence + Chrome major-version lockstep
  (the 149→150 drift, previously a manual audit line — and the probe
  caught a REAL 150→151 drift on its very first live run);
  `~/.shiftlefter/config.edn` validity (parse errors are silently
  swallowed into PATH fallback on every other path — triage is
  sl-26yo); extra-dependency adapters present when configured
  (the sl-gcdu Playwright class); daemon health (`daemon.edn` vs live
  pid, jar/lib-sig staleness noted; routed COLD in `bin/sl` so doctor
  observes the daemon from outside rather than answering from inside
  it); orphaned-chromedriver scan (parent-is-PID-1 discriminator —
  scan only, reaping stays sl-34fu); and `sl/lib` jar sanity
  (readable archives + lib-sig drift). Output is a human table, `--edn`
  through the pinned printer seam with `:doctor/version 1`, and doctor
  carries its own tiny exit contract — 0 healthy / 1 issues found — a
  machine verdict deliberately outside the run-verdict table (sl-7c7i).
  The chartered adjacent verification confirmed config validation fires
  on every config-parsing invocation (cold commands exempt via the
  single `needs-project-context?` allowlist; `run` exits 2, the
  orient family deliberately exits 1); the one gap found was dead code,
  filed as sl-v8cu.

<!-- entry: sl-sehc -->
- **Playwright captures now: `screenshot` and `page-source` — and a
  configured kind that can't fire tells you so before a browser opens.**
  The Playwright backend contributes both pull-at-capture kinds through
  the same lazy-load boundary as the rest of the adapter (nothing new on
  the default classpath). `console` is deliberately absent there: capture
  reads are destructive drains of a browser-side buffer, and Playwright
  has no such buffer — its console messages are listener events, so a
  pull-at-capture read finds nothing; the subscription-shape design that
  fixes this properly is sl-lam0. And the warn granularity finally
  matches the composition: alongside the run-level unknown-kind (typo)
  warning, a new plan-time, per-group check
  (`:capture/adapter-unsupported`) fires when a configured kind is
  supported by none of the adapters a group actually uses — computed
  from the declared adapter data (sl-e0ml), so it appears in `--dry-run`
  previews and rides `:diagnostics :capture-lints` under `--edn`. That
  kills two silent edges at once: a pure-Playwright project with capture
  configured no longer gets nothing-and-no-warning, and a mixed
  etaoin+playwright multi-group run warns on exactly the group whose
  evidence would be missing (the old registry-level check could never
  see either, because the kinds ARE contributed — by the other adapter).

<!-- entry: sl-0gfc -->
- **Ask the tool what the run will believe: `sl glossary` and
  `sl explain NAME`.** The files are the declared state; these commands
  expose the RESOLVED state — the effective vocabulary after config
  resolution, glossary merging, and interface wiring, before anything
  runs. `sl glossary` lists subjects, verbs (per interface type, frames
  as schema), and objects/intents, every entry carrying a provenance
  marker (`builtin` / `user-file` / `config-override` /
  `interface-contributed`); narrowing arguments (`sl glossary verbs`)
  select one section. `sl explain NAME` resolves one name across every
  kind — subject type, instance, verb, intent, key name — with its
  definition site, provenance, frames, and per-interface bindings
  INCLUDING missing ones; a miss gets did-you-mean suggestions
  (edit-distance plus surface-token lookup: `sl explain clicks` suggests
  `:click`) and exit 1. Both commands resolve through the run's own
  config path (`-c/--config` behaves exactly as `sl run` — proven by
  test), take `--edn` as the stable machine contract (human format
  explicitly unstable), and stamp their output with the substrate it was
  computed against: config path + source, git sha with dirty marker, and
  the EFFECTIVE `:svo` strictness levels — including the conditional
  `:unknown-object` default no file can show. Shifted-only, with an
  honest Vanilla message. Alongside: the long-documented
  `:override-defaults` glossary flag was verified DEAD through the
  public loaders (docs corrected — and later in this release the flag
  was removed outright: see Breaking Changes, sl-h7c3), and the real
  merge semantics are now documented where they live: project verbs
  extend the builtin set, a same-named verb replaces the builtin entry
  wholesale.

<!-- entry: sl-e0ml -->
- **Custom adapter registries are now visible to dry-run — declare the data,
  bind the factories.** A `setup.clj` group's custom adapter registry used to
  exist only as a return value of `:start`, so `sl run --dry-run` (which
  never runs `:start`, deliberately) bound against the default registry — a
  custom-registry project could bind differently in preview than live, and
  agents live on dry-run. Groups now declare the registry's *data half* as a
  new optional `:adapters` key: a vector of pure-data declarations
  (`{:name :my-sms :provides [...] :capture {...}}` — no functions), covering
  only what differs from the default registry. The preview binds against the
  declarations, `:start` still builds the real registry (factories closing
  over runtime state stay legal — the `04-sms-2fa` shared-mock pattern), and
  at `:start` the framework verifies the live registry matches the
  declaration: a mismatch is a loud planning error
  (`:setup/registry-mismatch`) — a declared contract, runtime-checked. A
  novel adapter name must declare `:provides` (an explicit `[]` is legal and
  truthful); malformed declarations fail plan-shape linting, visible to
  dry-run. **Migration:** a `:start`-returned registry with no declaration
  keeps working this release, with a preview-blind warning
  (`:setup/undeclared-adapter-registry` — one stderr line, or a supplementary
  `:setup-lints` form under `--edn`); add the `:adapters` declaration to
  silence it. The undeclared shape becomes an error in a future release.

<!-- entry: sl-08f7 -->
- **Every run has a seed — recorded, and handed to your code.** One seed
  per invocation (positive 64-bit integer): `--seed N` wins over
  `:runner {:seed N}` in config, and with neither set one is generated.
  It is recorded everywhere the run is: `:run/seed` in every group's
  `--edn` summary (core-record family — always present; a record without
  one is an old record), the run context reporters see, the HTML report
  header, and one console line (`run seed: N`, stderr — deliberately, so
  every run's reproduction handle is visible). The framework draws no
  randomness from it, on purpose: it is the datum your code seeds from —
  hook payloads carry `:seed`, step `ctx` carries `:seed`, and a
  `setup.clj` group's `:start` receives it on its config argument — so
  fixture generators and randomized libraries draw from one reproducible
  source instead of each inventing its own. Deriving independent
  per-scenario streams is a documented one-liner
  (`(bit-xor seed (hash scenario-id))`, docs/lifecycle.md § The run seed);
  the framework provides the datum, consumers choose. Groundwork for
  rerun-from-report.

<!-- entry: sl-n9c6 -->
- **Multi-group stable path becomes the invocation report.** With a
  multi-group `setup.clj`, the fixed `--html` path no longer holds
  whichever group's report was written last: it now carries an
  invocation-level TOC — run-root `report.html` above the group dirs,
  invocation verdict up top (7c7i suite precedence), one row per group in
  declaration order with status, exit code, counts, and a relative link to
  the group's own page. Links navigate inside the run directory and
  degrade to labeled text in the fixed-path copy; verdicts stay visible
  either way. A group that fails planning mid-run now keeps its PLANNING
  FAILED page durably at its own `<group-slug>/report.html` — previously a
  later green group's copy destroyed the only copy in existence.
  Single-group and implicit runs are byte-identical to before.

<!-- entry: sl-femd -->
- **Unknown-object checking now defaults on — when there's something to check
  against.** With an `:intents` glossary configured (declared under
  `:glossaries` or conventionally discovered at `glossary/intents/`),
  `:svo {:unknown-object …}` now defaults `:warn`: an unresolvable intent
  reference is reported at planning time with the name and a did-you-mean,
  instead of dying minutes into a browser run as an element error. Without an
  intents glossary the default stays `:off` — nothing declared means nothing
  to validate against. The warning never blesses success: an unresolvable name
  still fails at runtime; it now also fails before the run. Raw locators and
  quoted literals stay exempt (the locator-policy escape hatch, deliberate).
  Naming harmonized while here: the key's blocking level is now spelled
  `:error` like its `:unknown-subject`/`:unknown-verb` siblings, and `:strict`
  keeps working as a deprecated alias. **Migration:** a project with `:svo`
  configured and an intents glossary that relied on the silent `:off` will see
  new plan-time warnings (warnings only — nothing blocks, exit codes are
  unchanged); set `:svo {:unknown-object :off}` explicitly to restore silence.

<!-- entry: sl-d7ks -->
- **The bus counts its drops out loud.** The observe-plane bus already
  counted every event it refused at admission (full buffer — drops over
  blocking, by design), but the count only reached a log line. Each
  run-scope's summary now carries its own drop delta as `:dropped-events` —
  in the `--edn` record, the programmatic result map, and a console stderr
  line when nonzero. Absent when zero. Results are never affected by drops;
  the key means observations may be gapped.

<!-- entry: sl-oxiz -->
- **The `--edn` summary now carries attachment refs — the machine surface
  gets its evidence pointers.** Every other report surface already showed
  attachments; the one surface agents consume didn't. Now an executed
  summary (exit 0/1/4) with any captured or hook-claimed evidence carries a
  top-level `:attachments` index — one entry per evidence-bearing scenario,
  `{:scenario/name … :scenario/id … :attachments [ref …] :steps [{:step/id
  … :step/text … :attachments [ref …]}]}` — the authoritative complete
  evidence walk, covering green scenarios too (`every-step` captures,
  After-hook claims on passing runs). Failure entries additionally carry
  their own step's/scenario's refs inline for join-free triage; a failed
  step's refs appear in both places, verbatim, on purpose. Refs are the
  same `:attachment/*` maps the envelope carries — kind + group-root-
  relative path, media-type, bytes — so a consumer can resolve every
  artifact from the summary alone. Additive and seq-guarded: runs with no
  evidence produce byte-identical output; consumers already skipping
  unknown keys (the form-stream contract) need no changes.

<!-- entry: sl-e01m -->
- **Build your own report: docs/REPORTS.md documents the machine record.**
  The `--edn` summary stream plus the run-directory bytes are the extension
  contract — no plugin API, on purpose. The new page inventories the built-in
  outputs, documents the attachment-ref anatomy and the evidence walk (the
  authoritative `:attachments` index vs inline failure refs), and ships a
  complete runnable babashka recipe rendering a markdown digest with resolved
  evidence links. The in-tree JUnit reporter is the proof of the pattern.
  Also: agent-doc "Consuming the run record" subsection, and the hooks.md
  claim that `--edn` lacked attachment refs (stale since sl-oxiz) corrected.

<!-- entry: sl-2de7 -->
- **`relaunch-costume!` / `sl costume relaunch` — the human re-login flow
  for an existing costume.** Once a costume existed there was no
  supported way to open its browser with no WebDriver session attached —
  exactly what re-authenticating an expired credential needs
  (`init-costume!` refuses existing costumes by design;
  `connect-costume!` relaunches and attaches in one step; the workaround
  was dropping to `chrome/launch!` internals). The new launch-only
  relaunch opens the existing profile's browser for a human to log into;
  the next connect (or `sl run` via `:wears`) attaches as usual. An
  already-running browser is an idempotent success (`:already-running`,
  recorded pid trued up from the port's actual owner) — relaunch never
  kills a live browser (that compound remains destroy + init), spawns no
  chromedriver, and never touches the costume driver registry. This is
  the missing primitive for the costume expiry/refresh story; the
  policy half stays future work.

<!-- entry: sl-9od5 -->
- **Worked example: your adapter contributes a capture kind.** New
  `examples/07-custom-capture-kind/` demonstrates the CONTRIBUTE side of
  the capture contract — the half that makes the kind set open rather
  than a feature toggle. A mock-SMS adapter registry in `setup.clj`
  contributes a custom `:message-log` kind (the ~10-line capture-fn
  contract: `{:handle}` in; `{:data :media-type :ext}` out, nil = nothing
  to capture, throw = capture failed), the config's `:every-step` policy
  captures it after each step of a green scenario, refs land in the
  envelope, and the report renders them — the full arc, browser-free,
  nothing to install (`sl run sl/features/`). First consumer of the
  registry-entry contract's boundary validation: the example's README
  walks the two guardrails (`:setup/registry-mismatch` for an undeclared
  kind, `:setup/invalid-adapter-registry` for a malformed entry).
  `docs/hooks.md` gains a "Contributing a capture kind" section and the
  After-hook routing rule sharpens to one-off evidence vs
  capability-owned evidence. The example is validated in the bare unit
  suite — a planning validator plus a LIVE full-arc run (no browser
  needed), so this teaching surface can't rot silently. Pairs with
  `docs/REPORTS.md` (sl-e01m): produce evidence here, consume the record
  there.

## Breaking Changes

<!-- entry: sl-h7c3 -->
- **The `:override-defaults` glossary flag is gone — glossaries always
  extend the defaults.** The flag was documented for wholesale
  replacement but had been parsed-then-dropped by both public loaders
  through every real path for its whole life (verified during the 0.5.5
  audit cycle): no project ever got override behavior from it. Rather
  than wire up a capability nobody has, the flag is removed outright —
  the merge functions always extend, and a glossary file still carrying
  the key now draws a load-time warning naming the truth (not supported;
  glossaries always extend defaults — restate an entry to replace it
  wholesale), in lenient and Shifted mode alike. "Breaking" in letter
  only: behavior is identical for every file that ever loaded; what
  breaks is the false promise. A real wholesale-override design remains
  tracked separately.

<!-- entry: sl-luhn -->
- **A busy daemon now rejects a second invocation instead of queueing it
  silently.** Concurrent `sl` calls against one project were always serialized
  by the warm daemon, but the second caller just blocked with no output until
  the first run finished — for an agent, indistinguishable from a hang. It now
  fails fast: exit 2 with the structured error `:daemon/busy`, one stderr line
  (`another run is in progress for this project; retry when it completes`),
  and under `--edn` the standard exit-2 planning summary carrying the error
  type. The first run is undisturbed; nothing queues. Applies to every warm
  command; `sl daemon status`/`stop` stay wrapper-handled, so a busy daemon
  remains inspectable and stoppable. Also in this bead's floor: the fixed-path
  `--junit-xml` write is now atomic (temp sibling + rename, like the HTML
  report since 0.5.4) — concurrent cold runs get last-writer-wins, never a
  torn file — and the docs now state the concurrency contract plainly
  (docs/CI.md § Concurrent invocations, docs/daemon.md § One run at a time /
  § Aborting a run — including the live-verified fact that Ctrl-C does not
  stop a warm run). First-class abort is sl-617t; kill-leaked drivers are
  sl-34fu. **Migration:** anything that relied on the silent queueing must
  retry on exit 2 / `:daemon/busy` (or serialize its own calls); a plain
  rerun when the first run completes behaves exactly as before.

<!-- entry: sl-7c7i -->
- **The verdict contract: exit codes strengthened.** The 0/1/2/3 contract was
  reopened strengthen-only for this release, and locks hard from here:
  - **New exit 4 — run degraded.** A scenario `:error` (infrastructure
    failure: lifecycle hook throw, capability provisioning, capture, harness)
    now exits 4 with run status `:degraded`, no longer 1. Exit 1 now means
    exactly "at least one scenario failed — valid tests, app wrong."
  - **Provisioning failures join the `:error` family.** A capability
    provisioning failure makes the scenario `:error` (was `:failed`); its
    JUnit element moves from `<failure>` to `<error>`.
  - **Empty selection exits 2.** A run selecting zero scenarios (0-of-N tag
    filter, feature files with no scenarios, CLI paths narrowing every
    `setup.clj` group away — checked before any `:start` runs) is a planning
    error, never a silent green; exit 0 is now existential (≥1 scenario
    selected). Applies to dry runs too. No allow-empty knob: the record's
    `:data` carries selected/total/searched/filter so a CI wrapper can decide
    upstream (dry-run-first pattern, docs/CI.md).
  - **Arg-parse and usage errors exit 2** (were 1): a typo'd flag or missing
    subcommand argument is an un-runnable invocation, like an unknown command.
  - **Counts ride every `--edn` summary**, including exit-2/3 records (zeros).
  - **Migration:** CI that gates on "nonzero = fail" is unaffected. Scripts
    testing `== 1` must also handle 4; scripts treating an empty selection as
    green must adopt the dry-run-first wrapper. Canonical table:
    `shiftlefter.runner.verdict` + README § Return Codes, guarded by
    `exit-contract-guard-test`.

<!-- entry: sl-7q7w -->
- **`destroy-costume!` now refuses while the browser is live — and never
  deletes a profile it can't confirm is dead.** Destroy trusted the pid
  recorded in `browser-meta.edn`; when that pid was stale it reported
  `:destroyed`, deleted the credential-bearing profile, and left the real
  browser running, still authenticated. Liveness is now verified against the
  debug port (the process actually serving it), never the recorded pid:
  - **Live browser → structured refusal** `:costume/browser-live`, naming the
    pid actually serving the port. Override with `--force` (CLI) /
    `{:force true}` (REPL/programmatic), which kills the port's real owner and
    re-verifies death before deleting; if the browser survives the kill, the
    profile is kept (`:costume/destroy-failed`). Unreadable metadata also
    refuses (`:costume/meta-unreadable`) — no port, no confirmation.
  - **Dead browser → no kill at all.** The old path killed the recorded pid
    even when nothing served the port — a pid the OS may have reused for an
    unrelated process.
  - `connect-costume!` now refreshes the recorded pid on the
    attach-to-already-alive path (relaunch always did), so `costume list`
    stops reporting a dead pid for a live browser.
  - **Migration:** scripts and agents destroying live costumes must close the
    browser first or pass `--force`; a bare `sl costume destroy` on a live
    costume now exits 1 with the rerun hint.

## Fixes

<!-- entry: sl-zjf5 -->
- **A fresh install no longer offers ~96MB of binaries to your first
  `git add`.** `release/install.sh` now writes a binaries-only
  `sl/.gitignore` (the jar, the `.jsa` CDS archive, `lib/`) when none
  exists, so everything you author in `sl/` — config, features, glossary,
  steps — stays committable while the installed tooling stays out of your
  history; a `.gitignore` you wrote yourself is never touched. The same
  release-tail pass trued three doc claims: the exit-code docs now state
  that an infrastructure failure surfacing inside a step (a connection
  refused mid-run) reports as exit 1 today and may classify into the
  degraded exit 4 in a future release, and the config reference marks the
  default `:web` interface's deep-merge under user `:interfaces` as
  provisional.

<!-- entry: sl-t86d -->
- **A crashed run's HTML report says CRASHED, the API docs stop rotting
  silently, and two docstrings tell the truth.** Audit-tail tidies. The
  HTML report's header badge now speaks the whole verdict vocabulary
  (one table with the TOC): exit 3 reads CRASHED instead of
  misattributing FAILED, and the previously-unstyled error badge family
  (PLANNING FAILED / DEGRADED / CRASHED) is actually visible. The
  committed codox API docs (`docs/api`) were regenerated — killing a
  retired costume-durability overclaim and the pre-0.5.5 exit table that
  had shipped stale — and a new drift-guard test holds them fresh from
  now on: the stamped version must match build.clj's (the release-cut
  regen step, made mechanical) and sampled load-bearing docstrings must
  match their generated pages, with failures naming the remedy
  (`bin/docs`). Docstring truth fixes: `shiftlefter.costume` no longer
  claims a costume "survives JVM restarts" (Chrome is a JVM child;
  durability is the profile plus relaunch), and
  `registry-problems` documents that its not-a-map arm serves direct
  callers only — at the runner boundary the start-result spec catches a
  non-map registry first.

<!-- entry: sl-kkoh -->
- **A run now refuses up front when a scenario it selected cannot
  provision — and `sl orient` reports declared runtime facts instead of
  shrugging.** Two precision refinements on the 0.5.5 declaration
  contract. First, the registry check went plan-aware: when a group's
  `:start` returns a registry missing an adapter that a bound, selected
  scenario actually references, the run is a planning error (exit 2,
  `:setup/bound-adapter-missing`) instead of a mid-run
  `:adapter/unknown` death — and, exactness cutting both ways, a
  configured-but-untouched interface whose adapter is absent no longer
  draws the removal warning (that config-scoped warning remains only as
  the fallback for surfaces without compiled plans). The check follows
  the selection: a tag-filtered-out scenario cannot trip it, and
  `--dry-run` (which never runs `:start`) is unaffected. `:stop` still
  runs on this new error — `:start` ran, so the world it built is torn
  down. Second, `sl orient`/`--edn` adapter metadata now reads the
  declaration's optional runtime fields: a declared `:impl-key`
  (including the deliberate `nil`) and `:on-provision?` replace the
  `:runtime-unknown` marker for exactly the fields the declaration
  speaks to; undeclared fields stay marked, never guessed.

<!-- entry: sl-s35y -->
- **`sl daemon status` shows costume custody, and the docs say who holds
  what.** A warm run's worn costume staying alive inside the daemon
  between invocations is costume persistence working (same design as the
  dev REPL JVM) — but the custody was invisible. `status` on a live
  daemon now lists the costumes and driver liveness the daemon holds
  (a pure read through a dedicated status query — never the dispatch
  path, so it mutates nothing; no daemon still means no JVM). The
  daemon JVM joins the lifetime map (docs/architecture/repl-lifetimes.md)
  with its reapers named, COSTUMES.md/daemon.md state the custody rule
  in one sentence each, and docs/CI.md gains the ratified guidance:
  prefer the cold path in CI (`SL_NO_DAEMON=1`) — the daemon is a
  development-loop tool.

<!-- entry: sl-xjx0 -->
- **The audit C1 batch — ten truth fixes across the surface** (v0.5.5
  pre-tag audit, sl-p1op). Multi-group runs write ONE aggregated JUnit XML
  instead of each group replacing the file (a red early group no longer
  vanishes behind a green last group). The warm client releases its nREPL
  session after every dispatch (previously one leaked session + thread
  per warm invocation until the idle reaper). `repl/run` honors the
  global `:timing` budget and threads a run seed exactly like `sl run`
  (and returns the `:seed`). Config lint warns on `:timing`/`:costumes`
  nested under `:runner` (silently dead) and on a costume declared for a
  non-etaoin-adapter interface (attach is etaoin-only this release).
  `sl doctor`'s ChromeDriver lockstep probes every binary the runtime
  could use, including per-interface `:adapter-opts` pins. Costume lint
  messages tell the truth: a typo'd `:costumes` key gets a did-you-mean
  instead of a prescription to declare the typo, and the cross-interface
  wear messages describe the actual situation. Adapter declarations gain
  pure-data runtime-field faces (`:impl-key` — nil declares deliberate
  absence — and `:on-provision?`), verified against the live registry;
  a builtin-name override that silently drops the default entry's
  runtime keys warns naming them, and a removed default adapter a
  configured interface still references warns before provisioning fails
  live (later scoped by sl-kkoh, above: at the run seam the exact
  bound-adapter check owns removal; the warn remains for plan-less
  surfaces). Both custom-registry examples now update the default `:sms-mock`
  entry instead of rebuilding it, restoring the per-scenario SMS time
  fence they had silently lost.
<!-- entry: sl-d3cz -->
- **`sl doctor` no longer fails your machine over a key it merely doesn't
  know, and `:timing` typos stop dying silently.** Two config-truth fixes
  from the pre-0.5.5 audit's docs+tests batch. Doctor: an unknown key in
  `~/.shiftlefter/config.edn` is now a `[WARN]` (the config still works —
  every consumer ignores the key), while invalid VALUES (bad type,
  missing or non-executable file, parse errors) still `[FAIL]`; warns
  never flip exit 0. Timing: the `:timing` map's inner keys are now a
  closed set at both rungs (global and per-interface) — an unknown key
  like `{:interval 250}` was previously accepted and silently dead; it is
  now a config error with a did-you-mean. The same batch closed audit
  coverage gaps (an automated mid-stream daemon-loss exit-3 guard, run
  end-to-end proofs for `:costume/shared-wears` exit 2 and the
  capture-failure exit 4, a scaffold zero-config-lints assertion, and the
  exit-code guard extended to every surface stating the table) and swept
  stale claims from README, the architecture maps, and the lifecycle /
  hooks / daemon / resolution docs.

<!-- entry: sl-hyey -->
- **The resolved state stops lying about custom adapter registries.**
  `sl orient`/`sl glossary`/`sl explain` built their picture from a
  hardcoded default registry, so on a project whose setup.clj installs a
  custom adapter the `--edn` `:adapter-metadata` reported runtime fields
  the project's own `:start` replaces, and the usage index could bind the
  corpus against the wrong registry — spurious capability failures then
  silently emptied every usage marker. The projection now reads
  setup.clj's `:adapters` declarations (the same declared-preview model
  `--dry-run` binds against) and stamps metadata with provenance: a
  builtin entry keeps its full fields; a declared entry carries the
  declaration's data half with `:impl-key`/`:has-on-provision?` absent
  and named in `:runtime-unknown` — never guessed (since refined by
  sl-kkoh, above: fields the declaration speaks to now carry their
  declared values; only undeclared fields stay marked). The corpus bind
  threads the declared registry, and capability gating on the listing
  surfaces is warn-not-kill: a listing is not a run, so a genuine gap
  rides the output as a visible caveat (and the `--edn` corpus block)
  instead of suppressing the markers; `sl orient` still reports
  `:status :error` exactly where a run would fail planning. setup.clj
  now also rides the projection fingerprint.

<!-- entry: sl-st01 -->
- **A malformed `:costumes` declaration is now a config error from `sl
  run`, not a silent off-switch.** The costume validators (`:config/
  invalid-costume`, `:config/unknown-costume-interface` with did-you-mean)
  were reachable only from `sl orient`/`explain`; the run path never called
  them, so a guessable shorthand like `{:finance :web}` produced no error,
  no warning, and quietly disabled every costume lint. They now run at the
  config stage of every run — stderr `Config error:`, `--edn`
  `:planning :errors`, exit 2 — alongside the existing adapter-availability
  check. Pre-tag batch from the v0.5.5 release audit (sl-p1op); same batch:
  the live-browser e2e arm re-pinned to the timing-substrate capture-miss
  contract, the `sl init` worked intent teaches `fills Login.username`
  (the old `{username}` exemplar parsed as a raw locator and collided with
  the data-plane `{name}` syntax), the layout doctrine matches what `sl
  init` writes, and ARCHITECTURE's registry-entry example passes the
  shipped validator.

<!-- entry: sl-74w8 -->
- **Every config validator now runs on `sl run`, not just the projection.**
  The remaining `normalize` validators — invalid or unknown-type interface
  definitions, malformed `:timing` maps (with did-you-mean), and
  macros-enabled-without-`:registry-paths` — were reachable only from
  `sl orient`/`explain`: a config the projection reported as `:error`
  still loaded under `sl run` and failed later, or not at all, with a
  less legible diagnostic. The run path's config stage now runs the
  whole validation aggregator (subsuming the direct costume-validator
  call it gained in the sl-st01 fix), so those shapes fail planning the
  same way every other config error does — stderr `Config error:`,
  `--edn` `:planning :errors`, exit 2 — and any validator added in the
  future is reachable from both channels by construction. Second wave of
  the v0.5.5 release-audit findings (sl-p1op).

<!-- entry: sl-52ly -->
- **Playwright key presses no longer round-trip through etaoin's key
  representation.** The press path used to resolve W3C key names to
  etaoin's private-use unicode constants and have the Playwright backend
  decode them back into names — a double translation in which the other
  vendor's dialect was the accidental interchange format. Each backend
  now translates the W3C name directly (Playwright is identity on every
  name but `Space`), and a parity test pins every vendor table to the
  closed key-name set, so a future key name missing from a backend fails
  the build instead of surfacing at runtime. Invisible when correct —
  the observable behavior of every press step is unchanged.

<!-- entry: sl-9nzc -->
- **Stepdef namespaces can be reloaded — the registry no longer collides
  with itself.** `require :reload` of any stepdefs namespace (and
  re-evaluating a `defstep` form at the REPL) threw
  `:stepdef/duplicate` against its own previous load; the only escape
  was `clear!`, which wipes the whole registry AND disconnects every
  costume — a reconnect tax on every stepdef edit. Registration now has
  redefinition semantics keyed on the SOURCE FILE: a stepdef from the
  same file replaces its previous registration (line-independent, so an
  edited file whose definitions shifted reloads cleanly), while a
  duplicate from a different file still throws — the cross-namespace
  vocabulary-collision protection is unchanged. The stated trade: a
  same-pattern duplicate within one file is now last-wins, exactly like
  defining the same `defn` name twice in one file.

<!-- entry: sl-vmc5 -->
- **The REPL can now provision a bare (non-costume) browser — the
  bare-caller path lazy provisioning was built for actually works.**
  Previously the only way to a live browser in the REPL was
  `connect-costume!`: both `repl/as` and `repl/run` failed
  `:browser/no-session` for costume-less subjects, even though the
  engine's lazy provisioning exists precisely for plan-less bare callers
  (the config docs' own words). Now `run` threads the loaded config's
  `:interfaces` to the engine (provision on demand, ephemeral cleanup at
  each scenario's end — runner semantics exactly), and `as` provisions
  the step's interface lazily into the named context, where
  `reset-ctxs!`/`clear!` close it. That last part also makes the cleanup
  vocabulary's `:closed` branch reachable from public REPL calls for the
  first time — and carrying that exposed a real defect, fixed here: the
  REPL close path could not close the driver shape provisioning actually
  produces (it read a `:webdriver-url` key real drivers don't carry, and
  its rebuilt map dropped the `:process`), so it now uses the full
  driver reap (session deleted, chromedriver process killed, Chrome
  closed). The `:browser/no-session` error also stops dead-ending: it
  names both remedies (`connect-costume!` for an authenticated costume;
  a config with `:interfaces` for a bare browser).

<!-- entry: sl-34fu -->
- **Costume chromedriver processes are now reaped — a long REPL session
  no longer accumulates one live WebDriver endpoint per
  connect/reconnect.** Every costume driver spawn leaked its
  chromedriver: scenario cleanup deliberately skips persistent
  capabilities, and `destroy-costume!` killed only Chrome — so drivers
  piled up unbounded in exactly the long-lived REPL sessions costumes
  are for (measured: +1 per connect, per auto-reconnect, and — the
  widest route — per worn-scenario provision), each one a live WebDriver
  endpoint still pointed at the costume's authenticated Chrome. The
  costume now owns its driver handle: each successful
  connect/reconnect/provision **replaces** the prior driver (session
  deleted, process killed; the debuggerAddress-attached Chrome is never
  touched — verified live), and destroy reaps the last one, so nothing
  drivable outlives the costume. Transparent auto-reconnect is
  unchanged and re-verified post-fix (driver killed mid-session → next
  command rebuilds and answers, process count still net one). One
  consequence made explicit in COSTUMES.md: one live driver per costume
  is the contract — driving one costume through two handles
  concurrently causes reconnect thrash (each reaps the other's driver),
  not breakage.

<!-- entry: sl-uv3r -->
- **The default `:sms-mock` (and `:sms-twilio`) registry entries now
  declare `:impl-key :sms` — builtin SMS steps get the protocol impl,
  not the factory's wrapper map.** Both SMS factories return
  `{:ok {:sms <impl> :from-number ...}}`, but their registry entries
  omitted `:impl-key`, so a project using the DEFAULT `:sms-mock` (no
  `setup.clj` override) stored the wrapper map as the capability and the
  first builtin SMS step threw `No implementation of method: :send! ...
  PersistentArrayMap`. Example 04 never saw it because its registry
  override returns the bare impl — the masking case. With `:impl-key`
  declared, provisioning extracts the impl (the bundle rides
  `:cleanup-handle`, unchanged for cleanup fns), and the entries' 
  `:provides` promises are now actually verified at provision time —
  `check-extracted-impl` only runs for entries with an `:impl-key`, so
  the SMS protocol declarations had been unenforced. `:sms-twilio`
  carried the identical defect and gets the identical fix; bare-impl
  override registries (example 04's shape) are unaffected — `:impl-key`
  is per-entry and their entries don't declare one.

<!-- entry: sl-sfb1 -->
- **Stepdef↔glossary validation now fires for renamed interfaces.** The
  tier-2 cross-check (verb exists, frame exists, args match) looked up
  glossary verbs by the stepdef's `:interface` NAME against the
  TYPE-keyed glossary map, so any project whose interface name differs
  from its type (`:storefront {:type :web}`) silently skipped the entire
  check for those stepdefs — no unknown-verb, unknown-frame, or
  args-mismatch errors, ever. Under the sl-s2oa model (Features, above)
  the gap cannot recur by construction: stepdef `:interface` metadata
  names the TYPE directly — instance names live in the step's `[name]`
  annotation, never in stepdef metadata — so the glossary lookup is
  direct and covers renamed projects with no resolution step. Issues
  carry `:interface-type` alongside `:interface` for shape stability;
  the two are equal under that model. Found and filed during the
  sl-0gfc collision verification. **Note:** a project that renames
  its interfaces may see NEW validation errors on upgrade — that is the
  fix working; the errors were always true and previously invisible.

<!-- entry: sl-ur58 -->
- **A comment between a step and its table's first row now parses.** Upstream
  Gherkin treats standalone comment lines as legal anywhere, and comments
  between table rows already worked — but this one position ended the step
  at the comment and orphaned the table into `:unexpected-token` errors.
  Comment and blank lines there now attach the table to the step, matching
  upstream. Canonical formatting emits the comment above the table's first
  row (following-node attachment, as everywhere else). This unlocks the
  natural provenance spot: a `# why:` annotating the table it sits on. The
  fuzz generator now sprinkles this position too.

---

# Changelog: 0.5.4

**Release Date:** 2026-08-05

The theme of this release is **keep every promise the docs already made**.
The features our shipped docs called "planned" now exist: screenshot- and
console-on-failure as a config line, After-hook attachments that ride the
report surfaces, third-party jars dropped into `sl/lib/`, and a Playwright
backend you can wire without a Clojure toolchain — now honoring the same
step contract as WebDriver, with the real differences stated in a table
instead of implied away. The formatter keeps the oldest promise of all:
`sl fmt` now preserves every comment. The same promise-keeping applies to
run artifacts — a planning failure writes the HTML report it earned instead
of leaving a previous run's green one lying, a failed teardown warns the
*next* run that the environment may be dirty, and a setup plan with a dead
group exits 2 instead of green-lighting a group that tested nothing. Two
new pages pin the contracts down: the execution lifecycle on one page, and
the project-contents contract with a verification appendix citing code for
every table row. One breaking change worth reading before you upgrade:
everything ShiftLefter now lives under `sl/` — migration is one `git mv`.

## Stats

```
2120 tests, 7454 assertions, 0 failures (./bin/kaocha unit — the fast suite)
17 tests, 77 assertions, 0 failures (./bin/kaocha daemon — warm-path shell E2E through the built jar)
11 tests, 41 assertions, 0 failures (SHIFTLEFTER_LIVE_WEBDRIVER=1 ./bin/kaocha e2e — live-browser example validators)
Cucumber compliance: 46/46 good, 11/11 bad (100%)
Verified against Chrome 150 / ChromeDriver 150
```

## Features

<!-- entry: sl-3q9r -->
- **Screenshot-on-failure is now a config line.** The most-written After
  hook in the industry graduates to declarative configuration:
  `:runner {:capture {:screenshot :on-failure :console :on-failure}}`
  (CLI mirror `--capture screenshot=on-failure,…`; policies `never` —
  the default, capture is always opt-in — `on-failure`, `every-step`).
  `on-failure` captures at the failing step's *instant*, before After
  hooks, across **all** live browsers — Bob's screen may be what explains
  Alice's failure. `every-step` is the film-strip debug run, one capture
  per step following the acting subject. Console capture is a
  step-grained drain: under `every-step` each artifact holds exactly that
  step's log entries (plus a tail sweep for anything after the last
  step); under `on-failure` one whole-scenario log, drained only when
  red. Kinds are adapter-contributed — the browser provides `screenshot`,
  `console` (Chrome/Edge), `page-source`; a typo'd kind gets a warning,
  never a silent no-op. Capture cost is recorded per-artifact and never
  pollutes step durations. And the evidence contract is deliberately
  harsh: if capture infrastructure fails on an otherwise-green scenario,
  the scenario goes `:error`, capture-attributed — a green run whose
  promised evidence silently vanished would be green with missing
  evidence. Hand-rolled screenshot Afters: delete the hook, set the
  config line.

<!-- entry: sl-l9nj -->
- **Attachments: hooks can now hand evidence to the report.** An After
  hook's return value goes live: return
  `{:attachments [{:attachment/kind :screenshot :attachment/path "…"} …]}`
  and the refs ride the scenario envelope into every report surface —
  inline images and excerpt blocks in the HTML report, Jenkins
  `[[ATTACHMENT|…]]` lines in JUnit system-out, plain `attachment:` lines
  under console failures. Hook payloads gain `:artifacts-dir`, a
  pre-created per-scenario directory inside the run's results dir, so
  hooks have a sanctioned place to write. The contract is strict on
  purpose: nil means no claim, anything malformed (a typo'd key, a bad
  ref) makes the scenario `:error` — and a claimed file that's missing
  renders as a visible failed capture rather than vanishing. Attachments
  are references, never inline bytes; runs that claim nothing produce
  byte-identical output everywhere. The HTML report also *joins the run
  directory*: the primary `report.html` now lives in
  `results/<stamp>/` beside `attachments/`, making each run dir a
  self-contained, zippable artifact, while the configured `--html` path
  keeps working as an end-of-run copy — written atomically, so the HTML
  copy specifically can no longer be read torn under concurrent runs
  (JUnit XML writes are unchanged). The declarative capture policy built on
  this contract shipped in the same release (see the entry above).

<!-- entry: sl-yvaz -->
- **Teardown failures now tell the truth — twice.** When a setup group's
  `:stop` throws, the run no longer buries it in a log line: the failure
  lands on the group's result, prints an stderr notice, and (under
  `--edn`) emits a small supplementary diagnostics form after the group's
  summary — while the exit code stays what the tests earned (a leaked
  resource never flips a green run red). And because the person who
  suffers is whoever runs the suite NEXT, the failure is also written to
  `.shiftlefter/dirty-teardown.edn`: the next run announces "previous run
  teardown for group X failed — environment may be dirty" and clears the
  marker (a `--dry-run` announces but leaves it). EDN consumers: stdout
  is a form *stream* — read all forms, skip shapes you don't recognize
  (documented in docs/CI.md). Also: the `:start`-threw planning error now
  teaches partial-start ownership (anything your `:start` began is yours
  to clean — wrap your own try/catch), and the resolved config map that
  `:start` receives is now spec'd (`::config`, read-only by contract).

<!-- entry: sl-6hsd -->
- **Planning failures now produce the HTML report they earned.** When a run
  exits 2 at planning (undefined or ambiguous steps, arity mismatches, a
  hooks error), `--html` no longer stays silent — worse, it no longer
  leaves a *previous* run's report sitting there lying. The report is
  written with a PLANNING FAILED header, zero scenarios, and a full
  diagnostics section matching the console: undefined steps with their
  location and their grammar hint (the s04n teaching line, rendered under
  the step), ambiguous steps with their candidate stepdefs, invalid arity
  with expected/got, and generic planning errors. The EDN data island
  carries the same machine-readable diagnostics (`:summary :status
  :planning-failed`, `:scenarios []`), so island consumers can distinguish
  "nothing ran" from "everything passed" without heuristics. Scope note:
  parse/config-stage failures (before binding) still report on
  console/stderr only.

<!-- entry: sl-lkla -->
- **The setup.clj plan is now linted before anything runs.** Three
  plan-shape checks fire at plan time — and dry-run sees the same
  verdicts, because plan-shape is static. A group whose `:features`
  match no feature files is a planning **error** (exit 2): a dead group
  would report green while testing nothing. Two groups sharing a
  `:label` is a planning **error**: labels are reporting-load-bearing —
  a group is a run-scope, and its name must be unique. The same feature
  file declared by two groups is a **warning**, deliberately not an
  error: it runs once per group, and same-feature-under-two-registries
  is a legitimate matrix pattern we won't foreclose. Warnings print one
  stderr line each; under `--edn` they ride a supplementary
  `{:diagnostics {:setup-lints [...]}}` form before the group summaries.
  If your setup.clj carried a dead group or a duplicated label, runs
  that used to "pass" will now exit 2 — that green was a lie.

<!-- entry: sl-bav1 -->
- **Description prose that opens with a step keyword now gets named for
  what it is.** Gherkin descriptions end at the first
  `Given/When/Then/And/But` (or `*`) — Cucumber-identical, and staying
  that way — so natural BDD prose like
  `When the gateway degrades, the client retries.` inside an intended
  description silently becomes a real (and usually undefined) step. The
  undefined diagnostic now recognizes the shape — no quoted literal, no
  `{...}` locator, no `Object.ref`, ending in sentence punctuation — and
  teaches the rule: move prose above the first step, or rephrase it so it
  doesn't open with a step keyword. Teaching-only: there's no did-you-mean
  because no repair makes prose match; annotated steps (`[:web] …`) never
  draw it — an annotation is step-evidence. The companion parse error for
  prose *after* an accidental step explains itself now too:
  `Unrecognized line (not a keyword or step): … — if this was description
  prose, descriptions end at the first step keyword.` EDN report
  consumers see the new `:type :grammar/prose-as-step` in the stable
  `:hint` envelope.

<!-- entry: sl-38fy -->
- **Bare words in a value slot now get a quote-the-tail did-you-mean.**
  `fills {:id "code"} with the SMS code` used to report undefined with no
  guidance; the diagnostics now recognize a near-miss frame — quoting the
  trailing words produces a step that actually matches — and teach the slot
  grammar: *Value slots take a quoted literal ('...') or a binding
  ({Name}). Did you mean: … with 'the SMS code'?* The hint is
  rematch-gated (it only fires when the repaired step really matches, so
  ordinary undefined prose stays hint-free), quotes the corrected step
  with locators untouched, and doubles any interior apostrophes per the
  0.5.4 literal grammar (`with the user's code` → `'the user''s code'`).
  Suffix tails only for now: values before `from`/`and`-style
  continuations (e.g. `selects X from …`) don't hint yet. EDN report
  consumers see the new `:type :grammar/bare-value` in the stable `:hint`
  envelope.

<!-- entry: sl-lg8v -->
- **`sl fmt` now preserves every comment.** The canonical formatter emits
  interior comments — between steps, between scenarios, between table and
  Examples rows, and trailing at end of file — by attaching each comment to
  the construct that follows it and placing it at that construct's canonical
  indent. `fmt --write` no longer skips comment-bearing files, and the
  comment-loss guard from 0.5.1 stays wired as a permanent backstop that now
  refuses a write only if a formatter regression would drop a comment.
  One visible shift in `fmt --check`: files that used to sit in the
  CI-neutral `:comments` state now report NEEDS FORMATTING when their
  comments aren't in canonical position — because the formatter can finally
  fix them; one `--write` clears it. Known attachment rule worth knowing:
  a comment binds forward, so a note after a scenario's last step belongs
  to the next scenario — write trailing notes above the step they describe.
  Comment-preservation and idempotence are fuzz-covered (comments are now
  sprinkled through the generative corpus).

<!-- entry: sl-jlqz -->
- **Apostrophes inside quoted literals: write them doubled.** `''` inside a
  nonempty literal is one literal `'` character, SQL-style — so English UI
  text is now assertable as a plain literal:
  `should see 'Don''t have an account?'`, `fills Login.code with 'don''t'`,
  and `''''` is the one-character literal `'`. The rule holds in every
  quoted slot (value, text, location, key — `presses ''''` presses the
  apostrophe key), needs no backslash semantics, and stays unambiguous with
  several quoted slots in one step because the doubled pair must be
  adjacent. The bare empty literal `''` is still refused (emptying a field
  wants `clears`, and the hint still says so). The undefined-step
  diagnostics learned the rule too: an undoubled apostrophe inside a
  literal (`'Don't stop'`) now draws a hint teaching the doubling — with
  the exact corrected step when doubling it produces a real one — and the
  unbalanced-quote hint gained doubling repairs (`'friends' page'` →
  did-you-mean `'friends'' page'`). Grammar law updated in the
  grammar-markers decision table; the `sl agent-doc grammar` topic
  documents the rule.

<!-- entry: sl-5ee5 -->
- **Third-party libraries without build tooling: drop jars in `sl/lib/`.**
  Steps, hooks, and custom adapters can now use libraries beyond the bundled
  classpath — the flagship case is a JDBC driver for a database-seeding hook.
  Every jar in the project's `sl/lib/` directory rides the classpath of every
  run: the cold path and the warm daemon alike, framework jar first, and the
  daemon restarts itself when the lib set changes, exactly as it does on a
  framework upgrade. No dependency resolution — bring resolved jars (a
  library plus its dependencies' jars); for transitive resolution from
  `deps.edn` coordinates, that remains the Clojure-CLI tier. User jars are
  user code: same in-process trust as your `load-file`'d step definitions.
  See docs/INSTALL-TIERS.md § Third-Party Libraries.

<!-- entry: sl-tnx -->
- **Playwright nested addressing is now one browser round trip per address
  segment — and live-verified for the first time.** Resolving
  `Dashboard.featured[1].title` on the Playwright backend used to cost one
  browser call per candidate per segment; the new `:fast` strategy
  (default) fuses the scoped query and the nested-instance filter into a
  single script, matching the WebDriver backend. The previous per-candidate
  path is retained as `:pruning :safe` — Playwright's auto-retrying
  Locators all the way down, stale-safe across re-renders, at the old
  O(candidates) cost. Both strategies now pass the full nested-addressing
  example suites against a real browser (neither had ever been
  DOM-verified). `:fast` results are point-in-time element snapshots —
  re-resolve after navigation; the staleness contract, the knob, and the
  new backend where-they-differ table live in docs/INSTALL-TIERS.md
  § Backend differences. The knob is Playwright-meaning only: the WebDriver
  backend is always fused, having no retrying-locator concept to trade.

<!-- entry: sl-wgnw -->
- **The Playwright backend now works without a Clojure toolchain — three
  jars in `sl/lib/` and you're wired.** Verified end to end through the
  installed launcher, cold path and warm daemon alike: drop
  `playwright`, `driver`, and `driver-bundle` (same version) into your
  project's `sl/lib/` and set `:adapter :playwright` — the driver bundle
  self-extracts its runtime from the classpath jar, and browsers auto-fetch
  once per user on first use. docs/INSTALL-TIERS.md § Using Playwright now
  carries both wiring paths with exact version-pinned download links, and
  the config-load "not bundled" error names the `sl/lib/` remedy alongside
  the `deps.edn` one. (Honest sizes while we're at it: ~200MB driver bundle
  + one-time browser downloads, vs the 43MB framework jar.)

<!-- entry: sl-gd16 -->
- **A documented home for "why": the `@because=` / `# why:` convention.**
  Scenarios and glossary entries may carry an opaque why-pointer to the
  ticket, decision, or conversation that motivated them — a
  `@because=CHK-4412` tag, or a `# why:` comment on the line above the
  step or scenario it explains (`;; why:` in EDN glossaries). Nothing
  parses, validates, or resolves the pointer; both forms round-trip
  through `sl fmt` like any tag or comment. A convention, not a schema —
  documented in the authoring topic (`sl agent-doc authoring`) so
  rationale accretes in the corpus now, with promotion to a first-class
  annotation deferred until use-case artifacts land. No parser or runner
  changes.

<!-- entry: sl-q8al -->
- **The SVO demo now teaches the real verb model — and ships the missing
  macro worked example.** `examples/svo-demo` no longer declares domain
  actions (`:login`, `:logout`, `:search`, `:filter`) as interface verbs —
  the anti-pattern the three-tier vocabulary model retired: built-in
  interface verbs / **macros** for domain actions / rare defstep-backed
  verbs. The demo's verbs glossary is deleted (built-ins cover it, which
  is the normal case), and in its place the example gains a macro registry:
  `Alice logs in +` contracts fill/fill/click through `macros/login.ini`,
  with the fixed-text limitation stated honestly (named-argument macros
  are the planned upgrade). The packaged agent-docs examples topic
  (`sl agent-doc examples`) swaps its verbs-glossary section for the
  macro-registry INI worked shape it never had, and the demo's feature and
  stepdefs move to the single-quote literal convention. No behavior
  changes — this is the shipped teaching surface catching up to the ruled
  vocabulary model.

<!-- entry: sl-fhuj -->
- **The execution lifecycle is now one page.** `docs/lifecycle.md` tells
  the whole run story in order: every run is two phases — plan, then
  execute — with planning as a compilation (typed step resolution,
  per-scenario hook firing lists, binding producer checks, plan-shape
  lints) and `--dry-run` as that phase exposed. The execution weave is
  stated once, as a timeline: groups in declared order, `:start`,
  per-scenario Before hooks → capability provisioning → steps → After
  hooks → capability cleanup, `:stop` in a finally. A failure table
  walks every stage — what a `:start` throw, a hook error, a
  capture-infrastructure failure, or a `:stop` throw each does to the
  run — plus where parallelism applies and where capture fires in the
  weave. Agents get the same model from the jar:
  `sl agent-doc lifecycle`.

<!-- entry: sl-hivf -->
- **The project-contents contract is now one page.**
  `docs/project-layout.md` states both halves of the layout contract:
  everything a project brings to ShiftLefter (how required, where it's
  discovered, what overrides — with every table row verified against the
  code the page ships with, and planned changes annotated rather than
  omitted), and everything ShiftLefter writes back (`results/` run dirs,
  configured reports and their two path-anchoring rules, the full
  `.shiftlefter/` operational inventory — with the honest git guidance:
  add `.shiftlefter/` to your `.gitignore`, because the framework only
  self-ignores `results/` and the wardrobe). The page also carries the
  user-code doctrine in public form: three named attachment points plus a
  reserved fourth, config is data-only, no orchestration DSL ever, and
  the already-locked data contracts any future reporter/observer
  mechanism must honor. Agents get the same contract from the jar:
  `sl agent-doc layout`.

## Breaking Changes

<!-- entry: sl-fcjm -->
- **Everything ShiftLefter now lives in `sl/` — config discovery narrowed
  to `sl/shiftlefter.edn` only.** `<project>/shiftlefter.edn` at the
  project root is no longer discovered (the two-location rule and its
  ambiguity diagnostic are gone); `-c FILE` remains the escape hatch for
  exotic layouts. The corpus follows the config: config-declared paths
  resolve from `sl/`, so the defaults are `sl/steps/`, `sl/glossary/`,
  and the new `sl/features/` — bare `sl run` now runs the project's
  feature corpus (`[:runner :features]`, default `["features/"]`) when no
  paths are given and no `setup.clj` owns the plan. Glossaries at the
  conventional paths (`sl/glossary/subjects.edn`,
  `sl/glossary/verbs/<type>.edn`, `sl/glossary/intents/`) are discovered
  automatically when `:glossaries` is unset; an explicit `:glossaries`
  (including `nil`) is used as written. Run results move with the config
  root to `sl/results/`. **Migration is one move:**
  `mkdir sl && git mv shiftlefter.edn setup.clj hooks.clj features glossary steps sl/`
  (move the ones you have; skip the rest).

<!-- entry: sl-1qet -->
- **Removed the REPL surfaces + session-store subsystem**: `mark-surface!`,
  `unmark-surface!`, `surface?`, `list-surfaces`, `reset-surfaces!`, and the
  EDN session-handle store. This was a pre-costume design that never worked:
  the persisted handles were written with nil fields off real browser
  records, and nothing ever read them back — a write-only subsystem whose
  persist half was a bridge to nowhere. **The workflow lives on:** a browser
  session that survives REPL reset/clear is a costume — use
  `connect-costume!` + `repl/as`, which persist across JVM restarts too
  (more than surfaces ever delivered). See docs/COSTUMES.md.

## Fixes

<!-- entry: sl-g71p -->
- **Macro `:registry-paths` now accepts directories — as the docs always
  promised.** `docs/extending-vocabulary.md` showed
  `:registry-paths ["macros/"]`, but the loader slurped every path as a
  file and a directory failed with a read error. The docs' example is now
  true: a directory entry loads every `.ini` file under it, recursively,
  in sorted path order (so duplicate-key detection is deterministic);
  non-INI files are ignored; direct file paths behave exactly as before,
  and the two forms can be mixed in one list. A nonexistent path — file
  or directory — still fails loud with `:macro/file-not-found`:
  configured macros never go silently missing.

<!-- entry: sl-tss7 -->
- **REPL `reset-ctxs!` and `clear!` now actually close ephemeral browser
  sessions — and never touch a worn costume.** Previously the close path
  silently no-oped on the record shapes real provisioning produces (the
  session id lives inside the wrapped driver), leaking Chrome/ChromeDriver
  process trees while reporting `:closed`; connected costumes survived
  cleanup only by riding that same no-op. Cleanup now unwraps the capability
  to its raw driver and issues the real close, deliberately skips
  `:persistent` (costume) capabilities — only `destroy-costume!` kills a
  costume's Chrome — and closes every `:web` capability in a context, not
  just the first. The returned actions tell the new truth: one action map
  per capability with a `:capability` key, and `:action` one of `:closed`,
  `:skipped-persistent`, `:close-failed` (with the `:error`), or `:none` —
  code pattern-matching on the old always-`:closed` reports should switch
  on these.

<!-- entry: sl-xbm -->
- **A costume worn by two targets is now refused on the default path too —
  before any browser attaches.** A costume is a device-local browser profile:
  single-wearer, single-interface. The lazy provisioning path already refused
  duplicate `:wears`; the eager (default) path silently attached two WebDriver
  sessions to one Chrome when the wearers landed on different interfaces,
  because each interface provisions in its own parallel future. A pure
  pre-pass now rejects duplicate `:wears` across all targets before
  provisioning starts, and the error says where shared identity actually
  lives: in the application (an account/context), not the browser profile —
  "the same user on web and mobile" is two costumes plus one account. A
  refusal made honest, not a new restriction.

<!-- entry: sl-yynw -->
- **A failed capture's `.err` pointer now names a real file.** When a
  capture fn threw, the attachment ref recorded the miss with a path
  ending in `.err` — but nothing wrote that file, so the one pointer the
  HTML report printed led nowhere. The engine now writes the error
  message to that path at the moment of failure: the artifacts dir holds
  the evidence the ref promises. Report rendering is unchanged — errored
  captures still lead with the failure message on every surface.

<!-- no-entry: sl-5dhn (blog scaffold in the shiftlefter.com repo; no SL shipped surface — deploy is vvxu) -->
<!-- no-entry: sl-6uvs (VPS-to-GH-Pages migration of shiftlefter.com; infra move surfaced by the 5dhn close, no SL shipped surface) -->
<!-- no-entry: sl-85ls (cross-repo doc drift sweep: wiki/runbook/process edits only; runbook and wiki are not in the jar, no shipped surface — Chair pre-ruled 08-03) -->
<!-- no-entry: sl-c76w (validation exercise: paired REPL costume deep pass against real sessions; findings filed to 0.5.5, no shipped-surface change — Tower, 08-05, closing the parallel-close guard gap) -->
<!-- no-entry: sl-wp5k (Chair sitting: 85ls D4 discrepancy-table disposition — rulings, bead filings, and doc homes only; no shipped surface) -->
<!-- no-entry: sl-tnjq (release-process bead: Herald release pass — changelog drain + drift sweep; chartered doc fixes ship under their originating beads' entries) -->
<!-- no-entry: sl-8k5m (release-process bead: cut & tag v0.5.4) -->

---

# Changelog: 0.5.3

**Release Date:** 2026-07-28

The theme of this release is **the framework tells the truth**. Most of the
fixes below share one shape: somewhere, a green run was quietly lying — a
failed browser cleanup reported `:closed`, a failed setup group emitted
nothing in `--edn` mode, a diagnostic hint suggested a repair that would
corrupt the step, the costume docs taught a flow that fails at the first
call. Each of those surfaces now says what actually happened. The rest of
the release hardens the same promise from the other side: machine-verified
doc examples, offline-deterministic examples, a bytecode-enforced Java
floor, and drift guards that make the next lie a loud test failure.

## Stats

```
2012 tests, 6939 assertions, 0 failures (./bin/kaocha — the default suite; unit-only: 2003 tests, 6930 assertions)
Cucumber compliance: 46/46 good, 11/11 bad (100%)
```

## Fixes

<!-- entry: sl-lbrc -->
- **A failed capability cleanup no longer disappears on a green run.** When
  an adapter's cleanup fails (e.g. a browser quit that leaves the process
  tree running), the run now says so: console mode prints a stderr block
  ("Capability cleanup failures (resources may have leaked):" with scenario,
  capability, adapter, and error), and `--edn` summaries carry the same
  entries under `:diagnostics :cleanup-failures`. Warn-level only — the exit
  code is unchanged (a leaked resource does not flip a green run red).
  Previously the failure was recorded on the raw result and logged, but
  nothing user-facing showed it. Scope: per-scenario ephemeral capabilities
  (the runner surface); persistent-capability lifecycle belongs to the
  REPL/daemon surfaces and is tracked separately.

<!-- entry: sl-c6wk -->
- **Capability cleanup no longer reports `:closed` when the adapter said
  otherwise.** The browser adapters never throw on a failed quit — they return
  a structured `{:error …}` — but the cleanup path only mapped *thrown*
  exceptions to `:close-failed`, so a failed browser close (including the
  wrong-shape case that leaks chromedriver + Chrome process trees) read as
  `:closed` in the scenario's `:capability-cleanup` result. The adapter's
  return value is now inspected: a returned error maps to `:close-failed`
  carrying the adapter's message, and both failure paths emit a log warning.
  Exit codes and reports are unchanged (cleanup outcome never affects scenario
  status).

<!-- entry: sl-0n6t -->
- **Exit-2 `--edn` summaries no longer omit the planning error.** Nine
  planning-failure paths (config load, intents load, feature discovery, parse,
  tag filter, step load, setup load, setup CLI-path check, and the
  programmatic `:max-parallel` boundary) produced `:planning nil` in machine
  output — `:run/status :planning-failed` with no error detail at all; only
  hooks errors and the adapter-availability gate carried theirs. All nine now
  ride `:planning :errors` with `:type` and `:message`, and the aggregate
  families (intents/parse/step-load) keep their per-file/per-entry sub-errors
  nested under `:errors` with `:path` attribution — recursively projected, so
  record-bearing keys stay out of the EDN. Human-facing stderr output is
  unchanged on every path.

<!-- entry: sl-1h03 -->
- **A setup group whose `:start` fails now speaks in `--edn` mode.** Before,
  a group whose setup.clj `:start` threw (or returned an invalid shape)
  printed a console-only stderr line — in EDN mode that group emitted
  *nothing*: the process exited 2 while stdout's only EDN form could be a
  healthy sibling group's `:run/status :passed`, so a machine consumer
  concluded the run passed. Both failure branches now emit the standard
  exit-2 planning summary, with the error under `:planning :errors` carrying
  `:type` (`:setup/start-threw` / `:setup/invalid-start-result`), a new
  `:group` key naming the failing group, and a real `:message` (the
  invalid-shape error previously had none). Console stderr output is
  unchanged; a healthy group following a failed one still emits its own run
  summary alongside.

<!-- entry: sl-4tsi -->
- **Observe-plane bus lifecycle: closed once per invocation, on every exit
  path.** The bus is created once per run but was closed inside the per-group
  execute/report stage — in multi-group setup.clj runs, group 1's close killed
  the shared bus and groups 2..N silently dropped every event (`:test-run/*`,
  `:scenario/finished`, `:step/svo`); dry-run and planning-failure exits never
  closed it at all. The close is now hoisted to a suite-level `finally` in
  `execute!` covering all exit paths, and the per-group/crash-path closes are
  removed. Also: the drop warning no longer claims "bus full" when the cause
  was a closed bus. This is the *lifecycle* half of sl-4tsi; the drop-at-close
  race half shipped with sl-q9wp. No user-facing surface changes — production
  has no bus subscribers yet (first one: sl-yia).

<!-- entry: sl-3ub4 -->
- **`--edn` output is now immune to ambient printer state.** Envelope
  serialization (the `--edn` summary, the HTML report's data island, and
  rendered error values) goes through one pinned writer seam
  (`reporter/pr-edn-str`) instead of bare `pr-str`/`prn` under whatever
  printer bindings the launch context supplies. Previously, clojure.main's
  `*print-namespace-maps*` binding (live in every CLI run) printed a map
  whose keys share a digit-leading namespace — e.g. a programmatic
  `(keyword "0a" "A")` in a step's `ex-data` — as the `#:0a{...}` literal
  that EDN readers reject, breaking the machine-readable round-trip
  guarantee; a REPL-bound `*print-length*` would have silently truncated
  output. This was also the root cause of the suite's dominant flake (the
  reporter round-trip properties, ~7.5% of runs — sl-tkrx evidence), which
  is retired by the same change.

<!-- entry: sl-y9gn -->
- **The double-quote grammar hint no longer misfires on EDN locators — and
  no longer suggests corrupted ones.** Brace-delimited `{...}` slots (EDN
  locators, `{binding}` tokens) are now opaque to the undefined-step grammar
  scanner. This kills two lies: (1) a step whose only double quotes sit
  inside a locator (e.g. `clicsk {:css "button.submit"}` — a verb typo) no
  longer gets the irrelevant "literals use single quotes" hint pointing at
  the wrong repair; (2) when prose double quotes and a locator coexist
  (`fills {:id "code"} with "1234"`), the did-you-mean previously converted
  the *locator's* quotes too — suggesting `{:id 'code'}`, which reads as a
  symbol and targets an element that doesn't exist. Repairs now edit only
  prose positions, so the suggestion preserves the locator verbatim:
  `fills {:id "code"} with '1234'`. Genuine prose-quote hints and their
  did-you-means are byte-identical to before; an unclosed `{` fails open to
  the previous scanning behavior.

<!-- entry: sl-p6en -->
- **The costume docs no longer teach a flow that fails.** `init-costume!`
  has been launch-only since the persistent-browser rework — it opens
  Chrome for you to log into and returns `{:status :launched …}` with no
  browser attached; driving steps requires `connect-costume!` first.
  `docs/COSTUMES.md`'s REPL walkthrough claimed the old connected-with-
  browser return and skipped the connect step entirely (as did every flow
  in `docs/MANUAL-TEST-CHECKLIST.md`), so following the docs verbatim
  failed at the first `repl/as` call. Both now show the real
  init → log in → connect → drive sequence with return values copied from
  the current docstrings. Also removed a stale generated API page for the
  renamed-away `subjects` namespace.

<!-- entry: sl-gcdu -->
- **Dead-end `:playwright` config now fails at config load with instructions,
  not at run.** Playwright is supported but deliberately not bundled (the
  driver alone is ~163MB); previously `:adapter :playwright` without the dep
  loaded clean and only failed per-scenario at browser provisioning. Config
  load now probes the classpath and fails as a planning error (exit 2, carried
  in the `--edn` summary as `:config/adapter-not-bundled`) naming both
  remedies: use `:etaoin` (bundled), or add
  `com.microsoft.playwright/playwright` to your deps.edn. The packaged
  builtins reference now marks `:playwright` as not bundled with the same dep
  pointer, and docs/INSTALL-TIERS.md § Using Playwright covers the full wiring
  story (browser download, skip + `:channel`, per-user caches).

<!-- entry: sl-34ec -->
- **Location literals now resolve against `:base-url` — previously crashed.**
  `opens the browser to '/login'` and `should be on exactly '/login?a=1'`
  resolve schemeless literals per RFC 3986 relative resolution against the
  interface's `:config :base-url` (`'/login'` root-relative, `'Login'` →
  `…/Login`; absolute URLs pass through untouched; under RFC 3986
  `'example.com'` is a relative path, resolving to `…/example.com`). This
  makes relative exact-URL expectations expressible without hardcoding a
  host, and captured `{token}` paths get the same treatment (captured
  absolute URLs — the magic-link flow — are unchanged). Previously a
  schemeless literal reached WebDriver verbatim and died with a raw
  "invalid argument" error. Without a `:base-url`, navigation now fails
  with the structured `:intent/missing-base-url` teaching error (same as
  bare refs — one condition, one error) and `should be on exactly` keeps
  `:not-a-full-url`; both messages name the two exits: add `:base-url` to
  the interface config, or use an absolute URL. The region assertion
  (`should be on '/secure'`) is deliberately untouched: region comparison
  discards scheme and host by design, so it never needs a base URL.

<!-- entry: sl-k7gl -->
- **Matcher patterns using `(?x)` COMMENTS mode no longer crash on
  commented-out groups.** Named-group detection now uses the JDK's own
  `Pattern.namedGroups()` (available since Java 20; the floor raise to 21
  makes it safe to require), replacing the hand-rolled pattern scanner shipped
  in 0.5.2 as a Java-11-floor shim. The shim couldn't see `(?x)` comments, so
  a pattern like `(?x)(?<n>\d+) # (?<old>y)` reported the commented-out group
  as a phantom binding and the capture then crashed with
  `IllegalArgumentException` at exec time; the real API only reports groups
  that exist. Internal API note: `shiftlefter.stepengine.bindings/named-group-names`
  is removed (nothing outside the shim's own tests consumed it).

<!-- entry: sl-2sj6 -->
- Strict/warn `:unknown-object` validation no longer false-positives on
  free-text object slots: `should see the title 'Welcome'` under
  `{:unknown-object :strict}` used to fail with "Invalid intent reference:
  Welcome" because the title text was parsed as an intent ref. The title slot
  is now `:object-kind :text` (quoted literal or `{binding}` token, captured
  with its quotes and normalized by the engine), so quoted text — even
  ref-shaped text like `'Dashboard.home'` — is literal, always, and strict
  mode stays silent. `{token}` in the title slot resolves through the data
  plane end to end; an unquoted bare word no longer matches any stepdef
  (reserved for 0.6 named-text).

<!-- entry: sl-7kct -->
- Config lint now warns on top-level `:webdriver`/`:webdriver-url` — accepted
  but ignored by `sl run`, so they get the same unknown-key warning as any
  other unread top-level key (warning only, never blocks). Rides with the
  rest of the 0.5.2 audit P3 sweep: `:intents` added to the glossaries
  config spec, an unprovisioned-capability NPE in a Before now says *why*
  (Befores run before provisioning), and hooks doc/test nits.

<!-- entry: sl-w9wp -->
- `sl orient --edn` no longer derives `:webdriver-url` into the projection's
  `:config` from a top-level `:webdriver {:host :port}` map — the vestigial
  normalization (and its silent invalid-map error) was removed; `sl run`
  already ignored both keys and config lint warns on them.

## Added

<!-- entry: sl-cdrg -->
- **Costume custody floor.** `sl costume list` now shows each costume's
  last-connected age (`connected 45d ago`) — staleness made visible, so a
  standing credential nobody has touched in weeks announces itself; costumes
  without a recorded stamp show `-`. Alongside it, the costume docs
  (`docs/COSTUMES.md` and the agent doc) gained an explicit custody section:
  a costume is as sensitive as your own browser profile, exclude the
  wardrobe from sync/backup tools, a worn costume's session is drivable by
  any local same-user process while running, and destroy costumes you stop
  using. The docs also correct the costume definition: a costume is
  brought-state — what the actor brings from outside the system under test —
  with authenticated session state as the flagship example, not the genus.

<!-- entry: sl-y3wd -->
- **Run outputs now have a standing home: `results/<stamp>/`.** Every
  executing run in a configured project creates
  `<config-root>/results/<stamp>/` (`sl/results/…` under the `sl/` layout) —
  stamp is the run's UTC start time compacted filesystem-safe
  (`20260712T153042Z`; a same-second rerun gets a short run-id suffix). The
  directory is created empty today — run outputs now have a standing,
  stamped, browsable home. The results root ignores itself (the
  framework writes `results/.gitignore` containing `*`), so nothing appears
  in `git status`. Ad-hoc runs without a `shiftlefter.edn`, dry runs, and
  planning failures create nothing. `--junit-xml`/`--html` are unchanged —
  they still write exactly where you point them.

<!-- entry: sl-v85e -->
- **Gherkin description prose now reaches reports.** Pickles carry
  `:pickle/description` (scenario) and `:pickle/feature-description` as
  optional keys, and the HTML report renders the prose under the feature
  heading and inside each scenario's expanded body. Feature files that
  narrate themselves finally look that way in the run artifact. Absent
  descriptions render nothing; cucumber-compat NDJSON output is unchanged
  (the pickle projection is unaffected); JUnit and console output are
  byte-identical to before.

<!-- entry: sl-s04n -->
- **Undefined steps now teach the grammar at the point of failure.** When a
  step matches no stepdef, the diagnostic classifies why and says so:
  double quotes (`fills X with "x"`) → "ShiftLefter literals use single
  quotes; double quotes are reserved"; an unbalanced single quote → says so;
  an empty literal (`with ''`) → points at the verb, naming your element when
  it can ("to empty a field: clears Login.code");
  quoted ref-shaped content (`clicks 'Login.code'`) → glossary-aware — if the
  unquoted name resolves, "Did you mean Login.code (unquoted)?", else the
  slot-takes-refs-not-literals law. Classification is repair-and-rematch:
  when repairing the text produces a real match, the hint quotes the exact
  corrected step. At most one hint per step; apostrophes inside words never
  trigger it; a `"` inside a single-quoted literal stays an ordinary
  character. Hints appear in the console report (`hint:` line under the
  undefined step) and in the EDN report as a machine-readable `:hint` map
  (`{:type :grammar/… :suggestion … :repaired-text … :did-you-mean …}` —
  stable envelope; the `:type` set grows as new hint families land). The
  interface-annotation suggestion ("No [:sms] stepdef matches, but [:web]
  does.") now also renders in both reports — it was previously computed but
  never shown.

<!-- entry: sl-c9qj -->
- **`sl agent-doc examples` — worked file shapes, packaged in the jar.** A new
  generated topic carrying complete, runnable examples verbatim from the
  shipped `examples/` projects: a minimal `shiftlefter.edn`, the canonical
  glossary layout (subjects + project verbs), intents from a single-elements
  shape up to parent-anchored nested collections with a semantic address
  (`Dashboard.featured[1].rating.stars`), two feature files (intent refs and
  the raw-locator fallback), and a `setup.clj`. Each snippet names its source
  file; the topic is generated from those files (`clojure -T:build
  gen-examples`, chained into `gen-agent-md`) with a drift-guard test, so the
  worked examples cannot rot. The installer breadcrumb, `sl agent-doc
  overview`'s reading list, and `sl orient`'s no-config bootstrap all point at
  it — a jar-only agent no longer has to unzip the jar to learn the EDN shapes.

<!-- entry: sl-fcw0 -->
- **`sl agent-doc grammar` — the authored grammar, fully documented.** Every
  marker in step text accounted for in one reference: the one rule (quoted =
  literal, unquoted Capitalized = glossary, `{name}` = carried value, `/…/` =
  pattern), each slot kind's admitted forms, the complete W3C key-name list,
  every reserved form (double quotes, bare words in text slots, chords) — and
  a reader's explanation of why `should be on` takes names while
  `should be on exactly` takes only URL values. Also inlined in `docs/AGENT.md`
  and pointed to from the SVO guide.

<!-- entry: sl-gwru -->
- **`should see … matching /regex/` — assert-only format assertions.**
  Order-number shapes, prices, dates: assert an element's text matches a
  pattern without inventing a throwaway binding —
  `:alice should see Order.total matching /\d+\.\d{2}/`. Same poll semantics
  as `captures … matching` (wait-then-assert, embedded `{binding}` tokens
  interpolate as literals), binds nothing. Unnamed groups are the expected
  shape here; a named group is a plan error pointing at `captures` — named
  groups produce bindings, everywhere, and an assert-only frame can't honor
  that marker.

## Breaking Changes

<!-- entry: sl-yqgv -->
- **Java floor raised: 11 → 21.** ShiftLefter now requires Java 21 or later
  (previously documented as Java 11+). Rationale: Java 11's Temurin support
  ends October 2027 and Java 17's community support ends September 2026, while
  Java 21 is the current production LTS (supported through 2028+); locking the
  floor now, while the project has no installed base, makes this a zero-impact
  change rather than a future breaking one. A standing release gate
  (`scripts/release-staging/check-jdk-floor.sh`) now runs the unit suite on a
  Temurin 21 toolchain and scans the built jar for bytecode above the floor
  before every tag, and the jar's compiled Java client is pinned to
  `--release 21`, so the floor is enforced rather than merely documented.

<!-- entry: sl-2sj6 -->
- **`presses` key names are now W3C `KeyboardEvent.key` values, validated at
  planning time** — `presses Enter`, `presses ArrowUp`, `presses 'g'` (quoted
  single character). Typos are caught at plan with did-you-mean
  (`presses Entr` → "Did you mean: Enter?"), unconditionally — key names are
  a framework-owned closed set, so there is no leniency knob. Quoted
  multi-character text (`presses 'abc'`) is a plan error pointing at `fills`.
  The old lowercase dialect no longer matches: `enter`→`Enter`, `return`→`Enter`,
  `escape`→`Escape`, `backspace`→`Backspace`, `delete`→`Delete`, `space`→`Space`,
  `tab`→`Tab`, `up`→`ArrowUp`, `down`→`ArrowDown`, `left`→`ArrowLeft`,
  `right`→`ArrowRight`, `home`→`Home`, `end`→`End`, `pageup`→`PageUp`,
  `pagedown`→`PageDown`, `f1`–`f12`→`F1`–`F12`, `shift`→`Shift`,
  `control`/`ctrl`→`Control`, `alt`→`Alt`, `command`/`cmd`/`meta`→`Meta`; bare
  single characters must now be quoted (`presses a`→`presses 'g'`-style).
  **Chord support removed pending redesign** (`shift+control+t` no longer
  matches).

## Changed

<!-- entry: sl-7md -->
- **`docs/CAPABILITIES.md` is now `docs/INSTALL-TIERS.md`.** The old name
  collided with the product's own vocabulary — a "capability" is a
  provisioned adapter session — while the page is about install tiers.
  Same content, honest name; inbound links updated.

<!-- entry: sl-builtins-frame-pattern-bqo -->
- **The generated builtins reference now pairs every verb frame with a
  quotable, machine-verified step example.** Frames (`S navigates to O`)
  are schema, not steps — but the reference rendered them code-formatted,
  and an agent quoting one authored a step that didn't bind. Each frame is
  now rendered as explicitly non-quotable schema text paired with a
  `bind with:` example (e.g. `:alice opens the browser to '/login'`) drawn
  from new per-stepdef metadata; a test binds every example against its
  own pattern with the binder's exact match semantics, so the only quoted
  step text in the reference is guaranteed to bind. The regex section is
  retitled "Step grammar" and marked not-pasteable. Affects
  `docs/AGENT.md` and `sl agent-doc builtins` (regenerated, as always,
  from the registries).

<!-- entry: sl-q9t -->
- **The svo-demo example now uses the canonical glossary layout.** It was
  the last example on the pre-convention layout
  (`config/glossaries/subjects.edn`, `verbs-web.edn`); its glossaries now
  live at `glossary/subjects.edn` and `glossary/verbs/web.edn`, matching
  examples 04–06, the docs, and the framework docstrings (which taught the
  old layout in two `runner/config.clj` examples — also corrected). Its
  README no longer claims the example is "not runnable in place" — that
  predated the jar runner; the dry-run (`sl run --dry-run features/`)
  parses, binds, and SVO-validates all four scenarios in place. The
  stepdefs remain deliberate stubs: it's a structure reference, not a
  browser demo.

<!-- entry: sl-nart -->
- **Examples 02/02b now run fully offline against the bundled fixture
  server.** Both browser examples previously drove the public
  `the-internet.herokuapp.com` demo site — so they needed internet access
  and inherited its latency flakes (the release-acceptance gate observed a
  1-in-4 timeout). Each now ships a small `setup.clj` that starts
  ShiftLefter's bundled fixture app on `127.0.0.1` (fixed ports 9094/9095)
  with seeded users and stops it after the run — the same run-level
  orchestration convention example 04 teaches, using only classes already
  in the jar. Example 02's "zero code" claim is now stated precisely: zero
  *custom step definitions*; the ready-made `setup.clj` is orchestration,
  not test code. Bonus teaching upgrade in 02b: alice and bob log in with
  their own seeded credentials and the dashboard greets each session by
  name, so session isolation shows up as page text. The examples' fixture
  servers (02/02b/04/05/06) all bind `127.0.0.1` specifically now
  (sl-7hb1 hygiene).

<!-- entry: sl-ddi6 -->
- **Multi-byte output no longer crashes the REPL: bundled nREPL 1.5.0 → 1.7.0
  (cider-nrepl 0.50.0 → 0.62.2).** Any output flushed through an nREPL
  session containing multi-byte UTF-8 — emoji, `—`, `→`, `✓`, CJK — threw
  `StringIndexOutOfBoundsException` from the transport's flush path (byte
  offsets applied to a char-indexed string), aborting the surrounding eval
  and, in the worst case, taking the server down. Upstream fixed the whole
  family (nREPL #398, #403, #432); the bump rides it. This covers every
  nREPL-fronted surface: `sl repl`, `sl repl --nrepl`, the dev REPL, and
  the warm daemon's transport. Verified end-to-end: the canonical repro
  (`println` with `✓ — → 🚀` through an eval) now runs clean on the shipped
  jar, and a 12,000-char pure-multibyte flood round-trips byte-identical
  across transport buffer boundaries.

<!-- entry: sl-7hb1 -->
- **Demo fixture server binds loopback specifically; `base-url` dials
  `127.0.0.1`.** `with-fixture-server` previously bound `0.0.0.0` on an
  auto-assigned port and handed tests `http://localhost:PORT`. On macOS a
  wildcard bind can silently land on a port another process already holds
  as a `127.0.0.1`-specific listener (a running ChromeDriver, in the
  demonstrated case), and loopback requests then reach the stranger — the
  suite's fixture-family flake (~2.3% of runs: spurious 404s, missing
  session cookies, connection EOFs). The server now binds `127.0.0.1`
  directly on port 0 (occupied ports fail loudly instead of coexisting
  silently), and `base-url` pins the same address so client resolution
  can't wander across address families. Fixture HTTP helpers also send
  `Connection: close`, so no test socket survives its server.

<!-- entry: sl-kee -->
- **WebDriver element-key unwrap centralized** in the etaoin adapter:
  `script-result->handles` is now the one documented place carrying the
  W3C web-element-identifier coupling (etaoin's copy is private; its
  `el->ref` covers only the inbound direction). `query-all-pruned` uses it
  today; future fused script-returning ops (sl-zi2) adopt it. A script
  result entry with no element key now throws a structured
  `:webdriver/not-an-element` error instead of silently producing
  `{:el nil}` — loud on an already-broken path.

<!-- entry: sl-c1u8 -->
- **`printer/fmt-check` renamed to `printer/roundtrip-check`.** Two unrelated
  functions shared the name: `api/fmt-check` (the canonical-format check
  behind `sl fmt --check`) and the printer's lossless lex→print roundtrip
  verification. The printer one is now `roundtrip-check` (a map-returning
  check, so no `?` — that convention belongs to the boolean `roundtrip-ok?`);
  both docstrings now point at each other, and the README example that
  described `api/fmt-check` as a roundtrip check now shows its real
  three-state canonical result.

<!-- entry: sl-s04n -->
- **Quoted ref-shaped location literals now warn when the name is a real
  named location.** `opens the browser to 'Login'` where `Login` declares a
  `:location` gets a non-blocking warning: "'Login' is a quoted literal, but
  Login is a named location. Did you mean Login (unquoted)?" Previously
  silent. Fires only when the unquoted name provably resolves (says nothing
  about what location literals do at runtime — that ruling is sl-34ec), and
  sits behind the `:unknown-object` knob with its location-slot siblings
  (silent at `:off`). Also: the near-name did-you-mean on location refs now
  fires for "declares no :location" errors too, not just unknown intents
  (`should be on Feod` → "… Did you mean: :Feed?" when `Feed` is located).

<!-- entry: sl-gwru -->
- **`captures … matching` with only unnamed groups is now a plan error**
  (was a notice): a capture that binds nothing has lost the verb's entire
  point, so the error points at the new assert-only frame — "did you mean
  `should see … matching`?" The two errors are a symmetric teaching pair
  for the verb split. SMS `receives … matching` is unchanged: all-unnamed
  there stays a notice — binding-free assertion is legitimate in the shared
  matcher slot.

<!-- entry: sl-0fco -->
- The `:etaoin` adapter now sets automation-hygiene Chrome prefs by default —
  the password trio (`profile.password_manager_leak_detection`,
  `credentials_enable_service`, `profile.password_manager_enabled`, all
  `false`), so the native "password found in a data breach" popup and its
  peers never interrupt a session. Your `:adapter-opts` `:prefs` win per-key;
  see docs/INSTALL-TIERS.md § Default Automation-Hygiene Prefs.

<!-- no-entry: sl-tzub (test-infra: changelog-guard bead-id regex now matches hyphenated ids; no user surface) -->
<!-- no-entry: sl-9009 (test corpus + site-sample prep: hooked scenarios for the /report regen at tag time; no shipped surface) -->
<!-- no-entry: sl-iutt (release-process bead: the 0.5.3 opener triage session; no shipped surface) -->
<!-- no-entry: sl-hq30 (build tooling: gen-agent-md now chains gen-builtins; no shipped surface) -->
<!-- no-entry: sl-8bxt (internal: twin-regex drift-guard test; no user surface) -->
<!-- no-entry: sl-vkhk (internal: deps.edn/bin/sl nrepl-pin drift-guard test; no user surface) -->
<!-- no-entry: sl-w9v4 (example README doc fix; no shipped surface) -->
<!-- no-entry: sl-tkrx (overnight flake hunt: test-side hermetic fixtures + evidence beads; no shipped surface) -->
<!-- no-entry: sl-8094 (SubMid thread archive under notes/; personal-vision material, no shipped surface) -->
<!-- no-entry: sl-wvgn (Q06/Q07 audits under notes/; process + governance, no shipped surface) -->
<!-- no-entry: sl-xbh8 (overnight Warden batch audit + Herald tail prep; release-process bead, no shipped surface) -->
<!-- no-entry: sl-rfp (sl-vision wiki glossary seeding only; no dev-repo surface) -->
<!-- no-entry: sl-0ni2 (release-process bead: cut & tag v0.5.3) -->
<!-- no-entry: sl-7rb6 (test-side: per-JVM corpus scratch dirs make concurrent suite runs safe; no shipped surface) -->
<!-- no-entry: sl-8mz (test-strengthening board pass: REPL costume flow + persistent-skip/selectivity tests, findings report; no shipped surface) -->
<!-- no-entry: sl-mft6 (internal eval redesign: cross-interface-add retargeted from custom-glue authoring to data-plane discoverability; evals/ not in the public export) -->
<!-- no-entry: sl-686 (internal doctrine: three-deployment-instantiations canon page in the sl-vision wiki + agent-prompt pointers; packaged AGENT.md untouched, no shipped surface) -->
<!-- no-entry: sl-qo7 (internal doctrine: code-verified claim-stamp convention in wiki SCHEMA + stale release-map fix; no shipped surface) -->
<!-- no-entry: sl-ybox (test-side: planning-level validators for examples 02b/03/svo-demo in the bare unit suite; guards public examples, ships nothing) -->
<!-- no-entry: sl-kh5r (internal: cleanup-seam consolidation; no user surface) -->
<!-- no-entry: sl-z9m5 (internal doctrine: W1-W9/Q1-Q8 red-team amendments + sweep addenda across sl-vision wiki and notes/; no shipped surface) -->
<!-- no-entry: sl-3i4h (Pony Test talk: prep, delivery 07-31, archive to wiki decks; no shipped surface) -->
<!-- no-entry: sl-pzec (build tooling: docs task now cleans docs/api before codox regen so deleted namespaces drop their pages; docs/api is public-export-forbidden, no shipped surface) -->
<!-- no-entry: sl-h0oj (internal: version-agreement drift-guard test + append-release.sh tag/tree version refusal; no user surface) -->
<!-- no-entry: sl-1hpy (release-planning bead: the 0.5.4/0.5.5 split ruling + 0.5.6 renumber; board state only, no shipped surface) -->
<!-- no-entry: sl-mmfy (grooming session: setup.clj promoted-surface rulings — G1-G8 dispositioned, builds spawned as sl-yvaz/sl-lkla/sl-cgxr; no shipped surface) -->
<!-- no-entry: sl-kvw8 (notes/ groom: dispositions + board tooling bin/beads-web + bin/beads-graph; dev-repo hygiene, no shipped surface) -->
<!-- no-entry: sl-hbnp (rulings session: Q07 envelope owed decisions — Twilio dated, asami conscious-keep, Chrome dated-lockstep, S3 park checklist; runbook/wiki records, no shipped surface) -->
<!-- no-entry: sl-x7ec (docs-only truth-up: hygiene parity holds by construction under Playwright's off-the-record contexts — decision comment + table row, no behavior change; the table itself shipped in the sl-tnx entry) -->
<!-- no-entry: sl-l4c (internal doctrine: Capabilities & Boundaries ledger populated in the sl-vision wiki — Holds-today rows + permanent-only Non-goals; wiki-only, z9m5 precedent, no shipped surface) -->
<!-- no-entry: sl-c3gx (internal board tooling: edge metadata + renderer styling + chart relocation to board/; no shipped surface) -->

---

# Changelog: 0.5.2

**Release Date:** 2026-07-21

---

**ShiftLefter 0.5.2 is the standard-test-features release: scenario lifecycle hooks, a scenario data plane that carries captured values across steps and interfaces with zero custom code, named locations with region-vs-exact URL assertions, and configs that warn instead of silently shrugging.**

v0.5.1 made `sl run` a CI citizen. v0.5.2 fills in the features every test suite eventually reaches for — setup/teardown, passing a value from one step to a later one, asserting where the browser landed — each built the ShiftLefter way: declared in the feature file, checked at planning time, previewable with `--dry-run`.

<!-- no-entry: sl-qzhn (internal Java-floor fix — hand-rolled named-group scanner replaces a Java 20+ API; no user-visible change) -->
<!-- no-entry: sl-22h8 (internal: dead helper deleted) -->
<!-- no-entry: sl-i9hn (research/falsifier — no shipped surface) -->
<!-- no-entry: sl-hx8j (audit bead) -->
<!-- no-entry: sl-wwuq (test hygiene: run-to-run assertion-count variance; no user surface) -->
<!-- no-entry: sl-beag (folded into the sl-esq hooks entry — docs/hooks.md) -->
<!-- no-entry: sl-lhsn, sl-zy5r, sl-asam (release-process beads: Herald pass, Warden delta-pass, cut & tag) -->
<!-- no-entry: sl-anru (internal: the changelog drift-guard test itself; no user surface) -->
<!-- The no-entry allowlist above is the sl-anru convention: every closed 0-5-2
     bead must appear either as a changelog entry (with an adjacent
     `entry:` marker comment) or here, machine-enforced by
     test/shiftlefter/changelog_guard_test.clj. -->

## What's New

<!-- entry: sl-esq -->
### Scenario lifecycle hooks

Scenarios can now name the Before/After work that runs around them — and the
feature file says so, right where you read it:

```gherkin
@hook=reset-db
Scenario: Seeded scenario
```

Registrations live in `hooks.clj`, a sibling of your `shiftlefter.edn`
(discovered exactly like `setup.clj`): an ordered vector of named maps, each
with optional `:before` and `:after` fns; `:global? true` applies a hook to
every scenario. Befores run *before* capability provisioning — a broken seed
hook fails before you pay for a browser launch. Afters run after the steps
but *before* cleanup, so a screenshot-on-failure hook still reaches the live
browser. The unwind is LIFO, try-with-resources style: Afters run in reverse
order of the Befores that succeeded, and one failing After never stops the
rest.

Hook failure is loud (see `:error` under Behavior Changes), every hook that
ran is stamped into the scenario envelope under `:hooks`, and `--dry-run`
prints each scenario's firing list — console and EDN — without running
anything. A hook can also declare `:requires-serial` (the carrying scenario
auto-serializes under `--max-parallel`) and `:provides` (binding names it
contributes, feeding the data plane's static check below).

Before writing one, read `docs/hooks.md` — *Hooks, and When Not to Use
Them*. Hooks are the lowest rung of a ladder; that page routes the classic
hook use-cases to their stronger mechanisms. Full mechanics reference:
`sl agent-doc hooks`.

<!-- entry: sl-yh7 -->
### Scenario data plane: named bindings

A scenario can carry a value from the step where it's born to the step that
needs it — across interfaces — with no custom code. Name a regex group where
the value appears; reference it as `{name}` where it's consumed:

```gherkin
And [:sms] :user/alice receives a message matching /code is: (?<code>\d{6})/
And :user/alice fills Login.code with {code}
```

Named groups in a matcher bind into the scenario's data plane
(`:sl/bindings`); `{name}` tokens in value, location, and matcher slots
resolve against it. Two rules keep it predictable:

- **Quoted is always literal.** `'{code}'` is the six-character text
  `{code}`, never a lookup — the same rule as every other slot.
- **A binding is a value, never a regex fragment.** A `{name}` consumed
  inside a matcher is regex-quoted before the pattern compiles.

The flow is checked statically at planning time: consuming a `{name}` no
earlier step or hook produces is a planning error with a did-you-mean, and a
matcher whose groups are all unnamed gets a notice (it binds nothing).
`--dry-run` previews all of it without touching an interface.
`docs/across-interfaces.md` has the worked cross-interface examples,
including the magic-link pattern — capture a URL out of a message, then
`opens the browser to {resetLink}`.

<!-- entry: sl-zgna -->
### Web capture builtin

The step that makes the data plane general rather than an SMS feature:

```gherkin
And :user/alice captures Checkout.confirmation matching /Order (?<orderNumber>[A-Z0-9-]+)/
```

Capture is assert-plus-bind with should-see semantics: it polls the element
like a `should see`, and no match is a step failure — it fails as though you
could not see that text. With it, the 2FA example's 14-line custom handoff
stepdef is deleted; `examples/04-sms-2fa` now runs on zero custom steps.

<!-- entry: sl-3jr4 -->
### Named locations

An intent may now declare its own address: `:location {:web {:path "/login"}}`
— the semantic PATH lives on the intent, the environmental HOST lives in the
interface config as `:base-url`. `opens the browser to Login` and
`should be on Login` resolve the bare intent name; quoted literal URLs keep
working. Strict SVO validation catches typo'd names with a did-you-mean.

<!-- entry: sl-q81m -->
### `should be on exactly`

A second location-assertion frame for when every part of the URL matters:
full-URL structural equality — path and fragment exact, query compared as a
multimap (cross-key parameter order is insignificant, duplicate-key value
order is significant). The expectation is a quoted literal or a captured
`{binding}` token — `should be on exactly {resetLink}` asserts the browser
landed on precisely the URL captured earlier in the scenario (the magic-link
pattern). Named-location refs stay region-only (`should be on Feed`).

<!-- entry: sl-hlkz -->
### Config lint

Config problems now make noise. An unknown top-level config key, or a known
key at the wrong nesting level, prints a warning to stderr at load time:

```
Config warning: config key :step-paths is not read at the top level and was ignored — did you mean [:runner :step-paths]? [shiftlefter.edn]
```

Warnings only, never errors — a newer config on an older version still runs,
and the exit code is never affected. `sl orient` surfaces the same lints as
`:warn` diagnostics. (An example config once carried a top-level
`:step-paths` for its entire life and nothing ever said so; that class of
silent shrug is gone.)

<!-- entry: sl-lnj1 -->
In machine mode the diagnostics ride the data instead of stderr: the
runner's `--edn` summary carries them under `[:diagnostics :config-lints]`
(under `:planning` on a planning failure) — each a map of
`:type`/`:key`/`:message`, plus `:suggested-path` when there's a better
nesting. Additive and absent on clean runs: existing consumers see
byte-identical output.

<!-- entry: sl-gh75 -->
### CI integration guide

`docs/CI.md` assembles the pipeline story in one place: worked GitLab CI and
GitHub Actions examples, image and install guidance, headless-browser setup,
pending-scenario policy, and the one rule — gate on the exit code, not the
report file.

## Breaking Changes

<!-- entry: sl-yh7 -->
- **`:sms/captures` is removed from the scenario context.** The SMS receive
  matcher no longer stores positional groups at `[:sms/captures :groups]`;
  a custom stepdef that reads that key will find it absent. Name the groups
  instead — `(?<code>\d{6})` — and consume `{code}` directly in step text,
  or read `:sl/bindings` from the stepdef. The machinery keys
  (`:sms/last-message`, `:sms/last-receive-ts`) and the poll/timeout
  behavior are unchanged.

## Behavior Changes

<!-- entry: sl-esq -->
- **New `:error` scenario status: a failing hook turns the run red as an
  infrastructure failure, distinct from a step failure.** A Before that
  throws marks the scenario `:error` and skips its steps; an After that
  throws marks it `:error` even when every step passed — broken cleanup is
  a lying suite. The exit code is 1, like any red run (never 2, which stays
  reserved for planning failures). JUnit XML renders these as `<error>` —
  not `<failure>` — with hook attribution, and console, EDN, and the HTML
  report gain an error count. Additive when absent: hook-less runs keep the
  four-key counts map and byte-identical output.
- **`should be on` is now a region assertion, not a substring test.** It
  compares the normalized path (plus fragment) of the browser URL against the
  expectation and ignores the query string and host. Path-only expectations
  like `'/secure'` behave as before; expectations that relied on substring
  matching of hosts or query strings (`'example.com/dash'`, `'?tab=home'`)
  must switch to a path form or to `should be on exactly '<url>'`.
  Percent-encoding normalization, host-case rules, and byte-exact matching
  are deliberately out of scope — write a custom step assertion for those.
<!-- entry: sl-iseq -->
- **Quoted values never classify as refs in location slots.** One rule across
  the authored surface, matching element slots: quoted = literal, always;
  a bare PascalCase token = named-location ref. (`opens the browser to Feed`
  resolves the `Feed` intent; `opens the browser to 'Feed'` navigates to the
  literal string `Feed`.)

## Bug Fixes

<!-- entry: sl-ka80 -->
- **`should be on exactly '{token}'` no longer resolves the quoted text.**
  The at-exactly pattern captured inside its quotes, so a quoted `'{myUrl}'`
  reached the engine's capture normalization already unquoted, resolved as a
  binding token (violating quoted = literal, always), and produced an
  unparseable expected URL; the static lint also mis-registered the quoted
  text as a binding consumption. The slot now captures whole like every
  other value/location slot: quoted stays literal, and the same change is
  what admits the bare `{token}` consumption described under
  `should be on exactly` above.

<!-- entry: sl-uu7x -->
- **Partial provisioning failure no longer leaks browser processes.** When
  scenario-start provisioning failed partway (Alice's browser up, Bob's
  failed), capabilities provisioned before the failure were invisible to
  scenario-end cleanup — the adapter's `:cleanup` never ran and a
  Chrome/driver process leaked per failing scenario. The failure path now
  carries the partially-provisioned context, so every impl that came up is
  closed. Side effect worth knowing: After hooks on such failures now see
  the live partially-provisioned capabilities (they run before cleanup), so
  screenshot-on-failure hooks can reach a browser that did come up.

<!-- entry: sl-ev0b -->
- **`--dry-run` in setup mode no longer executes user lifecycle code.**
  `sl run --dry-run` against a `setup.clj` project used to call each group's
  `:start` fn — fixture servers spawned, ports bound — during what should be
  a pure plan preview. Dry-run now skips `:start`/`:stop` and every
  Before/After entirely: the preview lists each group's plan and its hook
  firing lists without running any of it.

<!-- entry: sl-27uh -->
- **Report data with non-round-tripping keyword or symbol names is now
  stringified.** `edn-safe?` admitted keywords and symbols whose names don't
  survive an EDN round-trip (`(keyword "a b")`, `(symbol "-1")`), letting
  them embed raw in the `--edn` summary and the HTML report's EDN island —
  where a hostile name could break the island's readability. Such tokens are
  now scrubbed to their `pr-str` string form, and the HTML island gained an
  integrity guard that aborts loudly rather than emit an unreadable island.
  Well-formed data is untouched.

## Stats

```
1882 tests, 6271 assertions, 0 failures
Cucumber compliance: 46/46 good, 11/11 bad (100%)
```

## Installation

**One-line installer (recommended):**

```bash
curl -fsSL https://raw.githubusercontent.com/SHIFT-LEFTER/shiftlefter/main/release/install.sh | bash
export PATH="$PWD/sl:$PATH"
sl --version
```

**Manual:** download `shiftlefter-v0.5.2.zip` from releases, unzip, add to PATH. Java 11+ is the only requirement — no Clojure toolchain needed.

## Links

- [README](https://github.com/SHIFT-LEFTER/shiftlefter/blob/v0.5.2/README.md)
- [Running in CI](https://github.com/SHIFT-LEFTER/shiftlefter/blob/v0.5.2/docs/CI.md)
- [Fit check — would ShiftLefter work for my project?](https://github.com/SHIFT-LEFTER/shiftlefter/blob/v0.5.2/docs/FIT.md)

---

# Changelog: 0.5.1

**Release Date:** 2026-07-10

---

**ShiftLefter 0.5.1 makes `sl run` a first-class citizen of your CI pipeline: JUnit XML your CI ingests natively, tag-based subset selection, live per-scenario output, parallel execution, and a self-contained HTML report — one release, one theme.**

v0.5.0 locked the core surface and launched publicly. v0.5.1 is about everything *around* the run that a pipeline needs: machine-readable results, picking the subset that matters, seeing progress as it happens, and honest wall-clock speed.

---

## What's New

### JUnit XML for CI

`sl run --junit-xml PATH` (config mirror `[:runner :report :junit-xml]`; the flag wins) writes a CI-ingestible JUnit XML report alongside the console/EDN output — verified live against GitLab's `artifacts:reports:junit` ingest. Test-case identity is stable across runs, failures are attributed through macro expansion to the step that actually failed, and per-scenario and per-step durations are captured.

Two behaviors to know (full detail in [ERRATA E009](ERRATA.md)):

- The XML contains a `<failure>`/`<error>` **iff** the run's exit code is nonzero — the report never contradicts the exit code.
- A planning error (exit 2) writes **no** file at all. Gate CI on the process exit code, not on the report's presence.

### Tag-Subset Selection

`sl run --tags TAGS` / `--skip-tags TAGS` runs a tagged subset of the suite: comma-separated, repeatable, `@` optional; include is OR, exclude wins; feature/Rule/Examples-block tags inherit per the Gherkin spec.

Filtering happens at **planning time** — deselected scenarios are never bound, and counts, JUnit, and EDN output reflect only the selection. `--dry-run` with a filter previews it: the console appends `; M filtered out by tags`, and the EDN gains an additive `:filtered-out` key. No filter = behavior unchanged.

### Live Per-Scenario Output

`sl run` now prints each scenario's result as it finishes, instead of staying silent until the whole suite completes. Same lines, same order — you just see them while the run is still going.

### Parallel Scenario Execution

`sl run --max-parallel N` (config mirror `[:runner :max-parallel]`; the flag wins; default 1 = sequential) runs scenarios on a bounded pool. Report output is identical in content **and order** to a sequential run at any N.

- `@serial` marks a scenario as **exclusive**: it runs alone, after the pool drains. It is an exclusivity mechanism, not an ordering one; a feature-level `@serial` marks each scenario individually, not the feature as an atomic block.
- Scenarios that wear costumes or touch a shared-impl interface auto-serialize the same way, with a notice line saying so.

### Self-Contained HTML Report

`sl run --html PATH` (config mirror `[:runner :report :html]`; the flag wins) writes a single-file HTML run report: works from a double-click, no network requests. Failures come expanded by default, with step-by-step transcripts, durations, tag chips, and dark/light rendering.

The report embeds its own run data as a machine-readable EDN island (`<script type="application/edn">`) — agents read the data, humans read the page, one artifact. Runs with thousands of scenarios build a big DOM and will scroll sluggishly.

### CLI Polish & Fixes

- `sl --help` now lists the daemon subcommands and `repl --clj` (previously undocumented in the help text).
- `sl verify` outside a ShiftLefter framework checkout now exits 2 with a notice (was: exit 0 — a consumer CI script could silently pass). Use `sl orient` and `--dry-run` to validate a project.
- `close-browser` handed a wrong-shape capability now returns a structured error instead of silently claiming `:closed` (both browser adapters).

### Internal

- Two-plane reporting architecture: a synchronous `Reporter` protocol for user output, an observer event bus for everything else — the groundwork the features above stand on. A throwing reporter fails the run immediately.
- Run-event envelopes gain `:scenario/id` and `:seq`; the observer bus is `offer!`-based (a slow observer can never stall or crash a run; drops are counted and logged) and drains on close, so accepted events are always delivered — killing a long-standing race that dropped each run's final events.
- A generated runner-mechanics corpus (seeded generator + JUnit-vs-manifest verifier) now exercises tags × parallel acceptance, and generative round-trip properties lock the envelope purity contract.

## Behavior Changes

- `sl verify` in a directory that isn't a ShiftLefter framework checkout exits 2 (was 0). A CI step that relied on the old silent green must switch to `sl orient` / `sl run --dry-run`.
- `sl run` console output now appears per-scenario during the run (content and order unchanged).

## Stats

```
1710 tests, 5593 assertions, 0 failures
Cucumber compliance: 46/46 good, 11/11 bad (100%)
```

## Installation

**One-line installer (recommended):**

```bash
curl -fsSL https://raw.githubusercontent.com/SHIFT-LEFTER/shiftlefter/main/release/install.sh | bash
export PATH="$PWD/sl:$PATH"
sl --version
```

**Manual:** download `shiftlefter-v0.5.1.zip` from releases, unzip, add to PATH. Java 11+ is the only requirement — no Clojure toolchain needed.

## Links

- [README](https://github.com/SHIFT-LEFTER/shiftlefter/blob/v0.5.1/README.md)
- [Fit check — would ShiftLefter work for my project?](https://github.com/SHIFT-LEFTER/shiftlefter/blob/v0.5.1/docs/FIT.md)
- [Why it's built this way](https://github.com/SHIFT-LEFTER/shiftlefter/blob/v0.5.1/docs/PHILOSOPHY.md)

# Changelog: 0.5.0

**Release Date:** 2026-07-07

---

**ShiftLefter 0.5.0 is the public launch: the core surface — a typed behavioral vocabulary driving real multi-actor browser sessions — is locked and ready to build on, and the whole project now treats an AI coding agent as a first-class author.**

v0.4.5 proved the multi-interface promise (web + SMS in one scenario). v0.5.0 pins the foundation those proofs stand on: a stability contract for the core, an agent front door, deterministic project discovery, and a formatter you can trust as a CI gate. Agents write good E2E tests against this surface today; the traceability graph that maps them to requirements and use cases builds upward from here.

---

## What's New

### Agent-First Front Door

`sl orient` (replacing `sl agent-prompt`) is the one command an agent — or a human — runs first in any ShiftLefter project. It states which mode the project is in (**Shifted** with a validated vocabulary, or **Vanilla** plain Gherkin), points at the key artifacts, and suggests next steps. `sl orient --edn` dumps the full machine-readable project projection — accepted working-tree truth an agent can consume directly, including validation commands phrased for the *consumer's* environment, not ours.

The doctrine now travels with the jar: `sl agent-doc` serves topic guides (locator strategy, SIEVE doctrine, orientation) from inside the packaged release, and the built-in step vocabulary reference is *generated* from the actual registered steps, so it cannot drift from the code. A one-line installer drops a runnable `sl` into `./sl/` and prints the `AGENTS.md` breadcrumb that tells a coding agent the surface exists.

### Deterministic Project Context

Project root and config discovery is now a single, deterministic resolution used everywhere — CLI, daemon, and examples resolve paths identically. The warm-path daemon wrapper that makes repeat `sl` calls fast is now the wrapper actually packaged in the release, and config-less directories no longer suffer a 10-second stall, a cold fallback, and a leaked background daemon.

### Two Modes, Stated Plainly

**Vanilla** mode (plain Gherkin, no vocabulary) is now genuinely reachable — previously the default config injected a glossary key and made every project look Shifted. `sl orient` names the mode explicitly, and planning-time validation checks exactly what the runner will run — no more validating steps the current mode ignores.

### A Formatter You Can Gate CI On

`sl fmt --check` / `--write` is advertised as a CI gate, so 0.5.0 makes it trustworthy:

- **Formatting is idempotent** — `fmt(fmt(x)) == fmt(x)` is now a property test in the generative suite, not an assumption. Multi-line descriptions previously drifted two spaces per pass and could never satisfy `--check`; they now normalize to a fixed column while preserving intentional relative indentation.
- **Descriptions are never deleted** — Scenario, Background, and Examples descriptions are emitted in canonical output (they were previously dropped), with a preservation property test guarding all positions.
- **Comments are never silently destroyed** — canonical formatting does not yet re-emit interior comments, so `--write` now *refuses* to reformat a file where comments would be lost, names the exact lines, and leaves the file untouched. `--check` reports such files as a third state — `CONTAINS COMMENTS` — distinct from OK and NEEDS FORMATTING, exiting 0 so a CI gate never goes red on a file `--write` won't fix. Full comment preservation is on the roadmap.

### Sharper Diagnostics

- Planning diagnostics (step-definition/glossary mismatches) now appear in **both** the console and EDN reporters — previously they were invisible in both.
- `sl <unknown-command>` is now a proper CLI usage error: non-zero exit (was: exit 0 with output on stdout).
- Provisioning rejects a second wearer of the same costume up front, instead of failing mysteriously mid-run.
- Browser step retry logic recognizes more Chrome error variants and waits out post-navigation races.

### Docs for Humans and Agents

The public docs got a positioning-and-honesty pass, validated by blind cold-reads against a first-day-adopter persona:

- [Would ShiftLefter work for my project?](docs/FIT.md) — a practical fit check you can hand to your agent.
- [Why it's built this way](docs/PHILOSOPHY.md) — the design-philosophy read behind the typed-vocabulary idea.
- The locator-strategy doctrine — why intent references beat brittle selectors — published for end users, with a slim agent-facing cut in `sl agent-doc`.
- Reference pages for the glossary, SVO validation, and costumes, plus a tutorial on-ramp through the numbered `examples/`.

### Agent Evaluation Corpus

`evals/` ships two self-contained agent evaluation tasks — a cross-interface authoring task (browser + SMS) and a multi-user session-isolation repair task — each with a start state, a solution, and a verifier. The 0.5 acceptance drive ran an agent through the real dogfood loop (N=3, all green through the built jar).

### Preview: SIEVE

The SIEVE browser-inspection server — live perception of a running page, two-observation reconciliation, semantic diffing — ships in the codebase as a **dev/REPL preview**: invocable from the REPL, not yet wired to the CLI. Its doctrine (`sl agent-doc sieve`) ships now, backed by the acceptance-drive evidence.

## Breaking Changes

- `sl agent-prompt` is now `sl orient` (same front-door role, richer output).
- `sl <unknown-command>` exits non-zero (was 0).
- `sl fmt` EDN output gains per-file `:comments` and summary `:with-comments` / `:skipped-comments` keys; comment-bearing files report the new `CONTAINS COMMENTS` state instead of NEEDS FORMATTING.

## Stats

```
1595 tests, 5069 assertions, 0 failures
Cucumber compliance: 46/46 good, 11/11 bad (100%)
```

## Installation

**One-line installer (recommended):**

```bash
curl -fsSL https://raw.githubusercontent.com/SHIFT-LEFTER/shiftlefter/main/release/install.sh | bash
export PATH="$PWD/sl:$PATH"
sl --version
```

**Manual:** download `shiftlefter-v0.5.0.zip` from releases, unzip, add to PATH. Java 11+ is the only requirement — no Clojure toolchain needed.

## Links

- [README](https://github.com/SHIFT-LEFTER/shiftlefter/blob/v0.5.0/README.md)
- [Fit check — would ShiftLefter work for my project?](https://github.com/SHIFT-LEFTER/shiftlefter/blob/v0.5.0/docs/FIT.md)
- [Why it's built this way](https://github.com/SHIFT-LEFTER/shiftlefter/blob/v0.5.0/docs/PHILOSOPHY.md)

# Changelog: 0.4.5

**Release Date:** 2026-06-05

---

**ShiftLefter now drives SMS as a first-class interface — the same zero-code step definitions that ran browsers send and receive real text messages — and locks its verb vocabulary behind frame-validated glossaries.**

v0.4.0 added the intent layer and typed subjects. v0.4.5 cashes in the multi-interface promise: a 2FA password-reset scenario that spans web *and* SMS, with every capability provisioned up front and validated against the protocols each adapter claims.

---

## What's New

### SMS Interface

A second real interface alongside the browser. The `ISMS` protocol covers production sending; `ISMSInbound` adds the test-seam read path. A Twilio adapter implements `ISMS` against the live API; a MockSMS adapter implements both for deterministic tests. Built-in step definitions — `:send`, `:send-media`, `:receive`, `:see`, `:count` — need no custom code:

```gherkin
When :user/alice sends "RESET" to the service
Then :user/alice should receive a message containing "code"
And :service should see 1 message from :user/alice
```

Configure it like any other interface:

```clojure
;; shiftlefter.edn
{:interfaces {:sms {:type :sms :adapter :twilio
                    :config {:account-sid "..." :auth-token "..." :from "+1..."}}}}
```

The Twilio read path was validated against the real API.

### Multi-Interface 2FA Demo

`examples/04-sms-2fa/` drives a password-reset flow across web and SMS in a single scenario — the canonical example of two interfaces cooperating, with a `setup.clj` wiring shared state.

### `setup.clj` Test Orchestration

A convention for scenarios that need shared setup across interfaces. A `setup.clj` next to your feature files runs once, plumbing an adapter registry and any shared state into the run — see `examples/04-sms-2fa/setup.clj` for the canonical pattern.

### Subject-Aware Verb Glossaries & Frames

Verbs are now declared in glossaries (`verbs-web`, `verbs-sms`) with a frame-based valence schema — each verb states the arguments (frames) it accepts, cross-checked against step definitions at compile time. `:hover` and `:wait` returned to the web glossary; `:count`, `:upload`, `:check`, `:uncheck`, and `:submit` were removed as defaults (rationale in the decisions record). See Breaking Changes.

### Capability Gating

Adapters declare what they `:provides`; scenarios (and the stepdefs they bind) declare `:requires-protocols`. The adapter registry validates these claims at suite-load, so a scenario that needs an inbound-capable SMS adapter fails fast at load if you wired a send-only one — not mid-run.

### `:be` Verb (URL assertions)

The `:url` frame moved out of `:see` into a dedicated `:be` verb with an `:at` frame. The surface pattern is unchanged — existing URL assertions keep working — but the model is cleaner.

---

## Behavior change: scoped-eager capability provisioning is now the default

**Affects:** Multi-interface scenarios under `sl run`.

Capabilities (browsers, SMS clients, API clients) are now provisioned at
**scenario start** for every interface the scenario's bound steps will
touch — not lazily on the first step that needs them.

**Why:** Bad credentials, missing config, and adapter errors now fail
fast — before any step runs — instead of mid-scenario after a partial
prefix has already executed. Per-interface timing markers (e.g.
`:sms/scenario-start-ts`) become honest: set when the scenario starts,
not when the first step happens to touch the interface.

**Behavior change to expect:** A scenario that previously short-circuited
before reaching an SMS step (e.g., a web step failed first) now pays the
SMS bring-up cost up front. Every `:on-provision` hook fires earlier in
the scenario than it used to.

**Opt-out:**

```clojure
;; shiftlefter.edn
{:runner {:provisioning :lazy}}   ;; revert to per-step on-first-touch
```

`:provisioning :eager` is the default; the key may be omitted. Valid
values: `:eager` | `:lazy`.

**Unaffected:**

- The REPL (`shiftlefter.repl/run` and direct `execute-scenario` calls
  with no `:provisioning` opt) keeps lazy semantics — exploratory work
  doesn't pay bring-up for unused interfaces.
- The per-step `ensure-capability` primitive is unchanged. Eager is
  layered on top: the scenario-start phase calls the same primitive once
  per `(interface, subject)` target, then per-step calls no-op.
- `:shared-impl?` interfaces (e.g., SMS) still provision their impl
  exactly once per scenario — Bob's eager target reuses Alice's via
  `find-existing-shared-impl`.

**Multi-interface parallelism:** When a scenario touches more than one
interface, eager bring-up runs the per-interface groups in parallel
(futures). Within an interface, sequentially so shared-impl handoff is
deterministic.

---

## Minor Improvements

- **Did-you-mean for `[:iface]` failures** — an unknown interface annotation now suggests the closest valid one instead of a bare error.
- **ChromeDriver retry** — "Node with given id does not belong to the document" joins the retryable-error set, smoothing over a transient DOM race.
- **Logging facade** — operational `stderr`/`println` output migrated to `clojure.tools.logging`; bring your own SLF4J binding to route it.
- **Fixture server promoted** — the test fixture server moved into `src/shiftlefter/demo/fixture/`, retiring the `:demo` deps alias the 2FA demo previously needed.
- **`exec.clj` split** — the runner core was split along provisioning / step-loop / cleanup seams (internal, no surface change).

---

## Breaking Changes

- **Default web verbs removed** — `:count`, `:upload`, `:check`, `:uncheck`, and `:submit` are no longer in the default web glossary. Declare them in a project glossary if you need them.
- **`SVOI` → `SVO`** — the subject-verb-object-instrument nomenclature collapsed to subject-verb-object across event keywords and namespaces. Code that referenced `:svoi/*` event keys or `shiftlefter.svoi.*` namespaces must use `:svo/*` and `shiftlefter.svo.*`.
- **`browser.ctx` bridge removed** — the legacy `shiftlefter.browser.ctx` namespace is gone; the unified capability/ctx path is the only one.

---

## Test Results

```
1359 tests, 4160 assertions, 0 failures
Compliance: 46/46 good, 11/11 bad (100%)
```

---

# Changelog: 0.4.0

**Release Date:** 2026-04-04

---

**v0.4.0 adds an intent layer — bind a business action like `Login.submit` to a real locator per interface — plus typed subjects with isolated sessions and runtime spec enforcement (45 fdefs).**

v0.3.x made browser tests run with zero custom code. v0.4.0 adds the structural layer beneath them: a vocabulary for intent, a type system for subjects, and specs that enforce the contracts at runtime.

---

## What's New

### Intent System

Declare *what* an action means once, bind it to *how* each interface performs it. Flat intent regions hold per-interface element bindings, so `Login.submit` resolves to a real locator at run time:

```clojure
;; intent/login.edn
{:region :Login
 :intents {:submit {:web {:css "button[type=submit]"}}}}
```

```gherkin
When :alice submits Login
```

Object enforcement is configurable — `:strict` (every intent reference must resolve), `:warn`, or `:off`.

### Subject Types & Instances

Subjects are now typed. `:user/alice` names an instance `alice` of type `user`, resolved through a two-level type/instance glossary. Each instance gets an isolated session, so multi-user scenarios don't bleed state:

```gherkin
Given :user/alice is logged in
And :user/bob is logged in
Then :user/alice should see her own dashboard
```

Display format is `[:type] instance` for readable reports.

### Spec Instrumentation

45 `fdef` specs across all namespaces, active during dev and test runs — so specs are runtime enforcement, not just documentation. Boundary guards validate external input where it enters, REPL interactions get type safety, and UTF-8 output is enforced at the stream boundary.

### Test Fixture Server

A configurable Ring harness (`with-fixture-server`) ships with login, dashboard, and logout pages and real multi-user session isolation — so browser scenarios have a deterministic target to drive without standing up an external app:

```clojure
(with-fixture-server [server {:port 0}]
  ;; login / dashboard / logout pages, per-user sessions
  (run-scenarios))
```

### Agent Documentation

- **`docs/AGENT.md`** — LLM-agent instructions covering the CLI, the SVO model, step definitions, multi-user examples, intent references, and the dual browser adapters (Etaoin + Playwright).

### Public Mirror Convention

Local agent primers and role prompts are not part of the public release spine. The public mirror ships user-facing documentation only.

---

## Notes

- **Source-only retroactive release.** v0.4.0 records a drop that was published to the public mirror but never tagged or released at the time. No binary artifact was built for it — binary distribution resumes at v0.4.5.
- **Spec coverage** rather than a fresh test count: this entry reconstructs an already-public source tree, so it reports the 45 `fdef` contracts added rather than a re-run pass total.

---

# Changelog: 0.3.6

**Release Date:** 2026-02-09

---

**ShiftLefter now supports Playwright, bundles nREPL, and ships with full API docs.**

v0.3.5 made browser testing work. v0.3.6 hardens the developer experience — alternative browser backends, a bundled REPL server, 36 browser steps, CLI safety, and comprehensive documentation.

---

## What's New

### Playwright Adapter (Experimental)

Alternative browser backend alongside Etaoin. Same step definitions, different engine:

```clojure
;; shiftlefter.edn
{:interfaces {:web {:type :web :adapter :playwright :config {:headless true}}}}
```

Supports Chromium, Firefox, and WebKit. All 31 IBrowser protocol methods implemented. Auto-waiting and string-based selectors map naturally. Requires the Playwright Java dependency (not bundled) — see `docs/INSTALL-TIERS.md` for setup.

### Browser Backend Configuration

Choose your browser engine per-interface via config. Generic `:adapter-opts` passthrough for backend-specific settings:

```clojure
{:interfaces {:web {:type :web
                    :adapter :etaoin
                    :adapter-opts {:load-timeout 30000}}}}
```

### 36 Browser Step Definitions

19 new protocol methods and 22 new step definitions (36 total browser steps). Covers scrolling, keyboard chords, window/tab management, frames, dialogs, and advanced form interactions. Etaoin (default, bundled) requires ChromeDriver on your system; see `docs/INSTALL-TIERS.md` for browser setup tiers.

```gherkin
When :alice scrolls down by 500 pixels
And :alice presses 'shift+control+t'
And :alice switches to window 2
And :alice accepts the dialog
Then :alice should see 3 {:css "li.result"} elements
```

### Bundled nREPL Server

`sl repl` now works with Java-only — no Clojure CLI required. CIDER middleware included for IDE integration:

```bash
sl repl              # interactive REPL
sl repl --nrepl      # start nREPL server (default port 7888)
sl repl --nrepl --port 9000
```

### API Documentation

Generated API docs for all 51 namespaces via Codox. [Browse on GitHub](https://github.com/SHIFT-LEFTER/shiftlefter/tree/v0.3.6/docs/api).

### Installation & Capabilities Docs

New `docs/CAPABILITIES.md` with tiered capability guide (Java-only → Java+ChromeDriver → Java+Clojure CLI). New `CONTRIBUTING.md` for developers.

---

## Bug Fixes

- **Inline comments** — `#` comments inside scenario bodies no longer cause parse errors.
- **CLI flag handling** — Unknown flags now rejected with usage message instead of silently ignored. Fixed `--config-path` not reaching the runner.
- **i18n keywords** — Formatter now preserves original language keywords instead of normalizing to English.

---

## Minor Improvements

- **Browser step retry** — Configurable `*retry-timeout-ms*` dynamic var (default 3s). Element count verification now retries.
- **CLI flags** — Added `--no-color` and `--mode` flags.
- **Bundled capabilities** — 8 libraries available in uberjar stepdefs (Cheshire, babashka.fs, core.async, spec.alpha, test.check, etaoin, Java stdlib, clojure.test). Classpath extension via `lib/*.jar`.

---

## Breaking Changes

- **"I" step patterns removed** — `I click`, `I fill`, etc. no longer exist. All steps use subject-extracting patterns (`:alice clicks`, `:bob fills`).
- **`repl/free` removed** — Use `repl/as` instead (identical behavior).

---

## Test Results

```
1029 tests, 3200+ assertions, 0 failures
Compliance: 46/46 good, 11/11 bad (100%)
```

---

# Changelog: 0.3.5

**Release Date:** 2026-02-05

---

**ShiftLefter now runs browser tests with zero custom code.**

v0.3.0 added browser automation and SVO validation. v0.3.5 makes them usable — multi-actor browser testing with auto-provisioning, tutorial examples, and a distributable release zip.

---

## What's New

### Multi-Actor Browser Steps

13 new built-in steps with named-subject routing. Each subject gets its own browser session:

```gherkin
Scenario: Two users collaborate
  When :alice opens the browser to 'https://app.com'
  And :alice fills {:id "message"} with 'Hello Bob'
  And :alice clicks {:css "button.send"}
  Then :bob should see 'Hello Bob'
  And :bob should see the title 'App - Messages'
```

7 action steps (navigate, click, double-click, right-click, move, drag, fill) and 6 verification steps (see text, see element, not see element, element count, URL check, title check). All include SVO metadata for shifted-mode validation.

### Zero-Code Browser Tests

Browser sessions auto-provision from `shiftlefter.edn`. No setup steps, no custom Clojure — just a feature file and a config:

```clojure
;; shiftlefter.edn
{:interfaces {:web {:type :web :adapter :etaoin :config {:headless true}}}}
```

The framework sees `:alice` needs a `:web` capability, looks up the interface config, and provisions a browser keyed to that subject on first use.

### Auto-Retry on Browser Steps

Action and verification steps automatically retry on transient browser errors — stale element references, elements not yet in the DOM, assertion mismatches during page transitions. 3-second timeout with 100ms backoff. No configuration needed.

### Tutorial Examples

Four new worked examples in `examples/`:

| Example | What it covers |
|---------|---------------|
| `01-validate-and-format` | Parse, validate, and format feature files |
| `02-browser-zero-code` | Browser test with zero custom Clojure |
| `02b-browser-multi-actor` | Two-actor browser test with SVO |
| `03-custom-steps` | Custom step definitions with `defstep` |

Each has a README with step-by-step instructions.

### Release Distribution Zip

Ship as a single zip. Users unzip, add to PATH, done:

```bash
unzip shiftlefter-v0.3.5.zip
export PATH="$PATH:$PWD/shiftlefter-v0.3.5"
sl fmt --check myfile.feature
```

No Clojure toolchain required — just Java 11+.

### REPL Shifted Mode

Toggle SVO validation interactively:

```clojure
(shifted!)   ;; enable SVO validation against glossaries
(vanilla!)   ;; back to vanilla mode
```

---

## Bug Fixes

- **`sl fmt --check`** — Previously always reported "OK". Now correctly exits 1 when files need formatting.
- **`sl gherkin ddmin`** — No longer crashes with "no matching clause" when called without `--mode`.
- **`sl gherkin fuzz --mutation --sources corpus`** — Clear error message when run outside the project directory instead of cryptic "bound must be positive".
- **`sl verify`** — Fuzz artifact scanning removed from default mode (was slow/crashy with large artifact sets). Use `--fuzzed` to opt in.
- **CLI path resolution** — `sl` installed on PATH now resolves relative arguments against the user's working directory, not the install directory.

---

## Minor Improvements

- **Undefined step locations** — Console output includes `file:line:column` for undefined steps
- **SVO diagnostics in `--edn` output** — SVO issues include full location info; macro-expanded steps show both call site and definition location in failure messages
- **Browser stability** — PersistentBrowser reconnect handles more error types; Chrome window verified before navigation; browser session options cleaned from WebDriver capabilities
- **Spec health** — All specs in the registry can now generate sample data; spec health test catches dangling references and missing generators in CI

---

## Breaking Changes

- **ctx-first step convention** — Step functions now receive `ctx` as the first argument with flat context shape. Old: `[subject element ctx]` → New: `[ctx subject element]`. DataTable/DocString accessible via `(step/arguments ctx)`.

---

## Test Results

```
945 tests, 2869 assertions, 0 failures
Compliance: 46/46 good, 11/11 bad (100%)
```

---

# Changelog: 0.3.0

**Release Date:** 2026-01-10

---

**ShiftLefter now catches mistakes before they run.**

v0.2.0 ran tests. v0.3.0 validates them — checking that your actors and actions are known before execution begins.

---

## What's New

### SVO Validation

Define who can do what in your test suite:

```clojure
;; config/glossaries/subjects.edn
{:subjects
 {:alice {:desc "Standard customer"}
  :admin {:desc "Administrative user"}
  :guest {:desc "Unauthenticated visitor"}}}
```

Add metadata to step definitions:

```clojure
(defstep #"^(\w+) clicks the (.+)$"
  {:interface :web
   :svo {:subject :$1 :verb :click :object :$2}}
  [ctx subject element]
  ...)
```

Typos are caught at bind time with suggestions:

```
Unknown subject :alcie in step "When Alcie clicks the button"
       at features/login.feature:12
       Known subjects: :alice, :admin, :guest
       Did you mean: :alice?
```

Configure enforcement levels in `shiftlefter.edn`:

```clojure
{:glossaries
 {:subjects "config/glossaries/subjects.edn"
  :verbs {:web "config/glossaries/verbs-web.edn"}}

 :interfaces
 {:web {:type :web :adapter :etaoin :config {:headless true}}}

 :svo
 {:unknown-subject :warn    ; or :error to block execution
  :unknown-verb :warn
  :unknown-interface :error}}
```

### Browser Automation

Built-in browser support via IBrowser protocol:

```clojure
(defstep #"^(\w+) clicks the (.+)$"
  {:interface :web ...}
  [ctx subject element]
  (let [browser (get-capability ctx :web)]
    (browser/click browser element)
    ctx))
```

Capabilities are auto-provisioned — declare the interface, get a browser.

### REPL-First Development

Interactive step development with named contexts:

```clojure
(require '[shiftlefter.repl :as repl])

(repl/as :alice)                    ; create/switch to Alice's browser
(repl/step "I click the login button")
(repl/as :bob)                      ; separate browser for Bob
```

Multi-actor scenarios use separate browser sessions per subject.

### Persistent Browser Sessions

Browsers survive JVM restarts and macOS sleep/wake cycles. Subject profiles stored in `~/.shiftlefter/subjects/` enable exploratory testing without session loss.

---

## Minor Improvements

- **Auto-provisioning**: Steps declaring `:interface :web` get browsers automatically
- **Capability cleanup**: Ephemeral capabilities cleaned up after scenarios; persistent ones survive
- **Error formatting**: Clear messages with location info and suggestions
- **Example project**: See `examples/svo-demo/` for a complete working setup

---

## Documentation

- `docs/SVO.md` — Full SVO validation guide with migration steps
- `docs/GLOSSARY.md` — Updated with SVO terminology
- `examples/svo-demo/` — Working example with glossaries and shifted stepdefs

---

## Test Results

```
865 tests, 2582 assertions, 0 failures
Compliance: 46/46 good, 11/11 bad (100%)
```

---

# Changelog: 0.2.0

**Release Date:** 2026-01-07

---

**ShiftLefter now runs tests.**

v0.1.0 was a parser. v0.2.0 is a test framework.

---

## What's New

### Test Runner (`sl run`)

Execute Gherkin features against Clojure step definitions:

```bash
sl run features/ --step-paths steps/
sl run features/login.feature --dry-run    # verify binding without execution
sl run features/ --edn                      # machine-readable output for CI
```

Exit codes: 0=passed, 1=failed/pending, 2=planning-failed, 3=crash

### Step Definitions

Define steps with the `defstep` macro:

```clojure
(defstep #"I have (\d+) cukes" [ctx count]
  (assoc ctx :cukes (parse-long count)))
```

Context threads between steps with fail-fast-per-scenario semantics.

### Macro Expansion

Reusable step sequences, defined in INI files:

```ini
# macros/auth.ini
name = login as alice
steps =
  Given I visit the login page
  When I enter username "alice"
  And I enter password "secret123"
  Then I click the login button
```

```gherkin
Scenario: User sees dashboard
  Given login as alice +       # expands to 4 steps
  Then I should see the dashboard
```

Enable in `shiftlefter.edn`:
```clojure
{:runner {:macros {:enabled? true
                   :registry-paths ["macros/"]}}}
```

### Internationalization (70+ Languages)

Full i18n support using Cucumber's official language definitions:

```gherkin
# language: fr
Fonctionnalité: Connexion
  Scénario: Utilisateur valide
    Soit un utilisateur "alice"
    Quand je me connecte
    Alors je vois le tableau de bord
```

### Multi-File Formatting

`sl fmt` now supports directories:

```bash
sl fmt --check dir/               # validate recursively
sl fmt --write dir/               # reformat in place
```

### Standalone Distribution

No Clojure toolchain required to run — just Java:

```bash
./sl fmt --check myfile.feature
```

---

## Minor Improvements

- **Error diagnostics**: Standard `path:line:col: type: message` format, `--edn` flag for machine-readable output
- **Validator command**: `sl verify` for quick repo health checks, `sl verify --ci` for full suite
- **Fuzzing tools**: `sl gherkin fuzz` and `sl gherkin ddmin` for parser testing
- **Rule keyword**: Full `Rule:` block support in parser and formatter

---

## Test Results

```
591 tests, 1704 assertions, 0 failures
Compliance: 46/46 good, 11/11 bad (100%)
```

# Changelog: 0.1.0

**Release Date:** 2025-12-31

First public release of ShiftLefter's Gherkin parser.

## Highlights
- **100% Cucumber-compatible** (46/46 test files passing)
- **Lossless roundtrip guarantees** (byte-for-byte reconstruction)
- **70+ language dialect support** (official Cucumber i18n)
- **Clojure API** for framework integration
- **268 tests, 0 failures**

## Installation

**Binary (recommended):**
1. Download `shiftlefter-v0.1.0.jar` below
2. Ensure Java 11+: `java -version`
3. Run: `java -jar shiftlefter-v0.1.0.jar fmt --check your-file.feature`

**From source:** See README for Clojure setup instructions.

## Links
- [README](https://github.com/SHIFT-LEFTER/shiftlefter/blob/v0.1.0/README.md)
- [ERRATA (known edge cases)](https://github.com/SHIFT-LEFTER/shiftlefter/blob/v0.1.0/ERRATA.md)
