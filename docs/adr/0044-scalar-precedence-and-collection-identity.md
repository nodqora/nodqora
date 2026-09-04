# ADR-0044: Scalars settle by one global plugin precedence; collections are additive with element identity

- **Status**: Amended by [ADR-0091](0091-kubernetes-emits-no-type-default.md)
- **Date**: 2026-09-02
- **Ticket**: [Discovery-engine merge semantics](https://github.com/fredskor/nodqora/issues/12)

> **Amendment (ADR-0091).** The contested-scalar table's `kubernetes`/`type`
> cell read *"kind-derived, or `topology.io/type`"*. There is no kind-derived
> value: it is `topology.io/type`, else `null`. The precedence order is unchanged
> and simply has less to settle — `type` is contested only when a YAML stanza and
> an annotation both speak.

## Context

Research [#4](https://github.com/fredskor/nodqora/issues/4) costed six merge
options and found that **nobody applies one rule to scalars and collections** —
option **F** is the pattern all four surveyed tools converge on without naming
it. ADR-0043 already removed option **D**, and option **A** was ruled out by the
fixture in the research itself. That leaves **B** (field-class partition) or
**C** (precedence table) for scalars, and element identity for collections.

`metadata{}` is non-contestable by construction (ADR-0006 namespaces it per
plugin). Exactly four scalars can be contested:

| field | `yaml` | `kubernetes` | `kafka` | `connect` |
|---|---|---|---|---|
| `type` | declares | `topology.io/type`, else `null` (ADR-0091) | `kafka-topic` | `connect-connector` |
| `displayName` | declares | `null` on purpose (ADR-0034) | `null` (ADR-0038) | `null` (ADR-0038) |
| `description` | declares | — | — | — |
| `ownerKey` | declares | `topology.io/owner` | `null` | `null` |

## Decision

**Scalars: research option C — one global precedence order, first non-`null`
wins.**

```text
yaml  >  connect  >  kubernetes  >  kafka
```

The order is **global**, applied identically to all four scalars. It is §34's own
tiers translated: manual → `yaml`, connector config → `connect`, Kubernetes
config → `kubernetes`, with `kafka` absent from §34 entirely.

**Collections: additive, with an element identity per collection.**

| collection | element identity |
|---|---|
| `links[]` | `(rel, normalized url)` |
| `backings[]` | `(plugin, kind, reference)` — ADR-0022 |
| `sources[]` | derived from membership; not stored as a decision |
| `metadata{}` / `metrics{}` | plugin namespace — ADR-0006, ADR-0028 |

`links[]` is deduplicated on the **normalized** URL, after ADR-0032's
scheme-prepending, and is **not** keyed by plugin.

## Consequences

- **`topology.io/owner` stays load-bearing.** Option B would forbid `kubernetes`
  from writing `ownerKey`, so a node discovered but not declared would have no
  owner even though `enricher-v2` carries the annotation — making a slot in
  ADR-0032's deliberately **closed** nine-key vocabulary dead code. That cost
  lands on the fixture's most important node.
- **Research #4's one objection to option C does not survive ADR-0043.** The
  stated cost was that the inspector could explain which source *would* win by
  rule, not which *did* set the value. Under a fold, it knows.
- A *global* order rather than per-field: no field was found wanting a different
  one, and a per-field table is a thing to maintain and get wrong, bought with no
  fixture evidence. The tail is nearly decorative — `kafka` and `connect` contest
  only `type`, and only under an ADR-0020 cross-type collision — but ordering
  them removes a "we never decided".
- **`rel` could not be the link key.** ADR-0032 admits one `workload`/`pods` link
  *per workload backing*, and ADR-0021's contested key **unions** backings, so a
  node can carry two `workload` links from `kubernetes` alone. Keying by `rel`
  would silently drop a real second workload.
- **Dedup must be blind to who said it, and must run after normalization.**
  `payments-enricher` carries `topology.io/repository: github.com/acme/payments-enricher`
  *and* a YAML `repository` entry. Keying by plugin renders two identical
  "Repository" buttons on the fixture's most-linked node; deduplicating the raw
  string does the same whenever one writer supplied a scheme and the other did
  not.
- Two writers with the same `rel` and genuinely different URLs produce **two
  links, both visible**. That is ADR-0031's failure direction — a visibly-wrong
  extra beats a silent drop — and it lets a human see that two sources disagree.
- ADR-0032's derived-vs-authored link hazard ("a template change leaves stale
  composed links accumulating") cannot occur: ADR-0043 replaces the snapshot
  rather than merging into it.
