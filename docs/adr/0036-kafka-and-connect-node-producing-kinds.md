# ADR-0036: Kafka and Connect produce exactly two node kinds — topic and connector

- **Status**: Accepted
- **Date**: 2026-09-02
- **Ticket**: [Kafka and Kafka Connect discovery scope](https://github.com/fredskor/nodqora/issues/11)

## Context

Product plan §11.3 lists brokers, topics and consumer groups as things to
discover; §11.4 lists clusters, connectors and workers. The reference pipeline's
inventory has exactly two Kafka nodes and two Connect nodes, all four of them
topics and connectors — but a fixture is one pipeline, not a scope rule, and
ADR-0030 set the precedent of naming the node-producing kinds explicitly and
justifying each exclusion.

ADR-0022 sharpens the consumer-group case specifically: because `kafka`
attributes nothing, a group reaches a node only via a routed backing. An
*unattributed* group has no route to any node, and its lag is invisible unless
the group is itself a node.

## Decision

**`kafka` emits `topic` nodes. `connect` emits `connect-connector` nodes.
Nothing else in either technology becomes a node.**

| candidate | why not |
|---|---|
| broker | physical; ADR-0005 models physical objects as backings, and a broker node would sit disconnected from every pipeline |
| Kafka cluster | already an Environment property (ADR-0004, `bootstrap:` in ADR-0014's config); a node duplicates the environment |
| Connect cluster / worker | ADR-0022 already models the cluster as a *workload backing* on every connector, and ADR-0031 suppresses the `kafka-connect` StatefulSet from node emission by exact name |
| consumer group | see below |

**Consumer groups are backings, never nodes.** A group is not a logical
component, it is *how* a component consumes — the fixture's
`enrich-consumer-prod` and `payments-enricher` are one thing, and promoting the
group would put both on the canvas, which is the duplicate ADR-0020's flat key
space exists to prevent.

Promoting only *unattributed* groups was rejected as structurally unavailable,
not merely undesirable: attribution is another plugin's output, and ADR-0012
makes plugins stateless and independently invoked, so `kafka` cannot know at
emit time whether anyone routed a group.

## Consequences

- **Lag on an unrouted consumer group is invisible in the MVP.** This is the
  price of ADR-0022 and it is stated rather than mitigated. ADR-0040 generalises
  it: such a group is invisible on the topic as well as on the service.
- A group-node would also have failed in the direction ADR-0031 ruled against:
  it appears as a phantom node with a machine-generated name and no edges, and
  the fix — annotating it — *removes* a node.
- The fixture's inventory is now a rule rather than a coincidence: four
  discovered nodes from these two plugins, and no eleventh.
- `listGroups` has no discovery caller. ADR-0040 removes its only other caller,
  so the MVP never invokes it.
