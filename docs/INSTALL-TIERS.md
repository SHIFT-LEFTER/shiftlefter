# What Can I Do with ShiftLefter?

ShiftLefter works in tiers. Each tier adds capabilities without removing any from the previous one. Most users only need Java.

## At a Glance

| You have | You can |
|---|---|
| **Java 21+** | Run features, format files, start the REPL, use built-in browser steps |
| **Java 21+ and write `.clj` files** | All of the above, plus write custom step definitions using the bundled libraries (HTTP, JSON, filesystem, browser, async, …) — and use third-party libraries by dropping their jars in `sl/lib/` |
| **Java 21+ and Clojure CLI** | All of the above, plus resolve dependencies from `deps.edn` coordinates and develop ShiftLefter itself |

---

## Tier A: Run & Format (Java only)

**Prerequisites:** Java 21 or later — nothing else for running and formatting.
Driving a real browser additionally needs Chrome and a matching **ChromeDriver**
(see the browser-steps note below).

**Platforms:** macOS and Linux — the `sl` launcher and installer are bash
scripts, so Windows is out of scope today (`java -jar` may work, but nothing
is tested there).

**What you can do:**

- `sl run features/ --step-paths steps/` — execute Gherkin scenarios against step definitions
- `sl fmt --check *.feature` — validate formatting
- `sl fmt --write *.feature` — auto-format in place
- `sl verify` — run validator checks
- `sl gherkin fuzz` — fuzz test the parser
- `sl repl` — start an interactive Clojure REPL with ShiftLefter loaded
- `sl repl --nrepl` — start an nREPL server for IDE integration (VS Code/Calva, Emacs/CIDER)

You can use the [built-in browser steps](browser-getting-started.md) to drive Chrome (or Firefox via the Playwright backend) without writing any code. This needs a matching **ChromeDriver** — found on your `PATH` or via `:chromedriver-path` in your config, so it doesn't have to be on `PATH`.

---

## Tier B: Write Custom Steps (Java only)

**Prerequisites:** Same as Tier A. No Clojure CLI needed.

**What's new:** Write `.clj` step definition files — for the cases the built-in
vocabulary doesn't cover: **test fixtures / data setup**, genuinely **niche web**
(canvas, custom JS), or a **new interface**. For ordinary web/SMS behavior you
shouldn't need this — use built-in steps and the glossary (see
[Add domain language](extending-vocabulary.md)). When you do need it, the runner
loads `.clj` files via `load-file`, giving your code access to everything bundled
in the JAR.

### Bundled Libraries

These Clojure libraries are included and available in your step definitions:

| Library | Version | What it does | Example |
|---|---|---|---|
| `cheshire` | 5.13.0 | JSON encoding/decoding | `(json/parse-string s true)` |
| `babashka/fs` | 0.5.20 | Filesystem operations | `(fs/exists? "file.txt")` |
| `etaoin` | 1.1.42 | Browser automation | `(e/go driver "https://...")` |
| `core.async` | 1.6.681 | Async channels | `(async/>!! ch val)` |
| `spec.alpha` | 0.5.238 | Data validation | `(s/valid? ::spec data)` |
| `test.check` | 1.1.2 | Property-based testing | `(gen/sample (s/gen int?))` |
| `clj-http` | 3.13.0 | HTTP client | `(http/get "https://...")` |
| `http-kit` | 2.8.0 | Async HTTP client/server | `(hk/get "https://...")` |
| `tools.logging` | 1.3.0 | Logging facade | `(log/info "...")` |
| `clojure` | 1.12.3 | Core language + `clojure.test`, `clojure.string`, etc. | `(str/split s #",")` |

> This is the subset most useful when writing steps. The released jar bundles a
> few more (e.g. `asami`, `tools.cli`, `nrepl`); anything on the jar's classpath is
> available to your step definitions via `require`.

### Java Standard Library

The full Java standard library is available via Clojure's Java interop:

| Package | Useful classes | Example use case |
|---|---|---|
| `java.time` | `LocalDate`, `Instant`, `Duration` | Date arithmetic, timestamps |
| `java.net` | `URI`, `HttpURLConnection` | URL parsing, HTTP calls |
| `java.security` | `MessageDigest` | Hashing (SHA-256, MD5) |
| `java.nio.file` | `Files`, `Path` | Modern file I/O |
| `java.util` | `UUID`, `Base64` | Unique IDs, encoding |

### Example Step Definition

```clojure
(ns my.steps
  (:require [shiftlefter.stepengine.registry :refer [defstep]]
            [cheshire.core :as json]
            [babashka.fs :as fs]))

(defstep #"I load config from \"([^\"]+)\"" [ctx path]
  (let [data (json/parse-string (slurp path) true)]
    (assoc ctx :config data)))

(defstep #"the config file should exist" [ctx]
  (when-not (fs/exists? (get-in ctx [:config :path]))
    (throw (ex-info "Config file missing" {})))
  ctx)
```

No `deps.edn`, no dependency management. Just write the file and point `sl run` at it.

### Third-Party Libraries: `sl/lib/`

When a step, hook, or custom adapter needs a library that isn't bundled — the
flagship case is a JDBC driver for a database-seeding hook — drop the
library's jar into your project's `sl/lib/` directory:

```
your-project/
  sl/
    shiftlefter.edn
    lib/
      postgresql-42.7.4.jar
```

Every jar in `sl/lib/` rides the classpath of every run — the cold path and
the warm daemon alike (the daemon restarts itself when the lib set changes,
just as it does on a framework upgrade). The framework jar always comes
first, so user jars can't shadow ShiftLefter's own code. `sl repl` (the
bundled REPL) sees the same classpath, so what you explore is what runs.

Two things to know:

- **Bring resolved jars, not coordinates.** `sl/lib/` does no dependency
  resolution: if a library has its own dependencies, drop those jars in too.
  For real transitive resolution from `deps.edn` coordinates, that's Tier C.
- **User jars are user code.** They run in-process with the same trust as
  your `load-file`'d step definitions — this is a visibility convention, not
  a sandbox.

Worked example with exact download links: [Using Playwright](#using-playwright)
wires a whole browser backend this way.

---

## Tier C: Full Development (Java + Clojure CLI)

**Prerequisites:** Java 21+ and [Clojure CLI](https://clojure.org/guides/install_clojure)

**What's new:**

- Real dependency **resolution**: declare `deps.edn` coordinates and let the
  Clojure CLI fetch a library *and its transitive dependencies* — versus
  `sl/lib/`'s bring-your-own-resolved-jars
- `sl repl --clj` — start a REPL that merges your `deps.edn`
- Develop on ShiftLefter itself (run from source, modify, test)

Use this tier when:
- A library's dependency tree is deep enough that hand-collecting jars for `sl/lib/` stops being fun
- You want to develop or contribute to ShiftLefter

---

## Browser Backend Configuration

ShiftLefter supports multiple browser automation backends via the `IBrowser` protocol.

### Available Backends

| Backend | Library | Config value | Bundled? | Best for |
|---|---|---|---|---|
| **Etaoin** | `etaoin/etaoin` | `:etaoin` (default) | Yes | Standard browser testing, Chrome/Firefox via WebDriver |
| **Playwright** | `com.microsoft.playwright/playwright` | `:playwright` | No — see [Using Playwright](#using-playwright) | Cross-browser (Chromium, Firefox, WebKit), modern sites |

### Backend differences

Both backends honor the same **step contract**: a feature file that runs on
one runs on the other, with the same step vocabulary, the same planning
errors, and the same nested-addressing semantics (both are live-verified
against the same fixture DOMs). Full behavioral parity, though, would be a
lie of the kinda-mostly class — if the backends did not act differently we
would not provide both. This table is the honest asterisk:

| Where they differ | WebDriver (`:etaoin`) | Playwright (`:playwright`) |
|---|---|---|
| Costumes (persistent authenticated sessions) | Yes — attach to a running Chrome profile | Not available: a costume is a WebDriver-owned Chrome profile |
| Nested-pruning strategy (`:pruning`) | Always fused/fast — WebDriver has no retrying-locator concept, so there is nothing to trade away | `:fast` (default) or `:safe` — see below |
| Automation-hygiene Chrome prefs | On by default via profile prefs (see below) | Popups can't occur: sessions are off-the-record contexts, so the password machinery has no profile to run against (no prefs channel exists, none needed) |
| Capture kinds (`:runner {:capture …}`) | Provides `screenshot`, `console` (Chrome/Edge only), `page-source` | Contributes no capture kinds yet (planned for 0.5.5) — a configured kind never fires on Playwright |
| Element staleness posture | Point-in-time element references | `:fast` = point-in-time handles; `:safe` = auto-retrying Locators |

#### The `:pruning` knob (Playwright-only meaning)

Nested addressing (`Dashboard.featured[1].title`) prunes candidates that
belong to a *nested* component instance. Playwright can do that filtering
two ways, chosen per interface in `shiftlefter.edn`:

```edn
{:interfaces {:web {:adapter :playwright
                    :pruning :fast}}}   ; the default — omit unless :safe
```

- **`:fast` (default)** — one fused browser round trip per address segment,
  matching the Etaoin backend. Results are **point-in-time element
  snapshots**: they reference the DOM as it was at resolution. If the page
  navigates or the matched region is re-rendered, a stale reference errors —
  re-resolve the address (run the step again) rather than holding results
  across page changes. This is the framework's §8 point-in-time posture, and
  what the Etaoin backend always does.
- **`:safe` (opt-in)** — Playwright's auto-retrying Locators all the way
  down: stale-safe across re-renders, at the cost of **one browser call per
  candidate per segment** (a 200-item collection costs ~200 round trips
  where `:fast` costs one). Reach for it only if a dynamic page makes
  snapshot staleness a real problem.

The knob exists only where the trade exists: Etaoin has no retrying-locator
concept, so it is always fused/fast and a `:safe` setting there would buy
zero staleness protection for strictly more round trips.

### Selecting a Backend

In your `shiftlefter.edn`:

```edn
{:interfaces
 {:web {:adapter :playwright
        :config {:headless true}}}}
```

The default is `:etaoin` — no configuration needed.

### Passing Backend-Specific Options

Use `:adapter-opts` in your interface config to pass options directly to the backend library. These are merged into the backend's native option map without translation.

**Etaoin examples:**

```edn
{:interfaces
 {:web {:adapter :etaoin
        :config {:headless true
                 :adapter-opts {:size {:width 1280 :height 720}
                                :prefs {"download.default_directory" "/tmp"}}}}}}
```

**Playwright examples:**

```edn
{:interfaces
 {:web {:adapter :playwright
        :config {:headless false
                 :adapter-opts {:browser-type :firefox}}}}}
```

### Default Automation-Hygiene Prefs (Etaoin)

The `:etaoin` adapter sets three Chrome profile prefs by default so native
password popups never interrupt an automation session — they're invisible
to WebDriver (native UI, not DOM) but noisy in demos and headed runs:

| Pref | Default | Suppresses |
|------|---------|------------|
| `"profile.password_manager_leak_detection"` | `false` | "Password found in a data breach" popup (fires on any test credential in public breach lists) |
| `"credentials_enable_service"` | `false` | "Save password?" bubble |
| `"profile.password_manager_enabled"` | `false` | Password-manager autofill offers |

These merge **under** your `:adapter-opts` `:prefs`, key by key — anything
you set wins, and setting one key back does not lose the other defaults:

```edn
{:interfaces
 {:web {:adapter :etaoin
        :config {:adapter-opts
                 ;; opt back in to leak detection; the other two defaults stay
                 {:prefs {"profile.password_manager_leak_detection" true}}}}}}
```

Only these password-popup prefs are defaulted — nothing that changes page
feature behavior (notifications, autofill of forms, translate) is touched.

**Playwright needs none of this — same outcome, different mechanism.** Every
Playwright session runs in an isolated off-the-record browser context, and
the password machinery (save bubble, leak detection, autofill offers) has no
profile to run against — on the bundled Chromium and on `:channel`-driven
system Chrome/Edge alike. There is no prefs channel under Playwright and no
`:prefs` adapter-opt; a future costume-style attach to a *real* Chrome
profile would inherit that profile's own hygiene settings (sl-x7ec records
the decision and its reopening conditions).

### Using Playwright

Playwright is supported but not bundled with ShiftLefter — the driver
bundle jar alone is ~200MB, several times the size of the whole ShiftLefter
jar. A config that selects `:adapter :playwright` without the dependency on
the classpath fails at config load with instructions (it does not get as
far as running scenarios). There are two ways to wire it up; both end with
setting `:adapter :playwright` in your `shiftlefter.edn` (see above).

**Tier A/B — drop three jars in `sl/lib/`** (no Clojure CLI needed):

Download these three jars — exact links, version-pinned, no inference
needed — and put them in your project's `sl/lib/` directory (see
[Third-Party Libraries](#third-party-libraries-sllib)):

- [playwright-1.50.0.jar](https://repo1.maven.org/maven2/com/microsoft/playwright/playwright/1.50.0/playwright-1.50.0.jar) (~600KB)
- [driver-1.50.0.jar](https://repo1.maven.org/maven2/com/microsoft/playwright/driver/1.50.0/driver-1.50.0.jar) (~7KB)
- [driver-bundle-1.50.0.jar](https://repo1.maven.org/maven2/com/microsoft/playwright/driver-bundle/1.50.0/driver-bundle-1.50.0.jar) (~200MB)

All three must be the same version. Nothing to restart: the next `sl run`
picks them up (the warm daemon restarts itself when `sl/lib/` changes).
This path is verified end to end — the driver bundle self-extracts its
runtime from the classpath jar on both the cold and warm execution paths
(sl-wgnw).

**Tier C — one `deps.edn` coordinate** (Clojure CLI resolves the rest):

```edn
{:deps {com.microsoft.playwright/playwright {:mvn/version "1.50.0"}}}
```

The jar covers all platforms automatically — there are no platform-specific
coordinates or instructions. Like any Maven dependency it is cached per-user
in `~/.m2`, so multiple ShiftLefter projects share one copy.

**Browsers.** Playwright drives its own patched browser builds, which are
never inside the jar: on first use it downloads Chromium, Firefox, and
WebKit (hundreds of MB) from Microsoft's CDN. Two things keep that cost
small in practice:

- **The download happens once per user, not per project.** Browsers live in
  a single per-user cache — macOS `~/Library/Caches/ms-playwright`, Linux
  `~/.cache/ms-playwright`, Windows `%USERPROFILE%\AppData\Local\ms-playwright` —
  shared across all your projects *and* across Playwright language bindings
  (Node, Python, Java, .NET use the same cache and build numbering). If any
  prior Playwright project already fetched browsers, they are reused
  automatically; only missing builds download.
- **You can skip the download entirely.** Set the
  `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD` environment variable and pass a
  `:channel` in `:adapter-opts` to drive your system-installed Chrome or
  Edge with zero download.

Two honest caveats: upgrading the Playwright version accumulates browser
build generations in the cache — this costs disk but never conflicts
(`playwright uninstall --all` resets it). And for CI or corporate layouts
that need the cache somewhere specific, `PLAYWRIGHT_BROWSERS_PATH` is the
explicit relocate/share lever.

---

## REPL at Each Tier

| Command | Tier | What it does |
|---|---|---|
| `sl repl` | A/B | Interactive Clojure REPL with ShiftLefter loaded. Java only. |
| `sl repl --nrepl` | A/B | nREPL server with CIDER middleware. Connect from VS Code, Emacs, IntelliJ. Java only. |
| `sl repl --clj` | C | REPL using Clojure CLI. Merges your `deps.edn` for custom libraries. |

---

## Decision Guide

| You want to... | Start with |
|---|---|
| Run existing feature files | Tier A — download the release zip |
| Validate and format `.feature` files in CI | Tier A |
| Use built-in browser steps (no code) | Tier A + ChromeDriver |
| Write custom step definitions | Tier B — same install, start writing `.clj` files |
| Use a Clojure library not in the bundled list | Tier B — drop its jar (and its dependencies' jars) in `sl/lib/` |
| Resolve a deep dependency tree from coordinates | Tier C — install Clojure CLI |
| Connect your IDE to a REPL | Tier A/B (`sl repl --nrepl`) or Tier C (`sl repl --clj`) |
| Contribute to ShiftLefter | Tier C — see [CONTRIBUTING.md](../CONTRIBUTING.md) |

---

## Troubleshooting

### Missing library in step definition

If your step definition requires a library that isn't bundled:

```
Could not locate some/library/name__init.class, some/library/name.clj
or some/library/name.cljc on classpath.
```

Check the bundled list above. If the library isn't there, drop its jar (plus
its dependencies' jars) into your project's `sl/lib/` directory — see
[Third-Party Libraries](#third-party-libraries-sllib) under Tier B. For
transitive resolution from coordinates, use Tier C (`deps.edn`).

### Step definition loading order

Files are loaded in sorted path order. If `b_steps.clj` needs something from `a_steps.clj`, it works because `a` sorts before `b`. For explicit control, use `require`.
