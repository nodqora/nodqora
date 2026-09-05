# ADR-0126: There is no free Enterprise tier, at any threshold

- **Status**: Accepted
- **Date**: 2026-09-05
- **Ticket**: [Pricing unit and trial](https://github.com/fredskor/nodqora/issues/45)

## Context

The ticket asked whether a free Enterprise tier exists under some threshold —
under N engineers, under N nodes, for small teams. The survey's free tiers are
Grafana's and GitLab's, and both are SaaS.

## Decision

**There is no free Enterprise tier and no threshold of any kind. A free key is a
sales decision, not a tier.**

Nothing prevents issuing a zero-cost, full-term key to a university, an OSS
foundation or a design partner. That stays a contract act with no product
surface, no published threshold and no expectation attached — which is how it
can be withdrawn without anyone calling it a takeback.

Rejected, and why:

- **A threshold on anything Nodqora observes.** It contradicts ADR-0123's
  load-bearing sentence directly. The model's one virtue is having exactly one
  countable object.
- **A threshold on organisational size** — headcount or revenue. Those are facts
  the product cannot see at all, so the boundary would be neither visible nor
  enforceable, and it reintroduces a count through a door the product cannot
  even reach.
- **A free tier without a threshold**, for anyone self-hosting. Any free
  Enterprise means publishing the artifact, which is ADR-0125's wall: it is a
  self-serve trial that never expires. Grafana's and GitLab's free tiers work
  because the vendor keeps a live hand on the switch; there is none here.

## Consequences

- **The buyer rule makes this close to tautological.** An organisation small
  enough to qualify for a free tier is one team, and so needs no view-scoping,
  no cross-install audit aggregation and little journal. The free tier would go
  to precisely the people with no use for it.
- **Community is already the answer for small**, and lavishly: every discovery
  plugin across all six tiers, the whole canvas, health, search, links, all of
  phase 8 including blast radius, drift, one-shot comparison, the incident view,
  OIDC and SAML authentication, and complete audit capture. A ten-person team
  lacks nothing.
- **If small organisations do turn out to need Enterprise, the fix is ADR-0107,
  not a discount.** A free tier would be a way of not noticing that the ledger's
  line is in the wrong place.
- **Revisit trigger.** Repeated inbound from organisations that are genuinely
  small *and* genuinely need view-scoping — a consultancy running many clients'
  estates in one install is the plausible shape. That means the buyer rule is
  measuring the wrong thing and reopens ADR-0107 and ADR-0123 together, rather
  than adding a tier here.
