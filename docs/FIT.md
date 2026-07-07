# Would ShiftLefter work for my project?

A practical fit check — *applicability*, not philosophy. If your next question is
"why is it built this way?", that's a separate, deeper read (see [the end](#going-deeper)).

**How to use this page.** Hand it to your coding agent along with your project — your
repo, your app's URL, what you want to test — and ask: *"Based on this, would
ShiftLefter work for us, and how?"* Everything the agent needs to give you a straight
answer is below. You can also just read it yourself.

## The problem it's actually for

An agent can write you a passing browser test in one shot today. That's not the hard
part anymore. The hard part is the **second** shot, and the tenth.

Your app isn't a one-shot — it changes. And each time an agent regenerates a test, it
re-derives its own understanding of your app from scratch: its own selectors, its own
names for things, its own assumptions about who "the user" is. Run that loop a few
times — especially across **different models** — and the tests drift apart from each
other and from the app. Names collide. Selectors rot. PRs fill with churn nobody can
review. The spiral gets *faster* the more agents touch it, not slower.

ShiftLefter exists to give that loop something stable to stand on: a **semantic
layer** for your app's behavior — a typed vocabulary you define once and everyone
shares. You describe behavior against *your* terms (`:shopper`, `Login.submit`), and
those terms are validated before anything runs. The test stops being a disposable
script and becomes an expression of durable, shared meaning.

## What you actually get from that

These are properties of the design, here today — not promises:

- **A semantic layer you own.** Your glossary (subjects, verbs, intents — plain EDN)
  is the single definition of what your app's actors and actions *mean*. Tests are
  written against it, not against raw DOM.
- **You change vocabulary, not every test.** When the app moves, you update an intent
  or a glossary entry in one place instead of hand-patching selectors across a suite.
  The durable artifacts are designed to outlive any individual test.
- **Many models, one understanding.** Any agent — Claude, GPT, whatever you run next
  quarter — reads the same glossary and means the same thing by `:shopper`. Shared
  ground truth across models *and* across the humans on your team. No re-deriving the
  app from scratch every session.
- **PRs you can actually review.** Behavior changes show up as vocabulary and intent
  diffs, not thousand-line selector noise. Reviewers see *what changed about the
  behavior*, not how the robot happened to phrase it this time.
- **Mistakes caught before the browser opens.** A typo'd actor or an undefined step
  fails at bind time — not several minutes into a run — with "did you mean?"
  suggestions.

## Get in on the ground floor

The reason the semantic layer is worth building now is where it leads. ShiftLefter is
heading toward **executable traceability**: your requirements and use-cases linked to
the features that realize them, the tests that exercise them, and the runs that prove
them — with observation lineage from the real world back to the spec.

That's **roadmap, not shipped** (see the [stability contract](README.md#what-05-is-and-isnt)) —
don't adopt 0.5 *for* the graph today. Adopt it for the semantic layer now, and know
that the glossary and intents you write today are exactly the foundation that graph is
built on. The discipline you put in early is what you'd be glad you have when it lands.

## Is it a fit for your project?

**It shines when:**

- The behavior runs through a **web UI** or over **SMS**.
- You're **authoring new behavior** and want it to last — a semantic layer you'll keep.
- **Agents (often several) write and maintain your tests**, and you want them to share
  one understanding of the app rather than each inventing their own.
- You have **multiple actors or interfaces** in play (multiple users, web + SMS) — the
  isolation and typed vocabulary earn their keep fast here.
- You're willing to author a glossary (plain EDN) and, for genuine edge cases, drop
  into a little Clojure.

**Wait for a later release if, *today*, you need:**

- **xUnit / HTML / JSON report artifacts** for a CI dashboard — output is console +
  structured EDN for now.
- **First-class fixtures, hooks, or `@tag` filtering** — not built yet (there's a
  Clojure escape hatch for test-data setup, but it isn't first-class).
- **To migrate an existing suite as-is** — your `.feature` files parse unchanged, but
  an existing Java/JavaScript/Ruby step library doesn't transfer. See
  [Bring Existing Gherkin (Preview)](adoption.md).
- **To drive something other than a web browser or SMS** — no adapter for native
  desktop, mobile apps, or API-only testing yet.

None of these are forever; they're "not in 0.5." If the first list sounds like you and
the second list isn't blocking, you're who 0.5 is built for.

## For your agent: a quick fit pass

Evaluate against the user's actual project. Questions 1–2 are load-bearing; 3–6 size
the upside and the gaps.

| # | About the project | Strong signal | Where you'll feel an edge |
|---|---|---|---|
| 1 | **Interface** | Web UI (Chrome, or Firefox via the Playwright backend) or SMS. | Native desktop, mobile app, or API-only with no web/SMS surface → not yet. |
| 2 | **New vs. existing** | Authoring new behavior. | Porting a production Cucumber/Selenium suite as-is → step code won't transfer. |
| 3 | **Who maintains tests** | Agents (esp. multiple models) + humans sharing one vocabulary. | A team that needs everything in their host language with zero Clojure. |
| 4 | **Scenario shape** | Multiple actors, or web + SMS together. | Single linear single-actor flows (works, just less upside). |
| 5 | **Reporting** | Console + machine-readable EDN is enough. | Need xUnit/HTML/JSON artifacts today. |
| 6 | **Fixtures/hooks** | Test data set up in a step; no hook/tag dependence yet. | Need first-class fixtures, hooks, or tag selection now. |

**Verdict:** strong on 1–4 → **strong fit**. A gap on 5 or 6 but yes on 1–2 →
**workable, with a known edge**. A "no" on 1 or 2 → **not yet for this project** —
worth watching, not worth forcing.

## How it would work on your project

- **Author a glossary** — your subjects, verbs, and intents as plain EDN. You and/or
  your agent write it; ShiftLefter validates against it.
- **Write features against built-in steps** — browser navigation, clicking, filling,
  verification, all with no custom code. Each actor gets its own isolated session.
- **Use costumes** for durable authenticated state (a logged-in Chrome profile an
  actor "wears") — see [Costumes](COSTUMES.md).
- **Drop into Clojure only at the edges** — test-data/fixtures, niche web (canvas,
  custom JS), or a new interface. Ordinary web/SMS behavior needs none.

Most of this is **Java-only** to install — no Clojure toolchain. See the
[capabilities tiers](CAPABILITIES.md#at-a-glance) for what each install unlocks.

## Going deeper

This page answers "*will it work for my project?*" If your next question is "*why is it
built this way, and should I align my own design with it?*" — that's
[Why it's built this way](PHILOSOPHY.md), the deeper design-philosophy read. You never
need it to use ShiftLefter; it's there if you want the reasoning underneath.
