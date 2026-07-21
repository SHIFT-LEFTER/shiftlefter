# Self-rooted nested addressing

A consistent-markup `Post` component (`article[data-testid='post']`) that
self-locates via its `:root` and is mounted in two regions with **no per-place
wiring** — the self-rooted anchoring model (design §7.2). This is the ergonomic
case: define `Post` once, mount it anywhere its markup is consistent.

## The site

`GET /feed` (served by the bundled fixture server) renders two regions:

- `section[data-testid='feed']` — a **flat timeline**: posts by Alice, Bob,
  Carol, Dave, with `div[data-testid='ad']` cells interleaved.
- `section[data-testid='thread']` — one **expanded post** (Alice) that **quotes**
  another post (Zoe) and carries two **comments** (Bob, Carol).

## The intents

- `Post` self-roots on `article[data-testid='post']`; its `:author`/`:content`
  are relative to an instance. It declares `:quoted` (→ `Post`, recursion) and
  `:comment` (→ `Comment`).
- `Comment` self-roots on `[data-testid='comment']` and **reuses** the
  `[data-testid='author']` binding.
- `Feed` and `Thread` each mount `Post` with no `:selector` (fall back to
  `Post`'s `:root`, §7.5).

## What it demonstrates

- **Named locations** (sl-3jr4): the first scenario navigates with
  `opens the browser to Feed` and asserts arrival with
  `should be on Feed` (a region assertion: normalized path + fragment,
  query ignored — sl-q81m). The name is **bare** on purpose: quoted =
  literal, always; bare = ref (sl-iseq) — no URL in the feature text. The semantic
  PATH lives on the `Feed` intent (`:location {:web {:path "/feed"}}`); the
  environmental HOST lives in `shiftlefter.edn` under
  `[:interfaces :web :config :base-url]`. To point a run at another host,
  pass `--config` with an alternate `shiftlefter.edn` (the file is replaced
  wholesale, not merged). Literal URLs still work — the second scenario
  keeps one on purpose.
- **Self-rooted anchoring** (§7.2): one `:root`, mounted twice, zero wiring.
- **Indexing**: `[1]`, `[2]`, `[3]`, `[-1]`.
- **Heterogeneous-cell exclusion** (§7.7): `Feed.post[3]` is Carol, not the ad
  that sits between Bob and Carol.
- **Recursion + cross-type** (§8.1, the *nearest-enclosing-instance* rule):
  `Thread.post[*]` is the top-level post only (not the quoted one), and
  `Thread.post[1].author` is the post's own author — not the quote's author and
  not a comment's author, even though all three share the `author` binding.

All scenarios pass, including the §8.1 recursion / cross-type cases —
`Thread.post[*]` selects only the top-level post and
`Thread.post[1].author` resolves to the post's own author via
nearest-enclosing-instance pruning.

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

The deterministic, CI-able assertions live in the internal validator at
`test/shiftlefter/examples/nested_self_rooted_e2e_test.clj`.
