# ADR-0062: YAML edges are verb keys under the acting node, and the six verbs are closed

- **Status**: Accepted
- **Date**: 2026-09-02
- **Ticket**: [YAML topology format](https://github.com/fredskor/nodqora/issues/14)

## Context

ADR-0041 left `yaml` owning **seven of the fixture's nine** production edges, so
the edge syntax is the format's most-used surface, not an afterthought.

ADR-0002 stores every edge **flow-directed** and gives three of the six MVP
relations `REVERSED` orientation: `CONSUMES_FROM`, `SOURCES_FROM` and `QUERIES`
read *against* the flow. The stored edge for "the enricher consumes from raw" is
therefore `payments.events.raw.v1 → payments-enricher`.

## Decision

**An edge is a verb key on the node that performs it, holding a list of the
other endpoint's keys. There is no `from`, no `to`, and no `edges:` list.**

```yaml
nodes:
  - key: payments-enricher
    consumesFrom: [payments.events.raw.v1]
    producesTo:   [payments.events.enriched.v1]

  - key: trino-analytics
    queries: [analytics.payments_events]
```

Six verbs, and they are **closed for `yaml`** — no escape hatch:

| verb | relation | orientation | stored edge |
|---|---|---|---|
| `calls` | `CALLS` | FORWARD | node → target |
| `producesTo` | `PRODUCES_TO` | FORWARD | node → target |
| `writesTo` | `WRITES_TO` | FORWARD | node → target |
| `consumesFrom` | `CONSUMES_FROM` | REVERSED | target → node |
| `sourcesFrom` | `SOURCES_FROM` | REVERSED | target → node |
| `queries` | `QUERIES` | REVERSED | target → node |

The subject is the enclosing node, and in all six relations the subject is the
**actor** — the thing calling, producing, consuming, writing, querying. So the
author never writes a direction, and therefore can never write one backwards.
The loader applies the orientation.

A flat `edges: [{from, to, relation}]` list was rejected: it is honest to how
the edge is *stored*, and reads backwards from how anyone says it out loud for
half the vocabulary. Getting one wrong is **silent** — the edge simply points
the wrong way on the canvas, and nothing in the model can tell an intended
reversal from a typo (ADR-0009 dropped `Edge.confidence`, so there is nowhere to
even record doubt).

**Consequently the six RelationDescriptors are core built-ins, seeded at
startup, not plugin-registered.** `relation` stays an open string in the model,
but the registry is not empty. Two reasons: with no escape-hatch verb, a
YAML-registered relation would be configuration nothing could ever emit; and
`SOURCES_FROM` is emitted by both `yaml` and `connect`, so plugin registration
would give one relation two definitions whose phrasing could disagree — making
the descriptor a node renders with depend on poll order, the exact race ADR-0049
refused for Owners.

## Consequences

- **A new relation is a code change, not a config change.** Adding `SCHEDULES`
  means adding a verb, which is deliberate: the alternative was asking the author
  for `orientation:` in an escape hatch, re-introducing the one question this
  shape removes.
- This makes `yaml`'s relation vocabulary closed while the *model's* stays open —
  the same split ADR-0032 made for annotations. **Closed vocabularies are a
  plugin property**, not a core property, in both plugins that have one.
- The three orientations are exercised by the fixture: `consumesFrom` and
  `queries` both produce edges stored against the direction they were written,
  and the prototype reproduces all seven YAML edges flow-directed with no
  per-relation branching in the author's hands.
- Edge `metadata` is unreachable from YAML. Nothing in the MVP writes it
  (ADR-0006's allow-list has no `yaml` row), so nothing is lost.
- ADR-0045's full-tuple edge identity makes a verb declared in two places free:
  the same edge from `yaml` and from `connect` is one edge by set union.
