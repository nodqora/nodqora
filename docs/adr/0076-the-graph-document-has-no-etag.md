# ADR-0076: The graph document has no ETag

- **Status**: Accepted
- **Date**: 2026-09-03
- **Ticket**: [PostgreSQL schema and persistence](https://github.com/fredskor/nodqora/issues/16)
- **Amends**: ADR-0059

## Context

ADR-0059 makes the ETag *"a hash of the canonically serialized graph document"*
and claims the payoff: *"a topology poll is a 304 whenever nothing changed, which
is nearly always."*

ADR-0056 then put `sources[].confirmedAt` and each plugin's `recordedAt` **into
that document**. Under a healthy `COMPLETE` poll, `confirmedAt` advances for every
node every five minutes, so the document changes on every discovery cycle by
construction — and since ADR-0059 sets the client's poll interval to that same
cadence, the 304 fires roughly never.

ADR-0059 wrote the disqualifying sentence itself, about the other endpoint:
*"`/state` gets no ETag. Its top-level `observedAt` moves on every poll by design,
so it could never honestly return 304."* ADR-0056 moved `/graph` into that
category and nobody noticed.

## Decision

**`/graph` has no ETag and no conditional fetch.** `/graph` and `/state` are
symmetric: both always return 200, each carrying its own freshness.

`/api/meta` still publishes the poll intervals (ADR-0059's other half stands).

## Consequences

- **Hashing only the folded content, excluding the projections, is disqualified
  sharply.** A `PARTIAL` retains keys, so a plugin flipping `COMPLETE → PARTIAL`
  frequently changes **no folded row at all** — same nodes, same edges. The server
  would return 304 and **the client would never learn a plugin had gone blind**:
  precisely, mechanically, the failure ADR-0026 and ADR-0056 exist to prevent —
  *"an unshown `outcome` silently converts 'we could not look' into 'nothing is
  wrong'"* — implemented as an HTTP status code, and delivered to
  [ticket #17](https://github.com/fredskor/nodqora/issues/17) as a signal that
  never arrives.
- **Keeping the ETag as-is keeps every cost and loses the benefit.** ADR-0059
  already conceded a content hash *"saves the network, not the query"* — the server
  builds the whole document in order to hash it. Without the 304 it saves neither.
- **Excluding only per-poll timestamps while hashing `outcome` and `reasons` was
  the serious alternative and is declined.** It has a real hole — during a sustained
  `PARTIAL`, a key coming back into view refreshes its `confirmedAt` while content
  and outcome stay identical, so the client keeps rendering it as retained-since-
  Tuesday. That error is in the safe direction. What is not acceptable is the
  burden: it hands ticket #17 a freshness display built on timestamps that are
  sometimes real and sometimes extrapolated from a poll interval, to save bandwidth
  on a document that is a few kilobytes at fixture scale.
- **Cost:** every client re-downloads the full graph every five minutes. Ten nodes
  is nothing; ten thousand is a few megabytes per tab per five minutes, gzipped —
  and by then ADR-0059's own named replacement, *"a fold generation counter bumped
  when ADR-0050's diff finds a change"*, is available and is strictly better than a
  content hash, being O(1) rather than O(document) over information that already
  exists. This ADR does not burn that path; it declines to build the weaker version
  of it now.
- `/graph` and `/state` stop having different caching stories for reasons nobody
  can reconstruct later. The frontend has one rule.
