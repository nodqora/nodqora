# ADR-0168: A card shows three figures and counts the rest

- **Status**: Accepted
- **Date**: 2026-09-15
- **Ticket**: [The metric line when two plugins have something to say](https://github.com/nodqora/nodqora/issues/107)
- **Amends**: [ADR-0018](0018-mvp-canvas-feature-scope.md) — the optional metric line becomes an optional figure strip. It stays behind the same canvas-level toggle, default on.

## Context

ADR-0018 gave each node one optional metric line. `summarizeMetrics` builds it:
alphabetical by plugin, then by key, humanized, joined with ` · `, clamped to one
line in CSS. It never branches on a plugin or a key. With one plugin's worth of
keys that was enough, and a workload read `desired replicas 3 · ready replicas 3`.

ADR-0165 and ADR-0167 add a second source to that line on a service: Prometheus's
`rate` (per second) and `latency` (mean, in ms), both or neither. They also add a
new card state: a node only Prometheus observes reads `UNKNOWN` **with metrics
and an `observedAt`**, and that time is the age of a measurement, not of a verdict.

A prototype ran four variants on the real canvas over the golden documents, with
the demo's readings poured in (the aggregator's 38/s and 0.036 ms). Its branch is
`prototype/metric-line-two-plugins`. What it showed:

- **Today's line already fails.** On `payments-api` the Prometheus keys are cut
  off after `ready replicas …`. On `payments-enricher` they never appear, because
  alphabetical order puts `prometheus` after `kafka` and `kubernetes`. The
  Prometheus-only node reads `latency 212 · rate 12.1`, with no units and no age.
- **A row per plugin** loses nothing, but a connector grows three rows, and height
  is the axis ADR-0016's layout has least of.
- **A composed line** (`2/3 ready · 38/s · lag 40k · 0.036 ms`) fits every node
  in the fixture into 236px.
- **Captioned figures, capped at three**, read fastest, and fastest of all during
  the incident: `0/3 · 0/s · 2.1M`.

## Decision

**1. The metric block is a strip of at most three figures.** Each figure is a
value over a short caption. The figures come from a fixed key priority, and
whatever is left over becomes one `+N more` figure. The full list stays where it
already is: the block's `title` on the canvas, and section 2 of the drawer,
namespaced by plugin.

| rank | keys | value | caption |
|---|---|---|---|
| 1 | `readyReplicas` + `desiredReplicas` | `2/3` | `ready` |
| 2 | `rate` | `38/s` | `rate` |
| 3 | `maxConsumerLag` | `40k` | `lag` |
| 4 | `latency` | `0.036 ms` | `mean` |
| 5 | `tasksRunning` + `tasksTotal` | `2/3` | `tasks` |
| last | any key not in the table | the raw value | the humanized key |

A pair collapses into one figure only when **both** of its keys are present.
Numbers are compact (`1.2k`, `2.1M`). Integers never gain a decimal. Sub-millisecond
latency keeps two significant figures. **`0/s` is a figure**, because ADR-0167's
`rate 0` is a real value; an absent key produces no figure at all.

The `rate` caption says nothing about records versus requests. ADR-0167 puts both
under one key, and the card follows it.

**2. The phrase table lives in the frontend, keyed by metric key, never by
plugin.** It sits in `metrics.ts` and replaces `humanize` as the rendering path.
It relaxes that file's "no per-type branch" rule for keys only. It still holds
where it mattered: a new plugin that reuses `rate` needs no frontend change, and a
new allow-listed key still renders without one, just generically and last, until
someone gives it a row.

**3. A node only Prometheus observes says how old its reading is:
`measured 45s ago`.** The age is faint, at the trailing end of the strip, and
never beside the health glyph, so it cannot read as the age of an `Unknown`. A
node that has voters shows no age on the card; the drawer already has it.

**4. The card's height is still declared, not measured.** The strip is one fixed
height whether it holds one figure or three, and it replaces the metric line's
height in `Canvas.tsx`. A node with no metrics still reserves nothing.

## Alternatives

- **Plugins ship a unit and a label for each key over the wire.** That keeps the
  frontend blind, but it grows ADR-0028's contract and ADR-0055's roster to carry
  presentation, the concern `metrics.ts` already declined to push into the
  server.
- **A row per plugin.** It is honest and needs no table, but its height is
  unbounded by design, and a node that three plugins observe becomes the tallest
  card on the canvas for a reason no reader cares about.
- **A composed line.** This was the close second: it shows everything in the
  fixture. It loses to the strip on glance-reading, and it has no rule for what
  gets dropped when a fourth fact arrives; the cap does.
- **Keep the line, reorder it.** Any fixed order for one clamped line hides the
  same tail that `+N` makes visible.

## Consequences

- **On a service Kafka also observes, `latency` is the first figure to go.**
  `payments-enricher` reads `2/3 ready · 38/s · 40k lag · +1 more`. That is the
  ranking working as intended: lag is the voting signal. Reordering is a
  one-line change, not a new decision.
- **A p95, if one ever arrives as a third Prometheus key (ADR-0167), needs a row**,
  or it renders as a generic figure ranked last.
- **The `title` tooltip stops being where overflow goes silently.** `+N more`
  tells the reader something is there, and says how much.
- **`summarizeMetrics`' contract changes from one string to ranked figures.** Its
  golden-document test is rewritten to match; the drawer is untouched.
