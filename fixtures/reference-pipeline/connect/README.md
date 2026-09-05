# Recorded Kafka Connect objects

`docs/reference-pipeline.md` §7, recorded at the `connect` plugin's own **outbound-client
interface** (ADR-0099). One file per cluster, named for the host in the configured `url`, because a
cluster is exactly what one `GET /connectors` covers — and `RecordedConnectApi` replays it through
`ConnectApi`, the same seam `HttpConnectApi` implements. There is no WireMock and no testcontainers.

| file | environment | cluster | scenario |
|---|---|---|---|
| `connect-prod.internal.json` | production | `connect-prod.internal:8083` | §8 baseline |
| `connect-staging.internal.json` | staging | `connect-staging.internal:8083` | §8 baseline |
| `incident/connect-prod.internal.json` | production | `connect-prod.internal:8083` | §8 incident |

**A scenario is an overlay, not a second recording.** The incident file changes both in-scope
connectors from `RUNNING` to `PAUSED` and nothing else.

## One entry carries both expansions

The plugin asks two questions of one cluster — `?expand=info` on the five-minute loop and
`?expand=status` on the thirty-second one — so each connector is recorded once with both answers.
Two lists could disagree about which connectors exist; Connect's own `expand` form is keyed by
connector name for the same reason.

## The list is the cluster's, not the plugin's scope

**Four connectors are recorded and two become nodes.** ADR-0090's scope rule is the thing worth
testing, so the recording holds what the cluster would actually return:

| connector | fate | why |
|---|---|---|
| `payments-es-sink` | node | matches the `payments-` include prefix |
| `payments-iceberg-sink` | node (production only) | §4's drift is the Iceberg branch |
| `payments-debug-reprocessor` | removed | `ignore`, by exact name (ADR-0031's escape hatch) |
| `orders-jdbc-source` | removed | another team's prefix, on a shared cluster |

Without the include prefix the plugin's scope is the whole cluster, which is every team's connectors
on the payments canvas. There is no default and startup fails without one.

## The two things §7 exists to prove

**`payments-iceberg-sink` is `RUNNING` with one `FAILED` task.** Research #5 verified from
`AbstractHerder.connectorStatus()` that the top-level state performs no aggregation over tasks, so
reading `connector.state` alone renders this connector HEALTHY — a correctness bug, not a
simplification. It reads **DEGRADED** because health reads `tasks[]` (ADR-0025).

**`payments-events-v1` is not in `payments-es-sink`'s config, and that is the point.**
`connection.url` is the Elasticsearch *cluster* endpoint; the sink derives its index from the topic
name. So the destination is knowable to a human reading §7 and **not** knowable to a plugin reading
this file, which is why ADR-0041 has `connect` parse the `topics` key alone and leaves both
`WRITES_TO` edges to YAML. `iceberg.tables` is recorded as the contrasting case — the node key,
stated verbatim, and still not parsed, because it is a third party's vocabulary.

The failing task's `trace` is a real-shaped multi-line stack trace. ADR-0028 takes its **first line
only**, bounded, into `rawSignal` and stores none of it: there is no failure timestamp anywhere in
the Connect API, and both connector nodes already link to the Connect UI and to Logs.

**`bootRun` does not read these.** The app's configured `connect` plugin talks to a real cluster over
HTTP, so running the demo without one leaves `connect` `FAILED` and the two `SOURCES_FROM` edges
absent — ADR-0046's retention working as designed.
