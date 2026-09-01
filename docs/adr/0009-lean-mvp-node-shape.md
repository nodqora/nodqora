# ADR-0009: The MVP Node and Edge carry a trimmed subset of §8.1

- **Status**: Accepted
- **Date**: 2026-09-01
- **Ticket**: [Core graph domain model](https://github.com/fredskor/nodqora/issues/3)

## Context

Product plan §8.1 sketches 22 fields on Node. ADR-0001 through ADR-0008 absorbed
or relocated most of them, leaving a set of fields that are either duplicated,
unread, or actively harmful to keep.

## Decision

The MVP shapes:

```text
Node          id · environmentKey + key · type · displayName · description?
              ownerKey? · links[] · backings[] · metadata{} · sources[]
              discoveredAt · updatedAt

Edge          id · environmentKey · fromKey → toKey · relation
              metadata{} · sources[] · discoveredAt · updatedAt

NodeState     nodeId · health · rawSignal · metrics{} · observedAt
Environment   key · displayName · connections{}
Owner         key · displayName · channel · onCall
Registry      TypeDescriptor · RelationDescriptor
```

Dropped from §8.1, with reasons:

| dropped | why |
|---|---|
| `subtype` | `type` is already an open string (ADR-0001); a second axis has no MVP use |
| `namespace` | a **Kubernetes concept in a technology-agnostic core** — a direct §5.2 violation. It belongs on the backing |
| `domain`, `labels`, `tags` | §54 grouping is post-MVP; `metadata` covers the need meanwhile |
| `externalId` | backings carry the external reference (ADR-0005) |
| `status` | collapsed into `health` + `rawSignal` on NodeState (ADR-0003) |
| `name` (vs `displayName`) | `key` is the identifier, `displayName` is the label — a third string had no distinct meaning |
| `team`, `owner` | replaced by `ownerKey` → Owner entity (ADR-0007) |
| `environment` (string) | replaced by `environmentKey` → Environment entity (ADR-0004) |
| `health`, `metrics` | relocated to NodeState (ADR-0003) |
| `source` | replaced by `sources[]` (ADR-0008) |
| `Edge.confidence` | §35 relationship confidence is post-MVP; every MVP edge is asserted |

## Consequences

- Every dropped field is recoverable from `metadata` without a migration, so
  none of these is a one-way door.
- §54 grouping and §35 confidence each need a field added when built.
- `namespace` leaving the core is the load-bearing removal: with it gone,
  nothing in the core model names a technology.
- `displayName` carries the fixture's real cases — `analytics (Trino)` for
  `trino-analytics`, and full names like `payments.events.enriched.v1` whose
  truncation is a canvas problem, not a model one.
