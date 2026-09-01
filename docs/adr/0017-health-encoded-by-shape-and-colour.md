# ADR-0017: Health is encoded by shape as well as colour

- **Status**: Accepted
- **Date**: 2026-09-01
- **Ticket**: [Canvas and node-inspector prototype](https://github.com/fredskor/nodqora/issues/7)

## Context

`Health` is a closed five-value enum (ADR-0003, product plan §13). The reference
pipeline's baseline scenario exercises all five in a single snapshot precisely
so the canvas has to distinguish them, and the fixture names the requirement:
"the canvas needs five visually distinct treatments".

Colour alone fails this twice.

**Two of the five have no natural hue.** `UNKNOWN` and `DISABLED` are both
"neither healthy nor failing" — one because nothing observes the node, the other
because something was deliberately turned off. Any honest palette renders both
as muted greys, and they collapse into each other. They are not
interchangeable: four of the fixture's ten nodes are permanently `UNKNOWN`,
while `DISABLED` in the incident scenario means a human paused a connector.

**Red/green carries no information for a substantial fraction of viewers.** For
a tool whose entire job is at-a-glance operational state, encoding the primary
signal in the one channel a common vision deficiency removes is a defect, not a
polish item.

## Decision

Every surface that renders health pairs its colour token with a **distinct
glyph shape**, and the glyph is the primary encoding:

| health | glyph |
|---|---|
| `HEALTHY` | filled circle |
| `DEGRADED` | filled triangle |
| `UNHEALTHY` | filled octagon |
| `DISABLED` | double vertical bar (pause) |
| `UNKNOWN` | dashed hollow ring |

`UNKNOWN` is styled **calm, not alarming**. It is a resting state, not an error
(CONTEXT.md) — a node that nothing observes is not a node in trouble.

## Consequences

- Health stays legible in the minimap, in monochrome, in a screenshot pasted
  into a chat, and to colourblind users.
- Adding a sixth health value would require designing a sixth glyph. That cost
  is deliberate; the enum is closed by ADR-0003 and should stay expensive to
  widen.
- The glyph set is a frontend concern and is **not** `TypeDescriptor.icon`,
  which names a node's *type* and is registered by plugins (ADR-0001). A node
  carries both: one icon for what it is, one glyph for how it is doing.
