# SPDX-License-Identifier: Apache-2.0
#
# ADR-0150: one public multi-arch image is the Community distribution, built by a self-contained
# multi-stage Dockerfile so that a clean clone and Docker are the entire prerequisite — no JDK, no
# Node, no warm cache on the release machine.
#
# ADR-0151 decides the frontend path through it: the node stage builds `frontend/dist`, and the
# Gradle stage copies that into `nodqora-app/src/main/resources/static/` before `bootJar`, so the
# boot jar serves its own UI from the same origin the API is on. Gradle never learns about npm, and
# `./gradlew build` on a laptop needs no Node.
#
# ADR-0154 makes this file the bundler, so it is also what owes attribution: the backend stage runs
# `:nodqora-app:thirdParty` and the runtime stage carries the result at /app/THIRD-PARTY.

# The runtime base, pinned by digest and named here alone. Three things read this one value, which
# is why it is an argument rather than three literals:
#   * the runtime `FROM` below — ADR-0154 needs the digest the header quotes to be the digest the
#     image was actually built on, which only holds if they cannot drift;
#   * `-PbaseImage` into `thirdParty`, so the header renders this exact reference;
#   * `org.opencontainers.image.base.name`, so the built image states it too.
# ADR-0155 makes the Dockerfile the single source for it: the release never names a base image.
#
# This is the digest of the multi-arch *index*, not of one architecture's manifest — pinning a
# single-arch digest builds fine on the laptop and then fails the other half of `--platform`.
# It resolves linux/amd64 and linux/arm64/v8 (and four more); verify with
#   docker buildx imagetools inspect eclipse-temurin:21-jre@<digest>
# before moving it.
ARG RUNTIME_BASE=eclipse-temurin:21-jre@sha256:7a65df4b22d2de92d4e04056e884f3b9122d70b21e2847fd66084278bd0ce037

# ADR-0153: one version spans all seven artifacts and the image tag *is* that number, so the image
# does not carry a version of its own — it is told one. The root `build.gradle.kts` stays its single
# home and `./gradlew release` passes it through as `--build-arg`. Unset in a working tree, which is
# the honest answer for a build that is not a release.
ARG NODQORA_VERSION

# --- the frontend ---------------------------------------------------------------------------
# Pinned to the major that `@types/node` in frontend/package.json is written against.
#
# `--platform=$BUILDPLATFORM` (ADR-0155): a JavaScript bundle is architecture-independent, so under
# `buildx --platform linux/amd64,linux/arm64` this stage must run once, natively, rather than twice
# with one run under QEMU. Without it multi-arch costs a multiple of single-arch, which is the fact
# ADR-0155 rests on when it rules out a hosted runner.
FROM --platform=$BUILDPLATFORM node:22-alpine AS frontend

WORKDIR /frontend

# Dependencies first, so a source-only change does not re-resolve the tree. `npm ci` rather than
# `npm install`: the lockfile is the input, and a build that can silently float a version is not a
# build ADR-0143's immutable-version promise can stand on.
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci

COPY frontend/ ./

# ADR-0156: the running version, baked into the bundle so the first-run screen's documentation link
# points at the tag this build was cut from rather than at `main`. ADR-0153 makes a minor version
# able to want a config edit, so an unpinned link teaches an old install a config grammar it does
# not have — and the reader cannot detect it, because they are on that screen precisely for not yet
# knowing how the thing is configured. Unset here, the link falls back to `main`, which is correct
# for a working tree; the release passes it.
ARG NODQORA_VERSION
ENV NODQORA_VERSION=${NODQORA_VERSION}

# `npm run build` is `tsc --noEmit && vite build`, so a type error fails the image rather than
# shipping a bundle nobody typechecked. It also emits `build/third-party-npm.json` through
# rollup-plugin-license (ADR-0154) — the npm half of the notices, generated from inside the bundler
# so the set it attributes is the set that actually ships.
RUN npm run build

# --- the boot jar ---------------------------------------------------------------------------
# `--platform=$BUILDPLATFORM` for the same reason as above, and more so: a boot jar is
# architecture-independent, and this stage is the expensive one.
FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk AS backend

WORKDIR /src

# The wrapper and the build scripts first: the dependency resolution they drive is the slow half,
# and it is invalidated by far fewer edits than the source is.
COPY gradlew ./
COPY gradle/ gradle/
COPY settings.gradle.kts build.gradle.kts ./
COPY nodqora-plugin-api/build.gradle.kts nodqora-plugin-api/
COPY nodqora-core/build.gradle.kts nodqora-core/
COPY nodqora-plugin-yaml/build.gradle.kts nodqora-plugin-yaml/
COPY nodqora-plugin-kubernetes/build.gradle.kts nodqora-plugin-kubernetes/
COPY nodqora-plugin-connect/build.gradle.kts nodqora-plugin-connect/
COPY nodqora-plugin-kafka/build.gradle.kts nodqora-plugin-kafka/
COPY nodqora-app/build.gradle.kts nodqora-app/
RUN ./gradlew --no-daemon :nodqora-app:dependencies --configuration runtimeClasspath > /dev/null

COPY . .

# ADR-0151's seam, and the whole of it: a directory copy, in one direction, at one moment. The
# static directory is in .dockerignore precisely so a developer's stale local build cannot arrive
# via `COPY . .` above and win over what the node stage just produced.
COPY --from=frontend /frontend/dist/ nodqora-app/src/main/resources/static/

# The npm half of the notices, by the same rule and for a sharper reason: `frontend/build` is in
# .dockerignore, so `COPY . .` cannot supply this file even on a machine that has one. It arrives
# from the stage that generated it or not at all — and `thirdParty` fails rather than defaulting if
# it is missing, so a Java-only THIRD-PARTY cannot ship.
COPY --from=frontend /frontend/build/third-party-npm.json frontend/build/third-party-npm.json

ARG RUNTIME_BASE

# Tests are not run here. They need Docker themselves (Testcontainers starts postgres:16-alpine),
# which is not available inside the build, and ADR-0145 puts the release gate in a Gradle task run
# before the tag rather than in the image build.
#
# One invocation, two products: the jar the runtime serves, and the notices file it carries.
# ADR-0154 makes both `-P` properties required with no defaults — the npm fragment because half a
# notices file is worse than a missing one, and the base image because this file is the only place
# that knows the digest.
RUN ./gradlew --no-daemon -x test \
      :nodqora-app:bootJar \
      :nodqora-app:thirdParty \
      -PnpmFragment=/src/frontend/build/third-party-npm.json \
      -PbaseImage="${RUNTIME_BASE}"

# --- the runtime ----------------------------------------------------------------------------
# No `--platform`: this stage is the one that is genuinely per-architecture, and under
# `buildx --platform` it is built once per target from the pinned index above. The work here is a
# `COPY` and a `useradd`, which is what makes multi-arch cost roughly what single-arch costs.
FROM ${RUNTIME_BASE} AS runtime

# ADR-0154: **the runtime stage installs no packages.** The base image's own attribution — the
# Ubuntu copyright files under /usr/share/doc/*/copyright and the Temurin notices under
# /opt/java/openjdk — travels with it in place, and THIRD-PARTY says so rather than reproducing it.
# That claim rests on redistributing the base unmodified, so anything installed here (a `curl` for
# a healthcheck is the obvious temptation) arrives unattributed and silently makes the file wrong.
# If you need one, it belongs in an earlier stage or in the notices, not here.
#
# ADR-0128 promises a self-hostable Community edition, and a self-hosted thing that insists on root
# is a worse promise than it sounds. `useradd` adds no package and is within the rule.
RUN useradd --system --create-home --uid 10001 nodqora
USER nodqora
WORKDIR /app

COPY --from=backend /src/nodqora-app/build/libs/nodqora-app-*.jar /app/nodqora.jar

# ADR-0154 puts the notices at this path and nowhere else: not a release asset, not served over
# HTTP, and not in the jar. `docker run --rm --entrypoint cat <image> /app/THIRD-PARTY` is how you
# read it, and the header explains how to read the base image's own.
COPY --from=backend /src/nodqora-app/build/third-party/THIRD-PARTY /app/THIRD-PARTY

ARG NODQORA_VERSION
ARG RUNTIME_BASE
# RFC 3339, passed by the release. See the `image.created` note below for why it has no default.
ARG NODQORA_CREATED

# `image.version` is the deployable's half of ADR-0153's lockstep, readable from a running container
# rather than only from the tag that fetched it — a tag can be pulled by digest, retagged locally,
# or written down wrong, and the support policy in ADR-0147 covers exactly one version. `base.name`
# restates the digest above so the image itself corroborates what THIRD-PARTY's header claims.
#
# The first four are not additions so much as corrections: labels are inherited, and the Temurin
# base carries Ubuntu's — without this block the image announces itself as `ubuntu` version `26.04`.
# `image.created` is the fourth of them and the reason it is set from an empty-defaulting argument:
# left alone it reports the day Canonical built the base, which is a false claim about this image,
# while inventing a timestamp here would put a different lie in a working tree. Empty claims
# nothing, the release fills it in, and the image config's own `Created` carries the real build time
# either way.
LABEL org.opencontainers.image.title="Nodqora" \
      org.opencontainers.image.description="Topology and health canvas for event-driven systems" \
      org.opencontainers.image.version="${NODQORA_VERSION}" \
      org.opencontainers.image.created="${NODQORA_CREATED}" \
      org.opencontainers.image.source="https://github.com/fredskor/nodqora" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.base.name="${RUNTIME_BASE}"

# ADR-0150's compose file publishes this port and the install instructions open it.
EXPOSE 8080

# ADR-0152: the image ships no environments, and an operator's own arrive as two mounts.
#
# `/app/config/application.yaml` is Spring Boot's own default search location — `optional:file:./
# config/` relative to this WORKDIR — which is why no flag, no `SPRING_CONFIG_ADDITIONAL_LOCATION`
# and no line of Dockerfile names it. It *merges* with the `application.yaml` inside the jar, so the
# file an operator writes carries `nodqora.environments` and nothing else: the two plugin orders,
# the refresh cadences and the Jackson, problemdetails and cache settings all inherit.
#
# `/etc/nodqora/topology/<environment>` is ADR-0061's own path, mounted read-only and optional —
# `yaml` is a plugin like any other, and an operator observing only Kubernetes and Kafka never
# declares it. It is deliberately *not* under `/app/config`: Spring searches `config/` and one level
# of `config/*/` for `application.yaml`, so a topology file with that name would be read as
# configuration by Spring and as topology by the plugin, and ADR-0064 makes an unknown top-level key
# the whole environment's failure.
#
# Environment variables keep the two jobs they are good at: the `NODQORA_DB_URL` / `_USER` /
# `_PASSWORD` triple, and ADR-0014's `${env:...}` and `${file:...}` secret references, which is how
# credentials stay out of the mounted file. They are not a way to declare an environment —
# `KafkaConfig.properties` holds dotted Kafka client keys like `security.protocol`, and Boot's
# environment source maps `_` to `.`, so that key is not expressible as a variable at all.
#
# Neither directory is created here. A bind mount makes its own, and an install with neither starts
# and serves an empty roster rather than crash-looping (UnconfiguredInstallTest).

ENTRYPOINT ["java", "-jar", "/app/nodqora.jar"]
