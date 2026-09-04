# ADR-0032: The `topology.io/*` vocabulary is closed; annotations hold ids and links are composed per environment

- **Status**: Amended by [ADR-0091](0091-kubernetes-emits-no-type-default.md)
- **Date**: 2026-09-02
- **Ticket**: [Kubernetes discovery scope and annotation convention](https://github.com/fredskor/nodqora/issues/10)

> **Amendment (ADR-0091).** The table's `topology.io/type` row said it overrode
> *"ADR-0030's kind-derived default"* — a default ADR-0030 never states. There is
> none: `kubernetes` emits `type: null` unless annotated, so this key **sets**
> `type` rather than overriding anything.

## Context

ADR-0021 fixed the *shape* of identity resolution — annotation, then object
name, exact, first match wins — and left the concrete keys here. The fixture's
block is `service` / `owner` / `repository` / `grafana`; ADR-0022 requires a
consumer-group annotation, and ADR-0031 adds an opt-out.

Two observations shaped the answer.

First, `topology.io/service` is misnamed. ADR-0030 admits StatefulSets and
CronJobs, so a key called `service` bakes a type into the identity mechanism and
reads as though it also sets `type: service`.

Second — and this is what forces the design — **`topology.io/grafana:
payments-enricher-overview` is not a URL.** It is a dashboard id. A `Link`
(ADR-0007) needs a URL, so something must compose one.

## Decision

**A closed vocabulary of nine keys. Unknown `topology.io/*` keys are ignored and
logged — never turned into links, metadata, or anything else.**

| annotation | becomes |
|---|---|
| `topology.io/node` | the node `key` — ADR-0021 tier 1 (renamed from `service`) |
| `topology.io/type` | `type` — the plugin's **only** route to one; absent, `type` is `null` (ADR-0091) |
| `topology.io/owner` | `ownerKey` (ADR-0007) |
| `topology.io/ignore` | suppression (ADR-0031) |
| `topology.io/consumer-groups` | `{ plugin: kafka, kind: consumer-group }` backings (ADR-0022); comma-separated |
| `topology.io/repository` | link `rel: repository` |
| `topology.io/runbook` | link `rel: runbook` |
| `topology.io/docs` | link `rel: docs` |
| `topology.io/grafana` | link `rel: dashboard` |

**Links are composed from per-environment templates** in the plugin config, from
three sources of value:

```yaml
kubernetes:
  links:
    workload:  "https://k8s.acme.io/{namespace}/{kind}/{name}"   # from the object
    pods:      "https://k8s.acme.io/{namespace}/{kind}/{name}/pods"
    logs:      "https://grafana.acme.io/explore?ns={namespace}&app={name}"
    dashboard: "https://grafana.acme.io/d/{value}"                # {value} = topology.io/grafana
    gitops:    "https://argocd.acme.io/applications/{value}"      # {value} = argocd.argoproj.io/instance
```

- **No template configured ⇒ no link.** Never a half-composed URL.
- **`repository` / `runbook` / `docs` need no template** — their values are URLs;
  `https://` is prepended when no scheme is present.
- **Labels are plugin constants**, not configurable.
- **One `workload`/`pods` link per workload backing** — normally exactly one.
- The **`gitops` link reads a third-party label**, `argocd.argoproj.io/instance`.

## Consequences

- **An open vocabulary could not have worked**, because half the values are not
  URLs. "Open" would have meant "open to absolute URLs only" — and an absolute
  URL is the wrong thing to put in an annotation: the same manifest is deployed
  to production and staging, so a hardcoded Grafana URL points staging's node at
  production's dashboard. **The id-plus-per-environment-template split is what
  makes one annotation render correctly in both environments**, and it is the
  reason the vocabulary is shaped this way rather than a matter of taste.
- Closed also matches ADR-0014's precedent: allow-list by name, because that is
  the only rule that fails safe when a new key appears.
- Reading `argocd.argoproj.io/instance` explains the fixture's deliberate
  unevenness honestly — Argo manages `payments-api` and not the enricher — at
  zero annotation cost, using a convention that already exists on every
  Argo-managed object.
- **A typo'd `topology.io/node` is the worst failure this design has.** It falls
  silently to ADR-0021 tier 2, the node takes the object's name as its key, and
  the node it should have been merged into is absent — fake drift under
  ADR-0004. The log line for unrecognised `topology.io/*` keys is the only
  defence, and it cannot be more: ADR-0021 forbids reporting this through
  `outcome`, because `PARTIAL` would trip the deletion fail-safes #12 builds on
  failure signals. Cheap detection, no enforcement.
- `docs/reference-pipeline.md` §5 is amended: `topology.io/service` becomes
  `topology.io/node`, and `topology.io/consumer-groups` is added.
- `topology.io/consumer-groups` is plural because ADR-0022 made it health
  routing, and nothing says a workload consumes one topic.
