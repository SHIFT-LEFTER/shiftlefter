# Writing Custom Steps

When built-in steps aren't enough, write your own in Clojure.

This example uses **vanilla mode** — standard Cucumber-style steps that work exactly like you'd expect from any Gherkin runner. ShiftLefter also supports **shifted mode** with subject-verb-object validation, multi-actor scenarios, and requirements traceability. See the main README for more on that.

## The Feature File

```gherkin
# features/cucumbers.feature
Feature: Cucumber basket

  Scenario: Eating cucumbers
    Given I have 12 cucumbers
    When I eat 5 cucumbers
    Then I should have 7 cucumbers

  Scenario: Eating too many
    Given I have 3 cucumbers
    When I eat 5 cucumbers
    Then I should have -2 cucumbers
```

## The Step Definitions

```clojure
;; steps/cucumbers.clj
(ns steps.cucumbers
  (:require [shiftlefter.stepengine.registry :refer [defstep]]))

(defstep #"I have (\d+) cucumbers"
  [ctx n]
  (assoc ctx :cucumbers (parse-long n)))

(defstep #"I eat (\d+) cucumbers"
  [ctx n]
  (update ctx :cucumbers - (parse-long n)))

(defstep #"I should have (-?\d+) cucumbers"
  [ctx n]
  (let [expected (parse-long n)
        actual (:cucumbers ctx)]
    (when-not (= expected actual)
      (throw (ex-info (str "Expected " expected " cucumbers but had " actual)
                       {:expected expected :actual actual})))
    ctx))
```

## The Config File

```clojure
;; shiftlefter.edn
{:step-paths ["steps/"]}
```

This tells ShiftLefter where to find your step definition files.

## Try It

```bash
sl run features/
```

## How It Works

### The `defstep` Macro

```clojure
(defstep #"regex pattern with (capture groups)"
  [ctx arg1 arg2 ...]
  ;; body - return ctx or updated ctx
  )
```

- **Pattern**: A regex that matches step text. Capture groups become arguments.
- **ctx**: The scenario context — a map that accumulates state across steps.
- **Arguments**: Strings captured from the regex (you parse them as needed).
- **Return**: The (possibly updated) ctx for the next step.

### Context Flow

Each scenario starts with an empty ctx `{}`. Steps can:

1. **Add data**: `(assoc ctx :cucumbers 12)`
2. **Update data**: `(update ctx :cucumbers - 5)`
3. **Read data**: `(:cucumbers ctx)`
4. **Assert**: Throw an exception if something's wrong

The ctx flows from step to step within a scenario, then resets for the next scenario.

### Assertions

To fail a step, throw an exception:

```clojure
(when-not (= expected actual)
  (throw (ex-info "Assertion failed" {:expected expected :actual actual})))
```

The error message and data will appear in the test output.

## What's Next?

- Add more step files in `steps/` — they're all loaded automatically
- Use `sl run --dry-run` to check step bindings without executing
- See `examples/02-browser-zero-code/` for built-in browser steps
