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
# Three things are deliberately unfinished, each owned by its own ticket rather than settled here by
# implementation:
#   TODO(#63) the topology directory. `nodqora.environments.*.plugins.yaml.dir` is repo-relative
#             today, and a container has no repo root — a volume and a set of environment variables
#             have to replace it, along with whatever configuration ships by default.
#   TODO(#67) THIRD-PARTY. ADR-0146 parks the attribution obligation on whoever bundles, and this
#             file is what bundles: the JRE base image, the Gradle runtime dependencies, and the
#             node stage's contribution.
#   TODO(#64) version. The image carries no version label and the jar is still `0.1.0-SNAPSHOT`;
#             what `v0.1.0` means for a deployable is that ticket's question.

# --- the frontend ---------------------------------------------------------------------------
# Pinned to the major that `@types/node` in frontend/package.json is written against.
FROM node:22-alpine AS frontend

WORKDIR /frontend

# Dependencies first, so a source-only change does not re-resolve the tree. `npm ci` rather than
# `npm install`: the lockfile is the input, and a build that can silently float a version is not a
# build ADR-0143's immutable-version promise can stand on.
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci

COPY frontend/ ./
# `npm run build` is `tsc --noEmit && vite build`, so a type error fails the image rather than
# shipping a bundle nobody typechecked.
RUN npm run build

# --- the boot jar ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jdk AS backend

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

# Tests are not run here. They need Docker themselves (Testcontainers starts postgres:16-alpine),
# which is not available inside the build, and ADR-0145 puts the release gate in a Gradle task run
# before the tag rather than in the image build.
RUN ./gradlew --no-daemon :nodqora-app:bootJar -x test

# --- the runtime ----------------------------------------------------------------------------
FROM eclipse-temurin:21-jre AS runtime

# ADR-0128 promises a self-hostable Community edition, and a self-hosted thing that insists on root
# is a worse promise than it sounds.
RUN useradd --system --create-home --uid 10001 nodqora
USER nodqora
WORKDIR /app

COPY --from=backend /src/nodqora-app/build/libs/nodqora-app-*.jar /app/nodqora.jar

# ADR-0150's compose file publishes this port and the install instructions open it.
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/nodqora.jar"]
