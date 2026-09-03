# ADR-0080: Derived tables rebuild; payloads discard on version mismatch

- **Status**: Accepted
- **Date**: 2026-09-03
- **Ticket**: [PostgreSQL schema and persistence](https://github.com/fredskor/nodqora/issues/16)

## Context

The schema has two halves with opposite migration properties, and the obvious
attention lands on the wrong one.

The **derived cache** — `node` / `edge` / `owner` / `node_state` — can be dropped
and recreated, because ADR-0043 already supplies the rebuild for free: *"On boot
it already holds the last accepted snapshot per pair; the fold runs from it
immediately"*, with no special cold-start path.

The **store** is the source of truth, so it is the one thing that cannot be
rebuilt — and its payloads are JSON, which Flyway cannot see inside.
`plugin_snapshot_entry.payload` holds a serialized node, edge, owner or descriptor
whose shape is `DiscoveryResult`'s. **The escape hatch and the migration surface
are exactly complementary.**

## Decision

**One Flyway history over all tables.** The store is not special enough to live
outside it — it still needs columns, indexes and constraints managed.

**Derived tables are droppable in a migration.** The boot fold is the repopulation
step; no marker, no manual trigger.

**Payloads are never migrated.** A single application-level `payload_version`
constant is written onto every stored row. At startup, any entry or header whose
version differs from the current constant is **discarded**. The plugins repopulate
within one cadence.

## Consequences

- **Tolerant deserialization forever was rejected for having no forcing
  function**: nobody discovers they have broken an old shape, because the old shape
  only appears on somebody else's deploy. In-place JSON migration was rejected as
  machinery to preserve data that is about to be overwritten anyway — the whole
  system rests on four plugins emitting full, stateless snapshots (ADR-0012), so the
  store is at most one cadence from being rebuilt by them.
- **The version is one application constant, not per-plugin.** A per-plugin version
  invites three plugins current and one stale, which is the partial-graph state the
  next point exists to avoid.
- **It fails in the right shape.** Because every row carries the same version, a
  bump discards all four plugins together: the graph goes **empty and repopulating**,
  not **partial and wrong** — the distinction ADR-0043 drew when arguing against an
  in-memory store (*"a restart in which `kubernetes` polls first ... deletes four of
  the fixture's ten nodes"*). Empty is honest; partial is misleading.
- **`health_contribution` gets the same treatment** and self-heals in thirty seconds
  rather than five minutes.
- **Cost, stated plainly:** a deploy that bumps the contract version shows an
  **empty graph for up to one discovery cadence — five minutes** — and ADR-0053's
  three GETs give `/graph` no way to say *"I am repopulating"*. That is a genuine
  regression in an observability tool at exactly the moment someone might be looking
  at it. It is bounded, self-healing, and occurs only on a deliberate developer act.
