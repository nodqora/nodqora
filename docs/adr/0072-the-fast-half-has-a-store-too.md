# ADR-0072: The fast half has a store too — retained contributions folded into NodeState

- **Status**: Amended by [ADR-0104](0104-an-abstention-is-an-omission.md)
- **Date**: 2026-09-03
- **Ticket**: [PostgreSQL schema and persistence](https://github.com/fredskor/nodqora/issues/16)
- **Amends**: ADR-0013

> **Amendment (ADR-0104).** `UNKNOWN` never reaches `health_contribution`: an abstention is an
> **omission**, dropped at the store, and the state fold writes no row for a node with no surviving
> contributions. Without it a node whose only contribution abstained would get a row that exists,
> reads `UNKNOWN`, and reports an `observedAt` for an observation nobody made — two ways to be
> `UNKNOWN` that disagree about a rendered field. `observedAt = min over its contributions` is
> unchanged and now exact. Everything else below stands.

## Context

ADR-0013 makes `NodeState` *"one row, but composed from several
StateContributions"* — `payments-enricher` is observed by `kubernetes` and
`kafka` at once. ADR-0035 and ADR-0042 have those plugins poll **independently**.
ADR-0026 gives `HealthResult` the same three outcomes as `DiscoveryResult`, *for
the same reason*.

Put together: when `kubernetes` finishes a poll and reports one contribution,
composing that node's `NodeState` requires `kafka`'s contribution, which arrived
at a different moment. A search across all seventy ADRs finds **no home for a
StateContribution between polls**, and no home for the health `outcome` ADR-0056
puts on `/state`. `StateContribution` is a CONTEXT.md term with no storage.

The slow half solved this exact problem in ADR-0043 and the fast half was never
given the answer.

## Decision

**The fast half is symmetric with the slow half.**

```text
health_run(environment_key, plugin_id, outcome, reasons, recorded_at,
           payload_version)                      PK (environment_key, plugin_id)

health_contribution(node_id, plugin_id, health, raw_signal, metrics jsonb,
                    observed_at, payload_version) PK (node_id, plugin_id)
```

Contributions are **retained per plugin** under ADR-0026's three outcomes, by
the same rules ADR-0046 gives discovery. `node_state` is a **fold** of them by
ADR-0024's three steps — a derived cache, exactly like `node` / `edge` / `owner`.

**`health_contribution` is keyed by `node_id`, not by node key.**

**A composed `NodeState.observedAt` is the `min` over its contributions.**

## Consequences

- **In-place merge into `node_state` is structurally impossible, not merely
  discouraged.** ADR-0024 discards abstentions before collapsing, so the composed
  row has already thrown away the information a re-collapse would need: **you
  cannot recompute a collapse from its own output.** It is also the shape in which
  a plugin can never *stop* saying `DISABLED` — ADR-0034 has `kubernetes` emit it
  from `spec.replicas: 0`, and scaling back up would need the old contribution
  found and cleared by hand rather than replaced.
- **Keying by `node_id` preserves ADR-0050 verbatim.** A node that leaves and
  returns gets a new surrogate id and therefore a fresh `NodeState`, reading
  `UNKNOWN` for up to one fast-loop interval — which ADR-0050 calls *"correct
  rather than merely tolerable"*. Keying by node key would let a returning node
  instantly re-inherit contributions from before it vanished, quietly overturning
  that.
- **ADR-0050's "a `NodeState` write for a `nodeId` that no longer exists is
  dropped, not an error" becomes the foreign-key-miss path**, and is written as
  `INSERT … SELECT … FROM node WHERE …` so a node deleted by a concurrent fold
  yields zero rows and no exception.
- **`observedAt` is `min` because the composed state is only as fresh as its
  stalest contribution.** ADR-0026's posture is that a plugin which could not look
  must not let the others make a node look fine; `max` would let a five-second
  `kubernetes` observation present a `kafka` reading from three cycles ago as
  current. Ticket #17 renders this number as "how old is this", so it must not
  overstate.
- `health_run` is the row ADR-0056's `/state` outcome block reads — the structural
  twin of ADR-0071's header.
- Two headers rather than one table with a `capability` discriminator: they are
  written by different loops on different cadences and read by different
  endpoints, and share only a column list.
- ADR-0028 is untouched: a node nobody observes has no contributions, therefore
  no `node_state` row, and the join synthesizes `UNKNOWN` / `null` / `{}` / `null`.
