# ADR-0077: TypeDescriptors are a read-time projection, not global mutable state

- **Status**: Accepted
- **Date**: 2026-09-03
- **Ticket**: [PostgreSQL schema and persistence](https://github.com/fredskor/nodqora/issues/16)
- **Amends**: ADR-0055, ADR-0049

## Context

ADR-0062 settles half the question already: *"the six RelationDescriptors are core
built-ins, seeded at startup, not plugin-registered."* Only **TypeDescriptors**
arrive in a `DiscoveryResult`.

TypeDescriptors are **global** (ADR-0055) while everything else in a snapshot is
environment-scoped — ADR-0049 calls them *"the single global exception"* and
describes their rule as **register-if-absent, first-registration-wins**.

That rule contradicts ADR-0043's central claim: *"the graph can be rebuilt from
the store at any time."* A register-if-absent table **cannot** be rebuilt, because
first-registration-wins is a fact about history rather than about the current
inputs — drop it, refold, and a different entry may win. It would be the only
mutable, non-derived, undeletable state in a system whose premise is that
everything is a fold. It also inherits the argument ADR-0049 made against exactly
this shape one entity over — *"register-if-absent would let poll order decide"* —
rejected for Owners as nondeterminism, and accepted for descriptors only because
nothing had forced the question. ADR-0074's cascade forces it: a global thing must
sit outside every environment's lifetime.

## Decision

**There is no descriptor table.** TypeDescriptors are stored as
`plugin_snapshot_entry` rows with `entity_kind = 'TYPE_DESCRIPTOR'`, and the
global set served inside `/graph` is a **read-time projection** over the store —
the third, alongside `outcome` and `confirmedAt` (ADR-0056).

**Contested type ids resolve by ADR-0044's plugin precedence first, then by
environment order as declared in the config file.** Both orders already exist and
are already deterministic; no new ordering rule is invented.

RelationDescriptors remain seeded code constants (ADR-0062).

## Consequences

- **ADR-0055's race is closed harder than ADR-0055 closes it.** Its worry was *"a
  canvas holding a node whose type it cannot draw"*, solved by committing node and
  descriptor in one fold. Here they need no common commit: **the snapshot that
  carried the node carried the descriptor**, they arrive in the same write, and
  `/graph` reads both from the store. There is no window.
- **Descriptors become mortal in the right way.** A descriptor lives exactly as
  long as some plugin's current snapshot claims it, with `PARTIAL` retention and
  `FAILED` no-op protecting it from a blip for free, by ADR-0046's existing rules.
  Under register-if-absent, a type from a YAML file deleted last year rides in
  every graph document forever with no removal path but a manual `DELETE`.
- **First-registration-wins is replaced by a total order over the current
  inputs** — the same substitution ADR-0049 made for Owners.
- **A global *derived* table was the near miss.** It works, but it makes every
  per-environment fold take a **global** lock, serializing `production` and
  `staging` folds against each other (ADR-0075) to maintain a table whose only
  reader is `/graph`.
- **Cost:** a type nobody currently declares renders with ADR-0001's fallback
  descriptor rather than keeping its icon indefinitely. This is a real behaviour
  change, and the one thing register-if-absent does better — but ADR-0055 already
  accepted exactly this cost for its rejected bootstrap-document alternative, and
  a vocabulary entry nothing claims is not obviously worth keeping.
- **Cost:** `/graph` gains a third read-store query, a `DISTINCT ON (type_id)` over
  descriptor entries across all environments. Tens of rows.
- `/graph` serves two descriptor lists from two entirely different mechanisms —
  odd on the wire, correct underneath, and worth a comment in the code rather than
  a table nobody writes.
