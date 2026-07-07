# Two-Factor Password Reset via SMS

A real browser drives a password reset flow. The second factor — a 6-digit
verification code — arrives by SMS. ShiftLefter reads it back from the
mock SMS line and types it into the form. One scenario, two interfaces,
no plumbing in the feature file.

> **Mode: Shifted** — the config carries `:svo` and a subject glossary, so
> the scenario validates against the project vocabulary at planning time
> (`sl orient` will say `Mode: Shifted`). This is also the first example
> with `setup.clj` orchestration — see
> [the examples index](../README.md) for where it sits on the path.

## The Punchline

```gherkin
Feature: Two-factor password reset via SMS

  Scenario: Alice resets her password using a code sent to her phone
    When :user/alice opens the browser to 'http://localhost:9090/reset-password'
    And  :user/alice fills {:id "email"} with 'alice@example.com'
    And  :user/alice clicks {:css "button[type=\"submit\"]"}
    Then :user/alice should see 'Enter Verification Code'

    When [:sms] :user/alice receives an SMS to '+15550001111' matching /verification code is: (\d{6})/
    And  :user/alice fills {:id "code"} with the SMS code
    And  :user/alice clicks {:css "button[type=\"submit\"]"}
    Then :user/alice should see 'Code verified for alice'
```

That's it. Each step calls into one of two interfaces (`:web` via Etaoin,
`:sms` via a mock adapter). The `[:sms]` annotation on the receive step
narrows binding to the SMS interface — that's the lane marker.

## The 14 Lines of Custom Code

The framework's SMS receive step stashes the regex captures into ctx as
`{:sms/captures {:groups [code]}}`. There's no built-in step today that
types a captured value into a browser field. So you write one:

```clojure
;; steps/handoff.clj
(defstep #":([\w./-]+) fills (\{[^}]+\}) with the SMS code"
  {:interface :web
   :requires-protocols [:shiftlefter.browser.protocol/IBrowser]}
  [ctx subject locator-text]
  (let [code        (first (get-in ctx [:sms/captures :groups]))
        session-key (bctx/subject->session-key subject)
        browser     (bctx/get-session ctx session-key)]
    (bp/fill! browser (edn/read-string locator-text) code)
    ctx))
```

That's the entire bridge between web and SMS. A planned follow-up
(generic ctx-interpolation) may make this expressible as
`{ctx.sms.captures.groups.0}` in step text directly. For now, custom
stepdefs are how you compose interfaces — and they're easy.

One expected notice at load time: `Step … has :interface without :svo —
SVO validation will be skipped`. That's accurate — this step's value comes
from ctx rather than step text, which the SVO arg model can't declare yet,
so the step opts out of SVO validation.

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

- **Cross-interface scenarios are trivial to write.** One feature file,
  two interfaces, one custom step.
- **Test orchestration has a home.** `setup.clj` is where you stand up
  whatever your test needs — HTTP servers, queues, mocked external
  services — and share state with the framework's capabilities.
- **No Twilio credentials required.** The mock satisfies both ISMS
  (production surface) and ISMSInbound (test seam) — no network, no
  auth, no flake.
