# ADR-0033: The `kubernetes` plugin emits no edges

- **Status**: Accepted
- **Date**: 2026-09-02
- **Ticket**: [Kubernetes discovery scope and annotation convention](https://github.com/fredskor/nodqora/issues/10)

## Context

Product plan §11.2 offers seven relationship-inference sources: selectors,
labels, environment variables, annotations, service names, OpenTelemetry and
application metadata.

Counting the fixture's nine production edges against the plugins that can
produce them settles the question before any of those are evaluated:

- **four from `connect`** — both `SOURCES_FROM` edges from the connector
  `topics` config, both `WRITES_TO` edges from `connection.url` and
  `iceberg.tables`;
- **five from YAML** — `stripe-webhooks→payments-api`,
  `payments-api→raw.v1`, `payments-enricher→raw.v1`,
  `payments-enricher→enriched.v1`, `trino-analytics→analytics.payments_events`.

> **Amended by [ADR-0041](0041-connect-infers-edges-from-the-topics-key-alone.md).**
> This count is wrong: `payments-events-v1` does not appear in
> `payments-es-sink`'s config, and `connection.url` is the Elasticsearch cluster
> endpoint, not the index. The correct split is **two from `connect`** and
> **seven from YAML**. Kubernetes still contributes zero, so the decision below
> is unaffected — only its supporting arithmetic.

**Kubernetes contributes zero.**

## Decision

**The `kubernetes` plugin returns an empty `edges[]`.** Selector matching is
used only to attach Service and Ingress backings (ADR-0030).

Each §11.2 source is declined for its own reason:

| source | why not |
|---|---|
| selectors | Service and Deployment are backings of the **same node** (ADR-0005), so a selector match is a backing attachment, not an edge |
| labels, service names | would produce *guessed* service→service edges; Kubernetes has no call graph |
| environment variables | scraping container env is where secrets live (ADR-0014), and the API returns node data wholesale on an unauthenticated service |
| OpenTelemetry | already out of scope on the map |
| application metadata | no MVP referent |

## Consequences

- **Guessing is structurally unavailable, not merely unwise.** ADR-0009 dropped
  `Edge.confidence` on the grounds that every MVP edge is asserted, so there is
  nowhere to record that an edge is a guess. Label and service-name inference
  would have to assert their guesses as facts.
- **The interesting casualty is `payments-enricher CONSUMES_FROM
  payments.events.raw.v1`.** It looks discoverable — `kubernetes` knows
  *node → consumer group* from `topology.io/consumer-groups`, and `kafka` knows
  *group → topic* from `describeConsumerGroups` — but **neither plugin knows
  both halves**, and ADR-0022 forbids `kafka` from attributing anything. It is a
  cross-plugin join. YAML declares the edge; the annotation does health routing
  only.
- The rejected alternative was `topology.io/consumes` / `topology.io/produces`,
  letting `kubernetes` emit a **dangling** edge to a topic key it does not own —
  explicitly legal under ADR-0012 and symmetric with what `connect` already
  does. It is deferred rather than refused: #14 has not decided the YAML edge
  shape, and pinning an annotation edge syntax first would pre-empt it. Adding
  the keys later is a pure addition to ADR-0032's closed vocabulary, with no
  migration.
- **The cost is stated, not hidden**: a team that adds a new consumer service
  gets health routing free from the annotation, but **no edge on the canvas
  until someone edits YAML**.
- Selector matching survives as the one load-bearing Kubernetes inference, and
  it is what gives `payments-api` its three backings.
