# ShiftLefter Locator Policy

Prefer intent references when accepted truth exists. An intent reference points
to a named project object or region and lets the project own how that object is
located for each interface.

Raw EDN locators are a fallback, not the normal authoring target. They are useful
for experiments, tiny examples, and cases where no accepted intent exists yet.
They are also easier to make brittle.

Do not replace an accepted intent reference with a raw selector just because the
raw selector is visible in a page. That loses project vocabulary and makes future
maintenance harder.

If an object is missing from accepted intents, record the gap and use the
bootstrap/reconciliation workflow. Do not silently promote a guessed locator into
accepted truth.

The same preference applies to URLs: navigation and region assertions
(`opens the browser to`, `should be on`) accept a bare intent name (`Feed`,
unquoted) resolved via the intent's `:location` binding plus the interface's
`:config :base-url` — prefer that over hardcoding URLs in step text. Quoted
values are always literals (quoted = literal, bare = ref — the same rule as
element slots); literal URLs remain the fallback, exactly like raw EDN
locators.

## Choosing a web locator strategy

The above is intent references vs. raw locators. This is which raw locator to use
once you reach for one — authoring an intent's web binding, or a tiny example with
no accepted intent yet.

A locator is a hypothesis about what stays stable. Bind to the most stable thing
you are committed to keeping true about the element. Brittleness is not one axis:
a break on a cosmetic refactor is noise; a break because an element lost its
accessible name is signal. Prefer locators readable as intent — legible locators
are repairable.

Two rankings; which applies depends on whether you can change the markup.

You control the source (can add/enforce markup):

1. Dedicated automation attribute (`data-testid`/`-test`/`-cy`/`-qa`) — canonical,
   paired with role + accessible name as validation.
2. ARIA role + accessible name.
3. Label (form fields).
4. Stable human-authored id (never hashy/generated).
5. Visible text (when the text is the requirement).
6. CSS attribute / light structural (`button[type=submit]`).
7. CSS class / layout — scoping prefix only, never identity.
8. XPath — last resort, relational/axis only.

You cannot modify the app (third-party/legacy — borrow, do not manufacture):

1. ARIA role + accessible name.
2. Label.
3. Visible text, scoped.
4. Stable id (only if human-readable, stable-looking).
5. CSS attribute selectors (`[name]`, `[type]`, `[aria-*]`, `[href]` over classes).
6. CSS class / structure (only if semantic and stable).
7. XPath (tables, sibling relations, ugly DOMs — scope it, mark fragile).

The dedicated attribute is canonical when you own the markup because this durable,
agent-maintained, often multi-locale layer pays most of its cost in maintenance
churn, and an owned contract — a `data-testid`, effectively an API endpoint in the
DOM — insulates against the copy/layout/i18n changes that otherwise force
re-derivation. The price: a test-id can stay attached while the real control
breaks, so always pair it with role + name as validation, and name by product
intent (`domain.object.action`, e.g. `checkout.submitOrder`), never by
implementation (`greenButton`). Enforce uniqueness.

Match exactly the set you mean — often one element, sometimes a known collection
you index into. Aim for a count you can state in advance, not an unpredictable one.

The agent or user may always override the default for a given element. Record why.

Other interfaces (iOS, Android, REST, …) narrow the menu but keep the rule: bind
to an owned, intent-named contract over incidental structure. Carry the
philosophy, not the literal web ranking.

Full justification and the change-behavior evidence table: `docs/LOCATORS.md`.
