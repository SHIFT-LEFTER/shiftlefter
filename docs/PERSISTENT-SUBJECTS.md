# Persistent Subjects Guide

Persistent subjects are named browser instances that survive JVM restarts. Use them for:

- **REPL development** — Keep your logged-in session across code reloads
- **Long-running automation** — Browser stays open while your agent works
- **Exploratory testing** — Pause, inspect, continue without losing state

## Quick Start

```clojure
(require '[shiftlefter.repl :as repl])

;; Create a new persistent subject
(repl/init-persistent-subject! :finance {:stealth true})
;; => {:status :connected :subject :finance :port 9222 :pid 12345 ...}

;; Chrome window opens. Log in, navigate around...

;; Later, after JVM restart:
(repl/connect-persistent-subject! :finance)
;; => {:status :connected :subject :finance :port 9222 ...}

;; Your cookies, localStorage, and navigation history are preserved!

;; When done:
(repl/destroy-persistent-subject! :finance)
;; => {:status :destroyed :subject :finance}
```

## How It Works

### What Gets Persisted

Each subject has a profile directory at `~/.shiftlefter/subjects/<name>/`:

```
~/.shiftlefter/subjects/finance/
├── browser-meta.edn      # Port, PID, settings
└── chrome-profile/       # Chrome user data directory
    ├── Cookies
    ├── Local Storage/
    ├── History
    └── ...
```

The Chrome profile contains everything Chrome normally stores:
- Cookies and session data
- localStorage and IndexedDB
- History and bookmarks
- Extensions (if installed)
- Cached credentials

### What's NOT Persisted

- **WebDriver session IDs** — These are ephemeral handles that die on sleep/wake
- **In-memory JavaScript state** — Page reloads clear this
- **Unsaved form data** — Fill and submit before sleeping!

## Lifecycle Functions

### `init-persistent-subject!`

Creates a new subject from scratch.

```clojure
(repl/init-persistent-subject! :work)
;; Uses defaults

(repl/init-persistent-subject! :secure {:stealth true})
;; With anti-detection flags

(repl/init-persistent-subject! :custom {:chrome-path "/path/to/chrome"})
;; With explicit Chrome binary
```

**Returns:**
```clojure
{:status :connected
 :subject :work
 :port 9222
 :pid 12345
 :browser <PersistentBrowser>}
```

**Errors:**
- `:subject/already-exists` — Subject with this name exists (use `connect-persistent-subject!` instead)
- `:subject/init-failed` — Chrome launch or connection failed

### `connect-persistent-subject!`

Reconnects to an existing subject after JVM restart.

```clojure
(repl/connect-persistent-subject! :work)
```

If Chrome is still running, creates a new WebDriver session to it.
If Chrome died, relaunches it with the same profile.

**Errors:**
- `:subject/not-found` — No subject with this name
- `:subject/connect-failed` — Chrome relaunch or connection failed

### `destroy-persistent-subject!`

Kills Chrome and deletes the profile directory.

```clojure
(repl/destroy-persistent-subject! :work)
;; => {:status :destroyed :subject :work}
```

**Warning:** This permanently deletes cookies, history, and all Chrome data for the subject.

### `list-persistent-subjects`

Shows all subjects and their status.

```clojure
(repl/list-persistent-subjects)
;; => [{:name "finance" :status :alive :port 9222 :pid 12345 ...}
;;     {:name "work" :status :dead :port 9223 :pid nil ...}]
```

Status values:
- `:alive` — Chrome is running
- `:dead` — Chrome not running (needs `connect-persistent-subject!` to relaunch)
- `:unknown` — Metadata file missing or corrupt

## Using the Browser

After connecting, use the browser via REPL free mode:

```clojure
;; Navigate
(repl/free :finance "I open the browser to 'https://app.example.com'")

;; Interact
(repl/free :finance "I click {:css \"button.submit\"}")
(repl/free :finance "I fill {:id \"email\"} with 'user@example.com'")

;; Query
(repl/free :finance "I should see {:text \"Welcome\"}")
```

Or access the browser directly:

```clojure
(require '[shiftlefter.browser.protocol :as bp])

(let [{:keys [browser]} (repl/connect-persistent-subject! :finance)]
  (bp/open-to! browser "https://example.com")
  (bp/click! browser {:q {:css "button"}}))
```

## Auto-Reconnection

The `PersistentBrowser` wrapper automatically handles session death:

1. Your laptop sleeps, WebDriver session dies
2. You wake laptop, Chrome window is still there
3. You run a browser command
4. ShiftLefter detects "invalid session id" error
5. Automatically creates new WebDriver session
6. Retries your command
7. Works transparently!

You don't need to manually reconnect after sleep/wake.

## Configuration

### User-Level Defaults

Set defaults in `~/.shiftlefter/config.edn`:

```clojure
{:chrome-path "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
 :default-stealth true}
```

Now all subjects use these defaults:

```clojure
(repl/init-persistent-subject! :finance)
;; Automatically uses /Applications/... and stealth=true
```

Explicit options override:

```clojure
(repl/init-persistent-subject! :work {:stealth false})
;; Uses config chrome-path, but stealth=false
```

### Stealth Mode

For sites with bot detection, enable stealth:

```clojure
(repl/init-persistent-subject! :scraper {:stealth true})
```

This adds Chrome flags:
- `--disable-blink-features=AutomationControlled`

For advanced anti-detection, see `docs/STEALTH.md`.

## Troubleshooting

### "Subject already exists"

```clojure
(repl/init-persistent-subject! :finance)
;; => {:error {:type :subject/already-exists ...}}
```

The subject was created previously. Either:
- Connect to it: `(repl/connect-persistent-subject! :finance)`
- Destroy and recreate: `(repl/destroy-persistent-subject! :finance)`

### "Subject not found"

```clojure
(repl/connect-persistent-subject! :typo)
;; => {:error {:type :subject/not-found ...}}
```

Check the name. List existing subjects:
```clojure
(repl/list-persistent-subjects)
```

### Chrome window closes unexpectedly

The WebDriver session may have timed out. The window closes because ChromeDriver cleans up. With persistent subjects, this shouldn't happen because we launch Chrome directly without ChromeDriver managing the lifecycle.

If it does happen:
```clojure
(repl/connect-persistent-subject! :finance)
;; Will relaunch Chrome with the same profile
```

### "Chrome not found"

ShiftLefter couldn't locate Chrome. Either:

1. Install Chrome
2. Specify the path:
   ```clojure
   (repl/init-persistent-subject! :work {:chrome-path "/path/to/chrome"})
   ```
3. Set in user config: `~/.shiftlefter/config.edn`

### Port conflicts

ShiftLefter auto-allocates ports starting at 9222. If all ports 9222-9322 are in use:

```clojure
;; => {:error {:type :persistent/no-port-available ...}}
```

Check for zombie Chrome processes:
```bash
ps aux | grep chrome | grep remote-debugging-port
```

Kill orphaned processes or reboot.

## Best Practices

### Name subjects meaningfully

```clojure
;; Good
:finance-prod
:dev-admin
:test-user-alice

;; Less good
:browser1
:temp
```

### Don't share subjects

Each subject should be used by one JVM at a time. Multiple JVMs trying to control the same Chrome will cause session conflicts.

### Clean up unused subjects

```clojure
;; List all
(repl/list-persistent-subjects)

;; Remove ones you don't need
(repl/destroy-persistent-subject! :old-test)
```

### Use stealth for real sites

If automating a real website (not your own test app), enable stealth to avoid triggering bot detection:

```clojure
(repl/init-persistent-subject! :real-site {:stealth true})
```

## Comparison: Ephemeral vs Persistent

| Aspect | Ephemeral (default) | Persistent Subject |
|--------|--------------------|--------------------|
| Created via | `sl run`, test runner | `init-persistent-subject!` |
| Profile | Temp directory, deleted on close | `~/.shiftlefter/subjects/<name>/` |
| Survives JVM restart | No | Yes |
| Survives sleep/wake | No | Yes (auto-reconnect) |
| Use case | CI, test runs | REPL dev, exploration |
| Cleanup | Automatic | Manual (`destroy-persistent-subject!`) |

## API Reference

| Function | Purpose |
|----------|---------|
| `init-persistent-subject!` | Create new subject |
| `connect-persistent-subject!` | Reconnect after restart |
| `destroy-persistent-subject!` | Kill Chrome, delete profile |
| `list-persistent-subjects` | Show all subjects with status |

All functions are available in both `shiftlefter.repl` and `shiftlefter.subjects`.
