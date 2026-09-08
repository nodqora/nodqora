# ADR-0149: An edge claims only that it has stopped carrying data, never that it is carrying data

- **Status**: Accepted
- **Date**: 2026-09-08
- **Ticket**: [Stalled edges: show where data has stopped moving, without claiming where it moves](https://github.com/fredskor/nodqora/issues/39)
- **Amends**: [ADR-0018](0018-mvp-canvas-feature-scope.md)

## Context

A node that is off is already legible: ADR-0017 gives `DISABLED` a grey pause bar
and `UNHEALTHY` a red octagon. What the canvas does not show is **the
consequence**. Under the incident scenario `payments-enricher` is `UNHEALTHY` and
both sinks are `DISABLED`, and the four nodes downstream of them sit `UNKNOWN`
— correctly, because ADR-0027 says health never propagates and nobody observed
them. Five nodes read as fine while the pipeline behind them has stopped, and
nothing on screen says so.

The fact belongs on the **edge**. An edge is a claim about data moving between
two nodes; whether it is moving is an edge's property, not a node's.

## Decision

### Only the negative claim is available

**Nothing in this system measures flow, so nothing may draw it.** Throughput
exists only as a sampled derivative of two `listOffsets` calls, and sampling *is*
diffing — ADR-0012 says plugins are stateless and hold no previous state, which
is why ADR-0038 rules throughput out structurally rather than on cost. No field
on the wire carries it and no plugin reports it.

So an edge is drawn **stalled** or it is drawn plain, and plain is not a signal:
it means *nothing observed says otherwise*. A live edge asserts nothing.

**Animation is refused.** Motion reads as *data is moving* more forcefully than
any legend can walk back, and asserting it from evidence nobody gathered is the
exact failure the honesty layer (ADR-0106) exists to prevent. The one path to an
honest animation is a *did the end offset move since the last poll* bit — the
`kafka` plugin already reads those offsets for lag — and it costs ADR-0012's
statelessness. That is a separate decision and is not taken here.

### Stopped is `DISABLED` or `UNHEALTHY`, and nothing else

A human wrote `replicas: 0`, or nothing is ready.

- **`DEGRADED` is not stopped.** Some replicas serve; data still moves, just less
  of it. ADR-0025 defines it as exactly that partial state.
- **`UNKNOWN` is not stopped.** Nobody looked. `UNKNOWN` is an abstention
  (ADR-0024, ADR-0029), and reading an abstention as a stall invents the
  observation this ADR exists to avoid inventing. Four fixture nodes hold
  `UNKNOWN` straight through the incident.

### Three rules, the third a fixed point

An edge is **stalled** when

1. its **source** is stopped — nothing is being produced onto it; or
2. its **target** is stopped — nothing is consuming from it; or
3. its source is **starved**: the source has at least one inbound edge and
   *every* one of them is stalled.

Rule 2 is the judgement worth stating: an edge carries data only if both ends
work, so a live arrow into a dead node would draw a thing that is not happening.

**"Every" inbound edge, not "any".** A node with two producers, one stopped and
one running, is still receiving data and must not starve. "Any" would stall half
of a healthy graph on one paused sibling.

Rule 3 is computed as a **least fixed point** over the seed set rules 1 and 2
produce. It is monotone, so it terminates; and starting from the empty set is
what makes a cycle stay live unless something outside it seeds one — the relation
vocabulary permits `service → topic → service`, and any pipeline with a retry
topic closes the loop.

**Nodes with no inbound edges never starve.** They stall only on their own
health, which falls out of the adjacency rather than being a case to remember.

### Encoding: hollow the arrowhead, thin the stroke

Hueless. ADR-0082 gives health sole ownership of hue and this must not compete
with it. The edge's other channels are already spent: `opacity` is selection-dim,
`stroke-dasharray` is ADR-0016's cross-team boundary, `stroke` colour is the
selection accent. A **hollow arrowhead** reads as nothing passing through, and a
**reduced stroke weight** reinforces it; both are free.

It **composes with all three existing states** rather than replacing them — a
stalled cross-team edge under a dimmed selection is a real combination — so
stroke weight is a product of two independent CSS custom properties rather than a
value each state overwrites.

### Where it lives

`frontend/src/canvas/stalled.ts`, a pure function over `(edges, health)` beside
`highlight.ts`, tested in `node` against the golden fixtures. **No backend change
and no new API field**: both inputs are already in the client's hands, and
ADR-0053 keeps traversal client-side (ADR-0054 has no traversal endpoint).

## Consequences

- **The incident's blast radius becomes visible without a single node changing
  colour.** Seven of the nine fixture edges stall; `stripe-webhooks →
  payments-api` and `payments-api → payments.events.raw.v1` do not, because
  ingest is still running and the canvas has to keep saying so. The baseline
  stalls nothing. That pair of assertions is the test.

- **This is inference from a neighbour applied to an *edge*, which ADR-0027
  forbids for *health*.** The boundary is not a loophole and is worth stating out
  loud, so ADR-0027 carries a pointer here rather than leaving the two readings
  to collide. A node's health is an observation with a `rawSignal` behind it, and
  a propagated one would force the inspector to print *"because something
  upstream is unhealthy"* — an inference where it promises a signal. An edge has
  no such field to overwrite: nothing ever observed whether data moves along one,
  so there is no observation to displace. Every input here is somebody's
  observation; only the composition is new.

- **ADR-0027's door stays shut.** The rolled-up "is anything wrong beneath me"
  value it left open is still not taken, and this is not it: nothing is stored,
  nothing lands on a node, and `health` remains an observation permanently.

- **`DEGRADED` under-reports on purpose.** A connector at 1 of 3 tasks moves data
  slowly enough to be an incident and this draws its edges plain. Calling it
  stalled would be false, and the honest alternative — saying *how much* it
  carries — is the throughput this ADR just refused.

- **Two stopped nodes at opposite ends of a graph stall two disjoint regions**,
  with no ranking between them and no "root cause" claimed. The canvas shows
  where data has stopped, not why; ADR-0018's deferred path highlighting is still
  deferred.

- **A cycle whose only external feed stops keeps drawing live.** A retry loop
  `a → b → a` fed by a stopped service: `a`'s inbound is the dead feed *and* the
  edge from `b`, and the second never resolves, because `b` starves only if `a`
  does. Under a least fixed point neither does, so the loop is its own live
  producer as far as anything here can tell. This is the price of the cycle guard
  that buys termination, and it is the right price — data already circulating in
  a retry loop is real data, and the alternative reading would stall it on no
  evidence. A stopped node *inside* the cycle stalls the whole loop normally,
  by rule 1 and then rule 3 around it.

- **The cost of "every inbound edge" is that one live producer masks the loss of
  every other.** A topic fed by four services, three of them down, is receiving
  data and is drawn as such. That is true and it is much less than the whole
  truth; the missing three-quarters is throughput again.

- **This is an ADR-0102 amendment of mechanism, not of reason.** ADR-0018's
  canvas scope gains an edge treatment it did not list, in the style of the
  ADR-0081/0083/0087/0097 notes already in that file. Its scope discipline is
  untouched — this is not one of the deferred filters, and it has no control,
  no toggle and no state.
