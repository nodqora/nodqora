# ADR-0167: A recipe picks the series and a selector binds them

- **Status**: Accepted
- **Date**: 2026-09-15
- **Ticket**: [How a node names the series that describe it](https://github.com/nodqora/nodqora/issues/106)
- **Amends**: [ADR-0164](0164-a-plugin-observes-what-someone-stamped-for-it.md) — the template becomes a map, and `topology.io/prometheus` separates bindings with `;`, not `,`.

## Context

ADR-0164 decided that a `prometheus` backing exists and how it arrives: a
template in the `kubernetes` config, the `topology.io/prometheus` annotation, or
a `prometheus:` key in YAML. It left the `kind` constant and the grammar of
`reference` open. ADR-0165 narrowed what the reference has to name: the series
behind exactly two keys, `rate` and `latency`.

Two things had to be settled together. **Binding** says which series belong to a
node, and nothing can infer it: `up{job="payments-enricher"}` is that node only
because a human knows it is. **Selection** says which series are read at all, and
§11.6 rules out arbitrary metric exploration.

[Stand a Prometheus up in the demo cluster](https://github.com/nodqora/nodqora/issues/104)
supplied the facts, read off a live instance:

- **No workload series carries the Deployment name.** `namespace` matches
  exactly; the name appears only as a prefix of `pod`, whose shape differs by
  kind, or in `app`, which equals it by the demo author's choice.
- **Topic and consumer-group labels equal node keys exactly.**
- **What "rate" means depends on the instrumentation, not the Kubernetes kind.**
  The aggregator serves no HTTP; its throughput is
  `kafka_stream_thread_process_rate`. A Spring service's would be
  `http_server_requests_seconds_count`. Its Micrometer HTTP series exist, and
  count only probes and scrapes.
- **Streams latency is a client-windowed gauge**, `latency_avg` and
  `latency_max`, per pod and thread. No p95 exists.

## Decision

**A `prometheus` backing is `{ plugin: prometheus, kind: <recipe>, reference:
<selector> }`. The plugin owns the recipe, which picks the series. The operator
owns the selector, which binds them to the node.**

### The selector

`label=value[,label=value…]`: **equality only, AND-ed**, over Prometheus label
names (`[a-zA-Z_][a-zA-Z0-9_]*`, the names after relabelling) with no namespace
prefix. `namespace` is one label among others, because Prometheus has no
namespaces and a topic's selector has no namespace to name.

- **Never empty.** A selector with no pairs is rejected, not read as *every
  series*.
- **Stored canonically, pairs sorted by label name**, so ADR-0022's union on
  `(plugin, kind, reference)` dedupes two routes that wrote the same pairs in a
  different order.
- **A value containing `,`, `=` or `;` is rejected**, and so is any interpolation
  that leaves a `{…}` unresolved. The binding is dropped with a log line, never
  partly applied. DNS-1123 names and Kafka topic names contain none of these.

Regex was rejected. `pod=~{name}-[a-z0-9]+-[a-z0-9]+` works without relabelling,
but its shape differs per kind and it fails toward matching **too much**:
`payments-api-.*` silently absorbs `payments-api-v2`. ADR-0164 requires the parse
to fail toward *no series*. An operator whose pods carry no label equal to the
workload name adds one, by relabelling or a pod label; `app.kubernetes.io/name`
is Kubernetes' own recommendation.

### The recipe

A recipe fixes metric names, aggregation, window and units for both keys. It is
named after **the instrumentation that emits the series**, because Micrometer and
OpenTelemetry name the same HTTP measurement differently. Two ship:

| recipe | `rate` (per second) | `latency` (ms, mean) |
|---|---|---|
| `kafka-streams` | `sum(kafka_stream_thread_process_rate{S})` | `sum(kafka_stream_thread_process_latency_avg{S} * on(pod, thread_id) kafka_stream_thread_process_rate{S}) / sum(kafka_stream_thread_process_rate{S})` |
| `micrometer-http` | `sum(rate(http_server_requests_seconds_count{S, uri!~"/actuator.*"}[2m]))` | `sum(rate(…_sum{…}[2m])) / sum(rate(…_count{…}[2m]))`, × 1000 |

`{S}` is the selector. The `/actuator` exclusion is a regex, and it is the
plugin's own, inside a recipe; the operator never writes one.

- **`latency` is the mean time per unit of work.** It is the only figure both
  recipes can produce honestly: exact for HTTP, rate-weighted over pods and
  threads for Streams, where an unweighted `avg` under-weights the busier pod. On
  the demo the mean read 0.036 ms and `max(latency_max)` read 1 ms; a worst case
  on a card with no room to label its statistic reads as an incident that is not
  happening. A max or a p95 is a **third key** with its own meaning, added later
  as a code change.
- **One window, `2m`**, for every `rate()`. **Instant queries**, at poll time.
- **Adding a recipe is a code change**, as adding a key is under ADR-0165.

### Precedence

When more than one recipe finds series on a node, **the first in the fixed order
`kafka-streams`, `micrometer-http` that yields any key supplies both keys.** Keys
from two recipes are never mixed, so a card never shows Streams records/s beside
HTTP milliseconds. The order is a plugin constant, like `PluginOrder`, and runs
from most specific to least: an app's HTTP series are at best the less meaningful
of its two throughputs. An operator overrides it for one workload through the
annotation, which replaces the template.

Namespacing keys by recipe was rejected: it breaks ADR-0165's two-key vocabulary.
Abstaining on a collision was rejected: it blanks the numbers on precisely the
node that is better instrumented.

### The three routes

```yaml
kubernetes:
  prometheus:
    kafka-streams:   "namespace={namespace},app={name}"
    micrometer-http: "namespace={namespace},app={name}"
```

- **The template is a map keyed by recipe.** Every discovered workload is stamped
  once per configured recipe. A recipe left out is never stamped; no map is no
  stamp. One environment runs both kinds of app, and a single-recipe template
  would need an annotation on every workload of the minority kind.
- **`topology.io/prometheus: "<recipe>:<selector>[;…]"`**, interpolated over
  `{name}`, `{namespace}` and `{kind}` exactly like the template. Bindings are
  separated by `;` rather than ADR-0164's `,`, because a selector contains commas.
  Interpolation is what ADR-0032's split exists for: one manifest deployed to
  staging and production must not bind staging's node to production's series.
- **YAML: `prometheus: [{ recipe: kafka-streams, selector: "…" }]`**, a
  structured list, **not interpolated**. A YAML node has no object to interpolate
  from, and the file is written per environment (ADR-0014).

### Silence

- **A node nobody bound** carries no `prometheus` backing and is never routed.
- **A binding that matches no series** is an empty abstention: an omission under
  ADR-0104 as amended by ADR-0165, and not `UNKNOWN`.
- **A binding whose series exist but are idle** reports `rate 0`. That is a
  measurement, not silence.
- **An unknown recipe or an unparseable selector** abstains the same way and logs
  the string.

## Consequences

- **Selection stays closed and binding stays open**, each with the party who
  knows it. ADR-0028's allow-list and ADR-0165's two keys hold without the plugin
  ever running a query an operator wrote.
- **The operator must have one label equal to the workload name.** That is the
  standing price of refusing regex, and the demo's `app` label already pays it.
  `docs/running-against-your-own-cluster.md` should say so when the plugin ships.
- **An app that is neither Kafka Streams nor Micrometer HTTP gets nothing** until
  someone adds its recipe. OpenTelemetry's HTTP semantic conventions are the
  likeliest next one.
- **The search index pays almost nothing.** A selector has no `/`, so ADR-0023
  adds it as one segment, and a workload stamped by both recipes carries the same
  string twice.
- **Queries scale with workloads × configured recipes × keys** per poll. The
  plugin batches per `(plugin, environment)` as ADR-0013 intends; how is an
  implementation choice, not a grammar one.
- **Topics have a recipe-shaped answer and no route.** `kafka-topic` would read
  `rate(kafka_topic_partition_current_offset[2m])` by exact `topic=` equality, but
  only YAML can stamp a topic today; a `prometheus:` template in the `kafka`
  config would amend ADR-0164's routes. Deferred: the destination names a service.
- **Reopens when** a recipe needs a figure the mean cannot stand for, or operators
  repeatedly cannot produce a label equal to the workload name.
