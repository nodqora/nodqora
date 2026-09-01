# ADR-0015: The core has no compile-time knowledge of any plugin

- **Status**: Accepted
- **Date**: 2026-09-01
- **Ticket**: [Plugin/adapter contract for the MVP](https://github.com/fredskor/nodqora/issues/6)

## Context

Product plan §5.2 makes it non-negotiable that the core does not know whether a
node came from Kafka, Kubernetes or anything else. ADR-0009 trimmed `namespace`
out of the node shape so that "nothing in the core names a technology".

That is currently a promise maintained by discipline, and discipline erodes one
convenient `if` at a time.

## Decision

Three modules, with the dependency arrow pointing one way:

```text
nodqora-plugin-api   immutable records only: Plugin, DiscoveryCapability,
                     HealthCapability, DiscoveryResult, StateContribution,
                     Node/Edge/Backing/Link value types, descriptors.
                     No Spring Data, no persistence, no core dependency.
nodqora-core      →  depends on plugin-api. Never on any plugin.
nodqora-plugin-*  →  depend on plugin-api. Four of them.
```

The contract's value types are **records in `plugin-api`**, not the persistence
entities. ADR-0012's "core vocabulary" means the vocabulary, not the JPA
objects.

Plugins are Spring `@Component`s in the **same deployable**, collected by
injecting `List<Plugin>`. One JAR, one process, no classloader isolation, no
separate artifacts.

The boundary is enforced by an **ArchUnit test** that fails the build if
`nodqora-core` references any plugin package — and, more bluntly, if core source
contains the identifiers `kubernetes`, `k8s`, `kafka` or `connect` at all.

## Consequences

- §5.2's central claim becomes checkable rather than aspirational, which is the
  cheapest possible guard on the product's main differentiator.
- The blunt identifier check will occasionally fire on something innocent (a
  variable named `connect`). Renaming it is a smaller cost than the drift the
  check prevents.
- Plugins cannot reach into core services; anything they need must be an
  argument on a capability method. That is what keeps ADR-0012's "stateless
  singleton, all state arrives as arguments" honest.
- A future out-of-process or independently-installed plugin changes only the
  registration mechanism, not the interfaces — `plugin-api` is already free of
  Spring and persistence.
