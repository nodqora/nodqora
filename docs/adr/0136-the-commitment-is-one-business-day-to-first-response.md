# ADR-0136: The commitment is one business day to first response, and nothing else

- **Status**: Accepted
- **Date**: 2026-09-07
- **Ticket**: [Support, SLA and what Community gets](https://github.com/fredskor/nodqora/issues/49)

## Context

The ticket named the binding constraint itself: *"what response commitment the
maintainer can actually honour at current headcount, which is the constraint
that should set the answer rather than the precedent."*

The headcount is one, in a single timezone. Anything resembling 24x7 is a
promise to be woken up, alone, indefinitely. Grafana's published bundle —
*"24x7x365 support with a 2-hour critical-response SLA"* — is a follow-the-sun
rotation described as a number.

Two things about this product bound the severity ceiling from the other side.
**Nodqora observes; it does not serve traffic.** Nothing in a customer's estate
stops when Nodqora stops, which is not true of Kong in the request path or
Temporal in the execution path. And ADR-0120 means even a fully lapsed key
leaves the install booting, the canvas rendering and health refreshing, so there
is no licensing-caused outage at all.

The honest counter is that Grafana's tight SLA exists because Grafana is what an
engineer stares at *during* an incident, which is much closer to Nodqora's shape
than Kong is. ADR-0107 already gave the incident view to Community, so an
Enterprise customer's incident-time dependency is a free capability either way —
which weakens the argument for pricing a tight commitment into Enterprise but
does not make an outage less annoying.

## Decision

**One business day to first response, Monday to Friday, in a published
timezone. No restoration commitment, no resolution commitment, no severity
ladder, and nothing 24x7.**

**First response only**, because it is the only quantity one person controls.
Restoration depends on what broke, and for a self-hosted install running a
version chosen by the customer, half of restoration is the customer's own hands
on their own cluster — a commitment covering it would be a commitment to
something not being done by the party making it.

**Flat rather than graduated.** A severity ladder is a thing that can be failed:
at one maintainer the failure mode is sleep or a flight, and a ladder converts an
ordinary bad week into a contractual breach. It also inherits ADR-0123's actual
payoff — the commitment stays one sentence, with no matrix and no argument about
which rung an incident landed on.

**The timezone is published, not hidden.** UTC+4 appears in `docs/editions.md`
so that a buyer in California can see they will get an answer overnight and
decide accordingly. A commitment whose working hours are undisclosed is a
commitment measured in a unit the buyer cannot convert.

Rejected, and why:

- **Two rungs** — same business day when an install is down or the data path has
  stopped refreshing, next business day otherwise. More useful to a buyer, and
  genuinely honourable because down-installs are rare, but it introduces an edge
  to argue about at precisely the moment nobody wants to argue.
- **A full severity ladder with a critical tier measured in hours.** What
  enterprise procurement expects to see, and dishonest without a rotation.
- **Hiding the timezone behind "business hours".** It reads better and it makes
  the number unconvertible, which is the part a buyer needs.

## Consequences

- **This is a commitment a single person can keep on a bad week**, which is the
  only kind worth publishing. Its value is that it will not be quietly broken.
- **It will lose deals to a published two-hour number**, and the loss will be
  invisible — a procurement checklist scored before anyone gets on a call.
- **Publishing UTC+4 costs the US-West buyer explicitly.** They see overnight
  turnaround before they see the product. Accepted: they would discover it in
  week one anyway, and later is worse.
- **Nothing here scales with customer count.** Ten customers at one business day
  is a different job from one customer at one business day, and the commitment
  does not notice the difference. That is the trigger below.
- **Revisit trigger.** A week in which the commitment is missed for reasons of
  volume rather than circumstance, or the arrival of a second person who can
  hold a rotation. The first is evidence the commitment now exceeds the
  headcount; the second is the only thing that makes a tighter one honest.
