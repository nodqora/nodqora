# Nodqora — Domain Context

The core graph model. Technology-agnostic by construction (product plan §5.2):
nothing in this document names Kubernetes, Kafka or Connect as a concept the
core understands. Those are plugins that produce these shapes.

Every term below is tested against [the reference pipeline](docs/reference-pipeline.md).
Decisions are recorded in [`docs/adr/`](docs/adr/). ADR-0001 through ADR-0009
were fixed by [Core graph domain model](https://github.com/fredskor/nodqora/issues/3);
ADR-0010 through ADR-0015 by
[Plugin/adapter contract for the MVP](https://github.com/fredskor/nodqora/issues/6),
which also amended ADR-0005; ADR-0016 through ADR-0019 by
[Canvas and node-inspector prototype](https://github.com/fredskor/nodqora/issues/7);
ADR-0020 through ADR-0023 by
[Identity-resolution rules for the MVP](https://github.com/fredskor/nodqora/issues/8);
ADR-0024 through ADR-0029 by
[Health normalization model](https://github.com/fredskor/nodqora/issues/9);
ADR-0030 through ADR-0035 by
[Kubernetes discovery scope and annotation convention](https://github.com/fredskor/nodqora/issues/10);
ADR-0036 through ADR-0042 by
[Kafka and Kafka Connect discovery scope](https://github.com/fredskor/nodqora/issues/11),
which also amended ADR-0033.

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
   └── Health     ──▶ HealthResult{contributions, outcome}           ──▶ NodeState
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

The key is a **flat** human-readable string, unique within its Environment
across **all** types — never qualified by type or plugin, never opaque
(ADR-0020). It is stored verbatim and trimmed, but uniqueness and merge lookup
are **case-folded**: `UNIQUE (environmentKey, lower(trim(key)))`. That is what
lets `yaml` and `kubernetes` mint `payments-enricher` independently and land on
one row.

### Identity resolution

How a plugin decides which node key a thing it found belongs to. It happens
**inside the plugin** (ADR-0012), and only `kubernetes` has a real problem: a
topic and a connector are each their own key, and YAML states its key outright.

The rule is an **ordered cascade of exact-match tiers, first match wins** —
annotation, then object name. No tier is ever fuzzy (ADR-0021).

### Contested key

Two objects resolving to one node key. The plugin emits **one** node: backings
unioned so health routing reaches every claimant, scalars from the newest object
by `creationTimestamp`, and the contest recorded under the plugin's own
`metadata` namespace (ADR-0021).

A contest is not an incomplete snapshot — never report it as `PARTIAL`. And note
the reverse is not a contest at all: one object backing many nodes is legal and
deliberate (ADR-0005, ADR-0022).

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

A node nobody observes gets **no row at all**; absence reads as `UNKNOWN` /
`rawSignal: null` / `metrics: {}` / `observedAt: null` on the join (ADR-0028).

Never say "the node's health is stale" — say the **NodeState** is stale.

### Health

The normalized operational state of a Node. A **closed** five-value enum
(product plan §13): `HEALTHY`, `DEGRADED`, `UNHEALTHY`, `UNKNOWN`, `DISABLED`.

Deliberately the one closed vocabulary in a model of open strings — normalizing
technology-specific status into a fixed set is what plugins exist to do
(ADR-0003).

**The five values are not a ladder.** Three are severities; two are not
opinions at all. `UNKNOWN` is an **abstention** — "I was not asked" or "I could
not look". `DISABLED` is **judgement suspended** — a human turned this off, so
the lag behind it and the readiness beneath it are consequences of that act,
not findings. So contributions collapse in three steps (ADR-0024):

1. **Discard** every `UNKNOWN`. Nothing left — including nothing asked — is
   `UNKNOWN`.
2. Any surviving `DISABLED` wins **outright**, over `UNHEALTHY` included.
3. Otherwise **worst-wins** over the only real ladder:
   `HEALTHY` < `DEGRADED` < `UNHEALTHY`.

The same three steps run **inside** a plugin collapsing several of its own
backings, so there is one composition algorithm applied at two levels.

`UNKNOWN` is **normal, not an error**: four of the reference pipeline's ten
nodes are permanently UNKNOWN because nothing observes them.

Health is **local** — a node's health is what its own backings report, and
nothing else. It never propagates across edges (ADR-0027).

`DISABLED` requires **evidence of intent**: `desired = 0`, `PAUSED`, `STOPPED`.
Absence of activity is never enough, which is why `kafka` never emits it — an
`EMPTY` consumer group is a scaled-down consumer and a crashed one alike
(ADR-0029).

**`kubernetes` is the plugin that can**, because its evidence is *declarative*
and sits in the object's own spec rather than being inferred from an observed
absence: `spec.replicas: 0` and a CronJob's `spec.suspend: true` are recorded
statements of intent (ADR-0034). One consequence to know: a shared workload
carries `DISABLED` to every node it backs, so scaling the `kafka-connect`
StatefulSet to zero turns **both** connectors `DISABLED` under step 2 above,
even though nobody paused the connectors.

### Raw signal

The technology-specific observation a health value was normalized *from* —
`"3 desired / 2 ready; lag 40000"`, `"RUNNING, 1 of 3 tasks FAILED"`. Held on
NodeState so the inspector can always show its work.

Contributions join in **registry order** — `yaml`, `kubernetes`, `kafka`,
`connect` — separated by `"; "`. It is **`null`** when nothing observed the
node, never a string like "no adapter"; the inspector has a designed empty
state for that. A Connect stack trace contributes its **first line only**,
bounded (ADR-0028).

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

In Kubernetes, a Service or Ingress attaches to a node **by selector** —
`Ingress →(backend service name)→ Service →(selector ⊇ pod labels)→ workload` —
and that chain is the only Kubernetes inference the MVP uses. It produces
backing attachment, never an edge (ADR-0030, ADR-0033).

**A backing is emitted by whichever plugin knows the node key**, whatever
technology the object belongs to (ADR-0022). The plugin that can *read* a signal
is routinely not the one that can *attribute* it: `kafka` is the only plugin
that can read consumer lag and the only one that cannot say whose lag it is. So
`connect` stamps its config-declared workload onto every connector it discovers,
and `enrich-consumer-prod` is attributed by an annotation on the workload or by
YAML. The `kafka` plugin attributes nothing: it emits exactly **one** backing,
`{ plugin: kafka, kind: topic, reference: <topic name> }`, on the node whose key
is that topic's name (ADR-0040).

### Routed group

A consumer group that some node carries as a backing — declared by an
annotation, by YAML, or by the `connect` plugin's own config. The routed set is
the **only** set the `kafka` plugin reads: it never enumerates groups, because
no API answers "which groups consume topic X" and the alternative is the whole
cluster's consumer state on every fast poll (ADR-0040).

Group→topic then falls out of the same response that carries the lag, since
committed offsets are keyed by topic-partition — so a routed group's lag reaches
both the node that routed it and the topics it consumes, without any plugin
attributing anything.

**A group nobody routed is invisible.** Its lag reaches no node and no topic.
That is the standing cost of attribution-by-the-knowing-plugin, and it is why
consumer groups are backings rather than nodes (ADR-0036).

Backings are also the **alternate-name index**. There is no `aliases` field: a
node is identified by `key`, `displayName` and `backings[].reference`, which is
where the fixture's `enricher-v2` and `enrich-consumer-prod` live (ADR-0023).

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
`logs`, `gitops`, `workload`, `config`, `topic`, `consumers`.

There is exactly **one** link mechanism. Repository and runbook are links, not
fields (ADR-0007). A node with no links is a designed empty state, not a defect.

### Metadata

Plugin-namespaced opaque JSON on a Node, Edge or Backing, keyed by plugin id:
`{ kafka: { partitions: 12, retentionMs: 604800000 } }`. **Slow-moving values
only.** The core never reads inside it (ADR-0006).

Keys are **allow-listed per plugin**, by name, never everything-except
(ADR-0014, ADR-0038): `kubernetes` → `image`, `version`; `kafka` →
`partitions`, `replicationFactor`, `retentionMs`, `cleanupPolicy`; `connect` →
`class`, `type`, `tasksMax`. Allow-listing is a habit, not only a leak
mitigation: a plugin that copies wholesale because it happens to be safe today
is the one that leaks when the API grows.

### Metrics

The same shape as metadata, on **NodeState**, for fast-moving values:
`{ kafka: { maxConsumerLag: 40000 } }`. Metadata and metrics are the same idea
split across ADR-0003's boundary.

Keys are **allow-listed per plugin**, never "whatever the API returned"
(ADR-0028): `kubernetes` → `desiredReplicas`, `readyReplicas`; `kafka` →
`maxConsumerLag`; `connect` → `tasksTotal`, `tasksRunning`.

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

### Discovery scope

What each plugin is pointed at, and what it turns into nodes. Every scope knob
is **required, enumerated and wildcard-free**, so a forgotten one reads as "half
my graph is gone" rather than as one subtly absent node.

| plugin | scope | node-producing kinds | edges |
|---|---|---|---|
| `kubernetes` | enumerated namespaces, one cluster per environment (ADR-0035) | Deployment, StatefulSet, CronJob (ADR-0030) | none (ADR-0033) |
| `kafka` | required topic-name **prefix** list (ADR-0037) | topic (ADR-0036) | none |
| `connect` | the configured cluster, whole | connector (ADR-0036) | from the `topics` key only (ADR-0041) |
| `yaml` | the topology file | any | any — seven of the fixture's nine |

Suppression is **subtractive and exact** in both scoped plugins — an exact
`kind/name` or topic name, never a narrowing selector and never a glob
(ADR-0031, ADR-0037) — because subtraction fails toward a visibly-wrong *extra*
node while narrowing fails toward a *missing* one.

Brokers, Kafka clusters, Connect clusters, workers and consumer groups are
**not** nodes: they are physical (ADR-0005), or they duplicate the Environment,
or they are backings (ADR-0036).

Both loops run at **5 minute discovery / 30 second health** for all three code
plugins, with timeouts strictly below their interval (ADR-0035, ADR-0042).

### `topology.io/*` annotations

The **closed** nine-key vocabulary the `kubernetes` plugin reads from an object
(ADR-0032). Unknown `topology.io/*` keys are ignored and logged — never
interpreted as links or metadata.

`node` (the key, ADR-0021 tier 1) · `type` · `owner` · `ignore` ·
`consumer-groups` · `repository` · `runbook` · `docs` · `grafana`

**Annotations hold ids, not URLs.** `grafana: payments-enricher-overview` is a
dashboard id composed against a per-environment template in the plugin's config,
which is what lets one manifest render correctly in both production and staging.
A rel with no configured template produces **no link**, never a half-composed
one.

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

Health collapses by ADR-0024's three steps — see **Health** above.

A contribution is not a NodeState. Only the state engine writes NodeState.

### HealthResult

One plugin's health poll of one environment: `{ contributions, outcome }`, where
`outcome` is `COMPLETE`, `PARTIAL(reasons)` or `FAILED(cause)` — the same three
values, for the same reason, as DiscoveryResult (ADR-0026).

**`health` says what we found; `outcome` says how well we looked.** They are
separate because ADR-0024 discards abstentions: a plugin that fails for a cycle
would otherwise let the plugins that did answer render a node green.

There is no staleness TTL. `observedAt` is returned and the frontend says how
old it is.

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
| alias | the **key**, or a **backing reference** | there is no alias field; backings carry the alternate names (ADR-0023) |
| fuzzy match / similar name | an exact **cascade tier** | every resolution tier is exact; §34 forbids fuzzy as the primary mechanism (ADR-0021) |
| generic external node | **declared node** | implies a mechanism that does not exist — a node with no plugin behind it is ordinary (ADR-0011) |
| the plugin's health value | the plugin's **StateContribution** | several plugins observe one node; only the state engine produces `health` (ADR-0013) |
| health rolls up / propagates | health is **local** | a propagated value has no raw signal behind it, and it flattens the shape the canvas exists to show (ADR-0027) |
| the plugin returned UNKNOWN | the plugin **abstained** | `UNKNOWN` is discarded in composition, so returning it for something observed deletes the plugin's own vote (ADR-0024, ADR-0029) |
| plugin config in the database | **file-declared** config | the MVP ships without auth; config is bound at startup, secrets are references (ADR-0014) |
| consumer group node | a **routed group** — a backing | a group is how a component consumes, not a component; unrouted, it is invisible (ADR-0036, ADR-0040) |
| topic filter / topic pattern | the **include prefix** list | scope is exact leading-substring match; no regex, no glob (ADR-0037) |
