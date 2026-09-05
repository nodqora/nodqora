# Golden documents

ADR-0099 splits `docs/reference-pipeline.md` into inputs and assertions. The inputs live in
`fixtures/reference-pipeline/`: the YAML halves are the product's own ADR-0061 format, so they
double as the demo topology and there is no second copy to drift, while `kubernetes/`, `kafka/` and
`connect/` hold §5's, §6's and §7's objects, each recorded at its own plugin's outbound-client
interface. The assertions live here.

**Why golden documents rather than hand-written expectations.** ADR-0050 records that losing
canonical collection ordering silently degenerates `updatedAt` into a poll clock — a named failure
with no visible symptom. A golden document is the only cheap thing that catches an ordering
regression; an `assertThat(node.links).contains(...)` would never notice, because `contains` is
order-blind by construction.

| file | asserts |
|---|---|
| `graph-production.json` | `/api/environments/production/graph` — §2's inventory and §3's nine edges |
| `graph-staging.json` | the same for `staging` — §2's inventory minus the Iceberg branch |
| `state-production-baseline.json` | `/api/environments/production/state`, §8 baseline |
| `state-staging-baseline.json` | the same for `staging` — §8's "all seven HEALTHY or UNKNOWN" |
| `state-production-incident.json` | §8 incident: the enricher 3 desired / 0 ready |
| `layout-production.json` | ADR-0016's claim: longest-path layering reproduces §1's shape |
| `layout-staging.json` | the same, re-flowing to a straight line with no hand-placed positions |

**All five assertion documents match the fixture in full.** Every earlier slice qualified this
paragraph — the goldens were stated for the plugin set that existed, and a value §8 derives from
consumer lag or connector state read `UNKNOWN` because the plugin that reads it had not been built.
There is nothing left to qualify. §2's inventory, §3's nine edges, §8's normalized column in both
scenarios and §9's owners and composed links are all asserted here against what the application
actually serves, and the only remaining `UNKNOWN`s are the four declared nodes that nothing in the
MVP will ever observe.

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
- **Slice 3 (health end to end)** — the two graph goldens were **unchanged**, and that was the
  first thing to read off the diff: health is runtime state, so a replica count moving cannot touch
  a Node (ADR-0003). What is new is the three state documents. Two of ten production nodes carry a
  health, a `rawSignal` and a `metrics` block — the two with a workload backing — and eight read
  `UNKNOWN` with `rawSignal: null`, `metrics: {}` and `observedAt: null`, which is not a placeholder
  but the outer join finding no row (ADR-0028). `payments-api` is HEALTHY at 3/3 and
  `payments-enricher` DEGRADED at 3/2, which is ADR-0025's choice of arithmetic over
  `status.conditions` visible as a single word: `Available=True` holds at 2 of 3, so the other
  reading would have rendered the fixture's one interesting workload HEALTHY.
- **Slice 4 (`kafka` + `connect`)** — the slice made four predictions and the diff kept all
  four. `connect` closed the edge set with the two `SOURCES_FROM` edges (ADR-0041), taking
  production to 9 and staging to 6, so the graph goldens now match §3 outright and
  `ReferencePipelineCountsTest` compares §4's numbers with no allowance left in it. The node count
  did **not** move: `kafka` and `connect` mint five keys between them and every one merges onto a
  key `yaml` already carried, which is ADR-0021 and ADR-0044 doing at four writers what they did at
  two. `typeDescriptors` gains `kafka-topic` and `connect-connector`, both registered by the plugin
  that owns the type rather than guessed — so no node in either graph is typeless any more, and the
  only nulls left are `displayName`s no writer chose to fill.

  The state diff is the review surface for ADR-0024's collapse, and it is worth reading closely.
  `payments-enricher` stays DEGRADED while gaining a second observer and a second `rawSignal`
  segment — the collapse discards votes, never evidence. Both topics and both connectors leave
  `UNKNOWN`. `payments.events.enriched.v1` reads HEALTHY at max lag 8,400 across two groups while
  `raw.v1` reads DEGRADED at 40,000, over one threshold rather than a pair (ADR-0025). And under the
  incident both connectors go **DISABLED rather than DEGRADED**, each collapsing three observers
  that disagree — which is the case that defeats plain worst-wins and the reason ADR-0024 is three
  steps instead of a `max()`.

- **Slice 5 (the honesty layer)** — **every one of these documents is byte-identical to slice 4's,
  and that is the result.** This slice is behaviour under conditions the fixture's happy path never
  enters, so ADR-0099's five assertions are exactly the wrong instrument for it and were left
  untouched on purpose: a diff here would have meant the honesty layer had changed what the
  application says on an ordinary day, which is the one thing ADR-0081 legislated against —
  *"nothing else renders while every outcome is `COMPLETE`."* What slice 5 added is asserted by
  named scenarios instead (`BlindObserverTest`, `ColdStoreTest`), because each of them is a
  statement about a state the fixture cannot be in and be itself.

  The goldens still carry the two fields the whole layer reads, and have since slice 3: every
  node's `sources[]` entries carry a `confirmedAt`, and each document carries the `plugins[]`
  roster that explains it — four `DISCOVERY` entries on `/graph`, three `HEALTH` on `/state`,
  because `yaml` declares no Health capability. Here they all read `COMPLETE`, which is precisely
  why they could not test anything: ADR-0084's retention is a comparison between two timestamps
  that a `COMPLETE` poll makes equal, and ADR-0085's `null` never appears in a document produced by
  polling everything first.

The layout goldens were stated over §3's **complete** edge set from slice 1, because ADR-0016's
claim is about the fixture's shape and not about how much of it one slice had built; until now the
canvas tests supplied the missing `SOURCES_FROM` edges by hand. They no longer do. The layering
those goldens have asserted since slice 1 is what the application produces from its own graph
document, with nothing supplied to it — which is the bet ADR-0098 took by putting the claim first,
settled.
