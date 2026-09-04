# ADR-0089: The plugins chip counts states rather than ranking them

- **Status**: Accepted
- **Date**: 2026-09-04
- **Ticket**: [The empty and repopulating environment](https://github.com/fredskor/nodqora/issues/19)
- **Amends**: ADR-0081

## Context

ADR-0081 pinned the chip's resting text on the happy path only: *"At rest it
reads `4 plugins · all complete`."* ADR-0085 makes the denominator a **config**
count that never shrinks, so the first half is settled. The summary clause is
not, and there is now a state ADR-0081 never saw.

## Decision

**The chip names the states that are present, with counts, and ranks nothing.**

```text
4 plugins · all complete
4 plugins · 1 failed, 2 not reported
4 plugins · none reported
4 plugins · no plugins configured        (roster empty — ADR-0087)
```

The popover is unchanged: every plugin's two capabilities, its `outcome`, its
reasons or cause, and when each was recorded.

## Consequences

- **A worst-first phrase requires a ranking that does not exist.** Under
  ADR-0086 a `FAILED` is **actionable now** — fix the kubeconfig — while
  unreported is **wait**. Neither dominates, and a ladder makes the chip quietly
  drop whichever it ranked second, which on a fresh install is the one you most
  need to see. This is ADR-0026's finding reused: *"the five states are confirmed
  but are not a ladder"*, and `outcome` is no more a ladder than `health` is.
- **A binary `needs attention` has the same defect in cruder form**, discarding
  precisely the distinction that decides whether the operator acts or waits.
- The clause only ever names states actually present, so the happy path is
  unchanged and the chip stays one rail element — ADR-0081's *"one rail element
  and zero canvas pixels"* standing cost is not reopened.
- **It reads as progress rather than as a fault during repopulation.** ADR-0087
  accepted that a `payload_version` discard is indistinguishable from a fresh
  install; a roster that moves `none reported` → `2 not reported` → `all
  complete` across successive polls is the mitigation that ADR-0087 could point
  at, and it exists here rather than being designed for it.
- Cost: the clause grows with the roster. At MVP scale it is four plugins over
  two capabilities, and §48 puts a plugin marketplace well past this
  destination.
