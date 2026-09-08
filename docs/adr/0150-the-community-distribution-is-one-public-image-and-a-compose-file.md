# ADR-0150: The Community distribution is one public image and a compose file

- **Status**: Accepted
- **Date**: 2026-09-08
- **Ticket**: [What the distribution is](https://github.com/fredskor/nodqora/issues/61)

## Context

[ADR-0141](0141-publication-is-private-and-the-sdk-trigger-stays-unfired.md)
published six library artifacts to a **private** registry whose audience is the
Enterprise build. [ADR-0114](0114-the-enterprise-edition-is-a-second-assembly.md)
excluded `nodqora-app` from those six, so nothing decided about publication so
far reaches the thing a stranger would install — and
[ADR-0128](0128-community-capabilities-move-one-way-only.md) promises Community
stays Apache-2.0 **and self-hostable**, which is a promise about being able to
run it, not only to read it.

Today nothing satisfies that promise. The facts, verified in the tree:

- **There is no Dockerfile for Nodqora.** The only one belongs to
  `demo/aggregator`.
- **PostgreSQL is required and deliberately non-portable** — `jsonb`, generated
  columns, partial unique indexes over `lower(btrim(...))`. Flyway is enabled, so
  the schema migrates itself, but the server has to exist first.
- **The topology paths are repo-relative** (`fixtures/reference-pipeline/...`),
  and both `bootRun` and the test task pin `workingDir` to the repo root to make
  that work. A boot jar run anywhere else finds no topology.
- **The frontend is outside Gradle entirely** — a Vite application whose
  `package.json` is `private: true`, consistent with
  [ADR-0117](0117-enterprise-builds-its-own-frontend.md) deferring the npm
  package.
- **The Kubernetes plugin already supports both placements.**
  [ADR-0035](0035-kubernetes-plugin-configuration-and-cadence.md) makes
  `kubeconfig` optional and absent mean in-cluster, so Nodqora can observe a
  cluster from inside it or from outside it with a mounted kubeconfig. Connect
  and Kafka are reached by host and port and force nothing.

So the question is not *can this be packaged*. It is what the unit is, where it
is hosted, and how many of them there are.

## Decision

**One published artifact and one release asset.**

- **`ghcr.io/fredskor/nodqora:<version>`** — an OCI image, **public**, built for
  `linux/amd64` and `linux/arm64`.
- **`compose.yaml`** — attached to the GitHub Release for the tag, pinning that
  exact image tag and running `postgres:16-alpine` beside it.

The sentence the decision had to pass:

```
curl -LO https://github.com/fredskor/nodqora/releases/download/v0.1.0/compose.yaml
docker compose up
open http://localhost:8080
```

**The registry is GHCR, and visibility is the whole distinction from ADR-0141.**
Same host, opposite audience: the six libraries are resolvable by one consumer
who is the publisher, and this image must be pullable by a stranger who has no
account. Stating both on one registry keeps that contrast legible rather than
hiding it behind two vendors.

**The image is built by a self-contained multi-stage Dockerfile** — a node stage
that builds the frontend, a Gradle stage that builds the boot jar, and an
`eclipse-temurin:21-jre` runtime stage — published with
`docker buildx build --platform linux/amd64,linux/arm64 --push`. A clean clone
and Docker is the entire prerequisite: no JDK, no Node, no warm cache on the
release machine, and `amd64` publishes correctly from an Apple-silicon laptop,
which is the actual hardware this is cut on.

**Compose bundles PostgreSQL 16 with a named volume.** The version is the one
the integration tests already run (`NodqoraIntegrationTest` pins
`postgres:16-alpine`), so the shipped path and the tested path are the same
database. The volume is not incidental:
[ADR-0087](0087-the-canvas-has-three-empty-states.md) and
[ADR-0088](0088-unreported-banners-over-a-non-empty-graph.md) make the canvas
reason about what has been seen before, and a store that empties on
`docker compose down` would make the second `up` lie about history. The
`NODQORA_DB_URL` / `_USER` / `_PASSWORD` variables — which
`application.yaml` already reads — are the documented way to point at an existing
PostgreSQL instead.

**Compose is the one supported install.**
[ADR-0147](0147-only-the-latest-release-is-supported.md) makes the latest release
the entire support surface, so every install path shipped at the first tag is one
that is owed support before a single stranger has installed once.

**Deferred, with a trigger:** a Helm chart or Kubernetes manifests, published
when **the first install that cannot mount a kubeconfig** appears — an operator
who wants Nodqora inside the cluster it observes, using the service account
rather than a credential file. This is the house pattern of
[ADR-0010](0010-plugin-entity-two-capabilities.md)'s SDK trigger and ADR-0117's
npm trigger. Deferral forecloses nothing, because the image is the unit every
future path shares.

Rejected, and why:

- **A Spring Boot fat jar as a release asset.** It reads as the smallest possible
  change and it is the weakest answer, because "download this jar" ends at "now
  install and migrate PostgreSQL 16, and find a JDK 21" — which is the
  `git clone` path the destination exists to remove, wearing a different hat.
  Compose answers the database in the same breath as the application.
- **Paketo buildpacks (`bootBuildImage`).** No Dockerfile to write, layering and
  an SBOM for free. Refused on two counts: the builder images make cross-arch
  publishing from `arm64` genuinely awkward, and
  [ADR-0146](0146-the-jar-carries-licence-and-notice-and-no-dependency-inventory.md)'s
  attribution obligation is far easier to discharge against a base image and a
  dependency set that were named than against an OS layer assembled by a
  buildpack.
- **Jib.** Native multi-arch, daemon-free, reproducible — and it has no answer for
  the npm stage, so adopting it means wiring the frontend into Gradle first. That
  is [ticket #62](https://github.com/fredskor/nodqora/issues/62)'s question, and
  it should not be settled by a build plugin's constraints.
- **Docker Hub, or mirroring to both.** Discoverability is real and it is bought
  with a second account, a second credential in a release path that
  [ADR-0145](0145-the-release-gate-is-a-gradle-task.md) wants runnable from one
  laptop, and anonymous-pull rate limits that land on exactly the first-time
  installer this map exists to serve.
- **Shipping a Helm chart at `v0.1.0`.** A chart is a second version number, a
  `values.yaml` that becomes a public contract on the day it ships, and — under
  [ADR-0143](0143-published-versions-are-immutable-and-the-inner-loop-has-no-artifact.md)
  — a second immutable thing to get right the first time.
- **Ephemeral Postgres**, and **no bundled Postgres at all**. The first erases
  history on restart; the second puts the prerequisite back in front of the
  stranger, which is the fat-jar failure again.

## Consequences

- **ADR-0117's premise expires.** "Nothing serves it in production — Community's
  own packaging story is unwritten" is no longer true. The npm package stays
  deferred on its own trigger; the packaging half of that sentence is answered
  here.
- **ADR-0146's obligation now has exactly one address.** One artifact bundles, so
  one `THIRD-PARTY` is owed — covering the JRE base image, the Gradle runtime
  dependencies, and whatever the node stage contributes. What that file covers
  and how it is generated is
  [its own ticket](https://github.com/fredskor/nodqora/issues/67).
- **The frontend question narrows to a mechanism.** With one image, `dist/` has
  to reach Spring's static resources inside the Gradle stage. Ticket #62 is no
  longer *whether* there is a second image.
- **`nodqora-app` acquires a public identity ADR-0114 did not give it.** It was
  excluded from the six as an assembly rather than a library; it is now the
  Community product's name in a registry, which is what makes
  [ticket #64](https://github.com/fredskor/nodqora/issues/64)'s versioning
  question load-bearing rather than clerical.
- **The repo-relative topology path becomes a mount.** A container has no repo
  root, so whatever `nodqora.environments.*.plugins.yaml.dir` points at has to
  arrive from outside the image. That is
  [ticket #63](https://github.com/fredskor/nodqora/issues/63)'s problem and this
  decision hands it a concrete surface: a volume and a set of environment
  variables.
- **The release is still a human at a keyboard.** `docker buildx ... --push` is
  one command and needs no pipeline, which is what makes
  [whether a release needs CI](https://github.com/fredskor/nodqora/issues/68)
  answerable at last — and it puts a second publishing step beside ADR-0145's
  Gradle `release` task, which today knows only about Maven coordinates.
- **A public GHCR package is a visibility setting that defaults the wrong way.**
  GitHub creates packages private and inherits nothing from the repository. The
  first release will appear to work and be unpullable by anyone else; this is the
  one operational footgun in the decision and it belongs in the install docs'
  release checklist.
- **Revisit trigger.** The first install that cannot mount a kubeconfig, which
  fires the chart. Separately, a second supported install path of any kind should
  reopen the single-path decision rather than accreting quietly beside it.
