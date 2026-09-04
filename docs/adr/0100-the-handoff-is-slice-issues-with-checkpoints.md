# ADR-0100: The handoff is five slice issues with named checkpoints, and no implementation plan document

- **Status**: Accepted
- **Date**: 2026-09-04
- **Ticket**: [Implementation slice ordering and handoff](https://github.com/fredskor/nodqora/issues/18)

## Context

Ninety-seven ADRs is past what fits in one agent context. ADR-0098 fixes the
order; this ADR fixes what an implementing session is actually handed when it
starts.

Three shapes were available: per-slice ADR subsets, a written implementation
plan, or issues-per-slice with pointers.

## Decision

### No implementation plan document

**A document restating the ADRs as build instructions is a second copy of every
decision.** It is the failure mode ADR-0051 refused a pinned-fields mechanism to
avoid, and the one ADR-0099 spent its whole design budget avoiding one layer
down. It would rot faster than `docs/reference-pipeline.md`, being longer and
less loved, and the rot would be invisible because nothing would test it.

### Five slice issues, four things each

One GitHub issue per ADR-0098 slice, labelled `slice:1` … `slice:5`, chained
with the tracker's native dependencies so the order renders in the UI. Each body
carries exactly four things and no prose that restates a decision:

1. **Scope**, in a paragraph.
2. **The ADR subset**, listed explicitly.
3. **Done-when**, stated as ADR-0099's golden artifacts.
4. **The accepted costs it must not try to fix** (ADR-0101).

### A checkpoint is one session

A slice is not a session. Slice 1's subset is roughly fifty ADRs, and *"ready to
build"* is false if the first thing an implementing agent must do is re-plan.

So each slice issue **names its checkpoints in its body**, and a checkpoint is
sized to one session with its own subset drawn from the slice's. Slice 1's are:

| # | checkpoint | subset centre |
|---|---|---|
| 1.1 | monorepo scaffold, Flyway, Vite, ArchUnit boundary test | ADR-0015 |
| 1.2 | snapshot store, the fold, derived tables | ADR-0043–ADR-0051, ADR-0071–ADR-0080 |
| 1.3 | the three GETs | ADR-0052–ADR-0060 |
| 1.4 | the canvas and the drawer | ADR-0016–ADR-0019 |

Checkpoints do not become their own issues unless the implementing session wants
them to. They are a starting decomposition, not a contract.

### The slice issues are not wayfinder tickets

They carry no `wayfinder:*` label and are **not children of map #1**. Map #1's
notes make it planning-only; a slice issue parented to it would put
implementation work on the frontier and the map would never reach its own
destination (ADR-0102).

## Consequences

- **Naming slice 1's checkpoints is a small act of implementation planning done
  before first contact** — precisely the kind of guess this map has otherwise
  refused to make. It is taken for slice 1 alone, because slice 1 is the one that
  has to start from nothing; slices 2–5 begin with working code and a running
  canvas to plan against.
- **The ADR subset is the context budget, and it is the artifact with real
  value.** Selecting fifty ADRs from ninety-seven is the expensive part of every
  session start, and doing it once per slice rather than once per session is
  most of what this decision buys.
- **An ADR appearing in two subsets is normal.** ADR-0044's precedence is read by
  slices 2 and 4; ADR-0017's encoding by slices 3 and 5. The subsets are reading
  lists, not a partition.
- **Nothing generates or checks the subsets.** An ADR written under ADR-0102
  during slice 3 is not automatically added to slice 4's list. The lists are
  maintained by hand or not at all, which is acceptable because a subset that is
  slightly short costs one lookup, not a wrong build.
- **Cost:** the slice issue is the only place a checkpoint exists, so a
  checkpoint's progress is invisible on the tracker unless the implementing
  session promotes it to an issue. Chosen over five-plus-N issues up front,
  which would put twenty-odd open issues on a repo that has never compiled.
