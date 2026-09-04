# ADR-0081: Outcome is a resting chip and an exception surface

- **Status**: Accepted
- **Date**: 2026-09-04
- **Ticket**: [Surfacing plugin outcome and node freshness in the UI](https://github.com/fredskor/nodqora/issues/17)

## Context

ADR-0056 put two things on the wire that had nowhere to render: each plugin's
`outcome` (discovery on `/graph`, health on `/state`) and a per-source
`confirmedAt` on every node. ADR-0019's inspector has a fixed seven-section
order with no slot for either, and ADR-0018's canvas scope lists no such
indicator. A `PARTIAL` plugin reached the browser and rendered nowhere.

ADR-0026 is the reason this matters: **`health` says what we found; `outcome`
says how well we looked.** An unshown `outcome` silently converts *"we could not
look"* into *"nothing is wrong"*. Its own worked example is the fixture's
`payments-es-sink` — with `connect` unreachable, ADR-0024 discards the
abstention and the node composes down to `kubernetes` at 2/2 and `kafka` at lag
120, rendering **`HEALTHY`** while nobody knows what its tasks are doing.

Three stances were prototyped over the reference pipeline, with the canvas and
inspector held fixed at ADR-0016–0019, and four fixtures: all-clear, a 23-minute
`PARTIAL`, the incident, and ADR-0026's blind case.

- **Environment-level only.** `outcome` is a property of the environment, so it
  sits in the rail beside the environment switcher and nowhere else.
- **Exception-driven.** Nothing renders while everything is `COMPLETE`; a
  non-`COMPLETE` poll raises a banner and marks the nodes it touched.
- **A permanent second channel.** Every surface that renders health also renders
  how well we looked: a rail strip, a coverage row on every node card, and an
  inspector section of its own.

## Decision

**A resting chip in the rail, and exception surfaces below it.**

- A **plugins chip** sits beside the environment switcher at all times. At rest
  it reads `4 plugins · all complete`; it opens a popover carrying every
  plugin's two capabilities, its `outcome`, its reasons or cause, and when each
  was recorded.
- **Nothing else renders while every outcome is `COMPLETE`.** A non-`COMPLETE`
  poll raises a **banner** above the canvas naming the plugin, the capability,
  the cause and the affected node count, and marks the affected nodes
  (ADR-0083).

The permanent second channel was rejected on two counts. It is **redundant with
a decision already made**: ADR-0019 gave the canvas the job of answering *"is
anything watching this?"*, and ADR-0017's dashed ring already answers it — so on
four of the fixture's ten nodes the coverage row would print "not observed"
directly beneath a glyph that means not observed. And a channel that reads
`COMPLETE COMPLETE COMPLETE` for weeks is one people stop reading, which is
precisely the wrong property for the day it changes. §14's clutter concern and
ADR-0018's "prove the central experience" point the same way.

Environment-level-only was rejected because it leaves the canvas with nothing in
the blind case. ADR-0026 accepted a **narrow** residual honesty gap — "only the
plugin status says so" — on the understanding that the plugin status is shown.
It did not license the canvas being the *last* place to find out.

The chip earns its place separately from the banner: without it, "is everything
actually being watched?" is unanswerable on a good day, and the trouble
encodings have nowhere to have come from the first time they fire.

## Consequences

- **ADR-0018 is amended.** Canvas node rendering gains conditional outcome
  marks, which the MVP scope list did not have.
- **ADR-0019 is not.** The seven-section order stands and gains no eighth
  section; outcome lands *inside* Health and freshness inside section 7
  (ADR-0083).
- The chip is the one thing on this surface with a standing cost, and it is one
  rail element and zero canvas pixels.
- ADR-0026's per-environment plugin status is now a rendered thing rather than a
  promise. §59 (observability of the platform itself) arrives further along for
  free.
- Two encodings exist that most viewers will not have seen before the day they
  matter. Accepted: the alternative is a permanent indicator on a graph where
  40% of nodes are permanently and correctly unobserved.
