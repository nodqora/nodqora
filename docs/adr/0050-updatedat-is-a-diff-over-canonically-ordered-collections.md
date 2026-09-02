# ADR-0050: `updatedAt` is a diff over canonically ordered collections

- **Status**: Accepted
- **Date**: 2026-09-02
- **Ticket**: [Discovery-engine merge semantics](https://github.com/fredskor/nodqora/issues/12)

## Context

ADR-0003 makes this load-bearing: `updatedAt` moves **only** when topology
changes, never on a health refresh. ADR-0043 rewrites every node in an
environment on every snapshot — twelve times an hour per plugin — so the write
path can no longer be trusted to imply a change.

## Decision

**`updatedAt` moves iff the folded row differs from the stored row in any field
except `id`, `discoveredAt` and `updatedAt`.** Everything on the slow side counts,
including `sources[]`. Same rule for edges and owners.

**The fold canonically orders every collection before comparing and before
storing:**

| collection | canonical order |
|---|---|
| `sources[]` | registry order — `yaml`, `kubernetes`, `kafka`, `connect` |
| `backings[]` | by `(plugin, kind, reference)` — ADR-0022's element identity is already a total order |
| `links[]` | by plugin precedence (ADR-0044), then emission order within a plugin |
| `metadata{}` / `metrics{}` | keys sorted |

**`discoveredAt` is when the fold created the row.** A node that leaves and
returns gets a new one.

**A `NodeState` write for a `nodeId` that no longer exists is dropped, not an
error.**

## Consequences

- `sources: [yaml, kubernetes]` degrading to `[yaml]` when Kubernetes loses sight
  of `enricher-v2` is correctly a topology change with a timestamp on it.
- **The ordering rule is the decision, not an implementation note.** Three node
  fields are collections assembled from up to four snapshots and none arrives in a
  stable order — the Kubernetes API promises no list ordering, `backings[]` is
  unioned across plugins (ADR-0022), `links[]` has four writers (ADR-0032,
  ADR-0039). Comparing them as built makes **every poll a spurious diff**, and
  `updatedAt` degenerates into a poll clock: ADR-0003's boundary silently stops
  meaning anything, and it fails in the direction where nothing looks broken. This
  is the hazard research #4 found in Backstage's silent-skip flapping on unstable
  list order, which ADR-0021 already cited once, recurring one layer up.
- **`discoveredAt` resets** because ADR-0043 keeps no tombstone, and adding one to
  preserve it would re-import exactly the retained deletion state ADR-0047 and this
  ADR spent their arguments removing — for a field nothing in ADR-0018's canvas
  scope reads except to display it. Cost: an ACL blip resets a node's "first seen"
  date.
- A node that disappears and returns gets a new surrogate `id` and therefore a
  fresh `NodeState`, reading `UNKNOWN` for up to one fast-loop interval. That is
  correct rather than merely tolerable: we genuinely have no current observation of
  a node just re-created, and ADR-0028 already says the honest rendering is
  `UNKNOWN` with `observedAt: null`.
- The slow fold and the fast health loop run concurrently, so the health loop can
  write a contribution for a node the fold has just deleted. The observation is
  genuinely obsolete, and failing the poll over it would let one deleted node take
  out an entire plugin's cycle.
