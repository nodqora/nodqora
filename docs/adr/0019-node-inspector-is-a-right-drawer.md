# ADR-0019: The node inspector is a right drawer with a fixed section order

- **Status**: Accepted
- **Date**: 2026-09-01
- **Ticket**: [Canvas and node-inspector prototype](https://github.com/fredskor/nodqora/issues/7)

## Context

The prototype tested three placements: a right drawer, a full-width bottom panel
in three columns, and a card anchored beside the selected node on the canvas.

Whatever the placement, the sections have to render well at both metadata
extremes the fixture supplies on purpose: `payments-api` carries every ownership
field and eight links; `stripe-webhooks` has a name, a type and nothing else.

Product plan §7.3 sketches different inspector layouts per node type — one for a
service, one for a Kafka topic, one for a connector.

## Decision

A **right drawer**, fixed width, one scrolling column, with a **fixed section
order** for every node type:

1. Identity — display name, node key, health chip, type label, environment
2. **Health** — normalized value, raw signal, metrics by plugin namespace
3. **Connections** — Upstream and Downstream, each peer a control that reselects
4. **Ownership** — team, channel, on-call
5. **Go to** — links
6. **Backings** — plugin, kind, reference
7. **Metadata** and **Discovered by** — namespaced metadata, then `sources[]`

Sections are **not per-type templates**. A Kafka topic and a service differ only
in which sections have content.

Every section renders a **designed empty state** rather than disappearing.
Absence is information: "No owner recorded. Nobody to page." beats a missing
section, which is indistinguishable from a section that failed to load.

Connections are phrased through the `RelationDescriptor` (ADR-0002), so the same
stored edge reads "delivers to" from the topic and "consumes from" from the
service.

The canvas shows **whether a node is observed at all**; the inspector shows
**which plugins contributed**. Provenance (`sources[]`) is inspector detail —
the canvas-level question is "is anything watching this?", which four of the
fixture's ten nodes answer no.

## Consequences

- The drawer takes roughly a fifth of the window's width from a layout that is
  already width-hungry and height-light (ADR-0016). This is the known cost of
  the decision. The bottom panel was the runner-up precisely because it spends
  the axis the canvas has to spare; **revisit if the canvas proves cramped on
  common screens.**
- Dropping §7.3's per-type templates removes a per-type branch from the
  frontend, consistent with the core never branching on `type` (ADR-0001).
- Raw signal is always displayed, so the inspector can always show its work —
  a normalized `DEGRADED` is never presented without the string it came from.
