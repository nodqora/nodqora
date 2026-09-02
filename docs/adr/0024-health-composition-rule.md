# ADR-0024: Health composes by discarding abstentions, then `DISABLED`-wins, then worst-of-three

- **Status**: Accepted
- **Date**: 2026-09-02
- **Ticket**: [Health normalization model](https://github.com/fredskor/nodqora/issues/9)

## Context

ADR-0013 established that several plugins observe one node and composed the
easy halves — `metrics` unions by namespace, `rawSignal` joins in plugin order —
then handed the hard half here: "**`health`** — the one genuine conflict, and
**#9's** to resolve. The obvious default is worst-wins."

Worst-wins does not survive the reference pipeline. Under the incident scenario
`payments-es-sink` is observed by three plugins at once:

| plugin | signal | contribution |
|---|---|---|
| `connect` | connector `PAUSED` | `DISABLED` |
| `kafka` | group lag behind a paused sink | `DEGRADED` |
| `kubernetes` | `kafka-connect` StatefulSet 2/2 ready | `HEALTHY` |

The fixture says the node is `DISABLED`. On any severity ladder where `DEGRADED`
outranks `DISABLED`, `max()` returns the wrong answer.

The reason is that the five values are **not a ladder**. Three of them are
severities; two are not opinions at all.

- **`UNKNOWN` is an abstention** — "I was not asked" or "I could not look". It
  is a resting state held permanently by four of the fixture's ten nodes, not a
  finding about anything.
- **`DISABLED` is judgement suspended** — a human turned this off on purpose,
  so the lag behind it and the readiness of the workload hosting it are
  *consequences of that act*, not findings either.

## Decision

The state engine collapses a node's `StateContribution`s in three steps:

1. **Discard** every `UNKNOWN` contribution. If nothing remains — including the
   case where no plugin was asked — health is `UNKNOWN`.
2. If any surviving contribution is `DISABLED`, health is **`DISABLED`**.
3. Otherwise **worst-wins** over the only real ladder:
   `HEALTHY` < `DEGRADED` < `UNHEALTHY`.

`DISABLED` wins **outright**, including over `UNHEALTHY`. A paused connector on
a crash-looping workload is, to the operator asking "does anyone need to act on
this?", still paused.

**The same three steps run inside a plugin.** ADR-0013's signature returns one
contribution per node per plugin, so a plugin observing several backings of one
node — `payments-api`'s Deployment, Service and Ingress; a node backed by two
workloads — collapses them with this rule before returning. There is exactly one
composition algorithm in the system, applied at two levels.

## Consequences

- Both fixture scenarios reproduce exactly, including the three-observer
  `payments-es-sink` case that defeats plain worst-wins.
- ADR-0013's "a node with no backings is `UNKNOWN`" stays arithmetic: step 1
  with an empty input, not a rule anyone writes.
- **No per-plugin precedence table.** The rejected alternative ranked plugins
  against each other, which means a node's health changes when an operator
  merely configures one more plugin — an answer that depends on deployment
  topology rather than on what is true.
- Abstentions being discarded has a sharp edge: if the only plugin that can see
  a node's real trouble fails for a cycle, the node composes down to whatever
  else answered. That is not fixed inside the enum — see ADR-0026.
- A plugin must therefore never return `UNKNOWN` for something it actually
  observed; doing so deletes its own vote. See ADR-0029 and CONTEXT.md's
  vocabulary note on *abstained*.
- Adding a sixth health value would now cost a glyph (ADR-0017) **and** a
  position in this rule. The enum should stay expensive to widen.
