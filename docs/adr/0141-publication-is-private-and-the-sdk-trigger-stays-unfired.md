# ADR-0141: Publication is private, and ADR-0010's SDK trigger stays unfired

- **Status**: Accepted
- **Date**: 2026-09-07
- **Ticket**: [Artifact publishing for core, plugin-api and the four plugins](https://github.com/fredskor/nodqora/issues/52)

## Context

[ADR-0114](0114-the-enterprise-edition-is-a-second-assembly.md) makes the
Enterprise repository build against six published artifacts — `nodqora-core`,
`nodqora-plugin-api` and the four `nodqora-plugin-*`. Nothing in this repository
is publishable today: there is no `maven-publish` plugin anywhere, every module
is `io.nodqora:*:0.1.0-SNAPSHOT`, and there are **zero git tags and zero
releases**.

[ADR-0010](0010-plugin-entity-two-capabilities.md) deferred a published SDK
artifact with a stated trigger — *the first plugin we do not compile* — and named
what is at stake in doing it early:

> While all four are first-party and in-repo the interfaces can be reshaped in
> one commit; that freedom ends the moment someone else builds against them.

ADR-0114 arrived by a route ADR-0010 did not anticipate. The question is whether
it fires that trigger.

## Decision

**The six artifacts are published to a private registry — GitHub Packages on
this repository — whose audience is the Enterprise build and nobody else.**
Nothing is published to Maven Central.

ADR-0114 requires an artifact the Enterprise build can *resolve*. It does not
require a *public* artifact, and conflating the two is how ADR-0010's trigger
gets fired by accident.

**The trigger stays unfired, and this ADR records why rather than leaving it
true by luck.** ADR-0112 puts *every* discovery plugin in Community, and
[ADR-0116](0116-the-substitution-set-is-two-stores-and-a-package-move.md) found
the whole Enterprise column to be addition — beans, schedulers, routes.
**Enterprise never implements `Plugin`.** `nodqora-plugin-api` reaches it as
*vocabulary*, the record types flowing through the fold, not as an extension
point. ADR-0114 therefore made the SDK a hard **artifact** and not a hard
**SDK**, and the reshape-in-one-commit freedom survives the Enterprise repo
intact.

Apache-2.0 is untouched. The source stays open and buildable by anyone; what is
private is a convenience binary, which no licence obliges anyone to ship.

Rejected, and why:

- **Maven Central.** It needs `io.nodqora` namespace verification against a
  domain, a GPG signing key, sources and javadoc jars, and — decisively —
  **releases to Central are permanent and immutable**. Private→public is an easy
  move later; public→private is not a move at all, because nothing published
  there can be withdrawn. Publishing to Central is also the act that fires
  ADR-0010's trigger, by making it possible for someone to build against these
  interfaces without asking.
- **Central for `plugin-api`, private for the rest.** Gives plugin authors the
  SDK and Enterprise the internals. It freezes the single module this project
  most wants to keep reshaping, in exchange for an audience that does not exist.

## Consequences

- **A would-be third-party plugin author has no coordinate.** They clone and run
  `./gradlew publishToMavenLocal`. That is a real cost and it is the point:
  someone doing that is the beginning of ADR-0010's trigger, and it should be
  visible rather than silent.
- **This looks like an open-core project that does not ship jars**, and a casual
  observer may read it as unfinished. Accepted; the alternative is an
  irreversible promise made to nobody.
- **GitHub Packages requires authentication even to read.** Both sides need
  tokens ([ADR-0145](0145-the-release-gate-is-a-gradle-task.md)), which under a
  private registry is no imposition, and under a public one would have been an
  annoyance in its own right.
- **Revisit trigger.** A third party who wants to build a plugin without cloning
  this repository, or a customer or partner who needs to resolve these
  coordinates. Either is the evidence ADR-0010 asked for, and going public is the
  same decision as reaching `1.0`
  ([ADR-0142](0142-one-version-for-six-artifacts.md)) — the surface becomes a
  promise at the moment it becomes reachable.
