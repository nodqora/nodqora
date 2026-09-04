# ADR-0082: Outcome is encoded without hue

- **Status**: Accepted
- **Date**: 2026-09-04
- **Ticket**: [Surfacing plugin outcome and node freshness in the UI](https://github.com/fredskor/nodqora/issues/17)

## Context

ADR-0081 puts `outcome` on the same screens as `health`. They are different
questions (ADR-0026) and must not be mistakeable for one another — a `PARTIAL`
plugin read as a `DEGRADED` node is worse than showing neither.

The colour channel is already fully spent. ADR-0017 assigns five health values
five colours, and the two with no natural hue — `UNKNOWN` and `DISABLED` — are
exactly the muted greys a fourth channel would reach for. There is no unclaimed
hue that does not either collide with a health value or shout louder than one.

The shape channel is spent too: ADR-0017 assigns five distinct glyph shapes and
makes the glyph the *primary* encoding, precisely because red/green carries no
information for a substantial fraction of viewers.

## Decision

`outcome` is drawn as a **square** family in **monochrome ink**, never a hue:

| outcome | glyph |
|---|---|
| `COMPLETE` | filled rounded square |
| `PARTIAL` | outlined square, hatched |
| `FAILED` | outlined square, crossed |

Severity is carried by ink weight and by hatching, not by colour. The square is
the one primitive ADR-0017's five shapes leave free — circle, triangle, octagon,
double bar and dashed ring are all taken — so an outcome glyph cannot be read as
a health glyph even at 9px, in a screenshot, or in monochrome.

This applies to **every** surface that renders an outcome: the rail chip, the
popover, the banner, the node marks and the inspector caveat.

## Consequences

- `health` keeps the hue channel to itself, so ADR-0017's colour-blind argument
  is not diluted by a second colour-coded axis fighting it for attention.
- Outcome is legible in the same places health is: monochrome, screenshots,
  small sizes.
- **A sixth health value stays expensive and a fourth outcome value becomes
  expensive too** — both enums are closed (ADR-0003, ADR-0012/0026) and now both
  have a shape budget behind them. That cost is deliberate.
- The banner and chip carry the only non-neutral treatment on this axis — a
  border and a tinted ground — and neither is drawn from the health palette.
