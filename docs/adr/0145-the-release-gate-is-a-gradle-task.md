# ADR-0145: The release gate is a Gradle task, and the jar ships with sources and nothing else

- **Status**: Accepted
- **Date**: 2026-09-07
- **Ticket**: [Artifact publishing for core, plugin-api and the four plugins](https://github.com/fredskor/nodqora/issues/52)

## Context

The map rules **founding CI out of scope**, so publishing is a human running
Gradle on a laptop. Three prior decisions sharpen what that means: published
versions are **immutable**
([ADR-0143](0143-published-versions-are-immutable-and-the-inner-loop-has-no-artifact.md)),
a version **is** a commit
([ADR-0142](0142-one-version-for-six-artifacts.md)), and
[ADR-0139](0139-support-covers-the-latest-release-and-the-repo-keeps-one-line-of-development.md)
leaves a regression **no escape hatch**.

This map now carries three decisions whose policy is documented and unverified —
[ADR-0129](0129-inbound-is-dco-because-apache-2-0-already-grants-the-sublicense.md)'s
DCO, [ADR-0130](0130-the-licence-is-stated-once-per-file-and-once-per-repo.md)'s
SPDX headers, [ADR-0134](0134-the-word-enterprise-has-one-address-in-the-frontend.md)'s
one-address check. ADR-0130 recorded the shape plainly: "the header policy will
decay. Nothing verifies it."

## Decision

**The release is a Gradle `release` task with gates, not a documented
checklist.** A Gradle task is verification that does not need CI: it runs on the
one machine where releases happen, and unlike a checklist it cannot be skipped by
someone in a hurry — which, for a solo maintainer at a keyboard, is the whole
threat model. This map does not acquire a fourth unverified policy.

The task refuses to publish unless:

- the working tree is **clean** and the branch is **`main`**;
- the version is **not** a `-SNAPSHOT`;
- the version is **not already tagged** — immutability is only real if the build
  will not overwrite, and a registry will accept a re-push of the same coordinate
  without complaint, silently breaking ADR-0142's *a version is a commit*;
- **no composite build is active** — a release built while ADR-0143's
  `includeBuild` dev loop is switched on is built against uncommitted source in
  another repository;
- the full `build`, tests included, has **passed in this invocation**.

It creates the tag as part of the release.

**What ships beside the jar:**

- **A sources jar. No javadoc jar.** The sources jar is not a convenience here:
  [ADR-0115](0115-a-core-seam-requires-a-community-consumer.md) recorded that
  "Enterprise copies thin classes and pays a drift tax at every upgrade", which
  makes Enterprise a *source reader by design*. A javadoc jar adds nothing an IDE
  cannot take from sources.
- **No GPG signing.** Signing proves provenance across an untrusted channel. This
  channel is an authenticated private registry with one publisher and one
  consumer, who are the same person. Central would require it — that is a cost of
  revisiting ADR-0141, not a cost today.
- **No test-fixtures variants.** `nodqora-plugin-kubernetes`,
  `nodqora-plugin-connect` and `nodqora-plugin-kafka` apply `java-test-fixtures`,
  publishing ADR-0099's recordings. There is a real case that Enterprise wants
  them — testing a view-scoped controller end to end needs a *populated* graph,
  and the recordings are the only way to get one without infrastructure — but
  Enterprise does not exist yet, and this whole ticket has run on publishing to
  the audience one has. Unlike ADR-0141's Central decision this is cheap to
  reverse: adding the variants later is one release.
- **Credentials are operational secrets.** Publishing needs a `write:packages`
  token, the Enterprise build a `read:packages` one; both live in
  `~/.gradle/gradle.properties` and never in the repository. Custody is out of
  scope on the map, where ADR-0121's signing key already sits.

Rejected, and why:

- **A documented checklist** in `docs/releasing.md`. No build work at all, and it
  relies entirely on discipline — the failure mode ADR-0130 already recorded and
  the map's founding-CI entry already exists to catch.

## Consequences

- **The task makes the right path easy, not the wrong path impossible.** Anyone
  may still run `./gradlew publish` directly and bypass every gate. With no CI
  that is the ceiling, and it is worth stating so the gate is not mistaken for an
  enforcement boundary.
- **This is the first executable policy in the repository**, which sets a
  precedent: the answer to *nothing verifies this* is a build task wherever one
  will fit, and CI when it eventually arrives.
- **The clean-tree and `main` gates make a release impossible from a branch**,
  including the documentation branches this map is currently stacked across. That
  is intended and it means the first release cannot happen until this map's
  branches land.
- **Suppressing the fixtures is the first thing [the first Enterprise
  feature](https://github.com/fredskor/nodqora/issues/53) should check**: if the
  chosen wedge needs an end-to-end test over a populated graph, it needs the
  variants, and that is one release away.
- **Revisit trigger.** CI arriving in this repository, at which point the gates
  move to where they can be enforced rather than merely offered — the same
  trigger ADR-0130 already carries.
