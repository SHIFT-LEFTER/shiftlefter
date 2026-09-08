<!-- GENERATED — DO NOT EDIT. Derived from the internal source
     notes/architecture/index.md (development repo) by
     scripts/derive-arch-docs.bb. Edit the source, then re-run:
     bb scripts/derive-arch-docs.bb -->

# The Architecture Pages

Six documents, one method: each page here is checked against the code it
describes, on a recorded date, by a stated procedure — extraction, live
runs, or a guard test — so the reader is holding a measurement, not a
recollection.

## The pages

- [The invocation map](invocation-map.md) — every CLI verb through the
  shared dispatch spine: three verb families, per-lane exit codes from
  live runs, warm/cold parity.
- [The control loop](control-loop.md) — inside one scenario's execution:
  the step loop, the context contract, and every failure path's honest
  exit.
- [The data-shape ledger](data-shapes.md) — every boundary-crossing value:
  producer, spec, consumers, stability class.
- [The module map](module-map.md) — the require graph as it actually is:
  extracted edges, layer laws, and the verdict on each.
- [REPL custody and lifetimes](repl-lifetimes.md) — what outlives an
  invocation in a live session, and who owns it.
- [Exit codes](exit-codes.md) — the ladder: five codes, the axis that
  orders them, and the error-class contract that keeps a green honest.

## How a page lands here

Each page is a mechanical projection of a canonical source maintained in
the development repository. Regeneration is scripted, and drift is a test
failure, three ways: a committed page that differs from fresh derivation
fails the suite; a stamped extraction count that disagrees with the code
fails the suite; an exit-code table that disagrees with the canonical
table in the code fails the suite. The pages are regenerated, not
remembered — when the code moves, a red test says which page is now
lying, and the remedy is a re-run, not an act of memory.

## How this page stays true

This page is a mechanical projection of a live-maintained internal source,
regenerated — never hand-edited — by the derivation pipeline and
drift-guarded by the test suite: a hand edit here fails a test, and so
does a page that no longer matches its source.
