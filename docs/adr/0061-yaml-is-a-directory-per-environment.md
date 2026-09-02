# ADR-0061: The YAML topology is one directory per environment, and every file declares the environment it is in

- **Status**: Accepted
- **Date**: 2026-09-02
- **Ticket**: [YAML topology format](https://github.com/fredskor/nodqora/issues/14)

## Context

ADR-0011 made the YAML topology a plugin and left "the YAML file format and its
environment scoping" to this ticket. ADR-0004 makes every Node and Edge belong
to exactly one Environment, and makes **drift absence**: staging lacks the
Iceberg branch by having no row for it, never by a flag.

The reference pipeline is 10 nodes in production and 7 in staging. Three
layouts were prototyped and **all three reproduce the fixture exactly** — same
10/7 nodes, same 7/5 YAML-owned edges, same owners, same descriptors. The
fixture does not discriminate. The choice therefore rests on what each layout
does to the *next* edit, not on what it can express.

## Decision

**One directory per environment. Every `*.yaml` in it is read together as one
snapshot. Each file declares `environment:` and is rejected if that disagrees
with the directory the config points at.**

```text
/etc/nodqora/topology/
  production/
    payments-platform.yaml
    data-platform.yaml
  staging/
    payments-platform.yaml
    data-platform.yaml
```

```yaml
# config, per ADR-0014
yaml: { dir: /etc/nodqora/topology/production }
```

Staging's drift is **not written anywhere**: `trino-analytics` simply has no
stanza in `topology/staging/`. That is the same absence `kubernetes` produces
when staging's namespace has no such Deployment, so `yaml` behaves like every
other plugin at the point where ADR-0004 is load-bearing.

Two alternatives were rejected:

- **One document with `environments: [production, staging]` on each node.** DRY,
  and impossible to forget — but drift becomes a *written statement*, and a
  declaration read once per environment cannot hold a URL, so links would have
  to become per-environment templates (ADR-0032's problem, re-imported into the
  one plugin that does not have it).
- **A base document plus per-environment overlays.** Needs `exclude:` — a
  hand-written **negative assertion**, which is the exact shape ADR-0051 refused
  one layer down.

**The `environment:` key is redundant by design.** The directory already
answers the question; the field exists so that the *copy* answers it too.
Per-environment files mean staging is written by copying production and editing
it, so a half-edited copy is the characteristic failure of this layout, and the
guard belongs where the mistake is made. A mismatch fails the environment
(ADR-0064).

**The fake-drift cost is accepted with no mechanism against it.** `yaml` is the
only plugin that *asserts* rather than observes, so it is the only one that can
manufacture drift: a declared node added to `production/` and forgotten in
`staging/` renders as drift that does not exist. Two guards were considered and
both rejected — a startup warning fires on the fixture's three **intended**
drifted nodes every five minutes forever to catch one hypothetical typo, and an
`absentFrom:` acknowledgement is the rejected `environments:` list wearing a
different hat, stated in the file that does *not* have the node. The guard is
code review: the two directories sit side by side in one diff.

## Consequences

- **YAML holds verbatim URLs, and it is the only writer that does.** The file is
  read once, for one environment, so `https://kibana.acme.io/...` in
  `production/` and `https://kibana-staging.acme.io/...` in `staging/` are two
  correct statements rather than one template. ADR-0032's argument (one manifest,
  two environments, an absolute URL points staging at production's dashboard)
  **does not reach** a per-environment file. The MVP's three link mechanisms are
  now: annotation ids composed against templates (`kubernetes`), object-name
  substitution (`kafka`, `connect`), and verbatim URLs (`yaml`).
- Teams get files. The fixture splits with **no key in both** —
  `payments-platform.yaml` carries the services and topics,
  `data-platform.yaml` the connectors and their destinations — which is not
  luck: ADR-0062 puts every edge in the stanza of the node *doing* the thing, so
  a cross-team edge never needs the other team's key.
- Adding an environment is copying a directory, and the `environment:` line is
  the one edit that cannot be skipped silently.
- Node keys stay environment-scoped without the format mentioning it: two
  directories, two snapshots, and ADR-0004's `(environmentKey, key)` falls out.
