# ADR-0020: Node keys are flat human strings, compared case-insensitively

- **Status**: Accepted
- **Date**: 2026-09-02
- **Ticket**: [Identity-resolution rules for the MVP](https://github.com/fredskor/nodqora/issues/8)

## Context

ADR-0004 fixed the natural key as `(environmentKey, key)` but not what `key`
holds. ADR-0012 then made the choice consequential: plugins return **final
keys** and the engine merges by that pair, so two plugins agreeing on a key is
the *only* mechanism by which their output becomes one node.

The reference pipeline's most important node depends on exactly that agreement:
`payments-enricher` is minted independently by `yaml` and by `kubernetes` and
must land on one row.

## Decision

**The key is a flat, free-form, human-readable string** — `payments-enricher`,
`payments.events.raw.v1` — unique within its Environment across **all** types.
It is not qualified by type, by plugin, or by anything else, and it is not
opaque.

**Stored verbatim, trimmed of surrounding whitespace. Uniqueness and merge
lookup are on the case-folded form:**

```text
UNIQUE (environmentKey, lower(trim(key)))
```

The surrogate `id` remains the only thing other tables reference, so
`(environmentKey, key)` is a lookup key and never a foreign key.

## Consequences

- A type-qualified key (`service/payments-enricher`) was rejected because
  `type` is an open string with no enum to check against (ADR-0001). YAML would
  have to independently guess the same `type` string the `kubernetes` plugin
  mints, and guessing wrong yields **two nodes and no error** — silent, and
  landing on the fixture's most important node. The same argument rejects
  byte-exact case comparison: a `Payments-Enricher` typo in YAML splits the node
  just as silently.
- An opaque minted key with a human-name alias index was rejected because no
  plugin could then mint a key alone — ADR-0012's "nodes carry final keys"
  breaks, and the central resolver that returns must understand Kubernetes
  annotations to be useful, which §5.2 forbids.
- **Cross-type name collisions are possible by construction.** A Kafka topic
  named `payments-api` merges into the service. That is deliberate: a collision
  across plugins is a merge, which is [#12](https://github.com/fredskor/nodqora/issues/12)'s.
- Two Kafka topics differing only in case collide into one node. Kafka permits
  such topics and warns against them; the case surfaces as a contested key
  (ADR-0021) rather than a silent merge.
- Keys are API surface, appear in URLs, and are typed by hand in YAML, so the
  case-folded index is what keeps hand-authoring forgiving without lying about
  the name a source system uses.
- `environmentKey` inherits DataHub's orphaning hazard noted in research #4 —
  re-scoping it orphans every node beneath it. Whether it is immutable from
  creation remains open on the map.
