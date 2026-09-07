# ADR-0143: Published versions are immutable, and the inner loop has no artifact

- **Status**: Accepted
- **Date**: 2026-09-07
- **Ticket**: [Artifact publishing for core, plugin-api and the four plugins](https://github.com/fredskor/nodqora/issues/52)

## Context

[ADR-0139](0139-support-covers-the-latest-release-and-the-repo-keeps-one-line-of-development.md)
commits support to "the latest released version". This repository has never
released anything — zero tags, zero releases — so that phrase currently points at
nothing, and this ticket has to invent the release rather than merely publish it.

The conventional JVM arrangement publishes a mutable `-SNAPSHOT` from the main
branch continuously, so a downstream repository always builds against current
work without a publishing step.

## Decision

**Every published version is immutable and tagged. `-SNAPSHOT` is never pushed
to the registry.** The registry holds only versions that could be running
somewhere.

**The Enterprise repository's day-to-day loop resolves Community through a Gradle
composite build (`includeBuild`), or `publishToMavenLocal` — no registry, and no
artifact at all.**

Three reasons, each already settled elsewhere:

1. **ADR-0139 made *released* a support-bearing word.** Releasing creates an
   obligation, because the latest release is the only thing that gets answered
   for. A coordinate whose contents change cannot carry that: there would be no
   telling which `0.2.0-SNAPSHOT` a customer had.
2. **The composite build beats a snapshot at the one thing snapshots are for.**
   With `includeBuild`, changing `nodqora-plugin-api` and the Enterprise code
   consuming it is a single edit-compile cycle with no publish. That is *more*
   freedom than a snapshot gives, and it is the same freedom
   [ADR-0141](0141-publication-is-private-and-the-sdk-trigger-stays-unfired.md)
   chose to keep — ADR-0010's reshape-in-one-commit survives the second
   repository because in the inner loop there is no artifact to reshape.
3. **It keeps the registry meaningful.** Under
   [ADR-0142](0142-one-version-for-six-artifacts.md) a version names a commit;
   immutability is what makes that true rather than aspirational.

Rejected, and why:

- **Snapshots alongside releases.** The default arrangement, and it dissolves
  ADR-0139's promise into a coordinate that cannot be pinned down after the fact.
  Its only real benefit — no publish step during development — is already had
  more cheaply by the composite build.

## Consequences

- **The Enterprise repository needs two modes and a switch between them**: a
  composite build for development, pinned released coordinates for anything it
  ships. An Enterprise image built while `includeBuild` was active is built
  against uncommitted source in another repository, and nothing about it looks
  different afterwards. The gate belongs in the release task
  ([ADR-0145](0145-the-release-gate-is-a-gradle-task.md)); the discipline belongs
  to the Enterprise repo, which starts knowing about it.
- **A broken release cannot be replaced, only superseded.** Combined with
  ADR-0139's finding that a regression has no escape hatch, the cost of a bad
  release is fully paid forward. That raises the stakes on the release gate and
  is one more argument for the founding CI this map has ruled out of scope.
- **Old coordinates resolve forever**, so an Enterprise build pinned to `0.4.0`
  keeps building indefinitely — which is a compatibility property and not a
  support one ([ADR-0147](0147-only-the-latest-release-is-supported.md)).
- **Revisit trigger.** A second person working on the Enterprise repository, at
  which point the composite build stops being available to everyone at once and a
  shared pre-release coordinate starts earning its cost.
