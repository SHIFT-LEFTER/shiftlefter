# What Can I Do with ShiftLefter?

ShiftLefter works in tiers. Each tier adds capabilities without removing any from the previous one. Most users only need Java.

## At a Glance

| You have | You can |
|---|---|
| **Java 11+** | Run features, format files, start the REPL, use built-in browser steps |
| **Java 11+ and write `.clj` files** | All of the above, plus write custom step definitions using 8 bundled libraries |
| **Java 11+ and Clojure CLI** | All of the above, plus use your own `deps.edn`, add any Clojure library |

---

## Tier A: Run & Format (Java only)

**Prerequisites:** Java 11 or later. Nothing else.

**What you can do:**

- `sl run features/ --step-paths steps/` — execute Gherkin scenarios against step definitions
- `sl fmt --check *.feature` — validate formatting
- `sl fmt --write *.feature` — auto-format in place
- `sl verify` — run validator checks
- `sl gherkin fuzz` — fuzz test the parser
- `sl repl` — start an interactive Clojure REPL with ShiftLefter loaded
- `sl repl --nrepl` — start an nREPL server for IDE integration (VS Code/Calva, Emacs/CIDER)

You can use the [built-in browser steps](browser-getting-started.md) to drive Chrome/Firefox without writing any code.

---

## Tier B: Write Custom Steps (Java only)

**Prerequisites:** Same as Tier A. No Clojure CLI needed.

**What's new:** Write `.clj` step definition files. The runner loads them via `load-file`, giving your code access to everything bundled in the JAR.

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
| `clojure` | 1.12.3 | Core language + `clojure.test`, `clojure.string`, etc. | `(str/split s #",")` |

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

---

## Tier C: Full Development (Java + Clojure CLI)

**Prerequisites:** Java 11+ and [Clojure CLI](https://clojure.org/guides/install_clojure)

**What's new:**

- `sl repl --clj` — start a REPL that merges your `deps.edn`, giving access to any Clojure library
- Use third-party libraries in step definitions (beyond what's bundled)
- Develop on ShiftLefter itself (run from source, modify, test)

Use this tier when:
- You need a library that isn't bundled (e.g., a database driver, HTTP client library)
- You want to develop or contribute to ShiftLefter

---

## Browser Backend Configuration

ShiftLefter supports multiple browser automation backends via the `IBrowser` protocol.

### Available Backends

| Backend | Library | Config value | Bundled? | Best for |
|---|---|---|---|---|
| **Etaoin** | `etaoin/etaoin` | `:etaoin` (default) | Yes | Standard browser testing, Chrome/Firefox via WebDriver |
| **Playwright** | `com.microsoft.playwright/playwright` | `:playwright` | No — add to your `deps.edn` | Cross-browser (Chromium, Firefox, WebKit), modern sites |

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

### Using Playwright

Playwright is not bundled with ShiftLefter. To use it:

1. Add the dependency to your project's `deps.edn`:

```edn
{:deps {com.microsoft.playwright/playwright {:mvn/version "1.50.0"}}}
```

2. Playwright automatically downloads browser binaries on first use.

3. Set `:adapter :playwright` in your `shiftlefter.edn` (see above).

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
| Use a Clojure library not in the bundled list | Tier C — install Clojure CLI |
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

Check the bundled list above. If the library isn't there, you need Tier C (Clojure CLI) to add it via `deps.edn`.

### Step definition loading order

Files are loaded in sorted path order. If `b_steps.clj` needs something from `a_steps.clj`, it works because `a` sorts before `b`. For explicit control, use `require`.
