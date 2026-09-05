# ADR-0112: Every discovery plugin is Community; technology is not a buyer axis

- **Status**: Accepted
- **Date**: 2026-09-05
- **Ticket**: [The Community/Enterprise feature ledger](https://github.com/fredskor/nodqora/issues/42)

## Context

[The map](https://github.com/fredskor/nodqora/issues/40) fixed *"every discovery
plugin"* on the Community side while charting, but product plan §43 lists
**enterprise integrations** as a possible Enterprise feature, and the temptation
is concrete rather than abstract. Plan §12's tier 5 contains Datadog, New Relic
and Dynatrace; tier 6 contains AWS, Azure and GCP. These are the classic paid
connectors — [the survey](https://github.com/fredskor/nodqora/issues/41) found
Airbyte's licence key literally carries an `enterpriseConnectorIds` field, and
Collate relicensed every OpenMetadata ingestion connector in 2024.

It is also the *easiest* thing to gate. ADR-0015 collects plugins as Spring
`@Component`s, so a proprietary plugin is another jar on the classpath and needs
no mechanism at all — it is the possession-of-artifact gate the survey found most
vendors actually rely on, available for free.

Against that stands the rule and a principle. The split rule is **buyer-based**,
and which technologies a team happens to run on is not a property of the buyer —
it is an accident of the stack they inherited. A four-person team on EKS and MSK
needs the AWS plugin to see the truth of their system, which is the Community
half of the rule stated verbatim; the identical team on self-managed Kubernetes
and Kafka needs nothing. Gating by technology sets the price by someone else's
architecture decision.

Plan §5.2 makes it sharper. **Technology-agnostic by design** is the principle
the core is built around — `CONTEXT.md` opens by noting that nothing in the
domain model names Kubernetes, Kafka or Connect. A free/paid boundary drawn by
vendor is that principle contradicted at the commercial layer while being
enforced by ArchUnit at the code layer.

## Decision

**Every discovery plugin is Community, across all six tiers of plan §12** —
cloud providers and commercial-vendor observability included. There is no
Enterprise plugin.

Scale remains the paid axis, not technology; what a licence may count is
[the pricing unit ticket's](https://github.com/fredskor/nodqora/issues/45)
decision.

**The one reserved case is cost recovery, not tiering.** An integration that
requires a paid vendor account or a commercial partnership *even to build and
test* may be priced individually, and if that ever happens it is recorded as an
exception naming that specific cost — never as a tier, and never as a category.

## Consequences

- **The answer to "does Nodqora support my stack?" is never "that depends what
  you pay".** For a product whose entire value is completeness of the map, a
  partial free map is a broken free product, and the §77 risk — engineers
  abandoning the diagram — bites hardest where the diagram has holes.
- **The cheapest available gate is given up deliberately.** Enterprise plugins
  would need no licence key, no mechanism and no enforcement — the survey's
  majority pattern, free of charge. It is declined because it prices the wrong
  thing, not because it is hard.
- **This closes §43's "enterprise integrations" line.** The ledger records it as
  resolved against, with this ADR as the reason.
- **Cost: a genuinely expensive integration has nowhere to recover its cost from
  except the reserved case**, and that case is deliberately narrow — it covers
  vendor fees, not engineering effort. A Dynatrace plugin that takes three months
  to build is Community regardless, and the argument for building it has to be
  adoption rather than revenue.
- **Cost: this forecloses the most natural expansion-revenue mechanism.**
  Charging per connector is how several products in the survey grow an account
  without a price rise. Nodqora gives that up and leaves
  [issue #45](https://github.com/fredskor/nodqora/issues/45) a smaller set of
  units to choose from — which is a constraint that ticket should know it
  inherited rather than discover.
- **Revisit trigger.** An integration whose vendor terms require a commercial
  relationship to redistribute, or adoption evidence that plugin breadth is what
  buyers actually pay for elsewhere in the category — the second would mean the
  buyer rule mis-classified an accident of stack as a non-buyer property.
