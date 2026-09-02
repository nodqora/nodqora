# ADR-0048: A missing edge endpoint materializes a stub node; a dangling `ownerKey` is ordinary

- **Status**: Accepted
- **Date**: 2026-09-02
- **Ticket**: [Discovery-engine merge semantics](https://github.com/fredskor/nodqora/issues/12)

## Context

ADR-0012 made dangling edges legal by design — a plugin emits edges referencing
keys it does not own — and ADR-0047's residual makes them likely: an ACL
tightening removes topic nodes while `connect` keeps naming them (ADR-0041).
ADR-0032 created the second shape: `topology.io/owner: payments-platform` names
an Owner whose `channel` and `onCall` exist only in YAML.

In the fixture's steady state there are **zero** dangling references. All nine
edge endpoints resolve. So this is purely about failure behaviour.

## Decision

**A folded edge whose endpoint key has no node materializes a stub node** at that
key: no `type` (→ ADR-0001's fallback descriptor), no `displayName`, no backings,
no links, no owner, `sources[]` = the plugins whose edges name it, health
`UNKNOWN` by ADR-0028's arithmetic. There is no `stub` flag.

**An unresolved `ownerKey` is ordinary.** The node keeps the key; the inspector
renders it with no contact details. No `PARTIAL`, no warning, no materialized
Owner. Logged at info and counted.

**`yaml` validates its own edges** and never emits one whose endpoints it does not
declare in that environment — dropped at parse time, reported as a `PARTIAL`
reason. Handed to [YAML topology format](https://github.com/fredskor/nodqora/issues/14).

## Consequences

- **ADR-0031's failure-direction principle decides the edge case.** A typo'd
  `toKey` produces a bare ghost node sitting next to the real topic — ugly and
  obvious. Dropping the edge instead produces a relationship that silently never
  existed. Subtraction fails toward a visible extra; narrowing fails toward a
  missing one; same principle, same answer.
- **No new machinery.** ADR-0001 already designs the fallback descriptor to
  render, and ADR-0028 already makes "no observer ⇒ `UNKNOWN` / `rawSignal: null`"
  arithmetic. A stub is the empty case of a node the model already has. Adding a
  flag would un-trim what ADR-0009 trimmed.
- Under ADR-0047's residual the topology's **shape** survives an authz change,
  degrading to "something is here, we cannot see what" rather than the pipeline
  visibly severing.
- **The two dangling shapes are treated differently for a structural reason.** An
  edge missing an endpoint *cannot render* — a canvas edge needs two nodes — so
  something had to be invented. An `ownerKey` with no Owner row renders perfectly
  well as a name with an empty contact section, a state the fixture already
  demands (`stripe-webhooks` is the designed bare case).
- **A dangling `ownerKey` is the expected steady state, not a fault.** A node
  discovered by `kubernetes` in an org that has annotated its manifests but not
  yet written a YAML owner block has one by design — the adoption path ADR-0044
  chose option C to keep open. Warning on it trains people to ignore warnings, and
  *dropping* the key would collapse ADR-0044's argument entirely.
- **The one real risk sits in `yaml`, and that is where the validation goes.** If
  `yaml`'s **staging** snapshot emitted `trino-analytics → analytics.payments_events`,
  the fold would manufacture phantom staging nodes and destroy ADR-0004's drift
  demo — the fixture's whole point. The asymmetry is principled: `yaml` is the
  authored plugin and owns every key it names, so it can validate; `connect`
  cannot, because ADR-0012 requires it to emit edges to keys it does not own.
