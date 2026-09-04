# ADR-0098: The MVP is built as five vertical slices, `yaml` first

- **Status**: Accepted
- **Date**: 2026-09-04
- **Ticket**: [Implementation slice ordering and handoff](https://github.com/fredskor/nodqora/issues/18)

## Context

Ninety-seven ADRs and a `CONTEXT.md` exist; no code does. The destination on
[map #1](https://github.com/fredskor/nodqora/issues/1) is *"fully specified and
ready to build"*, and the order in which it gets built is the last thing
undecided.

**The dependency structure is not the ADR numbering.** ADR-0043's fold and
ADR-0071/ADR-0072's stores sit under everything and were written seventieth.
ADR-0016's layout needs edges, which under ADR-0033 as amended come from `yaml`
and `connect` only. Numbering records the order questions were *asked*, which is
close to the reverse of the order they must be *answered in code*.

## Decision

**Five vertical slices, in this order. Each carries one hard idea and ends at
something a human can look at.**

| # | slice | the one hard idea | visibly new when done |
|---|---|---|---|
| 1 | `yaml` end-to-end | the **fold** (ADR-0043) — snapshot store, derived tables, `/graph`, layout, drawer | production renders (10 nodes / 7 edges), staging renders (7 / 6), layout matches `docs/reference-pipeline.md` with no hand-placed positions; every node grey |
| 2 | `kubernetes` discovery | the **merge** — ADR-0021's cascade and ADR-0044's precedence | `enricher-v2` ≠ `payments-enricher` ≠ `enrich-consumer-prod` resolves to one node; backings appear; ADR-0032's composed links appear |
| 3 | health end-to-end | the **collapse** (ADR-0024) — contribution store, second fold, `/state`, ADR-0017's encoding | the canvas goes coloured at 30s; the scaled-to-zero enricher renders `DISABLED` (ADR-0034) |
| 4 | `connect`, then `kafka` | the third observer, and **attribution by the knowing plugin** (ADR-0022) | the last two edges close the graph; `payments-es-sink` collapses three observers; `RUNNING`-with-a-`FAILED`-task renders `DEGRADED`; lag renders amber |
| 5 | the honesty layer | non-`COMPLETE` polls and cold stores (ADR-0081–ADR-0089), plus search (ADR-0066–ADR-0070) and the URL (ADR-0092–ADR-0097) | `connect` unreachable is *visible*; an empty environment gets its three states; search and deep links work |

### Why `yaml` first, and why that is not a general preference for vertical slicing

**`yaml` is a complete plugin, and it is the only one that is.** ADR-0011 made
the YAML topology a plugin; ADR-0063 then made it carry a stanza for all ten
fixture nodes — four declared with `type` and `displayName`, six of one or two
lines — and seven of the nine production edges. Only the two `SOURCES_FROM`
edges come from `connect` (ADR-0041, ADR-0033 as amended).

So a `yaml`-only slice renders **the reference pipeline** — laid out, selectable,
deep-linkable — with **no cluster, no broker and no Connect worker**. It
exercises the snapshot store, the fold, scalar precedence, stub nodes
(ADR-0048), descriptors riding with the graph (ADR-0055, ADR-0077), `/graph`,
the layout and the drawer. Nothing else on the map can do that, because
everything else needs infrastructure to say anything at all.

A horizontal build reaches the same picture only at the very end, and pays for
it by having **no executable check on any ADR until then** — which, with
ninety-seven of them written before first contact, is the risk worth the most to
retire early.

### Why slices 2 and 3 are separate

Both are merges, and they are not the same merge: two writers into one **node**
against three observers into one **health**. Slice 2 is also the *minimum pair*
of discovery plugins — the merge is not real with one — and `yaml` + `kubernetes`
is exactly the pair that produces the fixture's deliberate three-way identity
mismatch (`docs/reference-pipeline.md` §5.1).

### Why `connect` before `kafka` inside slice 4

`connect` closes the edge set, so the graph is complete one step earlier.
`kafka` reads only the **routed** group set (ADR-0040), and its routes arrive by
two unioning paths — `topology.io/consumer-groups` and YAML `consumerGroups:` —
both of which have already landed by then.

### There is no slice 0

Monorepo layout, Spring Boot, Flyway, Vite and XYFlow ride inside slice 1. One
exception is pulled forward: **ADR-0015's ArchUnit boundary test must exist
before slice 2 lands**, because the second plugin is the first thing capable of
breaching it.

## Consequences

- **Slice 1 ends with every node grey, and that is a correct rendering rather
  than a placeholder.** `yaml` declares no Health capability (ADR-0010), so
  `UNKNOWN` is the honest answer — the same answer ADR-0057 will have `/state`
  synthesize in slice 3 for any node with no contributions. The frontend's
  health path therefore does not change when `/state` arrives; only its source
  does.
- **Slice 1 is the largest by a wide margin** — roughly fifty ADRs — and is
  decomposed into named checkpoints by ADR-0100 for that reason.
- **ADR-0016's central claim is tested first, not last.** It asserts that plain
  longest-path layering reproduces the fixture's documented shape with no
  hand-placed positions. Under this order that claim is executable at the end of
  the first slice, when the cost of it being wrong is lowest — and ADR-0018,
  ADR-0019 and ADR-0069 all rest on it.
- **The four permanently-`UNKNOWN` nodes are load-bearing from slice 1.** They
  are `yaml`'s declared nodes, so the graph is visibly mixed before any code
  plugin exists — ADR-0011's *"no MVP feature may assume every node has a plugin
  behind it"* is enforced by the build order rather than by discipline.
- **Cost:** slices 2 and 4 need recorded infrastructure responses before they can
  be tested, and ADR-0099 pins how those are obtained. Slice 1 needs none, which
  is the reason it can start cold and the later slices cannot.
