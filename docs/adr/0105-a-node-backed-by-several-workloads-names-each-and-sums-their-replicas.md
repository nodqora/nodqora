# ADR-0105: A node backed by several workloads names each in `rawSignal` and sums their replicas in `metrics`

- **Status**: Accepted
- **Date**: 2026-09-05
- **Ticket**: [Slice 3 — Health end-to-end: the contribution store, `/state`, the collapse](https://github.com/fredskor/nodqora/issues/24)
- **Amends**: ADR-0034

## Context

ADR-0034 fixes three things about the `kubernetes` contribution and leaves a
fourth undecided.

It fixes the **health**: *"Several workload backings on one node collapse with
ADR-0024's own algorithm inside the plugin."* It fixes the **shape** of the other
two: `rawSignal` is `"3 desired / 2 ready"`, and `metrics.kubernetes` is
`desiredReplicas` / `readyReplicas` per ADR-0028's allow-list.

Both of those shapes describe **one workload**. Neither says what they hold when
the node has two, and ADR-0034's own worked example is the case that produces it:
the `kafka-connect` StatefulSet backs *both* connector nodes, and either of them
may also be backed by something of its own. So the plugin has to decide, and
ADR-0102 says a decision made during implementation is recorded rather than left
in a method.

This is not hypothetical arithmetic. ADR-0034 already accepts that a shared
workload scaled to zero makes both connectors `DISABLED`; whatever the metrics
say, they will sit on a card whose glyph came from a different workload.

## Decision

**`rawSignal` names each workload when there is more than one.** One workload
reads `"3 desired / 2 ready"`, exactly as ADR-0034 writes it. Two read
`"payments-prod/kafka-connect scaled to 0, payments-prod/sidecar 1 desired / 0
ready"`.

The reference is noise in the single case — it repeats what `backings[]` already
says — and load-bearing in the plural one, because an unlabelled
`"scaled to 0, 1 desired / 0 ready"` makes the reader guess which half is which.
ADR-0028 licenses this: `rawSignal` is *"a gist"* and *"a short line"*, never an
expected-output assertion.

**`metrics` sums `desiredReplicas` and `readyReplicas` across the node's
workloads.** The overlay answers *how much of this node is running*, and a node
backed by two workloads is running in proportion to both. A workload with no
`spec.replicas` — a CronJob, or one whose spec could not be read — contributes to
neither sum; a zero there would render as a scaled-down workload, which is the
one reading it must not have.

## Consequences

- **The overlay and the glyph can say different-looking things about one card,
  and both are true.** A node backed by a StatefulSet scaled to zero and a
  Deployment at 1 desired / 0 ready reads `DISABLED` with `desiredReplicas: 1`.
  The glyph answers "does anyone need to act on this?" — no, someone turned part
  of it off on purpose (ADR-0024). The overlay answers "how much is up?" — of
  what is still meant to run, none. Reconciling them would mean either dropping
  the metrics or overriding the collapse, and both discard a true fact to make a
  card look tidier.
- **The fixture exercises none of this**, and that is why it is a short ADR
  rather than a design. Every node the `kubernetes` plugin observes today has one
  workload backing, so both rules degenerate to ADR-0034's stated shapes and the
  goldens are unchanged by their existence. Slice 4 is the first slice where the
  plural case can occur, via ADR-0022's stamp of the shared StatefulSet onto both
  connectors.
- **Summing was chosen over the alternatives.** *Max* would hide a second
  workload entirely; *emitting nothing* would drop the overlay from precisely the
  nodes that are most complicated; *per-workload keys* would break ADR-0028's
  allow-list, which enumerates key names rather than admitting a family of them.
- **This is an ADR-0102 amendment of mechanism.** ADR-0034's reasons — declared
  intent, only workload backings, one collapse at two levels — are untouched;
  this decides a case its shapes did not cover.
- Ordering is by backing reference so the line and the sums are stable across
  polls, because the Kubernetes list order is not (ADR-0021's reason, applied to
  the fast half).
