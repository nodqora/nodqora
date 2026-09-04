# Nodqora

A topology and health canvas for event-driven systems. The decisions are in
[`CONTEXT.md`](CONTEXT.md) and [`docs/adr/`](docs/adr/); the worked example everything is tested
against is [`docs/reference-pipeline.md`](docs/reference-pipeline.md).

**Status: slice 1 of five (ADR-0098).** The `yaml` plugin runs end to end — snapshot store, the
fold, the derived tables, the three GETs, the canvas and the drawer. Production renders at 10 nodes
and 7 edges, staging at 7 and 5, and every node is grey: `yaml` declares no Health capability, so
`UNKNOWN` is the honest answer rather than a placeholder.

## Layout

```text
nodqora-plugin-api/    immutable records only. No Spring, no persistence, no core dependency.
nodqora-core/       →  depends on plugin-api. Never on any plugin (ADR-0015, enforced by ArchUnit).
nodqora-plugin-yaml/ → depends on plugin-api.
nodqora-app/        →  the deployable: core plus the plugins, wired together.
frontend/              Vite + React + XYFlow.
fixtures/
  reference-pipeline/  the demo topology, in the product's own format. Not a test double.
  golden/              ADR-0099's assertions.
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
