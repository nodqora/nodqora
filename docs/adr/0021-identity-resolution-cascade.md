# ADR-0021: Identity resolution is an ordered exact-match cascade inside the plugin

- **Status**: Accepted
- **Date**: 2026-09-02
- **Ticket**: [Identity-resolution rules for the MVP](https://github.com/fredskor/nodqora/issues/8)

## Context

ADR-0012 put identity resolution **inside the plugin** and observed that of the
four plugins only one has a problem to solve: a Kafka topic *is* its own logical
key, a Connect connector *is* its own logical key, and YAML states its key
outright. Only `kubernetes` faces the fixture's deliberate mismatch, where
Deployment `enricher-v2` must become node `payments-enricher`.

The fixture pins both ends: `payments-api` "matches its Deployment by name — the
happy path", while `enricher-v2` proves "string equality is insufficient".
Product plan §34 requires that fuzzy matching not be the primary mechanism.

## Decision

**An ordered cascade of exact-match tiers. First match wins.** For the
`kubernetes` plugin:

```text
1. the topology.io/* node annotation   (exact)
2. the object's own metadata.name      (exact)
```

There is no third tier, and **no tier is ever fuzzy**. Exact name equality is
not fuzzy matching, so tier 2 satisfies §34 as written. The concrete annotation
key is [#10](https://github.com/fredskor/nodqora/issues/10)'s to pin; this ADR
fixes the shape and the order.

**When two objects resolve to one key, the plugin emits one node:**

- **backings are unioned** — every claimant becomes a backing, so ADR-0013's
  health routing still reaches all of them;
- **scalars come from the newest object** by `creationTimestamp`, ties broken by
  name ascending;
- **the contest is recorded** under the plugin's own `metadata` namespace and
  logged.

`outcome` is **not** used to report a contest: the snapshot is complete, and
marking it `PARTIAL` would trip the deletion fail-safes #12 is expected to build
on failure signals.

**Cross-plugin precedence is not this ADR's.** §34's tiers translate to plugin
ids — manual → `yaml`, connector config → `connect`, Kubernetes config →
`kubernetes` — and belong to
[#12](https://github.com/fredskor/nodqora/issues/12). §34's fourth tier,
*inferred*, has no MVP referent: ADR-0009 dropped `Edge.confidence` because
every MVP edge is asserted.

## Consequences

- **Determinism is the point.** The rejected Backstage behaviour — process one
  claimant, silently skip the rest — is nondeterministic because the Kubernetes
  list order is not stable, so the winner flips between polls and the node's
  image, links and health flap with no cause visible to the user. Newest-by-
  `creationTimestamp` is stable across polls and, during a progressive rollout,
  names the current workload.
- Dropping a contested key was rejected outright: an absent node reads as
  environment drift under ADR-0004, so a copy-pasted annotation would manufacture
  fake drift.
- Emitting `null` for disagreeing scalars was rejected as a default because it
  costs the node its `type`, dropping it to ADR-0001's fallback descriptor — a
  visible degradation of a node that was rendering correctly.
- A third tier of configurable transform rules (§34's "configurable mapping
  rules") was rejected as unearned: no transform turns `enricher-v2` into
  `payments-enricher`, so the annotation is required regardless.
- Tier 2 means every in-scope workload becomes a node by default, which would
  make the fixture's `kafka-connect` StatefulSet an eleventh node. **Which
  resources become nodes rather than backings is
  [#10](https://github.com/fredskor/nodqora/issues/10)'s**, and it inherits this
  as a constraint.
- The cascade is per-plugin. There is no cross-plugin index of claims
  (ADR-0012), so a contest *between* plugins is invisible until merge — correctly,
  because it is one.
