# ADR-0008: Provenance is a per-node contributor list, not per-field attribution

- **Status**: Accepted
- **Date**: 2026-09-01
- **Ticket**: [Core graph domain model](https://github.com/fredskor/nodqora/issues/3)

## Context

The reference pipeline's most important node arrives from two places at once:
`payments-enricher` is discovered from Deployment `enricher-v2` **and** declared
in YAML, which is where its owner, repository and Grafana annotations come from.
Product plan §56 additionally wants manual overrides to survive rediscovery.

The Node shape must carry enough provenance for
[Discovery-engine merge semantics](https://github.com/fredskor/nodqora/issues/12)
to define precedence rules on top of it — without that ticket's decision being
pre-empted here.

## Decision

Node and Edge carry `sources` — the list of contributors that produced them,
e.g. `[yaml, kubernetes]`. Nothing finer.

Per-field attribution is **not** in the MVP. Which source wins for a given field
is [#12](https://github.com/fredskor/nodqora/issues/12)'s decision, made against
this field.

## Consequences

- The UI can badge a node as declared, discovered, or both — the fixture's
  mixed graph is visible rather than implied.
- After a merge you cannot tell which source set a given field. §56 manual
  overrides will need their own mechanism (a pinned-fields list, or promotion to
  per-field provenance) when they are built.
- Roughly half the write-model complexity of per-field provenance, for a feature
  that is explicitly not in the MVP.
