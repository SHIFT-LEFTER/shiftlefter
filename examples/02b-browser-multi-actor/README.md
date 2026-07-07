# Multi-Actor Browser Test

Run browser tests with multiple users, each in their own browser session. Still zero custom code — just built-in steps, plus a two-line glossary that declares who exists.

> **Mode: Shifted** — this is the first example on the advertised path.
> `shiftlefter.edn` carries `:svo` and a subject glossary, so every scenario
> is validated against the project vocabulary at planning time, before a
> browser opens (`sl orient` will say `Mode: Shifted`). A Vanilla config is
> included for contrast — see [Vanilla contrast](#vanilla-contrast) below.

> **Network note:** like example 02, the scenario drives
> `the-internet.herokuapp.com`, a public demo site — internet access needed.

## What You Get

Two browser windows opening simultaneously, each logged in as the same user but with completely independent sessions. This demonstrates:

- Multiple actors (`:user/alice`, `:user/bob`) in one scenario
- Automatic browser provisioning per actor
- Session isolation — each actor has their own cookies, state, etc.
- Vocabulary validation — an actor the glossary doesn't declare is rejected before anything runs

## The Feature File

```gherkin
# features/two-users.feature
Feature: Multi-actor browser test

  Scenario: Two users login simultaneously
    When :user/alice opens the browser to 'https://the-internet.herokuapp.com/login'
    And :user/alice fills {:id "username"} with 'tomsmith'
    And :user/alice fills {:id "password"} with 'SuperSecretPassword!'
    And :user/alice clicks {:css "button[type='submit']"}
    Then :user/alice should see 'You logged into a secure area!'
    And :user/bob opens the browser to 'https://the-internet.herokuapp.com/login'
    And :user/bob fills {:id "username"} with 'tomsmith'
    And :user/bob fills {:id "password"} with 'SuperSecretPassword!'
    And :user/bob clicks {:css "button[type='submit']"}
    And :user/bob should see 'You logged into a secure area!'
    And :user/alice should be on '/secure'
    And :user/bob should be on '/secure'
    And :user/alice waits 3 seconds
```

The `:user/alice waits 3 seconds` at the end keeps both browsers open so you can see them side by side.

## The Config and the Glossary

```clojure
;; shiftlefter.edn
{:interfaces {:web {:type :web
                    :adapter :etaoin
                    :config {:adapter-opts
                             {:prefs {"profile.password_manager_leak_detection" false}}}}}
 :glossaries {:subjects "glossary/subjects.edn"}
 :svo {:unknown-subject :error
       :unknown-verb :warn
       :unknown-interface :error}}
```

(The `:prefs` line suppresses Chrome's breach warning for the demo site's
canned password — cosmetic only; see example 02's README for the story.)

```clojure
;; glossary/subjects.edn
{:subjects
 {:user {:desc "Standard application user"
         :instances [:alice :bob]}}}
```

The `:user/alice` and `:user/bob` prefixes in step text identify both the actor **type** (`:user`) and the session **instance** (`:alice`, `:bob`). The type is for glossary organization and display; the instance is the session key. When ShiftLefter sees a declared instance:

1. Provisions a fresh browser session for that instance
2. Routes all subsequent steps for that instance to their browser
3. Keeps sessions isolated — `:user/alice`'s cookies don't affect `:user/bob`

The `:svo` levels are the recommended baseline (the same block examples 04–06 use): unknown subjects and interfaces fail at planning time, unknown verbs warn.

## Prerequisites

- **Chrome** browser installed
- **ChromeDriver** matching your Chrome version, either on your PATH
  (macOS: `brew install chromedriver`) or pointed at via
  `~/.shiftlefter/config.edn` (`:chromedriver-path`)

## Try It

From this directory (release zip installs `sl`; in a checkout of this repo substitute `bin/sl`):

```bash
# Validate against the glossary — no browser opens
sl run --dry-run features/

# Run it: watch two Chrome windows open and fill forms independently
sl run features/
```

## Vocabulary Validation, Demonstrated

The glossary is the source of truth for who can act. Three quick experiments:

**1. Break a name.** Rename `:user/bob` to `:user/bbo` in the feature file and re-run the dry-run — binding fails at planning time:

```
ERROR: Unknown subject :user/bbo in step ":user/bbo opens the browser to '...'"
       Known subjects: :user/alice, :user/bob
```

A typo, or someone inventing test users without updating the domain model — caught before any browser opens. (Undo the rename before moving on.)

**2. Shifted with no glossary.** `shiftlefter-shifted-no-glossary.edn` turns on `:svo {:unknown-subject :error}` but declares nobody:

```bash
sl run -c shiftlefter-shifted-no-glossary.edn --dry-run features/
```

Every step is rejected — `:user/alice` and `:user/bob` are unknown until a glossary declares them.

**3. Vanilla contrast.** <a name="vanilla-contrast"></a>`shiftlefter-vanilla.edn` has no `:svo` at all:

```bash
sl run -c shiftlefter-vanilla.edn --dry-run features/
```

Everything binds — any actor name is accepted, like a standard Gherkin runner. That's Vanilla mode: fine for getting started (example 02 uses it deliberately), but nothing stops `:user/bbo` from silently becoming a third session. Shifted mode is the advertised path for exactly that reason.

The validation is extensible — glossaries are plain EDN files, enforcement levels are per-category (`:warn` or `:error`), and teams can layer project-specific constraints on top of framework defaults.

## What's Next?

When built-in steps aren't enough, write your own: see `examples/03-custom-steps/`.
