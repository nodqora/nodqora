# ADR-0065: `kubernetes` gains no edge annotations; the vocabulary stays closed at nine

- **Status**: Accepted
- **Date**: 2026-09-02
- **Ticket**: [YAML topology format](https://github.com/fredskor/nodqora/issues/14)

## Context

ADR-0033 established that `kubernetes` emits **no edges at all**, and deferred
`topology.io/consumes` / `topology.io/produces` to this ticket — held back only
so the YAML edge shape could be decided first. ADR-0041 then made `connect`
emit edges to keys it does not own, so a plugin declaring a dangling edge is now
established in one plugin, and ADR-0062 gives the annotation an obvious shape:
the verb key, with the annotated object as the subject.

The standing cost is real: a team that adds a new consumer service gets health
routing free from `topology.io/consumer-groups`, and **no edge on the canvas
until someone edits YAML**.

## Decision

**No. The `topology.io/*` vocabulary stays closed at nine keys, and `yaml` is
the sole declarer of edges the observing plugins cannot see.**

The mechanism would work — dangling endpoints are legal (ADR-0012), stub nodes
cover them (ADR-0048), and edges are set-union by full tuple (ADR-0045), so the
same edge declared in an annotation and in YAML costs nothing. It is declined on
grounds of having one place to look rather than two.

ADR-0033's finding was structural, not incidental: guessing an edge is
*unavailable* to `kubernetes` because ADR-0009 dropped `Edge.confidence`, so
there is nowhere to record that an edge is a guess. An annotation is not a
guess — but adding it makes the sentence "`kubernetes` emits no edges" false,
and that sentence is currently doing work: it is why the fixture's edge count
splits cleanly into 7 from `yaml` and 2 from `connect`, and why the Kubernetes
plugin has no edge code path to test, scope or get wrong.

## Consequences

- Adding a consumer to the graph is **two** edits in two repositories: the
  annotation for health routing, the YAML stanza for the edge. That is the cost,
  it is paid by the person adding a service, and it is visible rather than
  silent — the node appears on the canvas with lag and no incoming edge.
- The nine keys stay: `node` · `type` · `owner` · `ignore` · `consumer-groups` ·
  `repository` · `runbook` · `docs` · `grafana`.
- Both closed vocabularies in the MVP now belong to plugins — nine annotations
  for `kubernetes`, six verbs for `yaml` (ADR-0062) — while `type` and
  `relation` stay open strings in the core. If either needs to grow it is a code
  change in one plugin, and neither growth reaches the model.
- If this is revisited, the annotation should be the verb shape and nothing
  else: `topology.io/consumes: <node key>`, subject implied by the object it
  sits on, no direction written.
