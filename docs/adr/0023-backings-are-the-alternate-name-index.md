# ADR-0023: There is no alias field; backings are the alternate-name index

- **Status**: Accepted
- **Date**: 2026-09-02
- **Ticket**: [Identity-resolution rules for the MVP](https://github.com/fredskor/nodqora/issues/8)

## Context

Product plan §34 lists aliases among the techniques for identity resolution, and
the MVP Node shape (ADR-0009) has no such field. The fixture builds a three-way
mismatch — `payments-enricher` / `enricher-v2` / `enrich-consumer-prod` — and
notes that "search must disambiguate, not just substring-match". An SRE arriving
from a Kubernetes alert holds `enricher-v2`, which is not the key.

Aliases cannot be *resolution* input in any case: ADR-0020 rejected the
alias-index key scheme, and ADR-0012 forbids a central resolver. The only live
question is what identifies a node for search.

## Decision

**No alias field.** A node is identified by `key`, `displayName`, and its
`backings[].reference` values.

## Consequences

- All three of the fixture's strings are already on the node once ADR-0021 and
  ADR-0022 have run — `key` is `payments-enricher`, and the backings reference
  `enricher-v2` and `enrich-consumer-prod`. Nothing is lost and nothing is
  hand-maintained: discovery populates it.
- Consistent with ADR-0009, which dropped `externalId` for precisely this reason
  ("backings carry the external reference"). Adding `aliases[]` would put back a
  field that trimming removed, largely duplicating backing references, and would
  introduce another multi-writer collection for #12 to merge.
- **This is the input [#15](https://github.com/fredskor/nodqora/issues/15)
  needs**: the searchable string set for a node is exactly those three, and a
  match on a backing reference is a legitimate hit that should say *why* it
  matched — `payments-enricher` found via Deployment `enricher-v2`.
- A name that is genuinely not a backing — a Git repository, a Prometheus job, a
  legacy name kept after a rename — has no home. In the MVP's four plugins every
  such name is either a backing reference or a `Link` (ADR-0007), so the gap is
  empty today. It reopens when a plugin arrives whose identifiers are neither.
- Backing references are technology-shaped (`payments-prod/enricher-v2`), so
  search must decide whether to match the whole reference or its segments. That
  is #15's.
