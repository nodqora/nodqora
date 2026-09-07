# ADR-0118: A plugin capability arrives only with a Community implementation

- **Status**: Accepted
- **Date**: 2026-09-05
- **Ticket**: [The extension contract Enterprise plugs into](https://github.com/fredskor/nodqora/issues/43)

## Context

ADR-0010 gives a plugin exactly two capabilities, Discovery and Health, and
defers §10's `LinkProvider`, `RelationshipResolver` and `ActionProvider`. Of the
ledger's Enterprise items, exactly one would need a third: **write-back**, which
the ledger places by standing rule and §5.6 defers as a feature.

Write-back sits on a contradiction the ledger created and did not have to
resolve. ADR-0112 puts every discovery plugin in Community, and writing back to
Kafka needs the Kafka plugin's client, its credentials and its config — all
Apache-2.0, all in this repository. ADR-0042 goes further and makes the
obstacle mechanical: Connect's build **fails** if anything but a GET reaches it,
enforced by `GetOnlyTest`.

## Decision

**`nodqora-plugin-api` gains a capability only when a Community plugin
implements it.** Nothing is added now. This is ADR-0115's Community-consumer
test applied to the module ADR-0015 protects most.

Rejected:

- **Adding `ActionCapability` now**, unimplemented, so Enterprise has something
  to bind to. An empty interface shipped for a proprietary consumer is the hole
  ADR-0115 refused, placed in the one module that must stay clean.
- **Settling the composition model now** — letting a plugin id be assembled from
  several beans so Enterprise could contribute `kafka`'s action capability
  without touching the Community plugin. ADR-0010 makes the id API surface in
  four places and pairs it with one bean; changing that for an unspecified
  feature is designing against an imagined requirement.

**The constraint write-back inherits is recorded rather than solved.** Whenever
it arrives it must first settle who owns action code, and ADR-0042's guard must
be removed knowingly rather than noticed.

## Consequences

- **Enterprise cannot be technology-specific.** It can never introduce a
  capability, so it can never ship a premium connector or paid support for one
  vendor's system. Every Enterprise feature must be something done to *the
  graph*. ADR-0112 implied this commercially; this makes it structural.
- Reading the ledger back, **every current Enterprise item already satisfies
  it** — group sync, view-scoping, the journal, monitoring, audit aggregation,
  policy, the assistant. The rule costs nothing today and forecloses a category
  of future expansion revenue, on top of the per-connector pricing ADR-0112
  already foreclosed. Both narrowings are inherited by
  [the pricing unit](https://github.com/fredskor/nodqora/issues/45).
- **Write-back, when it lands, will land badly.** The likely resolution is
  Apache-2.0 action code in the Community plugin behind a paid button — free
  code and a paid orchestration. The ledger already accepted deterrence over
  enforcement, so this is consistent, but it is the first place where the free
  half would hold the entire mechanism of a paid feature.
- **Revisit trigger.** A Community plugin wanting a third capability on its own
  merits — most plausibly `RelationshipResolver` for §49 phase 7's automatic
  relationship discovery. That reopens the capability model on Community's terms,
  which is the only way this ADR permits it to be reopened.
