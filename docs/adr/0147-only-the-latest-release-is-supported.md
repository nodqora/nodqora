# ADR-0147: Only the latest release is supported, and nothing is deprecated

- **Status**: Accepted
- **Date**: 2026-09-07
- **Ticket**: [Artifact publishing for core, plugin-api and the four plugins](https://github.com/fredskor/nodqora/issues/52)

## Context

[ADR-0142](0142-one-version-for-six-artifacts.md) settles what a version bump
means. It does not settle *for how long* — the ticket's remaining question — and
that turns on a coupling neither
[ADR-0139](0139-support-covers-the-latest-release-and-the-repo-keeps-one-line-of-development.md)
nor [ADR-0114](0114-the-enterprise-edition-is-a-second-assembly.md) can see on
its own.

ADR-0139 promises support for "the latest released version". An Enterprise
customer runs an **Enterprise image**, built at some point against some Community
version. If this repository is on `0.6.0` and that image embeds `0.4.0`, it is
not obvious whether that customer is on the latest release.

## Decision

**"The latest release" means the latest release of the artifact the customer
deployed.** For an Enterprise customer that is the Enterprise image; the
Community version inside it is an implementation detail of that image. Two
assemblies, two release lines. Enterprise tracks this repository's line at its
own pace, and its lag is its own risk.

**There is no compatibility window and no deprecation cycle.** Nothing is marked
deprecated in one release and removed in the next; no published version stays
supported for a stated period. This repository's promise is exactly: *the latest
release is the only supported version, and a version is compatible with nothing
but itself.*

Old coordinates stay **resolvable** forever — ADR-0143's immutability guarantees
a build pinned to `0.4.0` keeps building — but resolvable is not supported, and
nothing is ever fixed there.

Under `0.x` with a single consumer who is also the author, a deprecation cycle is
ceremony performed for an audience of one.

Rejected, and why:

- **Requiring Enterprise to rebase onto each Community release** so its customers
  stay within ADR-0139's promise. It gives one line of truth, and it turns this
  repository's release timing into a schedule imposed on another repository —
  reintroducing one level up the calendar
  [ADR-0144](0144-releases-are-pulled-by-a-consumer-never-pushed-by-a-schedule.md)
  refused, and doing it to a repo this map cannot see.

## Consequences

- **An Enterprise customer's support entitlement is bounded by a rebase cadence
  in a repository outside this map.** A Community fix released today reaches a
  paying customer only when Enterprise rebases and cuts its own image. That
  follows from two assemblies rather than from this decision, but it is the kind
  of thing discovered during an incident if it is not written down first.
- **This is ADR-0114's recorded cost arriving where it is paid.** That ADR noted
  "two assemblies can drift; Enterprise owns keeping step; nothing here detects
  it." This says what the drift costs: support currency.
- **No deprecation cycle means Enterprise gets no warning before a signature
  moves** — only ADR-0142's minor bump, after the fact. Acceptable while the
  consumer and the author are the same person; it is among the first things that
  breaks when they are not.
- **Nothing here is customer-facing.** `docs/editions.md` gains no version-policy
  section, because ADR-0139 already states the customer-facing half and this ADR
  only says how it reads across two assemblies.
- **Revisit trigger.** A second consumer of these artifacts, or Enterprise
  passing into other hands. Either makes "no warning, no window" a cost paid by
  someone who did not choose it, and reopens this together with
  [ADR-0141](0141-publication-is-private-and-the-sdk-trigger-stays-unfired.md).
