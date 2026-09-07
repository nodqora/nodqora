# ADR-0135: Support is bundled into the subscription and runs with the term

- **Status**: Accepted
- **Date**: 2026-09-07
- **Ticket**: [Support, SLA and what Community gets](https://github.com/fredskor/nodqora/issues/49)

## Context

[The open-core survey](https://github.com/fredskor/nodqora/issues/41) found
support to be *"the one line item nobody has ever had to defend, relicense, or
walk back"* — but the four vendors it examined sell it in three incompatible
shapes. Grafana, GitLab and Kong **bundle** it into the paid tier. Frontside
sells it **separately and exclusively**, because it has no software to sell.
Temporal prices it as **the greater of a monthly floor or 5–10% of usage**.

The third shape is already foreclosed here.
ADR-0123
counts nothing, so there is no usage to take a percentage of, and a percentage
of a flat fee is only a larger flat fee.

What remained is whether support is a component of the Enterprise subscription
or a second thing sold beside it.

## Decision

**Support is bundled into the Enterprise subscription. There is no separate
support SKU, no support tier ladder, and no support add-on. The entitlement runs
with an active term — trials included — and ends at expiry.**

Bundled, because
ADR-0123's
stated payoff is that *"the model is one sentence long"*, and a separate support
SKU reintroduces exactly the configurator that ADR-0123 spent its argument
removing. The move after a second line item is always a tier ladder, and
Frontside's Silver / Gold / Platinum works because tiering support *is*
Frontside's entire product, staffed by a team that can differentiate standing
meetings, pairing sessions and code reviews between three columns. Here the
columns would be three sets of promises made by the same one person.

Bundling also removes the case where a customer declines support and files
issues anyway, which is what happens at this scale regardless of what was
signed.

**Trials are entitled**, on
ADR-0125's own
reasoning: the trial is full-featured because *"a trial with features removed
does not demonstrate the thing being sold"*. Support is now part of what is
sold. ADR-0125 also already records that **every trial costs a human**, so this
adds no cost that was not accepted there.

**The entitlement ends at expiry, not at the end of grace.**
ADR-0121's thirty-day
grace exists so renewal paperwork sitting in a procurement queue does not freeze
a customer's view scopes. It is a mechanism for the *software* not turning
hostile, and stretching support across it would convert it into a free month of
the thing being sold.

**One carve-out, written down rather than left to judgement: questions about
licence state, key reissue and renewal are answered for anyone, at any time,
in or out of term.** A customer whose control path has frozen and who asks how
to unfreeze it is not filing a support ticket — they are trying to pay. ADR-0121
frames the key as *"a renewal prompt, not a lock, and it is sold as one"*, and
declining to answer that question would make the prompt a lock.

Rejected, and why:

- **A separate support SKU sellable against either edition.** It makes a paid
  Community support tier trivial — the same object pointed at a different
  edition — and that convenience is not worth a second price to discover when
  ADR-0127 has
  not discovered the first.
- **Bundled, plus a paid escalation add-on** for customers wanting a tighter
  commitment. At one maintainer there is nothing to escalate *to*; the add-on
  would sell a second copy of the same person.
- **Support running through the thirty-day grace.** Kinder, and it makes grace
  a benefit rather than the mechanism ADR-0121 designed it as.

## Consequences

- **The Enterprise price now carries the support cost**, and there is no line
  item to point at when a customer asks what support costs. That is the intended
  effect: it cannot be declined, discounted away, or compared against a
  competitor's support SKU in isolation.
- **A Community self-hoster cannot buy support by buying a SKU**, which is what
  makes ADR-0138 a
  separate decision rather than a consequence of this one.
- **Support becomes a reason not to let a subscription lapse**, which is the
  first thing in this map that gives a lapsed key any teeth at all —
  ADR-0120 and
  ADR-0121 deliberately left the software fully working.
- **Revisit trigger.** A customer asking to buy a materially higher commitment
  than ADR-0136
  publishes, and willing to pay separately for it. That is the first real
  evidence for a second commercial object, and it reopens this ADR together
  with ADR-0136.
