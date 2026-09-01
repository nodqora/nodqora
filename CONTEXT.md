# Nodqora — Domain Context

The core graph model. Technology-agnostic by construction (product plan §5.2):
nothing in this document names Kubernetes, Kafka or Connect as a concept the
core understands. Those are plugins that produce these shapes.

Every term below is tested against [the reference pipeline](docs/reference-pipeline.md).
Decisions are recorded in [`docs/adr/`](docs/adr/). ADR-0001 through ADR-0009
were fixed by [Core graph domain model](https://github.com/fredskor/nodqora/issues/3);
ADR-0010 through ADR-0015 by
[Plugin/adapter contract for the MVP](https://github.com/fredskor/nodqora/issues/6),
which also amended ADR-0005.

---

## The model at a glance

```text
Environment ──owns──▶ Node ──▶ NodeState        (fast: health, metrics)
     │                 │  ├──▶ Backing[]        (physical objects behind it)
     │                 │  ├──▶ Link[]           (where to go next)
     │                 │  └──▶ Owner            (who to page)
     └──owns──▶ Edge ──┘

Registry: TypeDescriptor (per node type) · RelationDescriptor (per relation)

Plugin[yaml · kubernetes · kafka · connect]
   ├── Discovery  ──▶ DiscoveryResult{nodes, edges, descriptors, outcome}
   └── Health     ──▶ StateContribution{health, rawSignal, metrics}  ──▶ NodeState
```

**Slow half** — Node, Edge, Backing, Link, Owner, Environment. Changes when
architecture changes. **Fast half** — NodeState. Changes every refresh. The
boundary is ADR-0003 and it is the model's load-bearing wall.

---

## Ubiquitous language

### Node

A **logical** component in the topology: a service, a topic, a connector, an
index, a table, a query engine, an external API. Never a physical object — a
Deployment, a pod and a Service are *backings* of a Node, not Nodes (ADR-0005).

Fields: `id` (surrogate), `environmentKey` + `key` (natural key), `type`,
`displayName`, `description?`, `ownerKey?`, `links[]`, `backings[]`,
`metadata{}`, `sources[]`, `discoveredAt`, `updatedAt`.

`updatedAt` moves **only when topology changes** — never on a health refresh.

### Node key

The stable logical identifier of a Node, unique within its Environment:
`payments-enricher`. It is not the name of any physical object — the fixture's
enricher is a Deployment called `enricher-v2` with a consumer group called
`enrich-consumer-prod`. The key is what survives all three.

The natural key of a Node is the pair **(environmentKey, key)** (ADR-0004).

### Type

An **open string** on a Node — `service`, `kafka-topic`, `connect-connector`,
`iceberg-table`. The core never branches on its value. Presentation and
semantics come from a TypeDescriptor looked up at runtime (ADR-0001).

Say *type*. There is no `subtype` in the MVP (ADR-0009).

### TypeDescriptor

A registry entry describing one node type: category, icon, display label,
source. `icon` is a **name** from a fixed frontend icon set, never a shipped
asset; `source` is a plugin id. Registered by **plugins and by the YAML topology
alike** — which is how
`iceberg-table` exists with no plugin behind it. An unregistered type resolves
to a fallback descriptor and still renders (ADR-0001).

### Edge

A directed relationship between two Nodes in the same Environment. Fields:
`id`, `environmentKey`, `fromKey`, `toKey`, `relation`, `metadata{}`,
`sources[]`, `discoveredAt`, `updatedAt`.

**`from` → `to` is always the direction data flows** (ADR-0002). Downstream is
always "follow outgoing edges"; upstream is always "follow incoming". There are
no exceptions and no per-relation branching in traversal.

### Relation

An open string naming the *kind* of relationship — `PRODUCES_TO`,
`CONSUMES_FROM`. It is a label, not a direction: direction lives in `from`/`to`.

MVP vocabulary (ADR-0002):

| relation | orientation | forward phrasing | reverse phrasing |
|---|---|---|---|
| `CALLS` | forward | calls | is called by |
| `PRODUCES_TO` | forward | produces to | is produced by |
| `CONSUMES_FROM` | reversed | delivers to | consumes from |
| `SOURCES_FROM` | reversed | feeds | sources from |
| `WRITES_TO` | forward | writes to | is written by |
| `QUERIES` | reversed | is queried by | queries |

### Orientation

A property of a **relation**, held in its RelationDescriptor: whether the
relation reads with the flow (`FORWARD`) or against it (`REVERSED`). A reversed
relation is stored flow-directed and *phrased* against the flow, so the
inspector says "payments-enricher consumes from payments.events.raw.v1" while
the stored edge runs topic → service.

Orientation affects **wording only**. It never affects traversal.

### RelationDescriptor

Registry entry for one relation: orientation, forward phrasing, reverse
phrasing. Registered like a TypeDescriptor.

### Environment

A first-class entity — `production`, `staging` — holding its own discovery
connection configuration. Every Node and Edge belongs to exactly one
Environment (ADR-0004).

An environment that lacks a component simply has no row for it. **Drift is
absence**, never a flag.

### NodeState

The fast half: `nodeId`, `health`, `rawSignal`, `metrics{}`, `observedAt`.
Written by the health refresh loop on its own cadence, joined to the Node on
read (ADR-0003).

One row, but **composed from several StateContributions** — `payments-enricher`
is observed by `kubernetes` and `kafka` at once (ADR-0013).

Never say "the node's health is stale" — say the **NodeState** is stale.

### Health

The normalized operational state of a Node. A **closed** five-value enum
(product plan §13): `HEALTHY`, `DEGRADED`, `UNHEALTHY`, `UNKNOWN`, `DISABLED`.

Deliberately the one closed vocabulary in a model of open strings — normalizing
technology-specific status into a fixed set is what plugins exist to do
(ADR-0003).

`UNKNOWN` is **normal, not an error**: four of the reference pipeline's ten
nodes are permanently UNKNOWN because nothing observes them.

### Raw signal

The technology-specific observation a health value was normalized *from* —
`"3 desired / 2 ready; lag 40000"`, `"RUNNING, 1 of 3 tasks FAILED"`. Held on
NodeState so the inspector can always show its work.

### Backing

A physical object behind a Node: `{ plugin, kind, reference }`. Topology, so it
lives on the Node side; the live signal from a backing lives in NodeState
(ADR-0005).

`plugin` names the plugin whose **technology domain** the object belongs to —
not the one that discovered it. A consumer group is a Kafka object however we
learned of it, so the `connect` plugin writes `{ plugin: kafka, kind:
consumer-group, ... }`. Provenance is the node's `sources[]`, never this field.

**Backings are the routing table for health**: a plugin is asked to observe
exactly the nodes carrying a backing of its own (ADR-0013).

- A Node may have **many** backings (`payments-api`: Deployment + Service + Ingress).
- One object may back **many** Nodes (one `kafka-connect` StatefulSet backs both connectors).
- A Node may have **none** — it is then a declared node.

### Declared node / Discovered node

**Declared** — comes from the YAML topology; `sources` contains `yaml`.
**Discovered** — comes from another plugin. A node can be both, and the
fixture's most important node is (`payments-enricher`:
`sources: [yaml, kubernetes]`).

The graph is permanently mixed. No feature may assume an observing plugin exists
behind a node. There is no "generic external node" — a declared node is an
ordinary node (ADR-0011).

### Owner

A first-class entity — a team: `key`, `displayName`, `channel`, `onCall`.
Nodes reference it by `ownerKey`. Stored once, never copied onto nodes
(ADR-0007).

### Link

A navigation target on a Node: `{ rel, label, url }`. `rel` is an **open**
string with well-known values — `repository`, `runbook`, `docs`, `dashboard`,
`logs`, `gitops`, `workload`, `config`.

There is exactly **one** link mechanism. Repository and runbook are links, not
fields (ADR-0007). A node with no links is a designed empty state, not a defect.

### Metadata

Plugin-namespaced opaque JSON on a Node, Edge or Backing, keyed by plugin id:
`{ kafka: { partitions: 12, retentionMs: 604800000 } }`. **Slow-moving values
only.** The core never reads inside it (ADR-0006).

### Metrics

The same shape as metadata, on **NodeState**, for fast-moving values:
`{ kafka: { maxConsumerLag: 40000 } }`. Metadata and metrics are the same idea
split across ADR-0003's boundary.

### Sources

Which plugins produced a Node or Edge: `[yaml, kubernetes]`. Per-node, not
per-field — the MVP records *that* a source contributed, not *which field it
set* (ADR-0008). Merge precedence is decided separately.

This is the model's **provenance** mechanism. `Backing.plugin` is not.

---

### Plugin

A registered contributor of graph data: `id`, display label, declared
capabilities, config type, contributed descriptors (ADR-0010). The MVP has
**four**, and these exact id strings are API surface — they are the `metadata`
keys, the `sources[]` entries, the `backings[].plugin` values and
`TypeDescriptor.source`:

`yaml` · `kubernetes` · `kafka` · `connect`

The YAML topology is a plugin like any other (ADR-0011). Kafka and Kafka Connect
are two plugins, not one.

Configuration is per environment, file-declared and bound at startup; secrets
are `${env:}` / `${file:}` references, never values (ADR-0014).

### Capability

What a plugin can do. There are exactly **two**, either or both:

- **Discovery** — produces topology, returning a DiscoveryResult.
- **Health** — produces runtime state, returning StateContributions.

A capability is present *iff* the plugin implements it. Links are **data**, not
a capability: declared in YAML, emitted by Discovery, or templated per node type
in environment config (ADR-0010).

### DiscoveryResult

One plugin's **full snapshot** of its scope for one environment — never a delta:
`{ nodes[], edges[], descriptors[], outcome }`, where `outcome` is `COMPLETE`,
`PARTIAL(reasons)` or `FAILED(cause)` (ADR-0012).

Nodes carry final keys and only the fields the plugin knows; `null` means **no
opinion**, not empty. Edges may reference keys the plugin does not own.

`outcome` is not a completeness guarantee — a plugin cannot always tell it was
blind. Say the snapshot is `PARTIAL`, not "discovery failed".

### StateContribution

One plugin's observation of one node: `{ health, rawSignal, metrics{} }`
(ADR-0013). Several are composed into the single NodeState row — metrics union
by namespace, raw signals join in plugin order, and health collapses by a rule
that is decided separately.

A contribution is not a NodeState. Only the state engine writes NodeState.

## Vocabulary to avoid

| don't say | say | why |
|---|---|---|
| component | **Node** | "component" is unscoped — physical objects are components too |
| status | **health** (normalized) or **raw signal** (unnormalized) | §8.1's `status`/`health` pair was collapsed; the distinction that survived is normalized vs raw |
| node type enum | **type** + **TypeDescriptor** | there is no enum; implying one invites a central switch |
| edge direction / flow direction | just **direction** | they are the same thing by construction (ADR-0002) |
| the node is unhealthy | the node's **health is UNHEALTHY** | health is a NodeState value, not a node property |
| pod / Deployment node | **backing** | physical objects are never Nodes |
| namespace (on a Node) | the **environment**, or the backing's namespace | `namespace` is a Kubernetes concept; it is not a core field |
| label / tag | **metadata** | neither field exists in the MVP (ADR-0009) |
| missing in staging | **not present in** staging | drift is absence, not a marked state |
| adapter / integration | **plugin** | one word for one concept; a capability implementation is not an adapter (ADR-0010) |
| generic external node | **declared node** | implies a mechanism that does not exist — a node with no plugin behind it is ordinary (ADR-0011) |
| the plugin's health value | the plugin's **StateContribution** | several plugins observe one node; only the state engine produces `health` (ADR-0013) |
| plugin config in the database | **file-declared** config | the MVP ships without auth; config is bound at startup, secrets are references (ADR-0014) |
