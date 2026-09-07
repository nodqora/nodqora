# ADR-0117: Enterprise builds its own frontend, and Community publishes one on the trigger

- **Status**: Accepted
- **Date**: 2026-09-05
- **Ticket**: [The extension contract Enterprise plugs into](https://github.com/fredskor/nodqora/issues/43)

## Context

Every Enterprise item in the ledger that a user can see needs UI: the journal's
timeline, monitoring configuration, policy results, the assistant. None of it
can be added today, because there is nothing to add it to.

`frontend/` is a standalone Vite application that proxies `/api` to the Spring
app next door. **Nothing serves it in production** — Community's own packaging
story is unwritten. Routing is hand-rolled in `routing/route.ts` rather than a
router with a registry. The inspector is a single `Drawer.tsx`. There is no
extension point to extend, no artifact to extend it from, and no version to
extend against.

## Decision

**Enterprise builds its own frontend**, against Community's published as an npm
package — the frontend saying what ADR-0114 said: two builds, one library.
Enterprise imports the canvas, the drawer and the API client, and composes its
own application with its own routes and panels around them.

**The package is published when the first Enterprise feature needs UI, and not
before.** This mirrors ADR-0010's trigger for the deferred SDK — *the first
plugin we do not compile* — and it means the Enterprise repo can be started
backend-first, which is all ADR-0114 and ADR-0116 require.

What is decided now is the shape, because the shape is what is hard to reverse.
Rejected permanently:

- **Named slots** — a registry of extra inspector tabs, routes and overlays that
  the Enterprise build fills at compile time. This is the tempting one, because
  a slot is a far smaller stable surface than an exported component API. It is
  refused because **a slot with nothing in it in Community is exactly the
  Enterprise hole ADR-0115 rejected, relocated to TypeScript where no ArchUnit
  check can see it.** It would pass the Community-consumer test only if
  Community composed its own inspector through the registry, which is a frontend
  refactor justified by a proprietary edition.
- **Runtime module loading**, where the Community bundle dynamically imports
  remote modules the server declares. Community would ship a loader for modules
  only Enterprise has, and it puts a plugin ABI on React components — the least
  stable interface in the system.

## Consequences

- **The Enterprise repo can start without any of this**, which is what keeps the
  map's destination reachable.
- **The first Enterprise UI feature pays a large one-off bill**: extracting a
  publishable frontend package, and almost certainly landing Community's
  production packaging in the same push, since a library and a deployable are
  the same extraction seen from two sides.
- **Community's frontend acquires a public API it does not want.** Every future
  refactor of the canvas or the drawer becomes a breaking change for an edition
  that is not in this repository — the same reach-back ADR-0115 accepted for
  `GraphController`, on a much larger and more volatile surface.
- **The trigger fires at the worst moment.** It fires in the middle of building
  the first Enterprise feature, which is precisely when reaching for slots will
  look like pragmatism. This ADR exists to be read on that day.
- **Revisit trigger.** A published component API proving unholdable in practice —
  concretely, a second consecutive Community frontend change that cannot be made
  without breaking Enterprise. That is evidence the surface is wrong, and the
  answer then is a narrower one, not the slots refused here.
