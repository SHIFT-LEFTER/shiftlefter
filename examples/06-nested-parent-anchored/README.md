# Parent-anchored nested addressing

One reused `ProductCard` component, rendered under three different section
wrappers with **different container markup but identical inner markup** — the
Amazon-`ProductCard` pattern (design §7.3). This is the anchoring model
self-rooting *cannot* express: when the same visual component is emitted by
different templates with different wrappers, only the parent knows where its
instances are, so the parent's collection supplies the `:selector`.

## The site

`GET /catalog` (served by the bundled fixture server) renders:

| Section | Wrapper selector | Cards |
|---|---|---|
| `.featured` | `.fbt-item` | Widget A, Widget B |
| `.sidebar`  | `.recs-card` | Gadget X, Gadget Y (+ a `.promo` banner) |
| `.results`  | `.s-result-item` | Thing 1, Thing 2, Thing 3 |

Every card's inner markup is the same: `.title`, `.price`, and a nested
`.rating` → `.stars`.

## The intents

- `ProductCard` declares **no `:root`** — its container varies, so it can't
  self-locate. Inner elements (`.title`, `.price`) are relative to whatever
  container holds it.
- `Dashboard` mounts the same `ProductCard` three times; each collection
  supplies a different `:selector` (`.featured .fbt-item`, etc.).
- `Rating` is a nested region inside each card (`.rating` → `.stars`),
  demonstrating nested descent: `Dashboard.featured[1].rating.stars`.

## What it demonstrates

- **Parent-anchored anchoring** (§7.3/§7.4): one component, three wrappers.
- **Indexing**: `[1]`, `[2]`, `[-1]`.
- **Whole-collection fan-out**: `[*]` (see the test).
- **Nested descent**: `…rating.stars`.
- **Heterogeneous-cell exclusion** (§7.7): the `.promo` banner is excluded from
  `Dashboard.sidebar[*]` because the precise `.recs-card` selector skips it.

## Run it

> **Mode: Shifted** — the config carries `:svo` plus subject *and intent*
> glossaries (`sl orient` will say `Mode: Shifted`).

This example is a mini-project pulling the framework via `:local/root`.
Run from this directory so the relative glossary/feature paths resolve
(needs a real browser — ChromeDriver on PATH or via
`~/.shiftlefter/config.edn`):

```bash
clj -M:demo
```

The deterministic, CI-able assertions (including `[*]` fan-out and the §7.5
fallback-chain validation) live in the internal validator at
`test/shiftlefter/examples/nested_parent_anchored_e2e_test.clj`.
