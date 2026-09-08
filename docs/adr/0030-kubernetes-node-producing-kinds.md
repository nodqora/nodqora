# ADR-0030: Three workload kinds become nodes; Services and Ingresses attach as backings

- **Status**: Amended by [ADR-0148](0148-a-pods-backing-names-a-selector-and-readiness-is-counted-from-live-pods.md)
- **Date**: 2026-09-02
- **Ticket**: [Kubernetes discovery scope and annotation convention](https://github.com/fredskor/nodqora/issues/10)

> **Amendment (ADR-0148).** A `kubernetes` backing may also be `{ kind: pods, reference:
> <namespace>/<label selector> }`, which health reads by counting live matching pods. It is **not**
> a produced kind: discovery never emits it, `WorkloadKind` does not gain a constant, and a pod is
> still not a node and not a backing (ADR-0005) — the backing names a query, and the pods it
> matches are counted without one of them ever being named. Which kinds become nodes is unchanged.

## Context

Product plan §11.2 lists namespaces, Deployments, StatefulSets, Services,
Ingress, Jobs and CronJobs as things to discover, without saying which become
graph nodes. ADR-0021 made the question load-bearing rather than descriptive:
its tier 2 resolves an object by its own `metadata.name`, so **every in-scope
object becomes a node by default**, and the fixture's `kafka-connect`
StatefulSet would be an eleventh node in a ten-node inventory.

Two of §11.2's kinds are already settled. ADR-0005 pins `payments-api` as backed
by "a Deployment **and** a Service **and** an Ingress", so Services and
Ingresses are backings; pods are §53 runtime detail and out by the same ADR.

## Decision

**Deployment, StatefulSet and CronJob become nodes. Nothing else does.**

- **Bare Jobs do not.** A Job is a *run*, not a component. Under ADR-0004
  absence is drift, so a Job that completes and is garbage-collected between two
  slow-loop polls reads as a node that vanished — manufactured drift
  indistinguishable from staging's missing Iceberg branch. The CronJob is the
  component; its Jobs are not discovered at all, not even as backings.
- **DaemonSets do not.** Absent from §11.2, and overwhelmingly
  infrastructure-shaped — precisely the class ADR-0031 exists to suppress.
- **Namespaces do not.** ADR-0009 removed `namespace` from the core as a §5.2
  violation. A namespace is *scope* (ADR-0035), not a node and not a backing
  kind.

**Services and Ingresses attach to a node by selector, never becoming one.**

```text
Ingress --(backend service name)--> Service --(spec.selector ⊇ pod labels)--> workload
```

Backings are `{ plugin: kubernetes, kind: <lowercased kind>, reference:
<namespace>/<name> }`. A Service selecting pods from two workloads backs both
nodes; a Service selecting nothing — headless, `ExternalName` — attaches to
nothing and produces no node.

## Consequences

- The fixture reproduces exactly: `payments-api` gets three backings via the
  Ingress→Service→Deployment chain, `enricher-v2` gets one, and the ten-node
  inventory holds once ADR-0031 removes `kafka-connect`.
- **Selector matching is the only Kubernetes inference that survives.** It
  produces backing attachment, not edges — see ADR-0033.
- A genuinely DaemonSet-deployed service cannot be a discovered node; it must be
  declared in YAML. Accepted: every kind admitted is another source of
  unwanted nodes, and the fixture has no such case.
- Every workload kind past Deployment is speculative — the fixture has no
  StatefulSet node and no CronJob node at all. `StatefulSet` earns its place only
  because the fixture has one, and it is the one we must exclude.
- Adding a kind later is additive and needs no migration. Removing one is not:
  nodes disappear, which ADR-0004 reads as drift.
