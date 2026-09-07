# Where Comparable Open-Core Tools Drew the Community/Enterprise Line

Research input for [Research: how comparable open-core infra tools drew their
community/enterprise line](https://github.com/fredskor/nodqora/issues/41), on the map
[Wayfinder map: Nodqora tiers and monetization](https://github.com/fredskor/nodqora/issues/40).
It feeds four tickets that follow it: the feature ledger, the gating mechanism, the pricing
unit, and the repo's licensing files.

This document reports what ten comparable products actually do. It does not recommend
anything; the **Implications** section lists options and their costs and stops there.

All load-bearing claims below were fetched from the cited primary source — official docs,
vendor pricing pages, upstream repository files, licence texts, or first-party announcements.
Where a claim could not be checked against a primary source it says **not verified** rather
than guessing. Nothing here rests on a comparison blog or a listicle. Pricing is dated,
because pricing pages move; unless stated otherwise, figures are **as published, September
2026**. Coverage is uneven by design: OpenMetadata, Airbyte and GitLab were read down to the
source files that implement the gate, DataHub and Marquez as far as confirming that no such
file exists, and Kong and Spotify Portal only as far as their documentation and distribution
artifacts.

The ten split into three groups, surveyed separately below: **platform and developer
portals** (Backstage, Grafana, GitLab, Kong), **infrastructure dev-tools** (Temporal,
Airbyte, Cerbos), and **lineage and catalog products** (OpenMetadata, DataHub, Marquez).
The third group is the closest in shape to Nodqora and its findings diverge sharply from
the first, which is itself the most important result here.

---

## Summary

Findings that bear on Nodqora's decisions, stated as findings.

**The gating mechanism is chosen by product architecture, not by legal preference — and this
is the single finding that most constrains Nodqora.** Where the paid value is a service the
client *calls*, nothing valuable ships in the customer's binary, so the binary can stay
permissive with no gate at all: Cerbos ships its entire Hub client in the Apache-2.0 repo and
enforces by having Hub refuse credentials; Temporal has no proprietary server edition and no
licence-key machinery anywhere; Astronomer's paid product talks to Marquez over a wire
protocol. Where the paid value *executes locally in the customer's deployment*, the check
must ship to the customer, and then the licence has to forbid deleting the check — which is
why Airbyte moved to ELv2 with an anti-circumvention clause, and why Grafana, GitLab and Kong
all ship licence validators. Decide where Enterprise value sits and the mechanism follows
mechanically.

**"SSO is the enterprise tax" is false in the category Nodqora is actually in.** Three of the
four platform tools gate SSO, but every lineage/catalog product ships it free — OpenMetadata
ships eight providers in the Apache-2.0 tree, DataHub ships OIDC — and Backstage does not gate
it either. Where SSO *is* gated, the cut is finer than the slogan: Grafana and GitLab both make
bind-authentication free and put directory/**group synchronisation** behind the line. Spotify's
incentive is structural rather than principled — it sells plugins that attach to Backstage
rather than a tier of it, so crippling the base would cost it nothing and gain it nothing.

**The recurring RBAC seam is that the policy *framework* is free and the policy *authoring
surface* is paid.** Backstage ships a `PermissionPolicy` TypeScript interface upstream and no
policy UI whatsoever; that absence is precisely what Spotify's RBAC plugin and Roadie's Admin
UI sell. DataHub's refinement is sharper and more stealable: the policy engine is open source,
but specific privileges are Cloud-only — including `View Entity`. So *view*-level access
control is paid while *edit*-level control is free.

**Audit splits at capture versus aggregation, and that is the cheapest concession on the
board.** Cerbos gives away complete decision logging to local, file and Kafka backends and
sells only the `hub` backend — fleet-wide collection, search and retention. Backstage's
Auditor core service captures free while Spotify sells query and retention. GitLab ships audit
events at every tier but only sign-in events free. The self-hoster who wants logs already owns
the disk they would be written to, so conceding capture costs the vendor nothing and buys
considerable goodwill.

**Retention is metered rather than gated, and in the lineage slice it is not a paid axis at
all.** Temporal charges 40× more per GBh for Active than Retained storage and caps hosted
retention at 30–90 days while self-hosted is unlimited; Cerbos ladders audit retention at one
week / three months / one year / custom. In none of these is the *ability* to retain gated —
the vendor-hosted *duration* is. Among the lineage products no history or time-travel gating
appeared at all; Collate instead prices **freshness** (weekly / 8-hour / hourly refresh),
degrading the same feature on a timer rather than removing it. Retention monetizes cleanly
because it maps to a real vendor cost, which is exactly the property it loses when the customer
hosts the storage themselves.

**Every product in the survey fails soft, and they converge on the same rule: freeze the
control path, preserve the data path.** Kong on licence expiry goes read-only — the Admin API
is frozen while the proxy keeps forwarding traffic. Grafana keeps existing dashboards working
and blocks only the creation of new roles and reports. No one in the survey hard-stops.

**The gate that actually gets built is far weaker than a cryptographic scheme, and the vendors
accept that.** OpenMetadata's mechanism is a `Limits` interface with an inert default
implementation whose `enforceLimits` body is literally `// do not enforce limits`, selected at
startup by `Class.forName(limitsConfiguration.getClassName())` — the gate is possession of a
jar, not verification of a key, and there is no cryptographic validation anywhere in the open
code. Where a key does exist it is a signed token with the entitlements inside it: Airbyte
parses a signed JWT carrying `maxNodes`, `maxEditors`, `enterpriseConnectorIds` and `exp`;
Grafana's is bound to `root_url`; Kong ships a signed JSON licence. Airbyte's arrangement is
the instructive one — the *checking* code lives in the public repo, the *signing key* does not.
Ship the lock in public, keep the key private.

**Only GitLab puts proprietary source in its public repo, and it needed three separate
mechanisms to do it**: a root LICENSE that routes by directory, a bespoke `ee/LICENSE`, and
`License.feature_available?` with the tiers as constants. Grafana (private repo), Kong (a
separate `kong-enterprise-edition` distribution), Spotify (compiled-only npm packages), Cerbos,
Temporal, DataHub and Astronomer all keep proprietary code out of the open tree entirely.
Nodqora's already-fixed decision that this repo never contains proprietary files is the
majority practice, not the unusual one.

**Apache-2.0 with no CLA already preserves the right to ship the same code under a proprietary
licence — provided Enterprise lives in files outside contributors never touch.** Backstage runs
DCO-only with no CLA; DataHub and OpenMetadata have neither CLA nor DCO, relying on bare
Apache-2.0 §5 inbound=outbound; and Collate relicensed OpenMetadata's entire UI and connector
trees anyway, on that basis alone. A CLA becomes strictly load-bearing in only two cases: going
copyleft (Grafana's sublicensable grant is what makes AGPL-plus-Enterprise work), or putting
proprietary source inside the OSS repo — where GitLab's split is the design to copy, DCO for
the permissive tree and a CLA only for `ee/`. What Apache-2.0-without-a-CLA does *not* buy is
retroactive relicensing: every version already shipped stays forkable forever, as
OpenMetadata's 1.5.0 ingestion tree still is.

**The pricing unit splits cleanly by category, and per-seat is losing ground everywhere except
developer portals.** Roadie is the closest published precedent to a canvas product and charges
**$24 per developer per month with entity count explicitly unlimited** — the humans looking at
the graph, not the nodes in it (though both its developer-portal tiers are labelled "existing
subscribers only" as published, so the precedent is a priced one, not necessarily a live
one). The infrastructure tools have abandoned seats almost entirely: Temporal bills Actions
plus storage plus capacity with no seat or namespace line item anywhere,
Airbyte bills volume or Data Worker capacity, and Cerbos bills Monthly Active Principals — the
end users of the *customer's* application, not the customer's staff. The lineage products
publish nothing at all, and the one disclosed unit, Collate's seats × assets, is notable
because the asset axis is exactly what the `Limits` SPI exists to enforce: the pricing metric
and the code gate are the same object.

**Nobody in the survey publishes a self-hosted Enterprise price.** Grafana publishes eleven
Cloud unit prices and no self-managed figure, GitLab has withdrawn Ultimate's list price, and
DataHub's pricing URL 404s as of 2026-09-05. Self-hosted commercial pricing is contact-us
across all ten.

**GitLab is the only vendor that publishes its criterion, and it is almost exactly the rule
Nodqora already fixed while charting**: "If the likely buyer is an individual contributor the
feature will be open source, otherwise it will be source-available." That is the buyer-based
split rule arrived at independently, which is meaningful corroboration — with the caveat that
GitLab's written promise deliberately covers the OSS codebase and *not* SaaS, which is what
licenses the five-user cap on GitLab.com Free while self-managed Free stays uncapped.

**The line moves in both directions routinely, and the licence constrains none of it.** Kong
moved *toward* free twice (a free mode in 2021, Kong Manager open-sourced in 2023) and is now
reversing, with free mode deprecated at 3.10.0.0. Airbyte relicensed three times in four years,
each time citing competitors reselling contributors' work as a managed service. OpenMetadata
relicensed its UI in 2023 and every ingestion connector in 2024, quietly, by directory, in a PR
titled "Docs - Ingestion License" with no stated rationale — while the README still says the
project is "released under the Apache License, Version 2.0". Temporal moved SSO *into* its base
Enterprise plans at no additional charge. The direction of travel is what an adopter prices in,
and it is why "is this open source?" has to be answered per-directory rather than per-repo.

**No vendor in the survey demoted an existing free feature to paid — but withdrawing the free
way to *run* it is treated as fair game.** Grafana open-sourced OnCall in 2022 and archived it
in 2026 with a Cloud-only successor, citing technical debt rather than monetisation. Where a
vendor did move a line and users pushed back, it reversed within one point release (Insomnia
8.0 → 8.3), which was cheap only because the change was a default rather than a licence.

**A self-hosted commercial edition is not guaranteed to survive contact with its own operating
cost.** Airbyte's documentation now states flatly that "Airbyte no longer sells Self-Managed
Enterprise"; SSO, RBAC and audit moved to requiring an Airbyte-operated control plane. Cerbos
moved the opposite way, adding on-premise Hub in January 2026 for air-gapped buyers. Every
customer on a self-hosted edition runs a different version, support is harder, and the
licence-key machinery has to be built and maintained — a cost at least one serious vendor
concluded was not worth paying.

**Foundation hosting forecloses the exact right this effort has decided to keep.** Marquez and
OpenLineage are both LF AI & Data *Graduated* projects, and the foundation's lifecycle document
requires transferring "the project's assets to the Linux Foundation" and having "an OSI-approved
license". Those two clauses jointly forbid a Collate-style relicensing, so Astronomer's
commercial value landing in a separate proprietary product is a **consequence of governance,
not a strategic preference**. Note also the common belief that DataHub and OpenMetadata are
foundation projects is wrong — both are vendor-owned, which makes DataHub's restraint a choice
rather than a constraint, and therefore the more interesting model.

**Support is the one line item nobody has ever had to defend, relicense, or walk back.**
Frontside sells nothing else against Backstage; Grafana bundles indemnification with it;
Temporal prices it as the greater of a floor or 5–10% of usage.

---

## Implications

Options and their costs, against the decisions this map still has open. No recommendation.

### For the feature ledger

The survey contradicts the product plan's §43 sketch in one specific place worth resolving
deliberately rather than by inheritance. §43 lists **SSO/SAML** first among Enterprise
features, and that is the platform-tools convention — but no lineage or catalog product gates
it, and Nodqora is closer in shape to those. Three positions are available: follow §43 and gate
SSO (defensible against Grafana/GitLab/Kong precedent, but the loudest single thing a
prospective adopter checks); ship SSO free and gate **group synchronisation** only (the finer
Grafana/GitLab cut, which keeps single-team self-hosters whole while still requiring purchase
at organisational scale); or ship all of it free as the catalog products do and take the paid
line elsewhere entirely.

The categories the survey found reliably paid, which map onto Nodqora's roadmap, are: metadata
or state flowing *outward* to source systems (Nodqora analogue: any write-back to Kafka or
Connect — noting the plan's §5.6 read-only-by-default principle already places this late),
approval and request workflow, continuous monitoring and alerting, and fleet-scale rollups
across many instances. §43's **audit logging** and **topology history** both want splitting
rather than placing: capture free and aggregation paid is the consensus shape for audit, and
history is metered rather than gated everywhere it appears — which is a problem for Nodqora
specifically, because the customer hosts the storage, so the vendor cost that makes retention
metering defensible does not exist here. That is a real gap the ledger ticket has to close on
its own evidence.

DataHub's view-versus-edit privilege split is the one finding that transfers without
modification: gating *which nodes a user may see* while leaving *who may change them* free is
a coherent line for a topology canvas, and it is the shape of RBAC an organisation actually buys.

### For the gating mechanism

There is a live tension between two things this map has already fixed. The **Notes** commit to
a separate private Enterprise repo consuming published artifacts, which is the Cerbos/Temporal
shape — where the gate is that you do not have the jar. The [License-key and gating
mechanism](https://github.com/fredskor/nodqora/issues/44) ticket asks for a signed offline
licence key, which is the Airbyte/Grafana/Kong shape — where the gate is a check running inside
the customer's copy. These are not incompatible, but they answer different threats, and the
survey suggests picking one as primary: possession-of-artifact gating costs nothing to build and
is what the majority do, while key-based gating is what you need if Enterprise modules ship in
the same deployable and you want expiry, seat caps, or trials to mean anything.

If a key is chosen, Airbyte's arrangement is the one to copy and it is compatible with "this
repo never contains proprietary files" only in a specific way: the verifier can be Apache-2.0
and public, provided the signing key is not — but note that an Apache-2.0 verifier may be
freely deleted by a forker, since Apache-2.0 has no anti-circumvention clause. Every vendor in
the survey that ships an in-binary check also ships a licence forbidding its removal. Nodqora
has fixed Apache-2.0 for this repo, so a key-based gate here is honest deterrence rather than
enforcement, and should be described that way. OpenMetadata's inert-SPI pattern is the cheapest
version and the one that keeps the open repo clean: an interface plus a do-nothing default,
with the real implementation arriving as a jar.

On expiry, the survey is unanimous enough to treat as settled practice: fail soft, freeze the
control path, keep the data path alive. For Nodqora that reads as — the canvas keeps rendering
and health keeps refreshing; what stops is creating new roles, new retention, new comparisons.

There is also a load-bearing constraint from inside the repo. ADR-0015 fixes one JAR, one
process, no classloader isolation and **no separate artifacts**, with plugins collected as
Spring `@Component`s in the same deployable. Every mechanism above assumes Enterprise arrives as
a separately-distributed artifact. Reconciling that is properly the [extension
contract](https://github.com/fredskor/nodqora/issues/43) ticket's work, but the gating decision
cannot be made independently of it.

### For the pricing unit

The precedent closest to Nodqora in product shape is Roadie, and it charges per developer with
entity count explicitly unlimited — which is an argument against the instinct to bill per node
or per monitored service. The instinct has a real cost the survey makes visible: a unit that
grows with the customer's *topology* penalises exactly the adoption the product needs, since
discovering more of the estate is the thing Nodqora is for. Against that, the infrastructure
tools have moved away from seats precisely because seats do not track value, and Cerbos's
choice — billing the customer's end users rather than the customer's staff — has no clean
analogue here, since a topology canvas has no end users beyond the staff.

The units that remain, with their costs: **per seat** (proven for portals, penalises
read-only viewers, and Nodqora's own §77 risk is engineers not using it — a seat charge makes
that worse); **per connected environment or cluster** (tracks organisational scale, which is
what the buyer-based split rule already says Enterprise is for, and does not penalise discovery
within an environment); **flat per installation** (simplest to sell and to enforce offline,
gives up expansion revenue). Note that if the pricing metric and the enforcement mechanism are
to be the same object, as they are at Collate, then this decision constrains what the licence
key must encode — so it wants settling before or alongside the gating mechanism, not after.

The survey offers nothing on price *points* for self-hosted editions, because no vendor
publishes any. That is a finding, not a gap: contact-us is the norm, and the ticket's decision
to fix the unit and the trial shape without price points matches practice.

### For LICENSE, NOTICE and the contribution agreement

The [licensing files ticket](https://github.com/fredskor/nodqora/issues/46) turns on whether a
CLA is needed, and the survey's answer is that on the plan as currently fixed, it is not.
Apache-2.0 inbound already permits shipping the same code in a closed edition; Backstage proves
DCO-only works, and OpenMetadata proves that even bare Apache-2.0 with no sign-off at all was
enough to relicense a major subtree. The CLA becomes necessary only under a change this map has
already ruled out — proprietary source inside this repo — or one it has not considered, a
copyleft licence.

The costs are asymmetric in time, which is the thing to weigh. A CLA is cheap now and expensive
later: Airbyte introduced one at the moment of its first relicensing and drew on it three times
over four years, whereas retrofitting one requires re-consent from every prior contributor.
A DCO is the stronger community promise and is affordable exactly when Enterprise shares no
files with the open tree — which is Nodqora's stated plan, and Cerbos's. The decision therefore
reduces to how much confidence there is in the separate-repo commitment holding.

Two adjacent findings bear on the same ticket. Foundation donation forecloses the relicensing
right permanently, so if that is ever contemplated it has to be contemplated before, not after.
And Airbyte's split — keeping the *protocol* permissive while the implementation moved — is a
low-cost hedge available to Nodqora for `nodqora-plugin-api`: licensing the contract separately
and permissively buys ecosystem goodwill regardless of what the main repo's licence does later.

### For how Community surfaces gated features

The survey has one direct contribution and one warning. The contribution is the fail-soft
convention above, which is the same question asked at expiry rather than at first encounter, and
answering both the same way is cheaper and more honest than two rules. The warning is
OpenMetadata: a README that still claims Apache-2.0 over a tree that is substantially not, and a
relicensing shipped in a PR whose body was one line. The reputational cost of that is visible in
how the project is now discussed. Whatever the upsell surface says, the survey's evidence is
that adopters check the *claims* against the *tree*, and discrepancies are found.

---

## Survey: platform and developer portals

Four tools — Backstage (+ Spotify/Roadie/Frontside), Grafana, GitLab, Kong — examined on five axes: where the free/paid line sits, how it is enforced, what the pricing unit is, whether the line has moved, and what inbound contribution licensing preserves the vendor's right to ship the same code proprietarily.

All pricing claims are dated **as published, September 2026** unless stated otherwise. Claims that could not be grounded in a primary source are marked **not verified**.

---

### Backstage (Spotify Portal / Roadie / Frontside)

#### Where the line is

Backstage is the outlier of this slice: the upstream project is Apache-2.0 under the CNCF and **the paid layer sits entirely outside it**, added as plugins rather than carved out of it. [`LICENSE`](https://raw.githubusercontent.com/backstage/backstage/master/LICENSE) is verbatim Apache-2.0 and [`NOTICE`](https://raw.githubusercontent.com/backstage/backstage/master/NOTICE) attributes only two third-party bodies of code (Zalando's Tech Radar, OpenAPI Generator templates). Across all 273 `package.json` files in the repo, 231 declare `Apache-2.0`, 42 declare no `license` field (internal tooling), and **zero declare a proprietary or dual licence**. Free: Software Catalog, TechDocs, Scaffolder, Search, and the auth and permission frameworks.

**SSO is not the paywall — and that is the single most useful datum in this slice.** Upstream ships the full enterprise auth set for free: Auth0, Atlassian, Azure, Azure Easy Auth, Bitbucket, Bitbucket Server, Cloudflare Access, GitHub, GitLab, Google, Google IAP, Keycloak, Okta, OAuth2 Custom Proxy, OneLogin, OpenShift, VMware Cloud, plus **generic OAuth2 and SAML providers** and an "OIDC provider from scratch" guide ([backstage.io/docs/auth/](https://backstage.io/docs/auth/)). There is no enterprise auth tier upstream at all.

Where the line actually falls is one notch further in, and the shape is consistent: **upstream gives you the primitive, the vendors sell the surface over it.**

- **Authorization**: the permission framework is free but is *only a framework*. The docs describe policies expressed "in code, a provided authorization method (such as RBAC), or integrations with external authorization providers" ([overview](https://backstage.io/docs/permissions/overview)), and the documented path is writing a TypeScript class implementing `PermissionPolicy` with a `handle(request, user)` returning a `PolicyDecision` ([writing-a-policy](https://backstage.io/docs/permissions/writing-a-policy)). **There is no policy-authoring UI upstream.** That gap is precisely what Spotify's RBAC plugin and Roadie's no-code Admin UI sell.
- **Audit**: half-free, split along the same seam. Upstream has an **Auditor core service** giving "a standardized way to capture security events," with severity categorisation, metadata and success/failure reporting, defaulting to writing through `rootLogger` ([core-services/auditor](https://backstage.io/docs/backend-system/core-services/auditor)) — but no UI, no query API, no retention model. Spotify's Portal Audit Logs is explicitly "built on top of Backstage's Auditor Core Service and exposed via REST API," adding filtering by plugin, severity, time range and actor ([Portal audit-logs docs](https://backstage.spotify.com/docs/portal/core-features-and-plugins/audit-logs/getting-started.md)). **Capture is free; retention, query and reporting are paid.**

**Paid — Spotify Plugins for Backstage bundle**, four named plugins ([backstage.spotify.com/plugins-bundle](https://backstage.spotify.com/plugins-bundle)): **Soundcheck** (codified quality/reliability checks), **Skill Exchange** (internal learning marketplace), **Role-Based Access Control**, and **Insights** ("Identify, benchmark, and understand Backstage usage trends" — i.e. usage analytics, the same category Grafana reserves).

**Paid — Spotify Portal** adds beyond the bundle: Fleetshift (fleet-wide code changes including AI shifts), AiKA, AI Gateway, Data Experience, Audit Logs, the Catalog Builder / Portal-managed catalog ingestion, and a no-code Scaffolder template editor ([docs index](https://backstage.spotify.com/docs/portal/getting-started.md)).

**Spotify Confidence**, sold alongside, is the cleanest category-gating example anywhere in this slice ([confidence.spotify.com/pricing](https://confidence.spotify.com/pricing)): Free = **Google SSO only**, **audit logs 1 month**, no RBAC; Growth = **Google + SAML**, 6–12 month audit retention; Enterprise = **RBAC**, **custom audit retention**, SLA, dedicated CSE. SSO provider, audit *retention window*, and RBAC are the three dials.

**Paid — Roadie**: the Teams tier includes SSO, 75+ plugins, RAG AI and MCP server access, and optional Scorecards; **Growth** adds SLA, Slack/MS Teams support, **custom private plugins**, REST API access, advanced search, secure on-prem connection, usage analytics and **custom RBAC** ([roadie.io/pricing](https://roadie.io/pricing/)). Custom plugins are hard-gated — "Custom plugins are a feature of the Growth Plan" ([docs](https://roadie.io/docs/custom-plugins/overview/)) — and Roadie's RBAC is a product rather than a framework: roles "can be managed via the no-code Roadie Admin UI or ingested from an Identity Provider via the identity token," and it is "inspired by the Backstage Permissions framework" ([docs](https://roadie.io/docs/permissions/overview/)).

**Paid — Frontside**: no software at all. Backstage Support in Silver / Gold / Platinum tiers scaled by team size (<2 devs POC, 3–5 early adoption, 5+ advanced), differing in standing meetings, pair-programming sessions, code reviews, code examples, concurrent upstream changes, and chat-support seats ([frontside.com/backstage/support](https://frontside.com/backstage/support/)). Frontside's own products (Interactors, Effection, Simulacrum) are open source.

#### Gating mechanism

**Spotify's plugins are public npm packages with a runtime licence key — not a private registry.** This is commonly misstated and is directly verifiable. All 40 `@spotify/backstage-plugin-*` packages sit on the *public* registry.npmjs.org and anyone can `npm install` them (`@spotify/backstage-plugin-soundcheck@0.28.1`, `@spotify/backstage-plugin-rbac@0.8.11`, `@spotify/backstage-plugin-insights@0.12.0`, `@spotify/backstage-plugin-skill-exchange@0.13.4`). Their `license` field is `SEE LICENSE IN LICENSE.md`, and the bundled `LICENSE.md` reads: *"# Commercial License / Copyright (c) 2022 Spotify. All rights reserved. / This software is part of the 'Spotify Plugins for Backstage' bundle… To use it, you must obtain a license and agree to the License Terms."*

They are closed-source literally: unpacking `@spotify/backstage-plugin-soundcheck-backend@0.31.1` yields only compiled `dist/*.cjs.js`, `.d.ts` declarations and Knex migrations — the sole file under `src/` is a Liquid email template. No sources, no sourcemaps.

Enforcement lives in `@spotify/backstage-plugin-core-node`. Configuration is `spotify.licenseKey: <your_license_key_here>` in `app-config.yaml`, and the docs tell you to test connectivity with `nc -vz backstage-api.spotify.com 443` ([getting started](https://backstage.spotify.com/docs/plugins/getting-started/index.md)). The compiled bundle contains the literal endpoint `https://backstage-api.spotify.com/license/validate`, a hard-coded base64 public key, and logic that reads `config.getOptionalString('spotify.licenseKey')`, splits the key on `.` and **verifies the signature locally when the key `hasOfflineSupport`, otherwise POSTs it to the validate endpoint** — results cached with a TTL and refreshed on an interval, resolving to a `LicenseState` of `NO_KEY_DEFINED`, `TRIAL` or `VALID` with an expiry. So: online validation by default, signed offline keys as the air-gapped fallback. The terms forbid "sharing any license keys, moving, changing, disabling, or circumventing the license key functionality" and "reverse-engineering, decompiling, disassembling, modifying, creating derivative works" ([terms](https://backstage.spotify.com/spotify-plugins-for-backstage-terms)).

**Spotify Portal is account-based SaaS, not a separate binary.** "Portal is a fully managed SaaS product hosted by Spotify," sold direct or via AWS Marketplace with "AWS handl[ing] billing and invoicing as the merchant of record," and "Your Portal subscription includes the same product, features, and SLAs whether you purchase through AWS Marketplace or directly through Spotify" ([aws-marketplace docs](https://backstage.spotify.com/docs/portal/aws-marketplace.md)). The Portal docs contain no self-hosting, Docker or deployment page. Portal customers *can* publish their own plugins into their instance's registry using a Spotify-issued publishing token plus a `publishConfig` registry entry and `.yarnrc.yml` `npmAuthToken` ([publishing-plugins](https://backstage.spotify.com/docs/portal/portal-plugins/publishing-plugins)) — a private registry for *customer* code, not the channel for Spotify's own plugins.

**The `backstage/backstage` repo contains no proprietary directory — verified.** The top level is `packages/ plugins/ workspaces/ contrib/ docs/ microsite/ scripts/ beps/ docs-ui/` plus config; no `ee/`, `enterprise/`, `premium/` or `proprietary/` path exists anywhere in the 12,172 tracked files. The only per-file licence deviations are the two NOTICE-attributed third-party components, both open source.

**Roadie** gates by account: it hosts and upgrades instances ("we upgrade every Backstage instance approximately once per week"), and the platform is not self-hostable per its docs, though *customer* plugins may be self-hosted "on a static web host, S3, or even localhost." Roadie contributes back by its own account: "We've created 12+ open-source Backstage plugins which are free for the community" ([roadie.io/blog/10-reasons-to-get-backstage-from-roadie](https://roadie.io/blog/10-reasons-to-get-backstage-from-roadie/)). **Frontside** has no gating mechanism — services only, contributing upstream on the client's behalf.

#### Pricing unit

**Roadie is the only vendor here publishing numbers.** As published, September 2026 ([roadie.io/pricing](https://roadie.io/pricing/)): **Teams — "$24 per dev/month"**, minimum "50 to 150 developers. Unlimited entities."; **Growth — "Custom"**, "100 developers or more. Unlimited entities."; **Enterprise Context — "Custom"**, "Per organization." Note what is *not* metered: entity count is explicitly unlimited on both developer-portal tiers, so a catalog-shaped product is priced by the human, not by the object. Both developer-portal plans are labelled **"Existing subscribers only"** as published, September 2026. Free-trial terms: **not verified**.

**Spotify Plugins bundle: per seat, annual, price unpublished.** The FAQ describes "an annual subscription with customers gaining access to all plugins offered in the bundle," with "pricing flexibility based on individual parameters like Backstage usage and capacity within an organization" ([FAQs](https://backstage.spotify.com/faqs/)) — but the *binding* definition is in the terms: fees are based on **"the number of individual user seats that you have purchased from Spotify"** ([terms](https://backstage.spotify.com/spotify-plugins-for-backstage-terms)). No dollar figure is published anywhere on Spotify's sites — **not verified**, as of September 2026. It is bundle-only: "At this time, the Spotify plugins are only available to purchase via the bundle."

**Spotify Portal: no published price**, sold through sales or AWS Marketplace private offers — "Work with your Spotify account executive to align on pricing, contract duration, and any custom terms," and "Free trials are not currently available through the AWS Marketplace listing." Marketing offers only "Get free access to try Spotify Portal for yourself" with no stated limits or duration; any claim of a durable Portal *free tier* (as opposed to a sales-gated trial) is **not verified**, and Portal's pricing unit is **not verified**.

**Spotify Confidence: published, and priced per exposed user rather than per seat** (as published, September 2026, [confidence.spotify.com/pricing](https://confidence.spotify.com/pricing)): Free $0 (75,000 credits/mo), Growth Starter **$449/mo** (750,000), Growth Pro **$749/mo** (1,500,000), Growth Max **$999/mo** (2,500,000), Enterprise custom/annual. "One credit = one unique user exposed to an experiment in a given month"; feature flagging is free at any scale and **seats are unlimited on every tier**. Top-ups 150,000/$129, 500,000/$299, 1,000,000/$549; credits reset each cycle and do not roll over. Worth noting as the counter-example: when Spotify prices a product where usage rather than headcount is the cost driver, it drops seats entirely.

**Frontside: no published price** — tiers are scoped by client team size and by counts of sessions, reviews and upstream changes per period, implying a retainer, but no rate is published (**not verified**).

#### Did the line move?

**2020 — open-sourced and donated; the licence has never moved.** Backstage was "accepted to CNCF on September 8, 2020" ([cncf.io/projects/backstage](https://www.cncf.io/projects/backstage/)), announced on the project blog on 23 September 2020 with breadth of input as the stated reason: "By making Backstage open source, we can build it with people working inside a variety of engineering organizations all over the world," noting roughly "40% of pull requests are now coming from external, non-Spotify, contributors" ([announcement](https://backstage.io/blog/2020/09/23/backstage-cncf-sandbox/)). It "moved to the Incubating maturity level on March 15, 2022" and — contrary to common assumption — **is still Incubating, not Graduated, as of September 2026** ([CNCF project page](https://www.cncf.io/projects/backstage/); [backstage.io/docs/overview/background](https://backstage.io/docs/overview/background/) states "Backstage is currently in the Incubation phase").

**15 December 2022 — the paid line is drawn, and Spotify explains why.** The bundle launched in open beta with five plugins: Soundcheck, RBAC, Skill Exchange, **Pulse**, Insights ([launch post](https://backstage.spotify.com/blog/now-available-spotify-plugins-for-backstage)). The stated reasoning, quoted from the [FAQ](https://backstage.spotify.com/faqs/):

> "These plugins are designed to complement the open source platform and plugins you already enjoy… We hope that by adding paid plugins from Spotify to the other commercial services surrounding Backstage, the platform becomes more valuable to current adopters, we broaden Backstage's reach to new adopters, and we create a more diverse ecosystem for the community at large. **The bundle subscription is also how we see Spotify continuing to sustain its investment in Backstage.** Revenue generated from the bundle can be reinvested into developing and releasing more plugins — including more of the 200+ plugins we use internally — and evolving Backstage further."

And on staying closed:

> "No, these plugins are closed source and the license is owned by Spotify. We are always open to feedback and feature requests, but in order to provide the quality of support, we believe they must remain this way. If you have a feature request, please let us know or contribute it to the open source community."

**Pulse quietly left the bundle.** Five plugins at launch, four on the bundle page today, and there is no `@spotify/backstage-plugin-pulse*` package in the 40-package `@spotify` npm scope; the release notes never mention it. **Reason: not verified.** A separate, documented consolidation: Soundcheck's Tech Health got a dismissible sunset banner in v1.44.0 (October 2025) and the "deprecated Tech-Health service" was removed in v1.47.0 (January 2026), "with all functionality now provided through Tech Insights."

**30 April 2024 — Portal announced** as "a full-featured IDP designed by Spotify, that's both fast to get up and running, and easy to maintain," set up "in less than five minutes, with no coding required," alongside newly-introduced Spotify Enterprise Support ([Spotify engineering](https://engineering.atspotify.com/2024/4/supercharged-developer-portals)). **22 October 2025 — Portal GA** ([GA webinar post](https://backstage.spotify.com/discover/blog/spotify-portal-ga-webinar-october-2025/)), with the free trial bundling AiKA, Data Experience, Soundcheck, RBAC, Skill Exchange, Insights plus Confidence. Whether the 2024 beta was ever distributed as a self-hosted image that later became cloud-only is **not verified**.

**2025/26 — the commercial surface widened without moving the OSS line.** Spotify added a **Marketplace for Backstage** for third-party commercial plugins, positioned "in parallel to the open source plugin directory" because "the solutions listed in the Marketplace have dedicated support and investment from our trusted partners," with the boundary held firm — "Partners cannot sell their plugins as part of the Spotify Plugins for Backstage bundle." Portal absorbed genuinely new proprietary categories with no upstream equivalent (Fleetshift, AiKA, AI Gateway). **Roadie moved too**: as published September 2026 both developer-portal tiers are marked "Existing subscribers only" while the site leads with the Context Graph / "Enterprise Context" product — a repositioning away from selling hosted Backstage by the seat.

**Net: no feature has moved from paid back to free, and no feature has moved from upstream Apache-2.0 into a paid tier.** The four named plugins were never open source; nothing was removed from the OSS repo to create them. **Spotify has published no policy statement committing to what will remain free** — the closest is the "complement the open source platform" framing above. Any stronger commitment is **not verified**.

#### Inbound contribution licensing

**DCO, no CLA — verified three ways, and this is the structurally important one.** `CONTRIBUTING.md`: *"As with other CNCF projects, Backstage has adopted a [Developers Certificate of Origin (DCO)](https://developercertificate.org/). A DCO is a lightweight way for a developer to certify that they wrote or otherwise have the right to submit code or documentation to a project,"* with `git commit -s` instructions ([CONTRIBUTING.md](https://github.com/backstage/backstage/blob/master/CONTRIBUTING.md)). The repo root carries verbatim **DCO 1.1**, Linux Foundation copyright ([DCO](https://raw.githubusercontent.com/backstage/backstage/master/DCO)). `.github/` contains no CLA-bot config (its contents are CODEOWNERS, ISSUE_TEMPLATE, PULL_REQUEST_TEMPLATE.md, advanced-issue-labeler.yml, codecov.yml, copilot-instructions.md, issue-labeler.yml, labeler.yml, renovate.json5, vale, workflows), and none of the 49 workflow files is a CLA or EasyCLA workflow. Live enforcement on PR #35541 shows a check literally named **`DCO`** and no `cla-assistant` / `EasyCLA` / `license/cla` check; recent merged commits carry `Signed-off-by:` trailers. **No copyright assignment, no relicensing grant to Spotify or anyone else.**

**What the CNCF Charter forces.** The IP Policy section of the [CNCF Charter](https://github.com/cncf/foundation/blob/main/charter.md) requires that "All outbound code will be made available under the Apache License, Version 2.0," that projects use OSI-approved licences, and that "All new inbound code contributions to the CNCF shall be accompanied by a Developer Certificate of Origin sign-off." Critically, it also says: **"Each project shall determine whether it will require use of an approved CNCF CLA"** — the DCO is mandatory, a CLA is optional and per-project. Backstage took the DCO-only option. The charter further requires a project to "Have ownership of its trademark and logo assets transferred to the Linux Foundation or a Linux Foundation project hosting entity"; that the Backstage marks specifically were assigned is implied by that requirement but no explicit assignment record was found — **not verified**.

**How Spotify can then ship proprietary plugins: purely by separation of trees, with no right over contributors' code.** Nothing in the Apache-2.0 repo is proprietary; the paid plugins live in a wholly separate closed tree shipped as compiled artefacts under "Commercial License … All rights reserved"; they attach through the same public extension points any third-party plugin uses. Apache-2.0 already permits proprietary derivative works, so **Spotify needs no CLA and gains no special privilege** — and **any contributor or competitor has the identical right**, which is exactly what Roadie, Frontside and the Marketplace partners exercise. Whether a Spotify-owned CLA applied to pre-donation (pre-September-2020) contributions, and what changed at donation, is **not verified**.

---

### Grafana

#### Where the line is

Grafana Labs' own feature list for Grafana Enterprise is unusually clean about which *categories* it reserves. Per [grafana.com/docs/grafana/latest/introduction/grafana-enterprise/](https://grafana.com/docs/grafana/latest/introduction/grafana-enterprise/) and [grafana.com/products/enterprise/](https://grafana.com/products/enterprise/), Enterprise-only means:

- **Enterprise identity**: SAML authentication; "Enhanced LDAP" with active synchronisation; **Team sync** across every auth provider Grafana supports (Auth Proxy, Entra ID, GitHub, GitLab, Google, LDAP, Okta, SAML, generic OAuth); SCIM user and team provisioning; protected roles. Basic OAuth and basic LDAP bind-auth remain in OSS — the split is *authentication is free, directory synchronisation and SAML are paid*.
- **Authorization**: fine-grained **role-based access control**, and **data source permissions** (restricting which teams may query which data source). OSS ships only the three fixed org roles (Viewer/Editor/Admin).
- **Reporting and export**: scheduled **PDF reporting** and emailed dashboard export; **custom branding**.
- **Retention/history/insight**: **Usage insights** dashboards, **recorded queries** (persisting a data-source query's results as a time series so you get history a stateless data source cannot give you), presence indicators, and enhanced dashboard search.
- **Compliance**: **auditing**, request security (outbound request restrictions), Vault integration for secrets, and settings updates without a server restart.
- **Connectors**: 30+ proprietary **Enterprise data source plugins** — Splunk, Datadog, Dynatrace, ServiceNow, New Relic, AppDynamics, Oracle, Snowflake and others.
- **Support**: 24x7x365 support with a 2-hour critical-response SLA, long-term support of a deployed version, dedicated TAM, custom training, roadmap assurance, and **indemnification**.

That is the full canonical set — SSO, RBAC, audit, retention, reporting, support, compliance — with the one addition that Grafana also puts *proprietary connectors to other vendors' commercial products* behind the line. The connector category is worth noting because it is revenue that costs the OSS project nothing: the plugins are separate artifacts, not core code.

Notably **alerting is not behind the line** — Grafana Alerting is in OSS — and neither is dashboarding, provisioning, or the plugin SDK.

#### Gating mechanism

Grafana is the clearest example of **one binary, feature-flagged by a signed licence token**, with the proprietary source in a repo the public cannot see.

- The default download is **Grafana Enterprise**, not Grafana OSS. The install docs state plainly: *"The recommended and default edition of Grafana is Grafana Enterprise. It is free and includes all the features of the OSS edition"* ([docker install docs](https://grafana.com/docs/grafana/latest/setup-grafana/installation/docker/)). Two images exist — `grafana/grafana` (OSS) and `grafana/grafana-enterprise` — but the Enterprise image is a superset that runs indefinitely in OSS mode until a licence is applied. There is no separate purchase to *obtain* the binary.
- **The licence is a signed JWT.** `license.jwt` is uploaded through the admin UI, dropped in the data directory (`/var/lib/grafana`), or inlined as `license_text` in config. Grafana obtains **short-lived license tokens renewed roughly every 24 hours** by calling Grafana's API, and at startup **compares the licence URL to the instance's configured `root_url`** — exact match required including the trailing slash, HTTPS only, no localhost or wildcards. That binding is what makes the licence non-transferable between instances ([enterprise-licensing docs](https://grafana.com/docs/grafana/latest/administration/enterprise-licensing/)).
- **Expiry degrades rather than kills**: on expiry, custom RBAC roles can no longer be created or modified, new reports are blocked (existing ones keep running), external email sharing is revoked, and Enterprise plugins may stop. The active-user limit stops being enforced immediately; the concurrent-session limit persists for seven more days. Nothing about the OSS feature set stops working.
- **The OSS repo contains no proprietary source, but it does contain the hook.** `github.com/grafana/grafana` carries `pkg/extensions/main.go`, which is nothing but:

  ```go
  package extensions
  // Imports used by Grafana enterprise are in enterprise_imports.go (behind a build tag).
  var IsEnterprise bool = false
  ```

  Alongside it sits `pkg/extensions/enterprise_imports.go`, marked `//go:build never` and headed *"Code generated by scripts/ci/generate-enterprise-imports; DO NOT EDIT"* — a ~1500-line list of blank imports (AWS KMS, Azure Key Vault, `beevik/etree` for SAML, `go-jose`, `licensemanager`, …). It compiles into nothing in the OSS build; its job is to pin the OSS `go.mod` to every dependency the *private* enterprise build needs, so the two trees resolve to identical module versions. So: no proprietary code in the OSS repo, but a machine-generated shadow of the proprietary build's dependency graph, and a `false` constant the closed build overwrites.
- `github.com/grafana/grafana-enterprise` returns 404 to anonymous requests — the proprietary tree is a **private repo**, not a directory in the public one.
- **Licence scope is AGPLv3 for the core, Apache-2.0 for the edges.** [`LICENSING.md`](https://github.com/grafana/grafana/blob/main/LICENSING.md) states the default licence is AGPL-3.0-only, but carves out `packages/grafana-data/`, `packages/grafana-runtime/`, `packages/grafana-ui/`, `packages/grafana-e2e-selectors/`, `kinds/`, `pkg/kinds/`, `pkg/kindsys/`, `pkg/registry/schemas/`, `packaging/` and `grafana-mixin/` as Apache-2.0. That is deliberate: the packages a third party needs to *write a plugin* are permissive, so the copyleft never reaches plugin authors — including Grafana's own proprietary plugins.

#### Pricing unit

- **Self-managed Grafana Enterprise: no price is published.** [grafana.com/pricing/](https://grafana.com/pricing/) shows only Cloud tiers, [grafana.com/products/enterprise/](https://grafana.com/products/enterprise/) has no pricing section, and [grafana.com/get/](https://grafana.com/get/) routes self-managed Enterprise to sales. A commonly repeated "$55/user/month" figure surfaced in search but could not be found on any Grafana page — **not verified**.
- **The self-managed licence unit is nevertheless documented as the active user.** The licensing docs define an active user as one who "signed in to Grafana within the last 30 days," rolling; Grafana **counts only the total number of active users regardless of role** — a 150-user licence covers any mix of Admins, Editors and Viewers. Across multiple production instances "your licensed users form a shared pool," and one human signing into two instances consumes capacity on each ([enterprise-licensing docs](https://grafana.com/docs/grafana/latest/administration/enterprise-licensing/)).
- **Grafana Cloud is priced per-resource, not per-seat**, and this is where Grafana publishes actual numbers (as published, September 2026, [grafana.com/pricing/](https://grafana.com/pricing/)): Metrics **$6.50 per 1k series** (Pro) / "as low as $3 per 1k series" (Enterprise); Logs, Traces and Profiles at **$0.400/GB write, $0.100/GB retain, $0.050/GB process**; Kubernetes Monitoring **$0.0100 per host hour**; Application Observability **$0.025 per host hour**; Frontend Observability **$0.750 per 1k sessions**; Synthetics **$5.00 per 10k API executions / $50.00 per 10k browser executions**; k6 performance testing **$0.150 per virtual-user hour**; Grafana Assistant **$20 per active AI user**; IRM **$20.00 per active IRM user**. The one genuinely seat-shaped Cloud line is **Grafana Visualization at $8.00 per active user**. Cloud Enterprise carries a **$25,000/year minimum commitment**.

The pattern: Grafana charges self-managed by the *seat* and cloud by the *resource*, and only publishes the latter.

#### Did the line move?

Twice, in opposite directions, and one product made a full round trip.

**The 2021 relicense (Apache-2.0 → AGPLv3).** Announced at [grafana.com/blog/grafana-loki-tempo-relicensing-to-agplv3/](https://grafana.com/blog/2021/04/20/grafana-loki-tempo-relicensing-to-agplv3/) for Grafana, Loki and Tempo. The stated reason was explicitly peer-driven — "almost every at-scale open source company that we admire (such as Elastic, Redis Labs, MongoDB, Timescale, Cockroach Labs, and many others) has evolved their license regime" — framed as balancing value creation against value capture, and defended on the ground that AGPLv3 is still OSI-approved: *"Being open source will always be at the core of who we are, and we believe that adopting AGPLv3 allows our community and users to by and large have the same freedoms that they have enjoyed since our inception."* Crucially, **plugins, agents and certain libraries stayed Apache-licensed** — matching the `LICENSING.md` carve-outs above. No feature moved from free to paid at the relicense; the licence changed, the line did not.

**Grafana OnCall: free → OSS → gone.** In 2022 Grafana *open-sourced* on-call management, announcing Grafana OnCall OSS at GrafanaCONline as "the latest open source project from Grafana Labs, joining Grafana, Grafana Loki, Grafana Tempo, and Grafana Mimir" ([announcement](https://grafana.com/blog/introducing-grafana-oncall-oss-open-source/)) — a paid capability made free. Then on **11 March 2025** OnCall OSS went into maintenance mode, to be **archived on 24 March 2026**, with its Cloud Connection (SMS, phone and push delivery) deprecated the same day ([grafana.com/blog/grafana-oncall-maintenance-mode/](https://grafana.com/blog/grafana-oncall-maintenance-mode/), [docs](https://grafana.com/docs/oncall/latest/set-up/open-source/)). The stated reason is engineering cost, not monetisation: *"The software engineers working on both OnCall and Incident have long considered as tech debt the overhead of maintaining two closely coupled yet separate codebases."* The replacement, **Grafana Cloud IRM, is Cloud-only** — there is no self-hosted successor. Grafana kept the code AGPLv3 and invited a fork, promising "best reasonable effort" support to anyone who carries it forward.

That is the decision-relevant shape: Grafana did not *move a feature behind a paywall*; it **withdrew the free deployment target** and left only a hosted paid one. The end state for a user is the same as a paywall, and the vendor never had to break a stewardship promise to get there — because Grafana never made one.

#### Inbound contribution licensing

**Grafana requires a CLA — an Apache-style broad grant including sublicensing rights — and says so in the repo.** `CONTRIBUTING.md` line 127 onward: *"Before we can accept your pull request, you need to [sign our CLA](https://grafana.com/docs/grafana/latest/developers/cla/). If you haven't, our CLA assistant prompts you to when you create your pull request."* ([CONTRIBUTING.md](https://github.com/grafana/grafana/blob/main/CONTRIBUTING.md))

The operative clause, from the [CLA text](https://grafana.com/docs/grafana-cloud/learn-and-build/developer-resources/contribute/cla/):

> "You hereby grant to Grafana Labs and to recipients of software distributed by Grafana Labs a perpetual, worldwide, non-exclusive, no-charge, royalty-free, irrevocable copyright license to reproduce, prepare derivative works of, publicly display, publicly perform, **sublicense**, and distribute Your Contributions and such derivative works."

It is **not a copyright assignment** — "Except for the license granted to Grafana Labs and recipients of software distributed by Grafana Labs, You reserve all right, title, and interest in and to Your Contributions" — but the sublicense right is what lets Grafana ship contributed code under AGPLv3, under Apache-2.0 in the carved-out packages, and under a proprietary licence in the Enterprise build, without going back to contributors. The definition of "You" covers both "the copyright owner" and a "legal entity," so **one document serves as both the individual and corporate CLA**. Grafana has stated it based the agreement on the Apache Software Foundation's CLA "because of its popularity and familiarity to community members," and that it "clearly spells out the license terms and is balanced between the contributor's interests and Grafana Labs' rights to relicense the changes" — that sourcing is from a Grafana-authored page surfaced in search but the specific page could not be re-fetched at the canonical URL, so treat the *quoted rationale* as **not verified** while the CLA text itself above is verified.

Grafana additionally requires **signed commits on all Grafana Labs repositories effective 22 June 2026** (CONTRIBUTING.md) — provenance hardening, separate from the licence grant.

The mechanism worth copying: because outside contributions land only in the AGPL tree and the proprietary tree is a private repo, the CLA's sublicense right is a *belt* Grafana rarely needs — the *braces* are that no external contributor can touch enterprise code in the first place.

---

### GitLab

#### Where the line is

Three tiers as published September 2026 on [about.gitlab.com/pricing/](https://about.gitlab.com/pricing/): **Free** ($0/user/month), **Premium** ($29/user/month billed annually), **Ultimate** (list price no longer published — "Get custom pricing," contact sales).

**Free** (self-managed and SaaS) includes source control, built-in CI/CD, wiki, time tracking, container scanning, push rules, code quality reports, protected environments, remote development workspaces, and — notably — **Static Application Security Testing**. Free is not a crippled tier: the stewardship handbook promises "the open source codebase will have all the features that are essential to running a large 'forge' with public and private repositories" and "will not contain any artificial limits (repositories, users, size, performance, requiring a trademarked header, etc.)."

**Premium** adds, per the pricing table: advanced CI/CD (merge trains, parent-child pipelines, DAGs), team project management (epics, roadmaps, boards), **SLA management** with countdown timers and escalation policies, code owners, multiple merge-request approvers and approval rules, **LDAP group synchronisation**, **SAML group sync**, **group and project audit events**, GitLab Geo, and **priority support**.

**Ultimate** adds the security and compliance stack: dynamic application security testing, fuzz testing, IaC scanning, software composition analysis, **vulnerability management**, security dashboards, **compliance dashboards and custom compliance frameworks**, security policies, software supply chain security, strategic portfolio management, value stream management, DORA4 metrics, insights and health reporting, **unlimited guest users**, and **custom admin roles**.

The category split is precise and instructive:

- **SSO/auth**: basic LDAP bind and OmniAuth are Free; **LDAP synchronisation is Premium** ([docs tier badge: "Tier: Premium, Ultimate | Offering: GitLab Self-Managed"](https://docs.gitlab.com/administration/auth/ldap/ldap_synchronization/)), and assigning a custom admin role to an LDAP group is Ultimate. Same category-shaped cut as Grafana: authenticate free, *synchronise* paid.
- **Audit**: audit events carry the badge "Tier: Free, Premium, Ultimate" — but *"Successful sign-in events are the only audit events available at all tiers."* Group and project audit events are Premium/Ultimate ([docs](https://docs.gitlab.com/user/compliance/audit_events/)). So the free tier gets the *existence* of the feature and one event type, which is a more defensible cut than removing it entirely.
- **Compliance**: "Limited" in Premium, "Custom" in Ultimate.
- **Multi-team**: guest users are "limited access" in Premium and unlimited in Ultimate; **on GitLab.com only**, Free is capped at **5 users per top-level group**. That cap is absent from the self-managed Free column — GitLab moved the line on SaaS while holding it on self-managed, which is exactly what the stewardship promise reserves the right to do (see §4).

GitLab publishes its *criterion* for the line, which none of the other three do: *"To determine what is open source and what not we ask ourselves: Who cares the most about the feature. If the likely buyer is an individual contributor the feature will be open source, otherwise it will be source-available (proprietary)."* ([stewardship handbook](https://handbook.gitlab.com/handbook/company/stewardship/))

#### Gating mechanism

GitLab is the canonical **source-available directory inside the OSS repo**, plus a runtime licence check, plus a mechanically-generated downstream FOSS repo. All three layers are load-bearing.

**Layer 1 — the `ee/` directory and its own LICENSE.** The root [`LICENSE`](https://gitlab.com/gitlab-org/gitlab/-/blob/master/LICENSE) of `gitlab-org/gitlab` is a licence *router*:

> "All content that resides under the `ee/` directory of this repository, if that directory exists, is licensed under the license defined in `ee/LICENSE`. All content that resides under the `jh/` directory of this repository, if that directory exists, is licensed under the license defined in `jh/LICENSE`. … Content outside of the above mentioned directories or restrictions above is available under the 'MIT Expat' license."

`ee/LICENSE` is the **GitLab Enterprise Edition licence**, and it is proprietary in the strong sense:

> "This software and associated documentation files (the 'Software') may only be used in production, if you (and any entity that you represent) have agreed to, and are in compliance with, the GitLab Subscription Terms of Service … and otherwise have a valid GitLab Enterprise Edition subscription for the correct number of user seats. Subject to the foregoing sentence, you are free to modify this Software and publish patches to the Software. **You agree that GitLab and/or its licensors (as applicable) retain all right, title and interest in and to all such modifications and/or patches** … Notwithstanding the foregoing, you may copy and modify the Software for development and testing purposes, without requiring a subscription. … **Subject to the foregoing, it is forbidden to copy, merge, publish, distribute, sublicense, and/or sell the Software.**"

Two things stand out. First, EE source is *readable and patchable but not usable in production without a subscription* — source-available, not open source. Second, the licence itself claims ownership of outside modifications, which is how GitLab gets contribution rights over EE code even from people who never signed a CLA for that tree.

**Layer 2 — runtime licence checks.** Per [`ee/development/ee_features.html`](https://docs.gitlab.com/ee/development/ee_features.html), the required structure is *"Place all Enterprise Edition (EE) inside the `ee/` top-level directory. The rest of the code must be as close to the Community Edition (CE) files as possible."* Feature gating is by explicit call: `my_project.licensed_feature_available?(:feature_name)` for project/namespace scope, `License.feature_available?(:feature_name)` for instance scope, plus `Gitlab::Saas.feature_available?` and `Gitlab::Dedicated.feature_available?` for offering-scoped features. Tier membership is a data table: features are declared in `ee/app/models/gitlab_subscriptions/features.rb` and assigned to `PREMIUM_FEATURES`, `ULTIMATE_FEATURES`, or `GLOBAL_FEATURES`. So the tier of any given feature is a one-line constant, changeable without touching the feature's code — which is precisely why GitLab can promise to move features *down* a tier cheaply.

**Layer 3 — the FOSS-only build.** Setting the `FOSS_ONLY` environment variable, or simply deleting `ee/`, produces a build with no access to paid functionality. This is materialised publicly as [`gitlab-org/gitlab-foss`](https://gitlab.com/gitlab-org/gitlab-foss), whose own description reads *"GitLab FOSS is a read-only mirror of GitLab, with all proprietary code removed."* Verified against the API: the mirror has **no `ee/` directory in its tree**, and it was still being updated on 2026-09-05 — i.e. it is a live automated mirror, not an abandoned artifact. (Its root LICENSE still contains the `ee/` clause, harmlessly, guarded by the "if that directory exists" wording.)

The stewardship handbook commits to exactly this arrangement as policy, not just practice: *"We will always make it clear what is proprietary and what is open source code. This will be implemented with a separate directory for EE code and a Git repo downstream that is open source only."*

#### Pricing unit

**Per user per month, billed annually** — the purest seat model in this slice. Free $0; **Premium $29/user/month**; Ultimate custom (as published, September 2026, [about.gitlab.com/pricing/](https://about.gitlab.com/pricing/)).

Layered on top are usage units that are *not* seats, which is where GitLab has been expanding:

- **GitLab Credits at $1 per credit** — the currency for AI/agentic features. Premium includes **$12 of credits per user/month**, Ultimate **$24 per user/month**, both flagged as a limited-time promotional offer; Free gets none and must buy the add-on.
- **Compute minutes**: 400/month Free, 10,000 Premium, 50,000 Ultimate; overage at **$10 per 1,000 minutes**, one-time.
- **Storage**: 10 GiB Free, 500 GiB Premium; overage at **$5/month per 10 GiB billed annually**.
- **Enterprise Agile Planning seats at $15/user** — a cheaper non-developer seat class.
- **GitLab Flex**: "one annual commitment for seats and GitLab Credits" that can be re-allocated across plans and usage — an explicit hedge against the rigidity of pure seat pricing.

#### Did the line move?

GitLab is the only vendor of the four with a **written, dated, enumerated promise** about which direction the line may move, published in its handbook and linked from support replies.

The promises ([stewardship handbook](https://handbook.gitlab.com/handbook/company/stewardship/)):

> "When a feature is open source we won't move that feature to a paid tier. Features might be removed from the open source codebase in other cases, for example when combining features from multiple tiers into one new feature. To be clear, this promise only applies to open sourced features, features in paid tiers might move to a higher tier."

> "We won't introduce features into the open source codebase with a fixed delay, if a feature is planned to land in both it will be released simultaneously in both."

> "We never move existing features already in GitLab FOSS into a paid tier. This applies regardless of whether the feature was created by GitLab team members or the wider community of contributors."

And the reverse direction is explicitly live: *"From time to time we do open source a feature that was previously proprietary. We do this when we realize we made a mistake applying our criteria, for example when we learned that a branded homepage was an essential feature or when we brought GitLab Pages to the Community Edition."* Also: *"It is hard to get the tier right, and if we put something in a tier that is too high we won't hesitate to open-source it or move it to a lower tier."*

The carve-out that does the real work: **the promise covers the open source codebase only, not SaaS.** *"Our stewardship promise applies only to the GitLab open source codebase, not to GitLab, Inc services such as GitLab Software as a Service (SaaS). For GitLab SaaS, we may add limits around usage (e.g., storage, compute, traffic) or proxies for usage (e.g., number of users in a group) as we seek to optimize costs and revenues."* This is what licenses the 5-users-per-top-level-group cap on GitLab.com Free and the SaaS storage/compute caps, while self-managed Free stays uncapped. The handbook also states the promise "allows other companies and organizations to provide a SaaS offering based on the GitLab open source codebase" — the opposite of the Grafana/Elastic anti-hyperscaler posture.

GitLab explicitly rejects the time-delay open-core variant: *"An example of a limited time release strategy is the Business Source License that keeps features proprietary for 3 years. At GitLab we want to give everyone access to most of the features (and all the essential ones) at the date they are announced."*

**Actual moves.** Price: Premium went **$19 → $29/user/month effective 3 April 2023**, GitLab's first increase in more than five years, with existing customers held at a $24 transition price until 2 April 2024 ([blog, 2 March 2023](https://about.gitlab.com/blog/gitlab-premium-update)). The stated justification was accumulated value — Premium "added more than 400 features" since February 2018. Tiers: Bronze and Starter were **discontinued on 26 January 2021**, collapsing five tiers to three; the stated reason was that the tier "does not meet the hurdle rate expected from a tier and is limiting investment" (from GitLab's own announcement at about.gitlab.com/blog/2021/01/26/new-gitlab-product-subscription-model/ — the page did not render on fetch, so treat the exact quote as **not verified**; the date and the fact of the change are corroborated by the current three-tier pricing page). Ultimate's list price has since disappeared from the pricing page in favour of "Get custom pricing" — a real change in disclosure whose announcement I could not locate: **not verified** as to when or why.

#### Inbound contribution licensing

GitLab runs a **split regime keyed to the directory**, which is the single most transferable mechanism in this slice. From [about.gitlab.com/community/contribute/dco-cla/](https://about.gitlab.com/community/contribute/dco-cla/):

> "All contributions to GitLab are subject to the Developer Certificate of Origin (DCO), or the Corporate or Individual Contributor License Agreement (CLA), depending on where you're contributing and on whose behalf, unless otherwise agreed with GitLab in writing."

> "Individual and Corporate contributions to MIT-licensed code are subject to the DCO. Individual contributions to code in the `gitlab-org/gitlab/ee` directory are subject to the Individual CLA. Corporate contributions to code in the `gitlab-org/gitlab/ee` directory are subject to the Corporate CLA."

So: **DCO for the MIT tree, CLA for the `ee/` tree.** GitLab does not need a broad grant over the open-source code — MIT already permits everything, including proprietary redistribution — so it asks contributors only to certify provenance there. It asks for the strong grant *only* where it needs to relicense: the proprietary directory.

Both CLAs are Apache-derived and live in the repo itself, at [`doc/legal/individual_contributor_license_agreement.md`](https://gitlab.com/gitlab-org/gitlab/-/blob/master/doc/legal/individual_contributor_license_agreement.md) and `doc/legal/corporate_contributor_license_agreement.md`. The operative clause is identical in both:

> "Subject to the terms and conditions of this Agreement, You hereby grant to GitLab Inc. and to recipients of software distributed by GitLab Inc. a perpetual, worldwide, non-exclusive, no-charge, royalty-free, irrevocable copyright license to reproduce, prepare derivative works of, publicly display, publicly perform, **sublicense**, and distribute Your Contributions and such derivative works."

with the same reservation of title as Grafana's — "Except for the license granted herein to GitLab Inc. and recipients of software distributed by GitLab Inc., You reserve all right, title, and interest in and to Your Contributions." The two CLAs differ only in the definition of "Contribution" (the Corporate CLA's is narrower: "the code, documentation or other original works of authorship") and in who signs.

A third, belt-and-braces layer: the `ee/LICENSE` itself asserts that GitLab retains "all right, title and interest in and to all such modifications and/or patches" made to EE code — so even a patch published by someone who never signed the CLA is claimed. And GitLab requires disclosure of AI-generated contributions, with the stated reason being IP defensibility: *"Since community contributions are added directly to GitLab-owned IP, we have added this disclosure requirement to help GitLab protect our IP in the future as more AI-related regulations and legal decisions come into effect."*

---

### Kong

#### Where the line is

Kong is the messiest of the four to characterise, because Kong has spent five years *dissolving* the OSS-vs-Enterprise gateway distinction into a Konnect plan distinction, and the docs no longer publish a clean edition feature matrix. What is verifiable:

**Free / Apache-2.0 Kong Gateway** ([github.com/Kong/kong LICENSE](https://github.com/Kong/kong/blob/master/LICENSE), Apache-2.0, "Copyright 2016-2026 Kong Inc.") gives you the proxy, the Admin API, declarative config, the standard bundled Lua plugin set, and — since 2023 — **Kong Manager**, the admin GUI, which had previously been Enterprise-only.

**Enterprise-licensed** features, named by Kong's own pricing page and docs:

- **SSO**, **RBAC**, and **audit logging** — the pricing page lists exactly these three as what the Enterprise Konnect plan adds over Plus ([konghq.com/pricing](https://konghq.com/pricing)). That is a striking confirmation of the category thesis: Kong's own marketing reduces "Enterprise" to identity + authorization + audit.
- **Workspaces** (multi-tenant isolation of gateway config), admins, and RBAC roles/permissions. Kong's RBAC quickstart assumes "a valid license in the environment variable `KONG_LICENSE_DATA`" ([developer.konghq.com/gateway/entities/rbac/](https://developer.konghq.com/gateway/entities/rbac/)), and Kong Manager auth is meaningless without RBAC — "otherwise, anyone who can log in to Kong Manager can perform any operation available on the Admin API."
- **Dev Portal**.
- **Vitals** (analytics).
- Enterprise plugins, including **OpenID Connect** (the SSO plugin — named explicitly in Kong's own 2.3 announcement as requiring a paid licence), **LDAP Authentication Advanced**, **Mutual TLS Authentication**, **OAuth 2.0 Introspection**, **Rate Limiting Advanced** (sliding-window), **Vault Authentication**, and **Metering & Billing** (explicitly "a licensed add-on through Konnect"). The Plugin Hub at [developer.konghq.com/plugins/](https://developer.konghq.com/plugins/) does not carry visible per-plugin licence badges in the fetched page, so the free/paid status of the *complete* plugin list is **not verified**; the enterprise status of the plugins named here is verified from the pages and announcements cited.
- **Dynamic plugin ordering** — a specific capability Kong moved behind the licence in the 3.x line.
- **FIPS 140-2 builds** are Enterprise-only artifacts ([install docs](https://developer.konghq.com/gateway/install/)).

A claim of "29 additional plugins" in Kong Enterprise appeared in search results attributed to Kong material but could not be confirmed on a fetched Kong page: **not verified**.

#### Gating mechanism

Kong is the **separate proprietary build** case, cleanly. Unlike GitLab, the OSS repo contains **no proprietary directory at all**: the top level of `Kong/kong` is `bin/ kong/ spec/ build/ changelog/ autodoc/ scripts/ t/` plus config and CI — there is no `ee/`, no source-available tree, no licence-check stub. Everything proprietary lives outside the public repo.

Distribution is genuinely two artifacts:

- **OSS**: Docker Hub `kong/kong`, described by Kong as "Kong OSS images built by Kong."
- **Enterprise**: Docker Hub `kong/kong-gateway`, described as "Kong Gateway Enterprise — The world's most popular API gateway"; `.deb`/`.rpm` packages named **`kong-enterprise-edition`** ([install docs](https://developer.konghq.com/gateway/install/)).

Licence enforcement on the Enterprise build is a **signed JSON licence loaded at startup, in a documented precedence order** ([developer.konghq.com/gateway/entities/license/](https://developer.konghq.com/gateway/entities/license/)):

1. the `KONG_LICENSE_DATA` environment variable,
2. the default path `/etc/kong/license.json`,
3. the path in `KONG_LICENSE_PATH`,
4. a licence deployed via the `/licenses` Admin API endpoint.

The distribution asymmetry matters operationally: a licence deployed through the **`/licenses` Admin API is propagated by the control plane to every data plane** in a hybrid cluster; a licence supplied by env var or file is **not** — "you must add the license to each data plane node, and each node must start with the license."

Failure mode is **read-only, not shutdown**. On expiry: "All entity configurations become read-only" — the Admin API and all interfaces go read-only until a valid licence is applied, while the proxy keeps serving previously configured traffic unchanged. Only `/licenses` and the keyring recovery endpoints (`/keyring/recover`, `/keyring/import`, `/keyring/import/raw`) stay writable, so the gateway can be un-bricked. In DB-less mode and under the Kubernetes Ingress Controller, new node deployments and restarts fail outright.

**Kong requires a CLA, enforced by a bot** — see §5.

#### Pricing unit

Kong's published unit is the **gateway control plane per month**, plus usage add-ons — **not** seats, and not nodes. As published, September 2026, [konghq.com/pricing](https://konghq.com/pricing):

- **Free trial**: "$0 for 30 days — no credit card required," full enterprise functionality, no gateway limits.
- **Plus**: billed monthly, "charged per Gateway per month." Includes up to **5 serverless, 2 hybrid and 2 dedicated cloud gateways**, and **1M API requests/month** of advanced analytics. Kong does not publish a headline Plus figure; it publishes the per-unit adders instead, which amounts to the same thing:
  - Serverless gateway control plane: **$25/month per control plane**
  - Hybrid gateway control plane: **$200/month per control plane**
  - Dedicated Cloud gateway control plane: **$500/month per control plane**
  - Analytics beyond 1M: **$20 per 1M API requests** (a separately-quoted "$200/month per additional 1 million API requests/month" also appears on the page — the two figures are inconsistent as fetched, so treat the exact analytics overage rate as **not verified**)
  - Additional API Developer Portal: **$200/month**
  - Published APIs: **$20/month for the first 10 total APIs, $10/month for every extra API**
  - AI Gateway model proxy: **$100/month per model**
  - Access tokens (Kong Identity): **$30 per 1M access tokens**
  - Metering and Billing: **0.4% of billing volume up to $250,000**
- **Enterprise**: custom pricing, billed annually; unlimited gateways, dev portals and service catalog entries; adds **SSO, RBAC, audit logging**, dedicated CSM/TAM and "higher SLAs."

Kong's *other* product does charge per seat: **Insomnia** is Essentials free (up to 3 users, 1,000 mock requests/month), **Pro $12/user/month**, **Enterprise $45/user/month** self-serve up to 50 users — and the Enterprise seat is defined by **SSO and SCIM**. Kong also states that plugins are "no longer priced with Free, Plus, and Premium tiers separate from the Konnect subscription — there is no extra fee to use any particular plugin," i.e. Kong retired a per-plugin pricing axis.

Self-hosted Kong Gateway Enterprise (not Konnect) pricing is **not published** — **not verified**.

#### Did the line move?

More than any other tool here, and in both directions — Kong has moved the line four separate times, and reversed once under pressure.

**2021 — Enterprise made free to download (line moved toward free).** Kong Gateway Enterprise 2.3 introduced "free mode": *"anyone can now download what we've been calling Kong Gateway Enterprise. You can do so for free today without any paywall or forms to fill out"* ([konghq.com/blog/product-releases/kong-gateway-enterprise-2-3-now-generally-available](https://konghq.com/blog/product-releases/kong-gateway-enterprise-2-3-now-generally-available)). Kong Manager came with it. What stayed paid was named explicitly: *"In order to use our OIDC plugin, Vitals and other paid Enterprise features, you'll need a paid Konnect license."* Kong also announced a naming change in the same post — it would stop calling the gateway "Enterprise" and reserve "Kong Gateway (OSS)" for the Apache-2.0 builds. Stated reason: onboarding — *"If you're just starting out with Kong, we think that this is going to be the easiest, fastest and best way for you to get started."*

**September 2023 — Kong Manager open-sourced (line moved toward free).** The admin GUI, "previously available only in Kong Enterprise," shipped in OSS ([konghq.com/blog/product-releases/kong-manager-open-source](https://konghq.com/blog/product-releases/kong-manager-open-source), 27 September 2023). Stated reason: *"a growing demand for a more visual and intuitive interface, particularly from our users who are technically adept but also value the intuitiveness of a visual interface,"* and a goal to "provide as many options as possible for open source users." The specific Gateway version was not stated in the post — **not verified**. RBAC, Workspaces, Dev Portal and Vitals did **not** come with it.

**2023 — Insomnia 8.0 removed local-only storage, then 8.3 restored it (line moved to paid, then back, under community pressure).** Insomnia 8.0 required an account and defaulted projects to cloud storage, leaving only a one-collection "Scratch Pad" local. The reaction was severe enough that Kong reversed within a point release: Insomnia 8.3 brought back "100% local storage with no cloud synchronization for your projects," and made migration from pre-8.x default to **Local Vault** rather than cloud, specifically so nobody would accidentally upload data their employer forbids ([konghq.com/blog/product-releases/insomnia-8-3](https://konghq.com/blog/product-releases/insomnia-8-3)). CTO Marco Palladino's stated reason is unusually direct: *"We learned after releasing Insomnia 8.0 that for many users using cloud storage for projects (encrypted or not) is simply not an option,"* and *"I now understand the importance of having a local-only storage option for projects that for one reason or another cannot be moved to the cloud."* This is the clearest documented case in the slice of a vendor moving the line and being forced back by users.

**3.10.0.0 onward — free mode deprecated (line moving decisively toward paid).** The Kong Gateway breaking-changes page states: *"Free mode is deprecated and will be removed in a future 3.x version of Kong Gateway Enterprise,"* at which point *"running Kong Gateway without a license will behave the same as running it with an expired license"* — i.e. read-only Admin API ([developer.konghq.com/gateway/breaking-changes/](https://developer.konghq.com/gateway/breaking-changes/)). 3.15.0.0 tightened expiry behaviour to "you can no longer change any Kong Gateway configuration. The Admin API and all interfaces become read-only until a valid license is applied." No stated reason accompanies the deprecation on the breaking-changes page — **not verified** as to rationale.

This is the important reversal of 2021: Kong gave away the Enterprise *binary* to win adoption, then, having made it the default install for many users, is withdrawing the unlicensed operating mode. Apache-2.0 `Kong/kong` is unaffected and remains genuinely free — but the free path is being pushed back onto the OSS build, which has neither the enterprise plugins nor a supported upgrade path from a free-mode Enterprise install.

#### Inbound contribution licensing

**Kong requires a CLA, enforced by cla-assistant, and does not document it in CONTRIBUTING.md.** `CONTRIBUTING.md` in `Kong/kong` contains no occurrence of "CLA," "DCO," "sign-off," "copyright" or "license" in the contribution-process sections, and the PR template does not mention it either. The requirement is nevertheless real and enforced: querying a live PR's commit statuses shows a required status context

```
context:     license/cla
description: "Contributor License Agreement is signed."
target_url:  https://cla-assistant.io/Kong/kong?pullRequest=<n>
```

alongside a `check-copyright` check. So the gate is a **cla-assistant.io** integration — a click-through CLA at PR time — plus automated copyright-header enforcement.

**The text of Kong's CLA could not be retrieved: not verified.** cla-assistant.io serves it from a gist behind an authenticated API and its web UI is a JS SPA; `konghq.com/legal` publishes customer-side agreements (Master Software License Agreement, Konnect Customer Agreement) but no contributor agreement. Whether Kong's CLA grants a sublicense/relicense right, and whether it distinguishes individual from corporate contributors, is therefore unverified — though a click-through cla-assistant CLA on a project with a closed proprietary fork is essentially always an Apache-style broad grant, that inference is not a primary source.

What is verifiable is that **Kong does not structurally need the CLA to protect its Enterprise business**: `Kong/kong` is Apache-2.0, which already permits proprietary redistribution of contributions without any additional grant, and the Enterprise tree is a wholly separate closed repo that no outside contributor touches. The CLA is provenance and patent insurance, not the mechanism that enables the proprietary edition. The proprietary edition is enabled by the *build separation*.

Kong's [`COMMUNITY_PLEDGE.md`](https://github.com/Kong/kong/blob/master/COMMUNITY_PLEDGE.md) is the closest thing to a stewardship statement, and it is notably about *responsiveness*, not about the free/paid line: it promises acknowledgement of community contributions "within a dedicated timeframe of 10 working days," warns that Kong "may not always be able to … incorporate every submitted pull request into the product," and states that PRs and issues without a response in 3 weeks are auto-closed. Its only substantive claim about the open-core relationship is *"We make many of the enhancements to Kong Gateway in the Community Edition, so our open source community directly benefits from the commercial work that we do"* — an assurance of effort, with no commitment about which features stay free.

---

### Patterns: platform and developer portals

**Three of the four put SSO behind the line, and the one that didn't is the one whose paid layer isn't a tier of the same product.** Grafana reserves SAML, SCIM and team sync; GitLab reserves LDAP synchronisation and SAML group sync; Kong's pricing page reduces its entire Enterprise plan to "SSO, RBAC, audit logging." Backstage ships SAML, generic OAuth2, Okta, Keycloak, Entra and a dozen more upstream for free — because Spotify does not sell a Backstage tier, it sells *plugins that attach to* Backstage, so there is no incentive to cripple the base. The generalisable cut inside SSO is sharper than "SSO is paid": in both Grafana and GitLab, **authenticating against a directory is free and synchronising groups from it is paid.** The customer who needs group sync has an org chart; the customer who needs bind-auth has a laptop.

**All four gate RBAC, and all four gate it at the same seam: the policy framework is free, the policy authoring surface is paid.** Backstage's permission framework ships free but has no policy UI — you write a TypeScript `PermissionPolicy` class — and that exact gap is the product Spotify's RBAC plugin and Roadie's no-code Admin UI sell. Grafana ships three fixed roles free and sells custom roles. Kong's RBAC quickstart assumes `KONG_LICENSE_DATA` is set. GitLab puts custom admin roles in Ultimate. Nobody sells authorization-as-a-concept; everyone sells not having to write it in code.

**All four gate audit, but three of them ship a deliberately degraded free version rather than nothing — and the dial they turn is retention, not capture.** GitLab's audit-events docs carry the badge "Tier: Free, Premium, Ultimate" and then state that "successful sign-in events are the only audit events available at all tiers." Backstage upstream has a full Auditor core service that emits structured security events to the logger, and Spotify's Portal sells the REST API, the filtering and the retention on top of it. Spotify Confidence spells the dial out on its pricing page: audit logs **1 month** free, **6–12 months** on Growth, **custom retention** on Enterprise. Only Grafana and Kong gate auditing outright. Leaving the capture primitive free is cheap (it is a logger call), preserves the "we don't cripple the OSS" story, and still leaves the entire compliance buyer on the paid side, because compliance is a retention-and-query problem.

**Four tools, four different enforcement mechanisms — and only GitLab puts proprietary source in the public repo.** Grafana ships one superset binary (`grafana/grafana-enterprise` is the *default* download and "is free and includes all the features of the OSS edition") unlocked by a signed JWT bound to the instance's `root_url` and auto-renewed every 24 hours, with the proprietary tree in a private repo that 404s. Kong ships a genuinely separate distribution (`kong-enterprise-edition` packages, `kong/kong-gateway` image) unlocked by a signed JSON licence loaded from `KONG_LICENSE_DATA`, `/etc/kong/license.json`, `KONG_LICENSE_PATH` or the `/licenses` Admin API — and `Kong/kong` has no `ee/`, no stub, nothing. Spotify ships compiled-only npm packages on the *public* registry under a "Commercial License, All rights reserved" `LICENSE.md`, gated by a licence key that either verifies locally against an embedded public key or POSTs to `backstage-api.spotify.com/license/validate`. GitLab alone ships readable proprietary source, and it needed three mechanisms to do it: a root LICENSE that routes by directory, a bespoke `ee/LICENSE` making the code source-available-but-not-usable-in-production, and `License.feature_available?` checks with tier membership stored as constants in `features.rb`.

**Every one of the four fails soft. None of them stops serving traffic.** Grafana on an expired licence keeps every dashboard working and merely blocks new custom roles and new reports; the concurrent-session cap outlives the licence by seven days and then lifts. Kong on an expired or absent licence goes **read-only** — the Admin API refuses writes but the proxy keeps forwarding on the last-known config, and `/licenses` plus the keyring-recovery endpoints stay writable specifically so you can un-brick it. GitLab without a licence falls back to Free. Spotify's plugins resolve to `NO_KEY_DEFINED`. Nobody in this slice built a kill switch, and the two that gate infrastructure (Grafana, Kong) both explicitly preserve the data path while freezing the control path — a distinction worth copying directly for a topology-and-health canvas, where freezing edits while continuing to render is exactly the right degradation.

**The seat is the default unit, and the exception proves what drives it.** Grafana self-managed counts active users (signed in within 30 days, rolling, "only counts and enforces the *total* number of active users" regardless of role); GitLab is $29/user/month; Spotify's bundle terms define fees by "the number of individual user seats"; Roadie is $24 per dev/month with **entity count explicitly unlimited**. Kong is the outlier, charging per gateway control plane ($25 serverless / $200 hybrid / $500 dedicated per month) plus API-request, portal and token volume — because Kong's cost and value both scale with traffic, not headcount. Spotify Confidence confirms the rule from the other side: where the cost driver is exposed users rather than staff, it drops seats entirely (unlimited seats on every tier, $449–$999/mo by credits). **Roadie's choice is the directly relevant precedent: a catalog product priced per developer with unlimited entities** — you charge for the humans looking at the graph, not the nodes in it.

**Self-managed prices are systematically less public than SaaS prices.** GitLab publishes $29 for Premium and has *withdrawn* Ultimate's list price in favour of "Get custom pricing." Grafana publishes eleven distinct Cloud unit prices and **no self-managed Enterprise price at all** — pricing, product and get-started pages all route to sales. Kong publishes granular Konnect add-on pricing and nothing for self-hosted Enterprise. Spotify publishes nothing for either the bundle or Portal. Roadie is the only vendor here with a public per-seat number on its main product, and both its portal tiers now read "Existing subscribers only." If the plan is a self-hosted Enterprise edition, the honest read of this slice is that **nobody in it publishes a self-hosted Enterprise price**, and the two that publish anything at all publish the SaaS.

**The line moves in both directions, routinely, and the thing that constrains it is a written promise — not the licence.** Kong moved toward free twice (2021: Enterprise downloadable "for free today without any paywall or forms to fill out"; September 2023: Kong Manager open-sourced) and is now moving back (free mode deprecated at 3.10.0.0, after which "running Kong Gateway without a license will behave the same as running it with an expired license"). Grafana open-sourced OnCall in 2022 and archived it in 2026 with a Cloud-only successor. GitLab collapsed five tiers to three in 2021 and raised Premium from $19 to $29 in 2023. Licences constrained none of this. What constrained GitLab was its own handbook — *"When a feature is open source we won't move that feature to a paid tier,"* *"We never move existing features already in GitLab FOSS into a paid tier… regardless of whether the feature was created by GitLab team members or the wider community of contributors"* — and note the escape hatch it deliberately left itself: the promise *"applies only to the GitLab open source codebase, not to GitLab, Inc services such as GitLab Software as a Service,"* which is what licenses the 5-users-per-top-level-group cap on GitLab.com Free while self-managed Free stays uncapped.

**No vendor in this slice demoted a free feature to paid. Everything that moved, moved sideways.** What actually changed was licences (Grafana Apache-2.0 → AGPLv3), *deployment targets* (OnCall's free self-hosted option withdrawn; Kong's unlicensed operating mode being withdrawn), storage defaults (Insomnia 8.0), tier structure (GitLab's Bronze/Starter retirement), list prices, and SaaS-only usage caps. The taboo on taking back a shipped free feature holds even where nobody promised anything — but **withdrawing the free way to run it is treated as fair game**, and lands on the user identically. That is the manoeuvre to watch for and, if you want to be trusted, to promise against explicitly.

**Where a vendor did move the line and users pushed back, it reversed within one point release.** Insomnia 8.0 made cloud storage effectively mandatory; 8.3 restored "100% local storage with no cloud synchronization for your projects" and changed migration to default to Local Vault. Marco Palladino's stated reason is the most quotable line in this research: *"We learned after releasing Insomnia 8.0 that for many users using cloud storage for projects (encrypted or not) is simply not an option."* The reversal cost was low because the change was a default, not a licence.

**Inbound contribution licensing splits on two variables, and the answer for an Apache-2.0 project with a separate private repo is "you need nothing."** Three of the four use a CLA carrying the Apache-derived **sublicense** right — Grafana ("...publicly perform, **sublicense**, and distribute Your Contributions"), GitLab (identical wording, in `doc/legal/individual_contributor_license_agreement.md`), and Kong (enforced by a `license/cla` cla-assistant status check; **CLA text not verified**). Backstage uses **DCO only**, no CLA, verified by `CONTRIBUTING.md`, a verbatim DCO 1.1 at the repo root, the absence of any CLA workflow in 49 workflow files, and a live `DCO` check on PRs. The determining variables are not "do we want an Enterprise edition":

1. **Is the OSS licence permissive?** Apache-2.0 and MIT already permit proprietary derivative works, so no additional grant is needed to ship the same code in a closed edition. AGPL does not — Grafana's sublicense right is what lets it ship contributed code both AGPL and proprietary. **A repo staying on Apache-2.0 does not need a CLA to open a private Enterprise repo.**
2. **Does proprietary code live inside the OSS repo?** If yes, you need both a per-directory licence and a directory-scoped CLA. GitLab's split is the design to copy if that day comes: **DCO for the MIT tree, Individual/Corporate CLA only for contributions to `gitlab-org/gitlab/ee`** — ask for the strong grant only where you actually need to relicense. GitLab belts-and-braces it further: `ee/LICENSE` itself asserts that GitLab "retain[s] all right, title and interest in and to all such modifications and/or patches."

Backstage makes the consequence explicit and it cuts both ways: because Apache-2.0 already permits it, **Spotify needs no CLA and gains no special privilege — any contributor or competitor has the identical right to build a proprietary layer**, which is precisely what Roadie, Frontside and the Marketplace partners do. And where a foundation governs, the choice is preserved rather than removed: the CNCF Charter mandates the DCO but says "**Each project shall determine whether it will require use of an approved CNCF CLA**." Foundation governance forces Apache-2.0 outbound and DCO inbound; it does not force a CLA, and it does not stop the vendor selling a closed layer — it stops the vendor from being the *only* one who can.

**Every one of the four sells support, and one of them sells nothing else.** Frontside's entire Backstage business is Silver/Gold/Platinum support tiers scaled by team size and counted in meetings, pairing sessions and code reviews. Grafana bundles a 2-hour critical SLA, long-term support of a deployed version, a dedicated TAM, and **indemnification**. GitLab puts priority support in Premium. Kong Enterprise adds dedicated CSM/TAM and "higher SLAs." Support is the one line item nobody has ever had to defend, relicense, or apologise for.


---

## Survey: infrastructure dev-tools

Primary-source research for the Nodqora open-core decision. Pricing claims are dated **as published, September 2026** unless a different date is given. Where a claim could not be confirmed against a vendor-owned page, repo file, or official blog post, it is marked **not verified**.

---

### Temporal

Temporal is the outlier in this slice: it is not open-core at all. It is a permissively-licensed OSS server plus a hosted service, with no proprietary edition of the server and no license-key machinery anywhere in the codebase.

#### Where the line is

There is no feature line inside the software. The Temporal Server (`temporalio/temporal`) is MIT-licensed in full, and the SDKs and the web UI are MIT too — `temporalio/ui/LICENSE` and `temporalio/sdk-go/LICENSE` are both "The MIT License / Copyright (c) … Temporal Technologies Inc." (https://github.com/temporalio/temporal/blob/main/LICENSE). The paid product is **Temporal Cloud**, a hosted service; you buy operation of the thing, not unlocked features of the thing.

The line that *does* exist is a tiering line *inside the hosted service*, and it lands on exactly the categories the brief asks about. Per the pricing page (https://temporal.io/pricing, as published September 2026): **SAML SSO** is included in Business and Enterprise, not in Essentials; **SCIM** is an add-on on Business and included on Enterprise; **audit logging** is listed as a Cloud platform feature from Essentials up. Support itself is a priced line item on every plan, not a free tier. Temporal's own comparison page (https://docs.temporal.io/evaluate/development-production-features/cloud-vs-self-hosted-features) is notable for what it concedes to self-hosting rather than to Cloud: self-hosted gets **unlimited Namespaces** against a Cloud cap of 100, and **unlimited retention** against Cloud's 30–90 days. So retention and multi-tenancy — the two things most open-core vendors gate — are *less* restricted in the free self-hosted path than in the paid one, because the constraint there is Temporal's cost of storage, not a commercial fence.

Self-hosted deployments get authentication and authorization plumbing (the server ships an authorizer interface and the UI supports OIDC/OAuth via environment configuration, per https://docs.temporal.io/self-hosted-guide/security), but the *managed* SAML/SCIM identity experience is a Cloud-plane feature and does not exist in the self-hosted control plane, because the self-hosted deployment has no Temporal-operated control plane to put it in.

#### Gating mechanism

None, technically. A code search of `temporalio/temporal` for "license key" returns nothing; a search for "enterprise" returns only a transitive Go dependency (`github.com/googleapis/enterprise-certificate-proxy` in `go.mod`). There is no `ee/` directory, no source-available subtree, no separate proprietary build of the server, and no runtime license check. The proprietary code is the Temporal Cloud control plane, which simply is not in any public repository — the boundary is the network boundary of the SaaS, not a conditional inside the binary. This is the cleanest possible separation: nothing in the OSS repo is dead code waiting for a key, and no community contributor can accidentally touch the commercial surface.

#### Pricing unit

Consumption, with plan minimums layered on top. Per https://docs.temporal.io/cloud/pricing and https://temporal.io/pricing (as published September 2026):

- **Actions** are the primary unit — "Actions are the primary unit of consumption-based pricing for Temporal Cloud. They track billable operations within the Temporal Cloud Service, such as starting Workflows, recording a Heartbeat or sending messages." Overage tiers begin at **$50 per million Actions** for the next 5M beyond the bundle, then $45/million for the following 5M, with contact-sales above 200M.
- **Storage** is billed in gigabyte-hours, split into **Active Storage at $0.042/GBh** (storage used by running Workflows) and **Retained Storage at $0.00105/GBh** (after Workflow completion) — a 40× spread that is itself a retention-pricing lever.
- **Capacity** is either On-Demand (500 APS minimum, autoscaling) or Provisioned in **Temporal Resource Units (TRUs)**, each TRU being 500 Actions Per Second, with a minimum hourly requirement of 360,000 Actions Per Hour per additional TRU.
- **Support** is charged as the greater of a monthly floor or a percentage of usage: Essentials is "greater of $100/month or 5% of usage", Business "greater of $500/month or 10% of usage", Enterprise and Mission Critical priced annually via Sales.
- Plan bundles as published: Essentials $100/month minimum for 1M Actions / 1 GB Active / 40 GB Retained; Business $500/month minimum for 2.5M / 2.5 GB / 100 GB; Enterprise custom for 10M / 10 GB / 400 GB. A free tier of $1,000 in credits exists, plus a startup program of $6,000 in credits for companies with under $30M raised.

**No per-seat, per-user, per-node or per-namespace charge appears anywhere in the pricing model.** The docs pricing page contains no seat or namespace line item at all. There is no paid self-hosted support subscription published on temporal.io — self-hosted users get community Slack and Forum support; **not verified** whether Temporal sells bespoke self-hosted support contracts privately.

#### Did the line move?

The *licence* has never moved. Temporal was forked from Uber's Cadence by Cadence's original authors and has been MIT since inception; the LICENSE file still carries both "Copyright (c) 2025 Temporal Technologies Inc." and "Copyright (c) 2020 Uber Technologies, Inc." There is no ELv2/BSL/SSPL relicensing event to report, and no source-available directory has appeared in the server repo.

The *pricing* line moved once, and in the customer's favour on the governance axis. The Temporal Cloud Pricing Update (https://temporal.io/blog/temporal-cloud-pricing-update) announced four plans — Essentials, Business, Enterprise, Mission Critical — defaulting for new customers in **January 2025** and migrating existing customers from **February 2025**. Two changes matter here: volume discounting now begins at **5M Actions instead of 300M**, and discounts apply across all namespaces rather than per-namespace. And explicitly: **"SSO will now be included in Enterprise and Mission Critical plans without an additional charge."** That is SSO moving from a paid add-on to bundled — the opposite direction from the usual SSO-tax drift. The update also introduced minimum spend commitments on 1-, 2- and 3-year terms, with the stated softener that "Commitments do not have punitive overages, once you reach your commitment, additional usage will continue to be billed at the discounted rate."

#### Inbound contribution licensing

Temporal requires a **CLA**, not a DCO. `CONTRIBUTING.md` states: "All contributors also need to fill out the [Temporal Contributor License Agreement](./docs/development/temporal-cla.md) before we can merge in any of your changes."

The text at https://github.com/temporalio/temporal/blob/main/docs/development/temporal-cla.md is an **Individual** CLA on the standard Apache ICLA template, and it references a separate **Corporate** CLA (clause 4: "…or that your employer has executed a separate Corporate CLA with Company"). **Not verified** whether the Corporate CLA text is published anywhere public.

The operative clause is section 2, Grant of Copyright License:

> "You hereby grant to Company and to recipients of software distributed by Company a perpetual, worldwide, non-exclusive, no-charge, royalty-free, irrevocable copyright license to reproduce, prepare derivative works of, publicly display, publicly perform, **sublicense**, and distribute Your Contributions and such derivative works."

This is a licence grant, **not a copyright assignment** — "Except for the license granted herein to Company and recipients of software distributed by Company, You reserve all right, title, and interest in and to Your Contributions." But the combination of *sublicense* + *prepare derivative works* + *irrevocable* is precisely what permits Temporal to ship contributed code under any licence it likes, including a proprietary one, without going back to contributors. Temporal has never needed to exercise it — but the right is banked. Section 3 grants a matching patent licence with a standard defensive-termination trigger.

---

### Airbyte

Airbyte is the sharpest case in the slice and the most cautionary. It has relicensed twice, and it has **killed its self-hosted commercial edition outright**.

#### Where the line is

The current editions (https://airbyte.com/pricing, as published September 2026) are **Core** (self-managed, "Always free", open source), **Standard** (managed, volume-based, "Starting at $10 / month"), **Pro** (managed, capacity-based, custom pricing) and **Enterprise Flex** (managed control plane + customer-run data planes, custom pricing). There is also a separate Airbyte Agents product line with its own tiers.

The gated categories are almost a textbook list. Per https://docs.airbyte.com/platform/access-management/rbac, **Role Based Access Control** is "Available" in Pro and Enterprise Flex and "Not available" in Core and Standard. Per https://docs.airbyte.com/platform/enterprise-flex, Enterprise Flex adds **User Management**, **Single Sign-On** (Okta, Azure Entra ID, generic OIDC), **Multiple Workspaces** for team/project isolation, **Role-Based Access**, **Column Hashing** for PII, **External Secrets** managers, **Audit logs** "for compliance tracking", **AWS PrivateLink**, and **Support with SLAs**. So: SSO, RBAC, multi-tenancy (workspaces), audit logging, secrets management, network isolation, and support — every category on the list, all on the paid side. Core gets the full replication engine and the connector catalogue and nothing governance-shaped.

The structural detail that matters most for an open-core decision: **you can no longer buy any of that and run it yourself.** https://docs.airbyte.com/platform/enterprise-setup/ now says flatly, "Airbyte no longer sells Self-Managed Enterprise," preserving only PDF documentation for existing customers and directing new buyers to Cloud plans or to Core. Enterprise Flex is not a replacement for self-hosted Enterprise: it is "a fully managed Cloud control plane that supports separate data planes that run in your own infrastructure," where "Only metadata ever reaches the control plane." The governance features live in Airbyte's control plane. The free self-hosted product and the paid product are no longer the same artifact with a key — they are now different architectures.

#### Gating mechanism

Airbyte's mechanism is the most instructive of the three, because the enforcement code is **inside the open (ELv2) repo** rather than in a private one.

The root `LICENSE` of both `airbytehq/airbyte` and `airbytehq/airbyte-platform` is the Elastic License 2.0 in full, with no per-directory carve-outs. Within `airbyte-platform` there is a dedicated module, `airbyte-commons-license`, containing `ActiveAirbyteLicense.kt`. The mechanics are a **signed-JWT license key parsed at runtime**:

```kotlin
@Singleton
@RequiresAirbyteProEnabled
class ActiveAirbyteLicense(
  @param:Value("\${airbyte.license-key}") private val licenceKey: String,
) {
  var license: AirbyteLicense = extractLicense()
  val isPro: Boolean get() = license.type == LicenseType.PRO
```

The key is split on `.` into three fragments, the payload Base64-decoded, and deserialized into a `LicenseJwt` carrying `license` (type), `maxNodes`, `maxEditors`, `enterpriseConnectorIds`, `exp` and `isEmbedded`. Note the shape of those claims — the licence encodes a **node cap, an editor-seat cap, and an allow-list of entitled connector UUIDs**, so the key is a metering instrument, not just an on/off switch.

Enforcement is then done through the DI container rather than through scattered `if` statements. `VerifiedProLicenseCondition` is a Micronaut `Condition` — "Used to conditionally activate beans that should only be activated for verified installations of Airbyte Pro" — and `airbyte-commons-entitlements` layers an `EntitlementProvider` interface over it. The default implementation returns `false` for every entitlement:

```kotlin
class DefaultEntitlementProvider : EntitlementProvider {
  override fun hasConfigTemplateEntitlements(organizationId: OrganizationId): Boolean = false
  override fun hasDestinationObjectStorageEntitlement(organizationId: OrganizationId): Boolean = false
  override fun hasSsoConfigUpdateEntitlement(organizationId: OrganizationId): Boolean = false
  override fun hasManageDataplanesAndDataplaneGroupsEntitlement(organizationId: OrganizationId): Boolean = false
}
```

…and `EnterpriseEntitlementProvider`, annotated `@Replaces(DefaultEntitlementProvider::class) @RequiresAirbyteProEnabled`, swaps in and consults the active licence. Entitlements are additionally crossed with LaunchDarkly-style feature flags (`LicenseAllowEnterpriseConnector`, `EnableSsoConfigUpdate`, `AllowDataplaneAndDataplaneGroupManagement`), i.e. **feature-flag-plus-licence**, checked per-organization.

The legal backstop for all this is in ELv2 itself and restated in Airbyte's own FAQ (https://docs.airbyte.com/platform/developer-guides/licenses/license-faq): you may not "Circumvent the license key functionality or remove/obscure features protected by license keys", and "our code may contain watermarks or keys to unlock proprietary functionality". This is exactly why the licence had to be ELv2 rather than MIT — under MIT, deleting the `isPro` check and rebuilding would be entirely lawful. **The licence choice and the gating mechanism are the same decision.**

The pattern to take from this: the entitlement *checks* are open; the entitlement-*granting* signing key is not. Airbyte ships the lock in public and keeps the key.

#### Pricing unit

Two different units, deliberately, split by segment (https://airbyte.com/pricing, as published September 2026):

- **Core**: free, self-managed, no unit.
- **Standard**: **volume-based**, "Starting at $10 / month" — "For practitioners looking for fully managed software, basic functionality and prefer to be billed based on data volume." The pricing page's own FAQ describes volume pricing generically as billing "by rows, GBs, or events"; **the exact Standard metric and per-unit rate is not published on the page — not verified.**
- **Pro** and **Enterprise Flex**: **capacity-based**, priced by **Data Workers** — "a unit of capacity that powers your pipelines", where "Each Data Worker can run multiple syncs concurrently; typically ~3 at once." Published rate per Data Worker: **not verified** (custom pricing, contact sales).
- **Airbyte Agents** (separate product): priced by **Agent Operations (AOs)** — Free $0/month for 1,000 AOs and a single user; Individual $29/month for 5,000 AOs with overage at $0.004/AO; Team $299/month for 10,000 AOs, multiple users, overage at $0.005/AO. An AO is "the loop" through Search, Read, Act and Reason, and "Reasoning tends to consume the most AOs, while Reading consumes the least." Note this one *does* have a seat dimension (single user vs multiple users) — the only per-user gate in Airbyte's published pricing.

The internal licence JWT's `maxNodes` and `maxEditors` claims suggest the retired Self-Managed Enterprise SKU was metered on nodes and editor seats; **not verified** against a published Enterprise price list, since Airbyte never published one.

#### Did the line move?

Three times, and the direction is consistently outward.

**Move 1 — September 2021: the platform, MIT → ELv2.** Announced in https://airbyte.com/blog/a-new-license-to-future-proof-the-commoditization-of-data-integration, effective with Airbyte Core 0.30.0 (27 September 2021). Connectors stayed on their existing licences at this point. The stated fear was verbatim that "huge companies take the Airbyte project and start offering a clone of Airbyte Cloud," fragmenting the community and cutting off the revenue-share they wanted to pay connector maintainers. **The same post introduced the CLA** — "Nothing changes here besides the introduction of a Contributor License Agreement (CLA)" — which, in retrospect, was the enabling step for everything that followed.

**Move 2 — June 2023: strategic connectors → ELv2.** https://airbyte.com/blog/update-on-airbytes-license (30 June 2023) moved a named list of high-value connectors to ELv2: the database sources (Oracle, Postgres, MSSQL, MongoDB, MySQL), file sources (S3, GCS), the money API sources (Salesforce, HubSpot, Stripe, Shopify, Zendesk, Facebook Marketing, Google Ads/Analytics 4/Search Console/Sheets, Marketo), and the warehouse destinations (BigQuery, Snowflake, Redshift, S3, GCS). Stated reason: "The ELv2 license is very permissive for 99.9% of our users. It only adds restrictions for those who wish to create a hosted ETL service taking what is freely available and charging our users."

**Move 3 — 22 August 2025: everything else, MIT → ELv2.** https://airbyte.com/blog/move-to-elv2 completed the migration, taking the remaining connectors and platform to ELv2. CEO Michel Tricot's stated reason: **"We saw competitors taking the freely available work of contributors, your work, and reselling it as a managed service, without giving back."** The commitments made alongside it: "You can use the Airbyte platform and connectors however you like… You can run them in production, modify them, and share improvements", and "Airbyte will always be free to use for anyone building data pipelines." The residue of MIT is now small and deliberate: per https://docs.airbyte.com/community/licenses, "Airbyte Connectors and everything in our public repos excluding the airbytehq/airbyte-protocol are open sourced and available under the Elastic License 2.0 (ELv2)", while "Airbyte Protocol is open sourced and available under the MIT License" — i.e. **the interop contract stays permissive so third parties can implement it; the implementation does not.** That is a deliberate and copyable design.

**Move 4 — the discontinuation.** Separately from licensing, Airbyte **withdrew Self-Managed Enterprise from sale** (https://docs.airbyte.com/platform/enterprise-setup/). Date of withdrawal and stated reason: **not verified** — the docs page announces the fact without explaining it and without a corresponding blog post I could find. The commercial consequence is unambiguous, though: an enterprise that wants SSO/RBAC/audit from Airbyte must now accept an Airbyte-operated control plane. Enterprise Flex (https://airbyte.com/blog/enterprise-flex) is the sanctioned path.

#### Inbound contribution licensing

Airbyte requires a **CLA**, introduced in the September 2021 ELv2 post and still in force: per https://docs.airbyte.com/platform/developer-guides/licenses/license-faq, "We have a Contributor License Agreement that you have to sign with your first contribution." It is enforced as a status check on pull requests and signed once per contributor.

**The full CLA text could not be retrieved from an Airbyte-owned URL — the operative grant clause is not verified.** The FAQ references the CLA's existence but not its terms, and no CLA document appears in `airbytehq/airbyte` (`.github/` contains no CLA file and the workflow list contains no CLA job, consistent with enforcement by a third-party GitHub App rather than by repo-local CI).

What *can* be established from primary sources is the sequencing, and it is the load-bearing finding for us:

1. Airbyte shipped under MIT with an open contributor base and **no CLA** until September 2021.
2. It introduced the CLA **at the same moment** it first relicensed (platform MIT → ELv2), framing it as "nothing changes here besides…".
3. It then relicensed twice more over the following four years — June 2023 and August 2025 — by which point every contributor since 2021 had signed.

In other words, Airbyte acquired the inbound right *before* it needed it, and spent the following four years using it. The 2025 move relicensed connectors contributed by a large external community, and it is the CLA signed from 2021 onward that made that possible without a per-contributor consent exercise. **How Airbyte handled pre-September-2021 contributions to code that was relicensed in 2023 and 2025 — whether by rewrite, by removal, by retroactive sign-off, or by treating the original MIT grant as sufficient — is not verified.** Note that MIT is itself permissive enough to sublicense under ELv2 provided attribution is preserved, so pre-CLA MIT contributions likely needed no special handling; this is a legal inference, **not verified** against an Airbyte statement.

The lesson for Nodqora is blunt: **get the inbound grant in place before the first outside contribution, not before the first relicensing.** Airbyte got it 18 months into the project's life and still had to live with an MIT-era tail.

---

### Cerbos

Cerbos is the most philosophically explicit of the three, and the one whose mechanism is closest to what Nodqora is contemplating — except that Cerbos deliberately declined to put *any* feature gate in the OSS binary.

#### Where the line is

The line is drawn between the **decision engine** and the **control plane around it**, and Cerbos states this as policy rather than leaving it implicit. From https://www.cerbos.dev/blog/open-source-vs-paid-cerbos (18 August 2026):

> "The open source PDP is a full decision engine, not a teaser. Every policy feature ships in it. Resource and principal policies, derived roles, conditions written in CEL, schema validation, and first class test suites are all there."

> "No license, no account, no usage cap on the decisions themselves."

> "What you pay for is not a better engine. It is everything around the decision that you would otherwise build and run yourself."

**Cerbos Hub** is the paid side: "an end-to-end authorization platform comprising a policy control plane, a data enrichment layer, and a distributed policy engine" (https://docs.cerbos.dev/cerbos-hub/). Its features are collaborative policy authoring in shared IDE-like playgrounds; a managed build/release pipeline that "automatically validates, tests, signs, and distributes every policy change"; fleet visibility over Decision Points showing "which policies each PDP is serving, the exact bundle version, and when the instance was last seen"; embedded WASM PDPs for browsers and edge functions; and centralized audit log collection.

The audit-logging case deserves particular attention because it inverts the usual open-core move. Audit logging is **not** behind the line. Per https://docs.cerbos.dev/cerbos/latest/configuration/audit, the OSS PDP ships four audit backends — `local` (embedded KV store), `file` (NDJSON to file or stdout), `kafka`, and `hub` — and only the last "Requires a Cerbos Hub account." So a self-hosting user gets complete, compliance-usable decision logs for free; what Hub sells is **aggregation and search across a fleet**, not the logging itself. That distinction — the capability is free, the *fleet-scale operation* of the capability is paid — is the single most transferable idea in this slice.

Similarly, multi-tenancy is not gated in the engine (a PDP evaluates whatever policies you give it); what is gated is *managing many PDPs from one place*. SSO appears only at Hub's Enterprise tier, and it gates access to **Hub's** console, not to anything in the PDP.

The one genuine capability asymmetry: **embedded WASM PDPs are a Hub build artifact.** Hub compiles and CDN-distributes the policy bundle that `@cerbos/embedded-client` fetches at runtime. The engine module is published, but the bundle-building pipeline is Hub's. Notably, `cerbosctl hub epdp` was removed from the OSS CLI (changelog `20260713_1410.txt`, "Remove support for legacy Cerbos Hub workspaces"). **Whether an equivalent bundle can be produced with OSS tooling alone: not verified.**

#### Gating mechanism

**There is none in the PDP, and this is the interesting part.** The Cerbos PDP is Apache-2.0 throughout (`LICENSE` is the plain Apache License 2.0; `CONTRIBUTING.md` mandates the header `Copyright 2021-2026 Zenauth Ltd. / SPDX-License-Identifier: Apache-2.0` on every source file). There is no license key, no `isPro` equivalent, no entitlement bean, no source-available subtree.

More striking: **the Hub client is fully inside the Apache-2.0 repo.** `internal/hub/conf.go` holds "Credentials … for Cerbos Hub" and connection settings; `internal/storage/hub/` implements pulling policy bundles from Hub; `cmd/cerbosctl/hub/` provides `hub auth`, `hub store` and the `hubctl upload-git` command; the server accepts `CERBOS_HUB_DEPLOYMENT_ID` and `CERBOS_HUB_PLAYGROUND_ID` (mutually exclusive via a Kong `xor:"hub.source"` tag) and `CERBOS_HUB_CLIENT_ID` / `CERBOS_HUB_CLIENT_SECRET`. The Helm chart ships a `values-cerbos-hub.yaml`.

So the enforcement is **entirely server-side**: the OSS binary is a complete, freely-modifiable client that will happily try to talk to Hub, and Hub is what refuses unauthenticated or over-quota credentials. Nothing in the client needs to be trusted, so nothing in the client needs to be closed, so the licence can stay Apache-2.0 without commercial risk. Cerbos states the consequence directly:

> "The binary you run in production is the same binary whether or not you ever pay us a cent."

This is the architectural reason Cerbos could stay Apache-2.0 where Airbyte had to move to ELv2. **If your paid value is a service the client calls, you don't need a restrictive licence. If your paid value is a feature the client executes locally, you do.** That is the fork in the road for Nodqora, and it is decided by product architecture, not by legal preference.

Cerbos also sells **Cerbos Hub on-premise** (https://www.cerbos.dev/blog/cerbos-hub-now-available-on-premise, 22 January 2026), covering "On-premise data centers, Private and bring-your-own cloud environments, Fully air-gapped networks, Hybrid architectures, Cloud-hosted." This reintroduces the problem Cerbos otherwise avoided — an air-gapped Hub cannot be metered by a phone-home — so there is presumably some licence-key or entitlement mechanism in the Hub distribution. **Its mechanics are not verified**; the announcement post does not describe licensing, delivery format, or key checking, and Hub itself is not in a public repo.

#### Pricing unit

**Monthly Active Principals** — "unique principals authorized during one calendar month" (https://www.cerbos.dev/pricing, as published September 2026). This is neither a seat unit nor a volume unit: it scales with the *end-user population of the customer's application*, not with the customer's employee count or their decision throughput. Two customers doing identical request volumes pay differently if one serves 500 users and the other 50,000.

Published tiers:

- **Open Source** — free forever, no principal limit, no account.
- **Proof of Concept** — $0/month, up to 100 monthly active principals, 1 workspace, 2 developers, 2 Playgrounds, 1 week of unified audit logs.
- **Development** — from **$25/month**, first 100 monthly active principals included, 3 workspaces, 5 developers, up to 5 Playgrounds, **3 months** of unified audit logs, uptime SLA.
- **Production** — from **$933/month**, first 5,000 monthly active principals included, unlimited workspaces, unlimited developers, unlimited Playgrounds, **1 year** of unified audit logs.
- **Enterprise** — custom, with custom audit log retention, SSO support, self-hosted Cerbos Hub, and an enterprise support SLA.

Note the secondary units stacked on top of principals: **developer seats** (2 / 5 / unlimited), **workspaces** (1 / 3 / unlimited), and **audit log retention** (1 week / 3 months / 1 year / custom). Retention is a pure pricing lever here — the same data, priced by how long Hub keeps it — and it is the classic multi-tier ladder done on the *hosted* copy of logs the customer already has locally for free.

#### Did the line move?

No licence change to report. Cerbos has been Apache-2.0 from the start and the current `LICENSE` is unmodified Apache 2.0. Hub launched (public beta, then GA — https://www.cerbos.dev/news/cerbos-hub-is-now-generally-available) as an *addition* alongside the PDP rather than as a carve-out from it, which is the structurally easier version of this move: nothing that was free became paid.

The nearest thing to a formal open-core policy statement in this whole slice is Cerbos's, and it is worth quoting as the model of what such a commitment looks like when a vendor is willing to make one (https://www.cerbos.dev/blog/open-source-vs-paid-cerbos, 18 August 2026):

> "It is complete, and it stays that way."

> "If you're happy to run everything yourself, you never have to talk to us, never give us a penny."

Direction of drift has, if anything, been *toward* the OSS side: the `hub` audit backend gained the ability to fan out to a secondary backend so that "teams that want to leverage the power of Cerbos Hub to analyze audit logs while also aggregating all logs into a central silo for monitoring and compliance requirements" are not locked in (changelog `20251205_1145.txt`, released in v0.49.0). That is a deliberate anti-lock-in feature shipped in the free tier.

The one real removal was `cerbosctl hub epdp` and legacy Hub workspace support (changelog `20260713_1410.txt`, "Loading policy bundles from legacy Cerbos Hub workspaces is no longer supported and the corresponding configuration settings have been removed"), but that reads as deprecation of a superseded Hub API rather than a feature moving behind the line. **Not verified** whether any OSS capability was lost as a result.

#### Inbound contribution licensing

Cerbos requires a **DCO, not a CLA** — the only project of the three with no CLA. `CONTRIBUTING.md`: "Sign-off your commits to provide a [DCO](https://developercertificate.org). You can do this by adding the `-s` flag to your `git commit` command." The PR template carries the checklist item "All commits are signed-off (`git commit -s ...`) to provide the [DCO](https://developercertificate.org/)", and enforcement is configured in `.github/dco.yaml` (`require: members: false`, i.e. the DCO check is enforced on non-member contributors). There is no CLA file, no CLA workflow, and no copyright-assignment request anywhere in the repo.

This is entirely coherent with the rest of Cerbos's design, and the coherence is the finding. Under a DCO, Cerbos gets **no right to relicense contributed code** — a DCO is an attestation of provenance, not a licence grant to the vendor, so contributed Apache-2.0 code stays Apache-2.0 in Cerbos's hands as much as anyone else's. Cerbos can live with that because **it never needs to relicense**: the proprietary product (Hub) is a separate, private, server-side codebase that no outside contributor touches, and the OSS PDP contains nothing Cerbos wants to close. Contributors upstream and the commercial product downstream never share a file.

So the three-way relationship holds tightly:

- **Proprietary value in a separate server** → no need to relicense OSS code → DCO suffices → contributors face the lowest possible friction and the strongest possible guarantee. (Cerbos)
- **Proprietary value gated inside the shipped OSS artifact** → need to relicense and need anti-circumvention → CLA required *and* a restrictive licence required. (Airbyte)

Cerbos chose the first and got Apache-2.0 plus a DCO plus a credible "it stays that way" promise as a package deal. **Not verified**: whether Zenauth Ltd. has any separate agreement with employed maintainers, or how it would handle a future desire to relicense (which, on the DCO, it could not do unilaterally over external contributions).

---

### Patterns: infrastructure dev-tools

**1. The gating mechanism is chosen by product architecture, not by legal preference.** This is the strongest finding. Cerbos's paid value is a *service the client calls* (Hub), so nothing valuable ships in the binary, so the binary can be Apache-2.0 with the entire Hub client in the open — enforcement is Hub refusing credentials. Airbyte's paid value is *features the shipped artifact executes locally* (SSO, RBAC, audit in the customer's own deployment), so the check must live in the customer's copy, so the licence must forbid deleting the check — hence ELv2 and hence the anti-circumvention clause. Temporal's paid value is *operating the thing*, so there is nothing to gate at all and MIT costs nothing. **Decide where the value sits before deciding the licence; the licence follows mechanically.** For Nodqora, the question is: is Enterprise a control plane Nodqora runs, or features running inside the customer's Nodqora?

**2. Nobody in this slice puts proprietary source in the OSS repo — but two put the *enforcement* there.** None of the three has an `ee/` directory, a source-available subtree, or a dual-licensed folder. Airbyte's licence-checking code is in the ELv2 repo (`airbyte-commons-license`, `airbyte-commons-entitlements`) but the licence *signing key* is not — Airbyte ships the lock in public and keeps the key. Cerbos ships the entire Hub client in the Apache-2.0 repo because there is no lock to ship. Temporal ships nothing because there is no Cloud plane in the repo. The separate-private-repo model that Nodqora is planning matches Temporal's and Cerbos's shape; if Nodqora also needs in-binary feature gates, expect to end up with Airbyte's shape (checker in the open repo, signer in the private one) and to need a licence that forbids removing the checker.

**3. The gated categories are remarkably consistent — SSO, RBAC, multi-workspace, audit aggregation, secrets, network isolation, support SLA.** Airbyte gates literally all of them. Cerbos gates SSO (to its own console) and audit *aggregation*. Temporal gates SAML/SCIM inside its SaaS tiers. Nothing in this slice gates the core engine's functional capability. **The line reliably falls between "does the work" and "governs and observes many instances doing the work."** For a topology-and-health canvas, the analogue is: rendering the canvas is free; SSO, per-team RBAC, retained history, and multi-cluster fleet rollups are the natural paid side.

**4. Retention is a pricing lever everywhere, in a way that looks like metering rather than gating.** Temporal charges 40× more per GBh for Active than Retained storage and caps Cloud retention at 30–90 days while self-hosted is unlimited. Cerbos ladders unified audit log retention at 1 week / 3 months / 1 year / custom. Airbyte's Enterprise Flex includes audit logs as a tier feature. In none of these is the *ability* to retain gated — the *vendor-hosted duration* is. Retention monetizes cleanly because it maps to a real vendor cost, which makes it defensible to customers in a way that an SSO tax is not.

**5. Audit logging in the free tier, aggregation in the paid tier, is the most reusable split.** Cerbos gives away complete decision logging (local, file, Kafka) and sells only the fleet-wide collection, search and retention. That is a better-feeling line than Airbyte's (where audit logging as such is Enterprise-only) and it is essentially costless to concede: the self-hoster who wants logs already has the disk they'd be written to.

**6. Inbound licensing must be settled before the contributor base exists, and it constrains everything downstream.** Airbyte introduced its CLA at the exact moment of its first relicensing (September 2021) and then relicensed twice more over four years on the strength of it — the CLA was the enabling instrument. Temporal has an Apache-template ICLA whose grant includes *sublicense* + *prepare derivative works* + *irrevocable*, banking a relicensing right it has never used. Cerbos took a DCO and thereby gave up the right to relicense external contributions — a choice it can afford only because its commercial code shares no files with its OSS code. **A CLA with a sublicensable grant is the cheap insurance; a DCO is the stronger community promise you can only make if your Enterprise edition lives in genuinely separate files.** Nodqora is planning a separate private Enterprise repo, which is the Cerbos shape — but if there is any chance Enterprise will ever need to absorb code from the Apache-2.0 repo, the CLA is required, and it is far cheaper to require it now than after the twentieth outside PR.

**7. Permissive licences survive when nothing valuable is in the binary; they get abandoned when something is.** Temporal has been MIT for its whole life with no drift. Cerbos has been Apache-2.0 with an explicit "it stays that way." Airbyte went MIT → ELv2 in three steps over four years, and its stated reason each time was the same: competitors reselling the work as a managed service. Notably, Airbyte's *protocol* stayed MIT while the implementation went ELv2 — a deliberate split that preserves third-party interop while closing the resale path. If Nodqora publishes any wire format or plugin contract, licensing that separately and permissively is a low-cost hedge that buys ecosystem goodwill regardless of what the main repo's licence does later.

**8. Two of the three don't charge per seat at all, and the third charges for the customer's users, not the customer's staff.** Temporal charges Actions + storage + capacity + a support percentage, with no seat or namespace line item anywhere. Airbyte charges volume (Standard) or Data Worker capacity (Pro/Flex) — its licence JWT does carry `maxEditors`, so the retired self-hosted SKU had a seat dimension, but the published managed pricing does not. Cerbos charges Monthly Active Principals — the *end users of the customer's application* — with developer seats only as a secondary tier limit. Per-seat is conspicuously out of fashion in this slice; the units chosen all scale with the value the customer derives rather than with how many of their staff open the UI. That is worth weighing for a canvas product, where the obvious instinct is per-viewer seats and the market evidence points elsewhere (per-cluster, per-monitored-service, or per-connected-environment would be the analogues here).

**9. A self-hosted commercial edition is not guaranteed to survive.** Airbyte's "Airbyte no longer sells Self-Managed Enterprise" is the loudest single data point in this slice, and it went the way of a managed control plane with customer-run data planes instead. Cerbos moved the *opposite* way, adding on-premise Hub in January 2026 for air-gapped buyers. Temporal has never sold a self-hosted commercial edition. **The self-hosted Enterprise SKU carries real operational cost — every customer runs a different version, support is harder, and the licence-key machinery has to be built and maintained — and at least one serious vendor concluded it wasn't worth it.** If Nodqora's Enterprise edition is self-hosted by design, that cost should be priced in deliberately rather than discovered.

---

## Survey: lineage and catalog products

Research date: 2026-09-05. All pricing claims dated to that month. Primary sources only; every
load-bearing claim carries a URL. Gaps are marked **not verified** rather than filled by inference.

Tools covered: OpenMetadata (Collate), DataHub (DataHub Cloud, formerly Acryl Data), Marquez
(with OpenLineage and Astronomer).

---

### OpenMetadata (Collate)

#### Where the line is

The headline framing — "OpenMetadata is Apache-2.0, Collate is the paid SaaS on top" — is
materially wrong, and the repo says so. OpenMetadata's own README still asserts a single licence:
"OpenMetadata is released under the [Apache License, Version 2.0]"
([README.md, License section](https://github.com/open-metadata/OpenMetadata/blob/main/README.md)).
The repo root [LICENSE](https://github.com/open-metadata/OpenMetadata/blob/main/LICENSE) is indeed
Apache-2.0. But the repo is **split across two licences by directory**, and the README does not
mention the second one.

Measured against the repo (GitHub code search, 2026-09-05):

| Module | Licence |
|---|---|
| `openmetadata-service` (the backend) | Apache-2.0 — 1360 files carry the Apache header, 0 carry the Collate header |
| `openmetadata-spec` (JSON schemas) | Apache-2.0 |
| `openmetadata-airflow-apis` | Apache-2.0 ([LICENSE](https://github.com/open-metadata/OpenMetadata/blob/main/openmetadata-airflow-apis/LICENSE)) |
| `ingestion` (all ~80 connectors, profiler, PII, CLI) | **Collate Community License 1.0** ([LICENSE](https://github.com/open-metadata/OpenMetadata/blob/main/ingestion/LICENSE)) |
| `openmetadata-ui` (the entire web UI) | **Collate Community License 1.0** ([LICENSE](https://github.com/open-metadata/OpenMetadata/blob/main/openmetadata-ui/LICENSE)) |
| `openmetadata-mcp` (MCP server) | **Collate Community License 1.0** ([LICENSE](https://github.com/open-metadata/OpenMetadata/blob/main/openmetadata-mcp/LICENSE)) |

A representative per-file header, from
[`ingestion/src/metadata/__init__.py`](https://github.com/open-metadata/OpenMetadata/blob/main/ingestion/src/metadata/__init__.py):

```
#  Copyright 2025 Collate
#  Licensed under the Collate Community License, Version 1.0 (the "License");
```

2916 files across the repo carry that header. So the line is not only "OSS product vs paid SaaS";
a licence line runs *through the OSS product itself*, and it puts the two things a competitor would
need — the connectors and the UI — on the non-OSI side, while leaving the server permissive.

On top of that licence split sits a conventional commercial line, from the vendor's own
[comparison page](https://www.getcollate.io/comparison). Collate-only capabilities: **AI Analytics**
(NL query grounded in governed context), **Shared Chats**, **AI Studio & AI Automations**,
**AI Agents (Documentation, Data Quality, Tier)**, **AI Governance Studio** (registry and audit
reporting for LLMs/MCP servers/agents), **Reverse Metadata** (syncing tags/descriptions/owners back
to Snowflake, Databricks etc.), and **Data Access Requests & Admin Analytics** (scoped requests
converted to enforced grants, plus usage/billing visibility).

The *category* analysis is the interesting part, and it cuts against the usual open-core playbook:

- **SSO is NOT behind the line.** OpenMetadata OSS ships Google SSO, Okta, custom OIDC, Auth0,
  Azure, Amazon Cognito, OneLogin and Keycloak, with nothing marked Collate-only
  ([deployment/security docs](https://docs.open-metadata.org/latest/deployment/security)).
  SAML is not listed on that page — **not verified** whether SAML is OSS, Collate-only, or absent.
- **RBAC/policies are NOT behind the line.** The policy evaluator (`OperationContext`,
  `ResourceContextInterface`, `policyevaluator` package) is in the Apache-2.0 `openmetadata-service`
  tree.
- **What IS behind the line:** AI features, reverse/write-back integration (metadata flowing *out*
  to the warehouse), governance reporting and admin analytics, access-request workflow, and
  operational scale limits (see the next section — this one is enforced in code).

#### Gating mechanism

This is the most directly transferable finding in the slice, because **the enforcement hook ships
inside the Apache-2.0 OSS tree**, as a no-op.

`openmetadata-service` defines a `Limits` SPI
([Limits.java](https://github.com/open-metadata/OpenMetadata/blob/main/openmetadata-service/src/main/java/org/openmetadata/service/limits/Limits.java)):

```java
public interface Limits {
  void init(OpenMetadataApplicationConfig serverConfig, Jdbi jdbi);
  void enforceLimits(SecurityContext securityContext,
                     ResourceContextInterface resourceContext,
                     OperationContext operationContext);
  LimitsConfig getLimitsConfig();
  Response getLimitsForaFeature(String entityType, boolean cache);
  void invalidateCache(String entityType);
}
```

The OSS implementation
([DefaultLimits.java](https://github.com/open-metadata/OpenMetadata/blob/main/openmetadata-service/src/main/java/org/openmetadata/service/limits/DefaultLimits.java))
is deliberately inert — `enforceLimits` has a one-line body, `// do not enforce limits`, and
`getLimitsForaFeature` returns `Response.ok().build()` unconditionally.

The proprietary implementation is loaded **reflectively by class name from config**, in
[`OpenMetadataApplication.registerLimits()`](https://github.com/open-metadata/OpenMetadata/blob/main/openmetadata-service/src/main/java/org/openmetadata/service/OpenMetadataApplication.java)
(~line 1116):

```java
if (limitsConfiguration != null && limitsConfiguration.getEnable()) {
  limits = Class.forName(limitsConfiguration.getClassName()).asSubclass(Limits.class) ... .newInstance();
} else {
  LOG.info("Limits config not set, setting DefaultLimits");
  limits = new DefaultLimits();
}
```

And the config schema that drives it — in the Apache-2.0 `openmetadata-spec` module — is openly a
*billing* schema
([limitsConfiguration.json](https://github.com/open-metadata/OpenMetadata/blob/main/openmetadata-spec/src/main/resources/json/schema/configuration/limitsConfiguration.json)):

```json
"className": { "default": "org.openmetadata.service.limits.DefaultLimits" },
"enable":    { "default": false },
"limitsConfigFile": { "default": "limits-config.yaml" },
"credits":   { "description": "Collate platform credits", "default": 1000 },
"billingCycleStart": { "description": "The start of this limit cycle." }
```

The same pattern repeats for features rather than limits: the OSS schema enumerates `CollateAI` as
an application type with a `CollateAIAppConfig` config interface
([generated appMarketPlaceDefinition](https://github.com/open-metadata/OpenMetadata/blob/main/openmetadata-ui/src/main/resources/ui/src/generated/entity/applications/marketplace/appMarketPlaceDefinition.ts)),
while the implementing app is absent from the repo. Likewise the MCP server is loaded reflectively
(`Class.forName("org.openmetadata.mcp.McpServer")`).

So: **SPI + JSON-schema contract in the OSS repo; proprietary classes injected at runtime via a
config-supplied class name; no licence-key cryptography anywhere in the OSS tree.** There is no
signature check, no phone-home, no expiry validation in the open code — the gate is simply that you
do not possess the class. Searches for licence-checking code in `openmetadata-service` returned
nothing. Whether the Collate distribution additionally validates a signed key is **not verified**
(the proprietary artefact was not inspected).

The paid edition is therefore best described as a **superset build**: the same server, same
schemas, same database migrations, plus proprietary jars dropped onto the classpath and switched on
by `limitsConfiguration.className`. It is not a separate product consuming OSS as a dependency.

#### Pricing unit

From [getcollate.io/pricing](https://www.getcollate.io/pricing), as published September 2026. The
unit is a **two-axis cap: seats × data assets**, plus a metadata-freshness SLA as the third lever.

| Tier | Unit | Deployment |
|---|---|---|
| Free | 5 users, 500 data assets; weekly data refresh | Multi-tenant SaaS |
| Premium | 25 users, 5,000 data assets; 8-hour refresh — **contact sales** | Single-tenant or Hybrid SaaS |
| Enterprise | 50+ users (unlimited available), 10,000+ data assets (unlimited available); hourly refresh — **contact sales** | Single-tenant, Hybrid, or Private BYOC |

`Collate AI` is a separate **add-on**, also contact-sales (AskCollate, AI Studio, AI Analytics,
AutoPilot, Documentation Agent, Tiering Agent, Quality Agent, SQL Agent, MCP Support).

No dollar figure is published at any tier above Free. Two things worth noting for Nodqora: the
asset-count axis is exactly what the `Limits` SPI exists to enforce (so the pricing metric and the
code gate are the same object), and **data refresh frequency** is used as a paid axis — the same
feature, degraded on a timer, rather than removed.

#### Did the line move?

Yes — twice, and always in the same direction (toward the vendor). Neither move was announced with a
public rationale that I could find.

- **UI relicensed to Collate Community License, ~May 2023.** `openmetadata-ui/LICENSE` was added by
  commit dated 2023-05-10, message "Add missing license for UI module (#11510)". The file is CCL at
  tag `1.3.0` (verified by reading `openmetadata-ui/LICENSE?ref=1.3.0`). The commit message frames a
  licence change as filling in a *missing* file.
- **Ingestion relicensed to Collate Community License, September 2024.** Verified by reading the
  file at tags: `ingestion/LICENSE` is Apache-2.0 at `1.5.0` and Collate Community License at
  `1.6.0` and `1.7.0`. The change landed in commit dated 2024-09-17,
  [PR #17893](https://github.com/open-metadata/OpenMetadata/pull/17893), titled "Docs - Ingestion
  License" — a docs-flavoured title for a licence change on ~80 connectors. The PR body states only
  "Add Collate License to the ingestion framework"; there is no stated reason in the PR.
- **MCP server born proprietary-side, July 2025** — `openmetadata-mcp` was split out as its own
  Maven module (PR #22043, 2025-07-01) and carries CCL from creation.

The operative restriction in the Collate Community License 1.0 is an anti-SaaS-compete clause, quoted
in full from [`ingestion/LICENSE`](https://github.com/open-metadata/OpenMetadata/blob/main/ingestion/LICENSE):

> Licensee is not granted the right to, and Licensee shall not, exercise the License for an Excluded
> Purpose. For purposes of this Agreement, "Excluded Purpose" means making available any
> software-as-a-service, platform-as-a-service, infrastructure-as-a-service or other similar online
> service that competes with Collate products or services that provide the Software.

Otherwise the grant is broad: use, modify, distribute, reproduce, royalty-free, worldwide — with a
modification-notice requirement and a copyright-notice-preservation requirement. It is
source-available, not open source; it would not pass the OSI definition.

**Governance: fully vendor-owned.** OpenMetadata is not an LF AI & Data project — it does not appear
on [lfaidata.foundation/projects](https://lfaidata.foundation/projects/). The mark is Collate's:
[open-metadata.org](https://open-metadata.org/) footer reads "OpenMetadata® and the OpenMetadata logo
are trademarks of Collate, Inc." Nothing constrained where Collate put the line, and the two
relicensings are the visible consequence.

#### Inbound contribution licensing

**Neither a CLA nor a DCO.** `CONTRIBUTING.md`
([source](https://github.com/open-metadata/OpenMetadata/blob/main/CONTRIBUTING.md)) contains no
mention of a Contributor License Agreement, DCO sign-off, copyright assignment, or the licence
contributions are made under — it covers Slack, issues and community only. The same is true of the
docs site's [contribute page](https://docs.open-metadata.org/latest/developers/contribute) ("We ❤️
all contributions, big and small!"). I checked recent merged PRs (#32624, #32627, #32628) for a
CLA-assistant or DCO check run and found none — the only bot status was `claude`. The
`.github/workflows` directory contains no CLA or DCO workflow.

So inbound is governed solely by **Apache-2.0 §5** (inbound = outbound), which is what made the
2024 ingestion relicensing mechanically possible without collecting signatures: Apache-2.0 is
permissive, so Collate could take the combined work forward under CCL, with prior Apache-2.0 grants
surviving for the code as it stood at 1.5.0. Note the asymmetry this creates and that Collate did
not resolve: contributions merged into `ingestion/` *after* September 2024 arrive with no explicit
inbound licence statement at all, into a tree whose files are headed "Copyright 2025 Collate /
Licensed under the Collate Community License". Whether Collate relies on an implied licence, on the
GitHub Terms of Service §D.6 inbound=outbound provision, or on something else is **not verified** —
no primary source states it.

**Lesson for Nodqora:** a permissive inbound licence with no CLA still let this vendor relicense a
major subtree years later. A CLA is the belt-and-braces version, but permissive-inbound alone did
not foreclose the move. What it *does* forfeit is the ability to relicense retroactively — the
Apache-2.0 grant on pre-1.6.0 ingestion code is irrevocable, and anyone may fork from `1.5.0`.

---

### DataHub (DataHub Cloud, formerly Acryl Data)

#### Where the line is

Cleanly split, and — unlike OpenMetadata — the OSS repo is uniformly Apache-2.0 with no
source-available subtree. The line is drawn between two *products*, documented side by side in the
vendor's own [OSS vs Cloud comparison
guide](https://docs.datahub.com/docs/managed-datahub/managed-datahub-overview).

**DataHub Core (OSS)** gets a genuinely substantial catalog: "140+ Source Connectors with Unified
Search", "Column-Level Lineage & Impact Analysis", "Continuous Technical Metadata Sync", "GraphQL &
MCP Retrieval", "Full API & SDK", "Metrics & Semantic Models", "Data Contracts", "Incident
Management", "Business Glossary", "Data Ownership Management", "Quality & Health Status on Asset
Profiles", "Context Documents", "Multi-language Support", "Community Support".

**DataHub Cloud** adds, grouped by the category that matters for the open-core question:

- **AI / automation** (the largest single block): "AI Documentation Generation", "Cross-Platform
  Query History Mining", "Automated Context Updates", "Ask DataHub AI Agent + Plugins", "Agent
  Registry", "DataHub Hosted MCP Server", "Scoped MCP Servers", "AI Anomaly Detection", "Data
  Observability Agent".
- **Observability/monitoring**: "Freshness, Volume, Schema & Column Monitoring, Custom SQL Checks",
  "Data Health Dashboard", "Monitoring Rules", "Pipeline Circuit Breakers (API)", "Notifications for
  Data Assertions", "Secure In-VPC Quality Validation".
- **Governance workflow**: "Compliance Forms and Workflow Engine", "Metadata Tests", "Access Request
  Workflows", "Action Workflows", "Change Proposals: Documentation, Glossary, Tags & Ownership",
  "Bi-Directional Metadata Sync" (again: write-back to sources is paid).
- **Enterprise & security**: "Fine-grained Access Control", "AWS PrivateLink Support", "IP Address
  Restrictions", "In-VPC Remote Ingestion Agent", "99.5% Uptime SLA".
- **Support**: "Dedicated Customer Success", "Guided Implementation & Onboarding", "Private Slack
  Support Channel", "OSS Contribution Fast-Track".

Category read, and it matches OpenMetadata closely:

- **SSO is NOT behind the line.** OIDC SSO is documented as a plain self-hosted deployment option
  with no Cloud-only marker
  ([configure-oidc-react](https://docs.datahub.com/docs/authentication/guides/sso/configure-oidc-react)).
- **RBAC is mostly NOT behind the line, but is perforated.** The policies engine, roles, and
  metadata/platform privileges all work in Core, but individual privileges within it are flagged
  Cloud-only. From [the policies
  reference](https://docs.datahub.com/docs/authorization/policies), with the footer "[1] DataHub
  Cloud only": "View Entity", "Share Entity", "Propose Tags", "View Metadata Proposals", "Manage
  Monitors". Note what that first one means — *view-level* access control (the ability to hide
  assets from users at all) is the paid tier of RBAC, while edit-level control is free. That is a
  sharper, more defensible line than "RBAC is paid", and it is worth copying.
- **Behind the line:** monitoring/alerting, workflow engines, write-back, network-isolation
  features, SLA, and support.

#### Gating mechanism

**A genuinely separate proprietary build; nothing gates in the OSS repo.** I enumerated the
[repository root](https://github.com/datahub-project/datahub) and found no proprietary or
source-available directory — every top-level module (`metadata-service`, `metadata-io`,
`datahub-graphql-core`, `datahub-frontend`, `datahub-web-react`, `metadata-ingestion`,
`datahub-actions`, …) is under the single Apache-2.0
[LICENSE](https://github.com/datahub-project/datahub/blob/main/LICENSE). Code searches for
licence-validation logic in the OSS tree returned nothing.

The one place the two editions touch inside the OSS repo is cosmetic and, usefully, explicit about
the relationship —
[`ProductUpdateFlavor.java`](https://github.com/datahub-project/datahub/blob/main/metadata-service/configuration/src/productUpdateCiGate/java/com/linkedin/metadata/config/productupdate/ProductUpdateFlavor.java),
an enum with exactly two members, `CORE("DataHub Core", …)` and `CLOUD("DataHub Cloud", …)`, whose
Javadoc states:

> Core and cloud version independently, so each flavor resolves its own latest release.

Cloud release notes live in the OSS repo at `docs/managed-datahub/release-notes` and Cloud docs at
`docs/managed-datahub/`, but **only the docs** — no Cloud source. So the OSS repo carries the
*documentation* of the paid product and a "What's New" manifest for it, and nothing else.

Whether DataHub Cloud is internally a superset build of the Core codebase or a separate service
consuming it as a dependency is **not verified** — the independent version numbering (Core is on
`v1.x`, Cloud on `v0.3.x`) and the separate release-note trees are consistent with either, and no
primary source states which. What *is* verified is that the OSS repo contains no gate, no stub SPI,
and no licence check: whatever Cloud is, its code is not in this repository. That is the opposite of
Collate's approach and the cleaner of the two for a project that wants outside contributors.

#### Pricing unit

**Nothing published.** `https://datahub.com/pricing/` returns HTTP 404 as of 2026-09-05.
[datahub.com/cloud/](https://datahub.com/cloud/) names no tiers, no units and no figures; every
route is a demo booking:

> Work directly with a DataHub engineer to evaluate fit for your architecture, walk through
> technical integrations, and explore pricing and deployment options tailored to your use case.

So: **contact-us, with no published unit at all** — not even a seat/asset axis disclosed, which is
less transparent than Collate. DataHub Core is free to self-host. Any per-seat or per-asset claim
about DataHub Cloud is **not verified**; I found no vendor-published unit.

#### Did the line move?

No licence change has occurred. DataHub has been Apache-2.0 since LinkedIn open-sourced it — the
[NOTICE](https://github.com/datahub-project/datahub/blob/main/NOTICE) still opens "(c) 2015 LinkedIn
Corp. All rights reserved." — and remains uniformly Apache-2.0 today. I found no feature moving from
Core to Cloud or back; the comparison guide frames the relationship additively only:

> DataHub Cloud builds on the DataHub Core foundation with enterprise-grade capabilities including
> AI automation, advanced governance, operational reliability, and production support for
> mid-to-large organizations.

The corporate line moved instead of the licence line: **Acryl Data renamed itself DataHub in May
2025**, alongside a $35M Series B led by Bessemer
([datahub.com/news/series-b-announcement](https://datahub.com/news/series-b-announcement/)). Company,
OSS project and paid product now share one name — a naming choice Nodqora should decide on
deliberately, since it makes "is this feature in the free thing?" harder for users to answer.

**Governance: vendor-owned, not foundation-hosted.** DataHub does **not** appear on
[lfaidata.foundation/projects](https://lfaidata.foundation/projects/) — it is not an LF AI & Data
project at any stage. (This is a common misconception; it is not supported by the foundation's own
project listing.) Nothing external constrains where DataHub draws its line. The restraint shown —
uniform Apache-2.0, no source-available subtree, no stub gates — is therefore a *choice*, which
makes it the more interesting model of the two.

#### Inbound contribution licensing

**Neither a CLA nor a DCO.**
[`docs/CONTRIBUTING.md`](https://github.com/datahub-project/datahub/blob/main/docs/CONTRIBUTING.md)
covers feature requests, the RFC process, PR title format (conventional commits) and squash-merging,
and says nothing about a CLA, DCO, sign-off, copyright assignment or contribution licensing. I
inspected the check runs and commit statuses on recently merged PRs and found no CLA or DCO gate
(only `Meticulous Tests` and `Vercel`). `.github/workflows` contains no CLA or DCO workflow.

Inbound is therefore Apache-2.0 §5 inbound=outbound, exactly as with OpenMetadata. **DataHub is
consequently in the same legal position Collate was in before 2024 — free to relicense — and has not
used it.** They ship the paid edition from a separate tree that outside contributors never touch, so
the question of relicensing contributed code has never arisen.

That is the cleanest answer to the question Nodqora is actually asking: *you do not need a CLA to
keep the right to ship a proprietary Enterprise edition, provided the proprietary code lives in a
tree the community never contributes to.* A CLA becomes necessary only if you intend to relicense
code that outside contributors wrote — i.e. only if the Enterprise edition is a superset build that
absorbs community contributions into proprietary files.

---

### Marquez (with OpenLineage and Astronomer)

#### Where the line is

**There is no line. There is no paid edition of Marquez, and this is the negative result the brief
asked for.**

Marquez is Apache-2.0 in full
([LICENSE](https://github.com/MarquezProject/marquez/blob/main/LICENSE)), with no source-available
subtree, no enterprise directory, and no tiering. The
[repository root](https://github.com/MarquezProject/marquez) is a single ordinary OSS project: `api`,
`clients`, `chart`, `docker`, `web`, plus `GOVERNANCE.md`, `COMMITTERS.md`, `CONTRIBUTING.md`.
[marquezproject.ai](https://marquezproject.ai/) markets no commercial edition, no enterprise tier and
no vendor — it does not mention licensing, pricing or commercial offerings at all.

The commercial value moved entirely into a hosted product elsewhere, and the spec stayed neutral —
exactly the shape the brief hypothesised:

- **OpenLineage** is the spec, an independent LF AI & Data project.
- **Marquez** is its reference implementation, a separate LF AI & Data project.
- **Datakin** was the commercial product built on both, by the same people. Astronomer acquired
  Datakin in March 2022 as part of a $213M Series C
  ([Astronomer announcement](https://www.astronomer.io/blog/astronomer-acquires-datakin-the-data-lineage-tool/)),
  and Datakin's technology "will become an integral part of the Astronomer ecosystem."

So the monetisation is Astronomer's proprietary Astro platform, a *different product* that consumes
the open standard — not a paid tier of Marquez. Notably, Astronomer's acquisition post makes **no
commitment** to keeping Marquez or OpenLineage open source or neutral; it simply doesn't address it.
The neutrality comes from the foundation, not from a vendor promise (see §4).

Whether Astronomer's Astro lineage/Observe features are built on Marquez internally, or on a
rewritten proprietary backend, is **not verified**.

Marquez is not abandonware, which matters if you want to read this as a viable model rather than a
project that simply never got monetised: recent commits run to April 2026 (React 19 upgrade #3097,
a `/api/v1/jobs` query optimisation #3091, doc fixes), with contributions from multiple unaffiliated
people.

#### Gating mechanism

**None exists.** No licence-key code, no proprietary build, no stub SPI, no separate paid repo. The
project is one Apache-2.0 artefact, published as one Docker image, with a Helm chart in-tree.

For the distinction the brief cares about — superset build vs separate product consuming the OSS one
— Marquez is the purest example of the second: the paid product is a wholly separate proprietary
platform (Astro) at a different company, and its relationship to the OSS project is *the wire
protocol* (OpenLineage events), not a classpath. That is the strongest possible version of the
separation, and it is only available because the value being sold (orchestration + hosting) is
adjacent to, not carved out of, the OSS project.

#### Pricing unit

**Not applicable to Marquez** — it is free, self-hosted, with no commercial edition to price.

Astronomer's Astro pricing is a separate product's pricing and outside this slice; I did not verify
it against Astronomer's pricing page, so any unit is **not verified** here.

#### Did the line move?

**No line was ever drawn, and foundation governance is why it now cannot easily be.** This is the
governance-constrains-the-line case, and it is well documented.

Both projects are **Graduated** stage at LF AI & Data — the foundation's own
[projects listing](https://lfaidata.foundation/projects/) shows *Marquez* and *OpenLineage* both at
GRADUATED (and, for contrast, lists neither DataHub nor OpenMetadata at any stage). Marquez's
[GOVERNANCE.md](https://github.com/MarquezProject/marquez/blob/main/GOVERNANCE.md) states as a
founding principle:

> The resulting content is licensed under the Apache 2.0 license.

and vests authority in a committer body that elects its own members by majority vote, not in a
vendor:

> New committers are voted onto the committers list by the existing committers. … The committers
> vote, and if a majority agree then the requester is added to the committers list and given write
> access to the Git repositories.

[COMMITTERS.md](https://github.com/MarquezProject/marquez/blob/main/COMMITTERS.md) bears this out —
18 active committers spanning WeWork, Astronomer/Datakin alumni and independents. Project meeting
minutes live on the foundation's wiki (`wiki.lfaidata.foundation`), not a vendor's.

The binding constraint is in the foundation's own
[LF AI & Data Project Lifecycle Document](https://github.com/lfai/foundation/blob/main/LF%20AI%20&%20Data%20Project%20Lifecycle%20Document.md),
which requires a hosted project to:

> execute the Project Contribution Agreement transferring the project's assets to the Linux
> Foundation

and to:

> Have an OSI-approved license

**Those two clauses together foreclose the Collate move.** The assets — including the marks — sit
with the Linux Foundation, not with Astronomer; and the licence must remain OSI-approved, which the
Collate Community License is not (its "Excluded Purpose" anti-compete clause fails the OSI
definition's field-of-use restriction test). A vendor cannot unilaterally relicense Marquez into a
paid edition, and could not adopt a BUSL/CCL-style licence without leaving the foundation.

So yes: **Astronomer's paid product being a separate product rather than a superset build is a
consequence of foundation governance, not purely a strategic choice.** Even had Astronomer wanted a
"Marquez Enterprise", the Project Contribution Agreement and the OSI-licence requirement stand in the
way. The sources support stating that directly.

#### Inbound contribution licensing

**DCO, no CLA — and mandated by the foundation, not chosen by a vendor.**

[GOVERNANCE.md](https://github.com/MarquezProject/marquez/blob/main/GOVERNANCE.md):

> Each contribution is signed by the contributor to confirm they agree to our
> [Developer Certificate of Origin (DCO)](why-the-dco.md).

[CONTRIBUTING.md](https://github.com/MarquezProject/marquez/blob/main/CONTRIBUTING.md):

> The _sign-off_ is a simple line at the end of the message for a commit. All commits need to be
> signed. Your signature certifies that you wrote the patch or otherwise have the right to
> contribute the material (see [Developer Certificate of Origin](https://developercertificate.org))

This is enforced in CI — merged PRs (#3102, #3103) carry a `DCO` check alongside the CircleCI jobs.
The LF lifecycle document makes it a hosting requirement: projects must "Install the GitHub DCO app
on all repos", and the document makes **no mention of CLAs** anywhere.

The copyright consequence is decisive: contributors **retain** copyright. Source headers read
`Copyright 2018-2022 contributors to the Marquez project` / `SPDX-License-Identifier: Apache-2.0`
(mandated for all `.java`, `.sh` and `.py` files by CONTRIBUTING.md) — copyright is held by "the
contributors", collectively, not by Astronomer or any single company. A DCO certifies *origin*; it
grants the project nothing beyond the outbound licence and confers **no relicensing right** on any
vendor. `why-the-dco.md` explains the mechanism but does not itself discuss copyright ownership or
compare DCO to CLA — the ownership conclusion rests on the SPDX headers and the absence of any
assignment instrument, not on that file.

Net: Marquez has no CLA, cannot acquire one without foundation process, the marks and assets are the
Linux Foundation's, and the licence must stay OSI-approved. **A proprietary Marquez Enterprise is
structurally unavailable.** For Nodqora, the read is that donating to a foundation is a one-way door
on exactly the right the brief says must be preserved.

---

### Patterns: lineage and catalog products

**1. The gate that actually gets built is a no-op SPI in the OSS tree, not a licence key.** Neither
OpenMetadata nor DataHub contains cryptographic licence validation in its open code. OpenMetadata's
mechanism is an interface (`Limits`) with an inert default implementation and a
`Class.forName(config.getClassName())` at startup; the gate is possession of a jar, not verification
of a key. This is cheap to build, imposes near-zero complexity on the OSS product, and is trivially
removable by a determined forker — which the vendors evidently accept. If Nodqora wants a gate, this
is the shape to copy, and the honest framing is that it deters casual use, not adversaries.

**2. SSO and core RBAC stayed free in both commercial cases; the paid line sits at *workflow*,
*write-back*, and *monitoring*.** This is the most consistent and most counter-intuitive finding.
OpenMetadata ships eight SSO providers OSS; DataHub ships OIDC OSS; both ship a policy engine OSS.
What both put behind the line instead: metadata flowing *outward* to source systems ("Reverse
Metadata" / "Bi-Directional Metadata Sync"), approval and request workflow engines ("Access Request
Workflows", "Compliance Forms and Workflow Engine", "Change Proposals"), continuous monitoring and
alerting, and AI features. The classic "SSO tax" is absent from this category of product. DataHub's
refinement is worth stealing wholesale: not "RBAC is paid" but *view*-level access control is paid
("View Entity" — DataHub Cloud only) while edit-level control is free. For Nodqora — a topology and
health canvas — the analogues are alerting/monitoring rules, write-back to Kafka/Connect, and
approval workflow, not authentication.

**3. Retention/history/time-travel did not appear as a paid axis anywhere in this slice; freshness
did.** I found no evidence in any of the three of metadata history or time-travel being gated — but
Collate prices **data refresh frequency** as a tier lever (weekly / 8-hour / hourly). Degrading the
same feature on a timer, rather than removing it, is the retention-adjacent pattern that actually
shows up here. Note this is a lineage/catalog-slice observation; whether other categories gate
retention is out of scope and **not verified**.

**4. "Contact us" is the default, and the disclosed unit — where any is disclosed — is seats × assets.**
DataHub Cloud publishes no tiers, no units and no figures; its pricing URL 404s. Collate publishes
caps but no prices above Free. Neither vendor publishes a dollar figure for a paid tier. The one unit
disclosed anywhere in this slice is Collate's two-axis seat-count × data-asset-count, and it is not
coincidental that the asset axis is precisely what the `Limits` SPI exists to enforce — **the pricing
metric and the code gate are the same object**, which is worth designing for from the start rather
than retrofitting.

**5. The line moves toward the vendor, quietly, by directory, years after launch.** OpenMetadata is
the cautionary case: the README still says "released under the Apache License, Version 2.0" while
the UI (2023) and every ingestion connector (2024) are under a non-OSI, anti-SaaS-compete licence.
The 2024 change to ~80 connectors landed in a PR titled "Docs - Ingestion License" with the body "Add
Collate License to the ingestion framework" and no stated rationale. No blog post announcing it was
findable. The direction of travel is what a prospective adopter should price in — and it is why the
"is this project open source?" question has to be answered per-directory, not per-repo.

**6. Foundation hosting is a one-way door on the exact right Nodqora must keep.** The clean split is
between the two vendor-owned projects (OpenMetadata, DataHub — neither is an LF AI & Data project,
contrary to common belief) and the two foundation projects (Marquez, OpenLineage — both LF AI & Data
*Graduated*). The LF AI & Data lifecycle document requires transferring "the project's assets to the
Linux Foundation" and having "an OSI-approved license". Those two clauses jointly forbid a Collate-
style relicensing. Astronomer's commercial value therefore had to land in a separate proprietary
product (Astro) that talks to the OSS project over a wire protocol — **a consequence of governance,
not a strategic preference**. If Nodqora must retain the right to ship the same code under a
proprietary Enterprise licence, donating to a foundation forecloses it.

**7. You do not need a CLA — you need the proprietary code in a tree contributors never touch.**
None of the three projects has a CLA. OpenMetadata and DataHub have neither CLA nor DCO (bare
Apache-2.0 inbound=outbound, §5); Marquez has a foundation-mandated DCO with copyright retained by
"contributors to the Marquez project". Yet Collate relicensed a major subtree anyway, because
Apache-2.0 inbound is permissive enough to permit taking the combined work forward under other terms.
The two live options for Nodqora are therefore:

- **DataHub's model** — uniform Apache-2.0 OSS repo, no gates, no stubs, Enterprise in a separate
  private tree that no outside contribution ever enters. No CLA needed, because you never relicense
  anyone else's code. Cleanest for community trust; requires Enterprise to be genuinely separable.
- **Collate's model** — SPI stubs in the OSS repo, proprietary implementations injected at runtime,
  plus a source-available licence on the subtrees a competitor would need. Higher leverage, but
  costs README-level honesty and invites exactly the criticism OpenMetadata now attracts.

A CLA becomes strictly necessary only in a third case: an Enterprise edition that is a **superset
build absorbing community contributions into proprietary files**. If Nodqora avoids that shape, the
existing Apache-2.0 with no CLA already preserves the right the brief says is load-bearing. What
Apache-2.0-without-CLA does *not* preserve is the ability to relicense retroactively — every version
already shipped stays forkable under Apache-2.0 forever, as OpenMetadata's `1.5.0` ingestion tree
still is.

---
