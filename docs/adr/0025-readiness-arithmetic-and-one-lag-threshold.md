# ADR-0025: Normalization is readiness arithmetic plus a single lag threshold

- **Status**: Accepted
- **Date**: 2026-09-02
- **Ticket**: [Health normalization model](https://github.com/fredskor/nodqora/issues/9)

## Context

Product plan §13 sketches four examples and says "health thresholds should be
configurable" without saying what is a threshold and what is arithmetic. The
reference pipeline pins nine concrete normalized values across two scenarios,
and research #5 verified from Kafka 4.x source what each API can actually
return.

One correction to research #5 before building on it. It states that the
fixture's two groups — 8,400 reading `HEALTHY`, 40,000 reading `DEGRADED` —
mean "a single global threshold does not reproduce it". That is overstated: any
global threshold between the two reproduces the fixture exactly. Per-group
thresholds have to be justified on their merits, which they are, but not by
that argument.

## Decision

### Kubernetes — readiness arithmetic

| ready replicas | health |
|---|---|
| all desired ready | `HEALTHY` |
| some ready | `DEGRADED` |
| none ready | `UNHEALTHY` |
| `desired = 0` | `DISABLED` |

**The arithmetic, not `status.conditions`.** Reading `Available` would suppress
the flap a rolling update causes, but `Available=True` holds at 2 of 3 replicas,
which would render the fixture's `enricher-v2` `HEALTHY` — erasing the one
Kubernetes signal the fixture cares about. A tool whose job is "what does this
look like right now" says *two of three* when two of three are up. The deploy
flap is honest and self-clearing, and `rawSignal` carries the counts.

**Only workload kinds contribute** — Deployment, StatefulSet, DaemonSet. A
Service and an Ingress have no readiness to report, so `payments-api`'s three
backings yield one workload observation, collapsed by ADR-0024.

### Kafka Connect — the same arithmetic over tasks

Connector state decides first:

| connector state | health |
|---|---|
| `FAILED` | `UNHEALTHY` |
| `PAUSED`, `STOPPED` | `DISABLED` |
| `UNASSIGNED`, `RESTARTING` | `DEGRADED` |
| `RUNNING` | task arithmetic below |

Then, for a `RUNNING` connector, every task state collapses to a boolean —
`RUNNING` or not-running, with `FAILED`, `RESTARTING` and `UNASSIGNED` alike
counting as not-running — and the replica rule applies unchanged:

| tasks | health |
|---|---|
| all running | `HEALTHY` |
| some running | `DEGRADED` |
| none running | `UNHEALTHY` |
| zero tasks | `DEGRADED` |

Research #5 verified from `AbstractHerder.connectorStatus()` that the top-level
state performs no aggregation over tasks, so reading `connector.state` alone is
a correctness bug, not a simplification. Zero tasks is `DEGRADED` rather than
`UNHEALTHY` because it is usually a momentarily unassigned connector, matching
`UNASSIGNED` above.

This answers research #5's three open shape questions: all tasks failed is
`UNHEALTHY` by symmetry with zero ready replicas; `PAUSED` and `STOPPED` both
mean `DISABLED`; and `UNASSIGNED`/`RESTARTING` are `DEGRADED`, never `UNKNOWN`.

### Kafka consumer lag — one threshold, and never `UNHEALTHY`

Across both fixture scenarios every lag-derived value is `HEALTHY` or
`DEGRADED` — including 2,100,000 and growing. That is deliberate and it is
right: **lag means behind, not broken.** A consumer that is behind is still
doing its job; broken-ness arrives from the workload crash-looping or the tasks
failing, which are other plugins' signals.

So lag maps through **one** threshold, not a pair:

```text
maxLag >= degradedThreshold  ->  DEGRADED
otherwise                    ->  HEALTHY
```

Aggregation is **max, never sum**, and it happens over *health*, not over lag:

```text
max lag over partitions within a group
  -> evaluate against that group's own threshold
  -> per-group health
  -> collapse per-group healths with ADR-0024
```

Collapsing lag first would let the group with the highest lag borrow another
group's threshold.

Per research #5's caveats: negative lag clamps to 0 and reads `HEALTHY` (a
committed offset compared against a high watermark sampled a moment earlier
legitimately goes negative); partitions with no committed offset are **skipped**,
never counted as zero.

### Topic health is borrowed, and lag-only

A topic has no readiness of its own. Its health is the collapse of the healths
of the consumer groups committing offsets on it — `raw.v1` at 40,000 is
`DEGRADED`, `enriched.v1` at max 8,400 is `HEALTHY`. This is the one place
`kafka` can attribute without help, because committed offsets name
`(group, topic-partition)` directly; ADR-0022 is untouched.

**Producer staleness is out of the MVP.** The incident scenario's "no new
records for 20m" is computable only by comparing high watermarks across two
polls, and ADR-0012 makes plugins stateless. The fixture's incident row for
`enriched.v1` is amended to `HEALTHY` accordingly.

**Partition-level topic health is out too** — no leader, or ISR below
replication factor. It needs no history and `describeTopics` already exists, but
research #5 places that call in the slow topology tier; promoting it to a
30-second loop buys a signal the fixture never exercises and that Kafka's own
tooling owns (§5.1).

### Thresholds live in the plugin's per-environment config file

```yaml
kafka:
  bootstrap: kafka-prod.internal:9092
  lag:
    degradedThreshold: 10000
    perGroup:
      enrich-consumer-prod: 25000
```

File-declared and bound at startup like all plugin configuration (ADR-0014).
Not in the YAML topology — that plugin produces *topology*, and a threshold is
configuration (ADR-0011). Not in the database — same reasoning as ADR-0014.

Keyed by **consumer group**, not by node: the core never sees a threshold
(ADR-0015) and `kafka` never needs to know whose lag it is (ADR-0022).

## Consequences

- One rule shape — all / some / none — normalizes both Kubernetes replicas and
  Connect tasks. Two technologies, one arithmetic, and the third normalizer
  (lag) is a single comparison.
- An absolute offset threshold is a crude proxy for "how far behind in time".
  Research #5 established the honest version needs throughput, which AdminClient
  does not offer at all. Per-group overrides are the escape hatch for the batch
  consumer that is legitimately a million behind.
- Every ordinary deploy paints its node `DEGRADED` for a minute or two.
  Accepted knowingly; a deploy-aware refinement is post-MVP.
- `enriched.v1` reading `HEALTHY` through the incident is a feature, not a gap:
  nothing is wrong with an idle topic. The incident's shape — enricher
  `UNHEALTHY`, upstream backing up, downstream deliberately `DISABLED` — still
  reads off the canvas without it.
- Product plan §13's Elasticsearch example (`cluster red -> UNHEALTHY`) has **no
  MVP referent**: there is no `elasticsearch` plugin and `payments-events-v1` is
  a declared node, permanently `UNKNOWN`. Recorded so nobody implements it
  chasing parity with the plan text.
