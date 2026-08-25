# 07 — Custom capture kind: your adapter contributes evidence

The capture policy docs show *using* the built-in kinds — screenshot,
console, page-source — as a config line. This example shows the other
half of that contract: the kind set is **open**. The built-in kinds are
adapter-contributed, and a `setup.clj` adapter registry can contribute
yours the same way. No plugin API, no protocol to implement — a registry
entry is data, and the capture function is about ten lines.

Here a mock SMS adapter contributes `:message-log` — a text/plain dump
of the whole SMS conversation — and the `:every-step` policy captures it
after every step of a green scenario. Browser-free: the mock SMS line
ships in the framework, so this example needs nothing installed.

## Run it

```bash
sl run sl/features/
```

(In a checkout of this repo, substitute `bin/sl` for `sl` — see
[examples/README.md](../README.md).)

The run is green, and the stamped results directory
(`sl/results/<stamp>/message-log/` — one subdirectory per setup group)
holds the evidence:

- `attachments/<scenario-slug>/step-N-message-log-sms-alice.txt` — one
  conversation dump per step (from the first send onward), growing as
  the conversation does;
- `report.html` — the same refs rendered on each step, excerpt inline
  (an end-of-run copy also lands at the configured `results/report.html`);
- `sl run --edn sl/features/` prints the machine envelope, whose
  `:attachments` walk carries every ref
  (`:attachment/kind :message-log`, media type, byte count, capture
  duration).

## The arc, station by station

**1. Config names the kind and the policy**
([`sl/shiftlefter.edn`](sl/shiftlefter.edn)):

```clojure
{:runner {:capture {:message-log :every-step}}}
```

`:message-log` is not built in — the config line is just a name. It
means something because station 2 contributes it. `:every-step` is the
film-strip policy (captures on green runs, following the acting
subject); production suites usually want `:on-failure`. The CLI mirror
`--capture message-log=on-failure` wins over the config line as a whole
value.

**2. The adapter registry contributes the kind**
([`sl/setup.clj`](sl/setup.clj)):

```clojure
{:adapter-registry
 (assoc reg/default-registry :sms-mock
        {:factory  (fn [_] {:ok shared-sms})
         :cleanup  (fn [_] {:ok :closed})
         :provides [:shiftlefter.sms.protocol/ISMS
                    :shiftlefter.sms.protocol/ISMSInbound]
         :capture  {:message-log {:fn capture-message-log}}})}
```

The `:capture` map is the whole extension surface: kind → capture fn.

**3. Refs land in the envelope.** The framework owns timing, file
writes, filenames, and ref construction. Each ref records kind, path,
media type, byte count, capture duration (excluded from step durations),
and `:attachment/source :capture-policy`.

**4. The report renders them.** Every surface that renders attachments —
`report.html`, the EDN envelope, failure output — shows your kind with
no rendering code written. Unknown media types render as labeled lines
by construction.

## The capture fn contract

```clojure
(defn- capture-message-log
  [{:keys [handle]}]
  (let [{:keys [ok error]} (sms/query-messages handle {:since-ts Instant/EPOCH})]
    (cond
      error       (throw (ex-info ... {:error error}))
      (empty? ok) nil
      :else       {:data (format-log ok) :media-type "text/plain" :ext "txt"})))
```

- **In:** `{:handle h}` — whatever your factory produced. This entry
  declares no `:impl-key`, so the handle is exactly the instance the
  factory returned. (Entries with `:impl-key` get the full factory
  result — the cleanup handle — so captures can reach non-protocol
  internals like the underlying driver.)
- **Out, three ways — the split is the contract:**
  - `{:data ... :media-type ... :ext ...}` (+ optional `:excerpt`) —
    something captured. `:data` is a String or byte array; the framework
    writes the file.
  - `nil` — **nothing to capture.** No file, no ref. The scenario's
    opening empty-inbox step demonstrates it: the log is empty, the fn
    returns nil, and that step has no attachment.
  - **throw** — **the capture itself failed.** An error from the query
    is not an empty log; swallowing it would leave a green run whose
    promised evidence silently vanished. The framework converts the
    throw into a capture-attributed failure: an otherwise-green scenario
    goes `:error`, an already-red one keeps its status and the ref
    records the miss (the evidence contract in
    [docs/hooks.md](../../docs/hooks.md)).
- **`:scenario-scoped?`** — absent here because `query-messages` is
  non-destructive: each per-step capture re-reads the whole log, so the
  dumps grow step over step. A kind whose read *drains* its source (the
  built-in `:console` is a read-and-clear) sets `:scenario-scoped? true`
  so `:on-failure` drains once post-steps instead of at the failing
  instant.

## The declaration, and what validates you

The group's `:adapters` entry declares the registry's plan-time data
half — `:provides` plus the capture kinds:

```clojure
:adapters [{:name :sms-mock
            :provides [...ISMS ...ISMSInbound]
            :capture  {:message-log {}}}]
```

Dry runs bind against this declaration (functions never run at plan
time), and at `:start` the live registry is verified against it. Two
guardrails are worth triggering on purpose:

- Drop the declaration's `:capture` line → `:setup/registry-mismatch`
  planning error: the live registry contributes a kind the declaration
  didn't admit to, and an undeclared delta is exactly the preview
  blindness the contract exists to kill.
- Malform the live entry (typo `:captur`, a non-fn `:factory`) →
  `:setup/invalid-adapter-registry` at install, with a spec explanation
  naming the problem — the registry-entry shape is spec'd and validated
  at the setup boundary, so a bad entry fails legibly before any factory
  runs, not incidentally at first use.

## When to contribute a kind vs write an After hook

An After hook remains the sanctioned channel for one-off evidence. But
when the evidence belongs to a capability you provision — its adapter is
already in your registry — contributing a kind buys the whole policy
machinery for free: config/flag control, on-failure vs film-strip
semantics, timing honesty, capture-attributed failures, and rendering on
every report surface. The consume side of the same open surface —
building your own rendering from the envelope refs and run-dir bytes —
is its own documented arc: [docs/REPORTS.md](../../docs/REPORTS.md) —
the EDN envelope is the extension contract.
