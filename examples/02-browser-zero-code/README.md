# Browser Test — Zero Custom Code

Run browser automation tests without writing any step definitions. ShiftLefter
includes built-in steps for common web interactions — the feature file below is
the *entire* test.

> **Mode: Vanilla** — deliberately. There is no `:svo` in the config, so any
> subject name is accepted and ShiftLefter behaves like a standard Gherkin
> runner (`sl orient` will say so). That's the zero-setup entry point; the
> next example, [`02b`](../02b-browser-multi-actor/), adds the glossary and
> runs Shifted.

> **Fully offline.** The app under test is ShiftLefter's bundled fixture
> server — `setup.clj` starts it on `127.0.0.1` before the run and stops it
> after. No network, no third-party site.

## "Zero code"?

Zero **custom step definitions** — every step below is a built-in. There is
one small Clojure file here, `setup.clj`, and it's *orchestration*, not test
code: it starts the app the test drives, using classes already inside the
ShiftLefter jar (no Clojure toolchain needed). That's the framework's
run-level orchestration convention — a `setup.clj` next to `shiftlefter.edn`
is loaded automatically; see `ARCHITECTURE.md` § Test Orchestration and
example [`04`](../04-sms-2fa/), where you first write one of your own.

## What You Get

A working browser test that:

- Opens pages
- Fills forms
- Clicks buttons
- Verifies page content, titles, and URLs
- Checks element visibility

All in plain Gherkin — no step definitions to write.

## The Feature File

```gherkin
# features/login.feature
Feature: Login page

  Scenario: Page loads with expected elements
    When :user opens the browser to 'http://127.0.0.1:9094/login'
    Then :user should see the title 'Login'
    And :user should see {:id "username"}
    And :user should see {:id "password"}
    And :user should see {:css "button[type='submit']"}

  Scenario: Successful login
    When :user opens the browser to 'http://127.0.0.1:9094/login'
    And :user fills {:id "username"} with 'alice'
    And :user fills {:id "password"} with 'secret1'
    And :user clicks {:css "button[type='submit']"}
    Then :user should see 'Welcome, alice!'
    And :user should be on '/dashboard'
```

The credentials are seeded by `setup.clj` — the fixture server starts with
user `alice` / password `secret1` and a login + dashboard page pair.

## The Config File

```clojure
;; shiftlefter.edn
{:interfaces {:web {:type :web
                    :adapter :etaoin}}}
```

This tells ShiftLefter to auto-provision a Chrome browser when steps need
one. (The `:etaoin` adapter suppresses Chrome's native password popups by
default — automation hygiene; override via `:adapter-opts {:prefs {...}}`,
see `docs/INSTALL-TIERS.md`.)

## Prerequisites

- **Chrome** browser installed
- **ChromeDriver** matching your Chrome version, either on your PATH
  (macOS: `brew install chromedriver`) or pointed at via
  `~/.shiftlefter/config.edn` (`:chromedriver-path`)

Nothing else — no network access needed.

## Try It

From this directory (release zip installs `sl`; in a checkout of this repo
substitute `bin/sl`):

```bash
# Check that steps bind correctly — no browser opens, no server starts
sl run --dry-run features/

# Run the tests (watch Chrome open and fill forms!)
sl run features/
```

## Built-in Browser Steps

### Navigation
- `:alice opens the browser to '<url>'`

### Actions
- `:alice clicks {<locator>}`
- `:alice double-clicks {<locator>}`
- `:alice right-clicks {<locator>}`
- `:alice fills {<locator>} with '<text>'`
- `:alice moves to {<locator>}`
- `:alice drags {<locator>} to {<locator>}`

### Verification
- `:alice should see '<text>'` — text appears anywhere on page
- `:alice should see the title '<text>'` — exact page title match
- `:alice should be on '<url>'` — region match: normalized path + fragment;
  query string and host ignored. A bare intent name (`should be on Feed`,
  unquoted) resolves via the intent's `:location`; use
  `should be on exactly '<url>'` for full-URL structural equality
- `:alice should see {<locator>}` — element is visible
- `:alice should not see {<locator>}` — element is not visible
- `:alice should see <N> {<locator>} elements` — exact count

### Locator Syntax

Locators use EDN syntax:
- `{:id "username"}` — element by ID
- `{:css "button[type='submit']"}` — CSS selector
- `{:xpath "//button"}` — XPath
- `{:tag "body"}` — element by tag name

## What's Next?

When built-in steps aren't enough, write your own: see `examples/03-custom-steps/`.
