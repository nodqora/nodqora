# The market demo: a real pipeline to point Nodqora at

Everything in `fixtures/reference-pipeline/` is a recording. This directory is the opposite — a
pipeline that actually runs, on a real Kafka cluster, fed by a real third-party API, so that the
`kafka` and `connect` plugins can be exercised against infrastructure rather than against a
fixture. It exists because of the last line of the root README:

> **No test reaches a real cluster, broker or Connect worker.** […] at the price that "the client
> cannot start" is a class of defect only running against real infrastructure will find. #32 was
> exactly that.

Bringing this up found two more: [#35](https://github.com/fredskor/nodqora/issues/35) and
[#36](https://github.com/fredskor/nodqora/issues/36).

## The pipeline

```text
Coinbase Exchange API                            api.exchange.coinbase.com, no auth, real trades
   │  GET /products/{BTC,ETH}-USD/trades every 5s
   ▼
market-source-btc · market-source-eth            castorm HTTP source connectors
   ▼
market.trades.btc-usd · market.trades.eth-usd    3 partitions each
   ▼
market-aggregator                                Spring Boot + Kafka Streams, 2 replicas
   │  1-minute tumbling windows → OHLC, VWAP, trade count
   ▼
market.ohlc.1m
   ├──▶ market-mongo-sink        ──▶ mongodb      market.ohlc_1m, upserted on (product, windowStart)
   └──▶ market-opensearch-sink   ──▶ opensearch   index market.ohlc.1m, doc id = the record key
```

Eleven nodes and eleven edges, and every one of the four plugins contributes something no other
plugin can see.

## What it costs

Two nodes' worth of homelab: roughly 3 CPU and 6 GiB across Kafka, Connect, OpenSearch, MongoDB,
the console and two replicas of the aggregator. The Coinbase endpoints are public and unauthenticated;
two connectors polling every five seconds is about 0.4 requests a second, well inside the public rate
limit.

## Bringing it up

Requirements: a cluster with a `LoadBalancer` (MetalLB here) and a `local-path` StorageClass,
`helm`, `jq`, and Docker with `buildx`.

```bash
demo/scripts/up.sh          # ~10 minutes, mostly Kafka and OpenSearch starting
demo/scripts/status.sh      # endpoints, connector states, topic ends, lag, document counts
```

The LAN addresses are pinned in the manifests so they survive a rebuild, because
`demo/nodqora/local.yaml` names them literally:

| | |
|---|---|
| Kafka bootstrap | `192.168.0.211:9094` |
| Connect REST | `http://192.168.0.214:8083` |
| OpenSearch | `http://192.168.0.213:9200` |
| Kafka console | `http://192.168.0.215` |

Change them in `demo/k8s/*.yaml` (the `metallb.io/loadBalancerIPs` annotations) and in
`demo/nodqora/local.yaml` together.

Then point Nodqora at it:

```bash
docker start nodqora-db   # or the run command in the root README
./gradlew :nodqora-app:bootRun --args="--spring.config.additional-location=file:./demo/nodqora/local.yaml"
```

`homelab` joins `production` and `staging` in the environment switcher. Within one discovery
cadence — 30s here rather than the shipped 5m:

```bash
curl -s localhost:8080/api/environments/homelab/graph \
  | jq -c '{nodes:(.nodes|length), edges:(.edges|length), plugins:[.plugins[]|{plugin,outcome}]}'
```

```json
{"nodes":11,"edges":11,"plugins":[{"plugin":"yaml","outcome":"COMPLETE"},{"plugin":"kubernetes","outcome":"COMPLETE"},{"plugin":"kafka","outcome":"COMPLETE"},{"plugin":"connect","outcome":"COMPLETE"}]}
```

## What to look at

**The merge.** Every node worth merging carries more than one source, and that is the whole product
in one column:

```text
market.ohlc.1m          [kafka-topic]        sources=yaml+kafka+connect   backings=1
market-mongo-sink       [connect-connector]  sources=yaml+connect         backings=3
market-aggregator       [service]            sources=yaml+kubernetes      backings=3
coinbase-api            [external-api]       sources=yaml                 backings=0
```

`market-mongo-sink`'s three backings are the connector, its task, and the `kafka-connect`
Deployment that `connect` stamped on from `workload:` — while `kubernetes` suppresses that same
Deployment from node emission by exact name. Suppression removes emission, not readability.

**The composed raw signal.** Three plugins, one line, in registry order:

```text
HEALTHY   market-mongo-sink       1 desired / 1 ready; lag 0; RUNNING, 1/1 tasks RUNNING
HEALTHY   market-aggregator       2 desired / 2 ready; lag 50
UNKNOWN   coinbase-api            -
```

`coinbase-api` is `UNKNOWN` and stays that way. Nothing observes a third-party API, and the honest
answer to "is it healthy" is that nobody looked.

**Which edges are declared and which are observed.** Of eleven edges, `connect` infers two and
`yaml` declares nine:

```text
market.ohlc.1m --SOURCES_FROM--> market-mongo-sink        (connect)
market-source-btc --PRODUCES_TO--> market.trades.btc-usd  (yaml)
coinbase-api --SOURCES_FROM--> market-source-btc          (yaml)
```

The two observed edges come from the sinks' `topics` key, which is the only thing ADR-0041 parses.
A **source** connector has no `topics` key — this one writes to `kafka.topic`, the next one to
`topic`, a third to something else — so both of its edges are gaps, and the upstream one doubly so:
no key in any connector config names a third-party HTTP API. That is ADR-0063 in one screen:
`demo/nodqora/topology/homelab/market-demo.yaml` writes down thirteen facts and nothing else.

## Making it say something other than green

```bash
kubectl -n market-demo scale deployment/market-aggregator --replicas=0
curl -X PUT http://192.168.0.214:8083/connectors/market-source-eth/pause
```

Within one health cadence:

```text
DISABLED  market-aggregator   scaled to 0; lag 39
DISABLED  market-source-eth   1 desired / 1 ready; PAUSED, 0/1 tasks RUNNING
```

Both are `DISABLED` rather than red — a pause glyph, not a failure. Intent is declarative in both
cases: `spec.replicas` was written by a human, and so was the pause (ADR-0029). Leave the aggregator
down and the raw topics cross `lag.default: 1000` in about a quarter of an hour, at which point the
topic nodes turn `DEGRADED` — *behind*, never `UNHEALTHY`, because lag means behind, not broken
(ADR-0025).

To see the honesty layer instead, break the connection rather than the pipeline: point
`connect.url` at a port nothing listens on. The connectors keep their last reading, the plugin
reports `FAILED`, and the canvas says so rather than turning anything green.

```bash
kubectl -n market-demo scale deployment/market-aggregator --replicas=2
curl -X PUT http://192.168.0.214:8083/connectors/market-source-eth/resume
```

## The parts

```text
k8s/10-kafka.yaml         Strimzi 1.2 KafkaNodePool + Kafka, KRaft, v1 CRDs, LoadBalancer listener
k8s/11-topics.yaml        three KafkaTopics, 3 partitions each
k8s/20-mongodb.yaml       MongoDB 8 + PVC
k8s/21-opensearch.yaml    OpenSearch 2.19, security plugin off, vm.max_map_count via initContainer
k8s/30-kafka-connect.yaml Connect 3.9 as a plain Deployment; an initContainer downloads the plugins
k8s/40-aggregator.yaml    the Streams service, with the topology.io annotations that do the joining
k8s/50-kafka-ui.yaml      kafbat console — what Nodqora's link templates point at
connectors/*.json         four connector configs, applied with PUT /config so re-running is safe
aggregator/               Spring Boot + Kafka Streams, its own Gradle build, outside the root one
nodqora/local.yaml        the config overlay adding the `homelab` environment
nodqora/topology/homelab/ the yaml plugin's input: the gaps, and only the gaps
scripts/                  up · down · status · connectors · publish-aggregator
```

### Three decisions worth knowing about

**Connect is a plain Deployment, not a Strimzi `KafkaConnect`.** Two reasons. Strimzi builds
connector plugins into an image and needs a registry to push it to, which an initContainer and an
`emptyDir` do not. And Strimzi runs Connect as a `StrimziPodSet`, which ADR-0030 produces no nodes
or backings from — so `connect.workload` would have nothing to point at and every connector would
lose a backing. That is [#36](https://github.com/fredskor/nodqora/issues/36).

**Connect 3.9 against a Kafka 4.3 broker.** The HTTP source connector was last released in 2021;
a 3.x worker is the least adventurous host for it, and old clients against new brokers is the
compatibility direction Kafka supports.

**The aggregator image goes to ttl.sh.** Anonymous, no registry credentials, and it expires 24
hours after the push — `scripts/publish-aggregator.sh` re-pushes under a fresh name and rolls the
Deployment. ttl.sh reads the *tag* as a lifetime, which is why the version lives in the repository
name. For anything longer-lived, push to a registry you own and change one line in that script.

## Tearing it down

```bash
demo/scripts/down.sh
```

The namespace takes the PVCs with it. Strimzi's CRDs are cluster-scoped and are left alone; the
script prints the one-liner that removes them too.
