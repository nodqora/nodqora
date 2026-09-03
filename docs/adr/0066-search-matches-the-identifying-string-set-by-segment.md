# ADR-0066: Search matches the identifying string set only, split into segments

- **Status**: Accepted
- **Date**: 2026-09-03
- **Ticket**: [Simple name search behaviour](https://github.com/fredskor/nodqora/issues/15)

## Context

ADR-0023 fixed the identifying string set for a node as `key`, `displayName`
and `backings[].reference`, and handed this ticket two residuals: whether search
matches a technology-shaped reference (`payments-prod/enricher-v2`) whole or by
its parts, and whether the searchable set should be wider than those three at
all.

The set *could* be wider at zero technical cost. ADR-0053 puts the whole
environment document in the client's hands, so `type`, `ownerKey`, link URLs,
`metadata.image` and the raw health signal are all already present.

## Decision

**Searchable strings are exactly `key`, `displayName` and
`backings[].reference` — no wider.**

**A backing reference is searchable both whole and by its `/`-separated
segments.**

## Consequences

- Widening to `ownerKey` or `type` is not search, it is the **filter set
  ADR-0018 deferred**, arriving through the search box without being designed.
  On the fixture, `payments-platform` owns four nodes and `connect-connector`
  matches two — result rows with no distinguishing string to display, which
  breaks ADR-0023's requirement that a hit can show *what* matched.
- The cost is that an SRE who knows the team but not the node gets nothing.
  `data-platform` owns four fixture nodes and there is no other way to see them
  as a set. That gap closes when filter-by-owner is built, not here.
- Segment-splitting exists because **the SRE's clipboard holds `enricher-v2`,
  not `payments-prod/enricher-v2`** — the string arrives from a Kubernetes
  alert. Making it a first-class match rather than an incidental substring hit
  is what lets ADR-0067 rank on anchoring. Keeping the whole reference costs
  nothing and serves a paste from a `kubectl` context.
- **This partially defeats the decision above, and is accepted.** Splitting
  makes `payments-prod` a token on every Kubernetes-backed node, so typing it
  returns the whole namespace — owner-filtering by the back door, the exact
  thing the first half of this ADR excluded. Suppressing it would require the
  frontend to know which segment of a plugin's reference is a namespace, which
  ADR-0015 forbids. Recorded as a known leak, not solved.
