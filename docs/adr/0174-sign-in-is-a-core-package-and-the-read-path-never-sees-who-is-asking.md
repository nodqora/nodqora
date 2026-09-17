# ADR-0174: Sign-in is a core package, and the read path never sees who is asking

- **Status**: Accepted
- **Date**: 2026-09-17
- **Ticket**: [What core knows about who is asking](https://github.com/nodqora/nodqora/issues/141)

## Context

[ADR-0172](0172-community-authentication-is-oidc-and-saml-waits-to-be-asked-for.md)
made Community sign-in OIDC, and
[ADR-0173](0173-an-install-without-sign-in-configured-is-closed-and-open-is-declared.md)
made an install without it closed. Neither said where the code lives, and that
is a boundary decision rather than wiring: Community sign-in is **the first
Community consumer that could license an identity seam** under
[ADR-0115](0115-a-core-seam-requires-a-community-consumer.md), and Enterprise's
view-scoping ([ADR-0109](0109-authentication-is-community-and-view-scoping-is-enterprise.md))
will stand in the same request path.

The facts that bore on it, verified in the tree:

- **Enterprise's assembly does not contain `nodqora-app`.**
  [ADR-0114](0114-the-enterprise-edition-is-a-second-assembly.md) has
  `nodqora-enterprise-app` depend on published `nodqora-core`,
  `nodqora-plugin-api` and the plugins. What lives in `nodqora-app`, Enterprise
  rebuilds.
- **Configuration binds in core.** `NodqoraProperties` and `SecretReferences`
  are in `nodqora-core`, and ADR-0173's `nodqora.authentication` is a key there.
- **[ADR-0116](0116-the-substitution-set-is-two-stores-and-a-package-move.md)
  already assumes the principal is not core's.** Group synchronisation "reads
  claims off the principal Community already establishes", and Enterprise's
  controller "filters the document against the principal's scope". Neither asks
  core for a principal type.
- **ADR-0116 is not yet built.** `GraphStore` and `NodeStateStore` are still
  concrete, and `GraphDocuments` still sits in `io.nodqora.core.api`.
- **ADR-0115's identifier check was never written.** `CoreKnowsNoPluginTest`
  bans `kubernetes`, `k8s`, `kafka` and `connect`; nothing bans `enterprise`,
  `licence`, `license`, `rbac` or `tenant`.
- **`CommunityFrontend` is in `nodqora-app` on purpose**: its paths are a
  property of the assembly, and
  [ADR-0117](0117-enterprise-builds-its-own-frontend.md) has Enterprise build its
  own frontend.
- **"Identity" is taken.** `CONTEXT.md` uses it for node identity resolution.

## Decision

**Sign-in is a Community feature in core, in `io.nodqora.core.signin`. Nothing
else in core knows who is asking.**

### 1. Sign-in lives in `nodqora-core`

The filter chain, the session, ADR-0173's three states, the session state and
the probe are all in `io.nodqora.core.signin`. Core gains
`spring-boot-starter-security` and the OAuth2 client.

This is a feature, not a seam: Community uses it in full, and no part of it is
a no-op. Its placement is argued from the closed default — **ADR-0173's
guarantee travels with core into any assembly**, and an assembly that forgets to
build sign-in cannot serve `/api` unauthenticated.

### 2. The read path is identity-blind

`GraphController`, `GraphDocuments` and everything beneath them take no
principal and see no identity type. **Core defines no Nodqora type for who is
asking.** The only code that reads identity is the sign-in package, and it reads
Spring Security's own (`OidcUser` for the session state's name).

Enterprise's scoped controller reads `SecurityContextHolder` itself. The
principal Community establishes is Spring Security's, and establishing it is the
whole contract.

### 3. Enterprise extends sign-in by addition, or by dropping the package

Core carries no hook for it: no `@ConditionalOnMissingBean` on the chain, no
customiser interface. Enterprise **adds** beans Spring Security already looks up
from the context — an `OidcUserService`, a `GrantedAuthoritiesMapper` — or, if
that is not enough, **omits `io.nodqora.core.signin` from `scanBasePackages`**
and ships its own, as ADR-0116 does for `io.nodqora.core.api`.

Dropping the package is **permitted and not expected**. ADR-0116's substitution
set stays at three changes; a package that can be excluded whole is the
separately-scannable layering ADR-0116's consequences already require.

### 4. The chain fails closed on everything core does not own

| Path | Sign-in configured | Sign-in not configured |
|---|---|---|
| `/api/**` | `401` | `401` |
| The session state and the probe (paths in `signin`) | served | served |
| Anything else | **sign-in required**: a browser is redirected to the provider | served, so the first-run screen can render |

**The assembly adds its own public paths.** `nodqora-app` declares a
higher-priority `SecurityFilterChain` beside `CommunityFrontend` permitting the
frontend build's static assets. An assembly that forgets gets a page that asks
for sign-in, not a leak. The session state's and probe's paths and shapes
remain the shell's decision (ADR-0173 §3).

### 5. The guard tests grow three rules

- **No class outside `io.nodqora.core.signin` depends on
  `org.springframework.security..`.** This is what makes §2 checkable. A future
  Community feature that genuinely wants identity in the read path fails it, and
  that failure is the moment to reopen this ADR — with the consumer in hand.
- **ADR-0115's five identifiers join the source scan**, as their own list with
  their own reason. The sign-in package is the first core code tempted to write
  where Enterprise will map groups.
- **Core source says "OIDC", never "OpenID Connect".** `connect` is banned as a
  word, comments included, and ADR-0015 already prefers renaming to carving
  exceptions.

Rejected, and why:

- **Sign-in in `nodqora-app`.** Core stays free of a security dependency, and
  Enterprise must rebuild sign-in; if it forgets, core serves every environment
  unauthenticated — ADR-0116's "unscoped route in an install sold on RBAC", one
  layer earlier. It also leaves `nodqora.authentication` bound in core and acted
  on elsewhere.
- **A `Viewer` record in core**, built by sign-in and read by Enterprise. Its
  one Community consumer is the session state's name, a field read from
  `OidcUser` in the same package; the type would exist for Enterprise's claims.
- **The principal as a read-path parameter.** Community passes it and ignores
  it: ADR-0115's no-op seam, wearing a parameter.
- **`@ConditionalOnMissingBean` on Community's chain.** Community never supplies
  a second chain, so the condition is for the paid half — and any stray chain
  bean silently removes the closed default.
- **A `SignInCustomizer` interface.** The no-op SPI ADR-0115 names.
- **Spring's `PathRequest.toStaticResources().atCommonLocations()`.** It covers
  `/css`, `/js`, `/images`, `/webjars` and favicons, not the Vite build's
  `/assets`; making it work means core learning the Community frontend's layout.
- **Every non-`/api` path public, the shell redirecting to the provider.** More
  SPA-native, but it overturns ADR-0173's server-side redirect and turns every
  unrouted path into a `200`, undoing the closed grammar `CommunityFrontend`
  keeps. If the session decision finds the shell must own the redirect, it
  reopens this.

## Consequences

- **Every consumer of core runs behind a filter chain**, including core's own
  tests and the golden-document tests in `nodqora-app`. They declare `none` or a
  test provider, which the configuration grammar and the test strategy settle.
- **Enterprise couples to Spring Security's types**, not to a Nodqora one. A
  change of OIDC library in Community breaks Enterprise without warning — the
  drift tax ADR-0115 already accepts, in a new place.
- **An Enterprise build that drops `signin` re-implements ADR-0173 by hand**,
  which is the risk §1 moved sign-in into core to avoid. It is allowed, and it is
  Enterprise's to get right.
- **Adding a frontend path is now two edits in the assembly** — the forward in
  `CommunityFrontend` and, if public, the assembly's chain. Forgetting the second
  fails closed.
- **No glossary term is added.** Core has no identity type to name, and that is
  deliberate; `CONTEXT.md`'s **sign-in not configured** and **open install**
  already cover the states.
- **ADR-0115's word check lands with the sign-in package**, closing debt the ADR
  recorded as a decision but never enforced.
