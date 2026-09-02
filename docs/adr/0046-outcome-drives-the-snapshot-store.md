# ADR-0046: `outcome` drives the snapshot store, and absence is honoured at key granularity only

- **Status**: Accepted
- **Date**: 2026-09-02
- **Ticket**: [Discovery-engine merge semantics](https://github.com/fredskor/nodqora/issues/12)

## Context

Under ADR-0043 the snapshot store is a small state machine and the fold over it
is pure. `outcome` is the transition function.

Research [#4](https://github.com/fredskor/nodqora/issues/4) found the trap stated
at three granularities: absence of a **node** in an environment is meaningful
(ADR-0004's "drift is absence"), absence of a **field** in a payload is not, and
absence of an entire **run** is not either. Its two worked failures are a
Kubernetes adapter that cannot read an annotation this run blanking an owner YAML
set, and a Kafka adapter that fails to connect deleting every topic node.

## Decision

| `outcome` | effect on the stored snapshot for `(plugin, environment)` |
|---|---|
| `COMPLETE` | **replace wholesale.** Keys absent from it are absent. |
| `PARTIAL(reasons)` | **upsert the keys present; retain the keys absent.** |
| `FAILED(cause)` | **no change.** The store is untouched. |

**A `PARTIAL` upserts whole nodes.** Its blanked `metadata` blanks the stored
`metadata`. Field-level absence within a present node is not honoured.

That generalizes to an invariant:

> **Absence is honoured at exactly one granularity per level: key-level within a
> plugin's snapshot, field-level never.** Across plugins, fields are settled by
> ADR-0044's precedence, never by absence.

## Consequences

- `FAILED` is forced by the research: ADR-0012 made it explicit so that *"a
  `FAILED` snapshot is never mistaken for an empty one"*, and this is where that
  promise is cashed.
- `PARTIAL` means the plugin has said it was blind somewhere, so its absences
  carry no information. ADR-0042's two `kafka` reasons and ADR-0047's
  generalization all route through here.
- **Honouring field-level absence would be research option D creeping back in
  through the side door** — all of D's stickiness and all of its order-dependence,
  in a fold ADR-0043 chose specifically to be order-independent. What gets blanked
  instead is ADR-0006's explicitly **slow-moving decoration**, it self-heals on the
  next clean poll, and a silently-retained stale partition count is worse than a
  visibly-absent one. ADR-0042's second `kafka` reason — a listed topic whose
  `describeConfigs` came back empty — is exactly this case, and is accepted.
- **Cost, recorded rather than buried:** a plugin stuck `PARTIAL` for a long time
  retains keys nobody has confirmed in hours, and the fold cannot distinguish
  "confirmed 30 seconds ago" from "retained since Tuesday". That needs a
  `lastConfirmedAt` per retained key and an exposure of discovery `outcome` on the
  API — the same shape ADR-0026 established for health, handed to
  [REST API shape](https://github.com/fredskor/nodqora/issues/13).
