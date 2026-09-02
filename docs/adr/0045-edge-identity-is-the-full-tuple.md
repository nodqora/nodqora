# ADR-0045: An edge's identity is the full tuple, so edges have no contestable scalar

- **Status**: Accepted
- **Date**: 2026-09-02
- **Ticket**: [Discovery-engine merge semantics](https://github.com/fredskor/nodqora/issues/12)

## Context

Two candidate identities: `(environmentKey, fromKey, toKey)` with `relation` as a
contested scalar, or the full tuple including `relation`.

The case that would force the narrow key — a service that both produces to and
consumes from one topic — turns out to have been removed already by ADR-0002.
Because edges are stored **flow-directed**, `PRODUCES_TO` stores `svc → topic`
and `CONSUMES_FROM` stores `topic → svc`. Orientation separates them into two
different ordered pairs on its own.

## Decision

**Edge identity is `(environmentKey, fromKey, toKey, relation)`, with endpoints
matched case-folded per ADR-0020.**

Edge merge is therefore **set union over the stored snapshots**, with `sources[]`
falling out of membership.

## Consequences

- **Edges have no contestable scalar at all.** `relation` is in the key,
  `metadata{}` is namespaced (ADR-0006), `sources[]` is derived,
  `discoveredAt`/`updatedAt` are engine-computed (ADR-0050). ADR-0044's
  precedence order applies to **nodes only**. This is a real reduction in the
  engine, not a tidier way of writing the same thing.
- The fixture's one real double-write lands correctly for free: ADR-0041 has
  `connect` emit `payments.events.enriched.v1 → payments-es-sink`
  (`SOURCES_FROM`) and YAML declares the same relationship, so the tuple matches
  and they merge to one edge with `sources: [yaml, connect]`.
- Cost: two writers disagreeing about the relation between one ordered pair
  render **two parallel edges**. Taken deliberately — the same failure direction
  as ADR-0044 and ADR-0048, and the alternative silently discards a plugin's
  assertion in exactly the situation where a human most needs to see the
  disagreement.
- **Endpoint resolution and node uniqueness must use the same folding.** An edge
  naming `Payments-API` has to reach the node keyed `payments-api`, or ADR-0048
  materializes a second stub differing only in case — which ADR-0020's
  `lower(trim(key))` uniqueness exists to prevent.
