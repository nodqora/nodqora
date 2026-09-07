# ADR-0109: Authentication is Community; group synchronisation and view-scoping are Enterprise

- **Status**: Accepted
- **Date**: 2026-09-05
- **Ticket**: [The Community/Enterprise feature ledger](https://github.com/fredskor/nodqora/issues/42)

## Context

Product plan §43 lists **SSO/SAML** first among possible Enterprise features.
[The survey](https://github.com/fredskor/nodqora/issues/41) found that this is
the convention of a category Nodqora is not in. Three of the four platform tools
gate SSO — but **every lineage and catalog product ships it free**: OpenMetadata
ships eight providers in its Apache-2.0 tree, DataHub ships OIDC, and Backstage
does not gate it either. Nodqora is closer in shape to those than to Grafana.

The survey also found the cut is finer than the slogan wherever SSO *is* gated:
Grafana and GitLab both ship bind authentication free and put directory and
**group synchronisation** behind the line. That is a real seam rather than a
compromise — authenticating is a thing one team needs, and mapping an identity
provider's groups onto roles is a thing only an organization has.

RBAC is the harder half, and the repository settles a fact that changes the
question. **The API is entirely read-only — no writes anywhere** (`CONTEXT.md`,
the read API section; §5.6 in the plan). DataHub's transferable finding was that
*view*-level access control is paid while *edit*-level control is free. Here the
free half does not exist: there is no edit surface to leave open. Configuration
is deployment-level — files and environment variables — not a user action.

So authorization in Nodqora is view-scoping and nothing else, and view-scoping is
squarely what the buyer rule assigns to Enterprise: a team wants everyone to see
the whole map, and an organization needs team A not to see team B's environment.
Placing it there puts *all* authorization behind the line, which is worth stating
plainly rather than discovering.

## Decision

**Authentication is Community. Everything built on top of identity is
Enterprise.**

- **Community**: OIDC and SAML bind authentication against the customer's own
  identity provider. Anyone who authenticates sees every environment in that
  install.
- **Enterprise**: group synchronisation, roles, and per-environment,
  per-node-type, per-owner and per-team visibility scoping within one install.

**Community's answer to isolation is a second install**, not a role. Running one
Nodqora per environment or per team is already possible — the environment roster
is configuration — and it costs a deployment rather than a licence. Enterprise's
proposition is not *"isolation is possible"* but *"isolation without running N
deployments"*.

## Consequences

- **The loudest single check a prospective adopter runs is answered "free".**
  The survey named SSO as exactly that check, and answering it with a
  qualification — *"free, and group sync is paid"* — is a materially different
  first impression from answering it with a price.
- **This contradicts §43's first line, deliberately.** §43's ordering reflected
  the platform-tools convention rather than this category's, and §43 itself
  defers to adoption evidence.
- **All authorization is Enterprise, and the ledger says so without
  euphemism.** A Community install is a shared view. That is a real limitation
  and it is stated as one in `docs/editions.md` rather than described as a
  simplification.
- **Group synchronisation is worth nothing until roles exist**, so this ADR
  places two things that ship together. Until RBAC is built, the Enterprise half
  of this decision is empty — which means the SSO line in the ledger reads as
  entirely free for as long as that remains true, and the ledger must not
  advertise a paid capability that does not exist.
- **Cost: the free tier's isolation story consumes deployments.** A company with
  eight teams that wants eight views runs eight Nodqoras, eight databases and
  eight sets of credentials. That is a worse experience than roles and it is the
  intended shape of the upgrade pressure — but it also means a team that solves
  it with deployments may never feel the need to buy, and a self-hoster who
  already automates deployments feels this cost barely at all.
- **Revisit trigger.** A user-facing write path arriving (ADR-0108 places
  write-back as Enterprise, but a Community-side configuration UI would create an
  edit surface), or adoption evidence that view-scoping is the first thing
  single-team installs ask for — which would mean the buyer rule mis-read it as
  organizational.
