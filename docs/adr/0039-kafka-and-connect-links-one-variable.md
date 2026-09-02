# ADR-0039: `kafka` and `connect` compose links from per-environment templates with a single `{name}` variable

- **Status**: Accepted
- **Date**: 2026-09-02
- **Ticket**: [Kafka and Kafka Connect discovery scope](https://github.com/fredskor/nodqora/issues/11)

## Context

The fixture gives both topics a Grafana topic dashboard and both connectors
Connect UI / Logs / Config links (§9); §21 lists "Open topic / Open consumer
groups / Open Grafana" for Kafka and "Open connector / Open logs / Open config"
for Connect. **None of these has an annotation behind it** — there is no
Kubernetes object to annotate, and neither Kafka nor Connect has an annotation
facility at all.

ADR-0032 built per-environment URL templates with `{namespace}`, `{kind}`,
`{name}` and `{value}` substitution for exactly this shape.

## Decision

**Reuse ADR-0032's mechanism with one variable instead of four.**

```yaml
kafka:
  links:
    topic:     "https://akhq.acme.io/prod/topic/{name}"
    consumers: "https://akhq.acme.io/prod/topic/{name}/groups"
    dashboard: "https://grafana.acme.io/d/kafka-topic?var-topic={name}"
connect:
  links:
    connector: "https://connect-ui.acme.io/prod/{name}"
    config:    "https://connect-ui.acme.io/prod/{name}/config"
    logs:      "https://grafana.acme.io/explore?connector={name}"
```

ADR-0032 needed four variables because a Kubernetes plugin watches several
namespaces and several kinds and reads ids out of annotations. Here
**everything except the object's own name is constant per environment** — one
Kafka cluster, one Connect cluster, no annotations, no kinds — and the template
is already per-environment, so the cluster, namespace and workload path are
literal text the operator types in. `{name}` is the only thing that varies.

Carried over from ADR-0032 unchanged: **no template configured ⇒ no link, never
a half-composed URL**; labels are plugin constants, not configurable.

Two boundaries:

- **The `config` link must point at a UI, never at
  `GET /connectors/{name}/config`.** Research #5 verified that endpoint applies
  no masking of any kind, so a connector with an inlined password serves it in
  plaintext to whoever clicks. It is a template like any other and cannot be
  technically prevented, so it is a deployment note the MVP must not leave
  implicit, alongside ADR-0042's GET-only proxy note.
- **`connect` composes links only for the connectors it owns, never for
  destination nodes.** The fixture's Kibana link on `payments-events-v1` belongs
  to a declared node no plugin observes; it comes from YAML. `connect` writing
  links onto a node it merely points an edge at would make it a second writer
  for a field it knows nothing about.

## Consequences

- Three link mechanisms now exist in the MVP and they are the same mechanism:
  annotation-value substitution (`kubernetes`), object-name substitution
  (`kafka`, `connect`), and verbatim URLs from YAML. Only the source of the
  substituted value differs.
- An operator with no UI in front of Kafka configures no templates and gets
  topics with zero links, which the inspector's designed empty state
  (ADR-0019) already handles.
