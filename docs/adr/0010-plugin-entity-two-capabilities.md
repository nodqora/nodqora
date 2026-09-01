# ADR-0010: A Plugin is a first-class entity with exactly two capabilities

- **Status**: Accepted
- **Date**: 2026-09-01
- **Ticket**: [Plugin/adapter contract for the MVP](https://github.com/fredskor/nodqora/issues/6)

## Context

Product plan §10 sketches a `TopologyAdapter` with three methods and six
optional capabilities (`DiscoveryAdapter`, `HealthAdapter`, `MetricsAdapter`,
`LinkProvider`, `RelationshipResolver`, `ActionProvider`); §36 lists five and a
mature SDK's worth of machinery around them.

The core already depends on "who produced this" in four separate places, all
fixed by earlier ADRs: `metadata` is keyed by plugin id (ADR-0006),
`Backing.adapter` names one (ADR-0005), `sources[]` lists them (ADR-0008), and
`TypeDescriptor.source` records one (ADR-0001). Four subsystems agreeing on an
unenumerated string constant is how `k8s` and `kubernetes` become two inspector
sections.

## Decision

**`Plugin` is a first-class registered entity**: `id`, display label, declared
capabilities, config type, and the descriptors it contributes. A registry
enumerates them; the id is the single value used in all four places above.

The MVP ships **four** plugins with these exact ids, which are API surface the
frontend reads and not implementation detail:

`yaml` · `kubernetes` · `kafka` · `connect`

Kafka and Kafka Connect are **separate** plugins: different credentials and
trust levels (research #5 — no read-only Connect credential exists), different
failure domains, different metadata namespaces, independently present per
environment.

A plugin declares **two** code capabilities, either or both:

- **Discovery** — produces topology. See ADR-0012.
- **Health** — produces runtime state. See ADR-0013.

Metrics is not a third capability: ADR-0006 puts `metrics{}` on `NodeState`,
written by the same refresh as `health` and `rawSignal`, from the same round
trip.

**Links are data, not a capability.** A link reaches a node three ways: declared
in the YAML topology, emitted by a Discovery capability alongside `backings[]`,
or templated per node type in environment config
(`kafka-topic` → `https://kafka-ui/clusters/{env.kafkaCluster}/topics/{node.key}`).
Product plan §47's "configurable links" is the third route.

`TypeDescriptor.icon` is a **name from a fixed frontend icon set**, not a
shipped asset. An unknown name falls back exactly as an unknown type does
(ADR-0001).

Deferred from §36, and from §10's capability list: `LinkProvider`,
`RelationshipResolver`, `ActionProvider` (§22 — the MVP is read-only), UI
inspector panels (ADR-0006 renders metadata generically), search indexing hints
(#15 is name-only), event/change providers (§24 is out of scope), independent
installation, classloader isolation, plugin versioning and compatibility
contracts, capability *negotiation*, permission declarations, sandboxing,
registry/marketplace, a config-schema language, a published SDK artifact, and
any stability guarantee on these interfaces.

In the MVP a capability is present **iff the bean implements the interface** —
that is the whole of capability negotiation.

## Consequences

- `sources: [yaml, kubernetes]` resolves to known contributors the UI can render
  with a label and icon, rather than free text.
- Why links had to be data: of the five fixture nodes that most need links, four
  — `payments-events-v1`, `analytics.payments_events`, `trino-analytics`,
  `stripe-webhooks` — have **no code behind them**. A `LinkProvider` capability
  could not serve them at all.
- Link templates interpolate an untyped `metadata` blob, so a bad template
  yields a broken URL at render time with no validation. A dead link is visibly
  dead; accepted.
- The trigger for un-deferring the SDK machinery is **the first plugin we do not
  compile**. While all four are first-party and in-repo the interfaces can be
  reshaped in one commit; that freedom ends the moment someone else builds
  against them, so publishing an SDK is a decision to take deliberately rather
  than by tagging a release.
- The word is **plugin**. "Adapter" is retired — see ADR-0013 for the field
  rename it implies.
