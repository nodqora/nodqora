# ADR-0003: Topology and runtime state are separate records joined on read

- **Status**: Accepted
- **Date**: 2026-09-01
- **Ticket**: [Core graph domain model](https://github.com/fredskor/nodqora/issues/3)

## Context

Product plan §8.1 lists `status`, `health` and `metrics` as fields on Node.
Product plan §68 — "Important Architectural Boundary" — says the opposite:
topology changes slowly, state changes continuously, and "do not rewrite
topology every time health changes".

The reference pipeline makes the cost concrete: consumer lag and task states
move constantly, while the shape of the pipeline moves when someone ships an
architecture change.

## Decision

Split the two.

**Node** holds slow-changing topology only: identity, type, display name,
environment, owner, links, backings, metadata, provenance.

**NodeState** holds the fast half: `nodeId`, `health`, `rawSignal`,
`metrics{}`, `observedAt`. It is written by a health refresh loop on its own
cadence and joined to the Node when the graph is read.

`Node.updatedAt` therefore means **"the topology changed"** and nothing else.

`health` remains a **closed enum** of the five §13 values — `HEALTHY`,
`DEGRADED`, `UNHEALTHY`, `UNKNOWN`, `DISABLED` — deliberately unlike types and
relations (ADR-0001). Normalizing technology-specific status into a fixed
vocabulary is the entire job of an adapter; an open health vocabulary would
leave the canvas unable to render a node it had never seen a state for.

`rawSignal` preserves the unnormalized observation so the inspector can always
show what a normalized value came from.

## Consequences

- Two write paths and a join on every graph read. Accepted: the reference
  pipeline is ten nodes, and query shape is the storage ticket's problem.
- `Node.updatedAt` becomes a clean, free signal for §24 topology change
  tracking later — the main reason to pay for the split now.
- Health refresh cadence and topology discovery cadence are independently
  tunable (§69).
- `UNKNOWN` is a normal steady state, not an error: four of ten fixture nodes
  hold it permanently because nothing observes them.
- Health normalization *rules* are out of scope here — see
  [Health normalization model](https://github.com/fredskor/nodqora/issues/9).
