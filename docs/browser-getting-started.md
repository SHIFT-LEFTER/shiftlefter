# Browser Testing Quick Start

ShiftLefter supports browser automation via WebDriver (Etaoin backend).

## Prerequisites

1. **Chrome browser** installed
2. **ChromeDriver** matching your Chrome version

### Install ChromeDriver (macOS)

```bash
brew install chromedriver
```

### Start ChromeDriver

```bash
chromedriver --port=9515
```

Keep this running in a separate terminal.

## REPL Workflow

The recommended way to develop browser tests is via the REPL.

### 1. Start a REPL

```bash
clj -M:dev
```

### 2. Load browser stepdefs

```clojure
(require '[shiftlefter.repl :as repl])
(require '[shiftlefter.stepdefs.browser])
(require '[shiftlefter.browser.ctx :as browser.ctx])
(require '[shiftlefter.webdriver.etaoin.session :as session])
```

### 3. Create a browser session

```clojure
;; Create driver pointing to chromedriver
(def driver (session/make-driver "http://127.0.0.1:9515"))

;; Create a session (opens browser window)
(def result (session/create-session! driver))

;; result contains:
;;   :ok           - driver map with session attached
;;   :browser      - EtaoinBrowser implementing IBrowser protocol
;;   :etaoin-driver - raw Etaoin driver for advanced use

(def my-browser (:browser result))
```

### 4. Attach browser to a named context

```clojure
;; Clear any previous state
(repl/clear!)

;; Attach the browser to :alice context
(repl/set-ctx! :alice (browser.ctx/assoc-active-browser {} my-browser))
```

### 5. Run steps interactively

```clojure
;; Navigate
(repl/as :alice "I open the browser to 'https://example.com'")

;; Click
(repl/as :alice "I click {:css \"a\"}")

;; Count elements
(repl/as :alice "I count {:css \"h1\"} elements")
```

### 6. Use Surfaces for persistent sessions

```clojure
;; Mark a context as a surface (session persists across resets)
(repl/mark-surface! :alice)

;; Now when you call reset-ctxs!, the browser stays open
(repl/reset-ctxs!)

;; Session is saved to .shiftlefter/alice.edn
;; Next time you start, you can reattach to the existing session
```

### 7. Clean up

```clojure
;; Close the session (pass the driver, not browser)
(session/close-session! (:ok result))

;; Or clear everything (closes non-surface sessions)
(repl/clear!)
```

## Locator Syntax

Locators use EDN syntax in step text:

| Syntax | Example |
|--------|---------|
| CSS | `{:css "#login"}` or `[:css "#login"]` |
| XPath | `{:xpath "//button"}` |
| ID | `{:id "submit"}` |
| Name | `{:name "email"}` |
| Class | `{:class "btn-primary"}` |
| Tag | `{:tag "button"}` |

## Available Steps

```gherkin
# Navigation
I open the browser to '<url>'

# Clicks
I click {<locator>}
I double-click {<locator>}
I right-click {<locator>}

# Mouse
I move to {<locator>}
I drag {<from-locator>} to {<to-locator>}

# Input
I fill {<locator>} with '<text>'

# Query
I count {<locator>} elements
```

## CLI Usage

Run a feature file with browser steps:

```bash
# Ensure chromedriver is running on port 9515
./bin/sl run test/fixtures/features/browser_smoke.feature
```

Note: CLI mode is safe-by-default. Browser sessions are closed after each scenario.

## Configuration

Configure WebDriver endpoint in your config:

```clojure
;; shiftlefter.edn
{:webdriver {:host "127.0.0.1"
             :port 9515}}

;; Or direct URL
{:webdriver-url "http://127.0.0.1:9515"}
```

## Troubleshooting

### "No browser configured in context"

The step is running but no browser session exists in the context. Ensure you've created a session and attached it to the context.

### "session not created" from ChromeDriver

- Check ChromeDriver version matches Chrome version
- Ensure ChromeDriver is running (`chromedriver --port=9515`)
- Check no other process is using port 9515

### Session dies unexpectedly

If you're using surfaces and the session dies (Chrome closed manually, ChromeDriver restarted), the next operation will return `:webdriver/session-dead`. Clear and recreate:

```clojure
(repl/clear!)
;; Recreate session as above
```
