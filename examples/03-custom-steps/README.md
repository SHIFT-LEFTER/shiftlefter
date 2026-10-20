# Writing Custom Steps

When built-in steps aren't enough, write your own in Clojure.

> **Two typed steps and one untyped one — on purpose.** A typed step carries
> `:svo` metadata, so its subject is checked against the glossary before
> anything runs. The third step is left untyped to show what the planner says
> about it: it binds and runs, with a warning. Multi-actor browser scenarios
> are [`02b`](../02b-browser-multi-actor/); [the examples index](../README.md)
> has the full path.

## The Feature File

```gherkin
# sl/features/cucumbers.feature
Feature: Cucumber basket

  Scenario: Eating cucumbers
    Given :user has 12 cucumbers
    When :user eats 5 cucumbers
    Then I should have 7 cucumbers

  Scenario: Eating too many
    Given :user has 3 cucumbers
    When :user eats 5 cucumbers
    Then I should have -2 cucumbers
```

## The Step Definitions

```clojure
;; sl/steps/cucumbers.clj
(ns steps.cucumbers
  (:require [shiftlefter.stepengine.registry :refer [defstep]]))

(defstep #"^:(\S+) has (\d+) cucumbers$"
  {:svo {:subject :$1 :verb :have :frame :count}}
  [ctx _subject n]
  (assoc ctx :cucumbers (parse-long n)))

(defstep #"^:(\S+) eats (\d+) cucumbers$"
  {:svo {:subject :$1 :verb :eat :frame :count}}
  [ctx _subject n]
  (update ctx :cucumbers - (parse-long n)))

;; Untyped on purpose — see below.
(defstep #"I should have (-?\d+) cucumbers"
  [ctx n]
  (let [expected (parse-long n)
        actual (:cucumbers ctx)]
    (when-not (= expected actual)
      (throw (ex-info (str "Expected " expected " cucumbers but had " actual)
                       {:expected expected :actual actual})))
    ctx))
```

The first two steps are **typed**: `:svo` maps the first capture to the
subject (`:$1`), and the subject capture sits after the colon — `:(\S+)` —
so `:user` in the feature becomes the subject `:user`, which is checked
against `sl/glossary/subjects.edn`. A typo like `:usr` is caught at planning
time. These steps declare no `:interface` (a basket is not a browser), so
their subject is checked but their verb is not — verbs are checked against
an interface's verb glossary, and there is none here.

The third step is **untyped**. It still binds and runs, but the planner
reports it:

```
SVO validation warnings:
  WARNING: Untyped step "I should have 7 cucumbers"
         at sl/features/cucumbers.feature:6
         matched stepdef "I should have (-?\\d+) cucumbers", which declares no :svo — no subject, verb, or object was checked
```

That warning (`:stepdef/untyped`) is the lesson: nothing about the step went
through the type system. A real step library types every step; set
`:enforcement {:untyped-stepdef :error}` in `shiftlefter.edn` to make it a
planning error instead of a warning.

## The Glossary File

```clojure
;; sl/glossary/subjects.edn
{:subjects
 {:user {:desc "The person holding the cucumber basket"}}}
```

Discovered automatically at the conventional path next to the config.

## The Config File

```clojure
;; sl/shiftlefter.edn
{:runner {:step-paths ["steps/"]}}
```

This tells ShiftLefter where to find your step definition files, resolved
next to the config — `sl/steps/`. (`"steps/"` also happens to be the
default, but declaring it under `:runner` is the real config key — a bare
top-level `:step-paths` is ignored.)

## Try It

From this directory (release zip installs `sl`; in a checkout of this repo
substitute `bin/sl`):

```bash
sl run sl/features/
```

(Bare `sl run` works too — `sl/features/` is the default corpus location.)

## How It Works

### The `defstep` Macro

```clojure
(defstep #"regex pattern with (capture groups)"
  [ctx arg1 arg2 ...]
  ;; body - return ctx or updated ctx
  )
```

- **Pattern**: A regex that matches step text. Capture groups become arguments.
- **Metadata** (optional map before the argument vector): `:svo` names which
  capture is the subject (`:$1`), the verb, and the frame; add `:interface`
  when the step drives an interface (`:web`, `:sms`, …) so the verb is
  checked too.
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

- Add more step files in `sl/steps/` — they're all loaded automatically
- Use `sl run --dry-run` to check step bindings without executing
- See `examples/02-browser-zero-code/` for built-in browser steps
