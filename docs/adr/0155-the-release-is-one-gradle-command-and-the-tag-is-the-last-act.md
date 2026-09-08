# ADR-0155: The release is one Gradle command, run by hand, and the tag is the last act

- **Status**: Accepted
- **Date**: 2026-09-08
- **Ticket**: [Cutting the release: by hand or by workflow](https://github.com/fredskor/nodqora/issues/68)

## Context

[ADR-0145](0145-the-release-gate-is-a-gradle-task.md) made the release gate a
Gradle task explicitly because "publishing is a human running Gradle on a
laptop", and its task publishes **Maven coordinates** and creates the tag. Then
[ADR-0150](0150-the-community-distribution-is-one-public-image-and-a-compose-file.md)
put a **second publishing step beside it** — build and push a multi-arch image,
create a GitHub Release, attach `compose.yaml`, and make the GHCR package
public — and the two knew nothing about each other.
[ADR-0153](0153-the-deployable-shares-the-version-and-the-number-promises-an-upgrade-path.md)
then promised that one tag publishes jars, image and compose asset atomically,
which nothing yet owned.

The one argument for moving this to a runner was cross-architecture speed:
`buildx` emulating `amd64` on the Apple-silicon laptop this is cut from is
correct but slow. Three facts settle it the other way.

- **The repository is private.** GitHub-hosted `arm64` runners are free only on
  public repositories; here they are billed larger runners, and every Actions
  minute is metered. A workflow would still need the GHCR package flipped public
  by hand, because a package created from a private repository inherits private
  visibility.
- **The release machine is already fully tooled.** ADR-0145 gates on a passing
  full `build`, and `NodqoraIntegrationTest` starts `postgres:16-alpine` through
  Testcontainers. ADR-0150's "a clean clone and Docker is the entire
  prerequisite" is a promise to a *stranger building from source*, not to the
  releaser, who needs a JDK and a Docker daemon either way.
- **The emulation cost is avoidable, not inherent.** Both build stages are
  unpinned, so `buildx --platform linux/amd64,linux/arm64` runs npm *and* the
  entire Gradle build twice, once under QEMU. A boot jar is
  architecture-independent and nothing in those stages needs the target
  architecture. Pinning them `FROM --platform=$BUILDPLATFORM` runs them once,
  natively, leaving only the `eclipse-temurin:21-jre` runtime stage per-arch —
  a `COPY` and a `useradd`. Multi-arch then costs roughly what single-arch
  costs.

## Decision

**`./gradlew release` is the whole release.** ADR-0145's task grows to own the
image and the GitHub Release, shelling out to `docker` and `gh`. Two commands is
ADR-0145's rejected `docs/releasing.md` checklist wearing a shorter hat, and it
fails in the worst possible place: the skippable half is the one that produces
the thing strangers install. ADR-0153's atomic tag needs a single owner of
ordering, which a second command by definition is not.

**The five remote acts are ordered by visibility, and the tag is last:**

1. The ADR-0145 gates, then the full `build` with tests.
2. `docker buildx build --platform linux/amd64,linux/arm64` **without**
   `--push` — a dry run that proves the image builds before anything is
   published.
3. Publish the Maven coordinates. Private, one consumer, and the likeliest auth
   failure, so it fails where nothing public is left behind.
4. `buildx` again **with** `--push`, tagging both the exact version and
   `latest` per ADR-0153. Cache-hot from step 2, so this is an upload.
5. `gh release create v<version> --target <sha> compose.yaml` — **one call**
   that creates the tag, creates the Release and attaches the asset.

This dissolves the ordering problem rather than managing it. There is no window
in which a tag exists without an image behind it, because the image is already
pushed when the tag comes into existence — and under
[ADR-0143](0143-published-versions-are-immutable-and-the-inner-loop-has-no-artifact.md)
a tag with no image is the one failure that cannot be fixed by re-cutting. The
inverse window, between 4 and 5, is a pushed image no tag names: harmless,
undiscoverable, and erased by retrying the release from the top, which stays
safe precisely because ADR-0145's gate is "the version is not already tagged".

**GHCR visibility is verified, never flipped.** Between steps 4 and 5 the task
fetches an **anonymous** pull token from
`ghcr.io/token?scope=repository:fredskor/nodqora:pull` and does a `HEAD` on the
manifest for the exact version. Anything but a 200 fails the release. This is
the one failure invisible to the only person who can see it — the publisher's
`docker pull` succeeds against a private package because the publisher is
authenticated, while every stranger gets `denied`. Placed in the gap the
ordering already leaves, a private package stops the release *before* the
irreversible act, and the rerun is clean because no tag was made. The task does
not attempt the flip: there is no reliable REST endpoint for visibility on a
user-owned container package, and an undocumented `PATCH` that may or may not
work turns a loud one-time manual step into a silent, occasionally-working one.

**`compose.yaml` names `ghcr.io/fredskor/nodqora:@VERSION@` and the release
renders it.** The task writes `build/compose.yaml` with the Gradle version
substituted, and that is what `gh` uploads. The version keeps its single home in
the root `build.gradle.kts`, and the file in the tree is never a runnable
install — so an unpinned `docker compose up` cannot be copied out of `main`,
which is the failure ADR-0143's immutability would otherwise be worth nothing
against. This is the house pattern already: `nodqora-app/build.gradle.kts`'s
`thirdParty` task renders `@BASE_IMAGE@` into the notices header the same way,
for the same reason.

**The version returns to `-SNAPSHOT` by a human commit.** Cutting a release is
three acts: commit the version to `0.1.0`, run `./gradlew release`, commit
`0.2.0-SNAPSHOT`. Forgetting the last one is caught loudly at the only moment it
matters, by the already-tagged gate, whose message says the ritual in as many
words: *`0.1.0` is already released; bump to the next `-SNAPSHOT` first.*

**There is no `docs/releasing.md`.** Every step the task cannot enforce is
taught by the gate that fires on it — the SNAPSHOT gate when you have not
bumped, the already-tagged gate when you forgot to bump back, the anonymous-pull
check when the package is private, and `gh` itself when the tag exists remotely.
A document duplicating them is the failure
[ADR-0130](0130-the-licence-is-stated-once-per-file-and-once-per-repo.md) named
plainly — *nothing verifies it* — and it decays the first time a gate's wording
changes. The install documentation is a different audience and a different
artifact.

Rejected, and why:

- **A tag-triggered GitHub Actions workflow.** Its whole case was the emulated
  `amd64` build, which `$BUILDPLATFORM` removes. What is left is billed minutes,
  a second credential surface, and CI arriving as *founding* infrastructure at
  the moment the repository has zero releases and no test signal running
  anywhere — so the first thing CI would ever do is the one operation ADR-0143
  makes irreversible.
- **A `scripts/release.sh` wrapping `./gradlew release`.** One command, and
  honest that the second half is a sequence of shell steps — but it inverts
  ADR-0145's precedent by putting the outer layer outside the build, where
  nothing verifies it, and reopens the question of who can skip what.
- **Attempting the visibility flip before checking it.** See above; the check
  would still be needed to know which happened.
- **Leaving `:latest` in the tree's `compose.yaml` and rewriting it at release
  time.** Keeps `main` runnable, and a runnable-and-unpinned file is exactly the
  thing that gets copied.
- **Committing the exact version into `compose.yaml` at bump time.** `main` would
  permanently advertise the *previous* release, and the number would live in two
  files that can drift.
- **Having the task commit and push the `-SNAPSHOT` bump.** It would make
  `release` write to `main`, a real escalation in what the build may do, and it
  would have to guess whether the next release is a minor or a patch. A
  confidently wrong version line is not better than a stale one.

## Consequences

- **`docker` and `gh` join the JDK as release-machine prerequisites**, and
  Gradle becomes a process orchestrator, which is not what it is good at. The
  task is `Exec` calls with hand-written failure handling rather than idiomatic
  Gradle. That is the price of one command.
- **ADR-0145 said the task creates the tag; here `gh` creates it server-side**
  from `--target`. Same effect, one fewer remote act, and the local clone picks
  it up with `git fetch --tags`. This amends ADR-0145 rather than restating it.
- **Step 2 pays for a build that is thrown away**, buying failure isolation with
  wall-clock. It is cheap only because `$BUILDPLATFORM` makes the multi-arch
  build single-arch-shaped.
- **The first release will fail at the visibility check, on purpose.** That is
  the design working, not a defect, and it is the one wall a first-time cut is
  expected to hit.
- **The Dockerfile must pin its build stages `FROM --platform=$BUILDPLATFORM`**
  before any release can build multi-arch at a sane cost. That change belongs to
  [the Dockerfile's TODOs](https://github.com/fredskor/nodqora/issues/74), which
  already owns that file.
- **The Dockerfile stays the single source of the base image digest.** The
  release never names it: `thirdParty` receives it as `-PbaseImage` from inside
  the Docker build, per
  [ADR-0154](0154-third-party-covers-what-the-build-adds-and-the-base-image-attributes-itself.md).
- **Revisit trigger.** CI arriving in this repository for any other reason — a
  test signal, a PR check — at which point steps 2 through 5 have an enforceable
  home and the laptop stops being the only machine that can release. This is the
  same trigger ADR-0145 and ADR-0130 already carry.
