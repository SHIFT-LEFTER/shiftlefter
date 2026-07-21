# Test Across Interfaces

One scenario can span more than one interface — a browser flow whose second
factor arrives by SMS, for instance. The same actor acts over both, and ShiftLefter
keeps each interface's session separate automatically. Passing a captured *value*
from one interface to another is built in: named bindings, shown below.

## Two interfaces, one scenario

```gherkin
Scenario: Reset password with an SMS code
  When :user/alice opens the browser to 'http://localhost:9090/reset-password'
  And  :user/alice fills Login.email with 'alice@example.com'
  And  :user/alice clicks Login.submit
  Then :user/alice should see 'Enter Verification Code'

  When [:sms] :user/alice receives an SMS to '+15550001111' within the last 1 minute matching /code is: (?<code>\d{6})/
  And  :user/alice fills Login.code with {code}
  Then :user/alice should see 'Code verified'
```

The same actor (`:user/alice`) acts over both the `:web` and `:sms` interfaces;
ShiftLefter keeps each interface's session separate.

## Passing a value between interfaces: named bindings

The handoff is two tokens of Gherkin — no custom step definitions:

1. **Produce.** A named group in a capture step's match pattern —
   `(?<code>\d{6})` — captures into the scenario's *data plane*: a
   scenario-scoped map of named bindings. Two built-in producers: the SMS
   receive step (above) and the web capture step (`captures
   Checkout.confirmation matching /.../`, below). Every named group that
   participates in the match binds; unnamed groups bind nothing (the dry
   run notices a pattern that captures groups but names none). Capture is
   assert-plus-bind: if the pattern doesn't match within the step's
   window, the step fails — it fails as though the actor could not see
   that text.
2. **Consume.** Any literal-admitting slot accepts a `{name}` token —
   `fills Login.code with {code}` — resolved against the data plane when
   the step executes. Quoted text is always literal: `'{code}'` types the
   five characters `{code}`, it never interpolates.

Bindings are scenario-scoped, flow forward only, and die at scenario end.
Re-producing a name (a second receive capturing `(?<code>...)`) rebinds
it — last write wins, which is exactly what chained receives want.

The dry run statically checks the plane: every consumed `{name}` must
have an upstream producer (a named group in an earlier step, or a hook
that declares `:provides`). A typo like `{coed}` is a planning error with
a did-you-mean — it never reaches a live browser. A produced-but-never-
consumed binding gets a notice.

Full runnable example:
[`examples/04-sms-2fa`](https://github.com/SHIFT-LEFTER/shiftlefter/tree/main/examples/04-sms-2fa).

## The other direction: capture on the web, verify over SMS

Capture isn't SMS-shaped — it's general. The web capture step reads an
element's text (with the `should see` family's wait-then-assert
semantics: it polls the live DOM for up to the verification budget) and
binds its named groups the same way:

```gherkin
Scenario: Order confirmation arrives by SMS
  When :user/alice clicks Checkout.placeOrder
  And  :user/alice captures Checkout.confirmation matching /Order (?<orderNumber>[A-Z0-9-]+)/
  Then [:sms] :user/alice receives an SMS to '+15550001111' within the last 1 minute matching /order {orderNumber} has shipped/
```

The order number is *born on the web page* and verified in an SMS
matcher. `{orderNumber}` interpolates into the receive pattern as a
regex-quoted literal — a binding is always a value, never a regex
fragment, so an order number containing `.` or `+` matches itself and
nothing else.

## The magic-link pattern

A captured URL can be consumed anywhere a literal is admitted —
including the location slot of navigation:

```gherkin
When [:sms] :user/alice receives an SMS to '+15550001111' matching /(?<resetLink>https://\S+)/
And  :user/alice opens the browser to {resetLink}
Then :user/alice should see 'Choose a new password'
```

The link arrives out-of-band, the scenario captures it, and the browser
opens to it — no glue code, and the dry run still statically confirms
`{resetLink}` has a producer before anything runs.

## Hand-rolled interfaces: one call joins the data plane

Anything that can fetch text can participate. A custom interface's
receive step is one `capture!` call:

```clojure
(ns myproject.steps.email
  (:require [shiftlefter.capabilities.ctx :as cap]
            [shiftlefter.stepengine.bindings :as bindings]
            [shiftlefter.stepengine.registry :refer [defstep]]))

(defstep #":([\w./-]+) receives an email to '([^']+)' matching /(.+)/"
  {:interface :email}
  [ctx subject to-addr match-str]
  (let [inbox (cap/get-capability ctx :email (subject-key subject))
        msg   (poll-for-message inbox to-addr)]
    (bindings/capture! ctx match-str (:body msg))))
```

`shiftlefter.stepengine.bindings/capture!` takes ctx, the pattern, and
whatever text your interface fetched. That one call is full
participation: named groups merge into the data plane, provenance lands
in run evidence, a no-match throws the same structured capture failure
every built-in capture throws — and because the pattern is a step-text
literal, the planner enumerates its named groups statically,
interface-blind. Declare the frame with `:arg-kinds {:match :matcher}`
in your verb glossary and the dry run's producer/consumer check covers
your interface exactly as it covers the built-ins. The SMS receive step
and the web capture step are themselves built on this call.

## The `[:interface]` annotation

`[:sms]` in front of a step pins it to the SMS interface. It's both a filter
(bind only against `:sms` step definitions) and an assertion (if `:sms` isn't
configured, you get a planning error before the run). Use it when the same step
vocabulary legitimately spans channels — `receives a message` under `:sms`,
`:whatsapp`, `:email`. See [SVO.md](SVO.md#step-binding-rules).

## Configuring interfaces

Each interface names a `:type` (verb vocabulary) and an `:adapter` (the backend
that does the work):

```clojure
{:interfaces {:web {:type :web :adapter :etaoin}
              :sms {:type :sms :adapter :sms-mock}}}
```

Built-in verb vocabularies for `:web` and `:sms` load automatically. Adapters
shipped today include `:etaoin` and `:playwright` (browser) and `:sms-twilio` /
`:sms-mock` (SMS). Full runnable example:
[`examples/04-sms-2fa`](https://github.com/SHIFT-LEFTER/shiftlefter/tree/main/examples/04-sms-2fa).
