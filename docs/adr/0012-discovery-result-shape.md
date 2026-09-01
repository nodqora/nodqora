# ADR-0012: Discovery returns keyed core nodes as full snapshots that declare their own outcome

- **Status**: Accepted
- **Date**: 2026-09-01
- **Ticket**: [Plugin/adapter contract for the MVP](https://github.com/fredskor/nodqora/issues/6)

## Context

The Discovery capability is the joint where #8 (identity resolution) and #12
(merge semantics) both plug in, so its shape has to be fixed without pre-empting
either.

Two forces shaped it. First, of the three code plugins only **one** has an
identity problem: a Kafka topic *is* its own logical key, a Connect connector
*is* its own logical key, and only Kubernetes has the fixture's deliberate
mismatch between `enricher-v2`, `enrich-consumer-prod` and `payments-enricher`.

Second, research #5 established that **Kafka authz failures return empty rather
than erroring** — a principal without `Describe` gets a successful response
listing nothing.

## Decision

```text
DiscoveryResult { nodes[], edges[], descriptors[], outcome }
outcome = COMPLETE | PARTIAL(reasons[]) | FAILED(cause)
```

**Nodes carry final keys.** The plugin resolves identity itself and returns
core-vocabulary nodes; #8 writes the rules it follows, #12 merges by
`(environmentKey, key)`. There is no parallel claims model.

**Whole nodes with nulls.** A plugin populates only what it knows; `null` means
*no opinion*, not *empty*. That is ADR-0008's per-node `sources[]` doing its job
with no per-field structure.

**Edges may dangle.** A plugin emits edges referencing keys it does not own —
the `connect` plugin must emit `payments-es-sink → payments-events-v1` for an
index only YAML declares.

**Descriptors ride in the result.** The registry is the union of the latest
*successful* result per (plugin, environment), plus ADR-0001's fallback.
Descriptors are **global, not environment-scoped**. Conflicts resolve
**register-if-absent** — first registration wins, later ones are ignored and
logged; order is plugin order, code plugins before `yaml`.

**Invocation** is one call per (plugin, environment) pair, in parallel on
virtual threads with an engine-imposed timeout. Plugins are stateless
thread-safe singletons; all per-run state arrives as arguments.

**Two cadence loops, not per-plugin schedulers** — ADR-0003's boundary is a
cadence boundary, so it becomes a slow topology loop calling Discovery and a
fast state loop calling Health, each with a default interval and per-plugin
overrides in config. The intervals themselves are #10/#11's.

**A result is a full snapshot of that plugin's scope for that environment, never
a delta.** Plugins do no diffing and hold no previous state.

## Consequences

- A claims-based model would have been built so that one of three plugins could
  use it, with the other two filling in `claims: [{name: <the key>}]`. It would
  also put a resolver in the core that must understand Kubernetes annotations to
  be useful — the technology leakage §5.2 forbids.
- **There is no index of identity claims across plugins.** Two plugins
  independently claiming one key is invisible until merge. That is correctly
  placed: cross-plugin contests *are* merges (#12). Contests within one plugin
  stay visible to that plugin, which is where the fixture puts them.
- `null` cannot express "deliberately clear this field" — handed to #12.
- Descriptors exist only after a first successful poll, so cold start renders
  with fallbacks (which ADR-0001 designs for), and a failed poll must retain the
  last known set rather than clearing it.
- YAML cannot restyle `service` away from the `kubernetes` plugin's descriptor.
  Its descriptor mechanism is a gap-filler for types with no plugin behind them,
  not a theming layer, and making it one would make the canvas depend on
  file-read ordering.
- **`outcome` is not a completeness guarantee.** It reports only what a plugin
  *can* know — a sub-call threw, a page did not complete, a timeout was hit. The
  Kafka authz case is invisible to the Kafka plugin too. A `COMPLETE` result may
  be silently blind, and #12 owns that residual ambiguity: what this contract
  owes it is full snapshots, a well-defined per-plugin scope, and an explicit
  failure signal so a `FAILED` snapshot is never mistaken for an empty one.
- One failing (plugin, environment) pair does not touch the others — a dead
  staging Connect cluster must not stall production discovery.
- Three plugins stay simple and one engine gets smart. That is what makes #12
  possible at all.
