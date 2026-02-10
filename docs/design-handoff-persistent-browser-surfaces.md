# Design Handoff: Persistent Browser Surfaces

**Date:** 2026-01-07
**Status:** Design phase, pre-implementation
**Context:** This document captures a design discussion for continuation in a separate session without codebase access.

---

## 1. Problem Statement

### The User Pain Point

When developing browser-based tests interactively via REPL, or using browser automation for general tasks (discovery, monitoring, agent workflows), the browser session frequently dies unexpectedly. Specifically:

1. Developer opens browser via REPL, logs into a site, navigates around
2. Developer's laptop sleeps or locks (macOS)
3. Developer wakes laptop, browser window is still visible and manually usable
4. Developer tries to continue automation via REPL
5. **Session explodes** - "invalid session id" error, browser windows force-close

This destroys workflow state: login sessions, cookies, navigation history, any accumulated context. The developer must start over from scratch.

### Who This Affects

- **Test developers** iterating on browser tests in REPL
- **Automation agents** (like "MonkeyButler") doing long-running browser tasks
- **Discovery workflows** where someone is exploring an app's behavior
- **General QoL** for anyone using browser automation interactively

### Who This Does NOT Affect

- **Normal test runs** - tests execute quickly, sleep/wake is not a factor
- **CI/CD pipelines** - headless, no sleep cycles
- **Short-lived automation scripts** - finish before any interruption

The feature should be **opt-in** and clearly separated from the default "ephemeral" mode that tests use.

---

## 2. Current State

### System Overview

ShiftLefter is a Gherkin-based test framework in Clojure. It has:

- **REPL API** (`shiftlefter.repl`) for interactive step execution
- **Named contexts** (`:alice`, `:bob`) for multi-actor scenarios
- **Browser protocol** (`IBrowser`) abstracting browser operations
- **Etaoin integration** wrapping the Etaoin WebDriver library
- **"Surfaces" concept** (partially implemented) for session persistence

### Current Browser Architecture

```
┌─────────────────┐     ┌──────────────┐     ┌─────────────┐
│  REPL / Runner  │────▶│  Etaoin API  │────▶│ ChromeDriver│
└─────────────────┘     └──────────────┘     └──────┬──────┘
                                                    │ DevTools
                                                    │ Protocol
                                                    ▼
                                             ┌─────────────┐
                                             │   Chrome    │
                                             └─────────────┘
```

When you call Etaoin's `chrome` function:
1. Etaoin spawns ChromeDriver (if not already running)
2. ChromeDriver spawns Chrome with a temporary profile
3. ChromeDriver establishes DevTools Protocol websocket to Chrome
4. Etaoin receives a **session ID** - an opaque handle to this connection
5. All subsequent commands go: Etaoin → ChromeDriver → Chrome via that session

### Current "Surfaces" Implementation

We have partial infrastructure for persisting browser sessions:

```clojure
;; What we currently persist (to .shiftlefter/<name>.edn)
{:webdriver-url "http://127.0.0.1:9515"
 :session-id "83f2117f0fc5b5884a07130dcc1c0b7b"
 :saved-at "2026-01-07T..."}
```

The idea was: save session ID, reload it later, reattach. **This doesn't work.**

### Why Current Approach Fails

Through debugging, we discovered:

1. After sleep/wake, the **session ID is still valid** in ChromeDriver's `/sessions` list
2. Chrome windows are **still open and manually usable**
3. But the first WebDriver command **fails with "invalid session id"**
4. This failure also **force-closes Chrome** and removes the session

**Root cause:** The DevTools Protocol websocket between ChromeDriver and Chrome dies during sleep (TCP timeout). ChromeDriver doesn't notice until you try to use it. When it discovers the dead pipe, it nukes everything.

Key insight from debugging:
- Session IDs are handles to a ChromeDriver↔Chrome connection
- The connection can die while both endpoints are healthy
- ChromeDriver has **no reconnection logic** - it treats this as fatal
- Persisting session IDs is fundamentally the wrong approach

---

## 3. Proposed Design

### Core Insight

**Don't persist session IDs. Persist Chrome instances.**

Chrome can be launched with `--remote-debugging-port=NNNN`, which:
- Opens an HTTP endpoint for DevTools Protocol
- Survives independently of any WebDriver session
- Can be connected to by creating a **new** session with `debuggerAddress` capability

### Two Modes of Operation

#### Mode 1: Ephemeral (Default)

Current behavior. Etaoin manages everything:
- ChromeDriver spawns Chrome with temporary profile
- Session lives for duration of test/script
- Everything cleaned up on completion
- No persistence, no reconnection concerns

This is what tests use. No changes needed.

#### Mode 2: Persistent (Opt-in "Surfaces")

New behavior for interactive/agent workflows:
- **We** launch Chrome with explicit debugging port and profile
- ChromeDriver connects to our Chrome instance
- Session IDs are ephemeral handles, created fresh each connect
- Chrome instance persists across sleep/wake, JVM restarts
- Profile directory persists cookies, localStorage, etc.

### What Persistent Surfaces Store

```clojure
{:debug-port 9222                                    ;; Chrome's DevTools port
 :user-data-dir "/Users/x/.shiftlefter/surfaces/alice" ;; Chrome profile path
 :chrome-pid 12345                                   ;; For detecting if Chrome died
 :created-at "2026-01-07T10:30:00Z"
 :last-connected-at "2026-01-07T14:22:00Z"}
```

**NOT** session IDs - those are ephemeral.

### Connection Flow for Persistent Mode

```
First use:
1. Allocate debug port (e.g., 9222)
2. Create profile directory
3. Launch Chrome: chrome --remote-debugging-port=9222 --user-data-dir=<path>
4. Create session via Etaoin with {:debuggerAddress "127.0.0.1:9222"}
5. Persist surface metadata (port, profile path, pid)

Reconnect (after sleep/wake or JVM restart):
1. Load surface metadata
2. Check if Chrome alive: GET http://127.0.0.1:9222/json/version
3. If alive: Create NEW session with debuggerAddress
4. If dead: Re-launch Chrome, then create session
5. Update last-connected-at
```

### Terminology

- **Surface**: A persistent browser context with its own profile and debug port
- **Ephemeral session**: Default mode, Etaoin-managed, disposable
- **Debug port**: Chrome's `--remote-debugging-port`, survives session death
- **Profile directory**: Chrome's `--user-data-dir`, where cookies/storage live

### Architecture Sketch

```
Ephemeral Mode (unchanged):
┌──────┐    ┌────────┐    ┌──────────────┐    ┌────────┐
│ REPL │───▶│ Etaoin │───▶│ ChromeDriver │───▶│ Chrome │ (temp profile)
└──────┘    └────────┘    └──────────────┘    └────────┘
                                 ▲
                                 │ manages lifecycle

Persistent Mode (new):
┌──────┐    ┌─────────────────┐    ┌────────┐
│ REPL │───▶│ Surface Manager │───▶│ Chrome │ (persistent profile)
└──────┘    └────────┬────────┘    └────┬───┘
                     │                  │
                     │    ┌──────────────┐
                     │    │ ChromeDriver │
                     │    └──────┬───────┘
                     │           │
                     └───────────┘
                     debuggerAddress connection
                     (sessions are ephemeral)
```

### API Sketch

```clojure
;; Creating a surface (REPL)
(repl/create-surface! :alice)
;; => Launches Chrome with debug port, creates profile dir, persists metadata

;; Connecting to a surface
(repl/connect-surface! :alice)
;; => Checks if Chrome alive, creates session, attaches to context

;; Using it (repl/as prepends :alice automatically)
(repl/as :alice "opens the browser to 'https://example.com'")

;; After sleep/wake - automatic reconnection
(repl/as :alice "clicks {:css \"button\"}")
;; => Detects dead session, reconnects to same Chrome, retries

;; Destroying a surface
(repl/destroy-surface! :alice)
;; => Kills Chrome, removes profile dir, clears metadata
```

### Alternatives Considered

#### Alternative 1: Keep trying to persist session IDs
**Rejected.** Session IDs are handles to a websocket connection. When the connection dies, the ID is meaningless. ChromeDriver provides no way to revive a session.

#### Alternative 2: Prevent sleep during automation
Using `caffeinate` or similar. **Rejected as primary solution** - doesn't help with JVM restarts, doesn't solve the fundamental fragility, bad UX for interactive workflows.

#### Alternative 3: Fork/patch ChromeDriver for reconnection
**Rejected.** Massive undertaking, maintenance burden, not our core competency.

#### Alternative 4: Use Playwright instead of WebDriver
**Deferred.** Playwright has better connection handling, but would require significant rework. May revisit for v0.3+.

### Open Questions

1. **Port allocation strategy**: Fixed ports per surface name? Dynamic with persistence? Range?
   - Proposal: Start at 9222, increment per surface, persist chosen port

2. **Profile directory location**:
   - Proposal: `~/.shiftlefter/surfaces/<name>/chrome-profile/`
   - Should be configurable

3. **Chrome binary discovery**: OS-specific, user might have multiple Chromes
   - Proposal: Default paths per OS, configurable override
   - macOS: `/Applications/Google Chrome.app/Contents/MacOS/Google Chrome`
   - Linux: `google-chrome` or `chromium` on PATH
   - Windows: TBD

4. **What happens when Chrome dies entirely?**
   - Proposal: Detect via PID check or debug port probe, offer to relaunch
   - Cookies/storage preserved in profile dir

5. **Multiple windows within a surface?**
   - For now: Single window per surface
   - Future: Could track window handles, but complex

6. **Cleanup policy**: When do old surfaces get garbage collected?
   - Proposal: Manual only for now (`destroy-surface!`)
   - Future: TTL-based cleanup option

7. **Headless persistent surfaces?**
   - Useful for server/CI contexts
   - Proposal: Support via flag, but default to headed for REPL use

---

## 4. Technical Constraints

### ChromeDriver Limitations

- **No session recovery**: Once websocket dies, session is gone
- **No reconnection**: Must create new session to existing Chrome
- **Aggressive cleanup**: On any error, tends to kill Chrome
- **Session ≠ Browser**: Session is a control channel, not the browser itself

### DevTools Protocol

- Chrome's `--remote-debugging-port` opens HTTP + WebSocket endpoint
- `GET /json/version` - check if Chrome is alive
- `GET /json/list` - enumerate pages/tabs
- Survives independently of WebDriver sessions
- **This is our reconnection path**

### Etaoin Specifics

```clojure
;; Normal Etaoin - it manages Chrome
(e/chrome)

;; Connect to existing Chrome via debuggerAddress
(e/chrome {:capabilities
           {:goog:chromeOptions
            {:debuggerAddress "127.0.0.1:9222"}}})
```

When using `debuggerAddress`:
- Etaoin doesn't launch Chrome (we do)
- Creates session to existing instance
- New session ID, but same Chrome/profile
- Window handles get new IDs (need to re-enumerate)

### macOS Sleep Behavior

- TCP connections time out during sleep
- Chrome process survives (unless memory pressure)
- DevTools websocket dies
- Chrome windows remain visible and interactive
- Manual browser use works fine; automation path breaks

### Our Architecture Constraints

- `IBrowser` protocol is our abstraction layer - good, can hide reconnection
- `EtaoinBrowser` wraps Etaoin driver - will need reconnection-aware variant
- Named contexts (`:alice`) map to browsers - natural fit for surfaces
- Current session store persists wrong thing (session ID)

---

## 5. Success Criteria

### Must Have

1. **Survive sleep/wake**: Developer can sleep laptop, wake up, continue automation
2. **Survive JVM restart**: Exit REPL, restart, reconnect to same browser
3. **Preserve login state**: Cookies, localStorage persist across reconnections
4. **Opt-in**: Default mode unchanged, surfaces are explicit
5. **Clean separation**: Test runs don't touch surface code paths

### Should Have

1. **Automatic reconnection**: First command after wake "just works" (maybe with brief delay)
2. **Graceful degradation**: If Chrome died entirely, clear error message
3. **Easy cleanup**: Simple way to destroy surfaces and reclaim resources

### Nice to Have

1. **Multiple surfaces**: `:alice` and `:bob` can have separate persistent browsers
2. **Headless option**: For server-side persistent automation
3. **Profile inspection**: Easy way to see what surfaces exist, their state

### Out of Scope (for now)

- Syncing surfaces across machines
- Encrypted profile storage
- Automatic surface garbage collection
- Firefox/Safari support (Chrome only initially)

---

## 6. Conversation Context

### How We Got Here

This design emerged from a debugging session where the user experienced the sleep/wake problem firsthand. Key moments:

#### The Initial Bug Report
User ran REPL commands, laptop slept, woke up, got "invalid session id" errors. Browser windows force-closed. Frustrating because Chrome looked fine.

#### The Investigation
We hypothesized several causes:
- ChromeDriver timeout?
- Chrome process dying?
- Session expiring?

User ran experiments:
1. Checked `/sessions` endpoint after wake - **session still listed**
2. Manually interacted with Chrome windows - **worked fine**
3. Sent REPL command - **exploded and killed everything**

This proved: Chrome healthy, session "valid", but connection broken.

#### The Aha Moment
Research revealed: ChromeDriver connects to Chrome via DevTools Protocol websocket. This websocket dies during sleep. ChromeDriver doesn't reconnect - it just nukes everything on first failed command.

**Session IDs are handles to dead websockets. Persisting them is pointless.**

#### The Pivot
If session IDs are worthless, what CAN we persist?

Answer: The Chrome instance itself. Launch Chrome with `--remote-debugging-port`, keep it alive, create **new** sessions that connect to the **same** Chrome. Profile directory preserves state.

#### Design Principles Established

1. **Two modes**: Ephemeral (tests) vs. Persistent (REPL/agents) - cleanly separated
2. **Chrome, not sessions**: Persist Chrome instance metadata, not session IDs
3. **Reconnection = new session**: Don't revive dead sessions, create fresh ones to living Chrome
4. **Profile = state**: Cookies etc. live in Chrome's user-data-dir, not WebDriver
5. **Opt-in complexity**: Default path stays simple, surfaces are power-user feature

#### User's Framing

The user emphasized:
- Normal tests shouldn't deal with this complexity
- This is QoL for interactive/agent use cases
- Will need careful documentation for real users
- Future: custom profiles, OS-specific Chrome discovery
- Want to do this right, hence spec-first approach

### Key Technical Discoveries

1. `curl http://127.0.0.1:9222/json/version` - probe if Chrome is alive
2. `{:debuggerAddress "127.0.0.1:9222"}` - Etaoin capability to connect to existing Chrome
3. Session IDs become meaningless on reconnect, but that's fine - Chrome state persists
4. Need to launch Chrome ourselves, not let Etaoin do it

### Decisions Made

- Surface metadata: port + profile path + PID, not session ID
- Location: `~/.shiftlefter/surfaces/<name>/`
- Approach: Launch Chrome ourselves with explicit flags
- Scope: Chrome only, macOS first (user's platform)

### Decisions Deferred

- Port allocation strategy
- Chrome binary discovery across OS
- Multi-window handling
- Cleanup/GC policy
- Headless support details

---

## Appendix: Useful Code Snippets

### Launching Chrome Manually
```bash
/Applications/Google\ Chrome.app/Contents/MacOS/Google\ Chrome \
  --remote-debugging-port=9222 \
  --user-data-dir="/tmp/chrome-profile"
```

### Checking if Chrome is Alive
```clojure
(require '[clj-http.client :as http])

(defn chrome-alive? [port]
  (try
    (http/get (str "http://127.0.0.1:" port "/json/version")
              {:timeout 1000})
    true
    (catch Exception _ false)))
```

### Connecting Etaoin to Existing Chrome
```clojure
(require '[etaoin.api :as e])

(def driver
  (e/chrome {:capabilities
             {:goog:chromeOptions
              {:debuggerAddress "127.0.0.1:9222"}}}))
```

### What Surface Metadata Looks Like
```clojure
{:debug-port 9222
 :user-data-dir "/Users/x/.shiftlefter/surfaces/alice"
 :chrome-pid 12345
 :created-at "2026-01-07T10:30:00Z"
 :last-connected-at "2026-01-07T14:22:00Z"}
```

---

*End of handoff document. This should provide sufficient context to continue design work without codebase access.*
