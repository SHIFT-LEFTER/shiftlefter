# Two-Factor Password Reset via SMS

A real browser drives a password reset flow. The second factor — a 6-digit
verification code — arrives by SMS. ShiftLefter captures it into the
scenario data plane and types it back into the form. One scenario, two
interfaces, **zero custom code**.

> **Mode: Shifted** — the config carries `:svo`, a subject glossary, and a
> `Login` intent, so the scenario validates against the project vocabulary
> at planning time (`sl orient` will say `Mode: Shifted`). This is also the
> first example with `setup.clj` orchestration — see
> [the examples index](../README.md) for where it sits on the path.

## The Punchline

```gherkin
Feature: Two-factor password reset via SMS

  Scenario: Alice resets her password using a code sent to her phone
    When :user/alice opens the browser to 'http://localhost:9090/reset-password'
    And  :user/alice fills Login.email with 'alice@example.com'
    And  :user/alice clicks Login.submit
    Then :user/alice should see 'Enter Verification Code'

    When [:sms] :user/alice receives an SMS to '+15550001111' matching /verification code is: (?<code>\d{6})/
    And  :user/alice fills Login.code with {code}
    And  :user/alice clicks Login.submit
    Then :user/alice should see 'Code verified for alice'
```

That's it. Each step calls into one of two interfaces (`:web` via Etaoin,
`:sms` via a mock adapter). The `[:sms]` annotation on the receive step
narrows binding to the SMS interface — that's the lane marker.

## The Handoff: Named Bindings

The cross-interface bridge is two tokens of Gherkin:

1. **Produce.** The receive step's match pattern names a group:
   `(?<code>\d{6})`. On a match, the captured text becomes a scenario
   binding — `{code}` — in the scenario's data plane. (An unnamed group
   `(\d{6})` binds nothing, and the dry run notices it.)
2. **Consume.** Any literal-admitting slot accepts a `{name}` token:
   `fills Login.code with {code}` resolves the binding at execution time.
   Quoted text is always literal — `'{code}'` would type the five
   characters `{code}`.

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
manually before each run, so `setup.clj` (a sibling of `shiftlefter.edn`)
declares the orchestration. ShiftLefter loads it automatically.

```clojure
;; setup.clj — abridged
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

Needs a local ChromeDriver matching your Chrome — on PATH
(`brew install chromedriver` on macOS) or via `~/.shiftlefter/config.edn`
(`:chromedriver-path`).

```bash
cd examples/04-sms-2fa
clj -M:demo
```

`setup.clj` consumes `shiftlefter.demo.fixture.*` — first-class
namespaces under the framework's `src/`, so the `:demo` alias just sets
`:main-opts`; no classpath hack needed.

A Chrome window opens, drives the form through both stages, and exits
clean. Test passes; setup.clj's `:stop` releases port 9090.

### The receive window

The receive step's default smart baseline Just Works here: the SMS
adapter's `:on-provision` hook seeds `:sms/scenario-start-ts` when the
interface is provisioned, and provisioning is eager by default — every
interface a scenario touches is provisioned at scenario start, before the
fixture server sends the code. So the step reads "any matching SMS since
the scenario started."

An explicit wall-clock window is also available when you need one —
`receives an SMS to '…' within the last 1 minute matching /…/` — e.g.
for messages sent before the scenario began.

## What This Proves

- **Cross-interface composition needs zero custom code.** One feature
  file, two interfaces, a named group, and a `{code}` token.
- **The data plane is statically checked.** Consumed-without-producer is
  a planning error with a did-you-mean; a groups-but-no-names pattern
  gets a notice. Typos die in the dry run.
- **Test orchestration has a home.** `setup.clj` is where you stand up
  whatever your test needs — HTTP servers, queues, mocked external
  services — and share state with the framework's capabilities.
- **No Twilio credentials required.** The mock satisfies both ISMS
  (production surface) and ISMSInbound (test seam) — no network, no
  auth, no flake.
