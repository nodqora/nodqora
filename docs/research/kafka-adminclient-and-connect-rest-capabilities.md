# Kafka AdminClient and Kafka Connect REST — Capabilities Without Prometheus

Research input for [Kafka and Kafka Connect discovery scope](https://github.com/fredskor/nodqora/issues/11)
and [Health normalization model](https://github.com/fredskor/nodqora/issues/9).
This document reports what the two APIs actually expose. It does not recommend
anything; the **Implications** section lists options and their costs and stops
there.

The question is deliberately narrow: **what is obtainable with no Prometheus, no
JMX scraping, and no vendor API** — no Confluent Metrics API, no Strimzi CRDs, no
Cruise Control. The product plan (§11.3) lists Prometheus and JMX as *possible*
implementations; this establishes what the floor looks like without them, so the
discovery-scope decision knows what it is trading away.

**Version pin.** AdminClient claims were read from the `4.1.0` release tag of
`apache/kafka` and from `kafka.apache.org/40/javadoc`, which self-identifies as
Kafka 4.0.2 API. Connect claims were read from the 4.0 Connect runtime source and
the generated Connect REST OpenAPI specifications for 3.9, 4.0 and 4.3.
Behaviour is substantially the same back to 3.7 except where noted.

All load-bearing claims are pinned to a fetched primary source — Apache javadoc,
the `apache/kafka` repository at a release tag, Kafka documentation source, or a
KIP wiki page. Anything that could not be checked against one says **not
verified** rather than guessing. Vendor documentation is used only for
Confluent-proprietary connector config keys and is flagged **(vendor doc)**.

---

## Summary

Findings that bear on Nodqora's decisions, stated as findings.

**Everything §11.3 asks for except throughput is obtainable from AdminClient
alone.** Brokers, topics, partitions, replication, configs, retention, consumer
groups, offsets, lag and topic sizes all have a first-class API. Throughput is
the single exception, and it is a real one: it exists only as a sampled
derivative of two `listOffsets` calls, giving cluster-wide messages/sec per
partition with no per-producer or per-consumer attribution. The equivalent
Connect finding is starker — the Connect REST API exposes **no** throughput
metric of any kind; `sink-record-send-rate` and friends are JMX MBeans only.

**The top-level Connect connector state lies, and this is verified in source
rather than inferred.** `AbstractHerder.connectorStatus()` assembles the
connector's state and its tasks' states from independent status records with no
aggregation between them. A connector whose every task has failed still reports
`RUNNING`. The reference pipeline's `payments-iceberg-sink` (RUNNING with 1 of 3
tasks FAILED → DEGRADED) is not a contrived fixture; it is the normal shape, and
health normalization must read `tasks[]`. The observable state vocabulary is
`UNASSIGNED, RUNNING, PAUSED, FAILED, RESTARTING, STOPPED` for connectors and
the same minus `STOPPED` for tasks — `DESTROYED` exists in the enum but the
source comment says it is never visible to users.

**`listOffsets(latest)` returns the high watermark, not the log-end offset.**
`Partition.scala` shows the true-LEO branch is unreachable from AdminClient
because `ListOffsetsOptions` always carries an isolation level. Lag computed as
`latest − committed` is therefore HW-based — which is exactly what
`kafka-consumer-groups.sh --describe` reports in its `LOG-END-OFFSET` column, so
Nodqora's numbers will match the tool operators already trust. This matters more
for matching expectations than for accuracy.

**Neither API can tell you who produces to a topic.** `describeProducers`
returns a numeric producer id — liveness, not identity, with no client id and no
transactional id — and it is the only call in the survey requiring `Read` on
Topic, out of reach for a Describe-only principal. `listTransactions` names only
*transactional* producers. For a plain producer there is nothing. Producer→topic
edges must come from Connect connector configs, from OTel, or from the YAML
topology; this is the structural gap that makes the mixed declared/discovered
graph (ADR-0005, and the fixture's four declared-only nodes) load-bearing rather
than a convenience.

**Connect's two routes to topic→connector edges fail in opposite directions.**
`GET /connectors/{c}/topics` is *runtime-observed* — a configured connector that
has processed nothing returns an empty list, and a reconfigured connector keeps
reporting its old topic until an explicit reset. Parsing connector config is
*static* — it misses `topics.regex`, and for a sink with
`iceberg.tables.dynamic-enabled` the destination is chosen per-record at runtime
and is genuinely not in the config at all. Neither is complete alone.

**A read-only Kafka principal is sufficient; a read-only Connect credential does
not exist.** Every AdminClient call Nodqora needs is satisfied by `Describe` /
`DescribeConfigs` ACLs, and `describeLogDirs` needs only `Describe` on Cluster —
nothing is admin-only. Connect is the opposite: the Apache security
documentation states the REST API is unauthenticated by default, and that "once
a caller is allowed onto the REST API it can act on *any* connector". There is
no read-only mode and no per-endpoint authorization. Whatever credential Nodqora
holds can also delete every connector; the only mitigation is an operator-side
reverse proxy restricted to `GET`.

**Reading connector configs means reading credentials.** `GET
/connectors/{c}/config` performs no masking of any kind — grepping the 4.0
`AbstractHerder`, `ConnectorsResource` and `ConnectorInfo` for `PASSWORD`, `mask`
or `HIDDEN` returns nothing, because `ConfigDef` typing is never applied on that
path. Externalized `${file:...}` secrets do come back as placeholders
(`reverseTransform` writes variable references back over resolved values), but a
literally-inlined password comes back in plaintext. Since config parsing is one
of the two routes to edge inference, Nodqora cannot avoid fetching these.

**Authorization failures are silent, and that is a graph-deletion hazard.**
`listTopics`, `listGroups`, `listTransactions` and `describeLogDirs` all return
empty or filtered results on an authz failure rather than raising. A discovery
loop that prunes nodes absent from a poll will delete the graph the moment an
operator tightens ACLs. This is a direct constraint on
[Discovery-engine merge semantics](https://github.com/fredskor/nodqora/issues/12),
not just on this ticket.

**Topic→consumer-group edges expire.** Committed offsets survive a group going
empty by `offsets.retention.minutes`, default 7 days, after which the group and
its edges vanish from the API entirely. A batch consumer that runs monthly is
indistinguishable from one that was decommissioned.

**Polling cost is dominated by one call.** `describeConfigs` batches every topic
into a single request; `listOffsets` batches per leader; `describeConsumerGroups`
hits one coordinator. `describeLogDirs` is one request per broker whose response
is O(partitions on that broker), and the public API offers no way to scope it to
a topic subset — it always sends `setTopics(null)`. On the Connect side,
`GET /connectors?expand=status&expand=info` collapses what would be 1+2N requests
into exactly one, resolved in-process on the receiving worker. Topic size is the
only genuinely expensive thing either API asks of you.

---

## Part 1 — Kafka AdminClient

### Capability matrix

| Capability | Without Prometheus/JMX? | Exact API | Notes |
|---|---|---|---|
| Broker list, id, host, port, rack | **Yes** | `Admin.describeCluster().nodes()` → `KafkaFuture<Collection<Node>>` | `DescribeClusterRequest` (API 60), falls back to `Metadata`. Needs **no ACL at all** in 4.1.0 source (see Permissions). |
| Cluster id | **Yes** | `describeCluster().clusterId()` → `KafkaFuture<String>` | Non-null for broker ≥ 0.10.1.0. |
| Controller | **Yes** | `describeCluster().controller()` → `KafkaFuture<Node>` | May be `null` if controller id unknown; broker returns `-1` if the controller is not in the returned node list. |
| Fenced brokers | **Yes** | `DescribeClusterOptions.includeFencedBrokers(true)` | Cannot be combined with bootstrap-controller mode. |
| Broker **software version** | **No** | — | No API exposes it. `describeFeatures()` gives cluster-wide *feature levels*, not per-broker versions. See §1. |
| Topic list | **Yes** | `listTopics(ListTopicsOptions)` → `ListTopicsResult.namings()/listings()` | One `Metadata(allTopics)` request. Internal topics filtered **client-side**; default `listInternal = false`. |
| Partitions, leader, replicas, ISR, ELR | **Yes** | `describeTopics(TopicCollection)` → `TopicDescription.partitions()` → `List<TopicPartitionInfo>` | `leader()` is `null` when there is no leader. |
| Topic id (`Uuid`) | **Yes** | `TopicDescription.topicId()`, `TopicListing.topicId()` | Also `describeTopics(TopicCollection.ofTopicIds(...))`, broker ≥ 3.1.0. |
| Topic configs (retention, cleanup.policy, min.isr, …) | **Yes** | `describeConfigs(List.of(new ConfigResource(TOPIC, name)))` | Returns *effective* values with a `source()` telling you where each came from. |
| "Is this retention explicitly set on the topic?" | **Yes** | `ConfigEntry.source() == DYNAMIC_TOPIC_CONFIG` | Verified against the broker-side computation in `ConfigHelper.createTopicConfigEntry`. See §3. |
| Topic size on disk | **Yes** | `describeLogDirs(brokerIds)` → `LogDirDescription.replicaInfos()` → `ReplicaInfo.size()` | Per-**replica** bytes on one broker. Aggregation is yours to do. Expensive; see §4 and Polling cost. |
| Disk total/usable per log dir | **Yes** | `LogDirDescription.totalBytes()`, `usableBytes()` → `OptionalLong` | KIP-827, DescribeLogDirs v4. |
| Log start / end offsets | **Yes** | `listOffsets(Map<TopicPartition, OffsetSpec>)` | `latest()` returns the **high watermark**, not the LEO. See §4/§6. |
| Approximate message count | **Partly** | `latest − earliest` per partition | Meaningless on compacted topics; an upper bound with retention. See §4. |
| Consumer group list | **Yes** | `listGroups(ListGroupsOptions)` (4.1+); `listConsumerGroups()` deprecated **since 4.1** | Fans out to **every broker**. |
| Group state, members, assignment, coordinator | **Yes** | `describeConsumerGroups(Collection<String>)` → `ConsumerGroupDescription` | `state()` deprecated since 4.0 → `groupState()`. |
| Member client id / host / instance id | **Yes** | `MemberDescription.clientId()`, `.host()`, `.groupInstanceId()` | The single best "who is consuming" signal available. |
| Empty group with committed offsets | **Yes** | `describeConsumerGroups` (state `EMPTY`) + `listConsumerGroupOffsets` | Committed offsets survive until `offsets.retention.minutes` (default 7 days). |
| Committed offsets | **Yes** | `listConsumerGroupOffsets(Map<String, ListConsumerGroupOffsetsSpec>)` | `null` map value = no committed offset for that partition. |
| Consumer lag | **Yes** (derived) | `listOffsets(latest)` − `listConsumerGroupOffsets` | Exactly what `kafka-consumer-groups.sh --describe` does; verified in source. |
| **Throughput (msgs/sec, bytes/sec)** | **Only by sampling** | two `listOffsets(latest)` calls / Δt; two `describeLogDirs` calls / Δt | Coarse, non-monotonic, no per-client attribution. See §7. |
| Per-producer / per-consumer rates | **No** | — | JMX/Prometheus only. |
| Request rates, p95/p99 latency, queue times | **No** | — | JMX only (`kafka.network:type=RequestMetrics,…`). |
| Producer identity on a partition | **Partly** | `describeProducers(Collection<TopicPartition>)` → `ProducerState` | Gives `producerId` (a numeric PID) only — **no client id, no transactional id**. See §8. |
| Transactional ids | **Yes** | `listTransactions()` → `TransactionListing.transactionalId()` | Only covers *transactional* producers. Requires `Describe` on `TransactionalId`. |
| Topic→client edges for plain producers | **No** | — | The single biggest topology gap; nothing in AdminClient names a non-transactional producer. |

---

### 1. Brokers / cluster

#### API

```java
DescribeClusterResult describeCluster();
DescribeClusterResult describeCluster(DescribeClusterOptions options);
```

`DescribeClusterResult` (Kafka 4.0.2 javadoc):

| Method | Return type | Javadoc |
|---|---|---|
| `nodes()` | `KafkaFuture<Collection<Node>>` | "a future which yields a collection of nodes" |
| `controller()` | `KafkaFuture<Node>` | "this may yield null, if the controller ID is not yet known" |
| `clusterId()` | `KafkaFuture<String>` | "non-null for broker version 0.10.1.0 or higher, and null otherwise" |
| `authorizedOperations()` | `KafkaFuture<Set<AclOperation>>` | "non-null if the broker supplies this information, and null otherwise" |

`Node` carries id, host, port, rack (and `isFenced` via `DescribeClusterOptions.includeFencedBrokers(true)`).

#### Which RPC

`KafkaAdminClient.describeCluster` (4.1.0, `KafkaAdminClient.java:2457`) issues a
**`DescribeClusterRequest`** (API key 60) against
`LeastLoadedBrokerOrActiveKController`, and falls back to a `MetadataRequest`
with an empty topic list if the broker does not support it. So it is **one
request to one broker** — not a fan-out.

`DescribeClusterOptions.includeFencedBrokers(true)` throws
`IllegalArgumentException` if the client is using bootstrap controllers
(`KafkaAdminClient.java:2472-2474`).

#### Broker version — **not obtainable**

There is **no** AdminClient method that returns a broker's software version.
`grep -i apiversion` over `clients/src/main/java/org/apache/kafka/clients/admin/Admin.java`
at 4.1.0 returns nothing: the `ApiVersions` handshake result is internal
(`NodeApiVersions`) and not exposed on the public `Admin` interface. The Kafka
security docs state ApiVersions "is part of the Kafka protocol handshake and
happens on connection and before any authentication", i.e. it is not a
user-callable, authorized API.

The nearest available signal is:

```java
DescribeFeaturesResult describeFeatures();          // → KafkaFuture<FeatureMetadata>
```

`FeatureMetadata` (4.0.2 javadoc):

- `finalizedFeatures()` — "a map of finalized feature versions. Each entry … a
  key being a feature name and the value being a range of version levels
  supported by every broker in the cluster."
- `supportedFeatures()` — "…the value being a range of versions supported by a
  particular broker in the cluster."
- `finalizedFeaturesEpoch()` — empty if finalized features are unavailable.

The `metadata.version` finalized feature level maps to a Kafka release via the
`MetadataVersion` enum
(`server-common/src/main/java/org/apache/kafka/server/common/MetadataVersion.java`,
4.1.0):

```
IBP_3_7_IV0(15, "3.7", "IV0", true)   … IBP_3_7_IV4(19, "3.7", "IV4", false)
IBP_4_0_IV0(22, "4.0", "IV0", false)  … IBP_4_0_IV3(25, "4.0", "IV3", false)
IBP_4_1_IV0(26, "4.1", "IV0", false)     IBP_4_1_IV1(27, "4.1", "IV1", false)
MINIMUM_VERSION   = IBP_3_3_IV3
LATEST_PRODUCTION = IBP_4_1_IV1
```

**Caveat that matters for a topology graph:** `metadata.version` is a
*cluster-wide, operator-chosen* value, not a version report. A cluster of
brokers all running 4.1 binaries can sit at `metadata.version` 17 (3.7-IV2)
indefinitely. So this is a **lower bound on the cluster's feature set**, not the
broker software version. Do not label a node "Kafka 3.7" from it.

---

### 2. Topics

#### Listing

```java
ListTopicsResult listTopics();
ListTopicsResult listTopics(ListTopicsOptions options);
```

`KafkaAdminClient.listTopics` (4.1.0, `:2102`) issues a single
`MetadataRequest.Builder.allTopics()` to the least-loaded node, then filters:

```java
if (!topicMetadata.isInternal() || options.shouldListInternal())
    topicListing.put(topicName, new TopicListing(topicName, topicMetadata.topicId(), isInternal));
```

So **internal-topic filtering is entirely client-side** — the broker sends
`__consumer_offsets`, `__transaction_state` etc. over the wire regardless.
`ListTopicsOptions.listInternal` defaults to **`false`**
(`ListTopicsOptions.java:27`: `private boolean listInternal = false;`).

#### Describing

```java
DescribeTopicsResult describeTopics(Collection<String> topicNames);
DescribeTopicsResult describeTopics(TopicCollection topics, DescribeTopicsOptions options);
```

`TopicDescription` (4.0.2 javadoc) carries exactly:

| Method | Return type |
|---|---|
| `name()` | `String` |
| `isInternal()` | `boolean` |
| `partitions()` | `List<TopicPartitionInfo>` — "index represents the partition id" |
| `authorizedOperations()` | `Set<AclOperation>` — "or null if this is not known" |
| `topicId()` | `Uuid` |

`TopicPartitionInfo` (4.0.2 javadoc), verbatim:

| Method | Return type | Javadoc |
|---|---|---|
| `partition()` | `int` | "Return the partition id." |
| `leader()` | `Node` | "Return the leader of the partition or **null if there is none**." |
| `replicas()` | `List<Node>` | "…in the same order as the replica assignment. The preferred replica is the head of the list." |
| `isr()` | `List<Node>` | "…the ordering of the result is unspecified." |
| `elr()` | `List<Node>` | eligible leader replicas (KIP-966, ELR) |
| `lastKnownElr()` | `List<Node>` | last known eligible leader replicas |

**What `TopicDescription` does NOT carry:** no configs (separate
`describeConfigs` call), no sizes, no offsets, no partition count field other
than `partitions().size()`, no replication factor field other than
`partitions().get(i).replicas().size()`.

Under-replicated detection is therefore a client-side computation:
`isr().size() < replicas().size()`, and under-min-ISR needs
`min.insync.replicas` from `describeConfigs` as well.

#### RPC used, and the 4.x change

In 4.1.0, `describeTopics(TopicNameCollection)` routes to
`handleDescribeTopicsByNamesWithDescribeTopicPartitionsApi`
(`KafkaAdminClient.java:2135`, `:2314`), which uses the **`DescribeTopicPartitions`
API (key 75)** with cursor-based pagination, not `Metadata`. It first calls
`describeCluster()` internally to build a node-id map, then issues the paginated
call. `DescribeTopicsOptions.partitionSizeLimitPerResponse` defaults to **2000**
(`DescribeTopicsOptions.java:28`). A legacy `Metadata`-based path
(`generateDescribeTopicsCallWithMetadataApi`) remains for older brokers.

`describeTopics(TopicIdCollection)` still uses `Metadata` by topic id
(`handleDescribeTopicsByIds`) and requires broker ≥ 3.1.0 per the `Admin`
javadoc.

Pagination for `DescribeTopicPartitions` is from **KIP-966** (per the search
result text: "the caller can specify the maximum number of partitions to be
included in the response … the Cursor field will be populated"); the exact KIP
attribution for the *request type itself* vs. the ELR fields is **not verified**
— I did not fetch the KIP-966 page body directly. KIP-1062 ("Introduce
Pagination for some requests used by Admin API") is the follow-up that reworks
the Admin-side interface; also **not verified** in detail.

---

### 3. Topic configuration, and telling "set" from "inherited"

#### API

```java
DescribeConfigsResult describeConfigs(Collection<ConfigResource> resources);
DescribeConfigsResult describeConfigs(Collection<ConfigResource> resources,
                                      DescribeConfigsOptions options);
```

with `new ConfigResource(ConfigResource.Type.TOPIC, topicName)`. Result is
`Map<ConfigResource, KafkaFuture<Config>>`; `Config.entries()` →
`Collection<ConfigEntry>`.

`ConfigEntry` (4.0.2 javadoc + 4.1.0 source):

| Method | Meaning |
|---|---|
| `name()` | config name |
| `value()` | "Return the value or null. Null is returned if the config is unset or if `isSensitive` is true." |
| `source()` | `ConfigEntry.ConfigSource` |
| `isDefault()` | `ConfigEntry.java:102-104`: `return source == ConfigSource.DEFAULT_CONFIG;` |
| `isSensitive()` | value is nulled by the broker |
| `isReadOnly()` | see caveat below |
| `synonyms()` | "all config values that may be used as the value of this config along with their source, **in the order of precedence**" |
| `type()`, `documentation()` | `ConfigDef.Type`; docs only if `DescribeConfigsOptions.includeDocumentation(true)` |

`ConfigEntry.ConfigSource` enum with the in-source comments verbatim
(`ConfigEntry.java:215-225`):

```java
DYNAMIC_TOPIC_CONFIG,          // dynamic topic config that is configured for a specific topic
DYNAMIC_BROKER_LOGGER_CONFIG,  // dynamic broker logger config that is configured for a specific broker
DYNAMIC_BROKER_CONFIG,         // dynamic broker config that is configured for a specific broker
DYNAMIC_DEFAULT_BROKER_CONFIG, // dynamic broker config that is configured as default for all brokers in the cluster
DYNAMIC_CLIENT_METRICS_CONFIG, // dynamic client metrics subscription config that is configured for all clients
DYNAMIC_GROUP_CONFIG,          // dynamic group config that is configured for a specific group
STATIC_BROKER_CONFIG,          // static broker config provided as broker properties at start up (e.g. server.properties file)
DEFAULT_CONFIG,                // built-in default configuration for configs that have a default value
UNKNOWN                        // source unknown e.g. in the ConfigEntry used for alter requests where source is not set
```

#### How the broker computes `source()` for a topic — the exact answer

`core/src/main/scala/kafka/server/ConfigHelper.scala:227-249`
(`createTopicConfigEntry`), 4.1.0:

```scala
val allSynonyms = {
  val list = Option(ServerTopicConfigSynonyms.TOPIC_CONFIG_SYNONYMS.get(name))
    .map(s => configSynonyms(s, brokerSynonyms(s), isSensitive))
    .getOrElse(List.empty)
  if (!topicProps.containsKey(name))
    list
  else
    new DescribeConfigsSynonym().setName(name).setValue(valueAsString)
      .setSource(ConfigSource.TOPIC_CONFIG.id) +: list
}
val source = if (allSynonyms.isEmpty) ConfigSource.DEFAULT_CONFIG.id else allSynonyms.head.source
```

and the broker-side synonym chain, `ConfigHelper.scala:287-290`, in this order:

```scala
synonyms.foreach(maybeAddSynonym(dynamicConfig.currentDynamicBrokerConfigs,   ConfigSource.DYNAMIC_BROKER_CONFIG))
synonyms.foreach(maybeAddSynonym(dynamicConfig.currentDynamicDefaultConfigs,  ConfigSource.DYNAMIC_DEFAULT_BROKER_CONFIG))
synonyms.foreach(maybeAddSynonym(dynamicConfig.staticBrokerConfigs,           ConfigSource.STATIC_BROKER_CONFIG))
synonyms.foreach(maybeAddSynonym(dynamicConfig.staticDefaultConfigs,          ConfigSource.DEFAULT_CONFIG))
```

The wire enum `TOPIC_CONFIG` maps to the client enum `DYNAMIC_TOPIC_CONFIG`
(`DescribeConfigsResponse.java:116`).

**So, precisely:**

> `ConfigEntry.source() == DYNAMIC_TOPIC_CONFIG` **if and only if** that config
> key is present in the topic's own stored config overrides. Any other source
> means the value was inherited: `DYNAMIC_BROKER_CONFIG` (set dynamically on
> that specific broker) > `DYNAMIC_DEFAULT_BROKER_CONFIG` (cluster-wide dynamic
> default) > `STATIC_BROKER_CONFIG` (in `server.properties`) > `DEFAULT_CONFIG`
> (Kafka's compiled-in default).

For Nodqora's "show retention on a node": read `retention.ms` /
`retention.bytes`, and render the **value** always (it is the effective value in
every case), but annotate the *provenance* from `source()`. A retention badge
that says "7 days" is correct whether the source is `DYNAMIC_TOPIC_CONFIG` or
`STATIC_BROKER_CONFIG`; only a badge that says "explicitly configured on this
topic" needs `source() == DYNAMIC_TOPIC_CONFIG`. `isDefault()` is a *narrower*
test than "inherited" — it is true only for `DEFAULT_CONFIG`, so a value
inherited from `server.properties` has `isDefault() == false` while still not
being topic-specific. **Do not use `isDefault()` to mean "not explicitly set".**

**Caveat — `isReadOnly()` is useless for topics.** `ConfigHelper.scala:248`
hardcodes `.setReadOnly(false)` on every topic config entry.

**Caveat — synonyms cost nothing extra to ask for but are off by default.**
`DescribeConfigsOptions.includeSynonyms(true)` is what populates
`ConfigEntry.synonyms()`; without it you get `source()` but not the chain.

#### The configs worth reading (doc strings from `TopicConfig.java`, 4.1.0)

- `retention.ms` — "controls the maximum time we will retain a log before we
  will discard old log segments to free up space **if we are using the "delete"
  retention policy**. … **If set to -1, no time limit is applied.**"
- `retention.bytes` — "controls the maximum size a partition (which consists of
  log segments) can grow to … By default there is no size limit only a time
  limit. **Since this limit is enforced at the partition level, multiply it by
  the number of partitions to compute the topic retention in bytes.**"
- `cleanup.policy` — "The "delete" policy (which is the default) will discard
  old segments when their retention time or size limit has been reached. The
  "compact" policy will enable log compaction … It is also possible to specify
  both policies in a comma-separated list (e.g. "delete,compact")."
- `min.insync.replicas` — "Specifies the minimum number of in-sync replicas
  (including the leader) …"
- `delete.retention.ms`, `local.retention.ms` / `local.retention.bytes`
  (tiered storage; default `-2` meaning "use `retention.*`"),
  `remote.storage.enable`.

Two graph-relevant consequences: `retention.bytes` is **per partition**, so a
topic-level retention cap is `retention.bytes × partitions`; and `retention.ms`
is meaningless when `cleanup.policy` is `compact` alone.

---

### 4. Topic size on disk, and message counts

#### 4a. `describeLogDirs` — bytes

```java
DescribeLogDirsResult describeLogDirs(Collection<Integer> brokers);
DescribeLogDirsResult describeLogDirs(Collection<Integer> brokers, DescribeLogDirsOptions options);
```

`DescribeLogDirsResult`:

- `descriptions()` → `Map<Integer, KafkaFuture<Map<String, LogDirDescription>>>`
  — "a map from brokerId to future which can be used to check the information of
  partitions on each individual broker"
- `allDescriptions()` → `KafkaFuture<Map<Integer, Map<String, LogDirDescription>>>`
  — "succeeds only if **all** the brokers have responded without error"

`LogDirDescription` (4.0.2 javadoc): "A description of a log directory on a
particular broker."

- `replicaInfos()` → `Map<TopicPartition, ReplicaInfo>`
- `error()` → `ApiException`, "if the log directory is offline or an error
  occurred, otherwise returns null"
- `totalBytes()`, `usableBytes()` → `OptionalLong` — KIP-827, DescribeLogDirs
  protocol **v4**; "The optional will be empty if the broker does not support
  this feature or if an error happened accessing the log directory."

`ReplicaInfo` (4.0.2 javadoc): "A description of a replica on a particular
broker."

- **`size()`** → `long` — **"The total size of the log segments in this replica
  in bytes."**
- `offsetLag()` → `long` — "The lag of the log's LEO with respect to the
  partition's high watermark (if it is the current log for the partition) or the
  current replica's LEO (if it is the future log for the partition)."
- `isFuture()` → `boolean` — "Whether this replica has been created by a
  `AlterReplicaLogDirsRequest` but not yet replaced the current replica on the
  broker."

#### What `size()` actually is, from the broker source

`core/src/main/scala/kafka/server/ReplicaManager.scala:1217-1254`,
`describeLogDirs`, sets `.setPartitionSize(log.size)`. And
`storage/.../UnifiedLog.java:1998-2000`:

```java
public long size() {
    return LogSegments.sizeInBytes(logSegments());
}
```
with `LogSegments.sizeInBytes(Collection<LogSegment>)` = `segments.stream().mapToLong(LogSegment::size).sum()`.

So `ReplicaInfo.size()` is the **sum of the log-segment file sizes for one
replica of one partition, on one broker** — the on-disk footprint of that copy,
including indexes? No: it is the sum of `LogSegment::size`, i.e. the *log*
segment bytes; `.index`/`.timeindex` files are not included. It is **not**
deduplicated across replicas and it is **not** a logical byte count.

#### Aggregating to a "logical topic size"

There is no API for this; you must do it. Given
`Map<Integer /*broker*/, Map<String /*logDir*/, LogDirDescription>>`:

1. For each `(broker, logDir, TopicPartition) -> ReplicaInfo`, **discard
   `isFuture() == true` entries** — those are in-progress inter-log-dir moves
   and would double count.
2. Group the remaining by `TopicPartition`. You now have N sizes per partition,
   one per replica (roughly, but see caveats).
3. Two defensible aggregates:
   - **Physical / stored bytes** = sum over *all* replicas. This is what the
     cluster actually consumes and is what a capacity view wants.
   - **Logical bytes** = for each partition take the size of the **leader's**
     replica (join to `TopicDescription.partitions()[i].leader().id()`), then sum
     over partitions. This is the "how much data is in this topic" number a
     topology node should show.
   Sizes across replicas differ legitimately — segment roll timing, a lagging
   follower, retention deletion racing between brokers — so `physical / RF` is
   an approximation, not an identity.
4. Replicas whose log dir has a non-null `error()` (offline dir,
   `KAFKA_STORAGE_ERROR`) are simply missing; treat as unknown, not zero.

#### Cost

One `DescribeLogDirsRequest` **per broker**, and the AdminClient sends
`setTopics(null)`:

```java
public DescribeLogDirsRequest.Builder createRequest(int timeoutMs) {
    // Query selected partitions in all log directories
    return new DescribeLogDirsRequest.Builder(new DescribeLogDirsRequestData().setTopics(null));
}
```
(`KafkaAdminClient.java:3013-3017`)

meaning **there is no way to ask for a subset of topics through the public
`Admin` API** — the request always asks for everything and the response
enumerates every partition on that broker. Broker-side work
(`ReplicaManager.describeLogDirs`) is: `logManager.allLogs.groupBy(_.parentDir)`
(in-memory, O(partitions on the broker)), one `Files.getFileStore(dir)` +
`getTotalSpace`/`getUsableSpace` syscall **per log dir** (not per partition), and
`log.size` per partition which is an in-memory sum over cached segment sizes.
So it is not doing per-partition `stat()` calls — but the **response payload is
O(partitions on that broker)** and the client-side object graph is a `HashMap`
entry per replica per broker.

**Documented warnings:** I found **none**. Neither the `Admin` javadoc, the
`ops.html` docs, nor KIP-827 carries a caution about `describeLogDirs` cost —
**not verified** that any official warning exists. The cost profile above is
inferred from reading the implementation, which is a primary source, but the
absence of a documented warning is itself a finding: treat the cost as
structural (fan-out × partitions), not as an officially-flagged hazard.

#### 4b. `listOffsets` — message counts

```java
ListOffsetsResult listOffsets(Map<TopicPartition, OffsetSpec> topicPartitionOffsets);
ListOffsetsResult listOffsets(Map<TopicPartition, OffsetSpec> topicPartitionOffsets,
                              ListOffsetsOptions options);
```

`ListOffsetsResult.ListOffsetsResultInfo`: `offset()` → `long`, `timestamp()` →
`long`, `leaderEpoch()` → `Optional<Integer>`.

`OffsetSpec` factory methods (4.0.2 javadoc, verbatim):

| Factory | Javadoc |
|---|---|
| `earliest()` | "Used to retrieve the earliest offset of a partition" |
| `latest()` | "Used to retrieve the latest offset of a partition" |
| `forTimestamp(long)` | "…the earliest offset whose timestamp is greater than or equal to the given timestamp" |
| `maxTimestamp()` | "…the offset with the largest timestamp of a partition as message timestamps can be specified client side **this may not match the log end offset returned by LatestSpec**" |
| `earliestLocal()` | "…the local log start offset. Local log start offset is the offset of a log above which reads are guaranteed to be served from the disk of the leader broker." (tiered storage) |
| `latestTiered()` | "…the highest offset of data stored in remote storage." |

##### Caveat 1 — `latest()` is the **high watermark**, not the LEO

`kafka/cluster/Partition.scala:1577-1581` (4.1.0):

```scala
val lastFetchableOffset = isolationLevel match {
  case Some(IsolationLevel.READ_COMMITTED)   => localLog.lastStableOffset
  case Some(IsolationLevel.READ_UNCOMMITTED) => localLog.highWatermark
  case None                                  => localLog.logEndOffset
}
```

The AdminClient always builds a *consumer*-flavoured request
(`ListOffsetsHandler.buildBatchedRequest` → `ListOffsetsRequest.Builder.forConsumer(...)`
with an isolation level), and `ListOffsetsOptions`' no-arg constructor is
`this(IsolationLevel.READ_UNCOMMITTED)` (`ListOffsetsOptions.java:31`). So the
`None` branch — the true LEO — is **unreachable from AdminClient**. What you get
back is the high watermark by default, or the **last stable offset** if you pass
`new ListOffsetsOptions(IsolationLevel.READ_COMMITTED)`. With open transactions
on the partition, the READ_COMMITTED figure can sit arbitrarily far behind.

The same wording is in the consumer javadoc (`KafkaConsumer.endOffsets`, 4.1.0
source): "In the default `read_uncommitted` isolation level, the end offset is
the high watermark (that is, the offset of the last successfully replicated
message plus one). For `read_committed` consumers, the end offset is the last
stable offset (LSO), which is the minimum of the high watermark and the smallest
offset of any open transaction. Finally, if the partition has never been written
to, the end offset is 0."

##### Caveat 2 — `earliest()` is the log start offset, and it moves

`UnifiedLog.java:176-187` (4.1.0), on the `logStartOffset` constructor param:

> "The earliest offset allowed to be exposed to kafka client. The
> logStartOffset can be updated by: **user's DeleteRecordsRequest**, **broker's
> log retention**, **broker's log truncation**, **broker's log recovery**. The
> logStartOffset is used to decide the following: … **Earliest offset of the log
> in response to ListOffsetRequest.**"

So `earliest()` monotonically advances as retention deletes segments.

##### Caveat 3 — what `latest − earliest` actually means

- **`cleanup.policy=delete`, no deletions since the topic was created:** exactly
  the number of messages ever written (well — the number of *record batch
  offsets* assigned; a compressed batch consumes one offset per record, and
  transaction control markers also consume offsets).
- **`cleanup.policy=delete`, retention active:** an **upper bound** on the
  number of messages currently retained. Segments are deleted whole, and
  `logStartOffset` jumps to the new first segment's base offset, so the count
  is right at segment granularity — but only after the deletion actually lands.
- **`cleanup.policy=compact`:** the difference is **not** a message count at
  all, and can be arbitrarily larger than the number of retained records. From
  the Kafka design docs (§4.10, Log Compaction Basics):

  > "the messages in the tail of the log retain the original offset assigned
  > when they were first written—**that never changes**. Note also that **all
  > offsets remain valid positions in the log, even if the message with that
  > offset has been compacted away**; in this case this position is
  > indistinguishable from the next highest offset that does appear in the log.
  > For example … the offsets 36, 37, and 38 are all equivalent positions and a
  > read beginning at any of these offsets would return a message set beginning
  > with 38."

  and from the compaction guarantees list:

  > "**The offset for a message never changes.** It is the permanent identifier
  > for a position in the log."

  So on a compacted topic, `latest − earliest` counts *offset slots*, most of
  which may be holes. A 10-key compacted topic written 10 million times reports
  ~10,000,000, not 10.
- **`cleanup.policy=delete,compact`:** worst of both.
- **Tombstones** (null-payload records) also occupy offsets and are themselves
  deleted after `delete.retention.ms` (docs: "the default is 24 hours").

**Practical rule for Nodqora:** show `latest − earliest` as "offset span", not
"messages", and suppress or badge it whenever `cleanup.policy` contains
`compact`. The bytes number from `describeLogDirs` is the honest size signal.

---

### 5. Consumer groups

#### Listing

4.1.0 deprecated the consumer-specific listing. From `Admin.java` at 4.1.0
(lines 879-901), verbatim:

```java
/**
 * List the consumer groups available in the cluster.
 * @deprecated Since 4.1. Use {@link Admin#listGroups(ListGroupsOptions)} instead.
 */
@Deprecated(since = "4.1", forRemoval = true)
ListConsumerGroupsResult listConsumerGroups(ListConsumerGroupsOptions options);
```

**Correction to a common belief:** this is **4.1, not 4.0**. I checked the
`4.0.0` tag's `Admin.java` — `listConsumerGroups(ListConsumerGroupsOptions)`
carries no `@Deprecated` annotation there. `listGroups` itself is not deprecated
and has no version annotation in the javadoc.

```java
ListGroupsResult listGroups();
ListGroupsResult listGroups(ListGroupsOptions options);   // filters: types(), groupStates(), protocolTypes()
```

`ListGroupsOptions` filters by `GroupType` and `GroupState` and are pushed to
the broker as `setTypesFilter` / `setStatesFilter` on the `ListGroups` request
(`KafkaAdminClient.java:3498-3508`); `protocolTypes()` is filtered
**client-side** (`maybeAddGroup`).

#### Group state enums — the 4.0 rename

- `org.apache.kafka.common.ConsumerGroupState` — **deprecated as of 4.0**:
  "Since 4.0. Use `GroupState` instead." All constants deprecated.
- `org.apache.kafka.common.GroupState` — constants and their applicable group
  types (4.0.2 javadoc):

| Constant | Classic | Consumer | Share |
|---|---|---|---|
| `UNKNOWN` | ✔ | ✔ | ✔ |
| `PREPARING_REBALANCE` | ✔ | ✔ | |
| `COMPLETING_REBALANCE` | ✔ | ✔ | |
| `STABLE` | ✔ | ✔ | ✔ |
| `DEAD` | ✔ | ✔ | ✔ |
| `EMPTY` | ✔ | ✔ | ✔ |
| `ASSIGNING` | | ✔ | |
| `RECONCILING` | | | ✔ |

  plus `GroupState.groupStatesForType(GroupType)` and a case-insensitive
  `GroupState.parse(String)`. The exact release that introduced `GroupState` is
  **not verified** beyond "present in 4.0 and `ConsumerGroupState` is deprecated
  since 4.0".

#### Describing

```java
DescribeConsumerGroupsResult describeConsumerGroups(Collection<String> groupIds);
DescribeConsumerGroupsResult describeConsumerGroups(Collection<String> groupIds,
                                                    DescribeConsumerGroupsOptions options);
```

`ConsumerGroupDescription` (4.0.2 javadoc):

| Method | Return type | Note |
|---|---|---|
| `groupId()` | `String` | |
| `isSimpleConsumerGroup()` | `boolean` | |
| `members()` | `Collection<MemberDescription>` | |
| `partitionAssignor()` | `String` | |
| `type()` | `GroupType` | `CLASSIC` / `CONSUMER` |
| `state()` | `ConsumerGroupState` | **deprecated since 4.0** → `groupState()` |
| `groupState()` | `GroupState` | |
| `coordinator()` | `Node` | |
| `authorizedOperations()` | `Set<AclOperation>` | |
| `groupEpoch()` | `Optional<Integer>` | consumer-protocol only |
| `targetAssignmentEpoch()` | `Optional<Integer>` | consumer-protocol only |

How those are populated (`DescribeConsumerGroupsHandler.java`, 4.1.0):

- Consumer-protocol (KIP-848) groups, `:232-243`: `isSimpleConsumerGroup` is
  hardcoded `false`, `partitionAssignor` = `describedGroup.assignorName()`,
  `type` = `GroupType.CONSUMER`, epochs present.
- Classic groups, `:271-303`: accepted only if
  `protocolType.equals(ConsumerProtocol.PROTOCOL_TYPE) || protocolType.isEmpty()`;
  **`isSimpleConsumerGroup` = `protocolType.isEmpty()`**, `partitionAssignor` =
  `describedGroup.protocolData()` (e.g. `range`, `roundrobin`,
  `cooperative-sticky`), epochs `Optional.empty()`. If the protocol type is
  anything else (e.g. `connect`), the group fails with
  `IllegalArgumentException("GroupId X is not a consumer group (…)")`.

**Two RPCs behind one call.** The handler tries `ConsumerGroupDescribe` (API 69)
first and falls back to the classic `DescribeGroups` (API 15) per group, on
`UNSUPPORTED_VERSION` or `GROUP_ID_NOT_FOUND`
(`DescribeConsumerGroupsHandler.java:352-380`). This matters for ACLs — see
Permissions.

`MemberDescription` (4.0.2 javadoc), verbatim:

| Method | Return type | Javadoc |
|---|---|---|
| `consumerId()` | `String` | "The consumer id of the group member." |
| `groupInstanceId()` | `Optional<String>` | "The instance id of the group member." (static membership, `group.instance.id`) |
| `clientId()` | `String` | "The client id of the group member." |
| `host()` | `String` | "The host where the group member is running." |
| `assignment()` | `MemberAssignment` | "Provided for both classic group and consumer group." → `.topicPartitions()` : `Set<TopicPartition>` |
| `targetAssignment()` | `Optional<MemberAssignment>` | "Provided only for consumer group." |
| `memberEpoch()` | `Optional<Integer>` | set for `CONSUMER` groups, empty for `CLASSIC` |
| `upgraded()` | `Optional<Boolean>` | true if a member in a `CONSUMER` group uses the CONSUMER protocol |

#### A group with no live members

This is the important case for a topology graph, because it is the normal state
of a stopped or scaled-to-zero consumer.

- `describeConsumerGroups` **still returns the group**: `groupState() == EMPTY`,
  `members()` empty, `coordinator()` populated, `partitionAssignor()` typically
  an empty string. Verified indirectly through `ConsumerGroupCommand`, which
  branches on `case "Empty": case "Dead":` for reset operations
  (`ConsumerGroupCommand.java:663-666`) and prints group state for groups with
  no members.
- `listConsumerGroupOffsets` **still returns the committed offsets** — they live
  in `__consumer_offsets`, not in member state.
- `ConsumerGroupCommand.collectGroupsOffsets` (`:845-891`) handles it exactly
  this way: it collects rows from members with non-empty assignments, then takes
  every committed partition **not** covered by a member and emits it with
  consumer id / host / client id all set to `MISSING_COLUMN_VALUE` (`"-"`).

So: **an empty group gives you the topic→group edges (via committed offsets) and
the lag, but no client id, no host, and no member identity.** Topology edges
survive a consumer restart; attribution does not.

- A group that has been `DEAD`/deleted, or whose offsets expired, returns
  nothing useful — see §6 on `offsets.retention.minutes`.

---

### 6. Offsets and lag

#### API

```java
ListConsumerGroupOffsetsResult listConsumerGroupOffsets(String groupId);
ListConsumerGroupOffsetsResult listConsumerGroupOffsets(String groupId, ListConsumerGroupOffsetsOptions options);
ListConsumerGroupOffsetsResult listConsumerGroupOffsets(Map<String, ListConsumerGroupOffsetsSpec> groupSpecs,
                                                        ListConsumerGroupOffsetsOptions options);
```

`ListConsumerGroupOffsetsResult.partitionsToOffsetAndMetadata(groupId)` →
`KafkaFuture<Map<TopicPartition, OffsetAndMetadata>>`. The **batched** form
(a map of group id → spec) is the one to use for a poller: it lets you ask
about many groups at once, and the single-group overloads delegate to it
(`Admin.java:915-921`).

`ListConsumerGroupOffsetsSpec` optionally narrows to specific
`topicPartitions()`; leaving it unset returns **all partitions that have a
committed offset for that group** — which is exactly the set you want for
building topic→group edges, because it tells you which topics the group has
ever committed against.

`ListConsumerGroupOffsetsOptions.requireStable(boolean)` forces the coordinator
to refuse to answer while there is a pending transactional offset commit,
instead of returning a possibly-stale value.

#### Lag

Lag is **not** an API. It is arithmetic you do:

```
lag(tp) = listOffsets(latest).get(tp).offset() - committedOffset(tp)
```

#### Is this what `kafka-consumer-groups.sh --describe` does? Yes — verified

From `tools/src/main/java/org/apache/kafka/tools/consumer/group/ConsumerGroupCommand.java`
at 4.1.0:

```java
private Optional<Long> getLag(Optional<Long> offset, Optional<Long> logEndOffset) {
    return offset.filter(o -> o != -1).flatMap(offset0 -> logEndOffset.map(end -> end - offset0));
}
```
(`:609-611`)

```java
private Map<TopicPartition, LogOffsetResult> getLogEndOffsets(Collection<TopicPartition> topicPartitions) {
    return getLogOffsets(topicPartitions, OffsetSpec.latest());
}
```
(`:942-944`, and `getLogOffsets` calls `adminClient.listOffsets(startOffsets, withTimeoutMs(new ListOffsetsOptions()))` at `:955`)

```java
private Map<TopicPartition, OffsetAndMetadata> getCommittedOffsets(String groupId) {
    return adminClient.listConsumerGroupOffsets(
        Collections.singletonMap(groupId, new ListConsumerGroupOffsetsSpec()),
        withTimeoutMs(new ListConsumerGroupOffsetsOptions())
    ).partitionsToOffsetAndMetadata(groupId).get();
}
```
(`:1090-1098`)

Note it uses `new ListOffsetsOptions()` — i.e. `READ_UNCOMMITTED`, i.e. the
**high watermark**. So the tool's `LOG-END-OFFSET` column is the high watermark
despite its name, and so is every lag figure derived from it.

#### The exact caveats

1. **No committed offset for a partition → `null`, and the lag column is
   blank, not zero.** `ConsumerGroupCommand.java:622-624`:
   ```java
   // The admin client returns `null` as a value to indicate that there is not committed offset for a partition.
   Optional<Long> offset = Optional.ofNullable(committedOffsets.get(topicPartition)).map(OffsetAndMetadata::offset);
   ```
   and the underlying conversion, `ListConsumerGroupOffsetsHandler.java:168-171`:
   ```java
   // Negative offset indicates that the group has no committed offset for this partition.
   if (partition.committedOffset() < 0) {
       offsets.put(tp, null);
   } else { ... }
   ```
   **This only surfaces as an explicit `null` if you asked about that partition**
   via `ListConsumerGroupOffsetsSpec.topicPartitions(...)`. If you leave the spec
   open, uncommitted partitions are simply absent from the map.
2. **The meaning of `-1`.** On the wire, an unset committed offset is a negative
   `committedOffset` (the `INVALID_OFFSET` sentinel). The client converts
   `< 0` → `null`. The tool *additionally* filters `o -> o != -1` in `getLag`,
   belt-and-braces, so a `-1` that ever reached an `OffsetAndMetadata` would
   still produce no lag rather than `LEO + 1`. **Never compute `LEO - (-1)`.**
   Note the same sentinel is unrelated to `ListOffsetsResponse.UNKNOWN_OFFSET`,
   which is what a *failed* `listOffsets` partition returns.
3. **Lag can be negative.** Committed offsets are compared against a high
   watermark read at a different instant and possibly from a different leader.
   A consumer that has just committed past the HW you sampled a moment earlier
   yields a small negative number. Clamp at 0 for display; do not treat it as an
   error.
4. **Compacted topics make lag misleading in the same way as counts.** A lag of
   1,000,000 on a compacted topic may be 12 actual records to read (see §4b).
   Lag on a compacted topic is an offset distance, not a work estimate.
5. **Partitions with no leader** return an error rather than an offset; the tool
   maps those to `Unknown` and prints `-` (`ConsumerGroupCommand.java:636-637`).
6. **Staleness of the committed offset.** For an auto-committing consumer the
   committed value trails the actual position by up to one commit interval:
   `auto.commit.interval.ms` defaults to **5000 ms**
   (`ConsumerConfig.java:467-472`: `.define(AUTO_COMMIT_INTERVAL_MS_CONFIG, Type.INT, 5000, …)`),
   doc string: "The frequency in milliseconds that the consumer offsets are
   auto-committed to Kafka if `enable.auto.commit` is set to `true`." Manually
   committing consumers can be arbitrarily staler. So a small positive lag
   (order of one commit interval × throughput) is noise, not a signal.
7. **Committed offsets expire.** `offsets.retention.minutes` defaults to
   **10080 (7 days)** —
   `GroupCoordinatorConfig.java:118`: `OFFSETS_RETENTION_MINUTES_DEFAULT = 7 * 24 * 60;`
   Its doc string, verbatim:

   > "For subscribed consumers, committed offset of a specific partition will be
   > expired and discarded when 1) this retention period has elapsed after the
   > consumer group loses all its consumers (i.e. becomes empty); 2) this
   > retention period has elapsed since the last time an offset is committed for
   > the partition and the group is no longer subscribed to the corresponding
   > topic. For standalone consumers (using manual assignment), offsets will be
   > expired after this retention period has elapsed since the time of last
   > commit. Note that when a group is deleted via the delete-group request, its
   > committed offsets will also be deleted without extra retention period; also
   > when a topic is deleted via the delete-topic request, upon propagated
   > metadata update any group's committed offsets for that topic will also be
   > deleted without extra retention period."

   The expiry sweep runs every `offsets.retention.check.interval.ms`, default
   **600000 ms / 10 minutes** (`GroupCoordinatorConfig.java:127`).

   **Graph consequence:** a topic→group edge discovered only from committed
   offsets **disappears** 7 days after the group goes empty. Nodqora must decide
   whether such an edge is deleted or tombstoned/retained with a "last seen"
   timestamp. This is precisely the kind of edge that a naive "poll and replace"
   discovery loop will silently drop a week after a service is decommissioned —
   and, worse, a week after a service is merely *paused*.

---

### 7. Throughput — the honest answer

**There is no throughput API on `Admin`. None.** Every rate you can produce is a
client-side finite difference over two polls.

#### What you can actually build

**(a) Messages/sec per partition** — sample `listOffsets(latest)` at `t0` and
`t1`:

```
msgs_per_sec(tp) ≈ (latest(t1) - latest(t0)) / ((t1 - t0) / 1000)
```

Sum over partitions for a topic rate. Properties:

- It is the **high watermark** delta, not the LEO delta (see §4b caveat 1), so
  it lags real production by the replication acknowledgement time and, under
  READ_COMMITTED, by open-transaction duration.
- It counts **offsets**, not records: a compressed batch consumes one offset per
  record so that part is fine, but **transaction control markers (commit/abort)
  also consume an offset**, so a transactional workload over-reports slightly.
- It is monotonic (the HW never goes backwards on a stable leader), so the
  subtraction is safe **except** across leader changes with truncation, and
  across topic recreation.

**(b) Bytes/sec** — sample `describeLogDirs` at `t0` and `t1` and difference
`ReplicaInfo.size()` per partition (leader replica only).

```
bytes_per_sec(tp) ≈ (size(t1) - size(t0)) / ((t1 - t0) / 1000)
```

This one is **materially worse**, because `size()` is *bytes currently on disk*,
not *bytes ever written*:

- **Retention deletion makes it non-monotonic.** When a segment is deleted, the
  size drops by a whole segment (default `segment.bytes` is 1 GiB unless
  overridden — value **not verified** here). Between two polls you can easily
  see a large negative delta on a healthy, busy topic. A naive `max(0, delta)`
  discards the real traffic in that window; a naive raw delta reports negative
  throughput.
- **Compaction makes it non-monotonic** for the same reason, at unpredictable
  times, driven by the background cleaner.
- **Segment roll granularity**: bytes appear in chunks as the active segment
  grows, so short poll intervals give a lumpy signal.
- It measures **stored** bytes including replication overhead if you sum all
  replicas, and includes record overhead/headers, so it is not "application
  bytes".

**(c) Consumer throughput** — differencing committed offsets from
`listConsumerGroupOffsets` gives a *commit* rate, which is bounded above by
`1 / auto.commit.interval.ms` resolution and is therefore a very coarse proxy
for consumption rate.

#### Accuracy problems common to all of the above

- **Poll-interval granularity.** A 30 s poll gives you a 30 s average. Bursts,
  spikes, and anything you would want an alert on are invisible.
- **Restarts and gaps.** Any missed poll widens Δt; any collector restart loses
  `t0`. You must persist the previous sample or accept a hole.
- **Leader changes / partition reassignment.** The HW is per-partition and
  survives leader change, so (a) is mostly safe; `ReplicaInfo.size()` is
  per-broker-per-replica, so (b) breaks when a replica moves — a partition
  vanishing from broker 3 and appearing on broker 5 looks like a huge negative
  then a huge positive delta unless you difference per `TopicPartition` after
  picking the leader replica, and even then the new leader's copy may have a
  different size.
- **Topic deletion and recreation** resets offsets to 0 — a large negative
  delta. Guard with `TopicDescription.topicId()`, which changes on recreation.
- **Clock**: use the collector's monotonic clock for Δt, not broker timestamps.

#### What this does NOT give you, at all

- Per-**producer** or per-**consumer** rates (no client attribution exists in
  any of these numbers).
- Request rates, error rates, throttling.
- Any latency: produce/fetch p50/p95/p99, queue time, local time, remote time.
- Bytes in vs bytes out separately (`listOffsets` deltas cannot distinguish
  produce from replication; `describeLogDirs` says nothing about reads at all).
- Consumer fetch rates, rebalance rates, or `records-lag-max` as published by
  the consumer itself.

Those live in JMX; see the final section for the exact MBean names.

---

### 8. Other APIs relevant to a topology graph

#### `describeProducers` — who is writing to a partition (KIP-664)

```java
DescribeProducersResult describeProducers(Collection<TopicPartition> partitions);
DescribeProducersResult describeProducers(Collection<TopicPartition> partitions,
                                          DescribeProducersOptions options);
```

→ `Map<TopicPartition, KafkaFuture<DescribeProducersResult.PartitionProducerState>>`,
and `PartitionProducerState.activeProducers()` → `List<ProducerState>`.

`ProducerState` (4.0.2 javadoc) carries **exactly**:

| Method | Return type |
|---|---|
| `producerId()` | `long` |
| `producerEpoch()` | `int` |
| `lastSequence()` | `int` |
| `lastTimestamp()` | `long` |
| `coordinatorEpoch()` | `OptionalInt` |
| `currentTransactionStartOffset()` | `OptionalLong` |

**There is no `clientId()` and no `transactionalId()`.** KIP-664's own
DescribeProducers response field list is the same six fields (ProducerId,
ProducerEpoch, LastSequence, LastTimestamp, TxnStartOffset, CoordinatorEpoch).
The broker builds this from `Partition.activeProducerState` →
`log.activeProducers`, which is the producer-id state used for idempotence and
transactions.

**Verdict for the graph:** `describeProducers` proves *that* some producer is
active on a partition and gives you an opaque numeric PID and a last-write
timestamp. It does **not** name the writing application. A PID is assigned by
the cluster and rotates; it is not stable across producer restarts for
non-transactional producers. So this is a **liveness signal** ("this partition
has an active writer, last wrote at T"), not an identity signal, and it cannot
draw a producer→topic edge to a named service. It also requires **`Read` on the
topic**, which is a stronger permission than everything else in this document.

Cost: routed by `PartitionLeaderStrategy` (`KafkaAdminClient.java:4805-4810`),
so it is a metadata lookup plus one batched request per leader — but you must
enumerate **every partition you care about**, which makes a whole-cluster sweep
expensive.

#### `listTransactions` / `describeTransactions` — named transactional writers

```java
ListTransactionsResult listTransactions();
ListTransactionsResult listTransactions(ListTransactionsOptions options);
DescribeTransactionsResult describeTransactions(Collection<String> transactionalIds);
```

`TransactionListing` (4.0.2 javadoc): `transactionalId()` → `String`,
`producerId()` → `long`, `state()` → `TransactionState`.

**This is the one place AdminClient gives you a human-meaningful producer
name** — but only for producers that set `transactional.id`, i.e. Kafka Streams
applications (which derive it from `application.id`), exactly-once Connect
sink/source tasks, and hand-written transactional producers. Plain producers
never appear.

`listTransactions` fans out to **every broker** (`AllBrokersStrategy`,
`KafkaAdminClient.java:4861-4867`), and the broker filters the response per
`transactionalId` by `Describe` on `TransactionalId`
(`KafkaApis.scala:2570-2576`), silently removing entries you cannot see —
so a partial ACL grant yields a silently partial answer, not an error.

Joining `TransactionListing.producerId()` to
`ProducerState.producerId()` from `describeProducers` on a partition is the only
AdminClient-only route from a **partition** to a **named** writer. It works only
for transactional producers, and only while a producer id is live.
Whether the join is reliable in practice (PID reuse, epoch bumps) is **not
verified**.

#### `describeTopics(TopicCollection.ofTopicIds(...))`

```java
DescribeTopicsResult describeTopics(TopicCollection topics, DescribeTopicsOptions options);
// TopicCollection.ofTopicIds(Collection<Uuid>) / TopicCollection.ofTopicNames(Collection<String>)
```

`DescribeTopicsResult.ofTopicIds(...)` keys futures by `Uuid`. Per the `Admin`
javadoc this requires broker **3.1.0+**. Useful for Nodqora because `Uuid` is
the only identifier that distinguishes a recreated topic from the original —
`TopicDescription.topicId()` and `TopicListing.topicId()` both expose it, and it
is the right stable key for a topic node's identity (the *name* is not: delete
and recreate reuses the name with a fresh id and fresh offsets).

Note the mixed-collection restriction: `describeTopics` throws
`IllegalArgumentException` for a `TopicCollection` that is neither
`TopicIdCollection` nor `TopicNameCollection` (`KafkaAdminClient.java:2135-2143`).

#### Client-id / host visibility, summarised

The **only** client-identifying strings available anywhere in AdminClient are:

- `MemberDescription.clientId()` — the consumer's `client.id`
- `MemberDescription.host()` — the host it connects from
- `MemberDescription.groupInstanceId()` — `group.instance.id`, if static membership
- `MemberDescription.consumerId()` — coordinator-assigned member id (ephemeral)
- `TransactionListing.transactionalId()` — transactional producers only

All consumer-side identity therefore depends on **live members**. A stopped
consumer group is invisible for attribution (§5).

#### `listConfigResources` (4.1.0)

`Admin.listConfigResources(Set<ConfigResource.Type>, ListConfigResourcesOptions)`
(`KafkaAdminClient.java:4903+`) enumerates config resources of given types via
`ListConfigResources` — a way to find, e.g., all groups or client-metrics
resources with dynamic configs. Single request to the least-loaded node.
Its precise semantics and the KIP that introduced it are **not verified**.

#### Not covered / not investigated

- `describeShareGroups`, `listShareGroupOffsets`, streams-group APIs (KIP-932 /
  KIP-1071 surface area) — present in 4.1 but **not verified** here.
- `Admin.metrics()` returns the **client's own** metrics, not broker metrics.
- `Admin.clientInstanceId()` (KIP-714) — **not verified**.

---

### Permissions

The table below is taken from the "Security → Authorization and ACLs" protocol
table in `docs/security.html` at the **4.1.0** tag (which is what renders at
`kafka.apache.org/documentation/#operations_resources_and_protocols`), and
cross-checked against `core/src/main/scala/kafka/server/KafkaApis.scala` and
`ConfigHelper.scala` at the same tag. Where docs and source disagree, both are
shown.

| Admin call | Protocol API (key) | Operation | Resource | Verified in source | Notes |
|---|---|---|---|---|---|
| `describeCluster()` (no authorized-ops) | `DESCRIBE_CLUSTER` (60) | docs: `Describe` on `Cluster` | Cluster | `AuthHelper.computeDescribeClusterResponse:150-154` — the `authorize(DESCRIBE, CLUSTER)` call **only guards `clusterAuthorizedOperations`** | **Broker list, cluster id and controller id are returned with no ACL check at all.** Docs overstate the requirement. |
| `describeCluster()` with `includeAuthorizedOperations(true)` | `DESCRIBE_CLUSTER` (60) | `Describe` | Cluster | same | Without it, `clusterAuthorizedOperations` is set to `0` rather than erroring. |
| `listTopics()` | `METADATA` (3) | `Describe` | Topic | `KafkaApis.scala:905-907` `filterByAuthorized(DESCRIBE, TOPIC, …)` | **Per-topic filtering, no error.** Topics you lack `Describe` on are silently absent. An empty list is indistinguishable from an empty cluster. |
| `describeTopics(names)` | `DESCRIBE_TOPIC_PARTITIONS` (75) | `Describe` | Topic | docs table | 4.x path. |
| `describeTopics(ids)` / legacy path | `METADATA` (3) | `Describe` | Topic | `KafkaApis.scala:905` | |
| `describeConfigs(TOPIC)` | `DESCRIBE_CONFIGS` (32) | **`DescribeConfigs`** | Topic | `ConfigHelper.scala:62` `authorize(DESCRIBE_CONFIGS, TOPIC, resource.resourceName)` | **`DescribeConfigs` is a distinct `AclOperation` from `Describe`.** Granting `--operation Describe` does *not* grant it. |
| `describeConfigs(BROKER)` | `DESCRIBE_CONFIGS` (32) | `DescribeConfigs` | Cluster | `ConfigHelper.scala:60` | Docs: "If broker configs are requested, then the broker will check cluster level privileges." |
| `describeConfigs(GROUP)` | `DESCRIBE_CONFIGS` (32) | `DescribeConfigs` | Group | `ConfigHelper.scala:64` | |
| `listOffsets(...)` | `LIST_OFFSETS` (2) | `Describe` | Topic | `KafkaApis.scala:788-789` `partitionSeqByAuthorized(DESCRIBE, TOPIC, …)` | Unauthorized topics come back as `TOPIC_AUTHORIZATION_FAILED` per partition. |
| `describeLogDirs(brokers)` | `DESCRIBE_LOG_DIRS` (35) | `Describe` | **Cluster** | `KafkaApis.scala:2227` `authorize(request.context, DESCRIBE, CLUSTER, CLUSTER_NAME)` | **Confirmed cluster-level.** Docs note: "An empty response will be returned on authorization failure." The client turns that empty response into `CLUSTER_AUTHORIZATION_FAILED` (`KafkaAdminClient.java:3025-3031`). |
| `listGroups()` / `listConsumerGroups()` | `LIST_GROUPS` (16) | `Describe` on `Cluster`, else `Describe` on `Group` per group | Cluster / Group | docs table + `KafkaApis.scala:1343` `hasClusterDescribe` | Docs: "first checks for this cluster level authorization. If none found then it proceeds to check the groups individually… If none of the groups are authorized, then just an empty response will be sent back instead of an error. This operation doesn't return `CLUSTER_AUTHORIZATION_FAILED`." (Per-group path is "applicable from the 2.1 release.") |
| `describeConsumerGroups()` — classic groups | `DESCRIBE_GROUPS` (15) | `Describe` | Group | `KafkaApis.scala:1301` | Unauthorized groups get `GROUP_AUTHORIZATION_FAILED` per group. |
| `describeConsumerGroups()` — KIP-848 `consumer` groups | `CONSUMER_GROUP_DESCRIBE` (69) | **docs say `Read`; source says `Describe`** | Group | `KafkaApis.scala:2645` `authorize(request.context, DESCRIBE, GROUP, groupId)` | **Documented discrepancy — see below.** |
| `listConsumerGroupOffsets()` | `OFFSET_FETCH` (9) | `Describe` on `Group` **and** `Describe` on `Topic` | Group + Topic | docs table | Docs: "the application must have privileges on group and topic level too… it requires describe access instead of read. **Group access is checked first, then Topic access.**" |
| coordinator lookup (implicit) | `FIND_COORDINATOR` (10) | `Describe` | Group | docs table | Issued by `CoordinatorStrategy` under `describeConsumerGroups` / `listConsumerGroupOffsets`. |
| `describeProducers()` | `DESCRIBE_PRODUCERS` (61) | **`Read`** | **Topic** | `KafkaApis.scala:2493` `authorize(request.context, READ, TOPIC, topicRequest.name)` → else `TOPIC_AUTHORIZATION_FAILED` | **The only call here that needs `Read`.** |
| `listTransactions()` | `LIST_TRANSACTIONS` (66) | `Describe` | TransactionalId | `KafkaApis.scala:2570-2576` | Response is **silently filtered** to authorized transactional ids. |
| `describeTransactions()` | `DESCRIBE_TRANSACTIONS` (65) | `Describe` | TransactionalId | docs table | |
| ApiVersions handshake | `API_VERSIONS` (18) | — | — | docs table | "part of the Kafka protocol handshake and happens on connection and before any authentication. Therefore it's not possible to control this with authorization." |

#### The `CONSUMER_GROUP_DESCRIBE` discrepancy

`docs/security.html` at both `4.0.0` (line 2161-2163) and `4.1.0`
(line 2255-2257) tabulates:

```
CONSUMER_GROUP_DESCRIBE (69) | Read | Group
```

but `KafkaApis.handleConsumerGroupDescribe` at 4.1.0 (`:2644-2652`) authorizes:

```scala
consumerGroupDescribeRequest.data.groupIds.forEach { groupId =>
  if (!authHelper.authorize(request.context, DESCRIBE, GROUP, groupId)) { ... GROUP_AUTHORIZATION_FAILED ... }
```

The **source is what runs**, so `Describe` on `Group` is sufficient in 4.1.0.
Which of the two is intended (i.e. whether the docs table or the code is the
bug) is **not verified**. For safety, if you are writing ACL guidance for
customers, grant `Describe` on `Group` and note that some documentation claims
`Read` — and be aware that in 4.x `describeConsumerGroups` attempts
`ConsumerGroupDescribe` **first** for every group, so a mismatch here fails
before the classic-API fallback is reached (the fallback triggers only on
`UNSUPPORTED_VERSION` / `GROUP_ID_NOT_FOUND`, **not** on
`GROUP_AUTHORIZATION_FAILED` —
`DescribeConsumerGroupsHandler.java:352-380`).

#### Can a strictly read-only principal do all of the above?

**Almost — with three exceptions.**

A principal with:

```
Describe        on Cluster        (for describeLogDirs; also unlocks the cluster-wide listGroups shortcut)
Describe        on Topic  '*'     (listTopics, describeTopics, listOffsets, OffsetFetch topic half)
DescribeConfigs on Topic  '*'     (describeConfigs for topics)
Describe        on Group  '*'     (listGroups, describeConsumerGroups, listConsumerGroupOffsets, FindCoordinator)
```

can do capabilities **1–7** in full: brokers, cluster, topics, partitions,
replication, topic configs, log-dir sizes, offsets, groups, members, committed
offsets, lag, and the sampled-throughput derivations. **None of these is
cluster-admin-only in the sense of requiring `Alter`, `ClusterAction`, or any
write operation.** `describeLogDirs` is the only one that needs a *cluster*-scoped
grant, and it needs only `Describe`, not `Alter` — contrast
`alterReplicaLogDirs`, which requires `Alter` on `Cluster`
(`KafkaApis.scala:2232`).

The exceptions:

1. **`describeProducers` requires `Read` on `Topic`.** If "read-only" is
   interpreted as "`Describe` only, never `Read`" — which is the common
   hardened-cluster posture, since `Read` on a topic also permits `Fetch` — then
   `describeProducers` is **out of reach**. A principal granted `Read` on all
   topics can consume all your data.
2. **`listTransactions` / `describeTransactions` require `Describe` on
   `TransactionalId`**, a resource type most read-only ACL templates omit
   entirely. Without it you get an empty list, not an error.
3. **`DescribeConfigs` is a separate operation from `Describe`.** A grant of
   `--operation Describe --topic '*'` does **not** confer it, and the failure is
   a per-resource `TOPIC_AUTHORIZATION_FAILED` inside an otherwise-successful
   `describeConfigs` result. This is the single most likely misconfiguration.

**Silent-partial-result hazards** (all verified above): `listTopics`,
`listGroups`, `listTransactions`, and `describeLogDirs` all degrade to *empty or
filtered* responses on authorization failure rather than raising. A discovery
loop that treats "no topics returned" as "no topics exist" will silently
delete half the graph when an ACL is tightened. Nodqora should treat an
empty result from any of these as **suspicious** and distinguish it from a
verified-empty cluster (e.g. by checking `describeCluster().authorizedOperations()`
where available, or by refusing to prune on an all-empty poll).

---

### Polling cost

#### Fan-out per call — verified against `KafkaAdminClient` at 4.1.0

| Call | Routing | Requests issued | Source |
|---|---|---|---|
| `describeCluster()` | `LeastLoadedBrokerOrActiveKController` | **1** | `:2457-2466` |
| `listTopics()` | `LeastLoadedNodeProvider`, `MetadataRequest.Builder.allTopics()` | **1** (response sized by whole cluster) | `:2102-2110` |
| `describeTopics(names)` | internal `describeCluster()` first, then `DescribeTopicPartitions` with cursor pagination | **1 + ⌈partitions / 2000⌉** | `:2314-2354`, `DescribeTopicsOptions.java:28` |
| `describeConfigs(TOPIC…)` | `nodeFor(resource)` returns **`null`** for `TOPIC` → `LeastLoadedBrokerOrActiveKController` | **1 request, all topic resources batched into it** | `:2714-2735`, `nodeFor` at `:4156-4163` |
| `describeConfigs(BROKER, id)` | `ConstantNodeIdProvider(id)` | **1 per broker asked about** | `nodeFor` returns the broker id for non-default `BROKER` / `BROKER_LOGGER` |
| `describeLogDirs(brokers)` | `ConstantNodeIdProvider(brokerId)` in a loop | **1 per broker**, each asking for **all** partitions (`setTopics(null)`) | `:3001-3040` |
| `listOffsets(map)` | `ListOffsetsHandler extends Batched`, `lookupStrategy = PartitionLeaderStrategy` | **1 `Metadata` lookup** (unless the leader is in `partitionLeaderCache`) **+ 1 `ListOffsets` per leader broker**, all that broker's partitions batched into one request | `:4257-4266`, `ListOffsetsHandler.java:50-114`, `PartitionLeaderStrategy.java:69-75` |
| `listGroups()` / `listConsumerGroups()` | `Metadata` to find all brokers, then `ConstantNodeIdProvider(node.id)` per broker | **1 + N brokers** | `:3468-3500`, `:3631-3660` |
| `describeConsumerGroups(ids)` | `CoordinatorStrategy(CoordinatorType.GROUP)` | **`FindCoordinator` lookups + 1 request per distinct coordinator**, groups sharing a coordinator batched | `:3575-3583`, `DescribeConsumerGroupsHandler.java:75, :117-138` |
| `listConsumerGroupOffsets(specs)` | `CoordinatorStrategy` | same shape: per-coordinator, groups batched | `:3732-3740` |
| `describeProducers(partitions)` | `PartitionLeaderStrategy` | `Metadata` + **1 per leader**, batched | `:4805-4810` |
| `listTransactions()` | `AllBrokersStrategy` | **1 per broker** | `:4861-4867` |

Two batching facts worth stating plainly, since the brief asked:

- **`listOffsets` does batch by leader.** `ListOffsetsHandler` extends `Batched`
  and implements `buildBatchedRequest(int brokerId, Set<TopicPartition> keys)`,
  which groups the keys by topic into a single `ListOffsetsRequest`. So sampling
  the latest offset of 10,000 partitions across 10 brokers is ~10 requests, not
  10,000.
- **`describeTopics` does batch**, in two senses: all requested names go into
  one call, and the response is paginated at
  `partitionSizeLimitPerResponse` (default 2000) **partitions** per response,
  not per topic. `describeConfigs` batches all `TOPIC` resources into a single
  request to one broker, which makes "fetch configs for every topic" surprisingly
  cheap in *request count* — though the response is large.

#### Metadata cost and `metadata.max.age.ms`

- `AdminClientConfig` defines `metadata.max.age.ms` with default
  `5 * 60 * 1000` = **300000 ms / 5 minutes**
  (`AdminClientConfig.java:166`).
- Doc string (`CommonClientConfigs.java:64`): "The period of time in
  milliseconds after which we force a refresh of metadata even if we haven't
  seen any partition leadership changes to proactively discover any new brokers
  or partitions."
- The consumer's default is the same value (`ConsumerConfig.java:456-461` uses
  `CommonClientConfigs.METADATA_MAX_AGE_CONFIG`); its exact numeric default was
  not read out of that `define(...)` block — **not verified** for the consumer
  specifically, though `AdminClientConfig`'s 300000 is verified.

Implication: an AdminClient's cached cluster metadata can be up to 5 minutes
stale for *leadership*, which matters because `listOffsets` routes by leader.
A stale leader yields `NOT_LEADER_OR_FOLLOWER`, which
`PartitionLeaderStrategy.handlePartitionError` treats as retriable and unmaps
for a fresh lookup — correct, but it costs an extra round trip. If Nodqora polls
`listOffsets` frequently on a cluster with active reassignment, lowering
`metadata.max.age.ms` trades background metadata traffic for fewer retries.
Note also that `KafkaAdminClient` keeps its own `partitionLeaderCache`
(`:410`, `:634`) shared by `listOffsets`, `describeProducers`,
`deleteRecords` and `abortTransaction`, so repeated `listOffsets` polls do not
each pay for a metadata lookup.

#### Cost profile of `describeLogDirs` on a large cluster

Broker-side (`ReplicaManager.describeLogDirs`, `:1217-1254`):

- `logManager.allLogs.groupBy(log => log.parentDir)` — allocates a grouping over
  **every log on the broker** (leaders *and* followers), on every request.
- Per log dir: `Files.getFileStore(path)` + `getTotalSpace` + `getUsableSpace` —
  filesystem syscalls, but **O(log dirs)**, typically 1–12, not O(partitions).
- Per partition: `log.size`, which is
  `segments.stream().mapToLong(LogSegment::size).sum()` — in-memory, but a
  stream allocation per partition per request, over however many segments each
  log has.
- The `partitions.contains(log.topicPartition)` filter runs per log; since the
  AdminClient always sends `setTopics(null)` the filter set is "all", so nothing
  is pruned.

Client-side: one `HashMap<TopicPartition, ReplicaInfo>` entry per replica per
log dir per broker. On a cluster with 50 brokers and 100,000 total replicas,
one `describeLogDirs` sweep materialises ~100,000 objects and moves a response
proportional to that across 50 connections.

**There is no documented cost warning** in the Kafka docs or KIP-827 —
**not verified** that one exists. Draw your own conclusion from the shape.

#### Sensible refresh cadence

Grouping the calls by what they cost:

**Cheap enough for a 30 s poll** (constant or small request count, bounded
response):

- `describeCluster()` — 1 request. Could be 30 s or slower; brokers change rarely.
- `listOffsets(latest)` for the partitions you actually display — 1 metadata
  lookup (usually cached) + 1 request per leader broker. This is the workhorse
  for lag and for sampled throughput, and it is genuinely cheap **per broker**,
  though the request grows with the partition count.
- `describeConsumerGroups` + `listConsumerGroupOffsets` for a bounded set of
  groups — per-coordinator batching keeps this to a handful of requests.
- `listGroups()` — N requests, one per broker, but each response is small.

**Too expensive for 30 s on a large cluster** (fan-out × partitions):

- `describeLogDirs` — N brokers × all partitions, always. Poll on the order of
  **minutes**, not seconds. Topic sizes change on segment-roll granularity
  anyway, so a 5–15 minute cadence loses almost nothing. If you also want
  bytes/sec deltas from it, that cadence sets your throughput resolution — which
  is another argument for taking throughput from `listOffsets` deltas instead.
- `describeTopics` over *every* topic — paginated at 2000 partitions per
  response; on a 100k-partition cluster that is ~50 round trips. Partition
  counts, replica assignments and ISR do change (reassignment, broker restart),
  but structural topology is a **minutes**-cadence concern. Consider splitting:
  a slow full sweep plus an event-driven re-describe of topics whose leaders
  moved.
- `describeConfigs` over every topic — one request, but a very large response
  (every config key for every topic, ~40+ entries each). Configs change rarely;
  poll on the order of **minutes to tens of minutes**.
- `describeProducers` over every partition — requires enumerating all partitions
  and needs `Read`. Not a sweep candidate; use on demand for a single topic.
- `listTransactions` — N brokers; small responses; minutes is fine.

A defensible three-tier schedule:

| Tier | Cadence | Calls |
|---|---|---|
| Fast (state) | 30 s | `listOffsets(latest)`, `listConsumerGroupOffsets`, `describeConsumerGroups` |
| Medium (structure) | 5 min | `describeCluster`, `listTopics`, `describeTopics`, `listGroups`, `listTransactions` |
| Slow (bulk) | 15 min | `describeLogDirs`, `describeConfigs`, `listOffsets(earliest)` |

`listOffsets(earliest)` is in the slow tier deliberately: the log start offset
only moves when retention deletes a segment, so sampling it at 30 s is pure
waste — and it doubles the fast-tier request size for no benefit.

---

### What AdminClient cannot give you

Mapped directly onto Nodqora product plan §11.3's discovery list:

| §11.3 item | AdminClient verdict |
|---|---|
| brokers | ✅ full |
| topics | ✅ full |
| partitions | ✅ full |
| replication | ✅ full (replicas, ISR, ELR; under-replication derivable) |
| configurations | ✅ full, with provenance via `ConfigEntry.source()` |
| consumer groups | ✅ full — but member identity only while members are live |
| offsets | ✅ full (committed + earliest/latest) |
| lag | ✅ derived, exactly as `kafka-consumer-groups.sh` does it |
| retention | ✅ full (`retention.ms`, `retention.bytes`, `cleanup.policy`, + source) |
| topic sizes | ✅ via `describeLogDirs`, per-replica bytes, aggregation is yours |
| **throughput** | ⚠️ **only as a sampled derivative** — coarse, unattributed, and non-monotonic in bytes |

#### The explicit list of things that need JMX or Prometheus

Nothing below is obtainable from `Admin` or the consumer API. MBean names are
from `docs/ops.html` at the 4.1.0 tag.

1. **Broker/topic message rate.**
   `kafka.server:type=BrokerTopicMetrics,name=MessagesInPerSec,topic=([-.\w]+)`
   — "Incoming message rate per topic. Omitting 'topic=(...)' will yield the
   all-topic rate."
2. **Broker/topic byte-in rate from clients.**
   `kafka.server:type=BrokerTopicMetrics,name=BytesInPerSec,topic=([-.\w]+)`
   — the docs call it "the metric that monitors the write throughput of
   producers into each broker".
3. **Byte-out rate to clients.**
   `kafka.server:type=BrokerTopicMetrics,name=BytesOutPerSec,topic=([-.\w]+)`
   — "Byte out (to the clients) rate per topic." **AdminClient has no read-side
   signal whatsoever**: `listOffsets` and `describeLogDirs` are both write-side.
   You cannot tell a busy topic with no readers from a busy topic with fifty.
4. **Byte-out rate to other brokers** (replication traffic) — separate MBean;
   indistinguishable from client traffic in any AdminClient figure.
5. **Request rate, per API and version.**
   `kafka.network:type=RequestMetrics,name=RequestsPerSec,request={Produce|FetchConsumer|FetchFollower},version=([0-9]+)`
6. **Error rate.**
   `kafka.network:type=RequestMetrics,name=ErrorsPerSec,request=([-.\w]+),error=([-.\w]+)`
7. **Latency — all of it.**
   `kafka.network:type=RequestMetrics,name=TotalTimeMs,request={Produce|FetchConsumer|FetchFollower}`,
   "broken into queue, local, remote and response send time". There is no p50,
   p95, p99, or mean latency anywhere in AdminClient.
8. **Request queue depth.** `kafka.network:type=RequestChannel,name=RequestQueueSize`.
9. **Purgatory sizes.**
   `kafka.server:type=DelayedOperationPurgatory,name=PurgatorySize,delayedOperation=Fetch`.
10. **Network processor idle fraction.**
    `kafka.network:type=SocketServer,name=NetworkProcessorAvgIdlePercent`.
11. **Consumer-published lag.**
    `kafka.consumer:type=consumer-fetch-manager-metrics,client-id={client-id}`,
    attribute `records-lag-max` — the docs note explicitly this is "**Published
    by the consumer, not broker**". This is a *client-side* metric and is the
    only lag figure that reflects the consumer's true in-flight position rather
    than its last commit.
12. **Per-producer and per-consumer throughput and identity.** Client metrics
    are published by clients, keyed by `client-id`, and are the only route to
    "which application is writing to this topic at what rate". KIP-714 (client
    metrics pushed to the broker) is the standards-track answer and is
    **not verified** here.
13. **Producer→topic edges for non-transactional producers.** Not derivable at
    all from AdminClient (§8). This is the structural gap: Nodqora can build
    topic→consumer-group edges from committed offsets, but topic←producer edges
    require JMX client metrics, OpenTelemetry, Kafka Connect configs (§11.4), or
    manual configuration (§11.1).
14. **Anything historical.** Every AdminClient call is a point-in-time snapshot.
    There is no time series, no retention of past values, no "what was the lag
    an hour ago". Nodqora must store its own samples.


---

## Part 2 — Kafka Connect REST API

### Endpoint matrix

| endpoint | method | gives | cost |
|---|---|---|---|
| `/` | GET | `ServerInfo` — `version`, `commit`, `kafka_cluster_id` | trivial, local |
| `/health` | GET | worker readiness/liveness (KIP-1017, 3.9+) | trivial, local |
| `/connector-plugins` | GET | `PluginInfo[]` — `class`, `type`, `version` | local, cached |
| `/connector-plugins/{p}/config/validate` | PUT | `ConfigInfos` — full ConfigDef + errors | instantiates the plugin; not for polling |
| `/connectors` | GET | connector names | local, in-memory |
| `/connectors?expand=status&expand=info` | GET | every connector's status **and** info in one response | **one HTTP call, N in-process herder lookups** |
| `/connectors/{c}` | GET | `ConnectorInfo` — `name`, `config`, `tasks[]`, `type` | local |
| `/connectors/{c}/config` | GET | the raw config map | local |
| `/connectors/{c}/status` | GET | `ConnectorStateInfo` | local (status store) |
| `/connectors/{c}/tasks` | GET | `TaskInfo[]` — `id`, `config` | local |
| `/connectors/{c}/tasks/{t}/status` | GET | `TaskState` | local |
| `/connectors/{c}/topics` | GET | `ActiveTopicsInfo` — runtime-observed topic names | local (status store) |
| `/connectors/{c}/offsets` | GET | `ConnectorOffsets` (KIP-875, 3.6+) | reads offset store / consumer group |

Removed between versions: `/connectors/{c}/tasks-config` exists in the 3.9 spec
and is **gone in 4.0** (verified by diffing the two OpenAPI documents). Anything
built on it must move to `/connectors/{c}/tasks`.

---

#### 1. Cluster and worker level

`GET /` returns `ServerInfo`:

```json
{ "version": "4.0.0", "commit": "...", "kafka_cluster_id": "..." }
```

`kafka_cluster_id` is the useful field for Nodqora — it ties a Connect cluster
to the Kafka cluster it is attached to, which is how you know a connector node
and a topic node belong in the same Environment.

**There is no endpoint that lists the workers in a Connect cluster.** Confirmed
by enumerating every path in the 4.0 OpenAPI spec — the full set is `/`,
`/health`, `/admin/loggers`, `/admin/loggers/{logger}`, `/connector-plugins`,
`/connector-plugins/{p}/config`, `/connector-plugins/{p}/config/validate`,
`/connectors`, and the per-connector subresources. Worker identity leaks out
only as the `worker_id` field (a `host:port` string) on connector and task
status. To enumerate workers you must union the `worker_id` values across all
connector and task statuses, which only ever shows workers currently hosting
something.

#### 2. Connectors

`GET /connectors/{c}/status` returns `ConnectorStateInfo`. From the 4.0 source
(`ConnectorStateInfo.java`, a Java record) and the OpenAPI schema, the shape is:

```json
{
  "name": "payments-iceberg-sink",
  "connector": { "state": "RUNNING", "worker_id": "10.0.1.7:8083", "trace": null },
  "tasks": [
    { "id": 0, "state": "RUNNING", "worker_id": "10.0.1.7:8083" },
    { "id": 1, "state": "RUNNING", "worker_id": "10.0.1.8:8083" },
    { "id": 2, "state": "FAILED",  "worker_id": "10.0.1.8:8083",
      "trace": "org.apache.kafka.connect.errors.ConnectException: ..." }
  ],
  "type": "sink"
}
```

`type` is a closed enum: `source`, `sink`, `unknown`. The `msg` field present in
the schema is `writeOnly` — it is a request field, never returned.

#### 3. Connector and task state enum

Verified verbatim from `AbstractStatus.State` in the 4.0 source:

```java
public enum State {
    UNASSIGNED,
    RUNNING,
    PAUSED,
    FAILED,
    DESTROYED, // Never visible to users; destroyed Connector and Task instances are not shown
    RESTARTING,
    STOPPED,   // Only ever visible to users for Connector instances; never for Task instances
}
```

Two constraints fall straight out of the source comments: `DESTROYED` never
reaches a client, and `STOPPED` is **connector-only** — a task state will never
be `STOPPED`. So the observable connector states are `UNASSIGNED`, `RUNNING`,
`PAUSED`, `FAILED`, `RESTARTING`, `STOPPED`; observable task states are the same
minus `STOPPED`.

**Can a connector be `RUNNING` while a task is `FAILED`? Yes — verified from
source, not inferred.** `AbstractHerder.connectorStatus()` builds the two halves
from independent records and performs no aggregation whatsoever:

```java
ConnectorStatus connector = statusBackingStore.get(connName);
Collection<TaskStatus> tasks = statusBackingStore.getAll(connName);

ConnectorStateInfo.ConnectorState connectorState = new ConnectorStateInfo.ConnectorState(
        connector.state().toString(), connector.workerId(), connector.trace(), connector.version());
List<ConnectorStateInfo.TaskState> taskStates = new ArrayList<>();
for (TaskStatus status : tasks) {
    taskStates.add(new ConnectorStateInfo.TaskState(status.id().task(),
            status.state().toString(), status.workerId(), status.trace(), status.version()));
}
```

The top-level `connector.state` reflects only the **Connector** object's own
lifecycle — whether the coordinating instance is running — never the health of
the tasks that do the work. A connector whose every task has failed still
reports `RUNNING`. Any health normalization that reads `connector.state` alone
is wrong.

#### 4. Tasks

`GET /connectors/{c}/tasks` returns `TaskInfo[]`, each `{ id: { connector, task }, config }`.
The task config is the connector config as expanded per task, so it does carry
the topic assignment — but it is expanded config, not a runtime assignment, and
for a sink connector it typically repeats the connector-level `topics` value
rather than naming the partitions that task actually owns.

#### 5. Topics actively used by a connector

`GET /connectors/{c}/topics` → `ActiveTopicsInfo`:

```json
{ "payments-es-sink": { "topics": ["payments.events.enriched.v1"] } }
```

This is the endpoint that matters most for edge inference, and it has sharp
edges. From `AbstractHerder`:

```java
public ActiveTopicsInfo connectorActiveTopics(String connName) {
    Collection<String> topics = statusBackingStore.getAllTopics(connName).stream()
            .map(TopicStatus::topic)
            .collect(Collectors.toList());
    return new ActiveTopicsInfo(connName, topics);
}
```

Findings:

- **It is runtime-observed, not config-derived.** The status store is populated
  as tasks actually process records. A connector that is configured but has
  processed nothing — freshly created, `PAUSED` since creation, or a sink on an
  empty topic — returns an **empty** topic list. It cannot be used as the sole
  source of a topic→connector edge, because absence does not mean no
  relationship.
- **Works for sinks and sources alike.** `TOPIC_TRACKING_ENABLE_CONFIG` is read
  by both `WorkerSinkTask` and `AbstractWorkerSourceTask` in the 4.0 source, so
  source connectors register the topics they write to and sinks the topics they
  read from. The response does **not** say which direction — you must get that
  from `type` on the status response.
- **The REST response throws away most of what is stored.** `TopicStatus` holds
  `topic`, `connector`, `task`, and `discoverTimestamp`, but
  `connectorActiveTopics` maps to `TopicStatus::topic` and discards the rest. So
  there is no per-task attribution and **no first-seen timestamp** over REST,
  even though the worker has one.
- **It accumulates and only resets explicitly.** Entries persist until
  `PUT /connectors/{c}/topics/reset`, which is itself gated on
  `topic.tracking.allow.reset`. A connector reconfigured from topic A to topic B
  will report **both** until reset, so a stale edge can outlive the config that
  created it. `DistributedHerder` also resets active topics on connector
  deletion/reconfiguration paths (`resetActiveTopics`).
- Gated by `topic.tracking.enable` on the worker. If an operator has disabled
  it, the endpoint is unavailable and config parsing is the only route.

#### 6. Config as an edge source

For the reference pipeline's two connectors:

- `io.confluent.connect.elasticsearch.ElasticsearchSinkConnector` **(vendor doc
  — Confluent-proprietary, not in apache/kafka)**: input from `topics` /
  `topics.regex`, destination host from `connection.url`. The index name
  defaults to the topic name unless remapped, so the destination node key is
  *derived*, not stated.
- `org.apache.iceberg.connect.IcebergSinkConnector` (Apache Iceberg project
  docs): input from `topics`, destination from `iceberg.tables` (comma-separated)
  plus `iceberg.catalog`.

The important caveat, from the Iceberg connector's own config table:
`iceberg.tables.dynamic-enabled` routes each record to a table named by
`iceberg.tables.route-field` **at runtime**, in which case `iceberg.tables` is
not set at all. There is also `iceberg.table.<table>.route-regex` for
regex fan-out. So for a dynamically-routed connector the destination set is
genuinely not derivable from config — it exists only in the data.

Generalizing: config-derived edges work for the common statically-configured
case and fail for regex/dynamic routing (`topics.regex`, dynamic table routing,
Debezium's `topic.prefix` where topic names are generated per captured table).
Neither config parsing nor `/topics` is complete on its own; they fail in
opposite directions — config misses runtime routing, `/topics` misses
not-yet-active connectors.

#### 7. Config redaction — secrets are returned in plaintext

**`GET /connectors/{c}/config` performs no masking of any kind.** Grepping the
4.0 `AbstractHerder`, `ConnectorsResource`, and `ConnectorInfo` for `PASSWORD`,
`mask`, or `HIDDEN` returns nothing. The stored config is a `Map<String,String>`
served verbatim; `ConfigDef` typing (which is what knows a key is a password) is
never applied on this path.

Externalized secrets are the exception, and they are safe for the right reason.
`worker.configTransformer().transform(...)` is applied only on the
validation/instantiation path in `AbstractHerder`, and `reverseTransform`
explicitly writes variable references *back* over resolved values before configs
are stored:

```java
// Find the config keys in the raw connector config that have variable references
Map<String, String> rawConnConfig = configState.rawConnectorConfig(connName);
Set<String> connKeysWithVariableValues = keysWithVariableValues(rawConnConfig, ConfigTransformer.DEFAULT_PATTERN);
```

So a `${file:/opt/secrets.properties:es-password}` placeholder comes back as the
placeholder. But a password inlined literally into the connector config comes
back as the password.

For Nodqora this is a hard constraint, not a detail: **storing or displaying raw
connector configs means storing and displaying credentials** for any connector
whose operator inlined them. The Apache Connect security page states the same
thing about the config topic — configurations "including any inlined secrets"
live in `config.storage.topic`.

#### 8. Error detail

`trace` is present on both `ConnectorState` and `TaskState` and is a stack trace
string — unbounded length, whatever the failure threw. It reflects the
**current** status record only.

**There is no last-failure timestamp and no failure history over REST.** No
endpoint in the 4.0 spec exposes one, and `ConnectorStateInfo` carries no
timestamp field. When a failed task is restarted and succeeds, the trace is
replaced and the fact that it ever failed is gone. Anything Nodqora wants to say
about "last failure at…" must come from its own polling history.

#### 9. Validation and plugin metadata

`GET /connector-plugins` returns `PluginInfo[]` of `{ class, type, version }`,
where `type` classifies the plugin as source/sink — so a connector *class* can
be classified without an instance. `PUT /connector-plugins/{p}/config/validate`
returns the full `ConfigDef` with per-key definitions and errors, but it
instantiates the plugin to do so; it is a design-time call, not a polling one.

#### 10. Offsets

`GET /connectors/{c}/offsets` (KIP-875, Kafka 3.6+) returns `ConnectorOffsets`:
a list of `{ partition, offset }` pairs, both free-form JSON objects. For a sink
connector the partition object is the Kafka topic-partition and the offset is
the committed offset; for a source connector both are connector-defined
(a Debezium binlog position, a file line number).

Because the shape is connector-defined, it is **not** a general progress signal.
For sinks it duplicates what the AdminClient already gives you against the
connector's consumer group, in a less convenient form; for sources it is opaque
without per-connector knowledge.

---

### Permissions and security

From the Apache Kafka Connect security documentation:

- **The REST API is unauthenticated by default.** "Out of the box, anyone who
  can reach the REST port can create, reconfigure, stop, or delete any
  connector." The listener defaults to `http` on port 8083.
- **There is no read-only mode and no per-endpoint authorization.** "Once a
  caller is allowed onto the REST API it can act on *any* connector — reading
  another connector's configuration and stopping or deleting it." Authentication
  extensions gate the API as a whole, never individual connectors or verbs.
- Authentication options are a reverse proxy, or a REST extension registered via
  `rest.extension.classes` (the built-in `BasicAuthSecurityRestExtension` does
  JAAS HTTP basic auth; the reference `PropertyFileLoginModule` is explicitly
  "not intended for production").
- In distributed mode the REST API is **also** the inter-worker transport — a
  follower forwards to the leader over it — so it is a control channel as well
  as a management interface.
- `admin.listeners` can move or disable the `/admin` endpoints, but this does not
  give a read-only split of the connector endpoints.

The consequence for Nodqora: **there is no way to give the discovery adapter a
read-only Connect credential.** The docs say it outright — "you cannot grant
Connect REST API access to anyone you would not trust as a cluster
administrator." Whatever credential Nodqora holds can also delete every
connector. The mitigation is external: a reverse proxy in front of the workers
that allows only `GET`, with Nodqora pointed at the proxy. This is an
operator-side control Nodqora should document, not something it can enforce.

### Polling cost

- **`expand` collapses N+1 into 1, verified from source.**
  `ConnectorsResource.listConnectors` loops over `herder.connectors()` and calls
  `herder.connectorStatus(connector)` / `herder.connectorInfo(connector)`
  **in-process** — no per-connector HTTP. For 200 connectors, naive polling is
  `1 + 2N` = 401 HTTP requests; `GET /connectors?expand=status&expand=info` is
  **1**. This should be the default polling call.
- **Status reads are local.** `connectorStatus` and `connectorActiveTopics` read
  the worker's in-memory status store, which is a materialized view of the
  compacted `status.storage.topic`. No broker round-trip per request.
- **Write/lifecycle requests forward to the leader**, via
  `HerderRequestHandler.completeOrForwardRequest`, which re-issues the request
  against `forwardUrl` with `?forward=false` to bound recursion. GET status paths
  are served locally, so read-only polling never triggers forwarding.
- `/topics` is one call per connector — there is no expand-style batch form, so
  topic tracking costs N requests if used. Since it changes only when a
  connector starts touching a new topic, it belongs on a much slower cadence
  than status.
- No documented rate limits.

**Cadence.** `GET /connectors?expand=status&expand=info` at 30s is cheap and is
the health signal. `/topics` per connector belongs on the slow topology cadence
(minutes), not the health cadence. `config/validate` should never be polled.

### What the Connect REST API cannot give you

- **Any throughput metric.** No messages/sec, bytes/sec, or records-lag for a
  connector. `source-record-write-rate`, `sink-record-send-rate`,
  `sink-record-lag-max`, `poll-batch-avg-time-ms` and the rest are JMX MBeans
  only, exposed to Prometheus via JMX exporter. Nothing in the REST surface
  approximates them.
- **Per-task metrics** — batch sizes, offset commit latency, error counts from
  the dead-letter-queue reporter. JMX only.
- **Failure history / last-failure time.** Only the current `trace`.
- **A worker inventory.** Only `worker_id` values inferred from status.
- **Rebalance state and generation.** Not exposed.
- **Any historical value at all** — every endpoint is strictly current-state.

---

## Implications for Nodqora

Options and their costs. Nothing here is a recommendation — the choices belong
to [Kafka and Kafka Connect discovery scope](https://github.com/fredskor/nodqora/issues/11)
and [Health normalization model](https://github.com/fredskor/nodqora/issues/9).

### Throughput is a scope decision, not a technical one

§11.3 lists throughput; §14 shows `12k msg/s` on a topic node. Without
Prometheus there are three positions:

| option | cost |
|---|---|
| **Drop throughput from the MVP** | §14's topic-node overlay loses its headline number. Nothing else in the plan depends on it. Honest, and no machinery. |
| **Sampled `listOffsets` derivative** | Gives messages/sec per partition, monotonic and usable. Costs a stored previous sample per partition — the first thing in the MVP that needs history, which cuts against NodeState being a single current-value row (ADR-0003). Wrong immediately after a broker restart or partition reassignment. |
| **Defer to a Prometheus plugin post-MVP** | Keeps the number in the product's vocabulary without building sampling. The `metrics{}` field on NodeState (ADR-0006 shape) already accommodates a later writer. |

The `describeLogDirs` byte-delta variant is worth naming only to rule out: byte
deltas go **negative** on retention deletion, compaction, and replica movement,
so bytes/sec derived this way is not merely coarse but wrong in a way users will
notice.

### Topic size is the one expensive call

`describeLogDirs` is per-broker, unscopeable, and O(partitions per broker). It is
the only call in either API that is genuinely costly at cluster scale.

- **Skip it** — topic size disappears from the node inspector; every other §11.3
  field survives.
- **Poll it on the slow cadence** — it is topology-adjacent (a slow-moving
  number), so it fits `metadata{}` on the Node rather than `metrics{}` on
  NodeState, and a minutes-to-hours cadence makes the cost irrelevant.
- **Poll it on the health cadence** — no reason to, and it is the one way to make
  a 30s discovery loop expensive.

Note the aggregation choice is Nodqora's: `ReplicaInfo.size()` is per-replica
per-broker, so "topic size" is either the sum across all replicas (physical
footprint, ~3× with RF=3) or the sum of leader replicas only (logical size).
These differ by a factor operators will query.

### Connector edges need both routes, or an explicit gap

Since `/topics` and config parsing fail in opposite directions:

| option | cost |
|---|---|
| **Config parsing only** | Deterministic and available immediately, including for a `PAUSED` connector that has never run. Misses `topics.regex` and dynamic table routing. Requires fetching configs, therefore handling credentials (below). Requires per-connector-class knowledge of which keys name the destination — a plugin concern, and an open-ended one. |
| **`/topics` only** | No per-class knowledge needed and it reflects reality. But a connector that has not processed a record yields no edge, stale topics persist until reset, and the direction is not in the response — you must join to `type` from the status call. Unavailable entirely if the operator set `topic.tracking.enable=false`. |
| **Both, with config as the declared edge and `/topics` as confirmation** | Full coverage. Introduces exactly the question ADR-0008 deferred: two sources contributing the same edge, which is [Discovery-engine merge semantics](https://github.com/fredskor/nodqora/issues/12). Also gives a natural home for §35 edge confidence. |

Whichever is chosen, the destination node (`payments-events-v1`,
`analytics.payments_events`) is a *declared* node in the fixture — no adapter
observes it. Connector config parsing is what produces the edge *to* a node
nothing discovers, which is the mixed-graph case (ADR-0005) working as intended.

### Health normalization has one non-negotiable input

For connectors, `tasks[]` — not `connector.state`. The verified source makes
this a correctness requirement rather than a preference. Open shape questions
for #9:

- A connector `RUNNING` with *some* tasks `FAILED` is the plan's own DEGRADED
  example. With *all* tasks failed, DEGRADED and UNHEALTHY are both defensible.
- `PAUSED` and `STOPPED` are distinct states that both plausibly map to
  DISABLED; the fixture's incident scenario maps `PAUSED` → DISABLED.
- `UNASSIGNED` and `RESTARTING` are transient. Mapping them to UNKNOWN makes
  UNKNOWN mean two different things (nothing observes this vs. observed but
  indeterminate) — the fixture already uses UNKNOWN for the first sense on four
  nodes.
- Lag thresholds are per-topic or per-group, and the fixture deliberately has
  one group at 8,400 reading HEALTHY while another at 40,000 reads DEGRADED, so
  a single global threshold does not reproduce it.

The `trace` field gives the inspector's raw signal real content for free, but
there is **no last-failure timestamp anywhere** in the Connect API — if the
inspector should say when something broke, Nodqora stores that itself.

### Credentials posture is an operator-facing constraint

Two facts to surface in whatever the MVP documents:

- Kafka needs only a `Describe` / `DescribeConfigs` principal. Worth stating
  explicitly that `DescribeConfigs` is a **distinct** ACL operation from
  `Describe` — granting `--operation Describe --topic '*'` alone will silently
  yield topics with no configs, and the failure mode is empty results, not an
  error.
- Connect cannot be given read-only access. Nodqora either holds a credential
  that can delete every connector, or the operator fronts Connect with a
  GET-only proxy. This is a deployment note the MVP must not leave implicit —
  and it interacts with the decision to store connector configs, which contain
  whatever secrets the operator inlined.

The silent-empty-result behaviour is the sharpest cross-ticket finding: it means
"absent from this poll" cannot mean "deleted" for #12, and it means an ACL
tightening looks exactly like a decommissioned pipeline.

### Cadence falls naturally into two tiers

The API shapes suggest the same split ADR-0003 already draws between Node and
NodeState:

- **Fast (health):** `describeConsumerGroups` + `listConsumerGroupOffsets` +
  `listOffsets` for lag; `GET /connectors?expand=status&expand=info` — one HTTP
  call for every connector's health. Both cheap enough for 30s.
- **Slow (topology):** `listTopics`, `describeTopics`, `describeConfigs`,
  `GET /connectors/{c}/topics` and config fetches, `describeLogDirs` if topic
  size is in scope. These move `updatedAt`; the fast tier must not.

Nothing in either API forces a different shape, which is a mild confirmation
that ADR-0003's boundary survives contact with the first two real adapters.


---

## Sources

### Kafka AdminClient

#### Apache Kafka javadoc (kafka.apache.org, pages self-identify as Kafka 4.0.2 API)

- https://kafka.apache.org/40/javadoc/org/apache/kafka/clients/admin/Admin.html
- https://kafka.apache.org/40/javadoc/org/apache/kafka/clients/admin/DescribeClusterResult.html
- https://kafka.apache.org/40/javadoc/org/apache/kafka/clients/admin/FeatureMetadata.html
- https://kafka.apache.org/40/javadoc/org/apache/kafka/clients/admin/TopicDescription.html
- https://kafka.apache.org/40/javadoc/org/apache/kafka/common/TopicPartitionInfo.html
- https://kafka.apache.org/40/javadoc/org/apache/kafka/clients/admin/ListTopicsOptions.html
- https://kafka.apache.org/40/javadoc/org/apache/kafka/clients/admin/DescribeTopicsOptions.html
- https://kafka.apache.org/40/javadoc/org/apache/kafka/clients/admin/ConfigEntry.html
- https://kafka.apache.org/40/javadoc/org/apache/kafka/clients/admin/DescribeLogDirsResult.html
- https://kafka.apache.org/40/javadoc/org/apache/kafka/clients/admin/LogDirDescription.html
- https://kafka.apache.org/40/javadoc/org/apache/kafka/clients/admin/ReplicaInfo.html
- https://kafka.apache.org/40/javadoc/org/apache/kafka/clients/admin/OffsetSpec.html
- https://kafka.apache.org/40/javadoc/org/apache/kafka/clients/admin/ListOffsetsResult.ListOffsetsResultInfo.html
- https://kafka.apache.org/40/javadoc/org/apache/kafka/clients/admin/ConsumerGroupDescription.html
- https://kafka.apache.org/40/javadoc/org/apache/kafka/clients/admin/MemberDescription.html
- https://kafka.apache.org/40/javadoc/org/apache/kafka/common/GroupState.html
- https://kafka.apache.org/40/javadoc/org/apache/kafka/common/ConsumerGroupState.html
- https://kafka.apache.org/40/javadoc/org/apache/kafka/clients/admin/ProducerState.html
- https://kafka.apache.org/40/javadoc/org/apache/kafka/clients/admin/DescribeProducersResult.PartitionProducerState.html
- https://kafka.apache.org/40/javadoc/org/apache/kafka/clients/admin/TransactionListing.html
- https://kafka.apache.org/40/javadoc/org/apache/kafka/clients/consumer/KafkaConsumer.html

#### Apache Kafka source, `apache/kafka` at tag `4.1.0` (raw.githubusercontent.com)

Client:
- `clients/src/main/java/org/apache/kafka/clients/admin/Admin.java`
- `clients/src/main/java/org/apache/kafka/clients/admin/KafkaAdminClient.java`
- `clients/src/main/java/org/apache/kafka/clients/admin/AdminClientConfig.java`
- `clients/src/main/java/org/apache/kafka/clients/admin/ConfigEntry.java`
- `clients/src/main/java/org/apache/kafka/clients/admin/ListTopicsOptions.java`
- `clients/src/main/java/org/apache/kafka/clients/admin/DescribeTopicsOptions.java`
- `clients/src/main/java/org/apache/kafka/clients/admin/ListOffsetsOptions.java`
- `clients/src/main/java/org/apache/kafka/clients/admin/internals/ListOffsetsHandler.java`
- `clients/src/main/java/org/apache/kafka/clients/admin/internals/PartitionLeaderStrategy.java`
- `clients/src/main/java/org/apache/kafka/clients/admin/internals/DescribeConsumerGroupsHandler.java`
- `clients/src/main/java/org/apache/kafka/clients/admin/internals/ListConsumerGroupOffsetsHandler.java`
- `clients/src/main/java/org/apache/kafka/clients/consumer/ConsumerConfig.java`
- `clients/src/main/java/org/apache/kafka/clients/consumer/KafkaConsumer.java`
- `clients/src/main/java/org/apache/kafka/clients/consumer/Consumer.java`
- `clients/src/main/java/org/apache/kafka/clients/CommonClientConfigs.java`
- `clients/src/main/java/org/apache/kafka/common/config/TopicConfig.java`
- `clients/src/main/java/org/apache/kafka/common/requests/DescribeConfigsResponse.java`

Broker / server:
- `core/src/main/scala/kafka/server/KafkaApis.scala`
- `core/src/main/scala/kafka/server/AuthHelper.scala`
- `core/src/main/scala/kafka/server/ConfigHelper.scala`
- `core/src/main/scala/kafka/server/ReplicaManager.scala`
- `core/src/main/scala/kafka/cluster/Partition.scala`
- `storage/src/main/java/org/apache/kafka/storage/internals/log/UnifiedLog.java`
- `storage/src/main/java/org/apache/kafka/storage/internals/log/LogSegments.java`
- `group-coordinator/src/main/java/org/apache/kafka/coordinator/group/GroupCoordinatorConfig.java`
- `server-common/src/main/java/org/apache/kafka/server/common/MetadataVersion.java`

Tools:
- `tools/src/main/java/org/apache/kafka/tools/consumer/group/ConsumerGroupCommand.java`

Comparison against tag `4.0.0`:
- `clients/src/main/java/org/apache/kafka/clients/admin/Admin.java` (to confirm `listConsumerGroups` is *not* deprecated at 4.0)
- `docs/security.html`

#### Kafka documentation source (the files that render on kafka.apache.org)

- `docs/security.html` @ `4.1.0` — "Authorization and ACLs", the protocol-API →
  operation → resource table (rows extracted for LIST_OFFSETS, METADATA,
  OFFSET_FETCH, FIND_COORDINATOR, DESCRIBE_GROUPS, LIST_GROUPS, API_VERSIONS,
  DESCRIBE_CONFIGS, DESCRIBE_LOG_DIRS, DESCRIBE_CLUSTER, DESCRIBE_PRODUCERS,
  DESCRIBE_TRANSACTIONS, LIST_TRANSACTIONS, CONSUMER_GROUP_DESCRIBE,
  SHARE_GROUP_*, STREAMS_GROUP_*, DESCRIBE_TOPIC_PARTITIONS)
- `docs/security.html` @ `4.0.0` — same table, for the CONSUMER_GROUP_DESCRIBE comparison
- `docs/design.html` @ `4.1.0` — §4.10 Log Compaction: "Log Compaction Basics",
  "What guarantees does log compaction provide?", "Log Compaction Details"
- `docs/ops.html` @ `4.1.0` — Monitoring section, MBean names

#### KIP wiki (cwiki.apache.org)

- https://cwiki.apache.org/confluence/display/KAFKA/KIP-664%3A+Provide+tooling+to+detect+and+abort+hanging+transactions
  — DescribeProducers / ListTransactions / DescribeTransactions / AbortTransaction, field lists and ACLs
- KIP-827 ("Expose log dirs total and usable space via Kafka API") — read via
  search-result summary only; the page body was **not fetched directly** (the
  URL with the `logdirs` spelling 404s). `totalBytes`/`usableBytes` and
  DescribeLogDirs v4 are independently verified in the 4.1.0 javadoc and source.
- KIP-966 / KIP-1062 (DescribeTopicPartitions pagination, Admin pagination) —
  referenced from search results only, **not fetched directly**; the pagination
  behaviour is independently verified in `KafkaAdminClient` and
  `DescribeTopicsOptions` source.

#### Repository context

- `nodqora-product-plan.md` §11.3 — moved to the private planning repository by ADR-0158

### Kafka Connect

Apache Kafka 4.0 Connect runtime source (apache/kafka):

- `AbstractStatus.java` — the `State` enum
- `AbstractHerder.java` — `connectorStatus`, `connectorActiveTopics`, `reverseTransform`, config transformation
- `ConnectorStateInfo.java` — response record shape
- `ConnectorsResource.java` — `expand` handling
- `HerderRequestHandler.java` — leader forwarding
- `TopicStatus.java`, `ActiveTopicsInfo.java` — topic tracking shape
- `WorkerSinkTask.java`, `AbstractWorkerSourceTask.java` — `TOPIC_TRACKING_ENABLE_CONFIG` use
- `RestServerConfig.java` — topic tracking / reset gating
- `DistributedHerder.java` — `resetActiveTopics`

Generated Connect REST OpenAPI specifications, Kafka 3.9 / 4.0 / 4.3 — endpoint
inventory, schemas, and the 3.9→4.0 removal of `/connectors/{c}/tasks-config`.

Apache Kafka documentation — Kafka Connect Security Model (REST API
authentication, authorization, secrets in configuration).

Apache Iceberg — Kafka Connect sink configuration reference (`iceberg.tables`,
`iceberg.catalog`, dynamic routing).

Confluent documentation **(vendor doc)** — Elasticsearch Sink Connector
configuration (`connection.url`, `type.name`, index naming).

### Nodqora, for cross-reference

- [`CONTEXT.md`](../../CONTEXT.md)
- [`docs/reference-pipeline.md`](../reference-pipeline.md)
- [ADR-0003: topology/runtime state split](../adr/0003-topology-runtime-state-split.md)
- [ADR-0005: logical nodes with backings](../adr/0005-logical-nodes-with-backings.md)
- [ADR-0006: plugin-namespaced metadata](../adr/0006-plugin-namespaced-metadata.md)
- [ADR-0008: per-node provenance](../adr/0008-per-node-provenance.md)
