# ADR-0093: The root redirects to the first configured environment; an unknown one names the roster

- **Status**: Accepted
- **Date**: 2026-09-04
- **Ticket**: [Frontend routing and deep-linking](https://github.com/fredskor/nodqora/issues/21)

## Context

ADR-0092 makes the environment a path segment, which leaves two URLs that name no
usable scope: bare `/`, and `/environments/{something-not-in-the-roster}`.

ADR-0055 puts the roster in `/api/meta` as `{key, displayName}`, bound from config
at startup and fetched once, so the frontend holds the complete, ordered list of
environments before it routes anything. ADR-0052 matches the environment key
**exactly** — *"it is operator-authored config, not discovered data"* — in
deliberate contrast to node keys, which are case-folded.

## Decision

**Bare `/` redirects to `environments[0].key`** from the `/api/meta` roster.

**An unknown environment key renders a designed not-found that names the
environments that do exist.** Matching stays exact; there is no case-folding and
no redirect.

> "No environment named `Production`." — Available: `production`, `staging`

> **Amended by [ADR-0156](0156-the-unconfigured-install-has-a-first-run-screen-that-replaces-the-shell.md).**
> Both rules above presuppose a **non-empty** roster, and ADR-0152 makes the
> shipped image start with an empty one. With no environments there is no
> `environments[0]` to redirect to, and the not-found's roster line — the thing
> that turns a case typo into one click — renders nothing at all. So an empty
> roster is checked **before** either rule and renders a first-run screen on
> every route, leaving the URL exactly as given. Ordering it the other way frames
> a config-absent install as a **typo** and offers an empty repair.

## Consequences

- **A redirect makes the root's ambiguity last zero screens.** Bare `/` asserts no
  scope, so resolving it is not the leak ADR-0092 guarded against; what matters is
  that the address bar is explicit immediately afterwards, which a redirect
  guarantees and a silently-defaulted render does not.
- **Last-used-environment was rejected** even though it is friendlier. It makes a
  bookmarked `/` mean different things to different people and **drift over time**,
  which is at odds with ADR-0018's scope-as-frame: the frame should not move
  because of something the reader did last week in another browser. First-in-roster
  also makes config order meaningful — operators put `production` first.
- **A picker page was rejected on the fixture's own numbers.** Two environments do
  not earn an interstitial on every cold entry.
- **Redirecting an unknown key to a valid one is the worst option, not the safest.**
  It substitutes a scope the user did not ask for and then displays a URL that
  asserts it — ADR-0092's leak in its most confident form.
- **Naming the roster is not the cross-environment surface ADR-0054 called
  unavailable.** That unavailability is about **graph data**; the roster is already
  served by `/api/meta` and already fully rendered in the switcher beside the search
  box (ADR-0068). Listing it costs nothing and turns a case typo into one click.
- **Exact matching keeps one rule for one key across the stack.** Case-folding the
  path segment would give the environment key two comparison rules — folded in the
  UI route, exact in the API — for a hand-typed URL that the not-found already
  repairs in a click.
