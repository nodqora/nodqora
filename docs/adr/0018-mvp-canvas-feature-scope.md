# ADR-0018: MVP canvas feature scope

- **Status**: Accepted
- **Date**: 2026-09-01
- **Ticket**: [Canvas and node-inspector prototype](https://github.com/fredskor/nodqora/issues/7)

## Context

Product plan §7.2 lists twelve canvas capabilities. §47's required-feature list
is markedly narrower — it names an interactive graph, a node inspector,
upstream/downstream navigation and environment support, and none of the filters,
overlays or alternate layouts. The prototype tested which of the twelve earn
their place on a ten-node graph.

## Decision

**In the MVP:**

- pan, zoom, fit-to-screen
- node rendering: type icon, display name, type label, health (ADR-0017)
- an optional metric line per node, behind a canvas-level toggle, default on
- upstream/downstream highlighting on selection
- environment switching
- simple name search (issue #15)

**Deferred:**

- **minimap** — near-useless at fixture scale, and cheap to restore later
- **filter by component type**, **filter by health**
- **collapse/expand subgraphs**
- **path highlighting** (§16 Find Path / Show All Paths)
- **layout switching**, and with it the swimlane layouts (ADR-0016)

**Environment is not a filter.** It is a scope (ADR-0004) and is rendered as a
switcher. A node absent from an environment has no row, so there is nothing to
filter — drift is absence.

## Consequences

- Most deferred items are small. They are deferred for clutter and scope
  discipline, not cost — the MVP canvas should prove the central experience
  rather than the feature list.
- **Filter by health is the most likely to be pulled forward**, because it
  serves the incident case directly.
- **Collapse/expand cannot be pulled forward as-is.** There is no grouping
  entity in the core model — no system, domain or subgraph — so there is
  nothing to collapse *to*. It needs a domain decision first, not just UI work.
- Metrics being opt-in satisfies §14's requirement that users can toggle
  overlays to avoid clutter.
