# ADR-0059: Polling with published intervals and a conditional graph fetch

- **Status**: Accepted
- **Date**: 2026-09-02
- **Ticket**: [REST API shape](https://github.com/fredskor/nodqora/issues/13)

## Context

Product plan §70 says polling is sufficient initially and places SSE explicitly
later. The server's real cadences are file-declared per plugin — ADR-0035 and
ADR-0042 both landed on 5 minutes discovery / 30 seconds health, and ADR-0014
put them in files precisely so they could change without a rebuild.

Topology changes far less often than every five minutes. On the fixture, almost
never.

## Decision

Polling. No SSE, no WebSocket.

- **`/api/meta` publishes the poll intervals** — the minimum over the configured
  plugin cadences per loop — so the frontend does not hardcode a copy of file
  config:

  ```json
  { "refresh": { "graphSeconds": 300, "stateSeconds": 30 } }
  ```

- **`/graph` supports `ETag` / `If-None-Match`**, the ETag being a hash of the
  canonically serialized graph document — canonically, because ADR-0050 already
  requires canonical collection ordering, so an unstable order would flap the
  ETag exactly as it would flap `updatedAt`.
- **`/state` gets no ETag.** Its top-level `observedAt` moves on every poll by
  design, so it could never honestly return 304.

## Consequences

- A topology poll is a 304 whenever nothing changed, which is nearly always.
- **Stated plainly rather than oversold: a content-hash ETag saves the network,
  not the query.** The server still builds the document in order to hash it. If
  that cost ever matters, the replacement is a fold generation counter bumped
  when ADR-0050's diff finds a change — the information already exists.
- Client-side constants were rejected because they disagree with the server the
  first time anyone tunes a plugin's cadence, and the disagreement is silent.
- SSE would give better liveness and no wasted polls, but it adds connection
  lifecycle, reconnect and backfill to an MVP whose health loop is a 30-second
  timer on the server anyway. §70 is followed rather than pre-empted.
