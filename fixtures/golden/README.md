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
| `state-production-baseline.json` | `/api/environments/production/state`, §8 baseline |
| `state-staging-baseline.json` | the same for `staging` — §8's "all seven HEALTHY or UNKNOWN" |
| `state-production-incident.json` | §8 incident: the enricher 3 desired / 0 ready |
| `layout-production.json` | ADR-0016's claim: longest-path layering reproduces §1's shape |
| `layout-staging.json` | the same, re-flowing to a straight line with no hand-placed positions |

ADR-0099's five assertion documents are now all present. The state goldens are **stated for the
observer set that exists**, not for §8's full column: `kubernetes` is the only plugin declaring
Health, so a value §8 derives from consumer lag or connector state is `UNKNOWN` here. That is not a
weakened assertion — it is the honest rendering of a plugin that has not been built, and it is the
same `UNKNOWN` the four permanently-unobserved nodes carry.

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
- **Slice 2 (`yaml` + `kubernetes`)** — the counts are unchanged, which was the first thing to
  read off the diff: `kubernetes` emits no edges at all (ADR-0033), its two workloads merge onto
  keys `yaml` already carried (ADR-0021), and the third is suppressed by exact name (ADR-0031), so
  nothing was added and nothing was split. What did change is inside the two service nodes —
  backings, composed links, an annotated `type`, an `ownerKey`, and `sources: [yaml, kubernetes]`.
  Four nodes still carry no `type`: the topics wait for `kafka` and the connectors for `connect`.
  `typeDescriptors` gains `service`, declared in YAML — `kubernetes` sets the `type` from an
  annotation and guesses no descriptor for it (ADR-0091), so ADR-0001's "registered by plugins and
  by the YAML topology alike" is what keeps the two services off the fallback descriptor.
- **Now (slice 3, health end to end)** — the two graph goldens are **unchanged**, and that is the
  first thing to read off the diff: health is runtime state, so a replica count moving cannot touch
  a Node (ADR-0003). What is new is the three state documents. Two of ten production nodes carry a
  health, a `rawSignal` and a `metrics` block — the two with a workload backing — and eight read
  `UNKNOWN` with `rawSignal: null`, `metrics: {}` and `observedAt: null`, which is not a placeholder
  but the outer join finding no row (ADR-0028). `payments-api` is HEALTHY at 3/3 and
  `payments-enricher` DEGRADED at 3/2, which is ADR-0025's choice of arithmetic over
  `status.conditions` visible as a single word: `Available=True` holds at 2 of 3, so the other
  reading would have rendered the fixture's one interesting workload HEALTHY.
- **Slice 4** — `kafka` and `connect` arrive together. `connect` closes the edge set with the two
  `SOURCES_FROM` edges (ADR-0041), taking production to 9 edges and staging to 6, at which point the
  graph goldens match §3 exactly and `layout-production.json` matches what the app actually renders;
  `kafka` adds the topics' `type`. The state diff is the review surface for ADR-0024's collapse:
  `payments-enricher` must stay DEGRADED while gaining a second observer and a second `rawSignal`
  segment, both topics and both connectors must leave `UNKNOWN`, and the incident's two connectors
  must go DISABLED rather than DEGRADED — which is the case that defeats plain worst-wins.

The layout goldens are already stated over §3's **complete** edge set, because ADR-0016's claim is
about the fixture's shape and not about how much of it one slice has built. The tests supply the
missing `SOURCES_FROM` edges explicitly and say so.
