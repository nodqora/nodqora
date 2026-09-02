# ADR-0028: Raw signal and metrics are ordered, allow-listed, and absent when unobserved

- **Status**: Accepted
- **Date**: 2026-09-02
- **Ticket**: [Health normalization model](https://github.com/fredskor/nodqora/issues/9)

## Context

ADR-0013 settled the *mechanism* for the two easy halves of composition —
`rawSignal` joins in plugin order, `metrics` unions by namespace — and left the
content open. ADR-0014 established that plugin output must be secret-free and
that config-derived values are **allow-listed by name, never
everything-except**, because research #5 found `GET /connectors/{name}/config`
returns inlined secrets unmasked.

## Decision

**Plugin order is the registry order** — `yaml`, `kubernetes`, `kafka`,
`connect` — fixed, always, joined by `"; "`. CONTEXT.md's own worked example,
`"3 desired / 2 ready; lag 40000"`, is exactly kubernetes-then-kafka, so the
model already assumes it.

**`rawSignal` is `null` when nothing observed the node** — never a string such
as `"no adapter"`. The core would otherwise be describing plugin absence, which
ADR-0015 forbids, and *adapter* is retired vocabulary (ADR-0010). The inspector
renders its designed empty state instead (ADR-0019).

**Stack traces are bounded and not stored.** Research #5 found Connect's `trace`
is an unbounded stack trace with no failure timestamp anywhere in the API.
`rawSignal` stays a short line — `"RUNNING, 1 of 3 tasks FAILED"` — with the
trace's **first line only** appended, bounded to ~500 characters. The full trace
is not stored: both connector nodes already carry "Connect UI" and "Logs" links,
and §5.1 says do not replace the tools you link to. Rotating unbounded traces
through a table rewritten every 30 seconds buys little and costs a lot.

**`metrics` keys are allow-listed per plugin**, in ADR-0014's spirit —
enumerated by name, never "whatever the API returned":

| plugin | keys |
|---|---|
| `kubernetes` | `desiredReplicas`, `readyReplicas` |
| `kafka` | `maxConsumerLag` |
| `connect` | `tasksTotal`, `tasksRunning` |

That covers §14's overlay for every node type the MVP has. §14's other examples
— req/s, p95, doc counts, table size — need Prometheus or OTel, and topic size
needs `describeLogDirs`, which research #5 identified as the one expensive call.

**A node nobody observes gets no `NodeState` row at all.** Absence reads as
`UNKNOWN` / `rawSignal: null` / `metrics: {}` / `observedAt: null` on the join.

## Consequences

- The fast table stays proportional to *observed* nodes, and ADR-0013's "four of
  the fixture's ten are `UNKNOWN`" costs nothing to store. The storage ticket
  inherits this as a constraint.
- The fixture's raw-signal column is a **gist, not literal output**: it gives
  `payments-es-sink` `"RUNNING, 3/3 tasks RUNNING"`, but ADR-0013 has three
  plugins observing that node, so the composed string is
  `"2/2 ready; lag 120; RUNNING, 3/3 tasks RUNNING"`. The column documents the
  interesting signal; it is not an expected-output assertion. Noted in the
  fixture rather than changed on either side.
- A new secret-bearing field appearing in a Connect or Kubernetes API response
  cannot leak through `metrics`, because the allow-list fails safe — the same
  argument ADR-0014 made for `metadata`.
- Adding a metric is a deliberate edit in one place per plugin, which is the
  point.
