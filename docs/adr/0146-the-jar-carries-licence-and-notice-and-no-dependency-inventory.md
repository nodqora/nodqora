# ADR-0146: The published jar carries LICENSE and NOTICE, and no dependency inventory

- **Status**: Accepted
- **Date**: 2026-09-07
- **Ticket**: [Artifact publishing for core, plugin-api and the four plugins](https://github.com/fredskor/nodqora/issues/52)

## Context

[ADR-0130](0130-the-licence-is-stated-once-per-file-and-once-per-repo.md) kept
`NOTICE` to the project's own attribution and pushed dependency attribution here:

> Dependency attribution is a distribution-time concern — a generated
> `THIRD-PARTY` artifact in the built distribution — and belongs to artifact
> publishing.

Checked against what this ticket actually publishes, the handoff lands on the
wrong ticket, and honouring it literally would reproduce the mistake ADR-0130
was avoiding.

## Decision

**Each of the six jars carries `LICENSE` and `NOTICE` in `META-INF/`. None
carries a `THIRD-PARTY` file, because none of them bundles third-party code.**

`nodqora-core.jar` contains `io.nodqora.core` classes and a POM that *declares*
Spring, Flyway and PostgreSQL as dependencies. Declaring a dependency is not
distributing it: the consumer resolves those jars themselves, from their own
source, under their own terms. There is nothing inside any of the six to
attribute.

Shipping `LICENSE` and `NOTICE` inside the jar is Apache's own convention and one
Gradle block, and it is what makes ADR-0130's §4(d) propagation hook travel with
the artifact rather than only with the repository.

**The attribution obligation is real; it attaches to a fat jar or an image, and
is routed rather than dropped:**

- **The Enterprise distribution** bundles Community jars and every third-party
  dependency, and owes attribution for all of it. That is the Enterprise repo's
  own build, sitting exactly where its EULA already sits — **out of scope on the
  map**, recorded here so the requirement is inherited rather than discovered.
  ADR-0130 already gave it the Community half: `LICENSE` and `NOTICE` ship in the
  Enterprise distribution too.
- **A Community binary distribution** — an image, a boot jar, a download — does
  not exist. There are no releases and no tags, and
  [ADR-0114](0114-the-enterprise-edition-is-a-second-assembly.md) excludes
  `nodqora-app` from the six, so the Community assembly is not published by this
  decision at all. Today a Community user builds from source. **Nothing on the map
  decides how that changes**, and it is now recorded as fog.

Rejected, and why:

- **Generating `THIRD-PARTY` for all six from the resolved runtime classpath.**
  It satisfies the handoff literally and it feels like diligence. It produces a
  list of things the jar does not contain, and it drifts the moment a dependency
  version moves — the exact failure ADR-0130 rejected when it refused a
  dependency inventory in `NOTICE`. A stale attribution list is worse than none,
  and one written for a jar that bundles nothing is stale on arrival.

## Consequences

- **Something a prior ADR handed to this ticket is being handed on rather than
  done**, which is a pattern worth being suspicious of. The defence is that
  ADR-0130 named the destination correctly — *the built distribution* — and this
  ticket does not build one.
- **The first person to ship a runnable Nodqora inherits the whole obligation at
  once**, in whichever repository that happens. It is a generated file and a
  build plugin, not a decision, but it is not free and it has no owner yet.
- **`LICENSE` and `NOTICE` in `META-INF/` depend on ADR-0130's files existing**,
  which they do only on the licensing branch. Publishing cannot precede that
  landing.
- **Revisit trigger.** The first Community binary distribution, in this
  repository or anywhere else — that is the moment a `THIRD-PARTY` file stops
  being someone else's problem.
