# Multi-Actor Browser Test

Run browser tests with multiple users, each in their own browser session. Still zero custom code — just built-in steps.

## What You Get

Two browser windows opening simultaneously, each logged in as the same user but with completely independent sessions. This demonstrates:

- Multiple actors (`:alice`, `:bob`) in one scenario
- Automatic browser provisioning per actor
- Session isolation — each actor has their own cookies, state, etc.

## The Feature File

```gherkin
# features/two-users.feature
Feature: Multi-actor browser test

  Scenario: Two users login simultaneously
    When :alice opens the browser to 'https://the-internet.herokuapp.com/login'
    And :alice fills {:id "username"} with 'tomsmith'
    And :alice fills {:id "password"} with 'SuperSecretPassword!'
    And :alice clicks {:css "button[type='submit']"}
    Then :alice should see 'You logged into a secure area!'
    And :bob opens the browser to 'https://the-internet.herokuapp.com/login'
    And :bob fills {:id "username"} with 'tomsmith'
    And :bob fills {:id "password"} with 'SuperSecretPassword!'
    And :bob clicks {:css "button[type='submit']"}
    And :bob should see 'You logged into a secure area!'
    And :alice should be on '/secure'
    And :bob should be on '/secure'
    And pause for 3 seconds
```

The `pause for 3 seconds` at the end keeps both browsers open so you can see them side by side.

## The Config File

```clojure
;; shiftlefter.edn
{:interfaces {:web {:type :web
                   :adapter :etaoin}}}
```

Same config as single-user — ShiftLefter auto-provisions a new browser for each actor.

## Prerequisites

- **Chrome** browser installed
- **ChromeDriver** on your PATH (must match your Chrome version)
  - macOS: `brew install chromedriver`
  - Or download from https://chromedriver.chromium.org/downloads

## Try It

```bash
sl run features/
```

Watch two Chrome windows open and fill forms independently!

## How It Works

The `:alice` and `:bob` prefixes in step text are **actor names**. When ShiftLefter sees a new actor:

1. Provisions a fresh browser session for that actor
2. Routes all subsequent steps for that actor to their browser
3. Keeps sessions isolated — `:alice`'s cookies don't affect `:bob`

You can use any name: `:user`, `:admin`, `:customer1`, `:customer2`, etc.

## Bonus: Vocabulary Validation (Shifted Mode)

In vanilla mode, any actor name is accepted — ShiftLefter works like a standard Gherkin runner. In **shifted mode**, every scenario is validated against a project glossary before execution begins.

The glossary defines your domain vocabulary: which actors exist, which actions they can perform, and which interfaces they can target. ShiftLefter validates all three at bind time — before a single browser opens or a single step runs. This means:

- An actor not in the glossary is rejected (typo, or someone inventing test users without updating the domain model)
- An action not declared for an interface type is rejected (e.g., `clicks` on an `:api` interface)
- An interface not defined in config is rejected (misconfigured environment)

The validation is extensible — glossaries are plain EDN files, enforcement levels are per-category (`:warn` or `:error`), and the system is designed for teams to layer project-specific constraints on top of framework defaults.

This example includes two extra config files to demonstrate the simplest case: subject validation.

**Step 1: Run without a glossary**

```bash
sl run --config-path shiftlefter-shifted-no-glossary.edn --dry-run features/
```

This config sets `:unknown-subject :error` but provides no glossary. ShiftLefter rejects every step — `:alice` and `:bob` are unknown subjects:

```
ERROR: Unknown subject :alice in step ":alice opens the browser to '...'"
ERROR: Unknown subject :bob in step ":bob opens the browser to '...'"
```

**Step 2: Add a glossary and run again**

```bash
sl run --config-path shiftlefter-shifted.edn --dry-run features/
```

This config points to a glossary (`glossary/subjects.edn`) that declares `:alice` and `:bob` as valid actors:

```clojure
{:subjects
 {:alice {:desc "First test user"}
  :bob   {:desc "Second test user"}}}
```

Now binding succeeds. Rename `:bob` to `:bbo` in the feature file and it fails again — the glossary is the source of truth.

## What's Next?

When built-in steps aren't enough, write your own: see `examples/03-custom-steps/`.
