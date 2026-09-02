# ADR-0043: The stored graph is a fold over a durable per-plugin snapshot store

- **Status**: Accepted
- **Date**: 2026-09-02
- **Ticket**: [Discovery-engine merge semantics](https://github.com/fredskor/nodqora/issues/12)

## Context

ADR-0012 hands the engine four **full snapshots** per environment, one per
plugin, each stateless and non-diffing. Product plan §33 describes what happens
next as create / update / delete against a stored topology — an in-place merge.

That framing generates four separate problems: how deletion is detected, how
`null` can ever mean "clear this field" (ADR-0012's named gap, doubled by
ADR-0038 — three of the four plugins emit `ownerKey: null` deliberately), how a
composed link stops being stale after a template change (ADR-0032, ADR-0039),
and how the inspector explains where a winning value came from when ADR-0008
records provenance only per node.

Research [#4](https://github.com/fredskor/nodqora/issues/4) established that
**precedence and provenance are separable**, and that three of the four surveyed
tools make manual overrides survive rediscovery without reading provenance at
merge time.

## Decision

**The engine retains each plugin's latest accepted `DiscoveryResult` per
`(plugin, environment)`. The `node` / `edge` / `owner` rows are a *fold* of
those snapshots — a derived cache, not the source of truth.**

**The fold is order-independent.** Given the same set of stored snapshots it
produces the same graph, whatever order they arrived in. This is what makes the
graph reasonable about at all, and it is why research option **D** (OpenMetadata's
emptiness guard — "fill an empty field, never overwrite a populated one") is
structurally unavailable here: the fold has no "existing populated value" to
guard, only four inputs.

**The write path has two units.**

1. **The snapshot write is per `(plugin, environment)` and atomic in itself** —
   a snapshot lands whole or not at all, under ADR-0046's transition rule. This
   is the unit ADR-0012 already defined.
2. **The fold is triggered by a snapshot landing, recomputes the whole
   environment, and commits atomically.** A reader sees the graph before or
   after that plugin's snapshot, never mid-fold.

Whole-environment recompute rather than key-scoped recompute is deliberate: a
key-scoped path needs a correct affected-key set *including the keys a snapshot
stopped carrying*, and getting it wrong yields a graph that silently no longer
equals its own inputs.

**The snapshot store is durable, and there is no special cold-start path.** On
boot it already holds the last accepted snapshot per pair; the fold runs from it
immediately and the first incoming snapshot updates its own slot exactly as in
steady state.

**Two cleanup rules.** A stored snapshot whose `(plugin, environment)` is no
longer in the bound config (ADR-0014) is discarded at startup; so are the
snapshots and folded rows of a removed environment.

## Consequences

- **Deletion, `null`, stale composed links and value explanation stop being
  designed and start being arithmetic.** A key absent from a snapshot is absent;
  `null` stays "no opinion" forever because a value disappears when the snapshot
  carrying it stops carrying it; composed links regenerate identically every poll
  because the snapshot is *replaced*, not merged into; and the fold knows which
  snapshot supplied each winning field without a single per-field column.
- **ADR-0008 is untouched.** `sources[]` stays a per-node contributor list on the
  Node. The snapshot store is a write-model table, not a Node field.
- **The graph can be rebuilt from the store at any time** — after a restart,
  after a bug in the fold, after a schema change that would otherwise need a
  migration.
- **Durability is not a nice-to-have; it is what makes the per-snapshot fold safe
  across a restart.** With an in-memory store and only the folded rows persisted,
  a restart in which `kubernetes` polls first folds over a store holding one
  snapshot and deletes four of the fixture's ten nodes and seven of its nine
  edges, restoring them minutes later. The obvious patch — wait for all four —
  reintroduces the coupling ADR-0012 removed and deadlocks whenever a plugin is
  persistently `FAILED`.
- **The graph is never a consistent instant across plugins.** At any moment it
  holds `kubernetes` from seconds ago and `connect` from minutes ago. That was
  already true of ADR-0012 and ADR-0026, and the answer is the same: return the
  timestamps and let the frontend say how old each is.
- The fold is O(keys in the environment) per snapshot — twelve times an hour per
  environment, ten nodes in the fixture. At much larger scale it wants
  key-scoping, which is a change to the fold's *trigger*, not to any semantics
  here.
- Cost: four JSON blobs per environment, against a read path that is a full graph
  traversal.
