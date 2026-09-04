# ADR-0085: `plugins[]` is a config roster, and an unreported pair carries nulls

- **Status**: Accepted
- **Date**: 2026-09-04
- **Ticket**: [The empty and repopulating environment](https://github.com/fredskor/nodqora/issues/19)
- **Amends**: ADR-0056

## Context

ADR-0080 empties the store on a `payload_version` bump, deliberately, so the
graph goes *empty and repopulating* rather than *partial and wrong*. It recorded
the cost: *"ADR-0053's three GETs give `/graph` no way to say 'I am
repopulating'."* The same shape arrives without any version bump on a fresh
deployment, before any plugin's first poll.

ADR-0056 calls the outcome block a projection *"computed at read time from the
snapshot store"*. Read literally, a `(plugin, environment)` pair that has never
reported has no row in the store and therefore no entry in `plugins[]`, and
ADR-0081's chip reads `0 plugins` on a cold environment.

The fact needed to do better already exists. **ADR-0074 reconciles
`environment(key, display_name)` and `plugin(id)` from the bound config at
startup.** The set of pairs that *ought* to have reported is known independently
of the store, so "nobody has ever polled `kubernetes` in `production`" is
expressible today — no fourth endpoint, no new stored field.

## Decision

**`plugins[]` carries an entry for every configured `(plugin, capability)` pair
for the environment, always** — whether or not that pair has ever reported. It
is a config roster left-joined with the store, not a store projection.

**An unreported pair ships `outcome: null` and `recordedAt: null`.** The
`outcome` enum stays three-valued.

```json
{ "plugins": [
    { "plugin": "yaml",       "capability": "DISCOVERY", "outcome": "COMPLETE",
      "recordedAt": "2026-09-04T10:31:02Z" },
    { "plugin": "kubernetes", "capability": "DISCOVERY", "outcome": null,
      "recordedAt": null } ] }
```

## Consequences

- **This is ADR-0057's argument one level up.** `/state` carries every node key
  because *"absence is already load-bearing and already means something else"* —
  a missing key means **deleted** on `/graph`. The collision repeats here: under
  ADR-0046 and ADR-0047 an absent thing means the plugin looked and it was not
  there. A missing `plugins[]` entry may not also mean *never looked*.
- **A fourth `outcome` value is disqualified, not merely beaten.** ADR-0046
  makes `outcome` the store's **transition function** — `COMPLETE` replaces,
  `PARTIAL` upserts-and-retains, `FAILED` no-ops. Every value names a store
  effect. A `PENDING` would name none, because `outcome` is a field *of a
  `DiscoveryResult`* and the whole content of this state is that no result
  exists. It would put a non-result inside the type that means *a plugin
  returned something*, in the one enum ADR-0012 closed on purpose, and every
  plugin author and every fold branch would pay for a value the fold can never
  see.
- **`null` is the house answer twice over.** ADR-0012 fixed `null` as *no
  opinion*; ADR-0058 forbids the API fabricating a value no plugin supplied.
  Nobody supplied an outcome here. A sibling `reported: false` was rejected as a
  second field that can disagree with the first.
- **ADR-0056 is amended**: the outcome block reads config *and* store, so
  `/graph` now has three sources rather than two. Accepted — the roster is the
  cheaper half of the join and it is already in a table.
- **ADR-0084 needs no amendment.** `sources[]` is joined from
  `plugin_snapshot_entry` rows (ADR-0079), so a plugin that has never reported
  appears in **zero** nodes' `sources[]`. `retained(source) := source.confirmedAt
  < plugins[source.plugin].recordedAt` never meets a null on either side: the
  comparison is **unreachable** on a cold store, not undefined.
- **Cost:** `outcome` becomes nullable, so consumers branch where a `switch`
  could have been total. Taken because every consumer branches anyway — the
  rendered text genuinely differs (ADR-0087, ADR-0088, ADR-0089).
- The chip's denominator is now a **config** count and never shrinks because
  nothing has reported. That is what makes ADR-0089's summary honest.
