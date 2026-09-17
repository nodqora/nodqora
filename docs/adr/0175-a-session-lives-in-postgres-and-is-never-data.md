# ADR-0175: A session lives in Postgres, holds no token, and is never data

- **Status**: Accepted
- **Date**: 2026-09-17
- **Ticket**: [Where a session lives, and what the browser holds](https://github.com/nodqora/nodqora/issues/142)

## Context

[ADR-0172](0172-community-authentication-is-oidc-and-saml-waits-to-be-asked-for.md)
made Community sign-in OIDC,
[ADR-0173](0173-an-install-without-sign-in-configured-is-closed-and-open-is-declared.md)
made an install without it closed, and
[ADR-0174](0174-sign-in-is-a-core-package-and-the-read-path-never-sees-who-is-asking.md)
put it in `io.nodqora.core.signin` with the redirect to the provider made by the
filter chain. None said where a signed-in session is kept, what the browser
holds, how long it lasts, or how it ends.

The facts that bore on it, verified in the tree and in Spring's source:

- **ADR-0174 already chose a server-side redirect.** It rejected the shell
  owning the redirect, which is where an SPA holding tokens would start.
- **[ADR-0075](0075-folds-serialize-on-the-environment-row.md) refuses to
  assume one instance** — "correct until someone sets `replicas: 2`". A session
  in memory would be that assumption.
- **Postgres is already required, and core owns its schema** through Flyway
  (`nodqora-core/src/main/resources/db/migration`).
- **The canvas polls** on the server's interval
  ([ADR-0059](0059-polling-contract.md)), so an open tab touches its session every
  few seconds and an idle timeout never fires while it is open.
- **Nodqora never calls anything with the user's access token.** `/api` serves
  Nodqora's own graph.
- **Spring's default for `oauth2Login` keeps tokens in memory**: an
  `InMemoryOAuth2AuthorizedClientService` keyed by principal name, outside the
  session, growing with every user who ever signed in.
- **Spring Session JDBC stores attributes with JDK serialization**, and the
  attribute here is Spring Security's `SecurityContext`. Its classes' serial
  compatibility is Spring's, and a row that cannot be deserialized throws on
  load.
- **An unreachable `issuer-uri` stops Boot's auto-configuration from starting.**
  In Spring Boot 3.4.1, `OAuth2ClientPropertiesMapper` calls
  `ClientRegistrations.fromIssuerLocation` while the
  `ClientRegistrationRepository` bean is created. That holds only for
  `spring.security.oauth2.client.*`; `nodqora.authentication` is bound by
  `signin`, which decides for itself.

## Decision

**A session is a row in Postgres behind one opaque cookie. It holds who signed
in and nothing they could sign in with, it ends within twelve hours, and it is a
cache that may be lost at any time.**

### 1. Where it lives, and what the browser holds

The session is stored by **Spring Session JDBC** in Postgres. Its tables come
from a **Flyway migration in core**, not `spring.session.jdbc.initialize-schema`,
so the schema has one owner.

The browser holds **one session cookie**: opaque, `HttpOnly`, `SameSite=Lax`,
`Secure` whenever the request is. No token, no claim, no second cookie.

**More than one replica may run with sign-in on.** The in-flight authorization
request is kept in the same session, so the callback may land on any replica,
and a restart signs nobody out.

### 2. What it holds

**The `OidcUser` — the ID token and its claims — and nothing else.** The
authorized-client repository is replaced by one that keeps nothing, so access
and refresh tokens are discarded once sign-in completes. A leaked session row
carries no credential usable at the provider.

Every authorization request uses **PKCE**, confidential client or not.

### 3. How long it lasts

| Limit | Default | Enforced by |
|---|---|---|
| Idle | **30 minutes** | Spring Session's `maxInactiveInterval` |
| Absolute | **12 hours** from sign-in | `signin`, against the session's creation time |

Both are configurable under `nodqora.authentication`; the key names are the
configuration grammar's. **The absolute limit is the revocation bound**: a user
disabled at the provider keeps their session until it passes, because nothing
in §2 lets Nodqora ask the provider again.

### 4. What an expiry looks like

An expired session and a request that never signed in are indistinguishable:
**`/api` answers `401` as problem+json** ([ADR-0060](0060-unversioned-unenveloped-problem-json.md)).
The shell answers a `401` with **a top-level navigation to the current URL**,
which the chain sends to the provider and back (ADR-0173 §3) — silently where
the provider's own session is alive, to its login page where not.

The canvas never goes on showing a graph it can no longer fetch. How the
navigation looks, and how the shell avoids looping when sign-in is not
configured, are the shell's.

### 5. Sign-out

**`POST /logout`** invalidates the session row, then:

- **where the provider's discovery document advertises `end_session_endpoint`**,
  sends the browser there with `id_token_hint` and
  `post_logout_redirect_uri` set to `/` — RP-initiated logout;
- **otherwise**, redirects to `/` having signed out locally only.

Landing on `/` asks for sign-in, so **the provider's login page is the
signed-out screen** and no new public path is needed. Signing out at the
provider may end the user's single sign-on elsewhere; that is what RP-initiated
logout means, and it is the only form in which the next person at a shared
screen cannot see the canvas.

### 6. CSRF

**Spring Security's CSRF protection stays on**, with the token stored in the
session (`HttpSessionCsrfTokenRepository`, the default). **The session state
carries the token** (ADR-0173 §3), and the shell signs out by submitting a form
— a top-level navigation, because the response is a redirect to the provider.
The OIDC callback is protected by `state` and PKCE. `/api` stays GET-only, so
CSRF never reaches the read path.

### 7. A session is never data

**A session row that cannot be read is no session.** `signin` deletes it and the
request continues unauthenticated, which §4 turns into a re-sign-in. An upgrade
that changes Spring Security's serialized form therefore signs everyone out
once, and does nothing worse.

Sessions are outside every promise: no backup is needed to keep them, and
[ADR-0153](0153-the-deployable-shares-the-version-and-the-number-promises-an-upgrade-path.md)'s
upgrade path does not cover them.

Rejected, and why:

- **A session in memory.** One replica only, contrary to ADR-0075, and every
  restart and upgrade signs everyone out.
- **Tokens held by the SPA, sent as a bearer.** A browser OIDC library, token
  storage within reach of XSS, refresh in the shell, and it overturns ADR-0174's
  server-side redirect.
- **A stateless signed or encrypted cookie.** A new key through
  `SecretReferences`, and no session can end before it expires.
- **Keeping the refresh token** to re-check the user at the provider. A live
  credential at rest in Postgres, and refresh logic nothing else needs, for a
  revocation bound §3 already sets.
- **Idle timeout only.** Polling keeps an open tab signed in for ever.
- **Tying the session to the ID token's `exp`.** Five minutes to an hour,
  set in someone else's system; a wall display would bounce all day.
- **Local-only sign-out.** Undone by the next visit on any provider with a live
  session.
- **OIDC back-channel logout.** An endpoint the provider must reach, for a bound
  §3 already provides.
- **CSRF off, with `GET /logout`.** Sign-out forceable from any page, and
  `SameSite=Lax` does not separate sibling hosts, which are same-site.
- **Spring's SPA pattern, a readable `XSRF-TOKEN` cookie.** A second cookie and
  a custom handler, when the session state is fetched anyway.
- **Keeping the canvas with a "session expired" notice.** A frozen health view
  that still looks like one — stale reading as healthy.
- **Silent re-sign-in in a hidden iframe.** Broken by third-party cookie
  blocking for most providers, and a second sign-in path.
- **JSON serialization through Spring Security's Jackson modules.** Survives more
  upgrades, and puts sign-in at the mercy of whatever claim types a provider
  sends, to preserve something worth one redirect.
- **Clearing sessions whenever the deployed version changes.** Signs everyone out
  on every patch, and replicas mid-rollout would clear each other's.

## Consequences

- **Every open canvas reads its session row on every poll**, and Spring Session
  writes back the last-access time. Cheap on an indexed primary key, and the
  first write traffic `/api` has ever caused.
- **Core's schema gains Spring Session's tables**, created whether sign-in is
  configured or not; with `none` they stay empty.
- **Spring Session's expired-session cleanup runs on every replica.** It is an
  idempotent `DELETE`, so duplicates are harmless.
- **The session state gains a CSRF token.** Its shape remains the shell's.
- **Revocation is not immediate.** A user removed at the provider can read
  every environment for up to twelve hours, or whatever the operator sets.
- **Behind a TLS-terminating proxy**, the cookie's `Secure` flag and the OIDC
  `redirect_uri` both depend on forwarded headers being trusted. That belongs to
  the configuration grammar and `docs/install.md`.
- **Left to the configuration grammar:** whether provider discovery is eager —
  failing startup, as Boot's own auto-configuration does — or lazy.
- **No glossary term is added.** A session is Spring's concept, not the domain's,
  as ADR-0174 added no identity type.
