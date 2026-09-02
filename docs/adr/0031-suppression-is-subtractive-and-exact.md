# ADR-0031: Suppression is subtractive and exact — an opt-out annotation, with an exact-name deny-list as escape hatch

- **Status**: Accepted
- **Date**: 2026-09-02
- **Ticket**: [Kubernetes discovery scope and annotation convention](https://github.com/fredskor/nodqora/issues/10)

## Context

ADR-0030 admits three workload kinds, and ADR-0021's tier 2 turns each in-scope
object into a node. The fixture's `kafka-connect` StatefulSet must not be one —
yet it must stay reachable: ADR-0022 has the `connect` plugin stamp
`{ plugin: kubernetes, kind: statefulset, reference: payments-prod/kafka-connect }`
onto both connector nodes, and ADR-0013 then routes `kubernetes` to *observe*
those connectors through it.

The choice of mechanism is governed by **direction of failure**, not by
convenience. ADR-0004 makes absence mean drift, so a mechanism that fails toward
a **missing** node manufactures fake drift that is indistinguishable from the
real staging-Iceberg case. A mechanism that fails toward an **extra** node is
visibly wrong and gets reported.

## Decision

**Default-in, with explicit subtraction. Two routes, unioned; either suffices.**

```yaml
# on the object, where you can edit it
topology.io/ignore: "true"

# in the plugin config, where you cannot
kubernetes:
  ignore:
    - statefulset/kafka-connect      # exact kind/name — no globs, no regex
```

**Suppression removes node emission only.** A suppressed object is still read
when another plugin references it as a backing, which is what keeps ADR-0013's
routing to the connector nodes intact.

**No pattern matching, ever.** Exact `kind/name` entries bound the blast radius
to precisely what was typed.

**Each poll logs a count of suppressions by route.**

## Consequences

- **Two narrowing mechanisms were rejected outright** for failing in the wrong
  direction: opt-in (only annotated objects become nodes) and a config label
  selector naming what counts. Both turn a configuration slip into silent
  missing nodes, which is fake drift.
- **Patterns were rejected for the same reason at smaller scale.** Subtraction
  is the safe direction only while it cannot over-reach; `kafka-*` quietly
  eating a real service is the missing-node failure returning through the
  convenience door.
- **Neither route alone is sufficient.** Annotation-only repeats the objection
  ADR-0022 raised against `topology.io/backs`: it needs an edit in a repo owned
  by another team, and `kafka-connect` is Helm- or operator-installed, where an
  operator will revert the edit. Config-only forces a PR against the nodqora
  config repo for a service team's own migration runner. Research #4 named the
  general shape — annotation-driven resolution needs an escape hatch.
- **A tempting derivation was rejected**: "an object already recorded as a
  backing of some other node is not a node." It fails on timing — ADR-0012 runs
  plugins in parallel with each resolving identity itself, so `kubernetes`
  cannot know what `connect` emitted, and the outcome would depend on poll
  order. It also fails toward *missing*, and it is a cross-plugin engine rule,
  which is #12's and strains ADR-0015.
- The cost is two places to look when asking "why isn't my service on the
  graph?" The per-poll suppression count is what makes that answerable.
