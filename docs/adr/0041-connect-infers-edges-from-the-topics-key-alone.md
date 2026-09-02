# ADR-0041: `connect` infers edges from the `topics` key alone and emits no destination edges

- **Status**: Accepted
- **Date**: 2026-09-02
- **Ticket**: [Kafka and Kafka Connect discovery scope](https://github.com/fredskor/nodqora/issues/11)

## Context

Product plan §11.4 says "relationships can often be inferred directly from
connector configuration" and illustrates it with
`Kafka Topic → Elasticsearch Sink Connector → Elasticsearch Index`. Research #5
established that Connect's two routes to edges fail in opposite directions:
`GET /connectors/{c}/topics` is runtime-observed (empty for a connector that has
processed nothing, stale after reconfiguration until reset, disableable
cluster-wide by `topic.tracking.enable=false`), while config parsing is static
(misses `topics.regex` and dynamic routing, and needs per-connector-class
knowledge of which keys name the destination).

**The fixture settles it, and it contradicts the plan's own example.**
`payments-es-sink`'s config is:

```properties
topics         = payments.events.enriched.v1
connection.url = https://es-prod.internal:9200
type.name      = _doc
```

`payments-events-v1` **does not appear in it.** `connection.url` is the cluster
endpoint, not the index; the Elasticsearch sink derives its index from the
*topic name*. A plugin inferring the destination per-class would therefore emit
an edge to `payments.events.enriched.v1` — which under ADR-0020's flat,
type-agnostic key space **is the topic's own key**. The result is a self-loop
and a merge collapsing an Elasticsearch index and a Kafka topic into one node.
The transform that would yield the right answer,
`payments.events.enriched.v1` → `payments-events-v1`, is precisely the fuzzy
tier ADR-0021 banned.

`iceberg.tables = analytics.payments_events` is the opposite case: the node key,
stated verbatim.

## Decision

**`connect` parses the `topics` key and nothing else. It emits no destination
edges at all.**

The line: **`topics` is Connect's own configuration vocabulary;
`iceberg.tables` and `connection.url` are third-party class vocabulary.**
Reading Connect's own surface needs no registry, no versioning against connector
releases, and no per-class knowledge — the same instinct as §5.2's
technology-agnostic boundary, one level down.

Rejected alternatives:

| option | why not |
|---|---|
| a per-class destination registry | open-ended third-party vocabulary, and it produces destination edges for *some* classes and silently none for others, so the user cannot distinguish "unsupported class" from "misconfigured connector". It also buys only one of the fixture's two destination edges, since the Elasticsearch one is unobtainable either way |
| add `GET /connectors/{c}/topics` | two sources for one edge is #12's problem, and **ADR-0009 dropped `Edge.confidence`**, so there is nowhere to record that one route is observed and the other declared. ADR-0033 already called guessing "structurally unavailable" for this reason |

This is ADR-0033's precedent applied unchanged: an edge that *looks* discoverable
but whose mechanism is unreliable goes to YAML, with the cost stated.

## Consequences

- **ADR-0033's arithmetic is amended.** It counted "four from `connect`,
  five from YAML", crediting `connection.url` with the Elasticsearch
  destination. The correct count is **two from `connect`** (both
  `SOURCES_FROM`) and **seven from YAML**. Kubernetes still emits zero, so
  ADR-0033's *decision* stands; only its supporting count changes.
- **[YAML topology format](https://github.com/fredskor/nodqora/issues/14)
  inherits two more edges** than ADR-0033 assumed — both connector→destination
  edges — so seven of the fixture's nine production edges are YAML's to express.
- **§11.4's own worked example is the case that does not work.** It joins §13's
  Elasticsearch example and producer staleness on the list of plan examples with
  no MVP referent; the Elasticsearch examples are now nought for two.
- **`topics.regex` yields no edges**, stated as a gap. It is the one thing
  `/topics` would have covered, and evaluating the regex ourselves would need
  the topic list from a different plugin, which ADR-0012 forbids.
- A `PAUSED` connector that has never run still contributes its edges, because
  config parsing does not depend on runtime observation. That is the fixture's
  incident scenario working by construction.
