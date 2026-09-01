# ADR-0002: Edges are stored flow-directed; relation orientation governs phrasing only

- **Status**: Accepted
- **Date**: 2026-09-01
- **Ticket**: [Core graph domain model](https://github.com/fredskor/nodqora/issues/3)

## Context

Three of the reference pipeline's nine edges read backwards against data flow,
because their §8.3 verb takes the **consumer** as its grammatical subject:

```text
payments-enricher  CONSUMES_FROM  payments.events.raw.v1
payments-es-sink   SOURCES_FROM   payments.events.enriched.v1
trino-analytics    QUERIES        analytics.payments_events
```

Data flows the other way in all three. Product plan §16 (Show Upstream / Show
Downstream / Find Path) needs one unambiguous traversal direction, and the
reference pipeline document explicitly defers reconciling this to domain-model
work.

Storing edges grammatically would force every traversal, layout pass and path
query to consult per-relation orientation, where a single plugin registering a
relation without an orientation flag silently inverts blast direction.

## Decision

**`Edge.from` → `Edge.to` is always the direction data flows.** Reversed
relations are flipped at ingest.

The relation string is preserved as authored, and its `RelationDescriptor`
carries `orientation` plus phrasing for both reading directions, so the
inspector still reads naturally.

MVP relation vocabulary — the six §8.3 verbs the reference pipeline needs:

| relation | orientation | forward phrasing | reverse phrasing |
|---|---|---|---|
| `CALLS` | forward | calls | is called by |
| `PRODUCES_TO` | forward | produces to | is produced by |
| `CONSUMES_FROM` | reversed | delivers to | consumes from |
| `SOURCES_FROM` | reversed | feeds | sources from |
| `WRITES_TO` | forward | writes to | is written by |
| `QUERIES` | reversed | is queried by | queries |

The other thirteen §8.3 verbs are not used in the MVP. `RUNS_ON` and `DEPLOYS`
are specifically unnecessary because physical objects are not nodes (ADR-0005).

## Consequences

- `downstream(n)` is *always* outgoing edges; `upstream(n)` is *always*
  incoming. No orientation lookup in any traversal, layout or path query.
- A plugin that registers a relation with a wrong orientation produces awkward
  wording — a visible, harmless bug — rather than an inverted graph.
- Stored `from`/`to` no longer match how the relation reads aloud, so anything
  displaying an edge must go through the descriptor's phrasing. Raw
  `from RELATION to` is wrong for a third of the fixture's edges.
- The YAML format may still let authors write edges consumer-first; flipping is
  an ingest concern. That is the YAML topology ticket's business, not this one.
