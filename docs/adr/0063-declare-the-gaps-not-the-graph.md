# ADR-0063: A YAML stanza declares the gaps, not the graph

- **Status**: Accepted
- **Date**: 2026-09-02
- **Ticket**: [YAML topology format](https://github.com/fredskor/nodqora/issues/14)

## Context

ADR-0044 puts `yaml` at the top of the global merge precedence order —
`yaml > connect > kubernetes > kafka` — precisely so it can serve as §56's
manual-override mechanism with no pinned-fields list behind it (ADR-0051).

That makes every field a YAML author writes an **override**, whether or not they
meant it as one. `type: service` on `payments-api` looks like documentation and
behaves like a veto over whatever `kubernetes` reports forever.

## Decision

**The node stanza is deliberately small, and the convention is to write only
what no other plugin can supply.**

The complete stanza:

```yaml
- key: <string>                 # required; ADR-0020's flat, case-folded key
  type: <string>                # open string
  displayName: <string>
  description: <string>
  owner: <owner key>            # -> ownerKey
  links: [{rel, label, url}]    # verbatim URLs (ADR-0061)
  consumerGroups: [<group>]     # -> one kafka consumer-group backing
  calls | producesTo | consumesFrom | sourcesFrom | writesTo | queries: [<key>]
```

There is nothing else. In particular:

- **No `metadata`.** ADR-0006's per-plugin allow-list has rows for `kubernetes`,
  `kafka` and `connect` and no row for `yaml`. Nothing in the MVP reads it, and
  a `yaml` namespace would be an override channel for a field the core never
  looks inside.
- **No generic `backings:` list.** Backings are the routing table for health
  (ADR-0013), so a file that could write `{plugin: kubernetes, kind: Deployment,
  reference: enricher-v2}` would redirect another plugin's observation with no
  object behind it. `consumerGroups:` is the one route ADR-0022 actually names —
  the consumer-group case, where the plugin that can *read* the lag is not the
  one that can attribute it — and it is exactly as wide as that gap.
- **No `relations:` block.** The six RelationDescriptors are core built-ins
  (ADR-0062).

In the fixture this produces stanzas of one and two lines for the six
discovered nodes. `payments-api` is `{key, producesTo}`. Both topics are `{key,
owner}` — because `kafka` emits no `ownerKey` and nobody else can. Both
connectors are `{key, owner, writesTo}` — `connect` emits no `ownerKey` either,
and cannot see its own destination (ADR-0041). Only the four **declared** nodes
carry `type` and `displayName`, because for those `yaml` is the only source.

## Consequences

- The convention is documentation, not enforcement. A file *may* set `type` on a
  discovered node and it *will* win; the format cannot tell an intended override
  from a redundant restatement, and neither can the fold. This is the accepted
  price of ADR-0051's decision not to build a pinned-fields mechanism.
- **A stanza that carries only verbs is a mention, and `sources[]` cannot tell a
  mention from a declaration.** `payments-api` is emitted by `yaml` as a node
  with no fields at all, so it arrives with `sources: [yaml, kubernetes]` — the
  same provenance as `payments-enricher`, which YAML genuinely says something
  about. That is acceptable because ADR-0008 records *that* a source contributed
  and never *which field it set*, and because ADR-0011 forbids any feature from
  telling a declared node from a discovered one. It does mean "declared node"
  is a statement about provenance, not about how much YAML had to say.
- A verb target with no stanza of its own is **ordinary**: ADR-0048 materializes
  a stub node at the endpoint. So a file may reference a node it never declares,
  and the graph is still complete.
- An `owner:` naming no owner block is likewise ordinary (ADR-0048), which is
  what lets `topology.io/owner` be annotated before anyone writes the YAML
  owners.
- TypeDescriptors are per environment and only for the types that environment
  uses: `staging/` registers `external-api` and `elasticsearch-index` and not
  `iceberg-table`, since descriptors ride with the graph (ADR-0055) and staging
  has no Iceberg table.
