# OIDC and SAML for Community Sign-In

Research input for [Does the floor need OIDC, SAML, or both?](https://github.com/nodqora/nodqora/issues/139),
on the map [Community authentication](https://github.com/nodqora/nodqora/issues/138).
It settles a disagreement in the record before the first line of sign-in code is written.

The record says two different things. The "or" reading:

- [ADR-0169](../adr/0169-enterprise-opens-on-a-floor-and-evidence-and-the-clock-forces-a-decision.md),
  *The floor*, item 2: `**Community authentication** — OIDC or SAML against the user's own identity provider, shipped in a tagged release.`
- [`docs/editions.md`](../editions.md), *When Enterprise opens*, the floor row:
  `The Community release that closes Tier 1 with Prometheus, **and** Community authentication (OIDC or SAML) in a tagged release.`

The "and" reading:

- [ADR-0109](../adr/0109-authentication-is-community-and-view-scoping-is-enterprise.md),
  *Decision*: `**Community**: OIDC and SAML bind authentication against the customer's own identity provider.`
- [`docs/editions.md`](../editions.md), the ledger row:
  `**Authentication** — OIDC and SAML bind against your own identity provider`
- [ADR-0126](../adr/0126-there-is-no-free-enterprise-tier-at-any-threshold.md),
  *Consequences*, repeats it: `OIDC and SAML authentication, and complete audit capture.`

ADR-0169 sets the gate for opening Enterprise, and either protocol gets through it.
ADR-0109, ADR-0126 and the ledger say what Community *promises*, and they promise both.
So the "or" is about the gate and the "and" is about the product. The question is which
promise is true.

All load-bearing claims below were fetched from the cited primary source: vendor
documentation, upstream repositories and changelogs, Maven Central and Shibboleth
repository listings, and federation policy documents. Where a claim could not be checked
against a primary source it says **not verified**. Nothing here rests on a comparison blog
or a listicle. Unless stated otherwise, everything is **as published, September 2026**.
Unlike the other notes in this directory, this ticket asks for a decision, so the note
ends with a **Recommendation**.

---

## Summary

**None of the common identity providers is SAML-only.** Keycloak, authentik, Zitadel,
Microsoft Entra ID, Okta, Google, JumpCloud, PingOne, PingFederate and OneLogin all offer
OIDC to an application they sign users into. Dex offers *only* OIDC to applications. The
places where SAML is the only option are specific deployments, not products:

- **Google Workspace's Admin console.** Its custom apps are SAML. Google's OIDC is
  configured in the Google Cloud console instead.
- **AD FS on Windows Server 2012 R2.** OIDC arrived in AD FS 2016, and 2012 R2's last paid
  Extended Security Update year ends on 14 October 2026.
- **Academic federations.** InCommon registers SAML metadata and nothing else.

**The adjacent tools overwhelmingly ship OIDC first, and most never ship SAML free.**

- **OIDC only.** DataHub (open source *and* Cloud), Argo CD (except through Dex), Kafbat
  UI, AKHQ and Redpanda Console.
- **SAML, but paid.** Grafana, whose SAML has been Enterprise-only since it arrived in
  6.3 (2019).
- **SAML added later, free.** OpenMetadata, in 1.0 (April 2023), years after its OIDC
  providers.
- **SAML shipped early, then dropped.** Backstage had it from November 2020 but did not
  carry it into its new backend system in May 2025.

Kafbat UI is also a Spring Boot app, and its build depends on the OIDC client and LDAP
modules, with no SAML module.

**Spring Security makes the two protocols very different to build and run.**

- **OIDC.** `oauth2Login()` needs a client ID, a secret and an `issuer-uri`. It discovers
  everything else, and supports RP-initiated and back-channel logout. It uses Spring
  artifacts from Maven Central.
- **SAML.** `saml2Login()` depends on OpenSAML. Spring's own documentation says the
  **Shibboleth Maven repository is required**, because Shibboleth does not publish to
  Maven Central (Central stops at OpenSAML 4.0.1, from 2021). Single logout needs the
  application to hold its own PKCS#8 signing key and certificate. With single logout
  configured, the app can fail to start if the identity provider is down. And the Spring
  Security 6.x line Nodqora is on still carries OpenSAML 4, which reached end of life on
  1 September 2024.

**A SAML-only identity provider can be put behind an OIDC one.** Keycloak, authentik and
Zitadel each accept a SAML identity provider on one side and serve OIDC applications on
the other. Dex can do this too, but its own README marks its SAML connector
"Unmaintained and likely vulnerable to auth bypasses". Argo CD's documentation repeats
that warning.

**Recommendation: the floor, and Community's promise, is OIDC only.** SAML is not built
until an outside organisation asks for it. If it is ever built, it is Community.
Wording changes are at the end.

---

## 1. What identity providers offer an application

This section is about the side of the identity provider that a self-hosted application
like Nodqora connects to. (The provider is the OIDC *OpenID Provider* or the SAML
*asserting party*.)

| Identity provider | OIDC to an app | SAML to an app | Notes |
|---|---|---|---|
| **Keycloak** | Yes | Yes | ["Supports any platform/language that has an OpenID Connect Relying Party library or SAML 2.0 Service Provider library."](https://www.keycloak.org/docs/latest/server_admin/index.html) |
| **authentik** | Yes — OAuth2/OIDC provider | Yes — SAML provider | Also LDAP, proxy, RADIUS, SCIM, WS-Fed providers ([providers](https://docs.goauthentik.io/add-secure-apps/providers/)). Whether any is licence-gated: **not verified** |
| **Dex** | Yes — the *only* protocol it serves | **No** | ["Dex is an identity service that uses OpenID Connect to drive authentication for other apps"](https://github.com/dexidp/dex/blob/master/README.md). It can *consume* SAML upstream; see below |
| **Zitadel** | Yes | Yes | Its SAML guide: ["for new projects, it is advisable to consider OpenID Connect because it is a more modern standard and is the more popular choice in the industry"](https://zitadel.com/docs/guides/integrate/login/saml) |
| **Microsoft Entra ID** | Yes | Yes | Both listed under federation-based SSO. For ISVs: ["OIDC is usually simpler to build with modern frameworks. SAML offers broader enterprise compatibility."](https://learn.microsoft.com/en-us/entra/identity/enterprise-apps/what-is-single-sign-on) (page dated June 2026) |
| **Okta** | Yes | Yes | The App Integration Wizard ["allows you to create an app integration and connect Okta with your SAML, OIDC, SWA, or SCIM application"](https://help.okta.com/en-us/content/topics/apps/apps_app_integration_wizard.htm) |
| **Google Workspace** | Yes, but not from the Workspace Admin console | Yes — Admin console "custom SAML app" | See below |
| **AD FS (on-premises Microsoft)** | From **AD FS 2016** | Yes (SAML is AD FS's native protocol; not re-fetched for this note) | See below |
| **Shibboleth IdP** | Yes, through the official OIDC OP plugin, IdP V4.1+ | Yes, native | ["now available as an offically-supported plugin for IdP V4.1 and above"](https://shibboleth.atlassian.net/wiki/spaces/IDPPLUGINS/pages/1376878976/OIDC+OP) |
| **JumpCloud** | Yes | Yes | ["use the Custom OIDC App connector with any app that supports OIDC-based SSO"](https://jumpcloud.com/support/sso-with-oidc); a custom SAML connector is also documented there. Plan gating: **not verified** |
| **PingOne** | Yes — OIDC web app | Yes — SAML application | [Application types](https://docs.pingidentity.com/pingone/applications/p1_application_types.html) |
| **PingFederate** | Yes — OpenID Provider | Yes (native; not re-fetched) | ["PingFederate can be deployed as an OpenID Provider (OP), a Relying Party (RP), or both"](https://docs.pingidentity.com/pingfederate/13.0/introduction_to_pingfederate/pf_openid_connect_support.html) |
| **OneLogin** | Yes | Yes — "SAML Custom Connector (Advanced)" | [OIDC developer docs](https://developers.onelogin.com/openid-connect): "OpenID Connect is easier to integrate than SAML"; [SAML Custom Connector](https://support.onelogin.com/kb/4266907/saml-custom-connector-advanced) |

**No product in this table is SAML-only.**

### Google Workspace

In the Workspace Admin console, a custom application is a SAML app: the
[custom SAML app guide](https://knowledge.workspace.google.com/admin/apps/set-up-your-own-custom-saml-app)
covers "a custom app that is not in the preintegrated catalog" and mentions no OIDC
equivalent. OIDC exists elsewhere, in the Google Cloud console.

- **Google's OAuth 2.0 is OIDC.** It ["conforms to the OpenID Connect specification, and is OpenID Certified"](https://developers.google.com/identity/openid-connect/openid-connect),
  with discovery at `https://accounts.google.com/.well-known/openid-configuration`. That is
  exactly the `issuer-uri` shape Spring discovers.
- **It can be limited to the customer's organisation.** Projects in a Google Cloud
  organisation can set the audience to **Internal**
  ["to limit authorization requests to members of the organization"](https://support.google.com/cloud/answer/15549945).
- **The `hd` hint is not access control.** The ID token's signed `hd` claim can be
  trusted, but Google warns
  ["don't rely on this UI optimization to control who can access your app"](https://developers.google.com/identity/openid-connect/openid-connect).
- **Groups are not in the OIDC claims.** Argo CD's Google guide notes that
  ["Google doesn't expose the `groups` claim via _oidc_"](https://github.com/argoproj/argo-cd/blob/master/docs/operator-manual/user-management/google.md),
  and that Google's SAML path does not return group membership either. This matters to
  Enterprise group sync, not to the floor.

So a Google shop signs in to Nodqora over OIDC. Its administrator uses the Cloud console
rather than the Admin console.

### AD FS

The [AD FS OpenID Connect/OAuth concepts](https://learn.microsoft.com/en-us/windows-server/identity/ad-fs/development/ad-fs-openid-connect-oauth-concepts)
page "Applies to Active Directory Federation Services (AD FS) 2016 and later". It lists a
`/.well-known/openid-configuration` endpoint, and states "AD FS 2016 and later supports
single log-out for OpenID Connect/OAuth".

Windows Server 2012 R2 did have OAuth in AD FS, but it had no OIDC. The Microsoft Learn
page above names 2016 as the floor. That 2012 R2 had OAuth but no OIDC is from Microsoft
Learn search summaries: **not verified** against a page that says it. That server release:

- **Extended support.** Ended [11 October 2023](https://learn.microsoft.com/en-us/lifecycle/products/windows-server-2012-r2).
- **Paid Extended Security Updates.** Year 3, the last, ends **14 October 2026**.

An AD FS estate that cannot do OIDC is therefore one running an out-of-support OS within a
month of this note.

### Academic federations

InCommon's [Metadata Registration Practice Statement](https://incommon.org/federation/mrps/)
says it ["maintains a registry of organizationally valid SAML metadata"](https://incommon.org/federation/mrps/),
which "SHALL conform to and validate against the OASIS Security Assertion Markup Language
(SAML) V2.0 Metadata specification". It mentions no OIDC.

eduGAIN's site refused the connection, so its protocol basis is **not verified** here.

A university service that wants to join a research federation needs SAML. That is a
federation-membership requirement, not an identity-provider limitation, since the
Shibboleth IdP itself speaks OIDC through the plugin. Whether event-driven platform teams
inside universities adopt tools through federation registration: **not verified**. On its
face, it is far from Nodqora's buyer.

### Dex's SAML connector

Dex can use a SAML identity provider *upstream* and present OIDC to the application. Its
README connector table still lists the SAML 2.0 connector's status as "stable". The notes
column of the same row says:

> WARNING: Unmaintained and likely vulnerable to auth bypasses ([#1884](https://github.com/dexidp/dex/discussions/1884))

The [connector page](https://dexidp.io/docs/connectors/saml/) says it "is unmaintained,
likely vulnerable to authentication bypass vulnerabilities, and is under consideration for
deprecation". It also lists these limits:

- no refresh tokens
- no signed AuthnRequests or encrypted attributes
- no unprompted responses

[Discussion #1884](https://github.com/dexidp/dex/discussions/1884), "Proposal: deprecate the
SAML connector", was opened on 14 December 2020, after a security advisory. Its argument
includes "modern IdPs also always support some form of OAuth2 or OIDC".

Argo CD, which bundles Dex, repeats the warning. Its
[Google guide](https://github.com/argoproj/argo-cd/blob/master/docs/operator-manual/user-management/google.md)
labels the Dex SAML route a "Deprecation Warning".

### Bridging a SAML-only provider to OIDC

Keycloak brokers exactly this. Its admin guide has a "SAML v2.0 Identity Providers"
section, and states:

> At the end of the authentication process, Keycloak issues its token to client
> applications. Client applications are separate from the external identity providers, so
> they cannot see the client application's protocol or how they validate the user's
> identity. The provider only needs to know about Keycloak.
> — [Keycloak Server Administration Guide](https://www.keycloak.org/docs/latest/server_admin/index.html)

The other brokers:

- **authentik.** Separates *sources*, which include SAML
  ([sources](https://docs.goauthentik.io/users-sources/sources/)), from *providers*, which
  include OAuth2/OIDC. A SAML source can therefore sit behind an OIDC provider.
- **Zitadel.** Federates to ["Custom-built IdPs that adhere to OpenID Connect or SAML protocols"](https://zitadel.com/docs/guides/integrate/identity-providers/introduction)
  and serves OIDC to applications.
- **Dex.** Can bridge too, with the warning above.

Keycloak, authentik and Zitadel can all be self-hosted. Their licences were **not verified**
here. A SAML-only
customer is one extra deployment away from OIDC. That is the same currency ADR-0109 already
charges Community for isolation.

---

## 2. What adjacent products ship

| Product | Free edition's protocols | First | SAML | SAML free or paid |
|---|---|---|---|---|
| **Grafana** | Generic OAuth, GitHub, GitLab, Google, Okta, Entra ID, LDAP, JWT, auth proxy | OAuth: Google/GitHub in 2.x (2015); generic OAuth in 4.0-beta1 (Nov 2016) | Added 6.3.0 (Aug 2019) | **Paid**: Enterprise and Cloud only, then and now |
| **OpenMetadata** | Google, Okta, Auth0, Azure, Cognito, OneLogin, Keycloak, custom OIDC, **SAML**, LDAP | OIDC providers, in the tree by April 2022 at the latest | Merged March 2023, shipped in 1.0.0 (April 2023) | **Free**: in the Apache-2.0 tree |
| **DataHub** | OIDC, native, JaaS | OIDC, March 2021 | **None**, not even in Cloud | n/a |
| **Backstage** | OIDC, OAuth2, many vendor OAuth modules, proxy providers | OAuth providers and SAML both in the earliest releases (Nov 2020) | Present 2020–2025, **not carried** into the new backend system | Was free; now absent from the core |
| **Argo CD** | Bundled Dex, or an external OIDC provider | Dex SSO in v0.4.0 (May 2018); external OIDC in v0.11.0 (Jan 2019) | Only through Dex's unmaintained connector | Free, but discouraged by Argo's own docs |
| **Kafbat UI** | Basic, OAuth2/OIDC, LDAP | — | **None** | n/a |
| **AKHQ** | Basic, LDAP, JWT, OIDC, GitHub, header | — | **None** | n/a |
| **Redpanda Console** | Basic; **OIDC needs an Enterprise licence** | — | **None** | n/a |

Evidence, product by product.

### Grafana

The [authentication overview](https://grafana.com/docs/grafana/latest/setup-grafana/configure-access/configure-authentication/)
states: "Team sync, active sync, and SAML are available in Grafana Enterprise and Grafana
Cloud. They aren't available in Grafana open source." The
[SAML page](https://grafana.com/docs/grafana/latest/setup-grafana/configure-access/configure-authentication/saml/)
carries the same availability note.

The history in [`CHANGELOG.md`](https://github.com/grafana/grafana/blob/main/CHANGELOG.md)
and its [archive](https://github.com/grafana/grafana/tree/main/.changelog-archive):

- **2.x (2015).** Google and GitHub OAuth entries, e.g. issue #1660.
- **4.0-beta1 (9 November 2016).** "OAuth: Add support for generic oauth" (#4718).
- **6.3.0-beta1 (10 July 2019).** "SAML: Add SAML as an authentication option (Enterprise)".
  6.3.0 was released on 6 August 2019.

### OpenMetadata

The [security docs](https://docs.open-metadata.org/latest/deployment/security) list eight
OIDC-style providers. Its SAML support lives in the Apache-2.0 tree:

- the [`samlSSOClientConfig.json`](https://github.com/open-metadata/OpenMetadata/blob/main/openmetadata-spec/src/main/resources/json/schema/security/client/samlSSOClientConfig.json) schema
- the `openmetadata-service/.../security/saml/` package, which includes `SamlLoginServlet`,
  `SamlAssertionConsumerServlet`, `SamlLogoutServlet` and `SamlMetadataServlet`

SAML arrived in [PR #10669 "SAML feature"](https://github.com/open-metadata/OpenMetadata/pull/10669),
merged 24 March 2023. The first release after it was `1.0.0-alpha` (30 March 2023), and
[`1.0.0-release`](https://github.com/open-metadata/OpenMetadata/releases/tag/1.0.0-release)
followed on 25 April 2023.

The Google SSO client schema already existed in the tree before the September 2022 module
refactor, at `catalog-rest-service/...`, with commits from April 2022. The exact date OIDC
first shipped: **not verified**.

### DataHub

The [authentication overview](https://docs.datahub.com/docs/authentication/) lists native
authentication, "Single Sign-On with OpenID Connect", and JaaS. OIDC arrived in PR #2190
"feat(react): SSO support simple OIDC authentication", committed 11 March 2021.

DataHub Cloud's [SSO guide](https://docs.datahub.com/docs/managed-datahub/integrations/oidc-sso-integration)
states: "We do not yet support LDAP or SAML authentication."

### Backstage

The [`@backstage/plugin-auth-backend` changelog](https://github.com/backstage/backstage/blob/master/plugins/auth-backend/CHANGELOG.md)
records the history:

- **0.2.0 (published to npm 4 November 2020).** "819a70229: Add SAML login to backstage".
- **0.25.0 (20 May 2025).** "**BREAKING**: Removed support for the old backend system, and
  removed all deprecated exports." Providers moved to dedicated modules.

The repository's `plugins/` directory now holds `auth-backend-module-oidc-provider`,
`-oauth2-provider`, `-okta-provider`, `-microsoft-provider` and others, but **no SAML module**.
The provider directory was emptied in commit
[d72da5e](https://github.com/backstage/backstage/commit/d72da5ec194cfccd9ca263a9b0ad7b119c13ec1a)
(April 2025).

The [auth docs](https://backstage.io/docs/auth/) still say "There are generic
authentication providers for OAuth2 and SAML". Whether a maintained SAML module exists
outside the core repository: **not verified**.

### Argo CD

The [user management docs](https://argo-cd.readthedocs.io/en/stable/operator-manual/user-management/)
say to use the bundled Dex "if your current provider does not support OIDC (e.g. SAML,
LDAP)", and to use an existing OIDC provider otherwise.

Its [CHANGELOG](https://github.com/argoproj/argo-cd/blob/master/CHANGELOG.md) dates the two
routes:

- **v0.4.0 (17 May 2018).** "SSO Integration".
- **v0.11.0 (10 January 2019).** "External OIDC provider support … without the need for
  running Dex".

Argo CD has no SAML implementation of its own.

### Kafbat UI

The [authentication docs](https://ui.docs.kafbat.io/configuration/authentication) list
basic, OAuth2 (Google, GitHub, Azure, Cognito, Okta, Keycloak) and LDAP/Active Directory.

Kafbat UI is a Spring Boot app, so its build is the most direct precedent for Nodqora.
[`api/build.gradle`](https://github.com/kafbat/kafka-ui/blob/main/api/build.gradle) depends
on:

- `spring.starter.security`
- `spring.starter.oauth2.client`
- `spring.security.oauth2.resource.server`
- `spring.security.ldap`

It has **no `spring-security-saml2-service-provider`**.

### AKHQ

The [authentication docs](https://akhq.io/docs/configuration/authentifications/) list basic,
LDAP, JWT, OIDC, GitHub and header auth.
[`build.gradle`](https://github.com/tchiotludo/akhq/blob/dev/build.gradle) pulls
`micronaut-security-oauth2` and `micronaut-security-ldap`, and no SAML module.

### Redpanda Console

The [authentication docs](https://docs.redpanda.com/current/console/config/security/authentication/)
offer OIDC and basic authentication. For OIDC: "This feature requires an enterprise
license." SAML is not mentioned.

### What the adjacent products show

- **OIDC came first everywhere dates could be found.** Backstage is the exception: it
  shipped both in its earliest releases, then dropped SAML.
- **SAML appears in a free edition in exactly one product today:** OpenMetadata, which
  added it in its 1.0 release.
- **The one platform tool that ships SAML charges for it:** Grafana.
- **The four tools closest to Nodqora's Kafka domain have no SAML at all:** Kafbat UI,
  AKHQ, Redpanda Console and Argo CD's native path. One of them, Redpanda Console, charges
  for OIDC itself.

Taken together: ADR-0109's finding that "no lineage or catalog product gates SSO" still
holds. But in this category SSO has meant OIDC. That is an inference from the products
above, not a claim any of them makes.

---

## 3. What Spring Security gives each protocol

Versions current as of this note:

- **Spring Security docs:** 7.1.1 is current, with 7.2.0-M1 in preview and 7.0.x and 6.5.x
  also listed.
- **Maven Central:** Spring Boot's latest milestone is 4.2.0-M1.
- **Nodqora:** pins Spring Boot **3.4.1** (`build.gradle.kts`), which is the Spring
  Security 6.4 line. Its only repository is `mavenCentral()`.

### OIDC: `oauth2Login()`

**Dependency.** `spring-security-oauth2-client`, from Maven Central.

- **Boot 3.x starter.** `spring-boot-starter-oauth2-client`.
- **Boot 4.x starter.** `spring-boot-starter-security-oauth2-client`. The old name's 4.0.0
  POM says "(deprecated in favor of spring-boot-starter-security-oauth2-client)"
  ([Maven Central](https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-starter-oauth2-client/4.0.0/spring-boot-starter-oauth2-client-4.0.0.pom)).

**Configuration.** Boot's [OAuth2 reference](https://docs.spring.io/spring-boot/reference/security/oauth2.html):
"For OpenID Connect providers supporting discovery, simplify configuration using
`issuer-uri`". That means one `provider.<name>.issuer-uri` plus a `registration.<name>`
with `client-id` and `client-secret`. Boot also pre-configures `google`, `github`,
`facebook`, `x` and `okta`.

**Logout.** The [OIDC logout reference](https://docs.spring.io/spring-security/reference/servlet/oauth2/login/logout.html)
documents two kinds:

- **RP-Initiated Logout.** Through `OidcClientInitiatedLogoutSuccessHandler`, which reads
  `end_session_endpoint` from discovery "with `issuer-uri`".
- **Back-Channel Logout.** Through `oidcLogout().backChannel(...)`, at
  `/logout/connect/back-channel/{registrationId}`. It has two constraints: the default
  expects a `JSESSIONID` cookie, and the default `OidcSessionRegistry` is in-memory, which
  the docs show replacing for clustered deployments.

**Keys.** The application holds a client secret and nothing else.

### SAML: `saml2Login()`

**Dependency and repository.** `spring-security-saml2-service-provider`. The
[SAML 2.0 login overview](https://docs.spring.io/spring-security/reference/servlet/saml2/login/overview.html)
states:

> It builds off of the OpenSAML library, and, for that reason, you must also include the
> Shibboleth Maven repository in your build configuration.

Boot's [SAML reference](https://docs.spring.io/spring-boot/reference/security/saml2.html)
says the same, giving `maven { url "https://build.shibboleth.net/maven/releases" }`.
Boot 4 has a `spring-boot-starter-security-saml2` starter on Central, but OpenSAML is not
there.

**Why Central is not an option.** Shibboleth's
[Use of Maven Central](https://shibboleth.atlassian.net/wiki/spaces/DEV/pages/1123844333/Use+of+Maven+Central)
page (updated April 2024) says its developers do not publish there:

- **Legal exposure.** Central's terms require individual indemnification, which none of
  them will give.
- **Tampered copies.** The older OpenSAML copies on Central were uploaded by others and
  "had been tampered with".

Checked directly: [Central's `opensaml-saml-api` metadata](https://repo1.maven.org/maven2/org/opensaml/opensaml-saml-api/maven-metadata.xml)
stops at `4.0.1`, last updated February 2021. `5.2.3` returns **404 on Central** and
**200 on `build.shibboleth.net/maven/releases`**.

**OpenSAML versions.**

- **Main branch, 7.x.** Spring Security's [version catalog](https://github.com/spring-projects/spring-security/blob/main/gradle/libs.versions.toml)
  carries only `org-opensaml5 = "5.2.3"`. The 7.0 migration guide's examples use
  `OpenSaml5AuthenticationProvider`.
- **6.4.x and 6.5.x branches.** Their catalogs carry both `org-opensaml = "4.3.2"` and
  `org-opensaml5 = "5.1.2"`, with separate `opensaml4Main` and `opensaml5Main` source sets.
  Which one a Boot 3.4 build resolves by default: **not verified**.

Shibboleth's [announcement](https://shibboleth.net/pipermail/announce/2023-September/000301.html)
gave "the planned EOL date for the V4 IdP (and OpenSAML of course)" as **1 September 2024**.

**Metadata.**

- **Reading the identity provider's metadata.** `assertingparty.metadata-uri`, or
  `RelyingPartyRegistrations.fromMetadataLocation(...)`, reads it. For refresh and
  signature verification of that metadata, the
  [metadata reference](https://docs.spring.io/spring-security/reference/servlet/saml2/metadata.html)
  shows `OpenSaml5AssertingPartyMetadataRepository`, which is code, not a property.
- **Publishing Nodqora's own metadata.** `saml2Metadata()` publishes it at
  `/saml2/metadata/{registrationId}`.

**Keys.** The login overview's minimum is:

> you need to have a certificate from the asserting party so that the asserting party's
> signed responses can be verified.

Beyond that, keys depend on what is switched on:

- **Encrypted assertions** need `decryption.credentials`: a private key and certificate
  ([authentication reference](https://docs.spring.io/spring-security/reference/servlet/saml2/login/authentication.html)).
- **Signed requests and single logout** need `signing.credentials`.

The Boot properties exist for all of these: `signing.credentials[]`,
`decryption.credentials[]`, `assertingparty.verification.credentials[]`,
`assertingparty.entity-id`, `sso-url`, `metadata-uri`, and `singlelogout.url`,
`response-url` and `binding`.

**Logout.** The [SAML 2.0 logout reference](https://docs.spring.io/spring-security/reference/servlet/saml2/logout.html)
supports RP-initiated and AP-initiated single logout. It requires "a PKCS#8 private key
and X.509 certificate for signing `saml2:LogoutRequest`s and `saml2:LogoutResponse`s". It
also warns:

> the identity server must be up and receiving requests in order for Service Provider to
> successfully start up. If the identity server is down when Service Provider queries it
> (given appropriate timeouts), then startup will fail.

**Validation.** Spring validates `Issuer`, `Destination`, `InResponseTo`, audience,
delegation and subject confirmation by default
([authentication reference](https://docs.spring.io/spring-security/reference/servlet/saml2/login/authentication.html)).
The 7.0 migration notes that GET-bound responses will stop being processed by default in
Spring Security 8.

### Side by side

| | OIDC | SAML |
|---|---|---|
| Artifacts | Maven Central | Spring on Central, OpenSAML from **Shibboleth's repository only** |
| Minimum config | `issuer-uri`, `client-id`, `client-secret` | asserting party metadata or entity-id, SSO URL and verification certificate |
| Keys Nodqora must hold | none beyond the client secret | none at minimum; PKCS#8 key and cert for signing (and SLO) and for decryption |
| Logout from the identity provider | RP-initiated and back-channel, both supported | RP- and AP-initiated SLO, supported with signing keys |
| Metadata refresh | discovery at startup | property path reads once; refresh needs `OpenSaml5AssertingPartyMetadataRepository` in code |
| Identity provider down at start | **not verified** for `issuer-uri` in this note | startup fails when SLO metadata must be read |
| On Nodqora's 6.4 line | stable | OpenSAML 4 (EOL Sept 2024) or 5 |

The Shibboleth repository has a local cost. [ADR-0157](../adr/0157-the-image-build-takes-a-worker-count-because-some-networks-drop-parallel-fetches.md)
exists because the image build already loses parallel fetches from Maven Central on some
networks. A second remote repository is a second host to reach at build time and a second
supply-chain trust root. [ADR-0141](../adr/0141-publication-is-private-and-the-sdk-trigger-stays-unfired.md)
and the build currently trust only Central.

---

## Recommendation

**The floor needs OIDC, and only OIDC. Community's promise should say the same. SAML is
not built until an outside organisation asks for it, and if it is built, it is Community.**

### Reasoning

1. **Every common identity provider offers OIDC to applications.** None checked is
   SAML-only. The SAML-only cases are:
   - **Google Workspace's Admin console.** Google's OIDC is one console over.
   - **AD FS on Windows Server 2012 R2.** Its last paid security updates end on
     14 October 2026.
   - **Research-federation membership.** A long way from Nodqora's buyer.
2. **A SAML-only customer has a documented bridge** that keeps Nodqora on one protocol:
   Keycloak, authentik or Zitadel in front, speaking OIDC. It costs a deployment, the same
   currency ADR-0109 already uses for Community isolation. Dex is *not* a recommended
   bridge: its own README calls the SAML connector unmaintained.
3. **The category ships OIDC.** Among the adjacent tools:
   - **SAML free today:** only OpenMetadata, added later.
   - **SAML paid:** Grafana.
   - **SAML dropped:** Backstage.
   - **No SAML:** DataHub, even in its paid Cloud; Argo CD natively; and every Kafka UI
     checked.

   Kafbat UI, a Spring Boot app in Nodqora's own domain, is built on
   `spring-boot-starter-oauth2-client` plus LDAP and never took the SAML module.
4. **SAML costs Nodqora more than the protocol.**
   - **Build.** A non-Central repository, into a build that already has a Central-fetch
     problem (ADR-0157).
   - **Library.** On today's Spring Security 6.4 line, a choice between an end-of-life
     OpenSAML 4 and OpenSAML 5.
   - **Keys.** Signing and decryption keys for the operator to generate, mount and rotate
     if single logout or encryption is wanted.
   - **Startup.** A dependence on the identity provider being up when SLO metadata is
     read.

   OIDC needs a client secret and an issuer URL.
5. **ADR-0109's purpose survives.** It wanted "the loudest single check a prospective
   adopter runs" answered "free". With OIDC free, a self-hoster pointing Nodqora at
   Keycloak, Entra ID, Okta, Google or authentik gets sign-in free. Whether any adopter's
   checklist literally demands *SAML* rather than *SSO* is **not verified**. The revisit
   trigger below covers that.
6. **The gate does not change.** ADR-0169's "OIDC or SAML" is already met by OIDC alone.
   The change removes an unbuilt promise from ADR-0109, ADR-0126 and the ledger; it does
   not move the floor.

### Wording changes

Repository convention is that an ADR is amended by a later ADR, whose number goes on the
earlier ADR's status line (e.g. ADR-0010, ADR-0034). The suggested new ADR is
**ADR-0172: Community authentication is OIDC, and SAML waits to be asked for**, which
amends ADR-0109, ADR-0126 and ADR-0169. The number is a placeholder; use the next free one.

**ADR-0109, status line**

Before:

```
- **Status**: Accepted
```

After:

```
- **Status**: Amended by [ADR-0172](0172-community-authentication-is-oidc-and-saml-waits-to-be-asked-for.md)
```

**ADR-0109, *Decision***

Before:

```
- **Community**: OIDC and SAML bind authentication against the customer's own
  identity provider. Anyone who authenticates sees every environment in that
  install.
```

After:

```
- **Community**: OIDC bind authentication against the customer's own identity
  provider. Anyone who authenticates sees every environment in that install.
  SAML is not built until asked for, and is Community if it ever is (ADR-0172).
```

**ADR-0169, status line and *The floor***

Before:

```
- **Status**: Accepted
```

```
2. **Community authentication** — OIDC or SAML against the user's own identity
   provider, shipped in a tagged release.
```

After:

```
- **Status**: Amended by [ADR-0172](0172-community-authentication-is-oidc-and-saml-waits-to-be-asked-for.md)
```

```
2. **Community authentication** — OIDC against the user's own identity
   provider, shipped in a tagged release.
```

**ADR-0126, status line and *Consequences***

Before:

```
- **Status**: Accepted
```

```
  phase 8 including blast radius, drift, one-shot comparison, the incident view,
  OIDC and SAML authentication, and complete audit capture. A ten-person team
```

After:

```
- **Status**: Amended by [ADR-0172](0172-community-authentication-is-oidc-and-saml-waits-to-be-asked-for.md)
```

```
  phase 8 including blast radius, drift, one-shot comparison, the incident view,
  OIDC authentication, and complete audit capture. A ten-person team
```

**`docs/editions.md`, ledger row**

Before:

```
| **Authentication** — OIDC and SAML bind against your own identity provider | §49 phase 12 (part) | The category convention; contradicts §43 deliberately (ADR-0109) |
```

After:

```
| **Authentication** — OIDC binds against your own identity provider; a SAML-only provider is brokered through one that speaks OIDC | §49 phase 12 (part) | The category convention; contradicts §43 deliberately (ADR-0109, ADR-0172) |
```

**`docs/editions.md`, *When Enterprise opens*, floor row**

Before:

```
| **The floor** | The Community release that closes Tier 1 with Prometheus, **and** Community authentication (OIDC or SAML) in a tagged release. Not `1.0`, and none of phases 5–10 |
```

After:

```
| **The floor** | The Community release that closes Tier 1 with Prometheus, **and** Community authentication (OIDC) in a tagged release. Not `1.0`, and none of phases 5–10 |
```

**`docs/editions.md`, *Revisit triggers*, new bullet.** It moves a capability toward
Community, so ADR-0128's rule for that list holds.

```
- An outside organisation that cannot sign in because its identity provider
  speaks only SAML, and for whom brokering through an OIDC provider is not an
  option — SAML is then built, in Community (ADR-0109, ADR-0172).
```

Leave line 288 of `docs/editions.md` (`SSO/SAML is Enterprise (listed first)`) as it is: it
quotes §43, not a decision. The same goes for ADR-0109's *Context* line quoting §43.

---

## Sources

**Identity providers**

- Keycloak Server Administration Guide — https://www.keycloak.org/docs/latest/server_admin/index.html
- authentik providers — https://docs.goauthentik.io/add-secure-apps/providers/
- authentik sources — https://docs.goauthentik.io/users-sources/sources/
- Dex README (connector table) — https://github.com/dexidp/dex/blob/master/README.md
- Dex SAML connector — https://dexidp.io/docs/connectors/saml/
- Dex discussion #1884, "Proposal: deprecate the SAML connector" — https://github.com/dexidp/dex/discussions/1884
- Zitadel SAML guide — https://zitadel.com/docs/guides/integrate/login/saml
- Zitadel identity providers — https://zitadel.com/docs/guides/integrate/identity-providers/introduction
- Microsoft Entra ID, what is SSO — https://learn.microsoft.com/en-us/entra/identity/enterprise-apps/what-is-single-sign-on
- AD FS OpenID Connect/OAuth concepts — https://learn.microsoft.com/en-us/windows-server/identity/ad-fs/development/ad-fs-openid-connect-oauth-concepts
- Windows Server 2012 R2 lifecycle — https://learn.microsoft.com/en-us/lifecycle/products/windows-server-2012-r2
- Okta App Integration Wizard — https://help.okta.com/en-us/content/topics/apps/apps_app_integration_wizard.htm
- Google Workspace custom SAML app — https://knowledge.workspace.google.com/admin/apps/set-up-your-own-custom-saml-app
- Google OpenID Connect — https://developers.google.com/identity/openid-connect/openid-connect
- Google Auth Platform audience — https://support.google.com/cloud/answer/15549945
- Shibboleth OIDC OP plugin — https://shibboleth.atlassian.net/wiki/spaces/IDPPLUGINS/pages/1376878976/OIDC+OP
- InCommon Metadata Registration Practice Statement — https://incommon.org/federation/mrps/
- JumpCloud SSO with OIDC — https://jumpcloud.com/support/sso-with-oidc
- PingOne application types — https://docs.pingidentity.com/pingone/applications/p1_application_types.html
- PingFederate OpenID Connect support — https://docs.pingidentity.com/pingfederate/13.0/introduction_to_pingfederate/pf_openid_connect_support.html
- OneLogin OpenID Connect — https://developers.onelogin.com/openid-connect
- OneLogin SAML Custom Connector (Advanced) — https://support.onelogin.com/kb/4266907/saml-custom-connector-advanced

**Adjacent products**

- Grafana authentication overview — https://grafana.com/docs/grafana/latest/setup-grafana/configure-access/configure-authentication/
- Grafana SAML — https://grafana.com/docs/grafana/latest/setup-grafana/configure-access/configure-authentication/saml/
- Grafana CHANGELOG and archive — https://github.com/grafana/grafana/blob/main/CHANGELOG.md, https://github.com/grafana/grafana/tree/main/.changelog-archive
- OpenMetadata security — https://docs.open-metadata.org/latest/deployment/security
- OpenMetadata PR #10669 — https://github.com/open-metadata/OpenMetadata/pull/10669
- OpenMetadata 1.0.0 release — https://github.com/open-metadata/OpenMetadata/releases/tag/1.0.0-release
- DataHub authentication — https://docs.datahub.com/docs/authentication/
- DataHub OIDC — https://docs.datahub.com/docs/authentication/guides/sso/configure-oidc-react
- DataHub Cloud SSO — https://docs.datahub.com/docs/managed-datahub/integrations/oidc-sso-integration
- Backstage auth docs — https://backstage.io/docs/auth/
- Backstage auth-backend CHANGELOG — https://github.com/backstage/backstage/blob/master/plugins/auth-backend/CHANGELOG.md
- Argo CD user management — https://argo-cd.readthedocs.io/en/stable/operator-manual/user-management/
- Argo CD Google guide — https://github.com/argoproj/argo-cd/blob/master/docs/operator-manual/user-management/google.md
- Argo CD CHANGELOG — https://github.com/argoproj/argo-cd/blob/master/CHANGELOG.md
- Kafbat UI authentication — https://ui.docs.kafbat.io/configuration/authentication
- Kafbat UI `api/build.gradle` — https://github.com/kafbat/kafka-ui/blob/main/api/build.gradle
- AKHQ authentication — https://akhq.io/docs/configuration/authentifications/
- AKHQ `build.gradle` — https://github.com/tchiotludo/akhq/blob/dev/build.gradle
- Redpanda Console authentication — https://docs.redpanda.com/current/console/config/security/authentication/

**Spring**

- Spring Security SAML 2.0 login overview — https://docs.spring.io/spring-security/reference/servlet/saml2/login/overview.html
- Spring Security SAML 2.0 authentication — https://docs.spring.io/spring-security/reference/servlet/saml2/login/authentication.html
- Spring Security SAML 2.0 metadata — https://docs.spring.io/spring-security/reference/servlet/saml2/metadata.html
- Spring Security SAML 2.0 logout — https://docs.spring.io/spring-security/reference/servlet/saml2/logout.html
- Spring Security 7.0 SAML migration — https://docs.spring.io/spring-security/reference/7.0/migration/servlet/saml2.html
- Spring Security OIDC logout — https://docs.spring.io/spring-security/reference/servlet/oauth2/login/logout.html
- Spring Security version catalog (main) — https://github.com/spring-projects/spring-security/blob/main/gradle/libs.versions.toml
- Spring Boot OAuth2 — https://docs.spring.io/spring-boot/reference/security/oauth2.html
- Spring Boot SAML 2.0 — https://docs.spring.io/spring-boot/reference/security/saml2.html
- Shibboleth, Use of Maven Central — https://shibboleth.atlassian.net/wiki/spaces/DEV/pages/1123844333/Use+of+Maven+Central
- Shibboleth IdP V4 / OpenSAML 4 EOL announcement — https://shibboleth.net/pipermail/announce/2023-September/000301.html
- Maven Central, `opensaml-saml-api` metadata — https://repo1.maven.org/maven2/org/opensaml/opensaml-saml-api/maven-metadata.xml

**Nodqora, for cross-reference**

- [ADR-0109](../adr/0109-authentication-is-community-and-view-scoping-is-enterprise.md)
- [ADR-0126](../adr/0126-there-is-no-free-enterprise-tier-at-any-threshold.md)
- [ADR-0141](../adr/0141-publication-is-private-and-the-sdk-trigger-stays-unfired.md)
- [ADR-0157](../adr/0157-the-image-build-takes-a-worker-count-because-some-networks-drop-parallel-fetches.md)
- [ADR-0169](../adr/0169-enterprise-opens-on-a-floor-and-evidence-and-the-clock-forces-a-decision.md)
- [`docs/editions.md`](../editions.md)
