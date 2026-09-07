# ADR-0139: Support covers the latest release, and the repository keeps one line of development

- **Status**: Accepted
- **Date**: 2026-09-07
- **Ticket**: [Support, SLA and what Community gets](https://github.com/fredskor/nodqora/issues/49)

## Context

This is the support decision that reaches into the repository, and the survey
named it as the cost that killed a serious vendor's self-hosted SKU:

> Every customer on a self-hosted edition runs a different version, support is
> harder, and the licence-key machinery has to be built and maintained — a cost
> at least one serious vendor concluded was not worth paying.

Grafana's bundle explicitly includes *"long-term support of a deployed
version"*. It is the thing self-hosted buyers ask for, and it is the thing that
multiplies a maintainer's codebase: one release branch per supported version,
each with its own build, its own test run, and a decision on every fix about
whether it goes back.

## Decision

**Support covers the latest released version. There is no long-term support, no
backports, and no release branches: this repository keeps one line of
development.** A customer on an older version gets *"upgrade, and tell us if it
persists"*, and that is the ADR-0136 first response.

Two prior decisions make this a fair ask rather than a brush-off, and it would
not be fair without them. **ADR-0114 already made upgrading a redeploy, not a
key** — no licence dance, no migration purchase, no re-entitlement; the customer
pulls a new image. And **ADR-0123's annual subscription entitles the customer to
every version released during the term**, so "upgrade" never means "buy". The
policy is only harsh where upgrading is expensive, and this architecture spent
real design effort making it cheap.

The repository consequence is the part that belongs in an ADR rather than in
`docs/editions.md`: **no branch in this repository exists to carry a fix to an
older version.** A fix lands on the main line and ships in the next release. If
a release branch is ever created, this ADR is what it contradicts.

Rejected, and why:

- **Latest plus the previous minor.** A narrow, bounded window covering the
  customer one cycle behind. It is the smallest possible backport obligation and
  it is still a release branch, a second build, and a judgement call on every
  fix — the whole cost, at a fraction of the benefit.
- **A named LTS release per year with a stated support window.** Matches
  Grafana, is what enterprise procurement expects, and is the right answer if
  air-gapped regulated buyers turn out to be the market. It is a second codebase
  in exchange for a market with no evidence behind it yet.

## Consequences

- **This lands hardest on exactly the buyer Enterprise is for.** ADR-0125
  already acknowledges trials dying in change-approval queues; an air-gapped
  bank with a six-week change window cannot upgrade in order to receive a first
  response, and being told to will feel like a shell. That buyer is precisely
  who self-hosted Enterprise exists to serve.
- **One codebase, permanently.** The maintainer never chooses between shipping
  the product and servicing an old release — which is what protects the thing
  §77 says must stay worth using.
- **A regression in a new release has no escape hatch.** With no previous
  supported version, "roll back and wait" is the customer's own decision taken
  without support, which raises the stakes on every release and argues for the
  founding CI this map has ruled out of scope.
- **Revisit trigger.** A customer who cannot upgrade within the response window
  and whose contract is worth the branch — or a regulated segment appearing more
  than once. Either is evidence for a named LTS, and it reopens this ADR alone;
  nothing else in the support set depends on it.
