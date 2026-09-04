# ADR-0102: Implementation amends a failed mechanism and escalates a failed reason; the map closes and reopens

- **Status**: Accepted
- **Date**: 2026-09-04
- **Ticket**: [Implementation slice ordering and handoff](https://github.com/fredskor/nodqora/issues/18)

## Context

Ninety-seven ADRs were written before a line of code. Some are wrong. This map's
own history says so in its own words — three tickets record that *"the ticket's
own framing was wrong, and correcting it was the work"*, and ADR-0091 found a
rule that three ADRs cited and **nobody had ever written**.

First contact will do the same to the ADRs. If the response is undecided, the
default becomes whatever the first implementing session feels like, which is
either paralysis or silent divergence.

## Decision

### The split is reason versus mechanism

Every ADR on this map is a decision plus the fact it rests on. That is the line.

**The mechanism fails but the reason holds** → the implementing session writes a
new ADR amending the old one, continues, and notes it on the slice issue. No
escalation.

> *Example.* ADR-0016's longest-path layering needs a tiebreak rule to reproduce
> the fixture's documented column assignment. The reason — flow beats ownership
> as the organising axis, because ownership is sparse and degrades into one
> "Unowned" lane — is untouched.

**The reason itself turns out to be false** → stop, and open a new ticket on
[map #1](https://github.com/fredskor/nodqora/issues/1).

> *Example.* Flow-layering produces an unreadable canvas at fixture scale.
> ADR-0018, ADR-0019 and ADR-0069 all lean on that reason, so amending ADR-0016
> alone would leave three ADRs standing on a fact that no longer exists.

The test is not difficulty or size. A one-line fix that falsifies a reason
escalates; a week of work that only replaces a mechanism does not.

### One rule with no judgement in it

**An implementing session never edits `CONTEXT.md`'s ubiquitous language.**

Vocabulary is map work. Ticket #6's `Backing.adapter` → `Backing.plugin` rename
touched every plugin at once and retired a word from the whole repository; a
rename made inside one slice silently breaks the other four, and the breakage is
in prose, where nothing compiles and nothing fails. A session that finds it needs
a new term, or a changed one, escalates — however local the change looks from
inside its slice.

Adding to `CONTEXT.md` a paragraph that documents an ADR-0102 amendment is not a
vocabulary change and is ordinary.

### The map closes, and reopens

**Map #1 closes when [#18](https://github.com/fredskor/nodqora/issues/18)
closes.** Its destination reads *"fully specified and ready to build: nothing
left to decide before implementation starts"*, and closing the map is that
sentence asserted.

**An escalation reopens it**, as a new ticket on #1 rather than a second map, and
it closes again when that ticket closes. All ninety-seven ADRs already point at
#1 and its Decisions-so-far is the index an escalation needs; a second map
fragments that index for no gain.

The map's open/closed state therefore tracks the literal truth of its own
destination sentence rather than tracking the calendar.

## Consequences

- **Reopening is expected, not a failure.** The alternative reading — that a
  reopened map means the charting was bad — would make the honest response
  expensive and push sessions toward amending a reason quietly, which is the one
  outcome this ADR exists to prevent.
- **Keeping the map open through implementation was the serious alternative and
  is declined.** It redefines the destination from *specified* to *built*, and
  leaves a map whose children are all closed looking permanently unfinished — so
  the signal that something is genuinely open would be lost in the noise of a map
  that is always open.
- **ADR numbering continues from 0103** and implementation ADRs are not marked or
  segregated. An ADR written in slice 3 is an ADR; the `Ticket` field records that
  it came from a slice issue rather than a wayfinder ticket, which is enough.
- **A failed reason found late is more expensive than one found early, and
  ADR-0098 is partly an answer to this ADR.** Slice 1 exists to put the fixture on
  a canvas before four plugins are built on top of the assumption that it can be.
- **Cost:** the reason/mechanism line is a judgement, and a session that wants to
  keep moving can classify a failed reason as a failed mechanism without lying to
  itself. The `CONTEXT.md` rule is the one hard backstop, and it catches the
  subset of that error which changes vocabulary — not the whole of it.
