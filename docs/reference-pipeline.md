# The Nodqora Reference Pipeline

The project's single canonical example (product plan §65). Every MVP decision —
domain model, YAML format, discovery scope, health normalization, API shape,
canvas and inspector UX — is tested against this pipeline. If a design can't
express what's on this page, the design is wrong.

This document is **format-agnostic on purpose**. It states *what exists*, not
how it is serialized: the YAML topology schema is decided separately, and this
document is the content that schema must be able to express. Relationship types
below are named from the product plan's §8.3 vocabulary as *candidates*; pinning
the canonical subset and edge direction belongs to the domain-model work.

Fixed by issue [#2](https://github.com/fredskor/nodqora/issues/2).

---

## 1. Shape

```text
stripe-webhooks                          (external, declared)
      │ calls
      ▼
payments-api                             (k8s Deployment)
      │ produces to
      ▼
payments.events.raw.v1                   (Kafka topic)
      │ consumed by
      ▼
payments-enricher                        (k8s Deployment "enricher-v2")
      │ produces to
      ▼
payments.events.enriched.v1              (Kafka topic)
      │
      ├── payments-es-sink ────────────► payments-events-v1
      │   (Connect connector)            (Elasticsearch index, declared)
      │
      └── payments-iceberg-sink ───────► analytics.payments_events
          (Connect connector)            (Iceberg table, declared)
                                                │ queried by
                                                ▼
                                         analytics (Trino, declared)
```

Ten nodes in `production`, seven in `staging`.

**Connectors are nodes, not edges.** A connector has its own health (task
counts), its own owner (a different team from the services), and its own action
links (§21 lists "Kafka Connect" as a node type). An edge can carry none of
that.

---

## 2. Node inventory

`disc` = how the node enters the graph: **K8s**, **Kafka**, **Connect** (all
discovered) or **declared** (YAML only, no adapter).

| id | type | display name | disc | prod | staging |
|---|---|---|---|---|---|
| `stripe-webhooks` | external-api | Stripe Webhooks | declared | ✓ | ✓ |
| `payments-api` | service | payments-api | K8s | ✓ | ✓ |
| `payments.events.raw.v1` | kafka-topic | payments.events.raw.v1 | Kafka | ✓ | ✓ |
| `payments-enricher` | service | payments-enricher | K8s | ✓ | ✓ |
| `payments.events.enriched.v1` | kafka-topic | payments.events.enriched.v1 | Kafka | ✓ | ✓ |
| `payments-es-sink` | connect-connector | payments-es-sink | Connect | ✓ | ✓ |
| `payments-iceberg-sink` | connect-connector | payments-iceberg-sink | Connect | ✓ | — |
| `payments-events-v1` | elasticsearch-index | payments-events-v1 | declared | ✓ | ✓ |
| `analytics.payments_events` | iceberg-table | analytics.payments_events | declared | ✓ | — |
| `trino-analytics` | query-engine | analytics (Trino) | declared | ✓ | — |

Four of ten nodes are **declared, not discovered**. The graph is permanently
mixed; no MVP feature may assume every node has an adapter behind it.

---

## 3. Edges

| from | to | candidate §8.3 type | prod | staging |
|---|---|---|---|---|
| `stripe-webhooks` | `payments-api` | CALLS | ✓ | ✓ |
| `payments-api` | `payments.events.raw.v1` | PRODUCES_TO | ✓ | ✓ |
| `payments-enricher` | `payments.events.raw.v1` | CONSUMES_FROM | ✓ | ✓ |
| `payments-enricher` | `payments.events.enriched.v1` | PRODUCES_TO | ✓ | ✓ |
| `payments-es-sink` | `payments.events.enriched.v1` | SOURCES_FROM | ✓ | ✓ |
| `payments-es-sink` | `payments-events-v1` | WRITES_TO | ✓ | ✓ |
| `payments-iceberg-sink` | `payments.events.enriched.v1` | SOURCES_FROM | ✓ | — |
| `payments-iceberg-sink` | `analytics.payments_events` | WRITES_TO | ✓ | — |
| `trino-analytics` | `analytics.payments_events` | QUERIES | ✓ | — |

Nine edges in production, six in staging. Note that data flow and edge
direction disagree for `CONSUMES_FROM`, `SOURCES_FROM` and `QUERIES` — the
consumer is the subject. Reconciling that is domain-model work; the fixture
just records both facts.

---

## 4. Environments

| | production | staging |
|---|---|---|
| Kubernetes namespace | `payments-prod` | `payments-staging` |
| Kafka cluster | `kafka-prod.internal:9092` | `kafka-staging.internal:9092` |
| Connect cluster | `connect-prod.internal:8083` | `connect-staging.internal:8083` |
| Nodes | 10 | 7 |
| Edges | 9 | 6 |

**Staging drift** — staging lacks the entire Iceberg branch:
`payments-iceberg-sink`, `analytics.payments_events`, `trino-analytics`. This is
the product plan's own §23 example ("Production has connector X. Staging does
not.") made concrete, and it is what makes environment switching visibly
non-trivial.

Everything below describes **production** unless stated. Staging is identical
minus the Iceberg branch, and all-healthy.

---

## 5. Kubernetes objects

Namespace `payments-prod`.

| kind | name | replicas (baseline) | image |
|---|---|---|---|
| Deployment | `payments-api` | 3 desired / 3 ready | `ghcr.io/acme/payments-api:1.4.2` |
| Deployment | `enricher-v2` | 3 desired / 2 ready | `ghcr.io/acme/payments-enricher:2.0.1` |
| StatefulSet | `kafka-connect` | 2 desired / 2 ready | `confluentinc/cp-kafka-connect:7.6.0` |
| Service | `payments-api` | — | ClusterIP → Deployment `payments-api` |
| Ingress | `payments-api` | — | `payments.acme.io/webhooks/stripe` |

### The deliberate mismatch

`payments-api` matches its Deployment by name — the happy path.

**`payments-enricher` does not.** Its Deployment is named `enricher-v2` and its
Kafka consumer group is `enrich-consumer-prod`: three different strings for one
logical service. Resolution must come from annotations, not string equality:

```yaml
# on Deployment enricher-v2
topology.io/service:    payments-enricher
topology.io/owner:      payments-platform
topology.io/repository: github.com/acme/payments-enricher
topology.io/grafana:    payments-enricher-overview
```

`kafka-connect` hosts **both** connectors — one workload, two connector nodes.
The mapping from workload to connector is not one-to-one.

This mismatch is the fixture's most important feature. Identity resolution that
only works on the `payments-api` case is not identity resolution.

---

## 6. Kafka objects

Topics:

| topic | partitions | replication | retention |
|---|---|---|---|
| `payments.events.raw.v1` | 12 | 3 | 7d |
| `payments.events.enriched.v1` | 12 | 3 | 30d |

Consumer groups:

| group | consumes | belongs to | members | baseline lag |
|---|---|---|---|---|
| `enrich-consumer-prod` | `payments.events.raw.v1` | `payments-enricher` | 2 | 40,000 |
| `connect-payments-es-sink` | `payments.events.enriched.v1` | `payments-es-sink` | 3 | 120 |
| `connect-payments-iceberg-sink` | `payments.events.enriched.v1` | `payments-iceberg-sink` | 2 | 8,400 |

Three groups, three different naming conventions — one bespoke, two
Connect-generated (`connect-` prefix). Group-to-node attribution cannot rely on
a single rule.

---

## 7. Kafka Connect objects

Cluster `connect-prod.internal:8083`, hosted on StatefulSet `kafka-connect`.

| connector | class | state | tasks (baseline) |
|---|---|---|---|
| `payments-es-sink` | `io.confluent.connect.elasticsearch.ElasticsearchSinkConnector` | RUNNING | 3/3 RUNNING |
| `payments-iceberg-sink` | `org.apache.iceberg.connect.IcebergSinkConnector` | RUNNING | 2/3 RUNNING, 1 FAILED |

Config fields the graph reads (topic and destination come from connector config,
not from any Kubernetes object):

```properties
# payments-es-sink
topics                    = payments.events.enriched.v1
connection.url            = https://es-prod.internal:9200
type.name                 = _doc
# → writes index payments-events-v1

# payments-iceberg-sink
topics                    = payments.events.enriched.v1
iceberg.catalog           = analytics
iceberg.tables            = analytics.payments_events
```

A connector at `RUNNING` with a `FAILED` task is the product plan's own §13
DEGRADED example. It is in the baseline deliberately: the top-level connector
state lies, and health normalization must look at tasks.

---

## 8. Health scenarios

Two named scenarios. **Baseline** is the default and exercises all five §13
normalized states at once. **Incident** is for demos and for designing the
canvas's degraded rendering.

### Baseline (production)

| node | raw signal | normalized |
|---|---|---|
| `stripe-webhooks` | no adapter | UNKNOWN |
| `payments-api` | 3 desired / 3 ready | HEALTHY |
| `payments.events.raw.v1` | group lag 40,000 | DEGRADED |
| `payments-enricher` | 3 desired / 2 ready; lag 40,000 | DEGRADED |
| `payments.events.enriched.v1` | max group lag 8,400 | HEALTHY |
| `payments-es-sink` | RUNNING, 3/3 tasks RUNNING | HEALTHY |
| `payments-iceberg-sink` | RUNNING, 1 task FAILED | DEGRADED |
| `payments-events-v1` | no adapter | UNKNOWN |
| `analytics.payments_events` | no adapter | UNKNOWN |
| `trino-analytics` | no adapter | UNKNOWN |

Staging under baseline: all seven nodes HEALTHY or UNKNOWN, no lag.

DISABLED does not occur in baseline — see incident.

### Incident (production)

| node | raw signal | normalized |
|---|---|---|
| `payments-enricher` | 3 desired / 0 ready, `CrashLoopBackOff` | UNHEALTHY |
| `payments.events.raw.v1` | group lag 2,100,000, growing | DEGRADED |
| `payments.events.enriched.v1` | no new records for 20m | DEGRADED |
| `payments-es-sink` | connector `PAUSED` | DISABLED |
| `payments-iceberg-sink` | connector `PAUSED` | DISABLED |
| `payments-api` | 3 desired / 3 ready | HEALTHY |
| all declared nodes | no adapter | UNKNOWN |

The incident's shape matters: a single failing workload mid-pipeline, healthy
upstream, stalled downstream. The graph should make the blast direction obvious
without a blast-radius feature.

Thresholds (lag limits especially) are configurable per the product plan; the
numbers above are the fixture's values, not product defaults.

---

## 9. Ownership and links

**Two teams.** The pipeline crosses a team boundary at the connectors — which is
the entire reason the product exists.

| team | owns | channel | on-call |
|---|---|---|---|
| `payments-platform` | `payments-api`, `payments-enricher`, both topics | `#payments-oncall` | Payments SRE |
| `data-platform` | both connectors, ES index, Iceberg table, Trino | `#data-platform` | Data Platform SRE |

Population is **deliberately uneven** — one fully populated node, most partial,
one bare. The inspector must be designed against both extremes.

| node | owner | repo | runbook | docs | links |
|---|---|---|---|---|---|
| `stripe-webhooks` | — | — | — | — | *(none — empty-state case)* |
| `payments-api` | payments-platform | `github.com/acme/payments-api` | `wiki/runbooks/payments-api` | ✓ | Grafana, Logs, Argo CD, Workload, Pods |
| `payments.events.raw.v1` | payments-platform | — | — | — | Grafana (topic dashboard) |
| `payments-enricher` | payments-platform | `github.com/acme/payments-enricher` | — | — | Grafana, Workload, Pods, Logs |
| `payments.events.enriched.v1` | payments-platform | — | — | — | Grafana (topic dashboard) |
| `payments-es-sink` | data-platform | `github.com/acme/connect-config` | `wiki/runbooks/connect-sinks` | — | Connect UI, Logs, Config |
| `payments-iceberg-sink` | data-platform | `github.com/acme/connect-config` | `wiki/runbooks/connect-sinks` | — | Connect UI, Logs, Config |
| `payments-events-v1` | data-platform | — | — | — | Kibana |
| `analytics.payments_events` | data-platform | — | — | — | *(none)* |
| `trino-analytics` | data-platform | — | — | — | *(none)* |

`payments-api` is the **full** case: every §20 ownership field and every §21
service link. `stripe-webhooks` is the **bare** case: a node with a name, a type
and nothing else. Both must render well.

---

## 10. What this fixture is designed to stress

Each property below exists on purpose. Removing one removes a design constraint.

| property | forces |
|---|---|
| 4 of 10 nodes have no adapter | the graph is permanently mixed; UNKNOWN is normal, not an error state |
| `enricher-v2` ≠ `payments-enricher` ≠ `enrich-consumer-prod` | annotation-based identity resolution; string equality is insufficient |
| one workload hosts two connectors | workload-to-node mapping is not one-to-one |
| three consumer-group naming conventions | group attribution needs more than one rule |
| staging is missing 3 nodes | environment switching changes the graph, not just a label |
| connector `RUNNING` with a `FAILED` task | health normalization reads tasks, not top-level state |
| all five §13 states in one snapshot | the canvas needs five visually distinct treatments |
| `stripe-webhooks` has no metadata at all | the inspector needs a designed empty state |
| two owning teams | ownership is a graph property, not a global setting |
| realistic name lengths (`payments.events.enriched.v1`) | canvas label truncation and search disambiguation are real problems |
| two similar names (`payments-enricher`, `payments-events-v1`) | search must disambiguate, not just substring-match |
| the fan-out at `payments.events.enriched.v1` | layout must handle branching; downstream traversal must handle >1 path |
| Trino sits two hops past the last discovered node | traversal depth limits must count declared nodes too |
