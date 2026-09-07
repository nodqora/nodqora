# ADR-0114: The Enterprise edition is a second assembly, not a plugin

- **Status**: Accepted
- **Date**: 2026-09-05
- **Ticket**: [The extension contract Enterprise plugs into](https://github.com/fredskor/nodqora/issues/43)

## Context

[The tiers and monetization map](https://github.com/fredskor/nodqora/issues/40)
fixed while charting that Enterprise lives in a **separate private repo** which
consumes `nodqora-core` and `nodqora-plugin-api` as published artifacts, so that
this repository never contains a proprietary file.

That list of artifacts is short by four. ADR-0112 puts **every** discovery
plugin in Community, so an install holding only core and `plugin-api` discovers
nothing at all — it has the vocabulary and the fold and no contributors to fold.

The module the list is missing is a different kind again. `nodqora-app` is
neither core, nor contract, nor plugin: it is the **assembly**, and its own
javadoc says it "exists precisely so that the wiring lives somewhere that is
allowed to see both sides". ADR-0015 already named it as the one place with
knowledge of core and plugins at once.

So the question this ADR answers is not which interfaces Enterprise binds to.
It is what an Enterprise install physically *is*.

## Decision

**Enterprise is its own Spring Boot assembly.** The private repo builds
`nodqora-enterprise-app`, depending on published `nodqora-core`,
`nodqora-plugin-api` and all four `nodqora-plugin-*` artifacts, plus its own
proprietary modules. `nodqora-app` in this repository remains the Community
assembly and is unchanged by Enterprise's existence.

Two assemblies, one core. **Running Enterprise means deploying a different
image**, not enabling a flag on the Community one.

Rejected, and why:

- **Enterprise modules on the Community app's classpath.** ADR-0015's
  `@SpringBootApplication(scanBasePackages = "io.nodqora")` means this nearly
  works already, which is the objection rather than the argument: it works by
  accident, it is not a contract, and it fails the first time an Enterprise bean
  must *replace* a Community one instead of joining it. View-scoping has to
  intercept the read path, not sit beside it.
- **A sidecar process** over the read API. Authentication and view-scoping are in
  the request path; a process downstream of the API cannot scope what the API
  already served.

## Consequences

- **Artifact publishing stops being future work and becomes a dependency.** Six
  modules must be versioned and published before the Enterprise repo can build.
  This is ADR-0010's deferred "published SDK artifact" arriving ahead of its
  stated trigger of *the first plugin we do not compile*, by a route that ADR
  did not anticipate.
- **Upgrading to Enterprise is a redeploy, not a key.** That is a substantial
  constraint on
  [the gating mechanism](https://github.com/fredskor/nodqora/issues/44), which
  inherits a world with no runtime licence check to write.
- **Two assemblies can drift.** Spring configuration, dependency versions and
  defaults are duplicated, and a Community fix to `application.yaml` does not
  reach Enterprise. Enterprise owns keeping step; nothing here detects it.
- ADR-0015 is untouched *within* each edition: one JAR, one process, plugins as
  `@Component`s collected by injecting `List<Plugin>`. Enterprise repeats that
  arrangement rather than changing it.
- **Revisit trigger.** A customer requirement that Community and Enterprise be
  the same image — most plausibly an air-gapped buyer running one artifact
  through an approval process, or a distribution channel that certifies images
  individually. That would force a runtime capability check back into core and
  reopens ADR-0115 with it.
