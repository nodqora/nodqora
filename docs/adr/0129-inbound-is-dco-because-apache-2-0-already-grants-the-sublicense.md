# ADR-0129: Inbound is DCO, because Apache-2.0 already grants the sublicense

- **Status**: Accepted
- **Date**: 2026-09-05
- **Ticket**: [Add LICENSE, NOTICE and the contribution agreement to the repo](https://github.com/fredskor/nodqora/issues/46)

## Context

This repository has had no `LICENSE`, no `NOTICE` and no `CONTRIBUTING.md`
since it started, which means every commit to date is ambiguously licensed —
published source with no terms attached. The map fixed Apache-2.0 for
everything here while charting; nothing had yet written it down.

The one consideration the ticket named is that the maintainer must keep the
right to ship the same code under the proprietary Enterprise licence. The
instinct is that this requires a Contributor Licence Agreement. It does not,
and the reason is worth stating precisely because it is the load-bearing fact
in this decision: **Apache-2.0 §2 grants a "perpetual, worldwide,
non-exclusive, no-charge, royalty-free, irrevocable copyright license to
reproduce, prepare Derivative Works of, publicly display, publicly perform,
sublicense, and distribute."** The right to *sublicense* is the right a CLA
would otherwise be bought for, and inbound Apache-2.0 already carries it.

[The survey](https://github.com/fredskor/nodqora/issues/41) confirms this
empirically rather than only textually. Backstage runs DCO-only with no CLA;
DataHub and OpenMetadata have neither, relying on bare Apache-2.0 §5
inbound=outbound — and Collate relicensed OpenMetadata's entire UI and
connector trees on that basis alone. A CLA is strictly load-bearing in exactly
two cases, both already ruled out by this map: going copyleft, and putting
proprietary source inside this repository.

[ADR-0114](0114-the-enterprise-edition-is-a-second-assembly.md) closes the
third case the survey named — a superset build absorbing community
contributions into proprietary files. Enterprise is a second Spring Boot
assembly consuming `nodqora-core` as a published artifact, so no outside
contribution ever enters the proprietary tree.

## Decision

**Inbound is DCO sign-off. There is no CLA and no copyright assignment.
Contributors keep their copyright, and the right to ship their code in
Enterprise comes from Apache-2.0 §2.**

Four parts:

1. **`LICENSE`** is the unmodified Apache-2.0 text at the repository root,
   with the appendix copyright filled in.
2. **The copyright holder is "The Nodqora Authors."** Because there is no CLA,
   the maintainer never takes ownership of a contributed patch; a line naming
   one person over the whole tree would become false on the first outside
   contribution. The collective form stays true as contributors arrive and
   needs no edit if a company is later formed to sign Enterprise contracts.
   The line is attribution only — it is not what conveys the sublicense right.
3. **Every commit carries `Signed-off-by`**, certifying the Developer
   Certificate of Origin 1.1: the contributor wrote the patch or has the right
   to submit it under Apache-2.0. It costs `git commit -s` and buys a
   per-commit provenance record for code that will later be sublicensed.
4. **`CONTRIBUTING.md` discloses the open-core arrangement** — that a
   proprietary Enterprise edition exists, that a contribution may be used in
   it, that no file here is ever proprietary, and that Community placements
   move free-ward only
   ([ADR-0128](0128-community-capabilities-move-one-way-only.md)). The DCO
   certifies right-to-submit and says nothing whatever about commercial
   intent, so if contributors are to know, this page has to say it.

Rejected, and why:

- **A CLA.** Cheap now and expensive later, which is a real option-value
  argument — Airbyte introduced one at the moment of its first relicensing.
  But it buys only the two rights this map has foreclosed, it deters the
  drive-by contributor, and
  [ADR-0123](0123-the-unit-is-the-deployment-and-nothing-observed-is-counted.md)
  through ADR-0128 make the whole monetization model run on adoption. Paying
  a trust cost for a right already held is a bad trade.
- **Nothing at all — bare Apache-2.0 §5.** Proven sufficient by DataHub and
  OpenMetadata, and the lowest-friction option available. Declined because the
  sign-off costs a single flag and leaves an evidentiary record where §5
  leaves an inference.
- **Naming an individual as copyright holder.** True today and false on the
  first merged patch from anyone else.

## Consequences

- **The repository is licensed for the first time.** Everything published
  before this commit was source without terms; from here the terms are
  explicit and the ambiguity is closed prospectively.
- **The sublicense right is symmetric, and this is the accepted cost.**
  Apache-2.0 §2 grants it to *every* recipient, not to the maintainer
  specially — so a competitor may take Community proprietary on identical
  terms. A CLA would not have changed this; only a copyleft licence would, and
  the map fixed Apache-2.0. ADR-0128 makes this a deliberate bet on adoption
  rather than an oversight.
- **There is no retroactive relicensing.** Every version shipped stays
  forkable under Apache-2.0 forever, exactly as OpenMetadata's 1.5.0 ingestion
  tree still is. This is the one door DCO-without-a-CLA closes, and it closes
  it permanently: the decision to revisit could change future terms and could
  never reach a published tag.
- **Donating to a foundation is now visibly foreclosed**, which was already
  true and is now written down. The survey found the LF AI & Data lifecycle
  requires asset transfer and an OSI-approved licence, which jointly forbid a
  proprietary edition — and is why Astronomer's commercial value had to land
  in a separate product talking to Marquez over a wire protocol.
- **Nothing verifies sign-off.** This repository has no CI
  ([ADR-0130](0130-the-licence-is-stated-once-per-file-and-once-per-repo.md)
  carries the same cost for headers), so the DCO is documented and
  unenforced until CI exists.
- **Revisit trigger.** A decision to relicense this repository under copyleft,
  or to place proprietary source inside it — either would make a CLA
  load-bearing, and neither can be applied to code already contributed under
  this arrangement, so a CLA would cover new contributions only.
