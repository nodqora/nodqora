# ADR-0099: The reference pipeline becomes golden documents; its prose stays authoritative

- **Status**: Accepted
- **Date**: 2026-09-04
- **Ticket**: [Implementation slice ordering and handoff](https://github.com/fredskor/nodqora/issues/18)

## Context

`docs/reference-pipeline.md` has been the map's test bench for twenty tickets —
every ADR is argued against it, and ADR-0091 records that it has been *amended
three times* by decisions it was supposed to be testing. It is prose with
tables. To constrain code it has to split into inputs and assertions, and the
split is not uniform.

ADR-0028 already made one cut: the fixture's **raw signal** column is *"a gist,
not an expected-output assertion"*. Which of the remaining tables are executable
was left open.

## Decision

### 1. The split

| section | becomes |
|---|---|
| §5 Kubernetes objects, §6 Kafka objects, §7 Connect objects | **inputs** |
| §2 node inventory, §3 edges, §9 ownership and links | **assertions** |
| §8 *normalized* column | **assertion** |
| §8 *raw signal* column, §10 what this fixture stresses | **prose** (ADR-0028) |
| §4 environment counts | **neither** — derived from §2 and §3 |

### 2. Inputs are real artifacts, not restatements

**The YAML half is not a test double.** ADR-0061 made the topology a directory
per environment of the product's own format, so `fixtures/reference-pipeline/
production/*.yaml` and `.../staging/*.yaml` are genuine inputs that double as
the demo topology. There is no second copy to drift.

Kubernetes, Kafka and Connect are recorded, and they record at **each plugin's
own outbound-client interface** — one uniform seam for all three. No WireMock,
no testcontainers, no envtest in the MVP.

That seam is not a testing convenience; it is the shape ADR-0012 already
requires. A plugin returns a full stateless snapshot per poll, so its entire
dependency on the outside world is *one call returning one set of objects*.
Recording it is recording the thing the ADR says it is.

### 3. Assertions are golden documents

Five checked-in files:

| document | environment | scenario |
|---|---|---|
| `graph-production.json` | production | — (ADR-0003: the slow half has no scenario) |
| `graph-staging.json` | staging | — |
| `state-production-baseline.json` | production | baseline |
| `state-staging-baseline.json` | staging | baseline |
| `state-production-incident.json` | production | incident |

Golden documents beat hand-written expectations here for one specific reason
rather than as a style preference. **ADR-0050 records that losing canonical
collection ordering silently degenerates `updatedAt` into a poll clock** — a
named silent failure with no visible symptom. A golden document is the only
cheap thing that catches an ordering regression; an `assertThat(node.links)
.contains(...)` would never notice, because `contains` is order-blind by
construction.

### 4. The layout gets its own golden

ADR-0016 claims plain longest-path layering reproduces the fixture's documented
shape. That is checked as a **golden layer assignment** — node key to column
index — not as pixel positions, which would make the test a snapshot of the
renderer instead of a test of the claim.

### 5. The prose stays authoritative

**Tests do not parse `docs/reference-pipeline.md`, and it is not generated from
the fixtures.**

Generating it would destroy §10, which is the most valuable part of the file and
is pure argument — thirteen rows of *"removing one removes a design constraint"*
that no fixture format can hold. Parsing it would make the markdown a schema,
and the first prose edit would break the build for a reason nobody could read.

The drift this accepts is real but narrow, and it is the **counts** that rot: §4
says 10/9 and 7/6, and a node added to the fixtures without a doc edit makes
that false silently. So one cheap test asserts §4's four numbers against the
golden documents, and nothing else about the file is enforced.

## Consequences

- **The document and the fixtures may disagree about everything except the
  counts.** That is the accepted cost, taken because the file's value is its
  argument and its argument is not checkable.
- **§9's `docs` column stays prose.** It is a tick mark, not a URL; ADR-0032
  composes links from per-environment templates, so what the golden documents
  assert is the composed `links[]`, and a tick asserts nothing.
- **The five golden documents are the slice done-when.** ADR-0100 states each
  slice's completion in terms of which of them go green, which is what makes
  "done" a fact rather than a judgement.
- **A golden document changes when a plugin is added, and that is the point.**
  Slice 2 rewrites `graph-production.json` to add backings and composed links;
  the diff *is* the review surface for whether the merge did what ADR-0044 says.
- **Recording at the client interface means the MVP never proves it can talk to
  a real cluster.** No test in this plan connects to Kubernetes, Kafka or
  Connect. That is deliberate — first contact with real infrastructure is a
  known, bounded, manual step — but it is a gap and is named here rather than
  discovered later.
- **`docs/reference-pipeline.md` §8's thresholds move into fixture config.**
  ADR-0025 puts lag thresholds in the `kafka` plugin's per-environment file, so
  the fixture's numbers are set there and the golden state documents assert the
  resulting `health`, never the threshold.
