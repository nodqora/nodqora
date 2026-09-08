# ADR-0156: The unconfigured install has a first-run screen, and it replaces the shell

- **Status**: Accepted
- **Date**: 2026-09-08
- **Ticket**: [The first screen of an install with no environments](https://github.com/fredskor/nodqora/issues/71)
- **Amends**: ADR-0093, ADR-0132

## Context

[ADR-0152](0152-the-image-ships-no-environments-and-config-arrives-as-two-mounts.md)
makes the image ship **zero** `nodqora.environments`, and keeps the process
booting anyway so that an unconfigured install starts and serves an honestly
empty roster from `/api/meta`. That is the server contract and it is settled.
This is the client half, which nothing had a branch for.

Traced rather than assumed, here is what an empty roster renders today.
`defaultEnvironmentKey` returns `null`, so ADR-0093's redirect effect never
fires and `route.environmentKey` stays `null`. That also means the
`UnknownEnvironment` guard — `route.environmentKey !== null && !known` — never
fires. Both polls run on the server's own cadence and both return early at
`if (!environmentKey) return`, so `graph` stays `null` forever and the render
falls through to:

> **Reading the graph…**

under a top bar carrying an **empty** environment dropdown, and no search box or
plugins chip, because both are gated on `graph`. A permanent progress statement
for a fetch that will never be issued — a lie in the product's own honesty
vocabulary, on the first screen a stranger ever sees.

Three decisions touch this case and none of them covers it:

- **[ADR-0093](0093-root-redirects-and-an-unknown-environment-names-the-roster.md)**
  sends bare `/` to `environments[0].key`; with an empty roster there is no
  `[0]`. Its designed not-found *names the environments that do exist*, which
  here names none.
- **[ADR-0087](0087-the-canvas-has-three-empty-states.md)** gives the canvas
  three empty states, all of which presuppose an environment to name — the
  component takes an `environmentDisplayName`.
- **[ADR-0085](0085-plugins-is-a-config-roster.md)** made *never looked*
  expressible one level down, for the reason that an unreported pair must not be
  byte-indistinguishable from a failed one. The same argument runs up here.

## Decision

**An install whose roster is empty renders a first-run screen that replaces the
shell, on every route, and claims nothing it cannot see.**

### 1. It replaces the shell, because there is no shell to fill

Every element of the top bar is scope-dependent: the switcher has nothing to
switch, and the search box and the plugins chip are both gated on `graph` and
cannot appear. Rendering the frame would put an empty dropdown and three
invisible controls around one sentence.

`UnknownEnvironment` already establishes the pattern — *the URL names no usable
scope* is answered by replacing the shell, not by emptying its middle. This is
the same category one step earlier: not **I cannot find that environment** but
**there are no environments**.

### 2. The empty roster beats the URL, and the URL is not rewritten

The check runs **before** the known-check, so `/`,
`/environments/production` and `/environments/anything?node=payments-api` all
render it. There is one fact about this install and it does not vary by URL.

Ordering it the other way sends a stranger who was handed a link to ADR-0093's
not-found — *"No environment named `production`. Available:"* followed by
nothing — which frames a config-absent install as a **typo**, and offers a
repair affordance that is empty.

**The URL is left exactly as given.** ADR-0093 already ruled that substituting a
scope the reader did not ask for is *"the worst option, not the safest"*, and
there is a constructive half here: the pasted link is **correct in the future**.
The reader writes their config, restarts, reloads, and the link they were sent
resolves. Rewriting to `/` would destroy the one piece of state worth keeping
across the fix. A carried `?node=` and environment key are a deliberate no-op,
not a dropped input.

### 3. The headline is ADR-0087's sentence with the scope removed

> **No environments are configured.**

Not new vocabulary. ADR-0087 already ships *"No plugins are configured for
`production`"*; this is the identical sentence one level up, which is exactly
the relationship between the two states. The honesty grammar had a slot here and
nobody had filled it.

### 4. It states the route and claims nothing about compliance with it

The fix is a file the UI cannot verify, create, or check the mount of, so the
screen states what the reader does and never what the product has checked:

> Write an `application.yaml` declaring `nodqora.environments` into the config
> directory this container reads — `/app/config`, which the shipped
> `compose.yaml` mounts from `./config` beside it. Then restart, and reload this
> page.

Anchored on `/app/config` because that is true however the image is run, with
the Compose translation in the same breath because that is ADR-0150's single
supported install.

**No inline config snippet.** ADR-0152 pointed the prose at *"a worked example
the test suite keeps true"*, and that example cannot live in a React component.
A second copy of a config grammar in the one place nothing tests is worth less
than the click it saves.

**No spinner, no "waiting for configuration", no countdown, no retry.** Those
are claims about a thing being watched and nothing is being watched. It is
ADR-0087's *no countdown* discipline on a screen where the wait is not even
bounded by a cadence — it is bounded by a human editing a file.

### 5. One link, pinned to the version that is running

One link out, to
[`docs/running-against-your-own-cluster.md`](../running-against-your-own-cluster.md),
**at the tag this build was cut from**. The version is baked into the bundle at
build time through a Vite define fed by a Dockerfile build argument, falling
back to `main` for a working tree.

Pinning is not decoration.
[ADR-0153](0153-the-deployable-shares-the-version-and-the-number-promises-an-upgrade-path.md)
says a **minor version may want a config edit**, so an unpinned link would, the
moment `v0.2.0` lands, teach a `v0.1.0` reader a config grammar their install
does not have — and they cannot detect it, because they are on this screen
precisely for not yet knowing how the thing is configured. That is the failure
invisible to the only person harmed, which
[ADR-0155](0155-the-release-is-one-gradle-command-and-the-tag-is-the-last-act.md)
refused when it verified GHCR visibility rather than assuming it.

**Deferring the pin until the second release was the tempting wrong answer.**
The trigger fires on a *release*; the thing that breaks is an *already-installed
copy* nobody will revisit.

The target is a document that **exists today**, and that is a hard requirement
rather than a convenience: a 404 from the first-run screen is the worst failure
available on this route — the reader has done everything right, reached the one
screen designed to help, and been handed a dead link by the product. So this
ADR hands the install-documentation work a **constraint, not a request**:
`docs/running-against-your-own-cluster.md` keeps answering *"how do I declare an
environment"* at that path, or the same commit that moves it updates this
constant.

### 6. Nothing is polled; "reload" is the fifth imperative

`/api/meta` is fetched once, and ADR-0074 reconciles environments from bound
config **at startup**, so a config change is only ever visible across a process
restart. The screen does not poll and does not offer a *Check again* control.

Polling was the charming option and it makes this screen start managing a wait:
the reader's tab spends the restart window getting connection-refused, so it
would need a rule for suppressing errors it has decided are *expected* — and an
error we have chosen not to show is a bad thing to introduce on the honesty
layer of all places. A button is a manual reload in a nicer coat, on a screen
this ADR carves out of the control path.

### 7. Only a *known*-empty roster reaches this screen

The screen is gated on `meta !== null`. A **failed** `/api/meta` keeps the
existing error path.

This is not a detail. A server that is unreachable and a server that is
unconfigured both leave the roster unusable, and firing on the wrong one tells a
reader **"No environments are configured"** — a confident false statement about
their config, made from no evidence whatsoever. It is ADR-0085's own argument —
*absence is already load-bearing and already means something else* — one level
above where ADR-0085 put it.

### 8. It carries no Enterprise mention, and ADR-0132 says so by name

[ADR-0132](0132-upsell-rides-the-control-path-and-never-the-honesty-layer.md)'s
enumerated carve-out goes from four nouns to **five**: the plugins chip, the
banners, the outcome marks, the three canvas empty states, **and the first-run
screen**.

Both existing rules already forbid the mention.
[ADR-0131](0131-enterprise-is-named-only-where-its-free-half-is-in-hand.md) is
blunter — *"no nav entry, no About panel, no footer link, no editions page in
the product"*, and a capability whose free half is not in hand is never named;
on this screen **nothing** is in hand, not the paid half and not any free one.
ADR-0132's own test classifies it as honesty layer: it reports what we could
see, and prose about editing a file is not a control, because the product owns
no affordance that writes it.

Enumerating anyway, because ADR-0132 wrote down why: *"Enumerated rather than
left as a principle, so the carve-out survives the components being rewritten.
ADR-0087 and ADR-0089 both exist because a principle was not specific enough
about a surface, and the enumeration costs four nouns."* A new honesty surface
that the principle covers and the list does not is that argument arriving on
schedule — and this is the highest-attention pixel the product will ever have,
so it is the one most likely to be re-litigated by whoever next thinks about
growth.

**It does not point at the demo either.** ADR-0152 made the reference pipeline
repo-only, so *try the worked example* would route a stranger into `git clone`
and a JDK — the path this edition's whole distribution story exists to remove.
The example is reachable through the documentation link, as documentation, and
not as an offer the product makes.

## Consequences

- **Two screens now render outside the shell**, so anything later added to the
  top bar has to be placed twice or placed in a layer both share. Accepted: the
  alternative is a frame whose every control is inert.
- **ADR-0093 is amended.** Bare `/` gains an empty-roster branch that resolves to
  no environment at all, and the designed not-found is now unreachable with an
  empty roster — the case where its roster line would have rendered nothing.
- **ADR-0132 is amended**, to five nouns.
- **ADR-0087 and ADR-0018 are deliberately *not* amended.** This is not a fourth
  canvas empty state; it is not a canvas state at all, because there is no
  canvas. Adding it to the canvas's list would record the opposite of what was
  decided.
- **The frontend now has a build-time constant**, and therefore a version string
  it did not have before. `/api/meta` carries `environments`, `plugins` and
  `refresh` and no version, so this is the first place the running version is
  expressible in the UI. That generalises — ADR-0139 and ADR-0147 make support
  cover the latest release only, so *which version am I running* is a
  support-bearing question with no answer on any screen today.
- **The version belongs on `/api/meta` eventually**, and putting it there was
  rejected here as the right architecture in the wrong ticket: it is a property
  of the running server rather than of the bundle, and moving it later changes
  this screen not at all.
- **A dev server has no version** and links to `main`, which is correct for a
  working tree and wrong only in the case the build argument exists to fix.
- **Cost:** the reader takes one click to reach actual YAML, and the screen is a
  forward reference — it ships a link whose target the install documentation
  must keep satisfying.
