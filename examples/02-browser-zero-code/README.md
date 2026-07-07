# Browser Test — Zero Custom Code

Run browser automation tests without writing any Clojure. ShiftLefter includes built-in steps for common web interactions.

> **Mode: Vanilla** — deliberately. There is no `:svo` in the config, so any
> subject name is accepted and ShiftLefter behaves like a standard Gherkin
> runner (`sl orient` will say so). That's the zero-setup entry point; the
> next example, [`02b`](../02b-browser-multi-actor/), adds the glossary and
> runs Shifted.

> **Network note:** the scenario drives `the-internet.herokuapp.com`, a
> public demo site — so this example needs internet access. It's kept
> external on purpose: zero code *and* zero local setup.

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
    When :user opens the browser to 'https://the-internet.herokuapp.com/login'
    Then :user should see the title 'The Internet'
    And :user should see {:id "username"}
    And :user should see {:id "password"}
    And :user should see {:css "button[type='submit']"}

  Scenario: Successful login
    When :user opens the browser to 'https://the-internet.herokuapp.com/login'
    And :user fills {:id "username"} with 'tomsmith'
    And :user fills {:id "password"} with 'SuperSecretPassword!'
    And :user clicks {:css "button[type='submit']"}
    Then :user should see 'You logged into a secure area!'
    And :user should be on '/secure'
```

## The Config File

```clojure
;; shiftlefter.edn
{:interfaces {:web {:type :web
                    :adapter :etaoin
                    :config {:adapter-opts
                             {:prefs {"profile.password_manager_leak_detection" false}}}}}}
```

This tells ShiftLefter to auto-provision a Chrome browser when steps need one.
The `:prefs` line is cosmetic: the demo site's canned password is in public
breach lists, so without it Chrome shows a native "password found in a data
breach" warning after login. That warning is harmless to the test — it's
browser UI, not page content, so WebDriver reads the page underneath it —
but it's noisy, so the config turns leak detection off for the automation
profile. (`:adapter-opts` passes options straight through to the backend —
see `docs/CAPABILITIES.md`.)

## Prerequisites

- **Chrome** browser installed
- **ChromeDriver** matching your Chrome version, either on your PATH
  (macOS: `brew install chromedriver`) or pointed at via
  `~/.shiftlefter/config.edn` (`:chromedriver-path`)

## Try It

From this directory (release zip installs `sl`; in a checkout of this repo
substitute `bin/sl`):

```bash
# Check that steps bind correctly — no browser opens
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
- `:alice should be on '<url>'` — URL contains string
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
