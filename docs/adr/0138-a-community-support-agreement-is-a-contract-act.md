# ADR-0138: A Community support agreement is a contract act, not a tier

- **Status**: Accepted
- **Date**: 2026-09-07
- **Ticket**: [Support, SLA and what Community gets](https://github.com/fredskor/nodqora/issues/49)

## Context

When this map ruled a middle tier out of scope, it sent one item here: *"a paid
support tier on Community is bound by none of the above and is the one thing
that monetizes the free tier ADR-0123 and ADR-0126 deliberately made fat."*

Two clearing facts. **ADR-0128 does not bind this** — its promise covers
capabilities *shipped in Community* never moving to Enterprise, and support is a
service, not a shipped capability. Selling support against Community later is
not a demotion, and stays available permanently whatever is decided here. And
**it is the only thing that could be sold before the Enterprise repository
exists**: this map's destination is the point at which that repository can be
*started*, while a support agreement needs no artifact, no key, no code and no
second assembly.

Against that, support-only is a services business. It consumes the single scarce
resource linearly, it is what Frontside does as an entire company, and a
published offering is far harder to withdraw than one never made.

## Decision

**A paid Community support agreement is a contract act, not a tier. No published
price, no product surface, no threshold, no page. Available if asked.**

This is deliberately the same shape ADR-0126 gave the free Enterprise key, and
for the reason that ADR recorded: *"that stays a contract act with no product
surface, no published threshold and no expectation attached — which is how it
can be withdrawn without anyone calling it a takeback."* Applying one shape in
both directions makes the pair legible — **nothing is sold from a page that has
not first been sold to a person.**

The concrete reason not to publish now: with no users, a published support
offering advertises a business whose price would be discovered on the first
call. That is the same problem ADR-0127 identified for the Enterprise number,
where the answer was contact-us with a trigger. This is the stricter version of
the same call, because here even the *shape* is unproven.

Where such an agreement is signed, it is ADR-0136's commitment and ADR-0140's
scope, against the Community edition, for a term. There is no second commitment
shape to design.

Rejected, and why:

- **A published paid Community support offering** — flat annual, private channel
  plus the same one-business-day commitment, no Enterprise capabilities. It puts
  a real number in front of the fat free tier and it is the only sellable thing
  before the Enterprise repository exists. Declined on timing, not on merit.
- **Ruling it out entirely**, so support is only ever bought with Enterprise. It
  keeps exactly one commercial object and pushes every payer toward the edition
  that funds the product — and it discards the one revenue path available to a
  team that needs assurance and no Enterprise capability at all.

## Consequences

- **The fat free tier stays unmonetized, on purpose, for now.** ADR-0123 and
  ADR-0126 made Community deliberately complete; this declines to charge for the
  only thing it lacks. The decision is about timing and survives being wrong
  cheaply — publishing later costs a page.
- **ADR-0126's asymmetry is now symmetric.** A free Enterprise key and a paid
  Community agreement are both contract acts with no published surface, which
  means the published commercial model is exactly two objects: an edition and
  its price.
- **A team that wants assurance without Enterprise capabilities has nothing to
  find.** They have to ask, and most will not know to. That is the cost of not
  publishing, and it is the trigger below.
- **Revisit trigger.** Inbound asking to pay for support against Community —
  two independent asks, or one from an organisation whose name would carry. At
  that point the shape is proven by demand rather than guessed, and publishing
  becomes a page rather than a decision.
