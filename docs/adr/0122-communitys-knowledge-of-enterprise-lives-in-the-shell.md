# ADR-0122: Community's knowledge of Enterprise is static and lives in the app shell

- **Status**: Accepted
- **Date**: 2026-09-05
- **Ticket**: [License-key and gating mechanism](https://github.com/fredskor/nodqora/issues/44)

## Context

The ledger sells a paid half, and a Community user who never learns it exists
never buys it. But ADR-0115's ArchUnit rule forbids `enterprise`, `licence`,
`license`, `rbac` and `tenant` from `nodqora-core/src/main` outright, and the
rule is right on the merits: **a backend that reports which edition it is, is a
seam whose Community implementation means nothing.**

The sharper problem is in the frontend, and ADR-0117 created it. Enterprise
imports Community's canvas, drawer and API client as an npm package and composes
its own application around them. **Any upsell inside those components ships to
Enterprise customers as an advertisement for what they have already bought.**
Both obvious fixes are already closed: a prop puts edition-awareness into the
published component API, and a registry is the named slots ADR-0117 refused
permanently.

## Decision

**The Community frontend may carry static, hardcoded knowledge of what
Enterprise does. It lives only in the application shell — the routes,
navigation, settings and empty states Community composes for itself — and never
inside a component Enterprise imports. No backend, in either edition, reports
its edition to anything.**

Enterprise composes its own shell (ADR-0117), so it renders none of this **by
construction**. There is no flag to set, no prop to thread, and nothing to
suppress.

Rejected, and why:

- **Nothing anywhere in the product**, with upsell confined to the website and
  the docs. The purest boundary and the only one with no chance of the leak
  above. Refused because it makes the paid half discoverable only by reading
  marketing, which is a poor deal for a user who wants something they cannot see
  and cannot find out about.
- **A backend capability or edition descriptor** the frontend reads, so one
  build could render either way. ADR-0115's hole, and it trips the identifier
  ban on the way.
- **A prop or a slot on the shared components.** Both buy an upsell string with
  a permanent extension surface on the least stable interface in the system.

## Consequences

- **The rule is a placement rule and nothing enforces it.** ADR-0115's ArchUnit
  check covers `nodqora-core/src/main` and no TypeScript at all. A shared
  component that grows an upsell string is caught by review, or by an Enterprise
  customer.
- **This constrains Community's frontend structure before that structure
  exists.** ADR-0117 defers the published package boundary to the first
  Enterprise UI feature; this ADR says the boundary must fall so that the shell
  lands outside it.
- **[How Community surfaces gated features](https://github.com/fredskor/nodqora/issues/47)
  inherits a narrowed question** — the rule, the tone, and the placement within
  the shell, checked against ADR-0018 and ADR-0081 so upsell never competes with
  the honesty layer. *Where it lives in the codebase* is answered here.
- **The upsell copy can go stale.** It is hardcoded against a ledger
  `docs/editions.md` calls provisional, so moving a row between editions is now
  a frontend change as well as a documentation one.
- **Revisit trigger.** An upsell that has to be conditional on something only
  the server knows — most plausibly a Community install sitting alongside an
  Enterprise one. That is the first real argument for an edition descriptor, and
  it should first be met by asking whether the shell can know it statically.
