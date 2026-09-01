# ADR-0013: Backings route health observation; `Backing.plugin` names a technology domain

- **Status**: Accepted
- **Date**: 2026-09-01
- **Ticket**: [Plugin/adapter contract for the MVP](https://github.com/fredskor/nodqora/issues/6)
- **Amends**: [ADR-0005](0005-logical-nodes-with-backings.md)

## Context

Product plan §10 sketches `Optional<NodeState> getState(Node node)` — one call
per node. Research #5 rules that out: Kafka lag arrives from `listOffsets` plus
`describeConsumerGroups` across all groups at once, Connect from one
`GET /connectors?expand=status`, Kubernetes from one list call.

The harder question is **routing** — which nodes each plugin is asked about —
and `CONTEXT.md`'s own worked example of a raw signal already answers it:

> `"3 desired / 2 ready; lag 40000"`

That is Kubernetes and Kafka observing one node, composed. Multi-observer is not
an option the model left open; it is assumed.

The reference pipeline then adds: "Three groups, three different naming
conventions — group-to-node attribution cannot rely on a single rule." The
`enrich-consumer-prod` → `payments-enricher` mapping comes from an annotation or
the YAML; `connect-payments-es-sink` → `payments-es-sink` is known to the
`connect` plugin. In **neither** case does the `kafka` plugin know it — yet
Kafka is the only plugin that can read the lag.

## Decision

The Health capability is **batched**:

```text
Map<NodeKey, StateContribution> observe(Collection<Node> nodes, EnvironmentConfig config)
StateContribution = { health, rawSignal, metrics{} }
```

A **contribution**, not a `NodeState`.

**Backings are the routing table.** A plugin is asked about exactly the nodes
carrying at least one `Backing` whose plugin is its own.

**`Backing.adapter` is renamed `Backing.plugin`, and it names the plugin whose
technology domain the object belongs to — not the plugin that discovered it.**
A consumer group is a Kafka object regardless of who told us about it. So the
`connect` plugin emits `{ plugin: kafka, kind: consumer-group, reference:
connect-payments-es-sink }`, and the `yaml` plugin emits `{ plugin: kafka, kind:
consumer-group, reference: enrich-consumer-prod }`. Both route Kafka to observe.

Discovery provenance is unaffected — that is the node's `sources[]` (ADR-0008),
which was always the provenance mechanism and which `adapter` never was.

The state engine composes the single `NodeState` row from the contributions:

- **`metrics`** — namespaced union. Zero conflict by construction; ADR-0006's
  plugin-keyed map exists for exactly this.
- **`rawSignal`** — contributions joined in plugin order, which is literally how
  `CONTEXT.md`'s example reads.
- **`health`** — the one genuine conflict, and **#9's** to resolve. The obvious
  default is worst-wins.

## Consequences

- Against the fixture:

  | node | backings | observed by |
  |---|---|---|
  | `payments-api` | Deployment + Service + Ingress | `kubernetes` |
  | `payments-enricher` | Deployment `enricher-v2`, group `enrich-consumer-prod` | `kubernetes` **+** `kafka` |
  | `payments-es-sink` | connector, group `connect-payments-es-sink`, StatefulSet | `connect` **+** `kafka` **+** `kubernetes` |
  | `payments-events-v1` | — | **nobody** |

- `CONTEXT.md`'s "a node with `backings: []` is an ordinary node whose health is
  `UNKNOWN`" stops being a rule anyone writes and becomes arithmetic. Four of the
  fixture's ten nodes go `UNKNOWN` because no plugin is ever asked.
- `kafka-connect` is a `kubernetes` backing on **both** connector nodes, so
  Kubernetes contributes workload readiness to both alongside Connect's task
  counts. If the Connect workload is down, both connectors are in trouble.
- A plugin can only report on a node someone recorded a backing for, so a
  consumer group discovered before its owning node is known goes unobserved for
  one topology cycle. Self-healing.
- **Consumer-group-to-node attribution is now load-bearing** — it is the health
  routing table, not a metadata nicety. Handed to #8.
- The rejected alternative was a claim registry where plugins declare which
  backing *kinds* they observe. Semantically tidier, but a whole registration
  mechanism plus a duplicate-claim failure mode, to express what one field can
  say truthfully.
- ADR-0005's `{ adapter, kind, reference }` is superseded by
  `{ plugin, kind, reference }`. Nothing is built, so there is no migration.
