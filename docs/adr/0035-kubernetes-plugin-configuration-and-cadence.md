# ADR-0035: Kubernetes plugin configuration and cadence — enumerated namespaces, one cluster, poll not watch

- **Status**: Accepted
- **Date**: 2026-09-02
- **Ticket**: [Kubernetes discovery scope and annotation convention](https://github.com/fredskor/nodqora/issues/10)

## Context

ADR-0014 made plugin configuration file-declared and per-environment, sketching
`{ namespace: payments-prod, kubeconfig: "${file:...}" }`. ADR-0012 established
two cadence loops — a slow topology loop calling Discovery and a fast state loop
calling Health, each with a default interval and per-plugin overrides — and left
the numbers here.

## Decision

```yaml
kubernetes:
  namespaces: [payments-prod]                      # required, non-empty, no wildcard
  kubeconfig: "${file:/etc/nodqora/kubeconfig}"    # optional; absent ⇒ in-cluster
  context: prod-cluster                            # optional
  ignore: [statefulset/kafka-connect]              # ADR-0031
  links: { ... }                                   # ADR-0032
  discoveryInterval: 5m
  healthInterval: 30s
  discoveryTimeout: 30s
  healthTimeout: 10s
```

- **Namespaces are an enumerated list, required, with no `all` or wildcard
  mode.** Validated non-empty at startup.
- **One cluster per environment.** `backings[].reference` stays
  `<namespace>/<name>`, as ADR-0023 already assumes.
- **5 minute discovery, 30 second health.** Timeouts are validated to be
  strictly below their interval, so two polls can never be in flight.

## Consequences

- **Namespace scope is the one narrowing knob that cannot be avoided** — you
  must say where to look. ADR-0031's failure-direction argument still applies, so
  it is mitigated by making it loud rather than by removing it: no default, no
  wildcard, startup failure on empty. A forgotten namespace reads as "half my
  graph is gone", not as one subtly absent node.
- **No cluster-wide mode, specifically because it needs a `ClusterRole`.**
  Per-namespace read-only `Role`s are the least-privilege story matching research
  #5's read-only Kafka principal. Cluster-wide would also flood the graph with
  `kube-system` noise that ADR-0031 can only remove one exact name at a time, by
  design.
- **One cluster per environment is a soft one-way door.** If an environment later
  spans two clusters, `<namespace>/<name>` stops being unique and
  `backings[].reference` needs a cluster prefix — a data migration, not a code
  change. Prefixing now was rejected as speculative structure, and a second
  cluster is often more naturally a second Environment.
- **30 second health** is what the canvas is for: an SRE watching a rollout wants
  readiness inside a minute, and a namespaced list is served from the apiserver's
  watch cache. **5 minute discovery** because ADR-0003 made `updatedAt` mean
  *topology changed*, so churning it faster buys nothing — and it bounds how long
  ADR-0032's typo'd-annotation drift persists.
- **We are polling an API that has `watch`.** Watch is the native, cheaper,
  lower-latency mechanism and is what a Kubernetes integration would normally
  use. ADR-0012 rules it out — plugins are stateless singletons and every result
  is a full snapshot, never a delta, whereas a watch is a stateful delta stream.
  That is the right trade, because statelessness is what makes #12's merge
  tractable at all. But the `kubernetes` plugin is deliberately not using the
  best tool available to it, and if latency or apiserver load ever becomes a
  complaint, **ADR-0012 is what reopens, not this cadence.**
- Reconfiguring namespaces or link templates is a restart, per ADR-0014.
