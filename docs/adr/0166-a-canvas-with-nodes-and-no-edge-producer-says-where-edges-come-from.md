# ADR-0166: A canvas with nodes and no edge producer says where edges come from

- **Status**: Accepted
- **Date**: 2026-09-15
- **Ticket**: [A canvas with nodes and no edges does not say where edges come from](https://github.com/nodqora/nodqora/issues/113)
- **Amends**: [ADR-0087](0087-the-canvas-has-three-empty-states.md) — its three states stand unchanged; this adds a sentence for a canvas that is not empty.

## Context

[ADR-0033](0033-kubernetes-emits-no-edges.md) has the `kubernetes` plugin emit no
edges, so an environment declared with only that plugin renders every workload as
its own card on its own row. That is correct output, and a first-time user reads it
as broken. It happened on the install route against a k3s homelab: 16 workloads,
0 edges. The only hint in the app was the inspector's *"Nothing connects to this
node."*, which describes the node and does not point anywhere.

ADR-0087's three states all cover **no nodes**. Once discovery returns a node, the
canvas renders the graph and no state applies. The explanation lived only in the
docs, and [#114](https://github.com/nodqora/nodqora/pull/114) has since added
`docs/install.md` step 5, *Draw the edges*.

## Decision

**When the graph has nodes, has no edges, and no plugin in `plugins[]` can produce
an edge (`yaml`, `kafka`, `connect`), the canvas shows one sentence above the graph
and links to step 5:**

> The plugins reading `Homelab` report nodes, not what connects them. Edges come
> from a topology file. *Draw the edges*

The link is pinned to the tag the build was cut from, using ADR-0156's rule, which
is now shared in `frontend/src/docs/link.ts`.

## Consequences

- **The condition checks the roster, not only `edges.length`.** If an environment
  declares `yaml` and has no edges yet, the operator has already taken the step the
  sentence points at, so the sentence would give the wrong cause. `kafka` and
  `connect` observe edges, so declaring either one suppresses the sentence too.
- **It is not a fourth `CanvasEmptyState`.** The canvas is not empty, and
  `routing/resolve.ts` treats those states as no-node states. The sentence renders
  above the graph, not in place of it. An empty canvas never shows it, because
  ADR-0087's sentence already covers that case.
- **It is not an outcome banner.** Nothing failed. It uses the quiet ink of
  ADR-0087's headline, not the banner's bordered ground.
- **It does not promise a time**, per ADR-0087. The fix is a file the operator
  writes, and waiting does not fix it.
- **It does not name `kubernetes`.** The roster may hold any plugin that emits no
  edges, and the sentence is true for all of them.
- **ADR-0161 is checked, not just followed.** The document and the constant are in
  the same tree, and the link is pinned to that tree's tag. A test reads
  `## 5. Draw the edges` out of `docs/install.md`, so renaming the step fails the
  build and does not leave a dead anchor.
- **Cost:** the frontend now hard-codes which plugins produce edges. `PluginRef`
  carries capabilities and says nothing about edges. A new plugin that emits edges
  must be added to `EDGE_PRODUCERS`, or it will get the sentence while its edges
  are still loading. The sentence goes away as soon as one edge arrives.
