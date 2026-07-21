# Add Your First Browser Behavior

Drive a real browser from a Gherkin feature with **zero custom code** — just
built-in steps. This is the fastest way to see ShiftLefter work.

## Prerequisites

- **Java 11+** and the `sl` CLI on your PATH (see the [install guide](../README.md#install)).
- **Chrome** installed, and a matching **ChromeDriver** — on your `PATH`, or pointed
  to by `:chromedriver-path` in your config (it doesn't have to be on `PATH`).
  `brew install chromedriver` on macOS puts it on `PATH`; keep its major version in
  step with Chrome.

## 1. A feature file

`features/login.feature`:

```gherkin
Feature: Login

  Scenario: A user logs in
    When :user/alice opens the browser to 'https://the-internet.herokuapp.com/login'
    And :user/alice fills {:id "username"} with 'tomsmith'
    And :user/alice fills {:id "password"} with 'SuperSecretPassword!'
    And :user/alice clicks {:css "button[type='submit']"}
    Then :user/alice should see 'You logged into a secure area!'
```

Steps are **subject-first**: `:user/alice` names both the actor type (`:user`)
and the session (`:alice`). No "I" — every browser step has an explicit actor.

## 2. Configure the `:web` interface

`shiftlefter.edn`:

```clojure
{:interfaces {:web {:type :web :adapter :etaoin}}}
```

That's the whole config for the default WebDriver backend. (To use Playwright
instead, see [CAPABILITIES.md](CAPABILITIES.md#browser-backend-configuration).)

## 3. Run it

```bash
sl run features/
```

Chrome opens, fills the form, and the scenario passes. To check that everything
*binds* without launching a browser, add `--dry-run`.

(No `--step-paths` here: it's only needed when you have your own custom step
definitions. With built-in steps alone, there's no `steps/` directory to point at.)

## Built-in browser steps

No code required — these ship with ShiftLefter:

```gherkin
# navigation
:user/alice opens the browser to '<url>'

# actions
:user/alice clicks {<locator>}
:user/alice double-clicks {<locator>}
:user/alice right-clicks {<locator>}
:user/alice moves to {<locator>}
:user/alice drags {<from>} to {<to>}
:user/alice fills {<locator>} with '<text>'
:user/alice presses '<key>'

# verification
:user/alice should see '<text>'
:user/alice should see {<locator>}
:user/alice should not see {<locator>}
:user/alice should be on '<url>'
:user/alice should be on <Intent>
:user/alice should be on exactly '<url>'

# timing
:user/alice waits <N> seconds
:user/alice waits for {<locator>}
:user/alice waits for {<locator>} to show '<text>'
```

The current built-in set, with each verb's frames, is `sl agent-doc builtins`.

`should be on` is a **region** assertion: it compares the normalized path (and
fragment) and ignores the query string and host. **Quoted = literal, always;
bare = ref** (the same rule as element slots): a bare intent name like `Feed`
resolves via the intent's `:location` + the interface `:base-url`, while a
quoted value is taken as a literal URL or path. `should be on exactly`
compares the full URL structurally (query parameter order across keys doesn't
matter; everything else must match); it takes a quoted literal or a captured
`{binding}` token (a magic-link URL captured earlier in the scenario), never
a bare intent name.

### Locators

Locators are EDN maps in the step text:

| Kind | Example |
|---|---|
| CSS | `{:css "button[type='submit']"}` |
| XPath | `{:xpath "//button"}` |
| ID | `{:id "username"}` |
| Name | `{:name "email"}` |
| Class | `{:class "btn-primary"}` |
| Tag | `{:tag "button"}` |

These aren't equally durable — a `data-testid` or accessible role survives
refactors that break a class or positional XPath. [Choosing web locators](LOCATORS.md)
covers which to prefer and why.

Once you're past selectors, you can name elements semantically with **intent
references** (`Login.submit`) defined in `glossary/intents/` — see
[SVO.md](SVO.md#object-validation-against-intent-regions).

## Multiple actors

Add a second actor and ShiftLefter provisions an **independent, isolated browser
session** for them automatically — same config:

```gherkin
    And :user/bob opens the browser to 'https://the-internet.herokuapp.com/login'
    And :user/bob clicks {:css "button[type='submit']"}
```

`:user/alice` and `:user/bob` get separate cookies and state. The session key is
the instance (`:alice`, `:bob`); the type (`:user`) is for glossary organization.

## Catching mistakes before the browser opens

Point ShiftLefter at a glossary and it validates actors, verbs, and objects at
bind time — before a single browser launches. A typo'd `:user/bbo`, or `clicks`
on an interface that doesn't define it, fails immediately with a "did you mean?"
suggestion. See [SVO.md](SVO.md) for the validation model and
[the multi-actor example](https://github.com/SHIFT-LEFTER/shiftlefter/tree/main/examples/02b-browser-multi-actor).

## Authenticated and long-running sessions

For a browser that stays logged in across runs — your real account, a session you
set up once — use a **costume**: `sl costume init <name>`, then bind it to a
subject with `{:wears <name>}`. See [COSTUMES.md](COSTUMES.md).

## Troubleshooting

- **`session not created` from ChromeDriver** — ChromeDriver and Chrome versions
  must match; make sure ChromeDriver is running/​on PATH and the port is free.
- **Nothing happens / no browser** — confirm Chrome and ChromeDriver are
  installed and on PATH; run with `-v` for verbose diagnostics.
- **A step won't bind** — run with `--dry-run` to see binding diagnostics without
  launching anything.
