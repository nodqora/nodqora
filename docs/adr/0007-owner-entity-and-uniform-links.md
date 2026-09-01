# ADR-0007: Owner is an entity; every navigation target is a link with an open `rel`

- **Status**: Accepted
- **Date**: 2026-09-01
- **Ticket**: [Core graph domain model](https://github.com/fredskor/nodqora/issues/3)

## Context

Product plan §20 lists seven optional ownership attributes on a node, and §21
lists per-node-type action links. The two lists overlap confusingly: repository
and runbook appear as ownership attributes but behave exactly like links.

The reference pipeline separates them by *cardinality* instead:

- **Team-level** facts — Slack channel, on-call group — belong to two teams and
  are repeated across ten nodes if stored per node.
- **Node-level** facts — repository, runbook, docs, Grafana, Logs, Argo CD,
  Kibana, Connect UI — vary per node, from five links down to none at all.

## Decision

**Owner is a first-class entity**: `key`, `displayName`, `channel`, `onCall`.
Nodes reference it by `ownerKey`. Team facts are stored once.

**Everything navigational is one uniform list**: `links: [{ rel, label, url }]`,
where `rel` is an **open string** with well-known values — `repository`,
`runbook`, `docs`, `dashboard`, `logs`, `gitops`, `workload`, `config`.
Repository and runbook are links, not fields.

Plugins and YAML both append to the same list. The inspector pins well-known
rels first and renders the rest generically.

## Consequences

- One mechanism and one renderer for every navigation target — matching
  ADR-0001's open-string treatment of types and relations.
- Ownership is a graph property: nodes crossing the fixture's team boundary at
  the connectors reference a different Owner, and "who do I page" is answerable
  per node.
- Nothing in the model guarantees a service has a repository. `stripe-webhooks`
  has a name, a type and nothing else, and that is valid — the inspector needs
  a **designed empty state**, not an error path. Handed to
  [Canvas and node-inspector prototype](https://github.com/fredskor/nodqora/issues/7).
- A typo'd `rel` renders generically rather than failing, same trade as
  ADR-0001.
- §20's escalation policy is not modelled in the MVP; it is an Owner field to
  add when something needs it.
