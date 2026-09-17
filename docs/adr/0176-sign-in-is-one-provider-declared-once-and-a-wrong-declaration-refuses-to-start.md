# ADR-0176: Sign-in is one provider declared once, and a wrong declaration refuses to start

- **Status**: Accepted
- **Date**: 2026-09-17
- **Ticket**: [How sign-in is configured](https://github.com/nodqora/nodqora/issues/148)

## Context

[ADR-0172](0172-community-authentication-is-oidc-and-saml-waits-to-be-asked-for.md)
made Community sign-in OIDC.
[ADR-0173](0173-an-install-without-sign-in-configured-is-closed-and-open-is-declared.md)
made `nodqora.authentication` take a provider or `none`, with an absent key
meaning **sign-in not configured**.
[ADR-0174](0174-sign-in-is-a-core-package-and-the-read-path-never-sees-who-is-asking.md)
had `io.nodqora.core.signin` bind the key, and
[ADR-0175](0175-a-session-lives-in-postgres-and-is-never-data.md) made the two
session lifetimes configurable. All three left the key's grammar to this ticket,
along with what a partial declaration, a contradictory one and an unreachable
provider each do.

The facts that bore on it, verified in the tree, in Spring's source and against
[the demo's Keycloak](https://github.com/nodqora/nodqora/issues/143):

- **Nodqora already has two failure grammars, and they are not the same.**
  Configuration that is *wrong* stops startup and names the key:
  `BoundConfiguration` throws on an unknown plugin or a failed constraint, and
  `SecretReferences` throws on an unset `${env:}` or an unreadable `${file:}`. A
  dependency that is *unreachable* does not: a Kafka that is down is health, not
  a crash. ADR-0173's boot-and-explain covers only the **absent** key, where
  there is nothing wrong to name.
- **Spring flattens every property source into one namespace.**
  `NODQORA_AUTHENTICATION=none` in a compose file and an `oidc:` block in the
  mounted `application.yaml` both bind, and
  [ADR-0152](0152-the-image-ships-no-environments-and-config-arrives-as-two-mounts.md)
  already found that a later source cannot delete an earlier key. So `none`
  beside a provider is reachable, not hypothetical.
- **Spring's placeholder resolver eats secret references**
  ([ADR-0165](0165-plugin-slices-bind-literally-and-secret-references-are-the-only-resolver.md),
  [#111](https://github.com/nodqora/nodqora/issues/111)). The client secret is
  the first secret outside `nodqora.environments`.
- **Boot backs off when a `ClientRegistrationRepository` bean exists.** `signin`
  supplies one, so `spring.security.oauth2.client.*` keys would bind to nothing,
  silently.
- **Boot trusts forwarded headers on Kubernetes and nowhere else by default.**
  With `server.forward-headers-strategy` unset, Tomcat's `RemoteIpValve` is
  installed only when Boot detects a cloud platform, and Kubernetes is detected
  from `KUBERNETES_SERVICE_HOST`. The same image would build its `redirect_uri`
  from `X-Forwarded-*` in the homelab and from the socket under compose.
- **A wrong `redirect_uri` fails at the provider, where Nodqora cannot see it.**
  Keycloak answers an unregistered one with a `400` error page, not a redirect
  back.
- **A signed-in user never needs the provider again** until their session ends.
  The session holds the `OidcUser` and no token (ADR-0175 §2).
- **Only `sub` and `preferred_username` arrive for every user.** The demo's
  `nameless` user has no `name`. Keycloak sends `profile` and `email` claims even
  when only `openid` is requested, so a scope list can add claims there but not
  remove them.

## Decision

**`nodqora.authentication` is either `none` or one OIDC provider with its
session limits. A declaration that is wrong or contradictory stops startup and
names the key. A provider that cannot be reached does not: Nodqora boots, and
only new sign-ins wait for it.**

### 1. The grammar

```yaml
nodqora:
  authentication:
    oidc:
      base-url: https://nodqora.example.com        # required
      issuer: https://idp.example.com/realms/acme  # required
      client-id: nodqora                           # required
      client-secret: ${file:/run/secrets/oidc}     # optional
      scopes: [openid, profile]                    # default
      name-claim: name                             # default
    session:
      idle: 30m                                    # default
      absolute: 12h                                # default
```

or

```yaml
nodqora:
  authentication: none
```

- **One provider per install.** An estate with two identity providers brokers
  them through one, as ADR-0172 already has a SAML-only estate do. The `oidc:`
  level is kept so a future SAML sibling (ADR-0172) has somewhere to go without
  renaming anything.
- **The registration id is the constant `oidc`.** Spring's callback is therefore
  always **`/login/oauth2/code/oidc`**, and sign-in starts at
  `/oauth2/authorization/oidc`. Both are public paths in `signin`'s chain.
- **`base-url`** is Nodqora's external URL, exactly as a browser reaches it.
  `redirect_uri` is `base-url` + `/login/oauth2/code/oidc`, and
  `post_logout_redirect_uri` is `base-url` + `/`. The session cookie is `Secure`
  when its scheme is `https`, which refines ADR-0175 §1's "whenever the request
  is". **Forwarded headers are never consulted by sign-in**, and every other
  redirect the chain issues is relative, so no `Location` header carries a
  scheme guessed from the socket.
- **`client-secret`** present makes a confidential client
  (`client_secret_basic`). Absent, the client is public and PKCE (ADR-0175 §2)
  is its only proof.
- **`scopes`** defaults to `[openid, profile]`. Nodqora shows a name and never
  uses an email. A configured list without `openid` has it added, because
  without it the flow is not OIDC.
- **`name-claim`** defaults to `name`. When the claim is absent, the displayed
  name falls back to `preferred_username`, then `sub`. The fallback is not
  configurable.
- **`session.idle` and `session.absolute`** are ADR-0175 §3's two limits, as
  Spring durations.

### 2. Secrets have one resolver

`nodqora.authentication` is bound **with no placeholder resolver**, as ADR-0165
binds `nodqora.environments`, so `${env:}` and `${file:}` reach
`SecretReferences` as written. They are resolved at startup, and an unset
variable or unreadable file stops it, as it does in a plugin slice. The escaped
form `\${...}` is a reference here too, for the same reason.

### 3. What refuses to start

Each of these stops startup with a message naming the key:

| Declaration | Why it is wrong |
|---|---|
| `none` beside anything under `oidc` or `session` | contradictory: failing open or ignoring a declaration are both guesses |
| `oidc` without `base-url`, `issuer` or `client-id`, or `session` without `oidc` | incomplete |
| `base-url` or `issuer` not an absolute `http`/`https` URL, or `base-url` with a path | malformed |
| `session.idle` longer than `session.absolute`, or either not positive | the idle limit could never fire |
| a secret reference that does not resolve | ADR-0165's rule, unchanged |
| **any key under `spring.security.oauth2.client`** | a second grammar Nodqora does not read; the message names `nodqora.authentication.oidc` |

**Only the absent key boots and explains** (ADR-0173 §1). A key that is there and
wrong is a typo the operator can be told about in the log, and guessing past
it would either fail open or silently ignore what was written.

### 4. An unreachable provider is an outage, not a misconfiguration

**Discovery is lazy.** Nodqora does not contact the provider at startup. The
discovery document is fetched on the first request that needs it, **cached once
it succeeds**, and **not cached when it fails**, so the next sign-in retries.

While discovery fails, whether because the provider is unreachable or because
its document's `issuer` does not match `issuer`:

- **anyone already signed in keeps reading**, because their session needs
  nothing from the provider;
- **a browser that must sign in** gets a server-rendered **`503`** naming the
  issuer that could not be reached, and nothing else about the install. The
  redirect it replaces would have disclosed the same URL;
- **`/api` still answers `401`** to a request without a session, and **the probe
  still answers `200`**: Nodqora is up, and its provider is not;
- **each failed attempt logs an `ERROR`** naming the issuer and the cause.

**Sign-out** during an outage invalidates the session row and redirects to `/`
locally (ADR-0175 §5's second branch), because whether the provider advertises
`end_session_endpoint` cannot be known.

Rejected, and why:

- **A map of providers.** It needs a provider chooser, which is a public page
  outside ADR-0173 §3's table. A mounted file also cannot delete a provider the
  jar or another source declared (ADR-0152). Brokering already covers the estate
  that wants two.
- **A flat grammar with no `oidc:` level.** One line shorter, and no room for
  the SAML ADR-0172 says would be Community.
- **Booting to the first-run screen on a wrong declaration.** It fails closed,
  but the screen would have to describe the broken key before sign-in, and it
  would be the only wrong key in Nodqora that does not stop startup.
- **The provider winning over `none`,** with a `WARN`. Fails closed, and still
  silently ignores something the operator wrote.
- **Eager discovery, failing startup,** as Boot's own auto-configuration does.
  A typo in the issuer would surface at deploy, but Nodqora would crash-loop
  whenever someone else's provider is down, a restart during that outage would
  take the canvas from people already signed in, and `depends_on` cannot wait
  for an IdP the compose file does not run. An issuer typo surfaces at the first
  sign-in instead, with an `ERROR` and a `503` that name it.
- **Trying discovery at startup and then going lazy.** A startup that blocks on
  a network timeout, to write a log line the first sign-in writes anyway.
- **`base-url` optional, falling back to the request.** One line shorter for a
  localhost install, and a proxy user who forgets it is stranded on the
  provider's error page with nothing in Nodqora's log.
- **Trusting forwarded headers everywhere** (`forward-headers-strategy:
  framework`). No new key, and any client that reaches the port directly can set
  `X-Forwarded-Host` into the `redirect_uri`, on a compose file that binds every
  interface.
- **Honouring `spring.security.oauth2.client.*` as a fallback.** Two grammars,
  two secret resolvers (#111), and `base-url`, the fixed registration id and
  lazy discovery restated for the second.
- **Ignoring Spring's keys.** Boot backs off, and the operator sees a closed
  install with no reason why.
- **A required `client-secret`.** Excludes public clients, which Keycloak, Zitadel
  and authentik all offer and which PKCE already protects.
- **`openid profile email`.** Asks every user to consent to an address Nodqora
  never reads.
- **A configurable list of name claims.** Configuration for a label.

## Consequences

- **The first sign-in configuration is five lines**: `base-url`, `issuer`,
  `client-id`, and usually `client-secret`, under `oidc:`. The same `base-url`
  is what the operator registers at the provider, so `docs/install.md` shows
  both side by side.
- **A proxy in front of Nodqora needs no Nodqora configuration** beyond
  `base-url`, and forwarded-header trust stays whatever Boot's platform default
  is for everything that is not sign-in.
- **An issuer typo is found at first sign-in, not at deploy.** It announces
  itself with a `503` and an `ERROR`, and it cannot take down a running canvas.
- **Discovery's cache lives per replica and per process.** A restart refetches
  it; a provider that rotates signing keys is followed by Spring's JWK set
  handling, not by this cache.
- **The demo's provider caps its SSO session at 10 hours**, shorter than
  `session.absolute`'s 12. After 10 hours a return to the provider asks for a
  password while Nodqora's session is still alive. That is the provider's
  policy, and `docs/install.md` should say that the shorter of the two wins at
  the provider.
- **`signin` owns a startup check over `spring.security.oauth2.client`**, which
  is the only place core reads a `spring.*` key to reject it.
- **`demo/nodqora/local.yaml` gains an `oidc:` block**, whose `redirect_uri` is
  `http://localhost:8080/login/oauth2/code/oidc`. The demo client's wildcard
  redirect can narrow to it.
- **No glossary term is added.** `base-url` and the claims are Spring's and
  OIDC's concepts, not the domain's.
