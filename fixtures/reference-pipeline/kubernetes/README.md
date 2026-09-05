# Recorded Kubernetes objects

`docs/reference-pipeline.md` §5, recorded at the `kubernetes` plugin's own **outbound-client
interface** (ADR-0099). One file per namespace, because a namespace is exactly what one list call
covers, and `RecordedKubernetesApi` replays them through `KubernetesApi` — the same seam the
fabric8 client implements. There is no WireMock, no testcontainers and no envtest.

| file | environment | namespace | scenario |
|---|---|---|---|
| `payments-prod.json` | production | `payments-prod` | §8 baseline |
| `payments-staging.json` | staging | `payments-staging` | §8 baseline |
| `incident/payments-prod.json` | production | `payments-prod` | §8 incident |
| `scaled-to-zero/payments-prod.json` | production | `payments-prod` | ADR-0034's `DISABLED` |

**A scenario is an overlay, not a second recording.** `RecordedKubernetesApi` searches the scenario
directory first and falls through to the baseline, so each subdirectory holds only the file it
actually changes and staging keeps reading the baseline in every scenario. Both overlays here differ
from the baseline by **one number**:

| scenario | change | reads |
|---|---|---|
| `incident` | `enricher-v2` 3 desired / **0** ready | UNHEALTHY (ADR-0025, none ready) |
| `scaled-to-zero` | `enricher-v2` **0** desired / 0 ready | DISABLED (ADR-0034, declared intent) |

A full second copy would be four fifths duplication, and the four fifths is exactly where a
divergence would hide.

**These are the plugin's shape, not Kubernetes' wire format.** The seam returns
`ObservedWorkload` / `ObservedService` / `ObservedIngress`, so the recording is a small readable
file rather than a captured API payload — and it holds exactly the fields the plugin reads today.

`desiredReplicas` and `readyReplicas` are §5's counts, and one seam serves both capabilities:
discovery asks these objects what exists, health asks the same objects how much of it is ready. That
is why a scenario is expressed by changing a number here rather than by stubbing a health method —
every layer between the objects and `/state` runs on the way. §5's `image` column is still absent,
because nothing reads it.

All three fields are **nullable and never defaulted**. `desiredReplicas: 0` is a human turning
something off and reads `DISABLED`; a missing value means we could not read the spec and must
abstain. Defaulting would turn every unreadable object into a deliberate shutdown, which is the one
direction this must not fail in.

Staging is production's objects in `payments-staging`, with `enrich-consumer-staging` as the
enricher's consumer group. §4's drift is the Iceberg branch, which is `connect` and `yaml`; it
touches no Kubernetes object, because both connectors run on the one `kafka-connect` StatefulSet.

**`bootRun` does not read these.** The app's configured `kubernetes` plugin talks to a real
cluster through fabric8, so running the demo without one leaves `kubernetes` `FAILED` and the graph
degrades to what `yaml` alone knows — ADR-0046's retention working as designed. That is the cost
ADR-0099 names: nothing here proves the product can talk to a cluster.
