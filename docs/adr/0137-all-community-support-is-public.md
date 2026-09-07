# ADR-0137: All Community support is public

- **Status**: Accepted
- **Date**: 2026-09-07
- **Ticket**: [Support, SLA and what Community gets](https://github.com/fredskor/nodqora/issues/49)

## Context

The ticket asked what Community gets — *"issues only, no SLA — and whether that
promise is written down anywhere"* — and treated the SLA as the substance. It is
not. A response commitment Community does not have is easy to describe. The
load-bearing question is the **channel**.

ADR-0127 established this map's grain for what gets published: the unit and the
trial terms go on `docs/editions.md` even while the price is withheld,
specifically to recover the evaluator who will not take a call. Community's
support terms fit that pattern, and they are also what makes ADR-0136's
commitment legible — a buyer can only see what they are buying against a stated
baseline.

## Decision

**All Community support is public. Public issues on this repository, no response
commitment, no private support channel, and no support email. This is written
down on `docs/editions.md` and pointed at from `README.md`.**

The clause that does the work is *public*, not *no commitment*.

The failure mode for a single maintainer has never been the issue tracker. It is
the direct message, the "quick question" by email, the private channel given to
someone early and enthusiastic. Those become unpriced support relationships with
no term, no expiry and no way to decline without souring the relationship, and
they arrive one at a time so that no single one ever looks like a decision.
**ADR-0135 bundles support into the Enterprise subscription, which makes the
private channel the thing being sold** — so giving it away in Community is
giving away the product.

Public is also the only shape that compounds. At one maintainer, an answer
written in an issue is worth many times the same answer written in a DM, because
the next person finds it.

**Nothing is published about how quickly Community issues are actually
answered.** In practice a pre-adoption maintainer answers every one within
hours, because each issue is a user. That gap between the stated commitment and
the observed behaviour is correct and is not closed by promising the behaviour.

Rejected, and why:

- **Public issues, best effort, private channels not ruled out.** It keeps the
  door open for a design partner and it drops the clause that does the work.
  Every private channel that ever existed was opened for a good reason.
- **Saying nothing at all**, as most open-source projects do. It commits to
  nothing, and it leaves ADR-0136's commitment with no baseline to contrast
  against — a buyer cannot value one business day without knowing what the free
  edition gets.

## Consequences

- **The private asker is excluded, and some of them are lost.** A user will not
  post their own topology, internal service names, or an embarrassing
  misconfiguration in public. They sanitize the question, or they do not ask.
  A fraction of those quietly stop using the product, which is §77's risk
  exactly, and this ADR accepts it in exchange for the only channel one person
  can serve.
- **It is a promise about a channel, not about an answer**, so it cannot be
  breached by being busy. That is deliberate: an unbreakable promise is worth
  more here than a generous one.
- **Community and Enterprise now differ in a way that has nothing to do with
  capability**, which is the first such difference in this map. Every prior line
  was drawn by ADR-0107's rules over features; this one is drawn over access to
  a person, and ADR-0128's one-way promise does not constrain it because support
  is a service rather than a shipped capability.
- **Revisit trigger.** Public issue volume that cannot be read, or a design
  partner relationship valuable enough to justify a private channel. The second
  is the honest one: if it happens, it should be a contract under ADR-0138
  rather than an exception made quietly.
