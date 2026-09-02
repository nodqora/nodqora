# ADR-0052: The API is scoped by Environment and addressed by node key

- **Status**: Accepted
- **Date**: 2026-09-02
- **Ticket**: [REST API shape](https://github.com/fredskor/nodqora/issues/13)

## Context

Product plan §32 sketches `/api/topologies/{id}/graph` and `/api/nodes/{id}`.
Neither identifier exists in the model. There is no Topology entity: ADR-0004
made **Environment** the scope and `(environmentKey, key)` the natural key of
every Node and Edge, and [#7](https://github.com/fredskor/nodqora/issues/7)
settled environment as a switcher rather than a filter. The surrogate `id` does
exist, but ADR-0043 rebuilds rows by fold and ADR-0047 deletes immediately, so a
node that leaves a snapshot and returns gets a **new** `id` and a fresh
NodeState.

## Decision

Environment is the scoping resource. Everything graph-shaped hangs beneath it:

```text
GET /api/environments/{envKey}/graph
GET /api/environments/{envKey}/state
```

`/api/topologies` is **dropped**, not deferred.

Where a node is addressed, it is addressed by **key**, URL-encoded as one path
segment and matched **case-folded** per ADR-0020. The environment key is matched
**exactly** — it is operator-authored config, not discovered data.

## Consequences

- The URL mirrors the natural key, so a path that cannot express an unscoped
  node cannot express that bug. Environment-as-query-parameter was rejected for
  the opposite reason: a forgotten parameter is a cross-environment leak rather
  than a 404.
- Inventing a Topology entity now would put a second scoping concept in
  competition with Environment, in a model where drift is already defined as
  absence within a scope (ADR-0004).
- Node keys carry dots (`analytics.payments_events`, `payments.events.raw.v1`),
  so route matching must not do suffix or extension handling.
- ADR-0053 leaves no per-node route in the MVP. This decision still governs:
  the key is the identifier the `?node=` deep link carries, and the form any
  later per-node route takes.
