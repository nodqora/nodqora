# ADR-0040: `kafka` emits one backing and reads only routed groups; topic lag joins at read time

- **Status**: Accepted
- **Date**: 2026-09-02
- **Ticket**: [Kafka and Kafka Connect discovery scope](https://github.com/fredskor/nodqora/issues/11)

## Context

The fixture makes one group's lag land on **two** nodes: `enrich-consumer-prod`'s
40,000 appears on `payments-enricher` (a service) and on
`payments.events.raw.v1` (a topic).

The service route is settled — ADR-0022 puts a
`{kafka, consumer-group, enrich-consumer-prod}` backing on the enricher from the
Kubernetes annotation, and ADR-0013 routes health along it. The topic route is
not, and ADR-0022's "the `kafka` plugin attributes nothing" appears to forbid it.

It does not. What ADR-0022 rules out is **inference** — `kafka` guessing that
`enrich-consumer-prod` means `payments-enricher`, rejected because
`consumer.override.group.id` makes a `connect-*` prefix heuristic mis-attribute
silently. **Group→topic is not inferred**: `listConsumerGroupOffsets` returns
committed offsets keyed by `TopicPartition`. The API states it outright. The
unavailable half was always group→*service*.

## Decision

### `kafka` emits exactly one backing

`{plugin: kafka, kind: topic, reference: <topic name>}` on the node whose key is
that topic's name — the one node whose identity it cannot get wrong, since a
topic *is* its own logical key (ADR-0012). It emits no `consumer-group` backings
anywhere; those come from the Kubernetes annotation, from YAML, or from
`connect`'s own config, exactly as ADR-0022 says.

Discovering group backings **onto topic nodes** was rejected. The set of groups
on a topic would then be discovered on the slow topology loop, so a group
appearing or vanishing would move `updatedAt`, which ADR-0003 reserves for
*architecture changed*. Research #5 makes it concrete: committed offsets are
dropped after `offsets.retention.minutes` (**default 7 days**), so a monthly
batch consumer's group disappears from the API entirely and returns — a monthly
topology edit under that model, versus a lag number that stops and starts under
this one.

### `kafka` reads exactly the routed group set

The routed set is the union of `consumer-group` backings in the environment.
**`kafka` never enumerates groups, and `listGroups` is never called.**

This is forced by cost. There is no API answering "which groups consume topic
X": the alternative is `listGroups()` (1 + N brokers) followed by committed
offsets for *every group on the cluster*, every fast poll, to serve a
prefix-scoped handful of topics — the exact inverse of ADR-0037, and
unnarrowable, since group names bear no relation to topic prefixes.

Reading only the routed set is also ADR-0022's own sentence taken literally:
"it reads lag for the groups it is routed to".

### The group→topic join falls out for free

`listConsumerGroupOffsets` returns a `Map<TopicPartition, …>`, so the same
response that yields the lag names the topics that lag is *on*. One call over a
small declared set produces both the service-node routing and the topic-node
join. Topic health then collapses per-group verdicts under ADR-0025's per-group
threshold and ADR-0024's rule.

### A topic with no routed groups yields no contribution

`UNKNOWN`, not `HEALTHY`. Under ADR-0025 lag is the only topic signal, so zero
groups is zero signal, and ADR-0024 makes `UNKNOWN` an abstention that is
discarded. Reporting `HEALTHY` from no signal is what ADR-0026 split `outcome`
from `health` to prevent.

## Consequences

- **The fixture reproduces exactly.** All three groups are routed — one by
  annotation, two by `connect`'s config — so `raw.v1` reads DEGRADED at 40,000
  and `enriched.v1` HEALTHY at max 8,400.
- **ADR-0036's gap generalises rather than widening**: an unrouted group is
  invisible on the topic as well as on the service. One gap, stated once.
- **`maxConsumerLag`** (ADR-0028) means max over the topic's routed groups on a
  topic node, and max over that node's routed groups on a service node.
- Every fast-loop call is now batched and bounded over a declared set, which is
  what makes ADR-0042's 30-second health cadence affordable.
- `listGroups` having no caller also removes one of research #5's silent-authz
  surfaces.
