# ADR-0083: Retained and blind are separate marks, and the caveat sits inside Health

- **Status**: Accepted
- **Date**: 2026-09-04
- **Ticket**: [Surfacing plugin outcome and node freshness in the UI](https://github.com/fredskor/nodqora/issues/17)

## Context

ADR-0081 fixes *that* affected nodes are marked. Two different things can be
wrong with a node, and the prototype's fixtures separate them cleanly:

- **Retained** — a discovery poll came back non-`COMPLETE` and the node's
  topology was carried forward rather than confirmed. The fixture's `retained`
  case: `connect` polls fine but two of three workers 503, so both connectors
  are 23 minutes old. **Health is untouched.** Nothing on the health channel
  changes at all, so freshness is the only signal that exists.
- **Blind** — a *health*-capable plugin that backs this node abstained this
  cycle. ADR-0024 discards abstentions, so the node still shows the surviving
  observers' verdict. The fixture's `blind` case: both connectors read
  `HEALTHY` on `kubernetes` + `kafka` while `connect` is unreachable.

These are not degrees of the same problem. A retained node's *topology* is old
and its health is current; a blind node's topology is current and its health is
under-observed. The same mark for both would say neither.

The ticket also asked whether `DISABLED` and a failed observer must be
distinguishable without opening the node. The `blind` fixture answers it: the
enricher is honestly `DISABLED` (`spec.replicas: 0`, ADR-0034) while both
connectors are dishonestly calm. Under ADR-0017 alone, all three render quiet.

## Decision

> **Amended by [ADR-0088](0088-unreported-banners-over-a-non-empty-graph.md).**
> A health-capable backing whose plugin has **never reported** counts as blind,
> on the same mark, token and caveat. It also breaks the "four permanently
> `UNKNOWN` nodes are never marked" symmetry in the useful direction: a node with
> health-capable backings that have not reported *is* marked, so
> `UNKNOWN`-not-yet-looked stops rendering as `UNKNOWN`-nothing-watches-this.

**Two marks on the canvas, and neither is a colour (ADR-0082).**

| condition | canvas mark |
|---|---|
| any source retained (ADR-0084) | **hatched top edge** on the node card |
| any health-capable backing abstained this cycle | **outlined border** plus a footer token naming the plugin, e.g. `connect blind` |

A node can carry both; in the `blind` fixture both connectors do.

**In the inspector, the caveat sits inside Health — section 2 — not in a section
of its own.** It names the plugin that could not look, its cause, and what the
displayed value was actually composed from:

> `connect` could not observe this node this cycle. Healthy is composed from
> kubernetes + kafka only.

A separate Observation section was rejected because the caveat is a *statement
about that health value*. Putting it elsewhere makes the reader correlate two
places to find out whether the number above is trustworthy, and ADR-0019's whole
argument for a fixed section order is that the reader should not have to hunt.

**Section 7's "Discovered by" carries the freshness**, one row per source:
plugin, `confirmed 23m ago`, and a `retained` tag where ADR-0084 says so. That
is the entirety of `sources[]`'s new width on the wire, rendered where
`sources[]` already rendered.

## Consequences

- `DISABLED` and an unwatched node are distinguishable **on the canvas**: the
  enricher is calm and unmarked, the connectors carry a border and a named
  plugin. Question four is answered without opening a node.
- ADR-0019's seven sections stand unchanged. Nothing was added; two existing
  sections got denser.
- The four permanently-`UNKNOWN` nodes are never marked by either rule — they
  have no sources that can go stale beyond `yaml`, and no health-capable
  backings to abstain. The cry-wolf constraint holds by construction rather than
  by a special case.
- **`yaml` can be retained too.** It is a plugin (ADR-0011) with a discovery
  outcome like any other, so a directory that fails to parse marks its nodes.
  This is the first thing on any surface that makes `yaml`'s plugin-ness
  visible to a user.
- The footer token displaces the source list on marked nodes. Accepted: during
  trouble, which plugin went blind is worth more than which plugins discovered
  the node, and the full list is one click away in section 7.
