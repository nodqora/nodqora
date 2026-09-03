# ADR-0068: Search is a ranked result list, not a canvas filter

- **Status**: Accepted
- **Date**: 2026-09-03
- **Ticket**: [Simple name search behaviour](https://github.com/fredskor/nodqora/issues/15)

## Context

Two shapes were available: a result list the user picks from, or
filter-as-you-type directly on the canvas, where matches stay lit and
non-matches dim.

ADR-0054 removed any API question — the client already holds the environment
graph — so this is purely a presentation decision.

## Decision

**A ranked result list. The canvas is untouched until the user picks a result.**

Each row carries the display name (falling back to `key`), the type label, the
health chip, and a **"matched via"** line shown *only* when the hit came from a
backing rather than from `key` or `displayName`.

The control sits in the top chrome **beside the environment switcher**. `/`
focuses it, `Esc` clears and blurs. At most **10** rows are shown, with a count
line — "10 of 34 matches" — when there are more. There is **no minimum query
length**.

## Consequences

- **Dimming non-matches is filtering.** ADR-0018 deferred filter-by-type and
  filter-by-health wholesale; a live dim delivers the deferred feature under
  another name, and unlike a filter chip it has no visible off-switch — an
  abandoned query leaves the canvas quietly understating what exists.
- It also collides with ADR-0017, which encodes health by shape *and* colour
  because colour alone is fragile. Dimming attacks both channels at once, so a
  dimmed `UNHEALTHY` node is *less* legible during exactly the incident the
  fixture's second scenario exists to render.
- The "matched via" line is ADR-0023's requirement discharged: a hit on
  `enricher-v2` reads "`payments-enricher`, via Deployment `enricher-v2`", so a
  result whose matched string the node does not display no longer looks like a
  bug. A dimmed-canvas design has nowhere to put that sentence — which is the
  third and quietest reason the list wins.
- Rows fall back to `key` rather than fabricating a name, per ADR-0058. On the
  fixture that is most nodes: ADR-0034 has `kubernetes` emit `displayName` as
  `null` deliberately.
- Placement beside the switcher makes the **scope visible at the moment it
  matters** — ADR-0069's empty state names the environment, and the switcher
  that would change it is adjacent. A floating canvas control would also fight
  ADR-0016's layout for the axis it has least of.
- The cap is what keeps ADR-0067 honest. A total order over 34 substring hits is
  deterministic but only *informative* at the top; the count line tells the user
  the query was too broad, which a silently-truncated list would not. Uncapped
  scrolling invites reading results the ranking never claimed to sort well.
- `Cmd-K` is deliberately unbound: it conventionally opens a command palette and
  would promise a feature that does not exist.
- The cost, which is the same bet ADR-0018 made: on a large graph, thirty hits
  lit up in place genuinely beat thirty rows in a list. The MVP proves the
  central experience; the overlay is restorable later.
