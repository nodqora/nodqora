# ADR-0004: Environment is an entity; a node's natural key is (environment, key)

- **Status**: Accepted
- **Date**: 2026-09-01
- **Ticket**: [Core graph domain model](https://github.com/fredskor/nodqora/issues/3)

## Context

Product plan §23 requires the product to understand environments — view one,
switch, and eventually compare and show differences.

The reference pipeline makes environments load-bearing rather than decorative:

- `production` has 10 nodes and 9 edges; `staging` has 7 and 5.
- Staging is missing the entire Iceberg branch — the plan's own §23 example
  ("Production has connector X. Staging does not.") made concrete.
- Each environment has its own Kubernetes namespace, Kafka bootstrap and
  Connect cluster URL. That connection configuration has to live somewhere the
  API and UI can see, because the environment switcher is populated from it.

## Decision

**Environment is a first-class entity** holding its own discovery connection
configuration.

Every Node and Edge belongs to **exactly one** Environment. The natural key of
a Node is the pair `(environmentKey, key)`; production and staging
`payments-api` are two rows sharing the key `payments-api`.

An environment that lacks a component has **no row** for it. Drift is absence.

## Consequences

- Staging drift needs no representation at all — `trino-analytics` simply has
  no staging row.
- `NodeState` needs no environment dimension: it hangs off a node that is
  already environment-scoped.
- Health, links, metadata and ownership can differ per environment for free,
  because they are per-row.
- Future §23 environment comparison is a set diff on `key` across two
  environments — no new model.
- **Cost**: shared metadata is duplicated per environment. `payments-api`'s
  owner and repository are stored twice. The YAML topology format will need
  defaults or inheritance to keep authoring tolerable — a constraint handed to
  [YAML topology format](https://github.com/fredskor/nodqora/issues/14).
- The environment roster is queryable, so the UI's environment switcher has an
  authoritative source.
