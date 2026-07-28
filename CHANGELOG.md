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
<!-- no-entry: sl-0ni2 (release-process bead: cut & tag v0.5.3) -->
<!-- no-entry: sl-7rb6 (test-side: per-JVM corpus scratch dirs make concurrent suite runs safe; no shipped surface) -->
<!-- no-entry: sl-8mz (test-strengthening board pass: REPL costume flow + persistent-skip/selectivity tests, findings report; no shipped surface) -->
<!-- no-entry: sl-mft6 (internal eval redesign: cross-interface-add retargeted from custom-glue authoring to data-plane discoverability; evals/ not in the public export) -->
<!-- no-entry: sl-686 (internal doctrine: three-deployment-instantiations canon page in the sl-vision wiki + agent-prompt pointers; packaged AGENT.md untouched, no shipped surface) -->
<!-- no-entry: sl-qo7 (internal doctrine: code-verified claim-stamp convention in wiki SCHEMA + stale release-map fix; no shipped surface) -->
<!-- no-entry: sl-ybox (test-side: planning-level validators for examples 02b/03/svo-demo in the bare unit suite; guards public examples, ships nothing) -->
<!-- no-entry: sl-kh5r (internal: cleanup-seam consolidation; no user surface) -->

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
