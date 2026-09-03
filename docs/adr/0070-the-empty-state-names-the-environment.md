# ADR-0070: The empty search state names the environment and offers nothing further

- **Status**: Accepted
- **Date**: 2026-09-03
- **Ticket**: [Simple name search behaviour](https://github.com/fredskor/nodqora/issues/15)

## Context

Zero results is not an edge case on this fixture. Staging is missing three of
production's ten nodes — `payments-iceberg-sink`, `analytics.payments_events`
and `trino-analytics` — so an SRE scoped to staging who searches `trino` finds
nothing, and the true answer ("it exists, just not here") is precisely ADR-0004's
drift-is-absence, the most interesting thing the fixture has to say.

ADR-0054 makes search environment-scoped **by construction**: the client holds
one environment and cross-environment search is *unavailable* rather than
unbuilt.

## Decision

**The empty state names the environment**: "No node in **staging** matches
`trino`." It offers nothing further.

## Consequences

- This is ADR-0019's empty-state argument arriving in the search box. "No
  results" and "no such node anywhere" otherwise render as the same pixels, and
  an undesigned empty state discards the scope information for free.
- **A "try production?" affordance is declined twice over.** It needs the
  cross-environment surface ADR-0054 called unavailable, so it is a reopening
  rather than a UI choice; and a control that switches environment from inside a
  search result breaks ADR-0018's "environment is a scope, not a filter" —
  the scope is the frame every reading is made in, and search must not move it
  out from under the reader.
- The cost, recorded rather than hidden: a user can correctly conclude "no such
  node" about a node that exists one environment over. On this fixture that is
  three of ten. It is ADR-0054's price, paid at the point of use, and naming the
  environment is the whole of the available mitigation.
