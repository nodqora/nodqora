# ADR-0144: Releases are pulled by a consumer, never pushed by a schedule

- **Status**: Accepted
- **Date**: 2026-09-07
- **Ticket**: [Artifact publishing for core, plugin-api and the four plugins](https://github.com/fredskor/nodqora/issues/52)

## Context

Cadence looks like a build question and is a support question.
[ADR-0139](0139-support-covers-the-latest-release-and-the-repo-keeps-one-line-of-development.md)
supports only the latest release, so **every release silently moves every
existing install out of support**. How often releases happen therefore decides
how often a customer is told to upgrade before their question is answered.

[ADR-0136](0136-the-commitment-is-one-business-day-to-first-response.md) has
already had the underlying argument once, refusing a severity ladder because "a
severity ladder is a thing that can be failed when the failure mode is sleep",
and committing only to the one quantity a single person controls.

## Decision

**There is no release cadence, and none is published.** A release happens when a
consumer pulls one: the Enterprise repository needs to ship against something, or
a supported customer needs a fix. `docs/editions.md` states no release frequency,
for the same reason it states no fix time.

Rejected, and why:

- **A calendar** — monthly or quarterly. Predictable for procurement, and it is
  ADR-0136's refused object one level up: a recurring public promise with a
  single human bottleneck and no CI behind it. It also forces manual release work
  in months containing nothing.
- **Releasing on every merge to `main`.** Attractive because it keeps "the latest
  release" within one commit of the truth, which is the smallest possible support
  gap. Refused because ADR-0139 makes releasing an obligation-creating act:
  auto-releasing every merge obsoletes every customer's install on every merge,
  and with no CI each of those releases is unverified.

## Consequences

- **On-demand releasing interacts badly with ADR-0139 in a way neither decision
  can see alone.** If releases are rare, "upgrade to the latest" becomes a bigger
  ask each time it is said — and it is said to exactly the buyer ADR-0139 already
  admitted it lands hardest on, the one with a six-week change window. The
  mitigation is not a promise and is entirely in the maintainer's hands: a
  supported customer needing a fix *is* a pull, so cut the release from a small
  diff rather than letting the gap grow until it is a migration.
- **Nothing forces a release**, so the registry can fall arbitrarily far behind
  `main` with no signal. Under
  [ADR-0143](0143-published-versions-are-immutable-and-the-inner-loop-has-no-artifact.md)
  this costs Enterprise development nothing — the composite build does not read
  the registry — which is what makes the drift survivable and also what makes it
  easy to not notice.
- **A customer cannot plan around release timing**, and no procurement question
  about roadmap cadence has an answer. That is honest rather than evasive: the
  answer would be a number that could be missed.
- **Revisit trigger.** A customer contract that requires a stated release
  schedule, or a second maintainer — the bottleneck ADR-0136's reasoning rests on
  is one person, and it stops applying the moment there are two.
