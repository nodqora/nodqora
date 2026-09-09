# ADR-0158: The repository moves to an organisation and goes public with its record intact

- **Status**: Accepted
- **Date**: 2026-09-09
- **Ticket**: [Does the repository go public, and when](https://github.com/fredskor/nodqora/issues/84)
- **Amends**: [ADR-0150](0150-the-community-distribution-is-one-public-image-and-a-compose-file.md), [ADR-0155](0155-the-release-is-one-gradle-command-and-the-tag-is-the-last-act.md)

## Context

[ADR-0128](0128-community-capabilities-move-one-way-only.md) promised Community
stays Apache-2.0 and self-hostable, and the destination of the install map is a
release *a stranger* can run. Rehearsing that release exposed a gap no ADR had
named: the **repository itself** was private, so two of the four documented
steps 404 for exactly that stranger.

- `docs/install.md` opens with `curl -LO .../releases/latest/download/compose.yaml`.
  Release assets on a private repository are not anonymously downloadable, so the
  first command fails before Docker is involved.
- [ADR-0156](0156-the-unconfigured-install-has-a-first-run-screen-that-replaces-the-shell.md)'s
  first-run screen pins a `blob/` URL, which is the entire route from an empty
  install to a configured one.

Making the *package* public reaches neither. So the repository goes public. Two
questions came with that, and both are settled here.

**Where it lives.** A personal account is a weak home for a product with a
commercial edition: it reads as a side project, and adding a collaborator means
sharing a personal namespace. The coupling to the old path turned out to be
**eight lines** — `build.gradle.kts`, `compose.yaml`, `README.md`,
`docs/install.md` (×4) and `frontend/src/firstrun/unconfigured.ts`. Every ADR
also names it, but only in a `**Ticket**:` link, and those redirect. Eight lines
now, against a permanently wrong image path later: GHCR coordinates do not
redirect the way git URLs do, and nothing has been tagged or installed yet.

**What becomes readable.** 21 of 157 ADRs concern the Community/Enterprise line,
and the issue tracker holds a monetization map, a pricing ticket and a
license-key ticket. GitHub has no way to hide individual issues — a public
repository has a public tracker.

## Decision

**The repository moves to a `nodqora` organisation and becomes public**, and the
image coordinate becomes **`ghcr.io/nodqora/nodqora`**, superseding the
`ghcr.io/fredskor/nodqora` that ADR-0150 fixed.

**The ADR record stays public and whole.** Splitting it was considered and
rejected: `ADR-0151's seam` is written *in the Dockerfile*, `docs/install.md`
links ADR blob URLs, and CLAUDE.md makes `CONTEXT.md` plus `docs/adr/` the
documented layout. A code-only repository would cite, in almost every file,
documents nobody could read. The tiering ADRs are *policy* — that there is no
free Enterprise tier, that capabilities move one way — and policy is worth more
read than hidden.

**Two things move out**, to a private planning repository:

- `nodqora-product-plan.md`, 3,182 lines of positioning and roadmap.
- The pricing and license-key-mechanism issues, whose value to a competitor is
  higher than their value to a reader of the code.

## Consequences

- **ADR-0155's two private-repository premises no longer hold.** Free `arm64`
  runners become available, and a package created from a public repository does
  not inherit private visibility. The *decision* to cut by hand stands on its
  other legs — `FROM --platform=$BUILDPLATFORM` made multi-arch cost what
  single-arch costs, and the release machine is already tooled — but the reasons
  quoted from repository visibility are now stale, and act 4's predicted
  first-release failure should simply stop happening.
- **Act 4 is not weakened by that.** It verifies rather than assumes, and
  inheritance is a default, not a guarantee. The check stays exactly as it is.
- **`0.1.0` is spent and stays spent.** Its coordinates are published and
  immutable under ADR-0143, and its image sits at the old path. The first tag is
  `0.1.1`, which is what ADR-0155 meant by the first release paying for the
  route.
- **The plan remains in git history.** Moving the file out does not unpublish
  what was already committed; anyone can read it with `git log`. This is accepted
  rather than solved — rewriting history to remove it would cost the provenance
  that makes 158 ADRs worth having, for a document whose value decays with every
  release.
- **Transferring the two issues renumbers them**, breaking the `**Ticket**:`
  links in the ADRs that cite them. That is the price of the tracker being
  all-or-nothing, and it is paid on two issues rather than on the whole record.
- **Old URLs redirect, and the image path does not.** The git remote, every ADR
  ticket link and every issue reference survive the move. `ghcr.io/fredskor/nodqora`
  does not become `ghcr.io/nodqora/nodqora`; it is simply abandoned, which costs
  nothing because no one has pulled it.
