# ADR-0165: The canvas is not monitoring; Prometheus contributes `rate` and `latency` and never votes on health

- **Status**: Accepted
- **Date**: 2026-09-15
- **Ticket**: [What a Prometheus observation contributes, and where ADR-0108's line falls](https://github.com/nodqora/nodqora/issues/105)
- **Amends**: [ADR-0104](0104-an-abstention-is-an-omission.md) — only an *empty* abstention is an omission.

## Context

ADR-0164 settled how a `prometheus` backing arrives. It left open what the
plugin does with the node once it has it. `StateContribution` carries a
`health`, a `rawSignal` and a `metrics` map. Metrics are the obvious half, and
product plan §11.6's list is mostly metrics. Health is the dangerous half, for
two reasons.

**ADR-0108 makes continuous monitoring and alerting Enterprise, in every form.**
Reading Prometheus's `ALERTS`, or deriving `DEGRADED` from a threshold over a
series, looks at first sight like exactly that. A capability reaches Community
by nobody deciding, which is the failure ADR-0108 was written to prevent.

But **Community has derived health from a configured threshold since
ADR-0025.** `KafkaHealth` reads `maxLag >= threshold ? DEGRADED : HEALTHY`, from
a threshold per consumer group in `KafkaConfig`, every 10–30s. If deriving a
verdict on a schedule were monitoring, that shipped plugin — and `kubernetes`'
readiness arithmetic, and the health loop itself — would already sit on the
wrong side of the line. ADR-0108's own consequences say nothing is ever
demoted, so that reading cannot be the right one.

The demo Prometheus
([Stand a Prometheus up in the demo cluster](https://github.com/nodqora/nodqora/issues/104))
also showed what a threshold would stand on. The reference aggregator serves no
HTTP, so its rate and latency are Kafka Streams gauges already windowed by the
client. Latency is `avg` and `max` only, `avg` cannot be aggregated across pods
without weights, and **no p95 exists**. Consumer lag arrives from three sources:
the `kafka` plugin, Strimzi's exporter and the client's own fetch metrics.

One further fact decides the shape. **A contribution cannot carry metrics
without a vote today.** `StateContribution.health` is non-null, and ADR-0104
drops an `UNKNOWN` contribution at `HealthStore` and again at `StateFold` —
metrics included. "Metrics only" has no expression in the current contract.

## Decision

**1. The canvas is not monitoring.** ADR-0108's *continuous monitoring and
alerting* is ADR-0107's standing query read strictly: a result **watched for
change and reported when it changes**. Notification, rules evaluated inside
Nodqora, and passing alerts on to anyone are Enterprise. A verdict painted on a
canvas someone is looking at is observation, however often it is refreshed, and
is Community. `kafka`'s lag threshold sits where it always did.

**2. Prometheus never votes on health.** Its contributions are always `UNKNOWN`.
ADR-0024's collapse, and the three plugins that vote, are untouched. A node's
colour comes only from the plugins that know its technology.

This is a product choice, not a tier one. By (1), a threshold over a series
would be Community. It is declined because the series available do not support
one — a threshold over a client-windowed `latency_avg` is amber nobody can
explain, which ADR-0025 already warns against — and because the destination asks
for numbers beside the replica count, not a fourth voter.

**3. Only an *empty* abstention is an omission.** ADR-0104 is amended:

- A contribution whose `health` is `UNKNOWN` **and whose `metrics` are
  non-empty** is stored. `rawSignal` alone does not make it non-empty.
- An `UNKNOWN` contribution with no metrics is still dropped at the store,
  exactly as before.
- `health_contribution.health`'s CHECK admits `UNKNOWN` again.
- Step 1 of ADR-0024 still discards it from the vote; the `metrics` union, which
  is namespaced by plugin (ADR-0006), includes it.

`StateContribution` does not change shape. The rejected alternatives were a
nullable `health` — a sixth state in all but name, and a null branch in three
plugins, the fold and the store — and a separate metrics channel on
`HealthResult`, which doubles the store's paths and ADR-0006's namespacing for a
distinction nobody needs.

**4. Prometheus contributes a closed vocabulary of exactly two keys: `rate` and
`latency`.** The key names a meaning; the query behind it differs by node — an
aggregator's `rate` is its Streams process rate, a topic's is the rate of its
partition offsets. This is `kubernetes`' allow-list pattern. **Consumer lag is
not a key**: `kafka` reads it from the broker and votes on it, and a second copy
under another namespace is two numbers for one fact on the same card. Units and
aggregation are
[How a node names the series that describe it](https://github.com/nodqora/nodqora/issues/106)'s;
how the two keys read is
[The metric line when two plugins have something to say](https://github.com/nodqora/nodqora/issues/107)'s.

**5. Prometheus's own alerts are placed now and built later.** Showing firing
alerts that Prometheus evaluated, as a signal on a node, is Community by (1):
Prometheus did the watching and Nodqora only observes the result. It is past this
map's destination. Evaluating alert rules inside Nodqora, or forwarding alerts,
is Enterprise.

## Consequences

- **The next plugin reads (1) instead of arguing it.** Any verdict derived from a
  source's data and painted on the canvas is Community; the moment Nodqora keeps
  the result to tell someone about a change, it is Enterprise.
- **A node only Prometheus observes now has a `node_state` row** reading
  `UNKNOWN` with metrics and an `observedAt`. This brings back ADR-0104's "two
  ways to be `UNKNOWN`", which now differ in something real: one has a
  measurement and says how old it is. ADR-0104's actual goal — `observedAt` is
  never a freshness for an observation nobody made — holds, because a metrics
  reading *is* an observation. The card must not read that timestamp as the age
  of a health verdict; that is #107's to render.
- **`observedAt` on a node with voters is now a `min` that may include a
  non-voting contribution.** Both polls are fresh or both show their age, so the
  figure stays honest, but it is no longer strictly "how old is the health".
- **Nothing any shipped plugin does changes.** `connect`, `kafka` and
  `kubernetes` each omit a node whose collapse is `UNKNOWN` before it reaches the
  store, so none of them sends `UNKNOWN` with metrics today.
- **This is a core change, licensed by ADR-0115**, whose Community consumer is
  Prometheus. ADR-0164 could say core was untouched by routing; the value
  channel is where the cost landed.
- **Adding a vote later is cheap, and removing one would not be.** If a series
  ever supports a threshold worth trusting, a Prometheus voter is a Community
  addition under (1) and meets ADR-0024 as a fourth voter then. Reversing this
  decision in the other direction would be a visible change on every canvas.
- **Adding a key is a code change**, as it is for `kubernetes`. CPU and memory
  from §11.6 arrive one key at a time, not as operator-named series; free-form
  keys would make the plugin a general dashboard, the Grafana integration the map
  ruled out.
- **Revisit trigger.** Operators configuring `rate` and `latency` and then asking
  why a node with bad numbers stays green. One such request is expected; a
  pattern of them is the evidence for a voter.
