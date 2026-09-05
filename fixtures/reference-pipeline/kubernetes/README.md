# Recorded Kubernetes objects

`docs/reference-pipeline.md` §5, recorded at the `kubernetes` plugin's own **outbound-client
interface** (ADR-0099). One file per namespace, because a namespace is exactly what one list call
covers, and `RecordedKubernetesApi` replays them through `KubernetesApi` — the same seam the
fabric8 client implements. There is no WireMock, no testcontainers and no envtest.

| file | environment | namespace |
|---|---|---|
| `payments-prod.json` | production | `payments-prod` |
| `payments-staging.json` | staging | `payments-staging` |

**These are the plugin's shape, not Kubernetes' wire format.** The seam returns
`ObservedWorkload` / `ObservedService` / `ObservedIngress`, so the recording is a small readable
file rather than a captured API payload — and it holds exactly the fields the plugin reads today.
§5's replica counts and images are health inputs (ADR-0034) and arrive with slice 3, which extends
these files rather than replacing them.

Staging is production's objects in `payments-staging`, with `enrich-consumer-staging` as the
enricher's consumer group. §4's drift is the Iceberg branch, which is `connect` and `yaml`; it
touches no Kubernetes object, because both connectors run on the one `kafka-connect` StatefulSet.

**`bootRun` does not read these.** The app's configured `kubernetes` plugin talks to a real
cluster through fabric8, so running the demo without one leaves `kubernetes` `FAILED` and the graph
degrades to what `yaml` alone knows — ADR-0046's retention working as designed. That is the cost
ADR-0099 names: nothing here proves the product can talk to a cluster.
