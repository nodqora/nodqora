# ADR-0029: `DISABLED` requires evidence of intent

- **Status**: Accepted
- **Date**: 2026-09-02
- **Ticket**: [Health normalization model](https://github.com/fredskor/nodqora/issues/9)

## Context

ADR-0024 gives `DISABLED` extraordinary power: it wins the collapse outright,
over `UNHEALTHY` included. A value that can silence every other observer needs a
rule saying who is allowed to emit it.

ADR-0025 leaves it reachable from exactly two places — `kubernetes` with
`desired = 0`, and `connect` with `PAUSED` or `STOPPED`. Both are the residue of
a human's deliberate act. That looks like a coincidence and is not.

## Decision

**A plugin emits `DISABLED` only from a signal that evidences human intent.**
Absence of activity is never sufficient.

**`kafka` therefore never emits `DISABLED`.** An `EMPTY` consumer group — no
live members — is the exact state of both a scaled-to-zero consumer and a
crashed one, and research #5 confirms `describeConsumerGroups` returns it
identically either way, offsets and all. The plugin that *can* tell the
difference (`kubernetes`, seeing `desired = 0`) is already observing the same
node. An empty group with lag over threshold is `DEGRADED` like any other.

**A plugin never returns `UNKNOWN` for a node it actually observed.** Under
ADR-0024 `UNKNOWN` is an abstention that gets discarded, so returning it for an
observed-but-indeterminate state deletes the plugin's own vote — which is why
ADR-0025 maps Connect's `UNASSIGNED` and `RESTARTING` to `DEGRADED`. `UNKNOWN`
means "I was not asked" or "I could not look", and nothing else.

**No manual maintenance mode in the MVP.** §56 offers manual overrides and the
obvious shape is letting the YAML topology declare a node `DISABLED`. Rejected:
`yaml` stays **Discovery-only**. ADR-0011 and the plugin contract already make
`UNKNOWN` fall out of `yaml` having no Health capability, and reversing that
gives YAML a health value that is static forever, wins every collapse, and
quietly hides real failures until someone edits a file. The MVP is read-only
with no auth (ADR-0014), so there is no safe write path for a mute either.

## Consequences

- `DISABLED` keeps meaning one thing on the canvas — *someone turned this off* —
  which is what makes ADR-0017's pause glyph readable at a glance.
- A consumer scaled to zero still reads `DISABLED`, because Kubernetes says so;
  the signal arrives from the plugin that can see intent, not from the one that
  sees the silence. This is ADR-0022's separation of *reading* a signal from
  *attributing* it, applied to a second kind of blindness.
- Muting a node for a planned migration is not possible in the MVP. Revisit when
  auth exists and there is a safe write path.
- A future plugin adding a `DISABLED` source must point at the deliberate act it
  read. `paused`, `suspended`, `scaled to zero`, `stopped` qualify; `idle`,
  `no traffic`, `no members` do not.
