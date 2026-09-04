# ADR-0086: Every poll writes the header; the transitions govern entries only

- **Status**: Accepted
- **Date**: 2026-09-04
- **Ticket**: [The empty and repopulating environment](https://github.com/fredskor/nodqora/issues/19)
- **Amends**: ADR-0071

## Context

Two accepted ADRs contradict each other, and ADR-0085's cold-store roster lands
exactly on the seam.

ADR-0071 renders ADR-0046's transitions as statements about its two tables and
concludes: *"`COMPLETE` upserts the present keys then deletes the absent ones,
`PARTIAL` upserts only, **`FAILED` does nothing**."*

ADR-0084 tabulates the same three outcomes against what the timestamps say, and
for `FAILED` records *"no-op / **every key is older**"*. Every key can only be
*older* than `recordedAt` if `recordedAt` **advanced** on that failed poll while
no entry's `confirmed_at` moved. If `FAILED` truly wrote nothing, both
timestamps would sit still and no key would be older than anything.

## Decision

**A poll writes `plugin_snapshot` unconditionally** — `outcome`, `reasons` /
`cause` and `recorded_at` — whatever the outcome. ADR-0046's transition table
governs `plugin_snapshot_entry` only:

| `outcome` | header | entries |
|---|---|---|
| `COMPLETE` | written | upsert present, delete absent |
| `PARTIAL` | written | upsert present, retain absent |
| `FAILED` | written | untouched |

Header and entries still commit in one transaction (ADR-0071).

## Consequences

- **The store gains two states where it had one.** A `(plugin, environment)`
  pair now reads three ways, and the three demand different things of an
  operator:

  | store shape | meaning |
  |---|---|
  | no header | nobody has ever polled this pair |
  | header `FAILED`, no entries | we tried and could not look |
  | header `COMPLETE`, no entries | we looked and there is genuinely nothing here |

- **That distinction is the point, not a side effect.** Collapsing the first two
  means a fresh deployment with a typo'd kubeconfig renders *not read yet* —
  telling an operator to wait five minutes for a poll that will never succeed —
  where the true reading is `kubernetes · failed 20s ago · connection refused`,
  actionable immediately. It is ADR-0026's *"we could not look"* silently
  becoming *"nothing is wrong"* with the clock on the wrong side.
- **ADR-0084 is confirmed rather than repaired.** Its `FAILED` row was always
  right; ADR-0071's sentence was scoped to the entries it was describing and
  read wider than it meant. Retention keeps working through a total outage: the
  header advances, no `confirmed_at` does, so every source marks retained.
- **ADR-0046 is unamended.** Its subject is *the stored snapshot* — which keys
  are present — and the header is not a key. What changes is only where the
  boundary of "the snapshot" was assumed to lie.
- **`FAILED` stops being invisible.** ADR-0012 made the value explicit so *"a
  `FAILED` snapshot is never mistaken for an empty one"*; under ADR-0071's
  literal reading a first-ever `FAILED` left nothing behind and was mistaken for
  exactly that. This is where that promise is cashed on a cold store.
- Cost: one write per pair per poll that changes nothing but three columns,
  including on every failed poll. At four plugins over two loops that is a
  handful of narrow updates per minute, against a fold that recomputes an
  environment.
