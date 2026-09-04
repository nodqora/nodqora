# ADR-0096: Every selection change pushes a history entry, and the node key rides an environment switch

- **Status**: Accepted
- **Date**: 2026-09-04
- **Ticket**: [Frontend routing and deep-linking](https://github.com/fredskor/nodqora/issues/21)

## Context

Two questions about what the URL does as the user moves, which turn out to compose.

**History.** ADR-0019 makes each Upstream/Downstream peer *"a control that
reselects"*, so hopping `payments-api` → `payments.events.raw.v1` →
`payments-enricher` is a walk through the graph. Whether Back retraces that walk
or leaves the app is undecided.

**The switch.** ADR-0004 makes production and staging `payments-api` two rows
**sharing the key**, so carrying the key across an environment switch is a
well-defined operation rather than a guess.

## Decision

**Every selection change pushes a history entry — including clearing to none.**

**The `?node=` key rides an environment switch**, resolving in the new scope and
falling through to ADR-0095's states when absent.

## Consequences

- **Uniform push, because splitting it reopens ADR-0069's arbitration.** That ADR
  collapsed click and search-pick into *one* selection state precisely because
  *"had search carried its own notion of a 'current' node, the drawer would need to
  arbitrate between two."* Pushing for peer-clicks and replacing for canvas-clicks
  would give two paths to an identical state different history effects — the same
  warning one layer up.
- **`replace` was rejected on the incident case.** An SRE tracing upstream through
  the drawer who presses Back would be thrown out of the app entirely, losing their
  place during exactly the scenario the fixture's second health scenario exists to
  model. Against that, `push`'s worst case — leaving the app takes several Backs
  after a long exploration — is mild and universal on the web.
- **Deselection pushes too, for one rule rather than two.** Back from a closed
  drawer reopens the last node. Asymmetry here would need an explanation nobody
  has.
- **Carrying the key across the switch is not the affordance ADR-0070 declined.**
  That ADR refused a "try production?" control inside a search result because
  *"search must not move [the scope] out from under the reader."* Here the reader
  moved the scope themselves, using the one control whose entire job is moving it;
  the only question was whether the selection follows the frame. Nothing is moved
  out from under anyone, and ADR-0019's Identity section already names the
  environment in the drawer, so the new scope is never implicit.
- **This is §23 comparison with no new model.** ADR-0004 already noted that
  environment comparison is *"a set diff on `key` across two environments — no new
  model"*; the switcher plus a carried key is the one-node version, available now.
  Switching to staging with `trino-analytics` selected lands on ADR-0095's sentence,
  which **is** drift-is-absence, demonstrated.
- **The two halves compose into something neither was aiming at.** Because the
  switch pushes and the key rides, **Back after a switch returns to the same node
  in the previous environment** — Back/Forward becomes a side-by-side diff of one
  node across two scopes. Recorded because it is the most valuable behaviour on
  this ADR and it is emergent, so a later change to either half would cost it
  silently.
- **Carry-only-when-present was rejected as self-defeating.** It deselects exactly
  in the absent case, making the drift the product exists to show the one branch
  that renders nothing.
