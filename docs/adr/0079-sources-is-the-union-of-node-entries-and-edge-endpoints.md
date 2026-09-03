# ADR-0079: `sources[]` is the uniform union of node entries and edge endpoints

- **Status**: Accepted
- **Date**: 2026-09-03
- **Ticket**: [PostgreSQL schema and persistence](https://github.com/fredskor/nodqora/issues/16)
- **Amends**: ADR-0048, ADR-0056

## Context

ADR-0056 widens `sources[]` on the wire to `{plugin, confirmedAt}`, joined from
the snapshot store. Under ADR-0071 that join is against `NODE` entries.

**A stub node has no `NODE` entry anywhere** — ADR-0048 materializes it from an
edge whose endpoint no snapshot carries. The join returns nothing, so the stub
ships `sources: []` while its folded row says *"the plugins whose edges name it"*.
The document contradicts itself.

That exposes a rule the ADRs never settled. CONTEXT.md says `sources[]` is *"the
set of snapshots whose scope carries the key"*; ADR-0048 says a stub's is *"the
plugins whose edges name it"*. Two different rules, never distinguished, because
the fixture has zero dangling endpoints in steady state.

## Decision

**`sources[]` is the union: plugins carrying the key as a `NODE` entry, plus
plugins naming it as an edge endpoint. `confirmedAt` is the maximum over both
entry kinds.** The rule is uniform — stubs are literally the empty case.

## Consequences

- **ADR-0048's own framing decides it**: *"There is no `stub` flag — it is the
  empty case of a node the model already has."* A stub-only fallback reintroduces
  the flag as a branch in the fold and a branch in the read query, which is the
  same thing wearing different clothes, and lets the two rules drift apart later
  without anything failing.
- **Shipping `confirmedAt: null` for stubs was the cheapest fix and the worst.**
  It invents a fourth state — *a source with no confirmation time* — for ticket
  [#17](https://github.com/fredskor/nodqora/issues/17) to design a pixel for, in
  exactly the situation ADR-0056 built `confirmedAt` to explain.
- **Cost: provenance inflates.** Under ADR-0041 `connect` emits edges to topic keys
  it does not own, so `payments.events.enriched.v1` carries
  `sources: [yaml, kafka, connect]` where the strict rule gives `[yaml, kafka]`.
  The inspector's "Discovered by" section lists a plugin that did not discover the
  node.
- Taken anyway, because the inflated reading is **defensible rather than false** —
  `connect` did assert that topic exists, by naming it — and because ADR-0050 makes
  `sources[]` a diffed collection driving `updatedAt`. Under the strict rule,
  `connect` losing sight of a topic it names changes nothing, so an edge silently
  vanishing from a real node's provenance is invisible; under the union it moves
  `updatedAt`, which is what ADR-0003 wants that timestamp to mean.
- This is the closest call in the ticket. The strict rule is the answer if
  "Discovered by" must stay literal.
