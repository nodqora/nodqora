# ADR-0078: Edges store endpoint keys, not node ids

- **Status**: Accepted
- **Date**: 2026-09-03
- **Ticket**: [PostgreSQL schema and persistence](https://github.com/fredskor/nodqora/issues/16)

## Context

ADR-0048 materializes a stub node at any unresolved endpoint, so **every edge
endpoint resolves to a node row**. That makes `from_node_id` / `to_node_id`
foreign keys genuinely available, which is the shape most schemas would reach for.

## Decision

**`edge` stores `from_key` / `to_key` as text**, with generated folded columns
for ADR-0045's case-folded matching, and **no foreign keys to `node`**.

```text
edge(id, environment_key, from_key, to_key, relation,
     folded_from GENERATED, folded_to GENERATED,
     sources jsonb, metadata jsonb, discovered_at, updated_at)
  UNIQUE (environment_key, folded_from, folded_to, relation)
```

## Consequences

- **Node ids would make ADR-0050's edge diff sensitive to surrogate-id churn.**
  ADR-0050 gives a node that leaves and returns a **new id**, deliberately, because
  ADR-0043 keeps no tombstone. With ids on the edge, `payments-enricher` blipping
  out of `kubernetes`'s view for one poll and returning changes `from_node_id` on
  every edge touching it, so the diff moves `updatedAt` on all of them. By ADR-0045
  an edge's identity is the *key* tuple and nothing about those edges changed — the
  same components, the same relation. `updatedAt` would report a topology change
  that did not happen, on a row whose own definition says it did not. That is
  ADR-0050's poll-clock degeneration arriving through the one field it did not name.
- **It also removes a join from the read path.** ADR-0052 addresses nodes by key
  and the graph document ships edges as key pairs, so storing ids would mean
  resolving them back to keys on every `/graph` serialization to produce something
  the fold already had in hand.
- **Cost: nothing at the database level guarantees an endpoint resolves.** Accepted:
  that guarantee is the fold's job under ADR-0048 and the fold is the only writer.
  A foreign key would additionally forbid the write order the fold needs — stubs are
  materialized *from* edges, so edges are known before all their nodes are — making
  the fold's ordering load-bearing in exchange for enforcing an invariant it has to
  establish anyway.
