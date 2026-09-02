# ADR-0026: Health observation declares its own outcome

- **Status**: Accepted
- **Date**: 2026-09-02
- **Ticket**: [Health normalization model](https://github.com/fredskor/nodqora/issues/9)

## Context

ADR-0024 discards abstentions, which has a sharp edge. If the `connect` plugin
cannot reach its cluster for a cycle, `payments-es-sink` composes down to just
`kubernetes` observing the shared `kafka-connect` StatefulSet at 2/2 — and the
node renders **`HEALTHY`** while nobody knows what its tasks are doing. Green
because we stopped looking is the oldest failure mode in monitoring.

The tempting fix is to return `UNKNOWN` on failure and let it beat everything.
That re-overloads `UNKNOWN`, which ADR-0024 has just made mean *abstention*, and
it lets one Connect blip erase a genuine Kubernetes signal.

Research #5 sharpens the problem: **Kafka authz failures return empty rather
than erroring.** A principal without `Describe` gets a successful response
listing nothing, so a plugin cannot always tell that it was blind — the same
finding that gave ADR-0012 its `outcome`.

## Decision

Mirror ADR-0012 on the Health side. The capability returns an outcome alongside
its contributions:

```text
HealthResult { contributions: Map<NodeKey, StateContribution>, outcome }
outcome = COMPLETE | PARTIAL(reasons[]) | FAILED(cause)
```

Same three values, same meanings, same reason for existing.

This splits two facts that were being forced into one enum: **`health` says what
we found; `outcome` says how well we looked.** Composition (ADR-0024) is
untouched. A node whose only observer failed gets `UNKNOWN` for free — no
surviving contributions, arithmetic rather than a rule. A node with a surviving
observer keeps that observer's verdict, and the failure surfaces as a
per-environment plugin status rather than as a lie about the node.

Two further rules:

**No staleness TTL.** `NodeState.observedAt` already exists; the API returns it
and the frontend renders "as of 45s ago". Health is never blanked after N
seconds: if the loop runs, `observedAt` is fresh, and if the process is dead the
whole API is dead. A TTL only adds a clock-skew failure mode.

**Backing present, object gone reads `UNKNOWN`, not `UNHEALTHY`.** The health
loop runs at 10-30s and topology discovery at 1-5 min (§69), so a
decommissioned Deployment would otherwise paint ten cycles of red for something
a human deliberately deleted. There is nothing there to observe, which is the
"could not look" branch of `UNKNOWN`. ADR-0013 already accepted the
mirror-image case — a consumer group discovered before its owning node goes
unobserved for one topology cycle — as self-healing.

## Consequences

- Plugin observation status becomes real, queryable state, which is the first
  piece of §59 (observability of the platform itself) arriving for free.
- Exposing `outcome` over HTTP belongs to
  [REST API shape](https://github.com/fredskor/nodqora/issues/13); this ADR
  fixes only that it exists and what it means.
- A `FAILED` health poll and a `FAILED` discovery poll are now the same shape,
  so the engine reports both through one mechanism.
- The residual honesty gap is narrow but real: a node observed by two plugins
  where one fails still shows the other's verdict, and only the plugin status
  says so. Accepted — the alternative contaminates every node's health with the
  worst infrastructure problem anywhere in the environment.
