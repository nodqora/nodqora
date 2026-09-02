# ADR-0049: Owners are environment-scoped discovery output

- **Status**: Accepted
- **Date**: 2026-09-02
- **Ticket**: [Discovery-engine merge semantics](https://github.com/fredskor/nodqora/issues/12)
- **Amends**: ADR-0012

## Context

ADR-0007 makes Owner a first-class entity with `key`, `displayName`, `channel`
and `onCall`, referenced from nodes by `ownerKey`. `DiscoveryResult` is
`{ nodes[], edges[], descriptors[], outcome }`.

A search across all forty-two ADRs and `CONTEXT.md` finds **no delivery path for
an Owner entity at all**. Nodes reference `ownerKey`; nothing ever produces the
Owner. It went unnoticed because every prior ticket dealt in nodes and edges.

Only `yaml` can produce one: `kubernetes` reads `topology.io/owner` and gets a
*key*, never a channel or an on-call rotation.

## Decision

**`DiscoveryResult` becomes `{ nodes[], edges[], owners[], descriptors[], outcome }`.**

**Owners are environment-scoped and folded exactly like nodes** — keyed
`(environmentKey, ownerKey)`, same precedence (ADR-0044), same deletion-by-absence
(ADR-0047), same `PARTIAL` / `FAILED` handling (ADR-0046).

## Consequences

- **Not modelled on `descriptors[]`, deliberately.** Descriptors are global with
  register-if-absent, first-registration-wins. If owners worked that way,
  `production` and `staging` YAML both declaring `payments-platform` would resolve
  by whichever environment happened to poll first — a team's on-call channel
  decided by poll ordering, exactly the nondeterminism ADR-0021 rejected.
- Environment-scoping lets staging legitimately page nobody, and gives the engine
  **one fold over three entity kinds** with descriptors as the single global
  exception ADR-0012 already made.
- `payments-platform` is stored once per environment. Trivial duplication against
  a uniform fold.
- ADR-0007's "stored once, never copied onto nodes" is unchanged — the duplication
  is per environment, not per node.
