# ADR-0006: Plugin data is namespaced opaque JSON, split across the slow/fast boundary

- **Status**: Accepted
- **Date**: 2026-09-01
- **Ticket**: [Core graph domain model](https://github.com/fredskor/nodqora/issues/3)

## Context

Product plan §57 requires nodes to carry arbitrary plugin metadata while core
fields stay technology-independent. The reference pipeline needs topic
partitions, replication and retention; connector class and config; workload
image — none of which the core has any business understanding.

Some of those values are slow (partitions, retention, connector class) and some
are fast (consumer lag, task counts), so ADR-0003's boundary cuts straight
through "plugin data".

## Decision

Plugin data is a **map keyed by plugin id** holding arbitrary JSON, stored as
JSONB, and it appears on **both** sides of the boundary:

- `Node.metadata` — slow values. Also on Edge and Backing.
- `NodeState.metrics` — fast values. Same shape.

The core treats both as **opaque**: it never branches on a key and never
validates a value. The inspector renders them generically as per-plugin
sections.

## Consequences

- A new integration adds data with no migration and no core release (§5.2).
- No schema and no validation — a plugin can write anything, including
  something the inspector renders badly.
- Anything the core *does* need to reason about must be a real field, not a
  metadata key. This is the test for whether a future value belongs in the
  core model.
- JSONB keeps values queryable in Postgres if a later feature needs it, without
  committing to that now.
