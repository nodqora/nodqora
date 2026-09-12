# ADR-0159: The release runs on a runner, and the trigger is not a tag

- **Status**: Amended by [ADR-0163](0163-the-release-gate-asks-the-registry-what-the-credential-may-do.md)
- **Date**: 2026-09-09
- **Ticket**: [Move the release to a runner, now that ADR-0155's reasons have expired](https://github.com/nodqora/nodqora/issues/85)
- **Supersedes**: [ADR-0155](0155-the-release-is-one-gradle-command-and-the-tag-is-the-last-act.md)'s *by hand* decision only. Its ordering, its five acts and its gates are untouched.

## Context

ADR-0155 decided the release is cut by hand, and rested that on three facts.
Two releases later, one is false and the other two were never arguments against
a runner.

- **"The repository is private."** [ADR-0158](0158-the-repository-moves-to-an-organisation-and-goes-public-with-its-record-intact.md)
  made it public, so the metered Actions minutes and billed `arm64` runners that
  argument turned on are free — by ADR-0155's own statement that they are "free
  only on public repositories". Its follow-on, that a workflow would still need
  the GHCR package flipped public by hand, is also spent: that flip and the
  organisation's public-package policy were one-time acts, already done.
- **"The release machine is already fully tooled."** This argued the laptop was
  not a handicap, not that a runner could not do it. `ubuntu-latest` has a Docker
  daemon, so `NodqoraIntegrationTest`'s Testcontainers `postgres:16-alpine` runs
  there unchanged.
- **"The emulation cost is avoidable."** This removed the one argument *for* a
  runner — speed — by pinning both build stages `FROM --platform=$BUILDPLATFORM`.
  It is still true, and it makes multi-arch cheap on a laptop and a runner alike.

Since then a reason *for* a runner appeared that did not exist when ADR-0155 was
written. [ADR-0157](0157-the-image-build-takes-a-worker-count-because-some-networks-drop-parallel-fetches.md)
found that the network the releases were cut from drops Gradle's parallel Maven
Central fetches, forcing `--max-workers=1`. Both releases so far took roughly
twelve minutes where the build itself takes about forty seconds. That is a
property of one machine's connection, and it is the largest cost in the process.

## Decision

**The release runs in GitHub Actions, dispatched by a human. The trigger is
`workflow_dispatch`, and explicitly not a tag push.**

This is the part that matters. The conventional recipe — push a tag, let CI
build and publish — **inverts ADR-0155's central invariant**. That ADR made the
tag the *last* act so that a tag can never exist without an image behind it;
tag-triggered, a failed build leaves a tag pointing at nothing, which under
[ADR-0143](0143-published-versions-are-immutable-and-the-inner-loop-has-no-artifact.md)
is the one failure that cannot be fixed by re-cutting. The workflow therefore
*creates* the tag at the end, or no tag exists.

**The division of the three acts is preserved.** ADR-0155's act 1 — set the
version, commit it — stays a human commit on `main`, because
[ADR-0142](0142-one-version-for-six-artifacts.md) makes a version *be* a commit
and a bot author would make that commit no one's. The workflow is act 2. Act 3,
the bump back to `-SNAPSHOT`, stays a human commit as ADR-0155 left it.

**The dispatch input is a confirmation, not a source.** The version is read from
`build.gradle.kts`; the input must match it or the run fails before anything is
built. A dispatch cannot release whatever `main` happens to hold at the moment a
stale browser tab is submitted.

**`GITHUB_TOKEN` replaces the personal access token.** It already carries
`packages: write`, so the PAT in `~/.gradle/gradle.properties` and the separate
`docker login ghcr.io` both disappear — two of the three manual setup steps the
first release needed. The credentials file is written on the runner and dies
with it.

> Amended by [ADR-0163](0163-the-release-gate-asks-the-registry-what-the-credential-may-do.md):
> this holds for packages the workflow **creates**, which is every Maven
> coordinate here. A container package pushed by hand before the workflow existed
> carries no repository link, and `GITHUB_TOKEN` has no write role on it until one
> is granted. That is what the first runner-cut release discovered, at the cost of
> 0.1.2.

**`nodqora.build.maxWorkers` is deliberately unset** on the runner. ADR-0157's
serialisation is a workaround for one network's fault, and a runner does not
have it.

## Consequences

- **ADR-0145's "publishing is a human running Gradle on a laptop" survives in
  substance.** A human still decides the version, commits it, and presses Run
  workflow. What moved is the machine, not the authority — the release is not
  automatic, it is *dispatched*.
- **The workflow owns no release logic.** The ordering, the gates, the anonymous
  visibility check and the single `gh release create` all remain in the Gradle
  task, which still runs identically on a laptop. This is a second place the
  release can be cut from, not a replacement — if Actions is unavailable,
  `./gradlew release` on a tooled machine is unchanged.
- **QEMU is needed and the two build stages still are not.** The runtime stage is
  per-architecture and does `RUN useradd`, so the non-native half is emulated.
  That is a `COPY` and a `useradd`, which is why ADR-0155's pinning still carries
  the cost argument.
- **`releasePreflight`'s branch gate constrains the checkout.** It reads
  `git rev-parse --abbrev-ref HEAD` and refuses anything but `main`, so the
  workflow checks out `ref: main` rather than the default detached HEAD, and with
  full history because the gate also lists tags.
- **This is the repository's first workflow.** There was no `.github` directory
  before it. Nothing else is moved to CI here — in particular the build and tests
  are not, because ADR-0145 gates on them passing *inside the release
  invocation*, and a separate CI run would be a second opinion rather than the
  gate.
