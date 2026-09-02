# ADR-0053: The MVP API is three GETs, split along the ADR-0003 wall

- **Status**: Accepted
- **Date**: 2026-09-02
- **Ticket**: [REST API shape](https://github.com/fredskor/nodqora/issues/13)

## Context

ADR-0003 splits Node (slow: changes when architecture changes) from NodeState
(fast: changes every refresh) and calls it the model's load-bearing wall.
ADR-0035 and ADR-0042 pin the two loops at **5 minutes discovery / 30 seconds
health**. Product plan §32 sketches per-node `/state` and `/metrics` routes, a
`POST /api/integrations` and a `POST /api/discovery/run`.

The canvas needs little per node (ADR-0018: icon, display name, type label,
health, one optional metric line). The inspector needs everything (ADR-0019:
seven sections). The reference pipeline is ten nodes.

## Decision

The entire MVP API:

```text
GET /api/meta
GET /api/environments/{envKey}/graph
GET /api/environments/{envKey}/state
```

- **`/graph`** returns the whole slow half for one environment — **complete**
  Nodes, Edges and Owners — plus the descriptors of ADR-0055 and the outcome
  block of ADR-0056.
- **`/state`** returns every NodeState in one document, on its own cadence.
- There is **no per-node route**, no `/metrics` route, and **no write of any
  kind**. `POST /api/discovery/run` is dropped with the rest.

## Consequences

- **The wall is visible in the API.** Two bulk endpoints at the two real
  cadences; a single combined endpoint would have had to be served at 30
  seconds, refetching topology 120× more often than it changes — and the payload
  moving every 30 seconds would be the one whose `updatedAt` must mean *topology
  changed* (ADR-0050).
- **`/metrics` is a field, not a resource.** `metrics{}` belongs to NodeState
  (ADR-0003), and a separate route would let a client read metrics without the
  `health` and `rawSignal` they must be read beside.
- **Full nodes remove a loading state from the drawer.** ADR-0019's design is
  that absence is information with a designed empty state; a lazily-fetched
  drawer makes "No owner recorded" and "not loaded yet" the same pixels. It also
  removes a second representation of a Node that could drift from the first.
- Known cost: the 5-minute payload carries inspector detail the canvas never
  draws. At fixture scale that is kilobytes. **This is the decision that has to
  be revisited first if graph size grows** — §71's mitigations all assume a
  server that ships less than the whole environment.
- **The MVP is read-only end to end**: GET-only from the browser, and ADR-0042's
  build-enforced GET-only into Connect at the far end. ADR-0035 accepted
  5-minute discovery latency deliberately and said latency complaints should
  reopen ADR-0012, not the cadence — a refresh button is exactly the pressure
  valve that would let that go untested, and without auth it is also an
  unauthenticated lever for making the server hammer four upstream APIs.
- The YAML iteration loop pays for this: an edit is invisible for up to five
  minutes unless the interval is shortened in dev config. That is the accepted
  cost, and it is config, not code.
