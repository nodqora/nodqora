# ADR-0001: Node types and relations are open strings with a runtime descriptor registry

- **Status**: Accepted
- **Date**: 2026-09-01
- **Ticket**: [Core graph domain model](https://github.com/fredskor/nodqora/issues/3)

## Context

Product plan §5.2 makes it non-negotiable that the core is not architected
around any specific technology, and §8 adds that node and edge behaviour should
be driven by extensible metadata "instead of central enums that require a core
release whenever a new integration appears".

Against that, two forces pull toward an enum:

- The canvas must render ten distinct node types from the reference pipeline
  with distinct icons and layout treatment.
- Four of the fixture's ten node types — `external-api`,
  `elasticsearch-index`, `iceberg-table`, `query-engine` — have **no plugin
  behind them at all**. They are declared in YAML only. A plugin-registration
  mechanism that only plugins can use cannot describe them.

## Decision

`Node.type` and `Edge.relation` are **opaque strings** in the core. The core
never branches on their values.

Presentation and semantics come from a **runtime descriptor registry**:

- `TypeDescriptor` — category, icon, display label, source.
- `RelationDescriptor` — orientation and directional phrasing (see ADR-0002).

The registry is populated by **plugins and by the YAML topology alike**. A type
declared only in YAML registers a descriptor the same way a plugin does, which
is how `iceberg-table` renders correctly with nothing observing it.

An unregistered type or relation resolves to a **fallback descriptor** and
still renders. Unknown is a display state, never an error.

## Consequences

- A new integration needs no core change and no core release — the §5.2 goal.
- No compile-time exhaustiveness: a typo in YAML silently becomes a new type
  rather than failing validation. Mitigation is a lint over the registered set,
  not a core enum.
- The frontend needs the registry too — the descriptor set is part of the API
  surface, not a frontend constant. This constrains the API shape work.
- `health` is a deliberate exception and stays a closed enum — see ADR-0003.
