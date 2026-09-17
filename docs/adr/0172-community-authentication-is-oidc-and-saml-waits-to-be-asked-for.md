# ADR-0172: Community authentication is OIDC, and SAML waits to be asked for

- **Status**: Accepted
- **Date**: 2026-09-17
- **Ticket**: [OIDC, SAML, or both, and which identity providers self-hosters actually run](https://github.com/nodqora/nodqora/issues/139)
- **Amends**: [ADR-0109](0109-authentication-is-community-and-view-scoping-is-enterprise.md), [ADR-0126](0126-there-is-no-free-enterprise-tier-at-any-threshold.md) and [ADR-0169](0169-enterprise-opens-on-a-floor-and-evidence-and-the-clock-forces-a-decision.md): *which protocol* Community authenticates with. Where authentication sits (Community), what sits on top of it (Enterprise) and the floor's two items all stand.

## Context

The record disagreed with itself. ADR-0169's floor and the floor row in
`docs/editions.md` say Community authentication is **OIDC or SAML**. ADR-0109's
*Decision*, ADR-0126's *Consequences* and the ledger row say **OIDC and SAML**.
The "or" set the gate for opening Enterprise; the "and" set what Community
promises. Shipping one protocol is a materially smaller release than shipping
two, so the disagreement had to close before any sign-in code was written.

[The research note](../research/oidc-and-saml-for-community-sign-in.md) read the
primary sources and found:

- **No common identity provider is SAML-only.** Keycloak, authentik, Zitadel,
  Entra ID, Okta, Google, JumpCloud, PingOne, PingFederate and OneLogin all offer
  OIDC to an application; Dex offers only OIDC. SAML is the sole option in
  specific deployments, not products: Google Workspace's Admin console (Google's
  OIDC is configured in the Cloud console), AD FS on Windows Server 2012 R2 (out
  of paid security updates on 14 October 2026), and research federations such as
  InCommon.
- **A SAML-only provider can be brokered.** Keycloak, authentik and Zitadel take
  SAML in and serve OIDC out. Dex can too, but its own README calls its SAML
  connector unmaintained and likely vulnerable to authentication bypass.
- **The category ships OIDC.** DataHub (even its paid Cloud), Argo CD, Kafbat UI,
  AKHQ and Redpanda Console have no SAML; Grafana's is paid; Backstage dropped
  its SAML provider in 2025; only OpenMetadata ships SAML free, added after its
  OIDC providers.
- **SAML costs more than the protocol in Spring Security.** OpenSAML is not on
  Maven Central, so the build takes the Shibboleth repository as a second trust
  root, into a build that already loses fetches on some networks (ADR-0157).
  Single logout and encrypted assertions need keys the operator must generate
  and rotate, and Spring Security 6.4 still carries end-of-life OpenSAML 4. OIDC
  needs an `issuer-uri`, a client id and a client secret, all from Central.

## Decision

**Community authentication is OIDC against the customer's own identity
provider. SAML is not built until an outside organisation asks for it, and if it
is ever built, it is Community.**

- **The floor's second item is OIDC.** ADR-0169's "OIDC or SAML" was already met
  by OIDC alone; saying OIDC makes the gate name the thing that will ship.
- **Community's promise is OIDC.** ADR-0109, ADR-0126 and the ledger stop
  promising an unbuilt SAML.
- **A SAML-only estate brokers.** The documented answer is an OIDC-speaking
  broker in front of it — the same currency, a deployment, that ADR-0109 already
  charges Community for isolation.

This does not move a capability out of Community. SAML never shipped, and
ADR-0128 leaves unshipped capabilities unconstrained; placing it in Community if
built keeps the direction ADR-0128 allows.

Rejected, and why:

- **Both protocols.** Doubles the sign-in surface for a buyer the survey could
  not find, and brings a non-Central repository, operator-held signing keys and
  an OpenSAML version choice with it.
- **SAML only.** Every identity provider checked speaks OIDC; the reverse is not
  true of Dex, and it is the harder protocol to build and run.
- **Leaving the "or" in the floor and the "and" in the ledger.** The record would
  keep promising something nobody intends to build before the gate is read.

## Consequences

- **The sign-in build is `oauth2Login()` on Maven Central alone.** The next
  tickets on the map — where a session lives, the configuration grammar, the
  demo's identity provider — assume one protocol.
- **A Google Workspace shop configures Nodqora in the Google Cloud console**, not
  the Workspace Admin console, and limits it to the organisation with an
  *Internal* audience. `docs/install.md` says so when sign-in ships.
- **A SAML-only customer runs a broker.** That is a real cost and it is stated,
  not hidden: one more deployment in front of Nodqora.
- **Whether any adopter's checklist demands SAML by name, rather than SSO, is not
  known.** The revisit trigger exists for that.
- **Revisit trigger.** An outside organisation that cannot sign in because its
  identity provider speaks only SAML, and for whom brokering through an OIDC
  provider is not an option. SAML is then built, in Community.
