# Golden documents

ADR-0099 splits `docs/reference-pipeline.md` into inputs and assertions. The inputs live in
`fixtures/reference-pipeline/`: the YAML halves are the product's own ADR-0061 format, so they
double as the demo topology and there is no second copy to drift, and `kubernetes/` holds §5's
objects recorded at the plugin's own outbound-client interface. The assertions live here.

**Why golden documents rather than hand-written expectations.** ADR-0050 records that losing
canonical collection ordering silently degenerates `updatedAt` into a poll clock — a named failure
with no visible symptom. A golden document is the only cheap thing that catches an ordering
regression; an `assertThat(node.links).contains(...)` would never notice, because `contains` is
order-blind by construction.

| file | asserts |
|---|---|
| `graph-production.json` | `/api/environments/production/graph`, for the plugin set that exists |
| `graph-staging.json` | the same for `staging` — §2's inventory minus the Iceberg branch |
| `layout-production.json` | ADR-0016's claim: longest-path layering reproduces §1's shape |
| `layout-staging.json` | the same, re-flowing to a straight line with no hand-placed positions |

## Timestamps

Every ISO-8601 instant is normalized to `"<timestamp>"`. The values are wall-clock and would make
the documents unmatchable; their *positions* are still asserted, which is what matters — a field
appearing or disappearing fails, and so does a reordered collection.

## These change as slices land, and that is the point

A golden document changes when a plugin is added, and the diff **is** the review surface for
whether the merge did what ADR-0044 says.

- **Slice 1 (`yaml` only)** — production 10 nodes / 7 edges, staging 7 / 5. Six of the ten
  production nodes carried no `type` and no `displayName`; four declared nodes carried both,
  because for those `yaml` is the only source. Every `sources[]` was `["yaml"]`.
- **Now (slice 2, `yaml` + `kubernetes`)** — the counts are unchanged, which is the first thing to
  read off the diff: `kubernetes` emits no edges at all (ADR-0033), its two workloads merge onto
  keys `yaml` already carried (ADR-0021), and the third is suppressed by exact name (ADR-0031), so
  nothing was added and nothing was split. What did change is inside the two service nodes —
  backings, composed links, an annotated `type`, an `ownerKey`, and `sources: [yaml, kubernetes]`.
  Four nodes still carry no `type`: the topics wait for `kafka` and the connectors for `connect`.
- **Slice 3** — `kafka` adds the topics' `type` and the first `NodeState` rows.
- **Slice 4** — `connect` closes the edge set with the two `SOURCES_FROM` edges (ADR-0041), taking
  production to 9 edges and staging to 6, at which point the graph goldens match §3 exactly and
  `layout-production.json` matches what the app actually renders.

The layout goldens are already stated over §3's **complete** edge set, because ADR-0016's claim is
about the fixture's shape and not about how much of it one slice has built. The tests supply the
missing `SOURCES_FROM` edges explicitly and say so.
