# ADR-0127: The price is contact-us until a publish trigger fires

- **Status**: Accepted
- **Date**: 2026-09-05
- **Ticket**: [Pricing unit and trial](https://github.com/fredskor/nodqora/issues/45)

## Context

The ticket forbids price *points*, but whether a number appears on a page at all
is shape rather than point, and it interacts with ADR-0123. That ADR accepted
the loss of in-install expansion revenue on the understanding that the band
would be negotiated at contract time. **Publishing a single number destroys that
mitigation**: the bank and the twenty-person startup then pay the same,
publicly, permanently.

The survey found nobody publishes a self-hosted Enterprise price — Grafana
publishes eleven Cloud unit prices and no self-managed figure, GitLab has
withdrawn Ultimate's list price, DataHub's pricing URL 404s. Contact-us is the
norm across all ten.

## Decision

**Contact-us now, with a committed trigger to publish: five closed Enterprise
deals, or twelve months of active selling, whichever comes first.**

**The unit and the trial terms are published immediately** on
`docs/editions.md`, even while the number is withheld — so a reader can tell
without a call that they will be charged once per deployment, annually, and
never per node or per seat. That is most of what an evaluator wants to know.

Rejected, and why:

- **Publishing a list price today.** There are no customers, so any number is a
  guess, and a published guess is an anchor owned permanently. GitLab's single
  increase in five years — $19 to $29 in April 2023 — needed a post justifying
  "more than 400 features" and a year of transition pricing for existing
  customers. The first handful of deals *are* the price discovery.
- **Contact-us permanently**, as all ten surveyed vendors do. It is the practice
  this product's audience actively resents, and it wastes the payoff of
  ADR-0123: a flat unit is the most publishable price shape there is, with no
  configurator and no "it depends."

## Consequences

- **Contact-us loses the evaluator who will not take a call**, and for a
  self-hosted infrastructure tool aimed at engineers that is a real fraction of
  them. Publishing the unit and the trial terms recovers part of it; nothing
  recovers the rest until the trigger fires.
- **It sits awkwardly beside the open-core trust story**, where absence of
  hidden pricing is part of the pitch. The mitigation is that the *model* is
  fully disclosed and only the figure is not.
- **When the trigger fires, publishing becomes a differentiator**, because
  nobody in the category does it.
- **Raising a published price later is a public act.** GitLab's precedent sets
  the standard to meet: an explicit justification and a transition period for
  existing customers. That obligation is taken on at the moment of publishing,
  not at the moment of raising.
- **Revisit trigger.** The stated trigger *is* this ADR's revisit: five closed
  deals or twelve months. It fires a decision about a number, which is a
  question this map holds out of scope.
