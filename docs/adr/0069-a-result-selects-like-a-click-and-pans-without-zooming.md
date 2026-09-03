# ADR-0069: A search result selects exactly like a click, and pans without zooming

- **Status**: Accepted
- **Date**: 2026-09-03
- **Ticket**: [Simple name search behaviour](https://github.com/fredskor/nodqora/issues/15)

## Context

Selection is already fully specified: ADR-0018 puts upstream/downstream
highlighting on it, ADR-0019 opens the right drawer onto it. The open question
was whether search reuses that, and what happens to the viewport when the hit is
off-screen — which, on ADR-0016's left-to-right layered layout, it usually is.

## Decision

**Picking a result is exactly clicking the node**: one selection state, the
ADR-0018 highlight, the ADR-0019 drawer.

**Pan to bring the node into view. Do not change zoom.**

The query and its results **clear on pick**.

## Consequences

- One selection state reached two ways. Had search carried its own notion of a
  "current" node, the drawer would need to arbitrate between two, and every
  later feature touching selection would have to honour both.
- **Not changing zoom is the load-bearing half.** ADR-0019 already records that
  ADR-0016's layout is width-hungry and height-light, so a user who has zoomed
  out to see the whole flow has done deliberate work. Zooming them to one node
  destroys that to solve a problem panning has already solved. Zoom-to-fit stays
  where ADR-0018 put it — an explicit control the user asks for.
- Clearing the query keeps a stale result list from overlaying a canvas the user
  is now reading; re-running the search costs one keystroke (ADR-0068's `/`).
