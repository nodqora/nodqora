# ADR-0177: The shell reads a session state, and a 401 asks it why

- **Status**: Accepted
- **Date**: 2026-09-17
- **Ticket**: [What the shell shows of a session](https://github.com/nodqora/nodqora/issues/149)
- **Prototype**: [`prototype/shell-session-area`](https://github.com/nodqora/nodqora/tree/prototype/shell-session-area)

## Context

[ADR-0173](0173-an-install-without-sign-in-configured-is-closed-and-open-is-declared.md)
put a session state and a probe outside `/api`, named four states, replaced the
shell with a first-run screen while sign-in is not configured, and gave an open
install the plain label *"Open — no sign-in"*. It left the paths, the shape and
the screen's wording to the shell.
[ADR-0175](0175-a-session-lives-in-postgres-holds-no-token-and-is-never-data.md)
made sign-out a form `POST /logout` carrying a CSRF token, made expiry a `401`
indistinguishable from never signing in, and made the shell answer a `401` with
a top-level navigation to the current URL.
[ADR-0176](0176-sign-in-is-one-provider-declared-once-and-a-wrong-declaration-refuses-to-start.md)
made the displayed name never empty and added a server-rendered `503` for a
browser that must sign in while the provider is unreachable.

What was still open: where the session sits in the shell, what the two public
paths are called and return, and how `client.ts` turns a `401` into a
navigation without looping when there is nowhere to navigate to.

A throwaway prototype put three homes for the session area into the real shell
over the golden documents — the top bar, an account menu holding session and
settings, and a status line under the canvas — and walked every state:
signed in with a name, with only `preferred_username`, with only `sub`, open,
sign-in not configured, a session expiring under a live canvas, and the
provider down.

## Decision

**The shell reads `GET /session` at startup and again on any `401`, and does
what the answer says. The session sits at the trailing edge of the top bar as
plain text.**

### 1. Two public paths

| Path | Answers |
|---|---|
| **`GET /session`** | `200`, always, `Cache-Control: no-store` — one of the four shapes below |
| **`GET /healthz`** | `200`, empty body — the process is up |

- **`/session`**, not `/api/session` (ADR-0173 keeps `/api` to three GETs) and
  not `/me`, which is wrong for the three states with nobody in them.
- **`/session` never answers `401`.** It is what the shell asks to learn *why*
  it got one.
- **`/healthz` answers `200` while the provider is unreachable** (ADR-0176). It
  claims liveness only. No `/livez`/`/readyz` split: there is no readiness
  distinction to report, and each path is public surface ADR-0153's number
  answers for.
- Neither collides with the SPA's routes (`/`, `/environments/…`) or with
  Spring Security's (`/login`, `/logout`, `/oauth2/authorization/oidc`,
  `/login/oauth2/code/oidc`).

### 2. The session state

```json
{ "state": "SIGNED_IN", "name": "Ada Lovelace",
  "csrf": { "headerName": "X-XSRF-TOKEN", "parameterName": "_csrf", "token": "…" } }
{ "state": "SIGNED_OUT" }
{ "state": "OPEN" }
{ "state": "NOT_CONFIGURED" }
```

- **Upper-snake enum values**, as `HEALTHY`, `DISCOVERY` and `PARTIAL` already
  are on the wire.
- **`name` and `csrf` exist only in `SIGNED_IN`** — absent, never `null`. An open
  install has no sign-out and no write path, so it has no token to carry.
- **`name` is resolved on the server** — `name-claim`, then
  `preferred_username`, then `sub` (ADR-0176). The shell never sees claims, and
  no email, `sub` or issuer rides beside the name.
- **Both CSRF names are carried**, so the sign-out form does not hard-code
  Spring's defaults.
- **Provider reachability is not reported.** A signed-in reader is unaffected
  by it, and a browser that must sign in gets ADR-0176's `503` before the SPA
  loads.

### 3. The session area

At the trailing edge of the top bar, after the theme picker:

- **Signed in:** the name as plain text, then **Sign out**, a link-styled submit
  button in a form `POST /logout` with the CSRF token as a hidden field. A name
  longer than 18 characters is truncated with an ellipsis and carried whole in
  `title`. An opaque `sub` is shown as it is: ADR-0176 chose it over an empty
  space, and the fix is the operator's `name-claim`.
- **Open:** *"Open — no sign-in"* in the same place, as plain text (ADR-0173).
- **Signed out, not configured:** nothing — the shell is not rendered.

Rejected:

- **An account menu** holding the name, sign-out and the theme picker. It adds
  a second idiom to a bar of plain labels and selects, hides sign-out a click
  deep, and moves the theme picker for no reason of its own.
  *"Open — no sign-in"* on a button reads as something to press.
- **A status line under the canvas.** It leaves the top bar alone, and adds a
  permanent strip to every screen to hold two words the bar has room for.

### 4. A `401` asks the session state, once

At startup the shell fetches `/session` beside `/api/meta`. The session area
needs it, and `NOT_CONFIGURED` shows the first-run screen without waiting for
`meta` to fail.

On a `401` from any of the three GETs, polling stops and the shell reads
`/session`:

| Answer | The shell |
|---|---|
| `NOT_CONFIGURED` | replaces itself with the no-identity-provider screen, in place — no navigation, so nothing to loop |
| `SIGNED_OUT` | replaces the canvas with *"Your session has ended. Signing in again…"*, then `location.assign(location.href)` — the server sends it to the provider and back to the same URL, `?node=` included |
| `OPEN`, `SIGNED_IN` | the server contradicts itself: an ordinary error in the top bar, no navigation |

**The canvas never stays on screen under the message** (ADR-0175): a graph that
can no longer be fetched is not shown as if it could.

**A loop guard.** Before navigating, the shell writes a timestamp to
`sessionStorage`. A second `SIGNED_OUT` re-navigation within **30 seconds** does
not navigate; it shows *"Signing in did not succeed. Reload to try again."*
That stops a server that completes sign-in and still answers `/api` with `401`
from reloading forever. A misbehaving provider is already stopped at the
provider.

### 5. The no-identity-provider screen

In [ADR-0156](0156-the-unconfigured-install-has-a-first-run-screen-that-replaces-the-shell.md)'s
grammar — one quiet headline, one route, one link, no retry, no spinner, no
Enterprise (ADR-0132's sixth noun):

> **No identity provider is configured.**
>
> Declare `nodqora.authentication` in `/app/config/application.yaml` — the same
> file that declares `nodqora.environments`. Name your provider under `oidc`, or
> set it to `none` to let anyone who can reach this install read it without
> signing in. Then restart, and reload this page.
>
> How to configure sign-in · Nodqora *version*

- **The link is `docs/install.md`'s sign-in section, pinned to the build's tag**,
  falling back to `main` as ADR-0156's does.
- **Both answers are named, and `none` by its consequence.** No warning and no
  recommendation; the order follows ADR-0173's closed default.
- **Nothing about upgrading.** A new install and a `v0.2.0` upgrade see the same
  screen; the release notes carry the upgrade sentence.

## Consequences

- **Two public paths, `/session` and `/healthz`**, join ADR-0153's surface once
  shipped, and `signin`'s chain permits both unauthenticated.
- **`client.ts` learns one status code.** A `401` stops being an error string and
  becomes a question to `/session`; every other failure keeps ADR-0060's path.
- **A fourth thing replaces the shell** — the re-navigation message, and the
  loop guard's sentence in its place — beside `UnknownEnvironment`, ADR-0156's
  screen and ADR-0173's. ADR-0173's *three times* now reads *four*.
- **The theme picker stays where it is**, no longer the last child of the bar.
- **`docs/install.md` owes a sign-in section** for the screen's link to land on.
- **The golden documents are unaffected**: the session state is not part of
  ADR-0053's read API.
