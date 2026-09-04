# ADR-0056: Outcome and freshness are read-time projections, never stored fields

- **Status**: Accepted
- **Date**: 2026-09-02
- **Ticket**: [REST API shape](https://github.com/fredskor/nodqora/issues/13)

## Context

ADR-0026 established that health observation declares its `outcome`, and that
**`health` says what we found; `outcome` says how well we looked**. ADR-0046
recorded a cost rather than burying it: a plugin stuck `PARTIAL` retains keys
nobody has confirmed in hours, and the fold cannot distinguish "confirmed 30
seconds ago" from "retained since Tuesday". It handed this ticket a
`lastConfirmedAt` per retained key plus an exposure of discovery `outcome`.

There is a trap in the obvious shape. ADR-0050 makes `updatedAt` a **diff over
canonically ordered collections**, and `sources[]` is one of the diffed
collections — `[yaml, kubernetes]` degrading to `[yaml]` is a topology change.
Any timestamp *stored* inside the folded row therefore changes on every poll and
turns `updatedAt` into the poll clock ADR-0050 exists to prevent.

## Decision

> **Amended by [ADR-0085](0085-plugins-is-a-config-roster.md).** The outcome
> block is a **config roster left-joined with the store**, not a store
> projection: it carries an entry for every configured `(plugin, capability)`
> pair whether or not that pair has ever reported, and an unreported pair ships
> `outcome: null` and `recordedAt: null`. "Computed at read time from the
> snapshot store" read literally makes a cold environment indistinguishable from
> an empty one, and under ADR-0046 absence already means *looked and not there*.
> `sources[]` is unaffected — it is still joined from stored entries alone.

**Per environment**, each payload carries the outcome that explains it:

- `/graph` carries each plugin's **discovery** `outcome`, its reasons or cause,
  and when it was recorded.
- `/state` carries each plugin's **health** `outcome`, likewise.

**Per node**, `sources[]` widens on the wire from a list of plugin ids to a list
of `{ plugin, confirmedAt }`, where `confirmedAt` is when that plugin's snapshot
last actually carried the key.

Both are computed **at read time from the snapshot store**. Neither is a field
of the folded Node.

```json
{ "key": "payments-enricher",
  "sources": [ { "plugin": "yaml",       "confirmedAt": "..." },
               { "plugin": "kubernetes", "confirmedAt": "..." } ] }
```

## Consequences

- The folded row still diffs as `["yaml","kubernetes"]`, so ADR-0050 is intact
  and `updatedAt` still means *topology changed*.
- `sources[]` stays ADR-0008's **one** provenance mechanism and gains exactly the
  timestamp ADR-0046 asked for, rather than growing a second plugin-keyed
  structure beside it that a reader could consult instead of it.
- Environment-level outcome alone was rejected: a `PARTIAL` retains only *some*
  keys while confirming the rest, so a per-environment banner paints every
  affected node as equally suspect when most were seen 30 seconds ago.
- This is the first API field that reads the snapshot store rather than the
  folded rows. The store is the source of truth under ADR-0043, so this is
  reading the authority directly — but it means `/graph` is not servable from
  the derived tables alone.
- Nothing here is a *negative* assertion: an outcome says how well a plugin
  looked, never that something is absent. ADR-0051 stands.
