# Model Real Users and Sessions

Alice and Bob are both users — but not the same session. ShiftLefter models that
directly: each actor gets an isolated session, provisioned automatically.

## Actors are subject-first

Every step names its actor as `:type/instance`:

```gherkin
When :user/alice opens the browser to 'https://app.example.com/login'
And  :user/bob   opens the browser to 'https://app.example.com/login'
```

- **Type** (`:user`) — the role, used for glossary organization and display.
- **Instance** (`:alice`, `:bob`) — the **session key**. Each distinct instance
  gets its own browser session, with isolated cookies and state.

You don't wire any of this up: when ShiftLefter first sees `:user/bob`, it
provisions a fresh session for `:bob` and routes Bob's later steps to it. Same
config as a single actor.

## Declaring who can act

A subject glossary makes the cast explicit (and lets validation catch typos like
`:user/bbo` before anything runs):

```clojure
;; glossary/subjects.edn
{:subjects
 {:user {:desc "Standard application user" :instances [:alice :bob]}}}
```

Types with `:instances` group sessions under a role; singletons (no `:instances`)
are used directly. See [SVO.md](SVO.md) for the full model.

## Authenticated sessions that persist

When an actor needs a real, already-logged-in session that survives across runs,
have them **wear a costume** — a durable authenticated browser context — via `:wears`
on their entry in your subjects glossary:

```clojure
;; glossary/subjects.edn
{:subjects {:operator {:desc "Ops user" :wears :finance}}}
```

See [COSTUMES.md](COSTUMES.md). Working example:
[`examples/02b-browser-multi-actor`](https://github.com/SHIFT-LEFTER/shiftlefter/tree/main/examples/02b-browser-multi-actor).
