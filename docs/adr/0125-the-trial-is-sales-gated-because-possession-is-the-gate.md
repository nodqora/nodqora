# ADR-0125: The trial is sales-gated, because possession is the gate

- **Status**: Accepted
- **Date**: 2026-09-05
- **Ticket**: [Pricing unit and trial](https://github.com/fredskor/nodqora/issues/45)

## Context

The ticket asked whether the trial is self-serve or sales-gated as though it
were a marketing choice. ADR-0114 makes Enterprise a private artifact and
ADR-0119 makes **possession of that artifact the gate on capability**, so a
trial means handing someone the image. Self-serve therefore means publishing the
proprietary artifact to anyone who completes a form, which retires the primary
gate permanently. Time-boxed registry credentials do not save it: once pulled,
the image is theirs.

ADR-0121 already supplies the mechanism — an offline token with an expiry and
thirty days of grace — so the trial needs no new machinery, only a shape.

## Decision

**The Enterprise trial is sales-gated, full-featured, thirty days, and extended
by reissuing a key. A trial key is `edition: trial` in the field ADR-0119
already defined, and it expires into ADR-0120's single *unlicensed* state like
any other key.**

Sales-gated, because the gate that matters is who receives the image. The
survey is unanimous for self-hosted: Spotify Portal routes to an account
executive and states free trials are unavailable through its Marketplace
listing; Grafana, GitLab Ultimate and self-hosted Kong are all contact-us.
Kong's self-serve thirty-day trial is Konnect — SaaS, where the vendor keeps a
live hand on the switch. Nodqora Enterprise is self-hosted and
air-gapped-capable; there is no switch.

Full-featured, because a trial with features removed does not demonstrate the
thing being sold, and the paid half is already the half that does not demo in
five minutes.

Extended by reissuing, because keys are minted per sale anyway. A fixed thirty
days that cannot move just means the trial dies in the customer's
change-approval queue.

Rejected, and why:

- **A self-serve download.** It is what an engineering audience wants and it
  ends capability gating on the day it ships.
- **A second, harder expiry behaviour for trials**, so an expired trial stops
  rather than freezing. It contradicts ADR-0120's "missing, malformed and
  expired are one state", and the tidy version of it — degrading to Community —
  was already rejected there for failing open on view-scoping, which is no less
  true of a trial someone is running against production.
- **A feature-limited trial**, which would gate the journal or monitoring behind
  a purchase and leave the trialist evaluating Community.

## Consequences

- **A trialist can configure once and keep the result forever.** ADR-0120 keeps
  everything already configured alive, so scopes, monitoring rules and retention
  set up during thirty days keep running after expiry — frozen and unchangeable,
  with the journal still writing. ADR-0119 half-saw this; a trial is the case
  where the lapsed install was never a customer. **Sales-gating bounds this by
  knowing who holds the image; nothing in the code does.**
- **Every trial costs a human.** There is no volume path to evaluation and no
  self-qualification, which suits a product with no customers and will not suit
  one with many.
- **Trial and paid keys differ only in a recorded claim**, so no code path is
  specific to trials and none can be got wrong.
- **Revisit trigger.** Trial requests arriving faster than they can be handled
  by hand, or a pattern of configure-then-lapse across more than one trialist.
  The first is an argument for self-serve and forces ADR-0119's gate to be
  rethought; the second reopens ADR-0120.
