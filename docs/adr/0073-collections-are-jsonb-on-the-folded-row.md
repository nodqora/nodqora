# ADR-0073: Node collections are JSONB columns, not child tables

- **Status**: Accepted
- **Date**: 2026-09-03
- **Ticket**: [PostgreSQL schema and persistence](https://github.com/fredskor/nodqora/issues/16)

## Context

A Node carries four collections — `backings[]`, `links[]`, `sources[]`,
`metadata{}` — and `NodeState` carries `metrics{}`.

ADR-0053 and ADR-0054 leave the read path a single query shape: everything for
one environment. But **ADR-0013 makes `backings[]` a routing table** — *"a plugin
is asked to observe exactly the nodes carrying a backing of its own"* — which is
a lookup inside a collection, running every thirty seconds per plugin. It is the
only thing in the MVP that would ever want an index on a collection, so it is the
fact that decides this.

## Decision

**All five are JSONB columns on their row.** No `node_backing`, no `node_link`.

Health routing is an in-memory pass over the environment's nodes.

## Consequences

- **ADR-0050 decides this, not the routing question.** ADR-0050 makes canonical
  collection ordering a *storage obligation* with a named failure: unordered
  collections make every poll a spurious diff and `updatedAt` degenerates into a
  poll clock, silently, in the direction where nothing looks broken. A JSONB array
  **is** ordered and the diff is one comparison over the whole row. A child table
  has no inherent order, so ordering becomes an `ordinal` column every write
  renumbers and every read must `ORDER BY` — and the day someone omits the
  `ORDER BY`, ADR-0050's hazard is back with the same silent signature. Child
  tables stake the prevention of that failure on a clause in a query.
- **The routing query does not rescue the alternative**: ADR-0043's fold already
  materializes the whole environment in memory on every discovery poll, so the
  health router can route off that. If it does hit the database, it is the same
  "select the environment's nodes" the read path already runs.
- **Splitting only `backings[]` was rejected**: it pays the ordering cost on the
  collection with the most complex ordering rule — `(plugin, kind, reference)`,
  unioned across plugins under ADR-0021's contested-key union — to serve a query
  with a free in-memory answer.
- **No SQL answer to "which node carries backing X".** In the MVP only the health
  router asks, in Java. Post-MVP blast radius (§48, out of scope) would want it,
  and the fix then is a GIN index or a fold-maintained routing table, not a
  re-layout.
- **No referential integrity on a backing**, which is correct: backings reference
  physical objects living in Kubernetes and Kafka, not in this database. ADR-0048's
  dangling shapes are edge endpoints and `ownerKey`, and both are deliberately
  unconstrained.
- `jsonb` normalizes object key order and strips duplicate keys, so
  `metadata{}` / `metrics{}` get canonical key ordering for free. It also means a
  round-trip is not byte-identical to what a plugin emitted — harmless for a
  value-comparing diff, and worth knowing before anyone hashes a column.
