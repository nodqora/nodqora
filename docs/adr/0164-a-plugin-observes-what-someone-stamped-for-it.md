# ADR-0164: A plugin observes what someone stamped for it; a template stamps at scale and an annotation corrects it

- **Status**: Accepted; amended by [ADR-0167](0167-a-recipe-picks-the-series-and-a-selector-binds-them.md) — the template is a map keyed by recipe, and `topology.io/prometheus` separates bindings with `;`
- **Date**: 2026-09-12
- **Ticket**: [How a plugin observes nodes it did not discover](https://github.com/nodqora/nodqora/issues/103)
- **Amends**: [ADR-0032](0032-closed-annotation-vocabulary-and-composed-links.md) — a tenth key and a second template family. [ADR-0022](0022-attribution-by-the-knowing-plugin.md) — its union rule gains one stated exception. [ADR-0010](0010-plugin-entity-two-capabilities.md) — the plugin list is no longer four.

## Context

Product plan §12 puts Prometheus in Tier 1, and it is the first plugin that owns
no technology domain. Every plugin so far discovers the objects it later
observes, so ADR-0013's routing rule — *"a plugin is asked about exactly the
nodes carrying at least one `Backing` whose plugin is its own"* — has never been
asked to route a plugin that discovers nothing. Read literally at
`HealthEngine:89`, nothing carries a `prometheus` backing and the plugin is
handed an empty list forever.

The ticket framed that as a defect in the routing contract and offered two
repairs: let Prometheus discover and claim its own backings, or give routing a
second mode in which a capability declares it observes the whole environment.
Neither is needed, because the contract already has the case.

**`kafka` is already a plugin that reads a signal it cannot attribute.**
ADR-0022 states it outright — *"The `kafka` plugin attributes nothing"* — and
resolves it by inverting who stamps: whichever plugin knows the node key emits
the backing, whatever technology the object belongs to. `connect` stamps a
`kubernetes` workload onto every connector; an annotation or the YAML stamps a
`kafka` consumer group onto the enricher. The plugin that can *read* the signal
is routinely not the one that can say whose signal it is, and the backing is how
the second tells the first.

**A backing reference is already allowed to be a query, declared and never
discovered.** ADR-0148 introduced `{ kind: pods, reference:
<namespace>/<key>=<value>[,…] }` for a workload whose owner is a kind the plugin
cannot produce, and settled the definitional question in passing: *"ADR-0005 is
untouched: a pod is still not a node and still not a backing. The backing names a
query."*

Prometheus is those two precedents composed. What it genuinely lacks is a
stamper, and at a scale neither precedent had to face: `connect` stamps one
config-declared workload onto every connector, and ADR-0148's selector is a rare
hand-written repair, but a Prometheus reference is wanted on *every* observed
workload and is pure configuration that nobody discovers.

ADR-0023 also named this arrival, four months early and by name: *"A name that
is genuinely not a backing — a Git repository, a Prometheus job, a legacy name
kept after a rename — has no home… It reopens when a plugin arrives whose
identifiers are neither."* It reopens here, and the answer is that a Prometheus
reference **is** a backing, on ADR-0148's reading of the word.

## Decision

**Routing does not change. A plugin observes exactly the nodes someone stamped a
backing of its own onto, and Prometheus is stamped like anything else.**
`HealthEngine` is untouched, `HealthCapability` is untouched, and no seam is
opened — so ADR-0115, which would have licensed one, is not invoked.

Three routes write a `prometheus` backing, all of them inside plugins:

- **A per-environment template in the `kubernetes` plugin config**, stamped onto
  every workload it discovers. This is ADR-0022's `connect` pattern with
  interpolation, and the interpolation is ADR-0032's, over the values that ADR
  already exposes:

  ```yaml
  kubernetes:
    prometheus: "<template over {name}, {namespace}, {kind}>"
  ```

  **No template configured ⇒ no stamp**, exactly as ADR-0032 says *no template ⇒
  no link*. That is the operator's per-environment off switch, and it is the
  whole of the opt-in.

- **`topology.io/prometheus`, ADR-0032's tenth key**, on the workload. It is the
  precise analogue of `topology.io/consumer-groups`, which already writes a
  foreign domain's backing from an annotation, and it is comma-separated on the
  same grounds and unions on `(plugin, kind, reference)`.

- **A `prometheus:` node key in YAML**, mirroring `consumerGroups:`. One named
  key naming one fixed foreign domain — **not** an arbitrary-backing escape
  hatch, which `yaml` has never had and does not gain here.

**The annotation replaces the template rather than unioning with it.** Where
`topology.io/prometheus` is present the plugin does not apply the template to
that workload.

This is the one exception to ADR-0022's *"both routes writing the same backing is
a union, not a conflict"*, and it is stated rather than left to be discovered.
The union rule governs two humans declaring two different facts — an annotation
naming one consumer group and the YAML naming another, both true. A template and
its own annotation are not that: the template is a guess about a single fact and
the annotation is that guess corrected. Unioning them would leave a reference
already known to be wrong sitting in the inspector and in ADR-0023's search
index, and would spend a query per poll proving it. The rule is unchanged
everywhere else, including across the `yaml` route and across plugins.

**The `kind` constant and the grammar of `reference` are not decided here.** This
ADR fixes that there is a `prometheus` backing and how it arrives; what its
reference says, and how the plugin turns it into a query, is
[#106](https://github.com/nodqora/nodqora/issues/106)'s.

## Consequences

- **Nothing in `nodqora-core` changes**, which is the result worth stating
  loudest. The routing rule that looked broken was load-bearing and correct; what
  was missing sat one layer out, in the plugins that know node keys. ADR-0115
  was charted as the licence for a core seam and turns out not to be needed —
  the cheapest reading of a contract was not the honest one.

- **Prometheus is the first Health-only plugin**, the mirror of `yaml`'s
  Discovery-only. It is legal with no change: ADR-0010 makes a capability present
  *iff* the bean implements the interface, and `BoundConfiguration.configuredPairs`
  is an `instanceof` filter over exactly that. The asymmetry it exposes is
  already exercised — `kafka` writes `metrics{}` onto `payments-enricher`, whose
  `sources[]` names `kubernetes` and `yaml` and not `kafka`, because `sources[]`
  is discovery provenance (ADR-0008) and has never claimed to list observers.

- **ADR-0010's four ids become five**, and ADR-0085's roster and `PluginOrder`'s
  two lists gain `prometheus`. Forgetting the order lists is survivable rather
  than fatal: `registryRank` sorts an unlisted plugin last rather than throwing,
  *"a fold must never fail over ordering"*.

- **Every workload in a configured environment carries a `prometheus` backing**,
  whether or not any series exist for it. The ones with none abstain by omission
  (ADR-0104), which is already the correct reading and not a new failure mode.
  The real cost lands in ADR-0023: `backings[].reference` is part of a node's
  identifying string set, so a blanket stamp puts a prometheus reference on every
  card in the graph. ADR-0148 called one selector *"noise, not a hazard"* for a
  rare repair; at this scale the judgement is #106's to make with the grammar in
  hand, and a reference that echoes the node key costs the index nothing while a
  PromQL fragment is noise everywhere.

- **ADR-0013's batching is unchanged and actively favourable.** One call per
  `(plugin, environment)` handed the routed set is the shape a range query over a
  label matcher wants anyway; the per-node signature ADR-0013 rejected would have
  been worse here than for any existing plugin.

- **ADR-0005 stays untouched, for the second time and the same reason.** A time
  series is not a node and not a physical object; the backing names the query
  that finds it, and the series it matches are read and discarded without one of
  them being named.

- **The reference is validated nowhere but at the point of use** — ADR-0148's
  standing price for the opaque-triple contract, inherited whole. A template
  typo costs a warning per poll and an unobserved node rather than a boot
  failure, and it is why the parse #106 designs should fail toward *no series*
  rather than toward a matcher matching everything.

- **A service that is not a Kubernetes workload keeps a route**, which is the
  reason the YAML key is in scope rather than deferred. ADR-0022 rejected
  annotation-only attribution for consumer groups on exactly this ground — *"a
  Kafka consumer that is not a Kubernetes workload — a Flink job, a VM — would
  have no route at all"* — and a service on a VM is the same node with the same
  problem.

- **What reopens this**: a plugin that owns no technology domain *and* cannot be
  stamped, because no plugin knows both the node key and the reference. Every
  route here depends on some discovering plugin being able to write the
  reference down; a source whose identifiers only it can compute would defeat all
  three, and that is when routing's second mode gets asked for again.
