# ADR-0011: The YAML topology is a plugin; there is no generic external node

- **Status**: Accepted
- **Date**: 2026-09-01
- **Ticket**: [Plugin/adapter contract for the MVP](https://github.com/fredskor/nodqora/issues/6)

## Context

`CONTEXT.md` defines a declared node as one whose `sources` contains `yaml` —
which places `yaml` alongside `kubernetes` in a list of contributors. ADR-0001
already says descriptors are registered "by plugins **and by the YAML topology
alike**". Both sentences are special cases in the core unless YAML is a plugin.

Separately, product plan §47 says "Iceberg and Elasticsearch may initially be
generic external nodes" — written before the model existed, and reaching for
"a node we can draw without building an integration for it".

## Decision

**The YAML topology loader is a Plugin**: id `yaml`, declaring Discovery only,
contributing descriptors like any other.

It is polled on the topology cadence like everything else, re-reading its source
each run, rather than driven by a bespoke file watcher.

**There is no generic-external-node mechanism** — not a type, not a flag, not a
fallback path. Everything §47 was reaching for is already covered:

| what "generic external node" wanted | what covers it |
|---|---|
| exists in the graph with no integration | the `yaml` plugin |
| renders with a sensible icon and label | YAML-registered TypeDescriptor (ADR-0001) |
| does not pretend to have health | no backings, so never routed to an observer (ADR-0013) |
| still has somewhere to click through to | declared links and type templates (ADR-0010) |

Say **declared node**.

## Consequences

- #12 gets **one** merge path. `payments-enricher` arriving as `[yaml,
  kubernetes]` is literally two plugins' Discovery output meeting in the same
  merge, not "a declared node that discovery then decorates".
- The observe/assert difference — adapters watch a running system, YAML asserts
  intent — is expressed as **capability presence**: `yaml` declares Discovery
  and not Health. Its nodes being permanently `UNKNOWN` is a consequence of the
  capability set, not a rule anyone wrote.
- Uniform contract is **not** uniform precedence. Whether `yaml` beats
  `kubernetes` on a contested field stays #12's decision, expressed as config
  over plugin ids, not baked into the interface.
- The fixture overtook §47: `analytics.payments_events` is not a generic box but
  a typed `iceberg-table` with its own descriptor, its own Trino link and
  `UNKNOWN` health — with zero lines of Iceberg code. Four of ten nodes are like
  this.
- An `external` type or an is-generic flag would fork the graph into first- and
  second-class nodes, which ADR-0005 explicitly forbids: nothing in traversal,
  search or layout may tell a declared node from a discovered one.
- The YAML file format and its environment scoping remain #14's.
