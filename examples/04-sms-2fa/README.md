# Two-Factor Password Reset via SMS

A real browser drives a password reset flow. The second factor — a 6-digit
verification code — arrives by SMS. ShiftLefter captures it into the
scenario data plane and types it back into the form. One scenario, two
interfaces, **zero custom code**.

> **Vocabulary:** every step here is typed — the project carries a subject
> glossary and a `Login` intent, and the config's `:enforcement` block sets
> the levels, so the scenario validates against the project vocabulary at
> planning time. This is also the
> first example where you write `setup.clj` orchestration of your own —
> 02/02b ship a ready-made one — see
> [the examples index](../README.md) for where it sits on the path.

## The Punchline

```gherkin
Feature: Two-factor password reset via SMS

  Scenario: Alice resets her password using a code sent to her phone
    When :user/alice opens the browser to 'http://localhost:9090/reset-password'
    And  :user/alice fills Login.email with 'alice@example.com'
    And  :user/alice clicks Login.submit
    Then :user/alice should see 'Enter Verification Code'

    When :user/alice receives an SMS to '+15550001111' matching Reset.smsCode
    And  :user/alice fills Login.code with {smsCode}
    And  :user/alice clicks Login.submit
    Then :user/alice should see 'Code verified for alice'
```

That's it. Each step calls into one of two interface instances (`:web` via
Etaoin, `:sms` via a mock adapter). No step says which: "receives an SMS"
only binds to SMS vocabulary, and this project configures exactly one
`:sms` instance, so the lane is derived. (With two instances of a type,
a `[:sms-name]` annotation at the head of the step picks one — see
`docs/SVO.md`.)

## The Handoff: A Declared Shape and a Token

The cross-interface bridge is one reference and one token of Gherkin:

1. **Produce.** `matching Reset.smsCode` names an intent element, and the
   element declares the shape of the text it owns — in
   `sl/glossary/intents/reset.edn`:

   ```edn
   {:intent "Reset"
    :elements {:smsCode {:bindings {:sms {}}
                         :shape "verification code is: (\\d{6})"}}}
   ```

   A message is a readable with no locator, so the `:sms` binding is
   empty and the shape is all it has. The shape has one capture group, so
   its value binds to the element's own name: on a match, `{smsCode}`
   enters the scenario's data plane. (A shape with several groups names
   each one — `(?<orderNumber>…)` — and those names are the tokens.)
2. **Consume.** Any literal-admitting slot accepts a `{name}` token:
   `fills Login.code with {smsCode}` resolves the binding at execution
   time. Quoted text is always literal — `'{smsCode}'` would type the
   eight characters `{smsCode}`.

The regex never appears in the scenario. It can — `matching /verification
code is: (?<code>\d{6})/` inline is the escape hatch for a one-off pattern
— but a shape declared once on the element is the primary form, and
putting both on one step is a planning error (two declarations of one
shape).

Bindings are scenario-scoped, flow forward only, and die at scenario end.
The dry run statically checks every consumed name has an upstream producer
(with a did-you-mean on typos), so a misspelled `{coed}` fails at planning
time, not mid-run.

Earlier versions of this example needed a 14-line custom step definition
to read the captured code out of ctx. That stepdef — and the load-time
"SVO validation will be skipped" notice it carried — is gone: the receive
step's frame is fully declared, and the handoff is first-class.

## Standing Up the SUT — `setup.clj`

This demo needs a target webapp. We don't want users to spin one up
manually before each run, so `setup.clj` (a sibling of `shiftlefter.edn`,
both in `sl/`) declares the orchestration. ShiftLefter loads it
automatically.

```clojure
;; sl/setup.clj — abridged
(defn- start-sms-2fa [_config]
  (let [shared-sms (->MirrorMockSMS (mock/make-mock-sms))
        stop!      (http/run-server
                    (fh/build-handler {:users {...}
                                       :pages [:reset-password]
                                       :sms shared-sms})
                    {:port 9090})]
    {:adapter-registry  ;; framework's :sms interface uses the SAME instance
     (assoc reg/default-registry :sms-mock
            {:factory (fn [_] {:ok shared-sms})
             :cleanup (fn [_] {:ok :closed})
             :provides [:shiftlefter.sms.protocol/ISMS
                        :shiftlefter.sms.protocol/ISMSInbound]})
     :stop (fn [] (stop! :timeout 100))}))

(def setups
  [{:label    "sms-2fa"
    :start    start-sms-2fa
    :features ["features/password_reset_sms.feature"]}])
```

Two non-obvious bits worth highlighting:

1. **Shared mock instance.** The fixture server and the framework's
   `:sms` interface get the *same* MockSMS object. That's the only way
   the code the server sends ends up in the log the test reads.

2. **`MirrorMockSMS` wrapper.** `MockSMS.send!` records `:direction
   :outbound` (the app's view). The framework's receive step polls
   with `:direction :inbound` (the user's view). Real Twilio logs
   both directions on the account; we simulate that with a 10-line
   wrapper that mirrors every send to an inbound entry.

## Running It

Needs a local ChromeDriver matching your Chrome — resolved via
`~/.shiftlefter/config.edn` (`:chromedriver-path`), no PATH setup needed
(PATH is the fallback).

```bash
cd examples/04-sms-2fa
clj -M:demo
```

`setup.clj` consumes `shiftlefter.demo.fixture.*` — first-class
namespaces under the framework's `src/`, so the `:demo` alias just sets
`:main-opts`; no classpath hack needed.

A Chrome window opens, drives the form through both stages, and exits
clean. Test passes; setup.clj's `:stop` releases port 9090.

Known warns: the two `should see '…'` steps report `Unknown object` at
plan time — a frame-classification gap (sl-9gfg), not a vocabulary hole;
they're harmless and will disappear when that bead lands.

### The receive window

The receive step's default smart baseline Just Works here: the SMS
adapter's `:on-provision` hook seeds `:sms/scenario-start-ts` when the
interface is provisioned, and provisioning is eager by default — every
interface a scenario touches is provisioned at scenario start, before the
fixture server sends the code. So the step reads "any matching SMS since
the scenario started."

An explicit wall-clock window is also available when you need one —
`receives an SMS to '…' within the last 1 minute matching Reset.smsCode`
— e.g. for messages sent before the scenario began.

## What This Proves

- **Cross-interface composition needs zero custom code.** One feature
  file, two interfaces, a declared shape, and a `{smsCode}` token.
- **The data plane is statically checked.** Consumed-without-producer is
  a planning error with a did-you-mean; a groups-but-no-names pattern
  gets a notice. Typos die in the dry run.
- **Test orchestration has a home.** `setup.clj` is where you stand up
  whatever your test needs — HTTP servers, queues, mocked external
  services — and share state with the framework's capabilities.
- **No Twilio credentials required.** The mock satisfies both ISMS
  (production surface) and ISMSInbound (test seam) — no network, no
  auth, no flake.
