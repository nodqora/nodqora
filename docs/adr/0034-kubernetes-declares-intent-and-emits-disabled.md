# ADR-0034: Kubernetes has declared intent, so it is the plugin that emits `DISABLED`

- **Status**: Amended by [ADR-0105](0105-a-node-backed-by-several-workloads-names-each-and-sums-their-replicas.md)
- **Date**: 2026-09-02
- **Ticket**: [Kubernetes discovery scope and annotation convention](https://github.com/fredskor/nodqora/issues/10)

> **Amendment (ADR-0105).** The `rawSignal` and `metrics` shapes below describe **one** workload.
> When a node has several, `rawSignal` prefixes each with its `namespace/name` and `metrics` sums
> the replica counts; a workload with no `spec.replicas` contributes to neither sum. The health
> collapse, and everything else here, is unchanged.

## Context

ADR-0025 fixed the arithmetic — all / some / none ready → HEALTHY / DEGRADED /
UNHEALTHY — and ADR-0029 required **evidence of intent** before anything may be
called `DISABLED`, concluding that `kafka` may never emit it: an `EMPTY`
consumer group is a scaled-down consumer and a crashed one alike.

Kubernetes is the case ADR-0029 left open. Intent there is **declarative and
recorded in the object's own spec**, not inferred from an observed absence.

## Decision

The `kubernetes` health contribution:

- **Only workload backings contribute.** Service and Ingress backings have no
  readiness concept and are inert for health.
- **`spec.replicas == 0` ⇒ `DISABLED`.** ADR-0025's all/some/none arithmetic
  applies only when `desired > 0`.
- **`spec.suspend: true` on a CronJob ⇒ `DISABLED`.** A running CronJob
  **abstains** — it has no replica concept, and ADR-0030 does not discover its
  Jobs.
- **A backing whose object has vanished ⇒ abstain.** Deletion is #12's to read
  from the discovery snapshot, never encoded as health.
- **`rawSignal`** is `"3 desired / 2 ready"`; **`metrics.kubernetes`** is
  `desiredReplicas` / `readyReplicas`, per ADR-0028's allow-list.
- **Several workload backings on one node** collapse with ADR-0024's own
  algorithm inside the plugin — one collapse applied at two levels.
- **`outcome`** (ADR-0026): `FAILED` when the API server is unreachable,
  `PARTIAL` when some list calls succeeded and others did not.

## Consequences

- A CronJob-only node sits at `UNKNOWN` unless another plugin observes it. That
  is ADR-0013's arithmetic being honest, not a gap.
- **`DISABLED` travels through shared workloads.** ADR-0013 routes `kubernetes`
  to observe both connector nodes via the `kafka-connect` StatefulSet that
  ADR-0022 stamps on them, so scaling that StatefulSet to zero contributes
  `DISABLED` to **both connectors**, and ADR-0024's "DISABLED wins outright"
  makes both connectors `DISABLED` — even though nobody paused the connectors
  themselves. This is accepted deliberately: the connectors genuinely are not
  running, someone turned the cluster off on purpose, and `UNHEALTHY` would page
  an on-call at 3am for an intentional scale-down. It is one plugin's read of a
  shared workload overriding another plugin's direct observation of the node,
  which is why it is recorded here rather than left to emerge.
- The fixture reproduces unchanged. Baseline `payments-iceberg-sink`:
  HEALTHY (k8s, 2/2) + HEALTHY (kafka, lag 8,400 under threshold) + DEGRADED
  (connect, 2/3 tasks) → **DEGRADED**. Incident connectors: HEALTHY (k8s) +
  DISABLED (connect, `PAUSED`) → **DISABLED**.
- A `HorizontalPodAutoscaler` or KEDA scaling a workload to zero reads as
  `DISABLED`. Correct — it is still a recorded decision that the workload should
  not be running now.
