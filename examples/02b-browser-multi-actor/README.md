# Multi-Actor Browser Test

Run browser tests with multiple users, each in their own browser session. Still zero custom code — just built-in steps.

## What You Get

Two browser windows opening simultaneously, each logged in as the same user but with completely independent sessions. This demonstrates:

- Multiple actors (`:user/alice`, `:user/bob`) in one scenario
- Automatic browser provisioning per actor
- Session isolation — each actor has their own cookies, state, etc.

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

The `:user/alice` and `:user/bob` prefixes in step text identify both the actor **type** (`:user`) and the session **instance** (`:alice`, `:bob`). When ShiftLefter sees a new actor:

1. Provisions a fresh browser session for that instance
2. Routes all subsequent steps for that instance to their browser
3. Keeps sessions isolated — `:user/alice`'s cookies don't affect `:user/bob`

The `:type/instance` form is the recommended syntax. The type (`:user`) is for glossary organization and display; the instance (`:alice`) is the session key.

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

This config sets `:unknown-subject :error` but provides no glossary. ShiftLefter rejects every step — `:user/alice` and `:user/bob` are unknown subjects:

```
ERROR: Unknown subject :user/alice in step ":user/alice opens the browser to '...'"
ERROR: Unknown subject :user/bob in step ":user/bob opens the browser to '...'"
```

**Step 2: Add a glossary and run again**

```bash
sl run --config-path shiftlefter-shifted.edn --dry-run features/
```

This config points to a glossary (`glossary/subjects.edn`) that declares `:alice` and `:bob` as instances of the `:user` type:

```clojure
{:subjects
 {:user {:desc "Standard application user"
         :instances [:alice :bob]}}}
```

Now binding succeeds. Rename `:user/bob` to `:user/bbo` in the feature file and it fails again — the glossary is the source of truth.

## What's Next?

When built-in steps aren't enough, write your own: see `examples/03-custom-steps/`.
