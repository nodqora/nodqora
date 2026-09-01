# Nodqora — Product Vision and Implementation Plan

## 1. Working Concept

**Working product name: Nodqora**

Nodqora is a technology-agnostic operational topology platform. The name is intentionally a working/demo name and can be changed later without affecting the product architecture or domain model.

Build a platform that gives engineering and operations teams a **live, clickable map of a distributed system**.

The product should show how APIs, services, Kafka topics, Kafka Connect connectors, Kubernetes workloads, workflows, databases, object stores, search indexes, Iceberg tables, queues, external APIs, and other infrastructure components are connected.

The key idea is not to replace tools such as Kubernetes dashboards, Grafana, Kibana, Kafka UI, Argo CD, Conductor, Temporal, Trino, or cloud consoles. Instead, the product becomes the **navigation, topology, lineage, and operational context layer above them**.

A user should be able to open a pipeline or system map, understand the complete flow at a glance, see the live state of every component, click any node, and immediately jump to the most relevant operational tool.

Long term, the same graph should support impact analysis, root-cause investigation, data lineage, dependency exploration, incident debugging, change risk analysis, and AI-assisted troubleshooting.

---

## 2. Core Product Statement

> A live operational graph for distributed systems that connects services, data pipelines, infrastructure, messaging, storage, workflows, observability, and developer tooling in one interactive map.

Alternative positioning language:

- Operational lineage for distributed systems
- Google Maps for production architecture
- Live architecture map for engineering teams
- Interactive dependency and data-flow graph
- Operational context layer for modern infrastructure

The preferred conceptual term is **Operational Topology** or **Operational Lineage**.

For planning, demos, repository naming, screenshots, and Wayfinder sessions, the working product name is **Nodqora**. Product-facing language may use **Nodqora — Live Operational Topology for Distributed Systems**.

---

## 3. Problem

Modern systems are spread across many technologies and operational interfaces.

A single business flow may look like:

```text
Third-party API
    ↓
Ingress/API Service
    ↓
Kafka Topic
    ↓
Processing Service on Kubernetes
    ↓
Kafka Topic
    ├── Kafka Connect Sink → Elasticsearch
    └── Kafka Connect Sink → Iceberg
            ↓
          Trino
```

The information required to understand and operate this flow is fragmented across tools:

- Kubernetes dashboard / k9s / Lens / Headlamp
- Kafka UI
- Kafka Connect REST API or UI
- Grafana
- Prometheus
- Loki / Elasticsearch logs
- Kibana
- Argo CD
- GitHub / GitLab
- Conductor / Temporal / Airflow
- Iceberg catalog
- Trino
- object storage
- cloud provider consoles
- custom internal systems

During development or incidents, engineers repeatedly need to answer questions such as:

- What produces this Kafka topic?
- What consumes it?
- Which service owns this component?
- Where does this data go next?
- Which Kubernetes deployment implements this processing step?
- Which connector writes this topic into Elasticsearch?
- Is the connector healthy?
- Is consumer lag increasing?
- Which dashboard belongs to this service?
- Where are the logs?
- What systems will be affected if this component fails?
- What is the end-to-end path from source API to final storage?
- Why is data not reaching its destination?

The answers often exist, but they are spread across multiple interfaces and tribal knowledge.

Static architecture diagrams help only partially because they become outdated and usually contain no live operational state.

---

## 4. Product Goal

The product should become the first place an engineer opens when trying to understand or investigate a distributed system.

It should provide:

1. **Architecture understanding** — what exists and how components connect.
2. **Operational status** — whether each component is healthy now.
3. **Navigation** — direct links into the real operational systems.
4. **Data flow** — how information moves through the platform.
5. **Dependency analysis** — upstream and downstream relationships.
6. **Blast-radius analysis** — what is affected by a failure or change.
7. **Ownership context** — team, repository, runbook, dashboard, alerts.
8. **Historical context** — what changed and when.
9. **AI-assisted diagnosis** — reasoning over the topology and current telemetry.

---

## 5. Product Principles

### 5.1 Do not replace existing operational tools

The platform should integrate with existing tools instead of trying to reproduce them.

Examples:

- Kubernetes node → open Headlamp / Lens / Kubernetes dashboard
- service node → open Grafana dashboard
- service node → open logs
- Kafka topic → open Kafka UI
- Elasticsearch node → open Kibana
- Argo CD node → open application
- repository link → open GitHub/GitLab
- workflow node → open Conductor/Temporal

The platform owns **context and relationships**.

Other tools own deep operational interaction.

### 5.2 Technology-agnostic by design

The platform core must never be architected around Kubernetes, Kafka, Elasticsearch, Iceberg, or any other specific technology or vendor. Those technologies are **initial integrations**, not foundational domain concepts.

The core product should understand generic concepts such as:

```text
Node
Edge
Relationship
Health
Metric
Action
Link
Event
Capability
```

Technology-specific knowledge belongs behind adapters/plugins. A Kafka plugin knows what a topic, consumer group, partition, and connector mean. A Kubernetes plugin knows what a Deployment, StatefulSet, Service, and namespace mean. A future Snowflake, Databricks, NATS, Pulsar, Oracle, Nomad, Ray, Milvus, or proprietary internal-system plugin should be able to join the same graph without requiring changes to the platform core.

The long-term goal is an **open topology platform** where any system that can expose components, relationships, state, metrics, events, actions, or links can participate in the graph.

This is a non-negotiable architecture principle. New integrations should normally be implemented by extending the plugin surface rather than by introducing technology-specific behavior into the core.

### 5.3 Graph first

The graph is the core product abstraction.

Everything becomes:

```text
Node + Relationships + State + Metadata + Actions
```

### 5.4 Progressive automation

Manual configuration should always be possible.

Automatic discovery should improve the graph, not be required for the graph to work.

### 5.5 Useful without AI

The graph itself must deliver clear value before AI features are added.

### 5.6 Read-only by default

The initial platform should be safe to deploy in production environments.

Navigation and observability should come before operational write actions.

---

# 6. Primary Users

## 6.1 Platform Engineers

Need to understand service dependencies, infrastructure relationships, ownership, deployments, and incidents.

## 6.2 Backend Engineers

Need to understand what consumes and produces data, where services run, and how to debug production flows.

## 6.3 Data Engineers

Need cross-platform lineage between Kafka, stream processors, Iceberg, object storage, Trino, databases, and search systems.

## 6.4 SRE / Operations Engineers

Need fast incident navigation, topology-aware health information, dependency analysis, and blast radius.

## 6.5 New Team Members

Need an explorable live architecture instead of relying on outdated diagrams and tribal knowledge.

## 6.6 Technical Leads and Architects

Need a high-level system view with the ability to drill into implementation details.

---

# 7. Core UX

## 7.1 Home Screen

Possible entry points:

- Systems
- Pipelines
- Domains
- Environments
- Teams
- Recently viewed
- Incidents
- Search

Example:

```text
Production

Audio Pipeline
Payments Pipeline
Analytics Platform
Customer Events
Search Platform
```

---

## 7.2 Pipeline Canvas

Example:

```text
External API
    │
    │ 32 req/s
    ▼
Kafka: incoming-audio
    │
    │ 2.1k msg/s
    │ lag: 4,211
    ▼
audio-processing-service
K8s: 8/8 healthy
    │
    ▼
Kafka: processed-audio
    │
    ├──────────────────────┐
    │                      │
    ▼                      ▼
Iceberg Sink            Elastic Sink
RUNNING                 RUNNING
    │                      │
    ▼                      ▼
Iceberg Table           Elasticsearch Index
```

The canvas must support:

- pan
- zoom
- fit-to-screen
- minimap
- collapse/expand subgraphs
- filter by component type
- filter by environment
- filter by health
- search
- upstream/downstream highlighting
- path highlighting
- layout switching

Possible layouts:

- left-to-right pipeline
- top-down pipeline
- force-directed graph
- hierarchical dependency graph
- swimlane by technology
- swimlane by team/domain

---

## 7.3 Node Inspector

Clicking a node opens a side panel.

Example for a service:

```text
audio-processing-service

Status: Healthy
Environment: Production
Namespace: audio
Version: 2.17.4
Pods: 8/8
CPU: 63%
Memory: 71%
Owner: Audio Platform

Consumes
- incoming-audio

Produces
- processed-audio

Actions
[Open Kubernetes]
[Open Grafana]
[Open Logs]
[Open Argo CD]
[Open Repository]
[Open Runbook]
```

For Kafka topics:

```text
processed-audio

Partitions: 18
Replication: 3
Messages/sec: 11,284
Retention: 180 days
Size: 7.4 TB

Producers: 3
Consumers: 5

Largest lag:
elastic-sink: 9.2M

[Open Kafka UI]
[Open Grafana]
[View Consumers]
[View Producers]
```

For a Kafka Connect connector:

```text
audio-elasticsearch-sink

Status: RUNNING
Tasks: 18/18
Worker Cluster: connect-prod

Consumes:
processed-audio

Writes:
elasticsearch/audio-events

[Open Connector UI]
[Open Kafka UI]
[Open Logs]
[Open Grafana]
```

---

# 8. Graph Data Model

The internal model must be technology-independent. Concrete technologies are represented as data and plugin-provided capabilities rather than hard-coded assumptions in the core domain model.

The graph schema should remain valid even for technologies that do not exist when the platform is first built. Where possible, node and edge behavior should be driven by generic capabilities and extensible metadata instead of central enums that require a core release whenever a new integration appears.

## 8.1 Node

```text
Node
- id
- externalId
- type
- subtype
- name
- displayName
- description
- environment
- namespace
- domain
- team
- owner
- status
- health
- metadata
- metrics
- labels
- tags
- links
- source
- discoveredAt
- updatedAt
```

## 8.2 Edge

```text
Edge
- id
- from
- to
- relation
- source
- confidence
- metadata
- discoveredAt
- updatedAt
```

## 8.3 Core Relationship Types

```text
PRODUCES_TO
CONSUMES_FROM
READS_FROM
WRITES_TO
CALLS
TRIGGERS
RUNS_ON
DEPLOYS
SINKS_TO
SOURCES_FROM
DEPENDS_ON
OWNS
MONITORS
EXPOSES
ROUTES_TO
STORES_IN
QUERIES
REPLICATES_TO
MIRRORS_TO
```

---

# 9. Supported Component Types

The model must be extensible. The list below is a **reference integration catalog and initial vocabulary, not a closed list of supported technologies**. The platform must support plugin-defined node subtypes and capabilities so new technologies can be introduced without redesigning the graph model.

A technology that is unknown today should eventually be able to register its own visual identity, metadata schema, discovery logic, health model, metrics, actions, links, and relationship resolvers through the plugin SDK.

## Application Layer

- SERVICE
- API
- BACKGROUND_WORKER
- CRON_JOB
- BATCH_JOB
- SERVERLESS_FUNCTION

## Messaging

- KAFKA_CLUSTER
- KAFKA_TOPIC
- KAFKA_CONSUMER_GROUP
- KAFKA_CONNECTOR
- KAFKA_CONNECT_WORKER
- KAFKA_MIRRORMAKER
- RABBITMQ_EXCHANGE
- RABBITMQ_QUEUE
- SQS_QUEUE
- PUBSUB_TOPIC

## Kubernetes

- K8S_CLUSTER
- NAMESPACE
- DEPLOYMENT
- STATEFULSET
- DAEMONSET
- JOB
- CRONJOB
- SERVICE
- INGRESS
- CONFIGMAP
- SECRET_REFERENCE
- POD

Pods should generally be runtime details rather than permanent topology nodes.

## Databases

- POSTGRESQL
- MYSQL
- MONGODB
- CASSANDRA
- REDIS
- CLICKHOUSE
- generic DATABASE
- DATABASE_TABLE

## Search / Analytics

- ELASTICSEARCH_CLUSTER
- ELASTICSEARCH_INDEX
- OPENSEARCH_CLUSTER
- OPENSEARCH_INDEX

## Data Lake / Lakehouse

- ICEBERG_CATALOG
- ICEBERG_NAMESPACE
- ICEBERG_TABLE
- DELTA_TABLE
- HIVE_TABLE
- S3_BUCKET
- MINIO_BUCKET
- ADLS_CONTAINER
- GCS_BUCKET

## Query Engines

- TRINO_CLUSTER
- TRINO_CATALOG
- SPARK_JOB
- FLINK_JOB

## Workflow / Orchestration

- CONDUCTOR_WORKFLOW
- TEMPORAL_WORKFLOW
- AIRFLOW_DAG
- ARGO_WORKFLOW

## Delivery / GitOps

- GITHUB_REPOSITORY
- GITLAB_REPOSITORY
- ARGOCD_APPLICATION
- HELM_RELEASE
- CI_PIPELINE

## Observability

- GRAFANA_DASHBOARD
- PROMETHEUS_TARGET
- ALERT
- LOG_SOURCE
- TRACE_SERVICE

## External

- EXTERNAL_API
- SAAS_SERVICE
- THIRD_PARTY_DATA_SOURCE

---

# 10. Integration Architecture

The product should use an adapter/plugin architecture. This architecture is the primary mechanism that keeps the platform technology-agnostic. The platform core must not need to know whether a node came from Kafka, Kubernetes, Snowflake, a cloud provider, an observability platform, or a proprietary internal system.

```text
                     Platform Core
                          │
              ┌───────────┼────────────┐
              │           │            │
            Graph       Health      Search
              │           │            │
     ┌────────┼───────────┼────────────┼───────┐
     │        │           │            │       │
     ▼        ▼           ▼            ▼       ▼
 Kubernetes  Kafka    Kafka Connect  Elastic  Iceberg
 Adapter     Adapter     Adapter      Adapter  Adapter
```

Each integration should implement a standard interface.

Example conceptual interface:

```java
interface TopologyAdapter {
    String type();

    DiscoveryResult discover(IntegrationConfig config);

    Optional<NodeState> getState(Node node);

    List<ActionLink> getLinks(Node node);
}
```

Optional capabilities:

```text
DiscoveryAdapter
HealthAdapter
MetricsAdapter
LinkProvider
RelationshipResolver
ActionProvider
```

This allows integrations to be added independently.

A plugin should be able to contribute some or all of the following without modifying core topology logic:

```text
node discovery
relationship discovery
node/edge subtype definitions
metadata schemas
health normalization
metric providers
event/change providers
external links
read-only or write actions
UI inspector panels
node icons / visual metadata
search indexing hints
relationship resolvers
configuration schema
credentials/authentication requirements
```

The desired long-term architecture is:

```text
                              Nodqora Core
                                   │
                 ┌─────────────────┼─────────────────┐
                 │                 │                 │
              Graph API       Query Engine       Web UI
                 │
            Plugin Runtime / SDK
                 │
     ┌───────────┼─────────────┬──────────────┬───────────────┐
     ▼           ▼             ▼              ▼               ▼
 Kubernetes    Kafka       Elasticsearch   Snowflake     Custom Plugin
   Plugin      Plugin          Plugin         Plugin      / Future Tech
```

The first-party integrations are simply plugins maintained by the product team. Third-party and customer-owned plugins should eventually use the same extension contract.

---

# 11. Discovery Strategy

Discovery should have multiple sources.

## 11.1 Manual Configuration

Example:

```yaml
components:
  - name: audio-api
    type: SERVICE
    environment: production

    kubernetes:
      cluster: prod
      namespace: audio
      workload: audio-api

    produces:
      - kafka://prod/incoming-audio

    links:
      repository: https://github.com/example/audio-api
      grafana: https://grafana.example.com/d/audio-api

  - name: incoming-audio
    type: KAFKA_TOPIC

  - name: audio-processor
    type: SERVICE

    consumes:
      - kafka://prod/incoming-audio

    produces:
      - kafka://prod/processed-audio
```

Manual topology provides:

- deterministic configuration
- rapid onboarding
- overrides for discovery
- metadata not available automatically

---

## 11.2 Kubernetes Discovery

Discover:

- namespaces
- deployments
- StatefulSets
- services
- ingress
- jobs
- CronJobs
- labels
- annotations
- container images
- versions
- replicas
- readiness
- health

Relationships may be inferred through:

- selectors
- labels
- environment variables
- annotations
- service names
- OpenTelemetry
- application metadata

Recommended annotations could include:

```yaml
topology.io/service: audio-processor
topology.io/owner: audio-platform
topology.io/repository: github.com/org/audio-processor
topology.io/grafana: audio-processor-dashboard
```

---

## 11.3 Kafka Discovery

Discover:

- brokers
- topics
- partitions
- replication
- configurations
- consumer groups
- offsets
- lag
- throughput
- retention
- topic sizes

Possible implementations:

- Kafka AdminClient
- JMX
- Prometheus Kafka Exporter
- Strimzi CRDs

---

## 11.4 Kafka Connect Discovery

Discover:

- clusters
- connectors
- connector type
- source/sink
- topics
- task status
- worker IDs
- connector configs
- error state
- last failure

Relationships can often be inferred directly from connector configuration.

Example:

```text
Kafka Topic
   ↓
Elasticsearch Sink Connector
   ↓
Elasticsearch Index
```

---

## 11.5 OpenTelemetry Discovery

OpenTelemetry becomes a major long-term discovery source.

Trace relationships can establish actual service communication:

```text
Service A → Service B
```

Benefits:

- actual runtime traffic
- dynamically discovered dependencies
- latency between components
- error propagation
- request path visualization

Possible model:

```text
configured dependency
observed dependency
```

Observed edges can contain confidence and last-seen timestamps.

---

## 11.6 Prometheus Discovery

Prometheus can provide runtime state and metrics.

Examples:

- request rates
- latency
- CPU
- memory
- Kafka lag
- connector task status
- Elasticsearch health

Nodes should expose only selected meaningful metrics rather than arbitrary metric exploration.

---

# 12. Integration Roadmap

## Tier 1 — Foundation

- Kubernetes
- Kafka
- Kafka Connect
- Prometheus
- Grafana
- GitHub/GitLab link integration

## Tier 2 — Data Platform

- Elasticsearch
- OpenSearch
- Iceberg
- Trino
- S3
- MinIO
- PostgreSQL
- MongoDB

## Tier 3 — Workflow

- Netflix Conductor
- Temporal
- Airflow
- Argo Workflows

## Tier 4 — Deployment / Platform

- Argo CD
- Helm
- GitHub Actions
- GitLab CI
- Jenkins

## Tier 5 — Observability

- Loki
- Elasticsearch logs
- OpenTelemetry
- Jaeger
- Tempo
- Datadog
- New Relic
- Dynatrace

## Tier 6 — Cloud

- AWS
- Azure
- GCP

Support resources such as:

- managed Kafka
- managed databases
- object storage
- queues
- serverless
- load balancers

---

# 13. Health Model

Every operational node should have a normalized health value.

```text
HEALTHY
DEGRADED
UNHEALTHY
UNKNOWN
DISABLED
```

Adapters convert technology-specific status into normalized states.

Examples:

Kubernetes deployment:

```text
8 desired
8 available
→ HEALTHY
```

Kafka Connect:

```text
connector RUNNING
17/18 tasks RUNNING
1 FAILED
→ DEGRADED
```

Kafka consumer:

```text
lag > defined threshold
→ DEGRADED
```

Elasticsearch:

```text
cluster red
→ UNHEALTHY
```

Health thresholds should be configurable.

---

# 14. Live Metrics on Graph

The canvas should optionally overlay operational state.

Examples:

Service node:

```text
8/8 pods
1.2k req/s
p95 210 ms
```

Kafka topic:

```text
12k msg/s
18 partitions
9.2M max lag
```

Connector:

```text
RUNNING
18/18 tasks
```

Elasticsearch:

```text
920M docs
yellow
```

Iceberg:

```text
2.8 TB
last commit 2m ago
```

Users must be able to toggle metrics to avoid clutter.

---

# 15. Search

Global search is important.

Users should be able to search:

```text
processed-audio
```

and find:

- Kafka topic
- producers
- consumers
- connectors
- downstream stores
- dashboards

Searchable metadata:

- names
- aliases
- labels
- team
- repository
- namespace
- cluster
- topic
- index
- database
- owner

---

# 16. Dependency Exploration

Core operations:

## Show Upstream

```text
What feeds this component?
```

## Show Downstream

```text
What depends on this component?
```

## Find Path

```text
Find path from external API to Elasticsearch.
```

## Show All Paths

Useful when multiple processing paths exist.

## Dependency Depth

```text
1 hop
3 hops
all
```

---

# 17. Blast Radius

This can become one of the strongest features.

User selects a component and chooses:

```text
Show blast radius
```

Example:

```text
Kafka Topic X
     ↓
Service A
     ↓
Kafka Topic Y
   ┌─┴─────────┐
   ↓           ↓
Elastic      Iceberg
```

The UI highlights all components that may be affected.

Potential modes:

- runtime blast radius
- data blast radius
- ownership blast radius
- deployment blast radius

Future capabilities could incorporate confidence and real traffic.

---

# 18. Data Lineage

The graph should support traditional data lineage in addition to service topology.

Example:

```text
External API
    ↓
Kafka raw-events
    ↓
Processor
    ↓
Kafka processed-events
    ↓
Iceberg events_table
    ↓
Trino
    ↓
BI Dataset
```

Possible future granularity:

- dataset-level lineage
- table-level lineage
- column-level lineage

Column-level lineage should not be an early priority.

---

# 19. Event / Request Path

A long-term advanced feature is the ability to follow one logical event through the platform.

For example:

```text
request_id = abc123
```

The platform might show:

```text
API
12:00:01.120
 ↓
Kafka incoming
12:00:01.178
 ↓
Processor
12:00:01.240
 ↓
Kafka processed
12:00:01.401
 ↓
Elastic Sink
12:00:01.621
 ↓
Elasticsearch
12:00:01.780
```

This may require:

- correlation IDs
- OpenTelemetry
- Kafka headers
- application instrumentation

This should be treated as an advanced capability rather than a launch feature.

---

# 20. Ownership Model

Every node should optionally include:

- owner team
- Slack/Teams channel
- repository
- service documentation
- runbook
- escalation policy
- on-call group

Example:

```text
Owner: Data Platform
Repository: github.com/company/audio-processor
Runbook: internal/wiki/audio-processor
On-call: Platform SRE
```

This moves the product toward a lightweight service catalog without becoming a full developer portal.

---

# 21. Navigation / Action Links

Each node type can expose integrations.

## Kubernetes

- Open workload
- Open pods
- Open logs
- Open Argo CD

## Kafka

- Open topic
- Open consumer groups
- Open Grafana

## Kafka Connect

- Open connector
- Open logs
- Open config

## Elasticsearch

- Open Kibana
- Open index
- Open dashboards

## Iceberg

- Open catalog
- Open Trino query editor
- Open object storage path

## Service

- Open Git repository
- Open Grafana
- Open logs
- Open traces
- Open deployment
- Open runbook

---

# 22. Operational Actions

Initially the product should be read-only.

Later, optional actions may include:

- restart deployment
- scale deployment
- pause connector
- resume connector
- restart failed connector task
- trigger workflow
- retry workflow
- open incident

Write actions should require:

- RBAC
- explicit permissions
- confirmation
- audit log
- optional approval workflow

These actions should be plugin-based and disabled by default.

---

# 23. Environments

The product must understand environments.

Examples:

```text
DEV
QA
STAGING
PRODUCTION
```

Users should be able to:

- view a single environment
- switch environments
- compare environments
- show topology differences

Example future feature:

```text
Production has connector X.
Staging does not.
```

---

# 24. Topology Change Tracking

The graph should eventually maintain history.

Examples:

```text
12:30 deployment v2.17.3 → v2.17.4
12:31 replicas 8 → 12
12:35 Kafka connector configuration changed
12:41 consumer lag started rising
```

This can provide useful incident context.

Potential sources:

- Kubernetes events
- GitOps
- Argo CD
- Kafka configs
- deployment pipelines

---

# 25. Incident Mode

A dedicated incident mode could simplify the graph.

Instead of displaying every component, show:

- unhealthy components
- directly affected neighbors
- important metrics
- recent changes
- alerts

Example:

```text
🔴 elastic-sink
   task 3 failed
       │
       ▼
🟠 processed-events
   lag 112M
       │
       ▼
🟢 processor-service
```

---

# 26. AI / SRE Assistant

AI should be built on top of the topology graph.

The graph gives the model deterministic context about system relationships.

Example question:

```text
Why is new data not reaching Elasticsearch?
```

The AI could evaluate:

```text
API                 healthy
Kafka raw topic     healthy
Processor           healthy
Kafka output topic  healthy
Elastic connector   degraded
Elasticsearch       healthy
```

Possible answer:

```text
The likely bottleneck is the Elasticsearch sink connector.
One task is failing and consumer lag has grown to 112M records.
Upstream processing is healthy.
```

Possible AI actions:

- explain architecture
- summarize component state
- identify likely bottleneck
- show dependency chain
- explain blast radius
- summarize incident
- compare environments
- identify recent changes
- generate troubleshooting checklist

The AI should never invent topology.

Graph data and integrations should be treated as authoritative context.

---

# 27. Natural Language Graph Queries

Users might ask:

```text
Show everything that writes to Elasticsearch.
```

```text
Which services consume customer-events?
```

```text
What would break if Kafka topic X disappeared?
```

```text
Show the complete path from API X to Iceberg table Y.
```

```text
Which unhealthy components are upstream of customer-search?
```

These queries can first be implemented deterministically and later exposed through AI.

---

# 28. Architecture

Suggested initial architecture:

```text
              ┌─────────────────────────┐
              │       Web Frontend      │
              │ React + TypeScript      │
              │ React Flow / XYFlow     │
              └────────────┬────────────┘
                           │
                           │ REST / GraphQL
                           ▼
              ┌─────────────────────────┐
              │     Platform Backend    │
              │ Spring Boot / Java      │
              └────────────┬────────────┘
                           │
           ┌───────────────┼────────────────┐
           │               │                │
           ▼               ▼                ▼
      Graph Service   Discovery Engine   State Engine
           │               │                │
           └───────────────┼────────────────┘
                           │
           ┌───────────────┼─────────────────────────┐
           │               │                         │
           ▼               ▼                         ▼
     Kubernetes         Kafka                Integration Plugins
```

---

# 29. Backend Technology

Recommended stack:

- Java 21+
- Spring Boot
- virtual threads where useful
- PostgreSQL
- Flyway
- Kafka client
- Kubernetes Java client
- Micrometer
- OpenTelemetry

Possible later technologies:

- Neo4j
- OpenSearch
- Redis

Do not introduce them before they solve a demonstrated scaling problem.

---

# 30. Graph Storage

## Phase 1

PostgreSQL is sufficient.

Tables:

```text
nodes
edges
integrations
node_state
node_metrics
node_links
```

Recursive CTEs can support early dependency queries.

## Future

A graph database such as Neo4j may become useful for:

- deep graph traversal
- path finding
- blast radius
- graph analytics

Migration should happen only if PostgreSQL becomes limiting.

---

# 31. Frontend Technology

Recommended:

- React
- TypeScript
- XYFlow / React Flow
- TanStack Query
- component library such as shadcn/ui

Important UI areas:

```text
Canvas
Node inspector
Search
Filters
Environment selector
Topology selector
Integration settings
Admin area
```

---

# 32. API Design

Possible endpoints:

```text
GET /api/topologies
GET /api/topologies/{id}
GET /api/topologies/{id}/graph

GET /api/nodes/{id}
GET /api/nodes/{id}/state
GET /api/nodes/{id}/metrics
GET /api/nodes/{id}/upstream
GET /api/nodes/{id}/downstream
GET /api/nodes/{id}/blast-radius

GET /api/search

POST /api/integrations
GET /api/integrations

POST /api/discovery/run
```

Future GraphQL support may fit graph exploration but is not required initially.

---

# 33. Discovery Engine

The discovery engine periodically asks adapters for discovered objects.

Conceptually:

```text
Kubernetes Adapter
        │
        ▼
Discovery Result
        │
        ▼
Identity Resolution
        │
        ▼
Graph Merge
        │
        ▼
Stored Topology
```

It must handle:

- create
- update
- delete
- stale objects
- conflicting sources
- manual overrides
- aliases

---

# 34. Identity Resolution

This is likely one of the hardest technical problems.

The platform must determine when different systems describe the same logical component.

Example:

```text
Kubernetes deployment:
audio-processor

Prometheus job:
audio-processing-service

Git repository:
audio-processor-service
```

Potential techniques:

- explicit IDs
- annotations
- labels
- aliases
- configurable mapping rules
- repository metadata
- service catalog metadata

Avoid relying primarily on fuzzy matching.

---

# 35. Relationship Confidence

Automatically discovered relationships may have different reliability.

Example:

```text
MANUAL               1.0
CONNECTOR_CONFIG      1.0
OTEL_TRACE            0.95
KUBERNETES_CONFIG     0.9
INFERRED              0.6
```

Edges can store:

```text
source
confidence
lastObserved
```

The UI could optionally indicate inferred relationships.

---

# 36. Plugin SDK

Long-term success depends on easy integration development. The public Plugin SDK is a strategic product capability, not merely an internal code organization technique. It is what allows the platform to remain useful as infrastructure stacks evolve.

The goal is that adding support for a new technology should normally require creating and installing a plugin, **not changing the topology core**. First-party plugins, community plugins, enterprise plugins, and private organization-specific plugins should converge on the same contract.

A plugin should define:

```text
metadata
configuration schema
discovery capability
health capability
metrics capability
link capability
action capability
```

Example conceptual plugin:

```text
ElasticsearchPlugin
  discoverClusters()
  discoverIndices()
  resolveHealth()
  generateKibanaLinks()
```

Plugins should eventually be installable independently.

Potential future examples include:

- Snowflake
- Databricks
- Apache Pulsar
- NATS
- Azure Service Bus
- AWS Kinesis
- Oracle / SQL Server
- Couchbase
- Neo4j
- ClickHouse
- Milvus / Qdrant / vector databases
- HashiCorp Nomad / Consul / Vault
- Ray
- custom internal schedulers, data platforms, gateways, and proprietary systems

A mature SDK should support plugin versioning, compatibility contracts, permission declarations, secrets/configuration schemas, capability negotiation, validation, sandboxing/isolation where appropriate, and a registry/marketplace model.

---

# 37. Authentication

Possible support:

- local development login
- OIDC
- OAuth2
- SAML for enterprise

Providers:

- Google
- Microsoft Entra ID
- Okta
- Keycloak

---

# 38. Authorization

RBAC should support:

```text
Viewer
Engineer
Operator
Admin
```

Possible future scope:

```text
team-based access
environment-based access
integration-level access
node-level action permissions
```

---

# 39. Secrets

Integration credentials must never be stored in plain text.

Possible approaches:

- Kubernetes Secrets
- HashiCorp Vault
- AWS Secrets Manager
- Azure Key Vault
- environment variables

The platform should support credential references rather than embedding secrets in topology configuration.

---

# 40. Security Model

The platform has access to sensitive production metadata.

Important protections:

- read-only integrations by default
- least privilege
- TLS
- encrypted secrets
- audit logs
- RBAC
- optional SSO
- action confirmation
- no secret values displayed in the graph

The system should collect metadata, not payload data, unless explicitly required.

---

# 41. Deployment Model

Initial deployment target:

```text
Self-hosted Kubernetes
```

Helm chart should install:

```text
frontend
backend
PostgreSQL dependency or external DB config
```

Later deployment modes:

- Docker Compose
- Helm
- managed SaaS control plane
- hybrid agent model

---

# 42. SaaS / Agent Architecture

For enterprise/SaaS deployment, direct SaaS access to customer clusters may be undesirable.

Possible architecture:

```text
Customer Kubernetes

Topology Agent
   │
   │ outbound TLS
   ▼
SaaS Control Plane
```

Agent responsibilities:

- discovery
- health collection
- metric summaries
- local integration access

Control plane responsibilities:

- graph storage
- UI
- search
- AI
- collaboration

This allows customers to avoid inbound cluster access.

---

# 43. Open Source Strategy

Possible open-core model:

## Open Source

- technology-agnostic topology core
- public plugin/adapter contract (when stable)
- topology graph
- Kubernetes integration
- Kafka integration
- Kafka Connect integration
- YAML configuration
- basic health
- navigation links

## Commercial / Enterprise

Possible features:

- SSO/SAML
- advanced RBAC
- audit logging
- topology history
- environment comparison
- incident mode
- advanced blast radius
- AI assistant
- enterprise integrations
- managed SaaS
- policy engine
- collaboration

This split should not be finalized until there is user adoption evidence.

---

# 44. Possible Product Names

Do not prioritize branding yet.

Conceptual directions:

- OpsGraph
- FlowMap
- RuntimeGraph
- SystemGraph
- InfraGraph
- TraceMap
- StackMap
- FlowScope
- TopologyHub
- OperMap

A final name should be researched separately for trademarks and domains.

---

# 45. Competitive Categories

The product overlaps with several existing categories but should avoid directly becoming any one of them.

## Developer Portals

Examples:

- Backstage
- Port
- OpsLevel

Strengths:

- service catalogs
- ownership
- integrations
- developer workflows

Differentiation target:

- graph-first
- pipeline-first
- runtime-first
- data + infrastructure topology

## Data Lineage

Examples:

- DataHub
- OpenMetadata

Strengths:

- datasets
- warehouse lineage
- governance

Differentiation target:

- services + Kafka + Kubernetes + runtime
- operational health
- incident debugging

## Observability

Examples:

- Datadog
- Dynatrace
- New Relic
- Grafana

Strengths:

- metrics
- traces
- logs

Differentiation target:

- canonical architecture graph
- cross-tool navigation
- explicit topology
- product-neutral integrations

## Kubernetes Visualization

Examples:

- Lens
- Headlamp
- k9s

Strengths:

- cluster operations

Differentiation target:

- system-level graph across technologies

---

# 46. Main Differentiator

The platform should answer:

> How does this complete distributed system actually work right now?

Not just:

```text
What services exist?
```

Not just:

```text
Where did this dataset originate?
```

Not just:

```text
What metrics are failing?
```

Instead:

```text
What is connected?
What is happening?
What depends on what?
Where should I investigate?
What will be affected?
```

---

# 47. MVP

The MVP should deliberately prove the central experience.

## Supported technologies

- Kubernetes
- Kafka
- Kafka Connect

## Required features

- interactive graph
- manual YAML topology
- Kubernetes health discovery
- Kafka topic discovery
- Kafka consumer group visibility
- Kafka Connect discovery
- configurable links
- node inspector
- upstream/downstream navigation
- environment support

## Example MVP topology

```text
Service A
   ↓
Kafka Topic A
   ↓
Service B
   ↓
Kafka Topic B
   ├── Connector → Elasticsearch
   └── Connector → Iceberg
```

Iceberg and Elasticsearch may initially be generic external nodes.

---

# 48. MVP Non-Goals

Do not initially build:

- AI assistant
- graph database
- write actions
- full OpenTelemetry discovery
- column-level lineage
- SaaS
- mobile app
- complex RBAC
- dozens of integrations
- custom metrics system
- log storage
- trace storage

---

# 49. Product Roadmap

## Phase 0 — Product Validation

Create:

- product vision
- mockups
- graph schema
- real-world example pipelines
- competitive research

Validate with engineers who operate Kafka/Kubernetes/data platforms.

---

## Phase 1 — Static Operational Map

Features:

- React graph canvas
- Spring Boot backend
- node/edge model
- PostgreSQL
- YAML topology import
- links
- node inspector
- search
- filters

Goal:

Prove that engineers find a clickable architecture map useful.

---

## Phase 2 — Kubernetes Integration

Add:

- Kubernetes clusters
- deployments
- StatefulSets
- services
- readiness
- replicas
- container versions
- links to Kubernetes UI

Goal:

Make service nodes live.

---

## Phase 3 — Kafka Integration

Add:

- clusters
- topics
- partitions
- consumer groups
- consumer lag
- retention
- throughput

Goal:

Make messaging visible.

---

## Phase 4 — Kafka Connect

Add:

- connectors
- tasks
- connector health
- connector config
- topic relationships

Goal:

Automatically create important pipeline edges.

---

## Phase 5 — Data Destinations

Add:

- Elasticsearch
- Iceberg
- S3/MinIO
- Trino
- PostgreSQL
- MongoDB

Goal:

Represent complete application/data pipelines.

---

## Phase 6 — Observability

Add:

- Prometheus
- Grafana
- logs
- OpenTelemetry

Goal:

Add real operational state to the graph.

---

## Phase 7 — Automatic Relationship Discovery

Use:

- connector configs
- Kubernetes metadata
- application annotations
- OpenTelemetry
- Prometheus labels

Goal:

Reduce manual graph maintenance.

---

## Phase 8 — Dependency Intelligence

Add:

- upstream
- downstream
- shortest path
- all paths
- blast radius
- critical dependencies

---

## Phase 9 — Change Tracking

Add:

- graph history
- deployment history
- configuration changes
- GitOps events

---

## Phase 10 — Incident Mode

Add:

- unhealthy graph filtering
- related alerts
- recent changes
- dependency impact
- incident timeline

---

## Phase 11 — AI Assistant

Add topology-aware AI capable of:

- explaining architecture
- identifying likely failures
- summarizing incidents
- querying dependencies
- generating troubleshooting guidance

---

## Phase 12 — Enterprise / SaaS

Add:

- remote agents
- SSO
- enterprise RBAC
- audit logs
- multi-tenancy
- SaaS control plane

---

# 50. Example End-State Scenario

An engineer opens the platform because customer search results have stopped updating.

They select:

```text
Customer Search Pipeline
```

The graph displays:

```text
Customer API
    ↓
customer-events
    ↓
customer-processor
    ↓
customer-index-events
    ↓
Elasticsearch Sink
    ↓
customer-search-index
```

The map shows:

```text
Customer API          🟢
customer-events       🟢
customer-processor    🟢
customer-index-events 🟠 lag 112M
Elasticsearch Sink    🔴 task 7 failed
Elasticsearch         🟢
```

The engineer clicks the failed connector.

The inspector shows:

```text
Task 7 failed 14 minutes ago
Last error: HTTP 429
```

Links:

```text
Open Kafka Connect
Open Logs
Open Grafana
Open Elasticsearch
```

They choose:

```text
Show blast radius
```

The affected downstream search system is highlighted.

They ask:

```text
Why is customer search stale?
```

The AI responds using topology + current state:

```text
The customer Elasticsearch sink is the likely bottleneck.
Task 7 is failing with HTTP 429 responses and the associated consumer group
has accumulated 112M messages of lag. The upstream processor is healthy.
```

This scenario represents the long-term product vision.

---

# 51. Important Engineering Challenges

The technically difficult parts are likely not the graph UI itself.

Primary challenges:

1. identity resolution across systems
2. reliable automatic relationship discovery
3. avoiding noisy/cluttered topology
4. normalizing health across technologies
5. keeping topology current
6. handling large graphs
7. managing integration credentials securely
8. representing environments and clusters correctly
9. distinguishing logical components from runtime instances
10. determining useful metrics rather than dumping telemetry

These should be treated as core product problems.

---

# 52. Scale Considerations

Small installations:

```text
100–1,000 nodes
```

Medium:

```text
1,000–20,000 nodes
```

Large enterprises may contain significantly more resources.

The UI should not attempt to render every node simultaneously.

Required techniques may eventually include:

- topology scopes
- aggregation
- clustering
- lazy loading
- collapsed groups
- server-side traversal

---

# 53. Logical vs Physical Topology

A major UX distinction should exist between logical and runtime topology.

Logical view:

```text
Service A → Kafka Topic → Service B
```

Runtime detail:

```text
Service B
  ├ pod-123
  ├ pod-456
  └ pod-789
```

The main graph should primarily represent logical architecture.

Runtime instances belong in the node inspector or expandable subgraphs.

---

# 54. Grouping

Users should be able to group graph components by:

- technology
- Kubernetes namespace
- domain
- team
- environment
- cluster
- application

Example:

```text
[Audio Domain]

API → Kafka → Processing → Storage
```

---

# 55. Configuration as Code

Topology metadata should support Git-based configuration.

Example repository:

```text
topology/
  production/
    audio.yaml
    search.yaml
  staging/
    audio.yaml
```

Benefits:

- reviewable changes
- version history
- GitOps
- reproducibility

Discovery and configuration should merge.

---

# 56. Manual Overrides

Users must be able to correct discovery.

Examples:

```yaml
relationships:
  - from: service:audio-processor
    to: kafka:processed-audio
    relation: PRODUCES_TO
```

Overrides should have higher priority than inferred relationships.

---

# 57. Extension Metadata

Nodes need arbitrary plugin metadata.

Example:

```json
{
  "kafka": {
    "partitions": 18,
    "retentionMs": 15552000000
  }
}
```

Core fields should remain technology-independent.

---

# 58. API / Plugin Stability

The plugin interface is part of the product platform.

Version it carefully.

Example:

```text
Plugin API v1
```

Avoid coupling plugins directly to database structures.

---

# 59. Observability of the Platform Itself

The platform should expose:

- Prometheus metrics
- structured logs
- OpenTelemetry traces
- health endpoints

Important metrics:

```text
discovery duration
adapter errors
nodes discovered
edges discovered
stale nodes
graph query latency
API latency
```

---

# 60. Testing Strategy

## Unit Tests

- graph merge logic
- relationship resolution
- identity matching
- health normalization

## Integration Tests

Use containers/test environments for:

- Kafka
- Kafka Connect
- PostgreSQL

Potential Kubernetes test environments:

- kind
- k3d

## Contract Tests

Each adapter should have a common compliance test suite.

## UI Tests

- node rendering
- filtering
- graph interaction
- inspector

## End-to-End

Create a reference pipeline:

```text
producer → Kafka → processor → Kafka → connector → target
```

and verify automatic discovery.

---

# 61. Demo Environment

Create a reproducible demo stack.

Possible Docker Compose/k3d environment:

```text
Kafka
Kafka Connect
sample producer
sample processor
Elasticsearch
Prometheus
Grafana
```

Later add:

```text
MinIO
Iceberg
Trino
```

This environment becomes useful for:

- development
- automated testing
- product demos
- documentation

---

# 62. Documentation

Documentation sections should eventually include:

- quick start
- architecture
- installation
- topology YAML format
- integration setup
- Kubernetes integration
- Kafka integration
- plugin development
- security
- troubleshooting

---

# 63. Product Metrics

Potential product success metrics:

- weekly active engineers
- pipelines mapped
- nodes discovered
- integrations connected
- topology searches
- click-throughs into operational tools
- incident sessions
- upstream/downstream queries
- blast-radius queries

More meaningful qualitative metric:

> Did this product reduce the time required to understand or debug a system?

---

# 64. Validation Questions

When talking with potential users, ask:

1. How do you currently understand production architecture?
2. What tools do you open during an incident?
3. How do you determine what consumes a Kafka topic?
4. How do you find the dashboard/logs for a service?
5. How often are architecture diagrams outdated?
6. Would you trust automatic topology discovery?
7. Which three integrations would make this useful immediately?
8. Would a blast-radius view change how you operate systems?
9. Which information should appear directly on the graph?
10. What would make the graph too noisy?

Avoid asking only whether users "like the idea".

Observe real debugging workflows instead.

---

# 65. First Reference Pipeline

Use one real production-style architecture as the project's canonical example.

Example:

```text
External API
    ↓
API Service
    ↓
Kafka Topic A
    ↓
Processor Service
    ↓
Kafka Topic B
    ├── Elasticsearch Sink
    │       ↓
    │   Elasticsearch
    │
    └── Iceberg Sink
            ↓
        Iceberg Table
            ↓
           Trino
```

Include:

- Kubernetes deployments
- Kafka consumer groups
- Kafka Connect tasks
- Grafana dashboards
- logs
- Git repositories

All initial UX decisions should be tested against this example.

---

# 66. Suggested Repository Structure

```text
operational-topology/

backend/
  src/main/java/...
    graph/
    topology/
    discovery/
    integrations/
      kubernetes/
      kafka/
      kafkaconnect/
    health/
    search/
    security/

frontend/
  src/
    graph/
    nodes/
    inspector/
    search/
    filters/
    integrations/

examples/
  topology/
  demo-stack/

docs/
  architecture/
  integrations/
  product/
```

A monorepo is likely easiest initially.

---

# 67. Initial Domain Objects

Suggested first backend types:

```text
Topology
Node
Edge
NodeType
RelationshipType
HealthStatus
Environment
Integration
ActionLink
DiscoverySource
```

Later:

```text
MetricSnapshot
TopologyChange
Incident
OperationalAction
```

---

# 68. Important Architectural Boundary

Separate:

```text
Topology
```

from:

```text
Runtime State
```

Topology changes slowly.

State changes continuously.

Example:

```text
Node:
audio-processor

Topology:
consumes incoming-audio
produces processed-audio

State:
7/8 replicas ready
CPU 82%
version 2.17.4
```

Do not rewrite topology every time health changes.

---

# 69. Refresh Model

Different information requires different refresh intervals.

Examples:

```text
Kubernetes health     10–30 sec
Kafka lag             10–30 sec
Connector state       10–30 sec
Topology discovery     1–5 min
Repository metadata   15–60 min
```

These should eventually be configurable.

---

# 70. Event-Driven Updates

Polling is sufficient initially.

Later integrations may support events:

- Kubernetes watches
- Kafka events
- Git webhooks
- Argo CD events

Backend updates can stream to the browser using:

- Server-Sent Events
- WebSocket

SSE may be sufficient for initial live graph status.

---

# 71. UI Performance

Rendering large graphs is challenging.

Mitigations:

- pipeline-specific views
- hide low-level runtime resources
- collapsed groups
- viewport rendering
- server-side filtering
- lazy subgraph expansion

The product should not default to displaying an entire enterprise as one graph.

---

# 72. Future Collaboration Features

Possible later features:

- saved views
- shared graph links
- annotations
- incident notes
- bookmarks
- team-owned dashboards

Example:

```text
/share/topology/audio-prod?focus=elastic-sink
```

---

# 73. Future Policy / Governance Features

Once the graph is reliable, it can support rules.

Examples:

```text
Every production service must have an owner.
Every service must have a runbook.
Every Kafka topic must have retention configured.
Production databases must have backups.
```

This pushes toward platform governance.

It should remain a later feature rather than defining the initial product.

---

# 74. Future Change Impact

Before deployment, the platform could analyze topology.

Example:

```text
You are modifying service X.

Potentially affected:
- topic A
- service Y
- connector Z
- Elasticsearch index Q
```

Integration with GitHub/GitLab pull requests could eventually surface this automatically.

---

# 75. Future Architecture Drift

Compare declared architecture with observed architecture.

Example:

```text
Declared:
Service A → Service B

Observed via OpenTelemetry:
Service A → Service B
Service A → Service C
```

The platform can flag:

```text
Undocumented dependency detected.
```

This could be highly valuable in large environments.

---

# 76. Possible Long-Term Moat

The defensible asset is not the graph renderer.

Potential moat:

1. reliable cross-system identity resolution
2. integration ecosystem
3. automatic topology discovery
4. historical topology data
5. operational dependency intelligence
6. troubleshooting knowledge built around the graph
7. organization-specific architecture context

The graph UI itself is relatively easy to replicate.

The topology knowledge layer is the valuable part.

---

# 77. Major Product Risk

The main risk is becoming a visually impressive diagram that engineers stop using.

To avoid this, every major feature should reduce real operational work.

The product must help answer concrete questions faster.

Examples:

```text
Where are the logs?
Who owns this?
What consumes this topic?
Why is this pipeline delayed?
What breaks if this fails?
What changed?
```

If the graph cannot answer these better than existing tools, it will become decoration.

---

# 78. Product Thesis

Modern production environments have plenty of tools that deeply understand individual technologies.

What is missing is a consistent operational context layer spanning those tools.

The product thesis is:

> Engineers need a continuously updated, explorable model of how their distributed system is connected, combined with live operational state and direct access to the tools responsible for each component.

If the topology becomes accurate and trusted, many higher-level capabilities naturally become possible:

```text
Topology
   ↓
Operational Navigation
   ↓
Dependency Analysis
   ↓
Blast Radius
   ↓
Incident Context
   ↓
Change Intelligence
   ↓
AI Troubleshooting
```

The graph is therefore not merely the visualization.

It is the foundational data model of the product.

---

# 79. Recommended Wayfinder Starting Point

Use this document as product context rather than attempting to implement everything at once.

The Wayfinder session should first decompose the product into epics.

Suggested epics:

```text
EPIC-01 Core graph domain model
EPIC-02 Backend API
EPIC-03 Interactive graph frontend
EPIC-04 YAML topology definition
EPIC-05 PostgreSQL persistence
EPIC-06 Integration framework
EPIC-07 Kubernetes integration
EPIC-08 Kafka integration
EPIC-09 Kafka Connect integration
EPIC-10 Health/state engine
EPIC-11 External action links
EPIC-12 Search and graph navigation
EPIC-13 Prometheus/Grafana integration
EPIC-14 Elasticsearch integration
EPIC-15 Iceberg/Trino integration
EPIC-16 Workflow integrations
EPIC-17 Automatic relationship discovery
EPIC-18 OpenTelemetry topology
EPIC-19 Blast-radius engine
EPIC-20 Topology history
EPIC-21 Incident mode
EPIC-22 Authentication/RBAC
EPIC-23 Plugin SDK
EPIC-24 AI assistant
EPIC-25 SaaS/remote agent architecture
```

Wayfinder should further break each epic into independently implementable tickets.

---

# 80. Recommended First Implementation Slice

Even though the full vision is much larger, development should begin with one thin vertical slice:

```text
YAML topology
    ↓
Spring Boot graph API
    ↓
PostgreSQL
    ↓
React Flow canvas
    ↓
Node inspector
    ↓
External navigation links
```

Then add one live integration:

```text
Kubernetes
```

Then:

```text
Kafka
```

Then:

```text
Kafka Connect
```

At every stage the application should remain usable.

Do not build isolated infrastructure foundations for months before the graph provides value.

---

# 81. Definition of Product Success

The product succeeds when an engineer who does not already understand a system can open the map and within minutes answer:

```text
What is this system?
How does data move through it?
What is broken?
What depends on the broken component?
Who owns it?
Where do I go next to investigate?
```

The long-term ambition is for this graph to become the operational map of record for distributed systems.
