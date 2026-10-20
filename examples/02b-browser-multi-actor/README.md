# Multi-Actor Browser Test

Run browser tests with multiple users, each in their own browser session. Still
zero custom step definitions — just built-in steps, a two-line glossary that
declares who exists, and a Login intent that names the form's three elements.

> **Two actors, one glossary.** A subject glossary sits at the conventional
> path beside `sl/shiftlefter.edn`, and the config's `:enforcement` block
> makes an unknown subject a hard error, so every scenario is validated
> against the project vocabulary at planning time, before a browser opens.

> **Fully offline.** The app under test is ShiftLefter's bundled fixture
> server — `setup.clj` starts it on `127.0.0.1` with two seeded users and
> stops it after the run. No network, no third-party site. (See example
> [`02`](../02-browser-zero-code/)'s README § "Zero code?" for what
> `setup.clj` is.)

## What You Get

Two browser windows opening simultaneously, each logged in as its own seeded
user — and the dashboard greets each session by name, so the isolation is
visible as page text. This demonstrates:

- Multiple actors (`:user/alice`, `:user/bob`) in one scenario
- Automatic browser provisioning per actor
- Session isolation — alice's window says `Welcome, alice!`, bob's says
  `Welcome, bob!`; cookies and state never cross
- Vocabulary validation — an actor the glossary doesn't declare is rejected before anything runs

## The Feature File

```gherkin
# sl/features/two-users.feature
Feature: Multi-actor browser test

  Scenario: Two users login simultaneously
    When :user/alice opens the browser to 'http://127.0.0.1:9095/login'
    And :user/alice fills Login.username with 'alice'
    And :user/alice fills Login.password with 'secret1'
    And :user/alice clicks Login.submit
    Then :user/alice should see 'Welcome, alice!'
    And :user/bob opens the browser to 'http://127.0.0.1:9095/login'
    And :user/bob fills Login.username with 'bob'
    And :user/bob fills Login.password with 'secret2'
    And :user/bob clicks Login.submit
    And :user/bob should see 'Welcome, bob!'
    And :user/alice should see 'Welcome, alice!'
    And :user/alice should be on '/dashboard'
    And :user/bob should be on '/dashboard'
    And :user/alice waits 3 seconds
```

The `:user/alice waits 3 seconds` at the end keeps both browsers open so you
can see them side by side. Note the re-check of alice's greeting *after* bob
logs in — if the sessions shared state, that step would fail.

## The Config, the Glossary, and the Intent

```clojure
;; sl/shiftlefter.edn
{:interfaces {:web {:type :web
                    :adapter :etaoin}}
 :enforcement {:unknown-subject :error
       :unknown-verb :warn
       :unknown-interface :error}}
```

```clojure
;; sl/glossary/subjects.edn
{:subjects
 {:user {:desc "Standard application user"
         :instances [:alice :bob]}}}
```

```clojure
;; sl/glossary/intents/login.edn
{:intent "Login"
 :elements {:username {:bindings {:web {:id "username"}}}
            :password {:bindings {:web {:id "password"}}}
            :submit   {:bindings {:web {:css "button[type=\"submit\"]"}}}}}
```

`Login.username` in a step is an intent reference: the `Login` intent owns
the form's three elements, each bound to its locator exactly once. When the
page changes, you edit one binding here and zero scenarios. A reference the
intents do not declare (`Login.sumbit`) is a planning error with a
did-you-mean and the file and line where the right name is declared.

Note there is no `:glossaries` key — `glossary/subjects.edn` and
`glossary/intents/` sit at the conventional paths next to the config, so
they are discovered automatically.
An explicit `:glossaries` map overrides the convention wholesale;
`:glossaries nil` turns glossaries off (experiment 2 below relies on that).

The `:user/alice` and `:user/bob` prefixes in step text identify both the actor **type** (`:user`) and the session **instance** (`:alice`, `:bob`). The type is for glossary organization and display; the instance is the session key. When ShiftLefter sees a declared instance:

1. Provisions a fresh browser session for that instance
2. Routes all subsequent steps for that instance to their browser
3. Keeps sessions isolated — `:user/alice`'s cookies don't affect `:user/bob`

The `:enforcement` levels are the recommended baseline (the same block examples 04–06 use): unknown subjects and interfaces fail at planning time, unknown verbs warn.

## Prerequisites

- **Chrome** browser installed
- **ChromeDriver** matching your Chrome version, either on your PATH
  (macOS: `brew install chromedriver`) or pointed at via
  `~/.shiftlefter/config.edn` (`:chromedriver-path`)

Nothing else — no network access needed.

## Try It

From this directory (release zip installs `sl`; in a checkout of this repo substitute `bin/sl`):

```bash
# Validate against the glossary — no browser opens, no server starts
sl run --dry-run sl/features/

# Run it: watch two Chrome windows open and fill forms independently
sl run sl/features/
```

## Vocabulary Validation, Demonstrated

The glossary is the source of truth for who can act. Three quick experiments:

**1. Break a name.** Rename `:user/bob` to `:user/bbo` in the feature file and re-run the dry-run — binding fails at planning time:

```
ERROR: Unknown subject :user/bbo in step ":user/bbo opens the browser to '...'"
       Known subjects: :user/alice, :user/bob
```

A typo, or someone inventing test users without updating the domain model — caught before any browser opens. (Undo the rename before moving on.)

**2. Enforcement with no glossary.** `sl/shiftlefter-shifted-no-glossary.edn` turns on `:enforcement {:unknown-subject :error}` but declares nobody — it carries an explicit `:glossaries nil` so the conventional `glossary/` files next to it stay out of play:

```bash
sl run -c sl/shiftlefter-shifted-no-glossary.edn --dry-run sl/features/
```

Every step is rejected — `:user/alice` and `:user/bob` are unknown until a glossary declares them.

The validation is extensible — glossaries are plain EDN files, enforcement levels are per-category (`:warn` or `:error`), and teams can layer project-specific constraints on top of framework defaults.

## What's Next?

When built-in steps aren't enough, write your own: see `examples/03-custom-steps/`.
