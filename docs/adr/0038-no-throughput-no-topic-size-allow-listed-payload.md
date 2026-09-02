# ADR-0038: Throughput and topic size are out; the discovered node payload is allow-listed

- **Status**: Accepted
- **Date**: 2026-09-02
- **Ticket**: [Kafka and Kafka Connect discovery scope](https://github.com/fredskor/nodqora/issues/11)

## Context

Product plan §11.3 lists throughput and topic sizes; §14 puts `12k msg/s` on the
topic node as its **headline** overlay number. §11.4 lists worker ids, error
state and last failure. Research #5 costed each and deliberately declined to
pick.

## Decision

### Throughput is out, and two accepted ADRs had already decided it

Throughput exists only as a sampled derivative of two `listOffsets` calls,
requiring a stored previous sample per partition. That collides with:

- **ADR-0012** — "plugins are stateless… a result is a full snapshot, never a
  delta. Plugins do no diffing and hold no previous state." Sampling *is*
  diffing.
- **ADR-0028** — `kafka`'s allow-listed metric keys were already fixed as
  `maxConsumerLag`, singular. Throughput was absent from that list.

The `describeLogDirs` byte-delta alternative is ruled out independently:
research #5 established byte deltas go **negative** on retention deletion,
compaction and replica movement.

### Topic size is out, and `describeLogDirs` is never called

The call is per-broker, `O(partitions on that broker)`, and — decisively — the
public API always sends `setTopics(null)`, so **it cannot be scoped**. ADR-0037
scoped topics precisely so a shared cluster does not flood the graph; topic size
is the one call where that scope cannot be pushed down. A Nodqora watching two
topics would issue the single most expensive call in either API against the
whole cluster, every slow poll.

### The payload each node carries, allow-listed by name

**Topic** — `key` is the topic name, `type` is the constant `kafka-topic`:

| metadata key | source |
|---|---|
| `partitions` | `describeTopics` |
| `replicationFactor` | `describeTopics` |
| `retentionMs` | `describeConfigs` |
| `cleanupPolicy` | `describeConfigs` — compacted vs deleted is a *topological* distinction; a compacted topic is a table, not a stream |

**Connector** — `key` is the connector name, `type` is the single constant
`connect-connector` for both source and sink:

| metadata key | source |
|---|---|
| `class` | `connector.class` |
| `type` | `source` \| `sink`, from `expand=info` |
| `tasksMax` | `tasks.max` |

`displayName` and `ownerKey` are emitted **`null`** by both plugins, following
ADR-0034: not to resolve a merge conflict with YAML but to avoid manufacturing
one. For a topic the display name is byte-identical to the key, and neither
technology has anywhere to record an owner.

Source-vs-sink stays metadata rather than becoming two types: direction is
already carried by the edges, and ADR-0001 says the core never branches on type,
so splitting would buy two descriptors and no behaviour.

ADR-0014 illustrated allow-listing with "`connector.class`, `topics`,
`tasks.max` and friends". `topics` is dropped here — it becomes edges
(ADR-0041), and storing it twice creates two places to disagree.

### Two §11.4 items have no MVP referent

- **Worker ids.** A `host:port` per task: a physical fact about a pod, which
  ADR-0005 models as backings rather than as data on a node, churning on every
  rebalance — on a node whose `updatedAt` means *architecture changed*.
- **"Last failure".** Research #5 verified there is **no failure timestamp
  anywhere in the Connect API**. ADR-0028 already routed the error state to
  `rawSignal` as the trace's first line, bounded. Last-failure is not descoped;
  there is nothing behind it to descope.

## Consequences

- **§14's topic overlay loses its headline number.** `partitions` and
  `maxConsumerLag` survive, so the overlay still says something true on every
  topic node.
- **`metrics{}` on NodeState is the pre-built home for a later Prometheus
  plugin** to write throughput without reopening anything — ADR-0006's
  namespacing already accommodates a second writer.
- **`describeLogDirs` is a call the MVP never issues**, which drops
  `Describe` on Cluster from the credential requirement.
- Allow-listing a topic's four keys is not a leakage mitigation — topic configs
  hold no secrets. It is ADR-0014's habit: a plugin that copies wholesale
  because it happens to be safe today is the one that leaks when the API grows.
- `describeConfigs` becomes a required call, and research #5 verified
  **`DescribeConfigs` is a distinct ACL from `Describe`**; granting only the
  latter yields topics with empty configs and no error. ADR-0042 makes that
  detectable.
