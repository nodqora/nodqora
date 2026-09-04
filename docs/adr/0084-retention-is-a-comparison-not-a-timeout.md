# ADR-0084: Retention is a comparison, not a timeout

- **Status**: Accepted
- **Date**: 2026-09-04
- **Ticket**: [Surfacing plugin outcome and node freshness in the UI](https://github.com/fredskor/nodqora/issues/17)

## Context

ADR-0083 marks a node whose topology was *retained* rather than confirmed. That
needs a definition of retained, and the ticket framed it as the frontend's call:
**ADR-0026 ships no staleness TTL**, deliberately, because "a TTL only adds a
clock-skew failure mode".

The obvious repair is a threshold — *stale after N × the plugin's discovery
interval*. It does not survive contact with the API. **ADR-0059 publishes
`refresh.graphSeconds` as the minimum over the configured plugin cadences**, not
a per-plugin figure, so a threshold could only ever be applied at the *tightest*
plugin's cadence. A plugin polling at fifteen minutes would be marked stale at
ten, on a healthy poll — the exact cry-wolf failure this ticket was told to
avoid. Making a threshold honest would require widening `/api/meta` to publish
per-plugin cadences: new API surface, bought for a constant.

## Decision

**A source is retained when `confirmedAt` is older than its plugin's outcome
`recordedAt` for this environment.** No threshold, no TTL, no constant.

```text
retained(source) := source.confirmedAt < plugins[source.plugin].recordedAt
```

The comparison is exact because the fold makes it exact (ADR-0046):

| outcome | snapshot effect | what the timestamps say |
|---|---|---|
| `COMPLETE` | replaces | every key it carries was confirmed at this poll — the two timestamps agree |
| `PARTIAL` | upserts and retains, whole nodes | confirmed keys agree; retained keys are older |
| `FAILED` | no-op | every key is older |

This **pins `recordedAt` as the field name** for ADR-0056's "when it was
recorded" on each entry of the graph document's `plugins[]`.

Two properties fall out rather than being designed in. It is **per key, not per
plugin** — a `PARTIAL` that confirmed eight nodes and retained two marks exactly
two, which is the distinction ADR-0056 said an environment-level banner could
not make, now available without a clock. And there is **no skew**: both
timestamps are produced by the same server and travel in the same document, so
the browser never compares a server time to its own.

## Consequences

- The frontend holds **no** staleness constant. ADR-0026's "no TTL" is honoured
  rather than worked around, and the ticket's premise — that the frontend must
  decide what counts as old — turns out to be answerable by not deciding.
- `/api/meta` is **not** widened. ADR-0059's per-loop minimum stays fit for its
  one purpose, driving the poll timers.
- Retention is **not** a duration, so the UI never claims one. Section 7 renders
  the elapsed time as information (`confirmed 23m ago`) and the `retained` tag
  as the judgement, and the two are computed differently on purpose.
- A plugin whose poll interval is long will show large elapsed times with no
  `retained` tag, which is correct and was the failure mode of every threshold
  considered.
- The rule reaches health only indirectly. `/state`'s outcome drives ADR-0083's
  *blind* mark, which is a live comparison of contributions rather than of
  timestamps — `observedAt` stays what ADR-0026 made it, a rendered fact with no
  rule behind it.
