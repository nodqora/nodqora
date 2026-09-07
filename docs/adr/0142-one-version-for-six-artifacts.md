# ADR-0142: One version for six artifacts, and the version names a commit

- **Status**: Accepted
- **Date**: 2026-09-07
- **Ticket**: [Artifact publishing for core, plugin-api and the four plugins](https://github.com/fredskor/nodqora/issues/52)

## Context

Six artifacts must be versioned. They have exactly one consumer — the Enterprise
assembly ([ADR-0114](0114-the-enterprise-edition-is-a-second-assembly.md)) —
which builds a single image from a single dependency set and has no reason to
hold `nodqora-core` at one version and `nodqora-plugin-kafka` at another.

[ADR-0139](0139-support-covers-the-latest-release-and-the-repo-keeps-one-line-of-development.md)
leaves this repository **one line of development**: no release branches, no
backports, no second build.

## Decision

**All six artifacts share one version, and every release publishes all six —
including modules in which nothing changed. The version is a property of this
repository, not of any module in it.** `io.nodqora:nodqora-core:0.4.0` and
`io.nodqora:nodqora-plugin-kafka:0.4.0` are the same commit, and that is the
whole meaning of the number.

The scheme is **semver, and stays below `1.0` indefinitely**. Semver's own
specification gives `0.x` no compatibility guarantee, which states ADR-0010's
"no stability guarantee on these interfaces" in a format every tool and reader
already parses.

Within `0.x`, the two lower positions carry a promise weak enough to keep by
inspection of a diff:

- **minor** (`0.4.0` → `0.5.0`) — a public signature somewhere in the six may
  have moved. Enterprise recompiles and may have to edit.
- **patch** (`0.4.0` → `0.4.1`) — no public signature changed anywhere. A safe
  drop-in; Enterprise bumps a property and ships.

This is deliberately weaker than semver's usual split: there is no claim that
minor releases are additive. Under `0.x` the right to break is retained, and the
number tells the one consumer only whether to expect work.

**`1.0` is not a maturity milestone. It is the same decision as going public**
([ADR-0141](0141-publication-is-private-and-the-sdk-trigger-stays-unfired.md)):
the version at which the surface becomes a promise, reached deliberately and
never drifted into.

Rejected, and why:

- **Independent per-module versioning.** Honest numbers per artifact, at the cost
  of deciding on every release which of six modules moved and by how much.
  ADR-0139 rejected "latest plus the previous minor" precisely because it was "a
  judgement call on every fix"; this is that same cost paid six times per
  release, for a consumer that does not mix versions. With no CI, the judgement
  is one person's memory.
- **CalVer**, `2026.9.0`. It communicates recency. Under lockstep the number
  already cannot mean *this artifact changed*; if it also cannot mean *this may
  break you*, it means nothing. `0.x` at least says one true thing.
- **Starting at `1.0` now.** It promises exactly what this project has decided
  not to promise, and with no CI it could not be verified if it wanted to be.

## Consequences

- **`plugin-api`'s version becomes uninformative.** It bumps when
  `nodqora-plugin-yaml` fixes a typo, so no reader can tell from the number
  whether the SDK surface moved. Under ADR-0141's private audience that costs
  nothing; it is the first thing that would have to change on going public.
- **Modules get releases in which nothing about them changed**, which is noise in
  the registry and confusing to anyone reading version history per artifact.
- **The minor/patch promise is kept by eye.** Nothing computes it — no binary
  compatibility check runs anywhere — so it is exactly as reliable as the person
  cutting the release. It was chosen to be weak enough that this is tenable.
- **Revisit trigger.** A second consumer of these artifacts, which is the first
  situation in which one version across six stops being free — and it arrives
  together with ADR-0141's own trigger, since a second consumer is what going
  public produces.
