# ADR-0016: The canvas is a flow-layered pipeline; grouped layouts are later view modes

- **Status**: Accepted
- **Date**: 2026-09-01
- **Ticket**: [Canvas and node-inspector prototype](https://github.com/fredskor/nodqora/issues/7)

## Context

Product plan §7.2 lists six possible layouts as peers — left-to-right pipeline,
top-down, force-directed, hierarchical, swimlane by technology, swimlane by
team/domain — without saying which is the base.

Three prototype variants were built over the reference pipeline. The contested
pair made the choice concrete: one used **flow** as the organising axis, the
other used **ownership**, placing nodes in swimlanes by owning team so the
pipeline's crossing from `payments-platform` to `data-platform` at the
connectors became the picture.

The plan answers this against itself. §3's thirteen incident questions are ten
flow questions ("what produces this topic", "what consumes it", "where does
this data go next", "what is the end-to-end path") and one ownership question.
§46's five differentiator questions are all connectivity. §4 ranks ownership
context 7th of 9 goals and describes it as an attribute — team, repository,
runbook, alerts — which is inspector material.

Three structural problems sank ownership as an axis:

- **`Node.ownerKey` is optional and, in practice, sparsely populated.** Topics
  arrive from the `kafka` plugin with no owner unless the YAML topology supplies
  one. A layout whose primary axis is frequently null degrades into one large
  "Unowned" lane — and degrades *silently*, looking correct on a curated fixture
  and wrong in production. Every node has edges; not every node has an owner.
- **Lane area scales with (teams × pipeline length) at roughly 1/teams
  occupancy.** In the fixture the `data-platform` lane is empty for five of
  eight layers. Three teams is already two-thirds whitespace.
- **The insight lanes deliver is one edge predicate.** The cross-team hand-off
  is `owner(from) ≠ owner(to)`, which does not need the Y axis to express.

## Decision

The base layout is a **longest-path layering over flow-directed edges**
(ADR-0002), drawn left-to-right, with a greedy slot assignment so a branch keeps
its predecessor's row.

**Grouped layouts — swimlane by team/domain, swimlane by technology — are
deferred** to the post-MVP layout switcher (ADR-0018).

The **cross-team boundary is an edge treatment**, applied where
`owner(from) ≠ owner(to)`, not a layout axis.

## Consequences

- Layout needs no per-relation branching. Because edges are stored
  flow-directed, plain longest-path layering reproduces the reference
  pipeline's documented shape exactly — ADR-0002 paying off a second time.
- Staging's missing Iceberg branch re-flows to a straight line with no
  hand-placed positions. Drift as absence (ADR-0004) needs no layout
  special-casing.
- A node with no owner is ordinary. Nothing in the canvas reads `ownerKey`.
- The canvas is **width-hungry and height-light** — the ten-node fixture used
  its full width and about a sixth of its height. This is a standing constraint
  on anything that takes width from it (ADR-0019).
- Deferring layout switching defers swimlanes with it. If ownership later
  becomes densely populated, this decision is worth reopening — sparse
  ownership is the load-bearing premise, not a matter of taste.
