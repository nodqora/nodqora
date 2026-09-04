# ADR-0097: A deep link lands on the ordinary cold-load viewport, then pans

- **Status**: Accepted
- **Date**: 2026-09-04
- **Ticket**: [Frontend routing and deep-linking](https://github.com/fredskor/nodqora/issues/21)
- **Amends**: ADR-0018

## Context

ADR-0069 fixed the viewport for a search pick — *"pan to bring the node into view.
Do not change zoom"* — but its reasoning is explicitly about preserving deliberate
work: *"a user who has zoomed out to see the whole flow has done deliberate work.
Zooming them to one node destroys that."*

On a cold load from a deep link there is no prior viewport and no work to protect,
so ADR-0069 is **silent** here rather than binding.

## Decision

A deep link is the ordinary cold load plus a selection:

1. **fit-to-screen** (ADR-0018's existing control)
2. **pan if the node is off-screen** (ADR-0069, unchanged)
3. select — drawer (ADR-0019) and upstream/downstream highlight (ADR-0018)

## Consequences

- **Zoom-to-node was rejected on ADR-0018's own headline.** The canvas behaviour
  selection exists to produce is **upstream/downstream highlighting**; centring the
  target at a readable zoom draws that highlight where nobody can see it. A reader
  arriving from a link is the reader with the *least* context, and the neighbours
  are the context.
- **It also invents nothing.** A zoom-to-node needs a zoom constant with no
  principle behind it, in a layout ADR-0016 already describes as width-hungry and
  height-light.
- **One initial-viewport rule, not two.** A deep-linked load and a plain load reach
  the same viewport; the link contributes a selection and nothing else. Step 2 is a
  no-op on the fixture, where fit-to-screen shows the whole ten-node pipeline.
- **Step 2 is kept anyway, for the graph the fixture is not.** Once a graph outgrows
  a fit that stays readable, the selected node can land off-screen with nothing to
  correct it; reusing ADR-0069's pan costs one branch and removes that cliff.
- **Amends ADR-0018** by naming what the canvas does on load when the URL carries a
  selection. That list said what the canvas renders, not what it renders *at*, and
  fit-to-screen was listed as a control the user asks for rather than an initial
  state.
