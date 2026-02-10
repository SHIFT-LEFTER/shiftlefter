# Validate & Format

ShiftLefter can parse, validate, and reformat any Cucumber-compatible Gherkin file. No configuration, no step definitions, no Clojure. Just point it at a `.feature` file.

## Validate

Start with any `.feature` file — one you already have, or create one:

```gherkin
# login.feature
Feature: User login

  Scenario: Successful login with valid credentials
    Given the user is on the login page
    When the user enters "alice@example.com" and "correct-password"
    And the user clicks the login button
    Then the user should see the dashboard

  Scenario: Failed login shows error
    Given the user is on the login page
    When the user enters "alice@example.com" and "wrong-password"
    And the user clicks the login button
    Then the user should see an error message "Invalid credentials"
```

Check whether it parses cleanly and has consistent formatting:

```bash
sl fmt --check login.feature
# => Checking login.feature... OK
```

Exit code 0 means valid, 1 means something needs attention. Point it at a directory to check everything at once:

```bash
sl fmt --check features/
# => 2 files checked: 1 valid, 1 invalid
```

This is enough for a CI gate — add `sl fmt --check features/` to your pipeline and formatting drift stops.

Validation also catches structural errors. Steps without a Scenario, missing keywords, broken nesting — the parser rejects them with line numbers:

```gherkin
# broken.feature
Feature: Missing scenario
  Given a step with no scenario
  When this is not valid Gherkin
  Then the parser should reject it
```

```bash
sl fmt --check broken.feature
# => Checking broken.feature... NEEDS FORMATTING
#      2:3: unexpected-token: Unexpected token: :step-line
#      3:3: unexpected-token: Unexpected token: :step-line
#      4:3: unexpected-token: Unexpected token: :step-line
```

## Format

Now try a file with inconsistent indentation:

```gherkin
# messy.feature
Feature:    User registration
  Background:
       Given the system has no users

    Scenario:   Register with valid email
  Given the user is on the registration page
      When the user enters "bob@example.com"
        And the user clicks register
  Then the user should receive a confirmation email
      And   the user should see "Check your inbox"

  Scenario: Duplicate email rejected
    Given the system already has "bob@example.com"
      When the user enters "bob@example.com"
    And the user clicks register
        Then the user should see "Email already taken"
```

```bash
sl fmt --check messy.feature
# => Checking messy.feature... NEEDS FORMATTING
```

See what canonical formatting looks like without changing anything:

```bash
sl fmt --canonical messy.feature
```

The formatter normalizes indentation and keyword alignment but preserves your step text. Structure gets cleaned up; meaning stays untouched.

When you're happy with what it produces, apply it:

```bash
sl fmt --write messy.feature
# => Formatting messy.feature... reformatted
```

The file is updated in place. Files that are already canonical are left alone.

## Try it

```bash
# Create a working directory
mkdir -p my-features && cd my-features

# Create the two files above, then:

# Check both
sl fmt --check .

# See what the messy one would look like
sl fmt --canonical messy.feature

# Fix it
sl fmt --write messy.feature

# Verify it's clean now
sl fmt --check messy.feature
```
