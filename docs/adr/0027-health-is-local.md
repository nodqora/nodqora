# ADR-0027: Health is local — it never propagates across edges

- **Status**: Accepted
- **Date**: 2026-09-02
- **Ticket**: [Health normalization model](https://github.com/fredskor/nodqora/issues/9)

> **Boundary noted by [ADR-0149](0149-an-edge-claims-only-that-it-has-stopped-never-that-it-carries.md).**
> This ADR stands unchanged and nothing here is amended. ADR-0149 infers a
> property of an **edge** from its neighbours — an edge whose source or target is
> `DISABLED` or `UNHEALTHY` is drawn as carrying nothing — and the two readings
> would otherwise collide, so the line between them is stated here rather than
> left to be rediscovered.
>
> What this ADR forbids is inference into **`health`**, a field that promises an
> observation with a `rawSignal` behind it; a propagated value has none, and the
> inspector would have to print *"because something upstream is unhealthy"* where
> it promises to show its work. Whether data moves along an edge was never
> observed by anybody — no plugin reports it and no field carries it (ADR-0038,
> ADR-0012) — so there is no observation to displace and no `rawSignal` to
> falsify. The five glyphs still discriminate, because ADR-0149 changes no node:
> under the incident the four downstream nodes stay `UNKNOWN` and the sinks stay
> green. And the door this ADR left open — a stored roll-up — stays shut.

## Context

A graph tool that knows both the topology and each node's health is one line of
code away from rolling health up the edges. The question is whether it should.

The reference pipeline answers it about as loudly as a fixture can. Under the
incident scenario `payments-events-v1` sits directly downstream of a `DISABLED`
sink and two hops from an `UNHEALTHY` enricher — and it is **`UNKNOWN`**. So are
`analytics.payments_events` and `trino-analytics`. All four declared nodes hold
`UNKNOWN` straight through an active incident happening immediately upstream.

## Decision

**A node's health is what its own backings report, and nothing else.** Health
never propagates, rolls up, or is inferred from a neighbour.

## Consequences

- `rawSignal` keeps its meaning. ADR-0003 holds the unnormalized observation so
  the inspector "can always show its work"; a propagated value has no
  observation behind it, and the inspector would have to print *"because
  something upstream is unhealthy"* — an inference, not a signal.
- **The shape stays readable, which is the actual information.** The incident is
  designed as "a single failing workload mid-pipeline, healthy upstream, stalled
  downstream — the graph should make the blast direction obvious." Propagation
  paints the whole downstream branch red and the one broken node stops standing
  out. ADR-0017 designed five distinct glyphs precisely so the canvas
  discriminates; propagation flattens half of them.
- **Blast radius stays out of scope.** §17 is ruled out on the map, and a
  propagation rule is that feature with worse ergonomics and no user control.
- **No cycle handling.** The relation vocabulary permits
  `service -> topic -> service -> topic -> service`, and any pipeline with a
  retry topic closes the loop. Propagation would need cycle detection and a
  fixpoint; local health needs neither.
- The door left open: if a rolled-up "is anything wrong beneath me" value is
  wanted later, it is **derived at read time in the API or frontend and never
  stored**, leaving `health` an observation permanently. Compatible with
  everything above and costing nothing now.
