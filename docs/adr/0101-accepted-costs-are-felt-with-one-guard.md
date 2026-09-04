# ADR-0101: Accepted costs are left to be felt, with exactly one guard

- **Status**: Accepted
- **Date**: 2026-09-04
- **Ticket**: [Implementation slice ordering and handoff](https://github.com/fredskor/nodqora/issues/18)
- **Amends**: ADR-0074

## Context

The map records a number of costs as **accepted rather than solved**. An
implementing session meeting one of them for the first time will be tempted to
add a small defence, and a small defence added on the way in is how an accepted
cost quietly becomes a half-built feature that nobody decided to build.

Which costs get a guard should therefore be a decision, not an oversight.

## Decision

**The costs sort by whether a guard was already considered, and almost all of
them were.** Those are closed and are not reopened at first contact:

| cost | guard considered? |
|---|---|
| ADR-0064 — `yaml` can manufacture drift | yes; both candidates fire on the fixture's three *intended* drifted nodes to catch one hypothetical typo. Rejected. |
| ADR-0076 — every client re-downloads the graph every five minutes | yes; two ETag variants, one disqualified sharply. Rejected, with ADR-0059's fold-generation counter named as the successor. |
| ADR-0080 — an empty graph for up to five minutes on a `payload_version` bump | yes; in-place migration and tolerant deserialization both rejected, and the user-facing half was later answered by ADR-0087's three empty states. |
| ADR-0067 — `payments-prod` matches the whole namespace through the segment rule | yes; unfixable without the frontend knowing plugin shapes, which ADR-0015 forbids. |
| ADR-0091 — an unannotated Deployment has no type at all | yes; a kind-derived default was the alternative and lost. |
| ADR-0082/ADR-0083 — two encodings nobody sees before the day they matter | yes; the always-on coverage channel lost structurally, not aesthetically. |
| ADR-0079 — a stub node's provenance is inflated | yes; the ticket's closest call, paid for deliberately. |
| **ADR-0074 — an environment-key typo cascades that environment's store away** | **no.** |

**One guard is added, and it is a log line.** At startup reconciliation, before
deleting an `environment` or `plugin` config-mirror row, the application logs a
WARN naming the key and the row counts about to cascade.

ADR-0074 weighed its cascade against *"the same deletion written out by hand
across eight tables with a chance of missing one"* and took the cascade. It never
weighed it against **saying so**. And it is the only cost on the map that is all
three of:

- **destructive** — it removes an environment's entire store;
- **triggered by an ordinary text edit** rather than a deliberate developer act,
  which is what ADR-0080's version bump is;
- **silent** — an empty canvas is visible and a re-download is measurable, but a
  cascade that self-heals within one cadence leaves no trace that it happened.

The guard builds no mechanism, adds no configuration, introduces no confirmation
prompt, and does not touch the invariant ADR-0074 exists to create. It converts a
silent destructive act into a loud one and stops there.

## Consequences

- **Everything else is felt in production before it is fixed.** That is the
  intent: each of those costs has a written argument for why the obvious defence
  is worse, and none of them should be re-litigated by a session that has read
  one ADR and not the argument behind it. ADR-0100 puts the relevant costs on
  each slice issue as *"must not try to fix"* so the session meets the ruling
  before it meets the symptom.
- **The WARN fires on legitimate removals too**, and there is no way to tell them
  apart — that is the same indistinguishability ADR-0074 accepted, now audible.
  It is a log line rather than an alert precisely because it cannot know.
- **ADR-0042's GET-only ArchUnit rule is not on this list.** It is already
  decided, so it is a slice 4 done-when rather than a guard — as ADR-0015's
  boundary test is a slice 2 done-when (ADR-0098).
- **A cost discovered during implementation is not covered here.** This ADR rules
  on the costs the map wrote down. A newly discovered one is an ADR-0102
  question, and which way it goes depends on whether it invalidates a reason.
