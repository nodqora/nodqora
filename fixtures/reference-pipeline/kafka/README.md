# Recorded Kafka objects

`docs/reference-pipeline.md` §6, recorded at the `kafka` plugin's own **outbound-client interface**
(ADR-0099). One file per cluster, named for the host in the configured `bootstrap`, because a
cluster is exactly what one `listTopics` call covers — and `RecordedKafkaApi` replays it through
`KafkaApi`, the same seam `AdminClientKafkaApi` implements. There is no WireMock, no testcontainers
and no embedded broker.

| file | environment | cluster | scenario |
|---|---|---|---|
| `kafka-prod.internal.json` | production | `kafka-prod.internal:9092` | §8 baseline |
| `kafka-staging.internal.json` | staging | `kafka-staging.internal:9092` | §8 baseline |
| `incident/kafka-prod.internal.json` | production | `kafka-prod.internal:9092` | §8 incident |

**A scenario is an overlay, not a second recording**, exactly as for `kubernetes/`. The incident
file differs from the baseline in `enrich-consumer-prod`'s three end offsets and nothing else.

## The lists are the cluster's, not the plugin's scope

`topics[]` is what `listTopics(listInternal=false)` returns, **including the topics the prefix scope
rejects**. That is deliberate: ADR-0037's prefix rule is the thing worth testing, and a recording
that had already applied it would prove nothing.

| topic | fate | why |
|---|---|---|
| `payments.events.raw.v1` | in scope | matches `payments.` |
| `payments.events.enriched.v1` | in scope | matches `payments.` |
| `connect-offsets` | removed | `ignore`, by exact name. `listInternal=false` does **not** hide it — Connect's `connect-*` topics are ordinary topics as far as Kafka is concerned (ADR-0037) |
| `orders.events.v1` | removed | another team's prefix, on a shared cluster |

`groups[]` is the same shape: `orders-consumer-prod` sits in the file and is **never read**, because
nothing in the environment carries a backing naming it. ADR-0040 has the plugin read exactly the
*routed* set, and `listGroups` is never called — so a group nobody attributed is invisible, which is
the property this entry exists to demonstrate.

## Offsets, not lag

Each partition records `committedOffset` beside `endOffset` rather than the difference. ADR-0025
attaches two caveats to lag and both live **above** this seam, where they can be tested: a partition
with no committed offset is *skipped* rather than counted as zero, and a committed offset compared
against a watermark sampled a moment earlier legitimately goes *negative* and clamps to zero. A
recording that had already subtracted would have applied both silently.

§6's "baseline lag" column is the **max over the group's partitions** (ADR-0025), which is the
number the recording is built to produce:

| group | topic | max lag | reads |
|---|---|---|---|
| `enrich-consumer-prod` | `payments.events.raw.v1` | 40,000 | DEGRADED at a 10,000 threshold |
| `connect-payments-es-sink` | `payments.events.enriched.v1` | 120 | HEALTHY |
| `connect-payments-iceberg-sink` | `payments.events.enriched.v1` | 8,400 | HEALTHY |

Under the incident `enrich-consumer-prod` reaches **2,100,000** and still reads DEGRADED, never
UNHEALTHY. That is ADR-0025 working rather than a threshold that needs raising: *lag means behind,
not broken*. Both sink groups are unchanged, because under the incident nothing produces to
`enriched.v1` and both sinks are paused — so their lag is frozen at its baseline and the topic reads
HEALTHY, which is §8's own amended row.

Three partitions per group rather than twelve. The plugin aggregates with `max`, so the twelfth row
would change no answer and hide the three that do.

**`bootRun` does not read these.** The app's configured `kafka` plugin talks to a real broker through
`AdminClient`, so running the demo without one leaves `kafka` `FAILED` and the graph degrades to what
the other plugins know — ADR-0046's retention working as designed.
