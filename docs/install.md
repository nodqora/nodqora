# Installing Nodqora

Community Nodqora ships as **one image and one `compose.yaml`** (ADR-0150). You need Docker with
Compose v2 and nothing else — no JDK, no clone, no build. If you have arrived here wanting to work
*on* Nodqora rather than run it, the source path is in [the README](../README.md#from-source).

The whole install is four steps, and the third one is the only one that takes thought.

## 1. Get the compose file

```bash
curl -LO https://github.com/nodqora/nodqora/releases/latest/download/compose.yaml
```

**Take it from the Release, never from this repository.** The `compose.yaml` in the tree carries a
`@VERSION@` placeholder where the image tag belongs and does not run as it stands — deliberately, so
that an unpinned install cannot be copied out of `main` (ADR-0155). The release renders it and
uploads *that* file.

The URL says `latest` and the file you get is **pinned**. `releases/latest/download/` resolves to the
most recent release and hands you its asset, which names one exact version. That is the intended
shape: a moving URL, an immovable file.

## 2. Start it

```bash
mkdir -p config topology
docker compose up -d
```

Make the two directories **before** the first `up`. They are bind mounts, and Docker creates a
missing mount source itself — as root, which on Linux leaves you needing `sudo` to write the config
file into a directory you are about to be told to edit.

Two containers come up — Nodqora and `postgres:16-alpine`, the same version the integration tests run
against. Postgres is required and deliberately not portable: the schema uses `jsonb`, generated
columns and partial unique indexes over `lower(btrim(...))`. Flyway migrates it on startup, so there
is no schema step for you to run.

The database lives in a named volume, which is not incidental — the canvas reasons about what it has
seen before (ADR-0087, ADR-0088), so a store that emptied on `docker compose down` would make the
second `up` lie about history.

Open **http://localhost:8080**. You should see:

> **No environments are configured.**

That is a **successful install**, not a failure. The image ships zero environments on purpose:
Spring's property merge lets a mounted file override a baked-in environment but never *delete* one,
so anything shipped in the image would be permanently stuck in your roster (ADR-0152). An
unconfigured Nodqora starts, migrates its schema, serves its frontend and reports an empty roster
honestly.

If you see something else, jump to [Troubleshooting](#troubleshooting).

## 3. Declare an environment

Write an `application.yaml` into the `config` directory beside your `compose.yaml`:

```yaml
# ./config/application.yaml
nodqora:
  environments:
    homelab:
      display-name: Homelab
      plugins:
        kubernetes:
          namespaces: [n8n, monitoring, actual]
```

This file **merges with the one inside the image** rather than replacing it, so it carries
`nodqora.environments` and nothing else — the plugin orders, the refresh cadences and the cache and
error settings all inherit. `/app/config` is Spring Boot's own default search location, which is why
no flag names it and why `compose.yaml` mounts `./config` there.

If you declare the `yaml` plugin, its topology is a second mount: one directory per environment
under `./topology/<environment>/`, read-only, matching ADR-0061's path. An operator observing only
Kubernetes and Kafka never creates it.

**[Running against your own cluster](running-against-your-own-cluster.md) is the full grammar** —
which plugin knows what, how a declared node joins an observed workload, and the annotations that let
your manifests carry the same facts. For a complete worked example, the repository's
`fixtures/reference-pipeline/application-demo.yaml` is a four-plugin configuration that the test
suite binds on every build, so it cannot rot without CI going red.

## 4. Restart

```bash
docker compose restart nodqora
```

Configuration is read at startup. Within one discovery cadence — five minutes by default — the canvas
fills in.

---

## Pointing at a PostgreSQL you already run

Delete the `postgres` service and its `depends_on` block, and set the three variables the compose
file already names:

```yaml
environment:
  NODQORA_DB_URL: jdbc:postgresql://db.internal:5432/nodqora
  NODQORA_DB_USER: nodqora
  NODQORA_DB_PASSWORD: ${NODQORA_DB_PASSWORD}
```

That triple is the whole database surface. Environment variables are **not** a way to declare an
environment: `KafkaConfig` holds dotted client keys like `security.protocol`, and Boot's environment
source maps `_` to `.`, so those keys are not expressible as variables at all. Credentials inside
your `application.yaml` stay out of the file as references — `${env:...}` and `${file:...}`, resolved
at use time and never persisted (ADR-0014).

## Upgrading

**Back up your database before you upgrade.** This is not general caution. Flyway is forward-only and
[ADR-0139](adr/0139-support-covers-the-latest-release-and-the-repo-keeps-one-line-of-development.md) leaves a regression no rollback path,
so `0.2.0` → `0.1.0` against one database is not a supported operation and will not become one. The
backup *is* your escape hatch.

```bash
pg_dump ... > nodqora-backup.sql          # or `docker compose exec postgres pg_dump ...`
curl -LO https://github.com/nodqora/nodqora/releases/latest/download/compose.yaml
docker compose up -d
```

What the number promises (ADR-0153):

| | |
|---|---|
| **patch** (`0.1.0` → `0.1.1`) | drop-in. Same config, no migration. |
| **minor** (`0.1.0` → `0.2.0`) | your `./config/application.yaml` **may need an edit**, and a schema migration **may run**. |

Below `1.0` the config grammar is still settling; `1.0` is the point at which it stops moving.

**`latest` is not for installs.** The image tag exists and is honest — the latest release is the
entire support surface (ADR-0147) — so that `docker run ghcr.io/nodqora/nodqora` works for someone
thirty seconds into meeting this project. But every `compose.yaml` asset pins an exact version, which
is what stops a stray `docker compose pull` walking you across a one-way migration you did not
choose.

A bad release is **superseded, never deleted**. `0.1.1` supersedes `0.1.0` and `latest` moves; the
broken version stays pullable, so pinned compose files keep resolving.

### Security updates arrive as releases

The image's base is **pinned by digest**, so nothing picks up a patched OS userland until the pin is
bumped and a new version is cut (ADR-0154). A base-image CVE therefore reaches you as a *release* to
upgrade to, not as a rebuild of a tag you already have. The upside is the one that made the pin worth
it: rebuilding a given version produces that version, and the notices file describes what is actually
in the image rather than what was in it once.

## What the image bundles

Everything the build adds is attributed in a notices file inside the image:

```bash
docker run --rm --entrypoint cat ghcr.io/nodqora/nodqora:<version> /app/THIRD-PARTY
```

It lives at that path and nowhere else — not a release asset, not served over HTTP, not in the jar.
It covers what the build adds; the `eclipse-temurin` base attributes itself in place, and the file's
header says where to read that.

## Troubleshooting

**The page says "No environments are configured" after I wrote my config.**
Configuration is read at startup — `docker compose restart nodqora`. If it persists, your file did
not land where the container reads: `docker compose exec nodqora ls /app/config` should show
`application.yaml`. A mount typo is silent by design, because an empty roster is a better first five
minutes than a crash-loop diagnosable only by `docker logs`.

**The page never loads at all.**
That is a different state and Nodqora will say so rather than claim your config is empty — an
unreachable server and an unconfigured one are never reported as the same thing (ADR-0156). Check
`docker compose ps` and `docker compose logs nodqora`.

**`docker compose up` fails on the image tag.**
You are running the repository's `compose.yaml` rather than the release asset — it names `@VERSION@`
on purpose. See [step 1](#1-get-the-compose-file).

**Nodqora crashed on the first `up` and worked on the retry.**
It should not: the compose file health-checks Postgres before starting Nodqora, because `depends_on`
alone waits for the container rather than for the server. If you edited that block out, put it back.

**My environment renders, but the nodes and my workloads are separate cards.**
That is the join, and it is a topology question rather than an install one —
see [Running against your own cluster](running-against-your-own-cluster.md#the-join-which-is-the-part-that-actually-matters).

## Getting help

**Open an issue.** That is the whole channel: support for the Apache-2.0 edition is public, so there
is no support email and no private channel, and every answer is where the next person will find it.
Support covers the latest release (ADR-0147), so say which version you are on — the running one is on
the first-run screen, and in the image's `org.opencontainers.image.version` label.

The commercial editions and the response commitment that comes with the paid one are in
[docs/editions.md](editions.md).
