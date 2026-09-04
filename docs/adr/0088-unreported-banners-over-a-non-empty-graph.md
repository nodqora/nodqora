# ADR-0088: Unreported banners only over a non-empty graph, and reads as blind on the node

- **Status**: Accepted
- **Date**: 2026-09-04
- **Ticket**: [The empty and repopulating environment](https://github.com/fredskor/nodqora/issues/19)
- **Amends**: ADR-0081, ADR-0083

## Context

ADR-0081 fixed the exception surfaces: *"Nothing else renders while every outcome
is `COMPLETE`. A non-`COMPLETE` poll raises a banner above the canvas naming the
plugin, the capability, the cause and the affected node count, and marks the
affected nodes (ADR-0083)."*

ADR-0085's `null` is not `COMPLETE`, so the rule as written fires — and
immediately demands two things that do not exist. There is no **cause**: nothing
failed, nothing was reported at all. And there is no computable **affected node
count**: an unreported plugin has said nothing about what it would have carried.

On the health side the problem is sharper. Under ADR-0057 an unobserved node
synthesizes `UNKNOWN` and ADR-0017 draws a dashed ring — correct and
unremarkable on four of the fixture's ten nodes. A health plugin that has never
reported therefore renders every node it backs exactly like a node nothing is
meant to watch.

## Decision

**Unreported raises a banner only when the graph is non-empty**, for either
capability:

> `yaml` has not reported yet. This graph may be incomplete.

Plugin and capability named; **no count and no cause**, because neither exists.

**An unreported health-capable backing counts as blind under ADR-0083** — the
same outlined border, the same footer token, the same caveat inside Health
section 2. No third encoding.

## Consequences

- **The all-cold case is already covered and would be said twice.** ADR-0087's
  empty state is unmissable; a banner stacked over an empty canvas is a second
  copy of one sentence.
- **The mixed case has no other surface at all.** The canvas draws a graph that
  looks whole. On the fixture that is concrete: `yaml` unreported with
  `kubernetes` reported renders a plausible pipeline with the four declared-only
  nodes silently missing. That is ADR-0026's failure verbatim — *"we could not
  look"* rendered as *"nothing is wrong"* — and ADR-0083's marks cannot reach it,
  because a node that is not there cannot be marked.
- **The cry-wolf objection was checked and does not hold.** ADR-0043 makes the
  store durable — *"on boot it already holds the last accepted snapshot per pair;
  the fold runs from it immediately"* — so an ordinary restart never enters this
  state. It is reachable only on a genuinely fresh install, an ADR-0080 version
  bump, or a newly-configured environment or plugin: three deliberate acts, each
  self-healing within one cadence. That is the opposite of the `COMPLETE COMPLETE
  COMPLETE` fatigue ADR-0081 legislated against.
- **The rule is uniform across capabilities because the distinction would cost
  more than it saves.** Unreported health beside a drawn graph is nearly
  unreachable — health polls at 30 seconds against discovery's 5 minutes
  (ADR-0035, ADR-0042) and ADR-0086 writes a header on every poll, so by the time
  discovery has made the graph non-empty, health has written one many times over.
  A discovery-only rule buys nothing and leaves a capability branch in the
  frontend that a later reader must reconstruct a justification for.
- **Blind already means the right thing.** ADR-0083's mark answers *is the health
  value on this node trustworthy?*, and a node whose `connect` backing has never
  been read is missing precisely the same contribution as one whose `connect`
  abstained this cycle. Its inspector sentence needs no rewording: *"`connect`
  could not observe this node this cycle. Healthy is composed from kubernetes +
  kafka only."* A third canvas mark was rejected against ADR-0081's own conceded
  cost — *"two encodings most viewers will not have seen before the day they
  matter"*.
- **It closes the ticket's own complaint with no new pixel.** ADR-0083 notes the
  four permanently-`UNKNOWN` nodes are *"never marked by either rule — they have
  no health-capable backings to abstain"*. A node whose health-capable backings
  exist but have never reported now **is** marked, so
  `UNKNOWN`-because-nobody-has-looked-yet stops rendering identically to
  `UNKNOWN`-because-nothing-watches-this.
- **Cost:** the banner has a third text variant with two fewer facts than the
  other two, and the reader has to notice that "has not reported yet" is weaker
  than a named cause. Accepted: the alternative is a fabricated count, which
  ADR-0058 forbids in general terms and ADR-0079 already refused in this exact
  situation.
