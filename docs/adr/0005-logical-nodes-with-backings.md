# ADR-0005: The graph holds logical nodes only; physical objects are backings

- **Status**: Amended by [ADR-0013](0013-backings-route-health.md)
- **Date**: 2026-09-01
- **Ticket**: [Core graph domain model](https://github.com/fredskor/nodqora/issues/3)

> **Amendment (ADR-0013).** The backing field `adapter` is renamed `plugin`, and
> its meaning is sharpened: it names the plugin whose *technology domain* the
> object belongs to, not the plugin that resolved it. Backings additionally
> became the routing table for health observation. The decision below stands
> otherwise.

## Context

Product plan §53 draws a UX distinction between logical and runtime topology:
the main graph should primarily represent logical architecture, and runtime
instances belong in the node inspector or expandable subgraphs.

The reference pipeline's ten nodes contain no Deployments, StatefulSets,
Services, Ingresses or pods — yet the physical objects behind them are neither
uniform nor optional:

- `payments-api` is backed by a Deployment **and** a Service **and** an Ingress.
- One `kafka-connect` StatefulSet backs **both** connector nodes — the mapping
  is not one-to-one.
- `stripe-webhooks` and three other nodes are backed by **nothing**.

## Decision

The graph contains **logical nodes exclusively**.

Each Node carries zero or more **Backings** — the concrete objects an adapter
resolved it to, as `{ adapter, kind, reference }`. A backing is topology, so it
lives on the Node side of ADR-0003's boundary; the live signal observed *from* a
backing lives in NodeState.

The inspector renders backings as §53's runtime detail. `RUNS_ON` and `DEPLOYS`
go unused in the MVP.

## Consequences

- All three fixture shapes — many backings, shared backing, no backing — are
  the same mechanism with no special cases.
- A node with `backings: []` is an ordinary node whose health is `UNKNOWN`.
  Nothing in traversal, search or layout can tell a declared node from a
  discovered one, which is what keeps the permanently-mixed graph honest.
- Node count stays equal to the architecture's component count, so traversal
  depth (§16) counts logical hops — `trino-analytics` sits two hops past the
  last discovered node and depth limits count it like anything else.
- Physical objects are not addressable, searchable or traversable in the MVP.
  If "show me the pods" becomes a graph query rather than an inspector panel,
  this decision is what would need reopening.
- How an adapter *decides* which node an object backs is out of scope — see
  [Identity-resolution rules for the MVP](https://github.com/fredskor/nodqora/issues/8).
