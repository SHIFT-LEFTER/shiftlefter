# Test Across Interfaces

One scenario can span more than one interface — a browser flow whose second
factor arrives by SMS, for instance. The same actor acts over both, and ShiftLefter
keeps each interface's session separate automatically. Passing a captured *value*
from one interface to another needs one small custom step today — shown below.

## Two interfaces, one scenario

```gherkin
Scenario: Reset password with an SMS code
  When :user/alice opens the browser to 'http://localhost:9090/reset-password'
  And  :user/alice fills {:id "email"} with 'alice@example.com'
  And  :user/alice clicks {:css "button[type='submit']"}
  Then :user/alice should see 'Enter Verification Code'

  When [:sms] :user/alice receives an SMS to '+15550001111' within the last 1 minute matching /code is: (\d{6})/
  And  :user/alice fills {:id "code"} with the SMS code
  Then :user/alice should see 'Code verified'
```

The same actor (`:user/alice`) acts over both the `:web` and `:sms` interfaces;
ShiftLefter keeps each interface's session separate.

## Passing a value between interfaces

The one step above that *isn't* built in is `fills {:id "code"} with the SMS code`.
Handing a value captured on one interface to another needs a small custom step today.
The SMS `receives` step stashes its regex captures at `ctx[:sms/captures]`; a short
`defstep` reads the captured code and types it into the browser field:

```clojure
(ns handoff
  (:require [clojure.edn :as edn]
            [shiftlefter.capabilities.ctx :as cap]
            [shiftlefter.browser.locators :as loc]
            [shiftlefter.browser.protocol :as bp]
            [shiftlefter.stepengine.registry :refer [defstep]]))

(defstep #":([\w./-]+) fills (\{[^}]+\}) with the SMS code"
  {:interface :web :requires-protocols [:shiftlefter.browser.protocol/IBrowser]}
  [ctx subject locator-text]
  (let [code    (first (get-in ctx [:sms/captures :groups]))
        browser (cap/get-capability ctx :web (keyword (name (keyword subject))))
        locator (loc/resolve-locator (edn/read-string locator-text))]
    (bp/fill! browser locator code)
    ctx))
```

The runnable original (with commentary) is
[`examples/04-sms-2fa/steps/handoff.clj`](https://github.com/SHIFT-LEFTER/shiftlefter/tree/main/examples/04-sms-2fa/steps/handoff.clj).

That ~14-line step is the *entire* glue between web and SMS in the 2FA demo. Generic
step-text interpolation (`{ctx.sms.captures…}`), which would remove even this, is on
the roadmap; until then a small `defstep` is how you compose interfaces — see
[Add Domain Language](extending-vocabulary.md#when-you-actually-write-a-step-definition).

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
