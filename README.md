# Nodqora

A topology and health canvas for event-driven systems. The decisions are in
[`CONTEXT.md`](CONTEXT.md) and [`docs/adr/`](docs/adr/); the worked example everything is tested
against is [`docs/reference-pipeline.md`](docs/reference-pipeline.md).

**Status: all five slices merged (ADR-0098).** Four discovery plugins — `yaml`, `kubernetes`,
`kafka`, `connect` — fold into one graph under a configured precedence, three of them also reporting
Health on their own cadence. Production renders at 10 nodes and 9 edges, staging at 7 and 6.

Health carries five values that are **not a ladder**: `UNKNOWN` is an abstention and `DISABLED` is
judgement suspended, so only `HEALTHY < DEGRADED < UNHEALTHY` is a severity order. Alongside it every
plugin reports an `outcome` — **`health` says what we found; `outcome` says how well we looked** — so
a failed poll leaves the last reading standing and says so, rather than turning a node green or red
on evidence nobody gathered.

To point it at infrastructure you actually run, see
[docs/running-against-your-own-cluster.md](docs/running-against-your-own-cluster.md).

## Layout

```text
nodqora-plugin-api/         immutable records only. No Spring, no persistence, no core dependency.
nodqora-core/            →  depends on plugin-api. Never on a plugin (ADR-0015, enforced by ArchUnit).
nodqora-plugin-yaml/     →  declared topology: the edges and ownership nothing else can observe.
nodqora-plugin-kubernetes/  workloads, Service and Ingress backings, readiness. Emits no edges.
nodqora-plugin-kafka/       topics and consumer-group lag against a threshold you choose.
nodqora-plugin-connect/     connectors and their tasks.
nodqora-app/             →  the deployable: core plus the plugins, wired together.
frontend/                   Vite + React + XYFlow.
fixtures/
  reference-pipeline/       the demo topology, in the product's own format. Not a test double.
  golden/                   ADR-0099's assertions: two graphs, three states, two layouts.
```

## Running it

The backend needs PostgreSQL. The schema uses `jsonb`, generated columns and partial unique indexes
over `lower(btrim(...))`, so it is not portable and is not meant to be.

```bash
docker run -d --name nodqora-db -p 5432:5432 \
  -e POSTGRES_DB=nodqora -e POSTGRES_USER=nodqora -e POSTGRES_PASSWORD=nodqora \
  postgres:16-alpine

./gradlew :nodqora-app:bootRun          # http://localhost:8080
cd frontend && npm install && npm run dev   # http://localhost:5173, proxying /api
```

`NODQORA_DB_URL`, `NODQORA_DB_USER` and `NODQORA_DB_PASSWORD` override the connection.

Out of the box this renders the reference pipeline and nothing else: the `kubernetes`, `kafka` and
`connect` blocks point at hosts that do not exist, so the canvas shows the declared topology in grey
with four banners saying so. That is the honesty layer working, not a broken install — every node is
`UNKNOWN` because nothing observed it.

The topology comes from `fixtures/reference-pipeline/{production,staging}/*.yaml`. Editing it is
invisible for up to one discovery cadence — five minutes — which is the accepted cost of there being
no refresh endpoint (ADR-0053). Shorten `nodqora.refresh.discovery` in
`nodqora-app/src/main/resources/application.yaml` while iterating; it is config, not code.

## The API

Three GETs, unversioned, unenveloped, read-only (ADR-0053, ADR-0060):

```text
GET /api/meta
GET /api/environments/{envKey}/graph
GET /api/environments/{envKey}/state
```

## Tests

```bash
./gradlew test                 # needs Docker: the integration tests run a real PostgreSQL
cd frontend && npm test        # layout, traversal and phrasing, against the golden documents
cd frontend && npm run typecheck
```

`ReferencePipelineCountsTest` is the only thing in the build that reads
`docs/reference-pipeline.md`, and it reads exactly §4's four numbers (ADR-0099).

**No test reaches a real cluster, broker or Connect worker.** Each plugin's outbound seam is an
interface and everything above it is exercised against a recording, which keeps the suite
deterministic and offline — at the price that "the client cannot start" is a class of defect only
running against real infrastructure will find. #32 was exactly that.

## Contributing and licence

Everything in this repository is [Apache-2.0](LICENSE) and always will be.
Contributions take a DCO sign-off (`git commit -s`) and there is no CLA — see
[CONTRIBUTING.md](CONTRIBUTING.md) for what that means and why
(ADR-0129, ADR-0130).

Nodqora is open core. A proprietary Enterprise edition lives in a separate
repository and consumes this one's published artifacts; no file here is ever
proprietary, and a capability that ships in Community never becomes paid
(ADR-0128).
