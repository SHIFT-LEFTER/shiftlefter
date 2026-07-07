# Choosing Web Locators

When ShiftLefter drives a browser, something has to point at the element you mean
— the submit button, the email field, the error banner. That pointer is a
**locator**, and which kind you reach for is the difference between a test that
survives a year of refactors and one that breaks every Tuesday.

This page is about **web** locators specifically, because the web is the rare
interface that hands you a dozen ways to point at the same element: a test
attribute, an accessibility role, a label, visible text, an id, a CSS path, an
XPath. Most interfaces give you one or two and the choice makes itself. The web
makes you choose, so it's worth knowing how. The *reasoning* here generalizes;
the *menu* is web-shaped — see [Beyond the web](#beyond-the-web) at the end.

## A locator is a hypothesis

Every locator is a bet about what will stay stable. `#submit-btn-v2` bets the id
won't change. "Click the button that says *Submit*" bets the copy won't change.
`div > form > button:nth-child(3)` bets the layout won't change. Pick the wrong
thing to bet on and the test breaks on a change that had nothing to do with the
behavior you were checking.

So the question behind every locator is the same: **what's the most stable thing
I'm actually committed to keeping true about this element?** Bind to *that*.

## Brittleness isn't one axis

It's tempting to rank locators from "robust" to "fragile" and call it done. That's
wrong, and the wrongness matters. A locator that breaks on a cosmetic refactor is
bad. A locator that breaks *because a button lost its accessible name* is **good**
— that break is a real signal you'd want to know about. Judge a locator not by
*whether* it breaks but by *what it couples to* and *whether its breakages mean
something*:

- Couples to **layout or CSS class** → breaks on reskins and reparenting that
  change nothing the user cares about. Noise.
- Couples to **visible text** → breaks on a copy edit or a locale switch. Sometimes
  noise, sometimes exactly the signal you want (if the text *is* the requirement).
- Couples to **semantic identity** (the accessibility role and name) → breaks when
  the element stops being that thing. Usually signal.
- Couples to **an explicit contract you put there on purpose** → breaks only when
  you move the contract. Quiet by design.

There's a second principle specific to how ShiftLefter is used. The thing
maintaining these tests over long development cycles is increasingly an **agent**,
and for an agent **legibility is repairability**. A locator an agent can read as
intent — "the checkout submit button" — can be re-derived and self-healed when the
page shifts. An opaque structural path can only be blindly re-scraped. Readable
locators are cheaper to keep alive.

## The criteria, in priority order

When you weigh one locator against another, this is the order that matters:

1. **Semantic-contract strength** — does it express the element's stable product
   meaning, or an incidental detail?
2. **Resistance to irrelevant change** — does it survive a CSS refactor, a DOM
   restructure, a copy edit, an i18n switch, a component swap?
3. **Correct failure behavior** — when it *does* break, is the break meaningful
   (the target is genuinely gone) rather than noise (a `div` got wrapped around it)?
4. **Legibility and repairability** — can a human or agent read *what* it points at
   and *why*, and infer a safe fix? Weighted high here, because the maintainer is
   an agent.
5. **Predictable scope** — does it resolve to exactly the set you *meant*, in the
   right context? Often that's a single element. Sometimes it's deliberately a
   collection — every row in a table, every tweet in a timeline you then index
   into or hang nested locators off of. What poisons an agent isn't matching
   *many*; it's matching an *unpredictable* number, or the wrong one. Aim for a
   count you can state in advance.
6. **Controllability** — can you actually use it, and do you own its stability? This
   one *gates the menu*: it changes the whole ranking depending on whether you own
   the markup.
7. **Cost and portability** — tie-breakers, once the above are settled.

## The ranking — two situations

Because controllability gates the menu, there isn't one ranking. There are two,
and which you're in depends on a single question: **can you change the markup?**

### When you control the source

You can add and enforce markup, so you can *manufacture* the most stable contract
instead of borrowing one:

1. **A dedicated automation attribute** — `data-testid` (or `data-test`,
   `data-cy`, `data-qa`). The canonical choice, *paired with role and name as
   validation* (more on that pairing below).
2. **Accessibility role + accessible name** — the best user-facing semantic
   selector, and the validation layer for the attribute above.
3. **Label** — for form fields, where the label is the stable user contract.
4. **A stable, human-authored id** — only when it's clearly intentional, never
   hashy or framework-generated.
5. **Visible text** — when the text itself is part of the requirement.
6. **CSS attribute / light structural** (`button[type=submit]`, `[aria-*]`) —
   fallback; prefer semantic attributes over classes.
7. **CSS class / layout structure** — avoid as identity; fine only as a scoping
   prefix.
8. **XPath** — last resort, for relational or axis traversal only.

### When you can't modify the app

Third-party or legacy software you don't own: there's no contract to create, only
the most contract-like property to *borrow*. So the dedicated attribute drops off
the top and semantic identity leads:

1. **Accessibility role + accessible name**
2. **Label**
3. **Visible text, scoped**
4. **A stable id** — only if it's human-readable and looks stable
5. **CSS attribute selectors** (`[name]`, `[type]`, `[aria-*]`, `[href]` over
   classes)
6. **CSS class / structure** — only if the classes look semantic and stable
7. **XPath** — for tables, sibling relationships, and ugly legacy DOMs; scope it
   and mark it fragile

## Why the dedicated attribute wins when you own the markup

This is the one recommendation that breaks with conventional wisdom, so it's worth
being honest about the disagreement.

Mainstream testing guidance — Testing Library, Playwright — puts **role + name
first** and treats test attributes as a fallback, on the principle that tests
should resemble how a user finds things, and that a meaningful failure has value.
That guidance is *correct for ordinary UI tests*. ShiftLefter is a different
animal: a **durable, agent-maintained semantic layer that lives across long
development cycles and is frequently multi-locale**. In that setting the dominant
cost isn't writing the locator — it's *maintenance churn*, the slow bleed of
re-deriving locators every time copy, layout, or locale shifts. An explicit owned
contract minimizes exactly that churn.

Think of a `data-testid` as an **API endpoint in the DOM**. It's decoupled from
CSS, layout, copy, and translation, so it survives precisely the incidental
changes that would otherwise force a re-derivation every cycle. Over a long-lived,
multi-locale suite, that insulation compounds.

This ranking isn't a fresh opinion. It's where a career of maintaining test
suites by hand tends to land — and when the same question is posed neutrally, with
no preferred answer and the criteria left un-named, to multiple frontier AI models,
they converge on the same governing principle, the same criteria, and the same
ranking, differing only in minor caveats about which default to lead with. When a
problem is described properly, experienced practitioners and independent models end
up in the same place. That's not proof, but it's a strong sign the ranking reflects
the shape of the problem rather than anyone's taste.

## The catch: a contract can lie

The price of an owned contract is that **a `data-testid` can lie**. It can stay
dutifully attached while the control underneath silently breaks for real users —
the button still has its test attribute but is no longer a button, or no longer
has its accessible name. A test that only checks the attribute would stay green
through a real regression.

So the canonical attribute is **never used alone — it's paired with role and name
as validation metadata.** The attribute is how you *find* the element; the role and
name are how you *confirm it's still the thing you meant*. The system warns when a
`data-testid` no longer resolves to the accessible control it's supposed to.

That pairing only works if the contract has discipline behind it:

- **Name by product intent, not implementation.** `checkout.submitOrder`,
  `auth.login.submit` — a `domain.object.action` shape. Never `greenButton`,
  `submit-btn-v2`, or `button-17`. The name should survive a redesign.
- **The attribute moves with the semantic control.** When a `<button>` becomes a
  design-system `<Button>`, the attribute rides along with it.
- **Uniqueness is enforced.** Lint or CI fails on duplicate ids, which turns
  test-id's biggest weakness — "discipline nobody enforces" — into a guarantee.
- **Don't strip test attributes from the production build** if the durable layer
  might ever run against production.

## What survives what

This is the evidence under the ranking — how each strategy behaves when the page
changes. ✅ survives · ➖ break is a *meaningful* signal · ⚠️ survives but may now
match the **wrong** element · ❌ breaks:

| Change | test attr | role + name | visible text | semantic id | CSS / structural | XPath (positional) |
|---|---|---|---|---|---|---|
| CSS redesign / reskin | ✅ | ✅ | ✅ | ✅ | ❌ ⚠️ | ✅ |
| Copy edit (Submit → Send) | ✅ | ➖ | ❌ | ✅ | ✅ | ✅\* |
| i18n / locale switch | ✅ | ❌ | ❌ | ✅ | ✅ | ✅\* |
| DOM restructure / reparent | ✅ | ✅ | ✅ | ✅ | ❌ ⚠️ | ❌ ⚠️ |
| Refactor / rename | ✅† | ✅ | ✅ | ❌ | ❌ | ❌ |
| Framework regeneration (hashed) | ✅ | ✅ | ✅ | ❌ | ❌ | ❌ |

\* if the XPath isn't text-based.  † depends on the discipline above carrying the
attribute with the control — which is why uniqueness enforcement matters.

The point of the table is the contrast in the two strongest columns. The test
attribute and role + name both dominate, but they **fail differently**: the test
attribute *insulates* against copy and locale changes; role + name *signals* on
them. ShiftLefter wants insulation as the default and the signal as paired
validation — which is exactly the pairing above.

## When to deviate

The default is a default, not a law. Override it deliberately when the situation
calls for it:

- **Accessibility *is* the requirement** → lead with role + name, and let it fail
  if the control is no longer an accessible button with that name. That failure is
  the whole point.
- **Exact copy *is* the requirement** → use visible text. Don't hide a copy
  regression behind a stable test attribute.
- **Copy is volatile** (A/B tests, experiments, locales) → use the dedicated
  attribute. The action is the stable identity; the string isn't.
- **A stable public id already exists** → using it is fine, though prefer adding an
  attribute when you can.
- **The element is genuinely unreachable** (no accessibility tree, no source
  control, a relational table cell) → narrowest scoped CSS, then XPath axes — and
  mark it fragile so the next maintainer knows.

Whatever you choose, **you can always override the default for a given element** —
just record *why*. The reason is what lets the next maintainer, human or agent,
understand the call instead of second-guessing it.

## Beyond the web

The specific ranking above is web-shaped, because the web is the unusual interface
with a dozen competing ways to address an element. Other interfaces narrow the
menu — but they keep the rule.

An iOS **accessibility identifier**, an Android **resource-id** or
**content-description**, a stable **REST resource path and contract field** — these
are the same move: bind to an owned, intent-named contract rather than to whatever
incidental structure happens to be nearby. The list of available strategies
changes from interface to interface; the question you're answering with it does
not. *What's the most stable thing I'm committed to keeping true about this
element?* — that travels everywhere.
