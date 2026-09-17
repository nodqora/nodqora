# ADR-0173: An install without sign-in configured is closed, and open is declared

- **Status**: Accepted
- **Date**: 2026-09-17
- **Ticket**: [What an install with no identity provider configured does](https://github.com/nodqora/nodqora/issues/140)
- **Amends**: [ADR-0132](0132-upsell-rides-the-control-path-and-never-the-honesty-layer.md), to six nouns

## Context

[ADR-0172](0172-community-authentication-is-oidc-and-saml-waits-to-be-asked-for.md)
settled that Community signs users in with OIDC against their own identity
provider. It left open what Nodqora does when no provider has been configured,
and every install today is exactly that: `v0.1.x` and `v0.2.0` authenticate
nobody, and [ADR-0150](0150-the-community-distribution-is-one-public-image-and-a-compose-file.md)'s
install is `docker compose up` then `http://localhost:8080`.

The facts that bore on it, verified in the tree:

- **The shipped `compose.yaml` publishes `"8080:8080"`** — every interface, not
  loopback. An open install is open to whoever can reach the host.
- **[ADR-0153](0153-the-deployable-shares-the-version-and-the-number-promises-an-upgrade-path.md)
  lets a minor release require an `application.yaml` edit.** A new required key
  on upgrade is within what the number promises, if the release says so.
- **[ADR-0156](0156-the-unconfigured-install-has-a-first-run-screen-that-replaces-the-shell.md)
  already answers an unconfigured install by booting and explaining**, not by
  refusing to start, and put that screen on ADR-0132's honesty layer.
- **`/api/meta` is not harmless.** It returns the environment roster and the
  plugin roster — a description of what an organisation runs.
- **[ADR-0109](0109-authentication-is-community-and-view-scoping-is-enterprise.md)
  makes authenticating the whole grant:** anyone signed in sees every
  environment. So what is withheld before sign-in is everything an environment
  would show, including the fact of its existence.

## Decision

**An install with no identity provider configured is closed. Running open is a
declaration — `nodqora.authentication: none` — and never an omission.**

### 1. Sign-in not configured: the process boots, and serves nothing

With neither a provider nor `none` declared, Nodqora **starts** and the read API
answers **`401`** to all three of ADR-0053's GETs. The shell is replaced, on
every route, by a first-run screen:

> **No identity provider is configured.**

It states the route and claims nothing about compliance with it, in ADR-0156's
grammar: declare `nodqora.authentication` — a provider, or `none` — in
`/app/config/application.yaml`, **the same file that declares
`nodqora.environments`**, then restart and reload. Naming the shared file is not
a claim that the roster is empty, which the screen cannot see; it lets the
reader who has neither fix both in one edit.

**This screen wins over ADR-0156's.** While sign-in is not configured the roster
is behind `401`, so the empty-roster check cannot run. An install lacking both
shows this screen, then ADR-0156's after the restart if the roster is still
empty. The URL is left exactly as given, for ADR-0156's reason.

### 2. Open is declared, and says so quietly

`nodqora.authentication: none` serves everything without sign-in, as `v0.2.0`
did. It is visible in two places and no more:

- **a `WARN` at startup** naming the setting, for the operator;
- **the shell's session area** — where a signed-in name and sign-out otherwise
  sit — reads *"Open — no sign-in"* as plain text, for the reader who was sent a
  link and never asked to sign in. No warning styling, no banner.

### 3. What is reachable without signing in, once a provider is configured

| Surface | Unauthenticated |
|---|---|
| The three `/api` GETs, `meta` included | **`401`** |
| SPA routes (`/`, `/environments/{key}`) | **redirect to the identity provider**, returning to the URL as given |
| The SPA's static assets | **served** — the public Apache-2.0 build, carrying nothing install-specific |
| A session state | **served** — only *which* state: sign-in not configured, open, signed out, or signed in with a name |
| A probe | **served** — `200` and nothing else: no version, no roster |

Sign-in not configured uses the same table, with the first-run screen in place
of the redirect.

**The session state and the probe live outside `/api`**, so ADR-0053's three
GETs stay three; the read API is the graph, and neither is. Their paths and the
session state's shape are the shell's decision. **The probe is one bare path, not
Spring Actuator**, whose endpoint family would be a second surface to lock down
and a second thing for ADR-0153's number to answer for.

### 4. The first-run screen is the honesty layer's sixth noun

ADR-0132's enumeration gains **the no-identity-provider screen**. It carries no
Enterprise mention, ever. That answers the ticket's *"is a warning upsell?"* by
removing the warning: the one surface that exists is exempt by name, and an open
install's label is not a warning.

Rejected, and why:

- **Stay open until a provider is configured.** No upgrade breaks, and
  "Community ships authentication" comes to mean "Community ships a switch most
  installs never flip" — on a compose file that binds every interface.
- **Refuse to boot.** Secure, and it collides with ADR-0156: a brand-new install
  would crash-loop where it now explains itself, and an upgrader from `v0.2.0`
  would read a stack trace instead of a sentence.
- **Serve, and warn loudly.** The warning has to render on every page, for ever,
  including for installs that are open on purpose, and it lands on the honesty
  layer ADR-0132 keeps clean. One screen before the choice does the same work.
- **Leave `/api/meta` public** so the shell can render before sign-in. It names
  every environment and plugin; the session state gives the shell what it needs
  without that.
- **One combined first-run screen** for no provider and no environments. It needs
  the roster's emptiness disclosed before sign-in, a small leak bought for a
  saved restart.
- **Spring Actuator for the probe.** See §3.

## Consequences

- **Every upgrade from `v0.2.0` stops serving data until one key is added.** It
  lands on a sentence, not a crash, and ADR-0153 permits it at a minor bump. The
  release notes and `docs/install.md` owe that sentence, and ADR-0153's backup
  advice still applies.
- **The five-minute install gains one line**, in a file the reader already writes.
- **ADR-0132 is amended**, to six nouns.
- **Two unauthenticated paths are new public surface** that ADR-0153's number
  must answer for once shipped.
- **Three screens now render outside the shell** — `UnknownEnvironment`,
  ADR-0156's, and this one — so ADR-0156's consequence about placing things twice
  now reads *three times*.
- **Left to the configuration grammar:** what `none` declared beside a provider
  means, and what a declared provider that cannot be reached at startup does.
- **"Unconfigured install" keeps ADR-0156's meaning** — an empty roster. This
  state is **sign-in not configured**, and the two are not interchangeable.
