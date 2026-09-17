# ADR-0178: Sign-in is proven against a stub, once against Keycloak, and once by hand

- **Status**: Accepted
- **Date**: 2026-09-17
- **Ticket**: [How sign-in is tested](https://github.com/nodqora/nodqora/issues/155)

## Context

[ADR-0174](0174-sign-in-is-a-core-package-and-the-read-path-never-sees-who-is-asking.md)
put every consumer of core behind a filter chain, including core's own tests and
the golden documents, and left what they declare to the configuration grammar and
the test strategy.
[ADR-0176](0176-sign-in-is-one-provider-declared-once-and-a-wrong-declaration-refuses-to-start.md)
settled the grammar, and
[Bind nodqora.authentication, and refuse a wrong declaration at startup](https://github.com/nodqora/nodqora/issues/152)
built it. Building the chain
([ADR-0175](0175-a-session-lives-in-postgres-and-is-never-data.md), ADR-0176 §4)
and the shell's half
([ADR-0177](0177-the-shell-reads-a-session-state-and-a-401-asks-it-why.md)) both
waited on how either would be proven.

The facts that bore on it, verified in the tree:

- **Docker is already a test dependency.** `NodqoraIntegrationTest` starts a
  Postgres Testcontainer.
  [ADR-0099](0099-the-reference-fixture-splits-into-inputs-assertions-and-prose.md)'s
  refusal of Testcontainers covers plugin inputs, not Nodqora's own dependencies.
- **No workflow runs tests on a pull request.** `release.yml` is the only
  workflow, and the release depends on every subproject's `build`. The suite runs
  on a developer's machine and at release.
- **Every app test but one shares a context** through `NodqoraIntegrationTest`,
  and the `test` task loads `fixtures/reference-pipeline/application-demo.yaml`,
  which declares no `nodqora.authentication`. `UnconfiguredInstallTest` removes
  that file and asserts an empty roster from `/api/meta`.
- **The frontend is tested with vitest and nothing else.** Its tests already read
  `fixtures/golden/*.json`, the same documents `Golden` asserts in Java, so the
  graph and state contracts are held on both sides without a browser.
- **Core already exposes a `Clock` bean.** Spring Session JDBC checks the idle
  limit against system time and offers no clock to inject; the absolute limit is
  Nodqora's own check (ADR-0175 §3).
- **The demo realm allows only `http://localhost:8080/*`** as the `nodqora`
  client's redirect and post-logout URIs, and Keycloak matches the port exactly.
  App tests run on a random port.
- **Only `nodqora-app` has Postgres in its tests.** The chain is core's, but
  ADR-0174 §4's table includes the assembly's static-asset chain and
  `CommunityFrontend`'s paths.

## Decision

**Sign-in's behaviour is proven in `nodqora-app` against a stub provider, its
flow once against Keycloak, its contract with the shell by golden documents, and
the browser round trip by hand before the tag.**

### 1. Read-path tests declare `none`

`NodqoraIntegrationTest` declares `nodqora.authentication: none` as a test
property. The golden documents, the incident scenario and the routing tests are
about what the canvas shows, and ADR-0174 §2 makes that blind to who is asking.
Tests that exercise sign-in declare a provider themselves.

`application-demo.yaml` stays without the key, so `bootRun` and the install docs'
worked example are not taught the open install.

`UnconfiguredInstallTest` still loads no file, so it becomes the test of **sign-in
not configured**: `/api/meta` answers `401`. Its empty-roster assertion moves to a
context that declares `none`.

### 2. A stub provider proves the behaviour

A provider in the test process serves discovery, JWKS, token and end-session
endpoints, and signs ID tokens with the `nimbus-jose-jwt` the OAuth2 client
already brings. Against it, `nodqora-app`'s tests prove:

- ADR-0173 §3's table: `/api` answers `401`, SPA routes redirect to the provider,
  static assets, `/session` and `/healthz` are served;
- the `redirect_uri` built from `base-url` and never from forwarded headers;
- the code exchange into a session, and the display name's fallback from
  `name-claim` to `preferred_username` to `sub`;
- RP-initiated sign-out, and the local branch when discovery is failing;
- ADR-0176 §4: an unreachable provider and a mismatched `issuer` each give a
  `503` to a browser that must sign in, an `ERROR`, a `401` on `/api` and a `200`
  on the probe; a signed-in session keeps reading; a failed discovery is not
  cached, and the next sign-in succeeds once the provider is back.

### 3. One test runs the flow against Keycloak

One Testcontainer test imports `demo/keycloak/nodqora-realm.json`, signs `ada`
and `nameless` in through Keycloak's own login form, reads `/session` for each,
and signs one out.

The realm's origin is the only thing it changes. The test **chooses a free port
first**, starts Keycloak with the realm file's `http://localhost:8080` replaced by
that port, then starts the app on it with `issuer` set to Keycloak's mapped URL.
Users, claims, the client secret and the SSO limit are proven as the demo has
them.

**The realm's redirect URI narrows to `/login/oauth2/code/oidc`** when the chain
is built, as ADR-0176's consequences allowed, so this test proves the narrow URI
and not the wildcard.

### 4. The two lifetimes are proven where they are owned

- **The absolute limit** is Nodqora's check, and the revocation bound. It reads
  core's `Clock`, and its test replaces that with one it moves forward.
- **The idle limit** is Spring Session's. What Nodqora owns is passing
  `session.idle` through, so the test asserts, through Spring Session's
  `SessionRepository`, that a new session's `maxInactiveInterval` equals it.

### 5. `/session` gets golden documents

`fixtures/golden/` gains one document per session state (sign-in not configured,
open, signed out, signed in) and the `401` problem body. Java asserts the chain
produces each; vitest feeds the same files to `client.ts` and the session area,
and proves the loop guard with fake timers. There is no browser runner.

### 6. The browser round trip is checked by hand, once, before the tag

`demo/README.md`'s *Signing in against it* section gains a checklist, run against
the homelab Keycloak:

1. an unauthenticated deep link reaches the provider and lands back on that link;
2. `ada` shows her name, and `nameless` shows `nameless`;
3. *Sign out* ends at `/`, and a reload asks for a password;
4. with `session.idle: 1m`, an idle tab re-navigates once and does not loop;
5. with Keycloak scaled to zero, a signed-in tab keeps reading and a new private
   window gets the `503`.

It is run before the release is tagged, and the results are recorded on the
release ticket.

### 7. Placement

The stub, the Keycloak test and the sign-in integration tests live in
`nodqora-app`'s test sources, beside `NodqoraIntegrationTest` and its Postgres.
`nodqora-core` keeps the tests that need neither an assembly nor a database: the
binder, the guard rules, and the absolute-lifetime check. Core publishes no test
fixture.

Rejected, and why:

- **`none` in `application-demo.yaml`.** One line, and `bootRun` and the install
  docs' worked example become open installs.
- **A signed-in session on every read-path test.** Authentication wired into
  ninety tests about something else, to prove a path §2 already proves once.
- **Keycloak for every sign-in test.** Real, and slow; an issuer mismatch is
  awkward to stage and an outage means stopping a container mid-test.
- **The stub alone.** It proves Nodqora's reading of OIDC, not whether the flow
  works against the provider the demo and the docs tell people to run.
- **Spring Security's `oidcLogin()` in place of a provider.** It establishes a
  principal without a redirect, a callback, discovery or sign-out, which is most of
  what there is to prove.
- **Real time with tiny session limits.** Slow, and flaky on a loaded machine at
  release.
- **Back-dating the session row** to prove idle expiry. A side channel into
  Spring Session's schema, which breaks when that schema changes.
- **Hand-written `/session` bodies in vitest.** Nothing stops them drifting from
  what the chain sends.
- **A browser runner** (Playwright). A new tool, browsers to install and the
  flakiest kind of test, for a round trip the Keycloak test and the goldens
  already bracket.
- **Running the Keycloak test on port 8080.** It fails whenever `bootRun` or a
  compose install holds the port.
- **Adding the test's redirect URI through Keycloak's admin API.** More code and
  admin credentials, to avoid a one-line substitution.
- **The hand check after each merge.** Three runs where one, before the tag, is the
  last point a missed browser detail can be caught.
- **Sign-in tests in `nodqora-core` with their own Postgres.** Core's tests cannot
  see the assembly's static-asset chain, so ADR-0173 §3's table would be proven in
  halves, with two Postgres setups to keep in step.
- **A core test fixture for the stub.** Reusable by Enterprise's assembly, which
  is a seam built before its consumer asks (ADR-0115).

## Consequences

- **The chain and the shell's half can be built.** The chain's PR carries the stub,
  the Keycloak test, the `/session` goldens, the `Clock` in the absolute check and
  the narrowed realm redirect. The shell's PR reads those goldens, so it follows
  the chain rather than running beside it.
- **`nodqora-app`'s suite grows a Keycloak image pull and roughly twenty seconds**,
  paid locally and at release.
- **Keycloak's login form is part of a test.** A Keycloak upgrade that changes the
  form breaks the test, which is when the demo's pinned version should be looked
  at anyway.
- **A browser detail can reach `main` unnoticed** and is caught at the latest
  before the tag. That is accepted for a round trip whose parts are each proven.
- **No glossary term is added.**
