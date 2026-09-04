# Golden documents

ADR-0099 splits `docs/reference-pipeline.md` into inputs and assertions. The inputs live in
`fixtures/reference-pipeline/` and are the product's own ADR-0061 format, so they double as the
demo topology and there is no second copy to drift. The assertions live here.

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

- **Now (slice 1, `yaml` only)** — production 10 nodes / 7 edges, staging 7 / 5. Six of the ten
  production nodes carry no `type` and no `displayName`, because those come from `kubernetes`,
  `kafka` and `connect`; four declared nodes carry both, because for those `yaml` is the only
  source. Every `sources[]` is `["yaml"]`.
- **Slice 2** — `kubernetes` adds backings, composed links, and `type` on the two services.
  `payments-enricher` becomes `sources: [yaml, kubernetes]`.
- **Slice 4** — `connect` closes the edge set with the two `SOURCES_FROM` edges (ADR-0041), taking
  production to 9 edges and staging to 6, at which point the graph goldens match §3 exactly and
  `layout-production.json` matches what the app actually renders.

The layout goldens are already stated over §3's **complete** edge set, because ADR-0016's claim is
about the fixture's shape and not about how much of it one slice has built. The tests supply the
missing `SOURCES_FROM` edges explicitly and say so.
