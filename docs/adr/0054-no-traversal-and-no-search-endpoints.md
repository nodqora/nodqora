# ADR-0054: No traversal endpoints and no search endpoint

- **Status**: Accepted
- **Date**: 2026-09-02
- **Ticket**: [REST API shape](https://github.com/fredskor/nodqora/issues/13)

## Context

Product plan §32 sketches `/nodes/{id}/upstream`, `/downstream` and
`GET /api/search`. §16 offers a depth control of 1 hop / 3 hops / all. The map
parked "upstream/downstream depth" here as a traversal parameter, and
[#15](https://github.com/fredskor/nodqora/issues/15) asks whether search is
client-side or an endpoint.

Two prior decisions have already removed the ground under both. ADR-0053 puts
the entire environment graph in the client's hands. ADR-0002 makes traversal
uniform — downstream is *follow outgoing*, upstream is *follow incoming*, with
no per-relation branching — and ADR-0023 fixes the identifying string set as
`key`, `displayName` and `backings[].reference`, all three of which ship in
`/graph`.

## Decision

**No traversal endpoints, no `?depth` parameter, and no depth control.**
Upstream/downstream highlighting is a client-side BFS over the loaded graph,
unbounded.

**No search endpoint.** Simple name search is client-side over the loaded graph.

## Consequences

- A round-trip to compute reachability over edges the client is already holding
  is pure latency, and a search endpoint would ask the server to grep a document
  it had just sent.
- **Unbounded depth is the deciding half, not an afterthought.** The fixture's
  longest path is six edges, and `trino-analytics` sits two hops past the last
  *discovered* node — so any default depth would hide precisely the declared
  tail that the permanently-mixed graph (ADR-0011) exists to prove. A depth
  control is also one more canvas widget of exactly the kind ADR-0018 deferred
  wholesale, and one that can be left on `1` with no sign the map is truncated.
- **§16 Dependency Exploration is out of scope for the MVP** in full: Find Path,
  Show All Paths and depth all land post-MVP with ADR-0018's deferred path
  highlighting.
- **Search becomes environment-scoped by construction**, because the client
  holds one environment. Cross-environment search is not merely unbuilt — it is
  *unavailable* without adding surface. Given drift-is-absence and an
  environment switcher, that reads as correct; it is recorded here so that
  nobody later mistakes it for an oversight. §15 global search was already out
  of scope.
- [#15](https://github.com/fredskor/nodqora/issues/15) inherits matching
  semantics and result presentation with **no API dependency at all**.
