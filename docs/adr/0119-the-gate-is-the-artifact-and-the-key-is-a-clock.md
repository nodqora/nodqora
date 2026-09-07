# ADR-0119: The gate is the artifact; the key is a clock

- **Status**: Accepted
- **Date**: 2026-09-05
- **Ticket**: [License-key and gating mechanism](https://github.com/fredskor/nodqora/issues/44)

## Context

ADR-0114 makes Enterprise a second Spring Boot assembly built in a private
repository, and ADR-0115 leaves no `if (licensed)` anywhere in core. Between
them, the gate an installation already passes is **possession of the image**.

[The open-core survey](https://github.com/fredskor/nodqora/issues/41) found two
gating shapes and they answer different threats. Possession-of-artifact
(OpenMetadata's inert `Limits` default, Cerbos, Temporal, Kong's separate
distribution) answers *may you run Enterprise at all*. An in-binary key check
(Airbyte, Grafana, Kong) answers *are you still paying*.

Cerbos and Temporal can stop at possession because their gate has a live half:
Hub refuses credentials. Nodqora Enterprise is self-hosted, potentially
air-gapped, and talks to no vendor service — **there is nothing to revoke.**
Without a key, a customer who stops paying runs the image they already pulled
forever, and a time-boxed trial has no mechanism at all, which
[the pricing unit ticket](https://github.com/fredskor/nodqora/issues/45) would
inherit as a fixed constraint rather than a choice.

## Decision

**Possession of the Enterprise artifact is the gate on capability. A signed
offline licence key is the gate on time, and it lives entirely inside the
Enterprise assembly.** Nothing in this repository verifies, parses, or knows
that a key exists.

The key carries customer identity, edition, issued-at, expiry, and an open
entitlements map — seat counts, node counts, whatever pricing eventually picks.
**Only expiry changes behaviour.** Every other claim is shown to the operator
and written to the audit stream; an overage is a conversation, not a block.

Rejected, and why:

- **No key at all**, which is the cheapest option and what the majority of the
  survey do. Refused because their possession gate has a live half Nodqora's
  does not, and because it would settle #45's trial question by default rather
  than by decision.
- **The Airbyte arrangement — a public verifier with a private signing key.**
  ADR-0115's ArchUnit rule forbids the identifier `licence` in core source
  outright, and an Apache-2.0 verifier carries no anti-circumvention term, so
  the public half would be freely deletable and load-bearing for nothing.
- **Enforced seat or node caps.** Enforcing a node cap means refusing to fold;
  enforcing a seat cap means refusing logins. Both are the data path ADR-0120
  keeps alive, and both would hand #45 a constraint — *pick something
  countable* — before that ticket has decided anything.

## Consequences

- **`docs/editions.md`'s "these deter rather than enforce" is now true of the
  mechanism as well as the lines.** The only thing the code refuses is a *fresh*
  Enterprise deployment running past expiry with its control path unfrozen.
- **The pricing unit keeps every option.** Any metric #45 chooses is expressible
  as a recorded claim, and nothing about the mechanism prefers a countable one.
  ADR-0112 and ADR-0118 had already foreclosed per-connector and
  technology-specific pricing; this narrows nothing further.
- **The Enterprise licence must forbid circumvention.** Every vendor in the
  survey that ships an in-binary check pairs it with a term against removing the
  check. The verifier is proprietary so the term is available — but it has to be
  written, and it is not among
  [this repository's licensing files](https://github.com/fredskor/nodqora/issues/46).
- **The key is worth less than it looks against a determined customer**, who
  holds the jar and can decompile it. It is a renewal prompt with cryptographic
  manners, and it should be sold and described as one rather than as a lock.
- **Revisit trigger.** A customer requiring one image for both editions —
  ADR-0114's own trigger. That forces a capability check back into core and
  turns the key from a clock into the gate, reopening this ADR and ADR-0115
  together.
