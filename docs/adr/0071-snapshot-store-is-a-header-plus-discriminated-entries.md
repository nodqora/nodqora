# ADR-0071: The snapshot store is a header plus discriminated entries, not a blob

- **Status**: Accepted
- **Date**: 2026-09-03
- **Ticket**: [PostgreSQL schema and persistence](https://github.com/fredskor/nodqora/issues/16)

## Context

ADR-0043 makes the retained `DiscoveryResult` per `(plugin, environment)` the
source of truth, and records its cost as *"four JSON blobs per environment"*.
That line is a cost estimate in a consequences section, not a storage decision,
and two later ADRs pull against it.

ADR-0046 defines `PARTIAL` as **upsert the keys present, retain the keys
absent** — per-key semantics. ADR-0056 then requires `confirmedAt` per
`(node, plugin)`: *"when that plugin's snapshot last actually carried the key"*.
Against a single blob, the first is read-modify-write over a hand-built index
and the second has nowhere to live but a field inside the serialized payload,
re-indexed by the read path on every request.

## Decision

**The store is two tables: a header per `(plugin, environment)`, and one entry
row per key that snapshot carries.**

```text
plugin_snapshot(environment_key, plugin_id, outcome, reasons, recorded_at,
                payload_version)                 PK (environment_key, plugin_id)

plugin_snapshot_entry(id, environment_key, plugin_id, entity_kind,
                      entity_key, from_key, to_key, relation,
                      payload jsonb, confirmed_at, payload_version)
```

`entity_kind` is a discriminator over `NODE`, `EDGE`, `OWNER`,
`TYPE_DESCRIPTOR` — one table, because the fold reads all four together per
plugin and the payload is opaque to the store regardless.

ADR-0046's transition rules become statements rather than procedures:
`COMPLETE` upserts the present keys then deletes the absent ones, `PARTIAL`
upserts only, `FAILED` does nothing. Header and entries commit in one
transaction, so ADR-0043's atomic snapshot write is unchanged.

**Identity is carried in columns, not in a composed string**, with two partial
unique indexes:

```sql
UNIQUE (environment_key, plugin_id, entity_kind, lower(btrim(entity_key)))
   WHERE entity_kind <> 'EDGE'
UNIQUE (environment_key, plugin_id,
        lower(btrim(from_key)), lower(btrim(to_key)), relation)
   WHERE entity_kind = 'EDGE'
```

Two rules that were unspecified anywhere before this ADR:

- **`ownerKey` is case-folded exactly like a node key** (ADR-0020).
- **`relation` is matched exactly**, not folded.

## Consequences

- **`confirmed_at` is a column**, so ADR-0056's read-time projection is a join
  rather than a parse of four payloads into a map on every `/graph` request.
- **A composed identity string was rejected because ADR-0020 pins no character
  class on the node key** — it is deliberately "a flat human string", so no
  delimiter provably cannot appear in a `fromKey`. A collision would silently
  upsert two distinct edges onto each other, which is the failure direction
  ADR-0031, ADR-0044, ADR-0045 and ADR-0048 each took the other branch to avoid.
  A hash column fixes the collision and makes the store unreadable during exactly
  the debugging session it exists for.
- Two `ON CONFLICT` targets, so the snapshot writer has two upsert paths. That
  is not really a cost: nodes/owners settle by ADR-0044 precedence and edges by
  ADR-0045 set union, so one code path pretending otherwise was never going to
  survive the fold.
- **`relation` is matched exactly** on ADR-0052's reasoning for the environment
  key — it is a vocabulary id from a registry of core built-ins (ADR-0062), not
  discovered data. **`ownerKey` is folded** on ADR-0020's — key agreement is the
  only way two writers' output becomes one entity, and the fixture's two team
  files are exactly where `payments-platform` / `Payments-Platform` would produce
  two owners and no error.
- Cost: the store now looks like a second table set, which invites someone to
  query it as if it were the graph. It is not — it holds four plugins'
  unreconciled opinions, and a `SELECT` over it returns `payments-enricher`
  three times.
