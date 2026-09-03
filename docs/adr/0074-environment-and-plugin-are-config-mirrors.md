# ADR-0074: Environment and plugin are config mirrors; the environment key is immutable

- **Status**: Accepted
- **Date**: 2026-09-03
- **Ticket**: [PostgreSQL schema and persistence](https://github.com/fredskor/nodqora/issues/16)

## Context

ADR-0014 makes plugin and environment configuration file-declared and bound at
startup; ADR-0055 serves the environment roster as *static* meta, `{key,
displayName}` and nothing else. Config is the source of truth for both rosters,
so a table cannot be a second one.

But ADR-0043 imposes two cleanup rules as imperative sweeps:

> A stored snapshot whose `(plugin, environment)` is no longer in the bound
> config is discarded at startup; so are the snapshots and folded rows of a
> removed environment.

Written by hand that is a `DELETE … WHERE environment_key NOT IN (…)` against
eight tables, each of which someone must remember to extend when the ninth
arrives. ADR-0043 states the cost of missing one: *"its nodes become immortal."*

Research [#4](https://github.com/fredskor/nodqora/issues/4) separately found
DataHub treats its URNs as immutable and warns that re-scoping orphans everything
hanging off the old key.

## Decision

**`environment(key, display_name)` and `plugin(id)` exist as tables, reconciled
from the bound config at startup** — upsert what is configured, delete what is
not — with `ON DELETE CASCADE` foreign keys from every table carrying the key.

**The environment key is immutable. A rename is a delete plus a create.**

## Consequences

- **ADR-0043's two cleanup rules stop being a checklist and become an
  invariant.** Deleting the `staging` row takes its snapshots, entries, health
  runs, contributions, nodes, edges, owners and states with it — including from
  the table someone adds next year without reading ADR-0043.
- **These are not a second source of truth.** Nothing writes them but startup
  reconciliation; they hold no connection config, no secrets, no cadences.
  `environment` holds exactly the `{key, displayName}` ADR-0055 already publishes.
- **`plugin` holding an id does not breach ADR-0015.** ADR-0015 forbids the core
  knowing plugin *shapes*, not plugin *ids* — `sources[]`, `backings[].plugin` and
  `metadata{}` keys are already plugin ids stored as core data.
- **Research #4's orphaning warning does not reach us.** It concerns a store whose
  entities are authored and unrecoverable. Here every row is rederivable from four
  stateless full-snapshot plugins (ADR-0012), so a rename costs one discovery cycle
  of emptiness after a deliberate operator edit — ADR-0047's argument exactly:
  deletion here is non-destructive and self-healing.
- **Cost, recorded rather than buried:** a *typo* in the environment config is
  indistinguishable from a removal and cascades that environment's entire store
  away at startup. It self-heals within one cycle, so the damage is bounded, but
  it is a genuinely destructive action triggered by a text edit. Taken anyway,
  because the alternative is not safety — it is the same deletion written out by
  hand across eight tables with a chance of missing one, which fails toward
  **immortal nodes**: permanent, invisible, and corrupting ADR-0004's
  drift-is-absence, which ADR-0047 already ranked as worse than over-deletion.
- The `environment` row doubles as the lock target in ADR-0075.
