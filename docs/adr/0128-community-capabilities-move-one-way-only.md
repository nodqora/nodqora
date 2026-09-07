# ADR-0128: Community capabilities move one way only

- **Status**: Accepted
- **Date**: 2026-09-05
- **Ticket**: [Pricing unit and trial](https://github.com/fredskor/nodqora/issues/45)

## Context

This pricing model depends entirely on adoption: no meter, no expansion axis
(ADR-0123), a very fat free tier (ADR-0126), and a paid half the ledger itself
admits only sells to an installed base.

Against that, `docs/editions.md` opens with **"Provisional. Every placement
below names the evidence that would reopen it,"** and ADR-0107's revisit trigger
is *"installs active for more than six months with no Enterprise enquiry"* —
which reads plainly as a trigger to consider moving something *out* of
Community. A ledger where every free placement is reopenable in both directions
is one an adopter cannot rely on.

[The survey](https://github.com/fredskor/nodqora/issues/41) is blunt about this:
no vendor in it demoted a free feature to paid, everything that moved moved
sideways — *"but withdrawing the free way to run it is treated as fair game, and
lands on the user identically. That is the manoeuvre to watch for and, if you
want to be trusted, to promise against explicitly."* Airbyte's withdrawal of its
self-hosted Enterprise SKU is already on file in this map's Notes as the
evidence for going self-hosted-first.

## Decision

**No capability shipped in Community ever moves to Enterprise. Placements are
reopenable free-ward only.**

Three parts, published on `docs/editions.md`:

1. **Enterprise capabilities may move down; Community capabilities never move
   up.** The ledger's revisit triggers operate in one direction.
2. **New, unshipped capabilities are unconstrained.** The rules place them when
   they ship; nothing is promised about work that does not exist.
3. **The Community edition stays Apache-2.0 and self-hostable.** This closes
   Airbyte's manoeuvre — never demoting a feature while withdrawing the free way
   to run it.

This narrows ADR-0107's revisit trigger rather than removing it. Evidence of no
Enterprise enquiry now means *moving more into Enterprise is not the answer*:
the fix is finding what an organisation will actually pay for, which is a
product question and not a repricing.

Rejected, and why:

- **Keeping the ledger reopenable in both directions.** It preserves the escape
  hatch and it is worth less than it looks, because using it once would cost the
  trust the whole adoption-first model runs on.
- **Promising only against feature demotion**, leaving the deployment story
  free. That is precisely the manoeuvre the survey says lands identically on the
  user, and declining to close it would make the promise a technicality.
- **Extending the promise to unshipped work** — "anything we build in this area
  will be free." It would place capabilities before the rules can see them and
  make ADR-0108's standing rule unwritable.

## Consequences

- **This is irreversible by design.** If blast radius or one-shot comparison
  prove to be the thing everyone would have paid for, they cannot be taken back
  — only built beside. That constraint is what makes the promise worth making,
  and it is accepted with eyes open rather than discovered later.
- **`docs/editions.md`'s "provisional" framing is now qualified**, and every
  Community row on it is a commitment rather than a placement.
- **It raises the cost of a placement mistake to permanent**, which should make
  the ledger's future rows slower and better argued.
- **Community's Apache-2.0 licence is now a product promise, not only a licence
  choice**, which touches
  [this repository's licensing files](https://github.com/fredskor/nodqora/issues/46).
- **Revisit trigger.** None that this ADR permits. A promise with a revisit
  trigger is not a promise; breaking it is possible in the sense that any
  published commitment can be broken, and it would be a deliberate reversal
  announced as one, not a triggered amendment.
