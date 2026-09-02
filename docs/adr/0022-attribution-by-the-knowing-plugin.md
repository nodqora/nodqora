# ADR-0022: A physical object is attributed by the plugin that knows the node, not the one that owns the technology

- **Status**: Accepted
- **Date**: 2026-09-02
- **Ticket**: [Identity-resolution rules for the MVP](https://github.com/fredskor/nodqora/issues/8)

## Context

ADR-0013 made backings the routing table for health, which promoted attribution
from a metadata nicety to a load-bearing mechanism: the mapping
`enrich-consumer-prod → payments-enricher` is *how the fixture's `lag 40000`
reaches the enricher at all*. It handed the rules here.

Two facts constrain the answer. The plugin that can **read** a signal is
routinely not the plugin that can **attribute** it — `kafka` is the only plugin
that can read consumer lag and the only one that cannot say whose lag it is.
And research #5 established that the Connect REST API never names the workload
it runs on, so the `kafka-connect` StatefulSet backing two connector nodes is a
fact **nobody discovers**; a human declares it.

ADR-0013 already licensed the inversion by redefining `Backing.plugin` as the
object's *technology domain* rather than its discoverer.

## Decision

**Whichever plugin knows the node key emits the backing, whatever technology the
object belongs to.**

- **Workload → connectors.** The `connect` plugin's per-environment config names
  the workload its cluster runs on, and it stamps that backing onto **every**
  connector it discovers:

  ```text
  connect config, environment production
    workload: { plugin: kubernetes, kind: statefulset,
                reference: payments-prod/kafka-connect }
  ```

- **Consumer group → node.** Two declaration routes, both emitting the same
  backing shape and unioning on `(plugin, kind, reference)`:

  1. an annotation on the Kubernetes workload, so `kubernetes` emits
     `{ plugin: kafka, kind: consumer-group, reference: enrich-consumer-prod }`
     on `payments-enricher`;
  2. the YAML topology, for a consumer that has no workload to annotate.

  Connect-generated groups need neither — `connect` emits its own, from its
  config, which is authoritative including when `consumer.override.group.id`
  overrides the default.

**The `kafka` plugin attributes nothing.** It reads lag for the groups it is
routed to and never decides ownership.

## Consequences

- Against the fixture, all three group naming conventions resolve without a
  shared rule, which is what "group attribution cannot rely on a single rule"
  demanded:

  | group | attributed by |
  |---|---|
  | `enrich-consumer-prod` | annotation on `enricher-v2`, or YAML |
  | `connect-payments-es-sink` | `connect`, from its own config |
  | `connect-payments-iceberg-sink` | `connect`, from its own config |

- **It scales past the fixture.** A twentieth connector on the same cluster gets
  its `kubernetes` backing with no edit anywhere. The rejected alternatives — a
  multi-valued `topology.io/backs` annotation on the StatefulSet, or hand-written
  backings in YAML — both require a human edit per connector per environment, in
  a repo owned by a different team from the one adding the connector.
- A `connect-*` prefix heuristic in the `kafka` plugin was rejected: it asserts
  what `connect` already states authoritatively, and it mis-attributes silently
  whenever `consumer.override.group.id` is set — plausible numbers on the wrong
  node, with no error.
- Annotation-only attribution was rejected because a Kafka consumer that is not a
  Kubernetes workload — a Flink job, a VM — would have no route at all. Its lag
  would reach no node while the node sat at `UNKNOWN`.
- Both routes writing the same backing is a **union, not a conflict**: `backings`
  is a collection with element identity `(plugin, kind, reference)`, which is
  research #4's option F applied where it is uncontroversial.
- One object backing many nodes was already legal (ADR-0005) and is now used on
  purpose, so it is not a contested claim. The only contest ADR-0021 has to
  reduce is the other direction — many objects claiming one key.
- A plugin can only observe a node someone recorded a backing for, so a group
  discovered before its owning node is known goes unobserved for one topology
  cycle, per ADR-0013. Self-healing.
- Whether an **unattributed** consumer group becomes a node of its own is
  [#11](https://github.com/fredskor/nodqora/issues/11)'s.
