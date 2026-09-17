# ADR-0170: Every plugin module is published, and the assembly is not

- **Status**: Accepted
- **Date**: 2026-09-17
- **Ticket**: [What the release carrying Prometheus publishes, and the number it lands on](https://github.com/nodqora/nodqora/issues/133)

## Context

[ADR-0142](0142-one-version-for-six-artifacts.md) put one version across **six**
library artifacts, and
[ADR-0153](0153-the-deployable-shares-the-version-and-the-number-promises-an-upgrade-path.md)
added the deployable as a **seventh** versioned artifact that is never a Maven
coordinate. Both wrote the membership as a count.

`nodqora-plugin-prometheus` is a fifth plugin module, and it is not in
`publishedModules`. The count is now wrong whichever way the plugin goes, and it
would go wrong again at the next plugin.

## Decision

**Every plugin module is published, alongside `nodqora-plugin-api` and
`nodqora-core`. The assembly, `nodqora-app`, is not.** Membership follows what a
module *is*, not a number: a library is a coordinate, and the one assembly
([ADR-0114](0114-the-enterprise-edition-is-a-second-assembly.md)) reaches its
audience as an image.

So `nodqora-plugin-prometheus` is the seventh published coordinate and the eighth
versioned artifact. ADR-0142's and ADR-0153's lockstep is otherwise unchanged:
one number, every artifact, every release.

Rejected, and why:

- **Leaving the plugin unpublished.** The Enterprise assembly builds from these
  coordinates, so it could not carry Prometheus at all. That makes a technology a
  difference between editions, which
  [ADR-0112](0112-every-discovery-plugin-is-community.md) rules out.
- **Correcting the counts in place.** Right until the next plugin, and then the
  same edit again.

## Consequences

- **The release carrying this plugin is `v0.2.0`, by ADR-0153's rule and not by
  choice.** `V3__a_measurement_without_a_vote.sql` is a migration, and
  `plugins.registry-order` and `plugins.precedence` must now name `prometheus`,
  so an install that overrides them must edit its config. `plugin-api` did not
  move, which alone would have been a patch.
- **`THIRD-PARTY` does not change**
  ([ADR-0154](0154-third-party-covers-what-the-build-adds-and-the-base-image-attributes-itself.md)).
  The plugin's dependencies are `connect`'s exactly.
- **ADR-0142's and ADR-0153's titles still say six and seven.** They are records
  of what was true when they were written; this ADR is where the membership now
  lives.
