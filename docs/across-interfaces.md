# Test Across Interfaces

One scenario can span more than one interface — a browser flow whose second
factor arrives by SMS, for instance. The same actor acts over both, and ShiftLefter
keeps each interface's session separate automatically. Passing a captured *value*
from one interface to another is built in: a declared shape and a `{token}`,
shown below.

## Two interfaces, one scenario

```gherkin
Scenario: Reset password with an SMS code
  When :user/alice opens the browser to 'http://localhost:9090/reset-password'
  And  :user/alice fills Login.email with 'alice@example.com'
  And  :user/alice clicks Login.submit
  Then :user/alice should see 'Enter Verification Code'

  When :user/alice receives an SMS to '+15550001111' within the last 1 minute matching Reset.smsCode
  And  :user/alice fills Login.code with {smsCode}
  Then :user/alice should see 'Code verified'
```

The same actor (`:user/alice`) acts over both the `:web` and `:sms` interfaces;
ShiftLefter keeps each interface's session separate. No step names its
interface: "receives an SMS" only binds to SMS vocabulary, and with one `:sms`
instance configured the lane is derived (the `[name]` annotation below is for
projects with several instances of a type).

## Passing a value between interfaces: a declared shape and a token

The handoff is one reference and one token of Gherkin — no custom step
definitions:

1. **Produce.** `matching Reset.smsCode` names an intent element, and the
   element declares the *shape* of the text it owns, once, in the intents
   glossary:

   ```edn
   ;; sl/glossary/intents/reset.edn
   {:intent "Reset"
    :elements {:smsCode {:bindings {:sms {}}
                         :shape "verification code is: (\\d{6})"}}}
   ```

   `:shape` is regex source, compiled at planning time. A message is a
   readable with no locator, so the `:sms` binding is empty — the shape is
   all it has. On a match, the shape's groups capture into the scenario's
   *data plane*, a scenario-scoped map of named bindings. A shape with
   exactly one group binds its value to the element's own name — `{smsCode}`
   — so the scenario reads end to end without opening the glossary; a shape
   with several groups names each one (`(?<orderNumber>…)`) and those names
   are the tokens. Two built-in producers: the SMS receive step (above) and
   the web capture step (`captures Checkout.confirmation`, below). Capture
   is assert-plus-bind: if the shape doesn't match within the step's
   window, the step fails — it fails as though the actor could not see
   that text.
2. **Consume.** Any literal-admitting slot accepts a `{name}` token —
   `fills Login.code with {smsCode}` — resolved against the data plane when
   the step executes. Quoted text is always literal: `'{smsCode}'` types the
   eight characters `{smsCode}`, it never interpolates.

The regex can also be written inline — `matching /verification code is:
(?<code>\d{6})/` — the escape hatch for a one-off pattern. Every named
group that participates in the match binds; unnamed groups bind nothing (a
web `captures` with *only* unnamed groups is a plan error pointing at the
assert-only `should see … matching`; SMS receive keeps a non-blocking notice
there). A step that names a shaped element *and* carries an inline pattern
is a plan error naming both sites: two declarations of one shape.

Bindings are scenario-scoped, flow forward only, and die at scenario end.
Re-producing a name (a second receive matching the same shape) rebinds
it — last write wins, which is exactly what chained receives want.

The dry run statically checks the plane: every consumed `{name}` must
have an upstream producer (a shape or named group in an earlier step, or a
hook that declares `:provides`). A typo like `{smscode}` is a planning
error with a did-you-mean that points at the glossary line that declared
the shape — it never reaches a live browser. A produced-but-never-consumed
binding gets a notice.

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
  And  :user/alice captures Checkout.confirmation
  Then :user/alice receives an SMS to '+15550001111' within the last 1 minute matching /order {orderNumber} has shipped/
```

with `Checkout.confirmation` declared as

```edn
:confirmation {:bindings {:web {:id "confirmation"}}
               :shape "Order (?<orderNumber>[A-Z0-9-]+)"}
```

`captures Checkout.confirmation` has no matching clause: the element's
declared shape is the pattern. The order number is *born on the web page*
and verified in an SMS matcher. `{orderNumber}` interpolates into the
receive pattern as a regex-quoted literal — a binding is always a value,
never a regex fragment, so an order number containing `.` or `+` matches
itself and nothing else. (An inline pattern may not contain a `{token}`
that doesn't exist yet, and a declared shape may not contain one at all —
the glossary is scenario-independent — which is why this receive step
stays inline.)

## The magic-link pattern

A captured URL can be consumed anywhere a literal is admitted —
including the location slot of navigation:

```gherkin
When :user/alice receives an SMS to '+15550001111' matching /(?<resetLink>https://\S+)/
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

(defstep (re-pattern (str ":([\\w./-]+) receives an email to '([^']+)' matching ("
                          bindings/matcher-re-fragment ")"))
  {:interface :email
   :svo {:subject :$1 :verb :receive :frame :default :args {:to :$2 :match :$3}}}
  [ctx subject to-addr match-str]
  (let [inbox (cap/get-capability ctx :email (subject-key subject))
        msg   (poll-for-message inbox to-addr)]
    (bindings/capture! ctx match-str (:body msg))))
```

`shiftlefter.stepengine.bindings/capture!` takes ctx, the pattern, and
whatever text your interface fetched. That one call is full
participation: named groups merge into the data plane, provenance lands
in run evidence, a no-match throws the same structured capture failure
every built-in capture throws. Splice `bindings/matcher-re-fragment` into
your step pattern, as above, and the slot takes both forms — an inline
`/…/` or an `Intent.element` reference to a declared `:shape` (an element
with an `:email` binding). The engine classifies the slot before your
function runs, so `match-str` is always bare regex source. Declare the
frame with `:arg-kinds {:match :matcher}` in your verb glossary and the
dry run's producer/consumer check covers your interface exactly as it
covers the built-ins. The SMS receive step and the web capture step are
themselves built on this call.

## The `[name]` annotation

`[:sms]` in front of a step names the interface **instance** the step executes
on. The instance's declared `:type` narrows binding to step definitions of
that type, and the annotation is an assertion (if `:sms` isn't configured,
you get a planning error before the run). It is **optional** whenever the
step text already picks the type and exactly one instance of that type is
configured — which is why the scenarios above carry none: "receives an SMS"
binds only SMS vocabulary, and one `:sms` instance exists. Write it to pick
one of several same-type instances (`[:admin-console]` vs `[:storefront]`,
both `:type :web` — every instance of a type speaks its full vocabulary,
builtins included), or simply to make the lane explicit. See
[SVO.md](SVO.md#step-binding-rules).

## Configuring interfaces

Each interface entry declares a named **instance**: a `:type` (verb
vocabulary) and an `:adapter` (the backend that does the work — invisible to
feature files):

```clojure
{:interfaces {:web {:type :web :adapter :etaoin}
              :sms {:type :sms :adapter :sms-mock}}}
```

Built-in verb vocabularies for `:web` and `:sms` load automatically. Adapters
shipped today include `:etaoin` and `:playwright` (browser) and `:sms-twilio` /
`:sms-mock` (SMS).

A user `:interfaces` map is the **complete** set — it replaces the builtin
defaults wholesale. A project that declares only `:sms` has exactly one
interface (no phantom `:web`), and `sl orient` says so. To keep a builtin
alongside your own, include it by reference — `:default` means the shipped
builtin def of that name, verbatim:

```clojure
{:interfaces {:web :default                         ; builtin browser, kept
              :sms {:type :sms :adapter :sms-mock}}} ; brought by the project
```

A full-map entry is used as-is under any name (a user `:web {…}` fully
shadows the builtin — user maps and builtin defs are never merged) and must
declare its `:type`; the type is never inferred from the name. See
[SVO.md](SVO.md#interfaces) for the full semantics table. Full runnable
example:
[`examples/04-sms-2fa`](https://github.com/SHIFT-LEFTER/shiftlefter/tree/main/examples/04-sms-2fa).
