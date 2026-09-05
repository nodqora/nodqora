# ADR-0104: An abstention is an omission — `UNKNOWN` never reaches the contribution store

- **Status**: Accepted
- **Date**: 2026-09-05
- **Ticket**: [Slice 3 — Health end-to-end: the contribution store, `/state`, the collapse](https://github.com/fredskor/nodqora/issues/24)
- **Amends**: ADR-0072

## Context

ADR-0072 gave the fast half a store and said `node_state` is a fold of it by
ADR-0024's three steps. ADR-0028 said, separately, that **a node nobody observes
gets no `node_state` row at all**, and that absence reads as `UNKNOWN` /
`rawSignal: null` / `metrics: {}` / `observedAt: null` on the outer join.

Building both, a case neither ADR names falls between them: a plugin that
**was** asked, **could** look, and has nothing to say about a particular node.
ADR-0034 produces three of them — a CronJob with no `suspend`, a backing whose
object has vanished, a workload whose spec could not be read — and ADR-0026's
"backing present, object gone reads `UNKNOWN`" names the situation without
saying where the value goes.

Two readings were available and they disagree about an observable field:

1. **Store the `UNKNOWN`.** ADR-0024 discards it at step 1, so the *health* is
   the same either way. But ADR-0072 makes `observedAt` the `min` over "its
   contributions", so a node whose only contribution is an abstention gets a
   `node_state` row that exists, reads `UNKNOWN`, and reports a freshness — a
   timestamp for an observation nobody made.
2. **Never store it.** The node has no row, and ADR-0028's join produces the
   four values by arithmetic.

Under the first reading the fast half has **two ways to be `UNKNOWN`** which
differ only in `observedAt`, and nothing in the model says which one a given
node should be in. That is the shape of a bug that surfaces as a rendered "as of
45s ago" next to the word "Unknown" — a sentence with no referent.

## Decision

**An abstention is an omission.** `UNKNOWN` is not a value the contribution
store holds:

- A plugin **omits** a node it could not read from its `contributions` map,
  rather than returning `UNKNOWN` for it.
- The store **drops** an `UNKNOWN` contribution if one arrives anyway, treating
  it exactly as an absent key — which under ADR-0046 means a `COMPLETE` run
  deletes any contribution it retracts.
- The `health_contribution.health` CHECK constraint therefore admits four
  values, not five.
- The state fold writes **no row** for a node with no surviving contributions.

Enforcement is at the store rather than in the plugin contract, because it must
hold for the three plugins that do not exist yet and is one line where it would
otherwise be a rule four teams have to remember.

ADR-0072's `observedAt = min` is untouched in wording and now exact rather than
approximate: there is no longer a contribution it could range over that does not
correspond to an observation.

## Consequences

- **There is exactly one way to be `UNKNOWN`, and it is the absence of a row.**
  ADR-0028's arithmetic becomes the *only* path to the value rather than the
  common one, which is what makes "no branch for the nodes that are permanently
  `UNKNOWN`" literally true of the query.
- **`observedAt` never describes an observation nobody made.** Ticket #17
  renders it as "how old is this", and the number now has a referent in every
  case it is non-null.
- **ADR-0024 is unchanged and still discards abstentions.** Step 1 is not dead
  code: a plugin returning `UNKNOWN` alongside real contributions is still
  handled correctly, and the collapse remains total over the five values so the
  same algorithm keeps working *inside* a plugin, where abstentions genuinely do
  arrive — ADR-0034's CronJob case produces one.
- **ADR-0029's "a plugin never returns `UNKNOWN` for a node it actually
  observed" gains a mechanism.** It was a rule with no enforcement; a plugin that
  breaks it now loses its vote *and* its row, which is the same outcome ADR-0029
  predicted, arrived at by the store rather than by the collapse.
- **Cost: the store cannot distinguish "asked and abstained" from "never
  asked".** Both are no row. That information exists one level up and is where an
  operator would look for it anyway: `health_run` records that the plugin
  reported, and its `outcome` says how well it looked. Pushing the distinction
  down to the node would mean storing abstentions, which is the thing this ADR
  declines.
- **This is an ADR-0102 amendment of mechanism.** ADR-0072's reasons — the fast
  half needs a store, contributions are retained per plugin, `node_state` is a
  fold, `observedAt` must not overstate — all hold, and this decides a case its
  mechanism did not cover.
