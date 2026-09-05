# ADR-0115: A core seam requires a Community consumer, and Enterprise extends by substitution

- **Status**: Accepted
- **Date**: 2026-09-05
- **Ticket**: [The extension contract Enterprise plugs into](https://github.com/fredskor/nodqora/issues/43)

## Context

ADR-0015 forbids the core any compile-time knowledge of a plugin and enforces it
bluntly: the build fails if core source contains the identifier `kafka` at all.
The guarantee it protects is §5.2's, the product's main differentiator.

ADR-0114 makes Enterprise a second assembly. That raises the question ADR-0015
never had to ask, because there was only ever one edition: **may core contain a
seam that exists only so that a proprietary module can plug into it?**

The pressure is real and immediate. Walking the ledger's Enterprise column, an
obvious design offers itself — a `ViewScope` interface for RBAC, a `HistorySink`
for the journal, a `PolicyHook` for the policy engine, each with a harmless
no-op implementation in Community.

## Decision

**The same rule binds Enterprise. Core exposes a seam only where a Community
feature genuinely uses it.** Everything else Enterprise achieves by
**substitution and addition at its own assembly**: it declines to scan a
Community bean and ships its own, or it adds beans, controllers and schedulers
that core never hears about.

The test is stated so it can be applied: **a seam whose Community implementation
is a no-op is an Enterprise hole in disguise.** `ViewScope`'s Community
implementation is *return everything*, because ADR-0109 made a Community install
a shared view deliberately. That is not a seam Community uses; it is a seam
Community tolerates.

**Structural and behavioural changes are distinguished.** Extracting an
interface from a concrete class adds no code path and no branch — it names a
boundary that already exists, and is permitted (ADR-0116 does exactly this
twice). Publishing an event with no Community subscriber, or a hook Community
implements emptily, is refused.

ArchUnit gains the mirror of ADR-0015's check: core source contains none of
`enterprise`, `licence`, `license`, `rbac`, `tenant`.

One candidate seam was examined at length and refused. The journal must observe
the fold, and **drift** (ADR-0110) looked like the Community consumer that would
justify a fold-commit event. It does not: ADR-0054 already puts traversal in the
client, the client holds the whole environment, and drift against the previous
fold may therefore be a diff of two consecutive `/graph` responses with no
server involvement at all. Whether drift is client-side or server-side is an
open Community design question, and a core event may not be justified by a
Community feature that might never want it.

Rejected, and why:

- **A declared Enterprise SPI** — honest about intent and clean to upgrade, but
  it is the no-op hole with a name, and once one is acceptable core accumulates
  them while the ArchUnit check still passes.
- **Fork-and-patch**, which makes every core change a merge conflict Enterprise
  resolves under commercial pressure.

## Consequences

- **There is no `if (licensed)` anywhere in core**, so the gating mechanism
  ([#44](https://github.com/fredskor/nodqora/issues/44)) is a build-and-deploy
  question rather than a key-check question. Combined with ADR-0114 this leaves
  that ticket much less to invent and much less to enforce with.
- **Enterprise copies thin classes and pays a drift tax at every upgrade.** A
  change to `GraphController` or to a document shape does not reach Enterprise,
  and nothing warns it. That is the standing cost of core staying honest.
- **Community's design acquires a constraint from an edition not in this repo.**
  Substitution is only cheap while the substituted classes stay thin; if
  `GraphDocuments` grows real logic, copying it stops being viable. This is the
  one way Enterprise reaches back into Community, and it is deliberately the
  only one.
- **The structural/behavioural distinction is enforced by review, not by a
  test.** ArchUnit cannot tell an honest boundary from an Enterprise hole, and
  the identifier check catches only seams careless enough to be named after what
  they are for.
- **Revisit trigger.** A third Enterprise item that can only be built by
  substituting a class with real logic in it. Two — a journalling store and a
  view-scoping controller — are affordable; a third suggests the seam Community
  is missing is a real one, and the fold-commit event refused above should be
  reconsidered on its own merits with a Community consumer found first.
