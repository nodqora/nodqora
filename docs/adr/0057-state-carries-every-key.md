# ADR-0057: `/state` carries every key; absence never crosses the wire

- **Status**: Accepted
- **Date**: 2026-09-02
- **Ticket**: [REST API shape](https://github.com/fredskor/nodqora/issues/13)

## Context

ADR-0028 says a node nobody observes gets **no NodeState row at all**, and that
absence reads as `UNKNOWN` / `rawSignal: null` / `metrics: {}` /
`observedAt: null` on the join. `UNKNOWN` is normal rather than exceptional:
four of the reference pipeline's ten nodes are permanently `UNKNOWN`.

Separately, ADR-0047 makes a key's absence from a `COMPLETE` snapshot — and
therefore from `/graph` — mean **deleted, immediately**.

## Decision

`/state` carries an entry for **every** node key in the environment,
synthesizing the ADR-0028 join result for the unobserved:

```json
{ "nodeKey": "trino-analytics", "health": "UNKNOWN",
  "rawSignal": null, "metrics": {}, "observedAt": null }
```

Individual **StateContributions are not exposed**. The API serves the composed
NodeState only.

## Consequences

- **Absence is already load-bearing and already means something else.** Two
  payloads, polled at different rates, in which a missing key means "deleted" in
  one and "fine, just unwatched" in the other, is a bug waiting for its first
  incident. Omitting unobserved keys would have been 40% smaller on the fixture
  and would have mirrored the storage layer literally; it was rejected for this
  reason alone.
- The client needs no defaulting branch, so there is no place for a client to
  default a missing key *wrongly*.
- Attribution survives composition without exposing contributions: ADR-0028
  already joins `rawSignal` in plugin order and namespaces `metrics` by plugin,
  so the inspector can still show its work. Exposing `contributions[]` would be
  more debuggable, but ADR-0019 designed **one** Health section rather than a
  per-plugin table, and CONTEXT.md's standing warning is that a contribution is
  not a NodeState.
- A node's absence from `/graph` is unambiguous, and `/state` never has to be
  consulted to interpret it.
