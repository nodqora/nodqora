# ADR-0131: Enterprise is named only where its free half is in the user's hands

- **Status**: Accepted
- **Date**: 2026-09-07
- **Ticket**: [How Community surfaces gated features](https://github.com/fredskor/nodqora/issues/47)

## Context

[ADR-0122](0122-communitys-knowledge-of-enterprise-lives-in-the-shell.md)
settled *where* Community may know about Enterprise — static, hardcoded, in the
application shell, never inside a component Enterprise imports — and left three
questions to this ticket: **which** gated capabilities are named at all, in what
**tone**, and **where within the shell**.

The shipped shell is smaller than the question assumes. `App.tsx` composes a top
bar (brand, environment switcher, search, plugins chip, metrics toggle, error)
and a workspace (banners, canvas or empty state, drawer). There are no routes
beyond `/{environment}?node=`, no navigation, and no settings page at all.
`EmptyCanvas`, `Banners`, `PluginsChip` and `SearchBox` are imported by
`App.tsx` and nothing else; `Canvas` and `Drawer` are what Enterprise will
import under [ADR-0117](0117-enterprise-builds-its-own-frontend.md).

Checked against the ledger, **not one Enterprise capability has a control in
Community today** — nothing to disable, no tab to grey out, nothing a user
currently reaches for and misses. The usual taxonomy for this question — nothing
/ a disabled control / an explanatory drawer / a link out — presumes a control
exists. Here none do.

## Decision

**Community names an Enterprise capability only where the Community capability
it extends is already in front of the user. There is no standing Enterprise
surface anywhere in the product, and a capability with no free half is never
named at all.**

Two parts.

**1. Adjacency, not a standing surface.** No nav entry, no About panel, no
footer link, no editions page in the product. The mention appears beside the
free version of the same thing — the journal beside drift, alerting beside blast
radius, the timeline beside the incident view, aggregation beside audit
capture, view-scoping beside authentication — or it does not appear.

A standing surface was rejected on
[ADR-0081](0081-outcome-is-a-resting-chip-and-an-exception-surface.md)'s own
argument, applied harder. It refused a permanent coverage channel because *"a
channel that reads `COMPLETE COMPLETE COMPLETE` for weeks is one people stop
reading, which is precisely the wrong property for the day it changes."* An
upsell nav item is that channel with worse economics: it never changes at all,
and it competes for rail space with the plugins chip, which is the one element
[ADR-0018](0018-mvp-canvas-feature-scope.md)'s scope discipline and §14's
clutter concern have already had to make room for.

This is not the *"nothing anywhere in the product"* stance ADR-0122 refused for
making the paid half *"discoverable only by reading marketing"*. The paid half
is in the product; it is only ever in the one place where the reader has
demonstrated they care.

**2. A capability with no free half is silent, permanently.** The policy engine,
the AI assistant and write-back have no Community counterpart, so they have no
control to attach to and are never named in the product.

The assistant cannot acquire one later either.
[ADR-0113](0113-the-ai-assistant-is-enterprise-by-5-5-not-by-the-buyer-rule.md) places it by
§5.5 *"useful without AI"* rather than the buyer rule, so Community will never
ship an "explain this" affordance — and building one solely to carry the mention
would be inventing a control for a feature we do not ship, which is an
advertisement wearing a control's clothes.

The rule that falls out is worth stating as the property it is: **Community
never names a capability it cannot show you the free half of.** Every mention is
attached to something the reader is holding and provably using, which is why it
is an affordance rather than an advertisement, and why it needs no defensive
tone.

## Consequences

- **The rule ships zero pixels today.** No current control has a paid
  neighbour, so it fires the first time one does — most plausibly drift against
  the previous fold, in §49 phase 9. ADR-0122 required the boundary to be
  decided before the shell exists; this is that, written to be read on the day
  it first applies.
- **The AI assistant is invisible in the product by construction**, and it is
  plausibly the most-requested Enterprise row. Accepted: it is the same
  discipline ADR-0113 applied in placing it, and a product that advertises AI it
  does not run is the shape §5.5 exists to avoid.
- **Mention count is structurally capped** at the number of shell controls with
  a paid neighbour — about five. Clutter cannot accumulate the way it could
  under a standing surface, so there is no threshold to police.
- **The copy can only go stale in one direction.** It is hardcoded against a
  ledger `docs/editions.md` calls provisional, and
  [ADR-0128](0128-community-capabilities-move-one-way-only.md) makes placements
  move free-ward only. So the failure mode is a line naming as Enterprise
  something that has since become Community — the product under-selling itself,
  never over-selling.
- **Revisit trigger.** Enterprise enquiries whose provenance is consistently
  outside the product — concretely, the first five enquiries with none citing an
  in-product mention. That falsifies the claim this ADR rests on, that a mention
  beside a control the reader is touching converts better than a page listing
  the paid half, and it reopens the standing surface and the silence on the
  three orphans **together**, because they are the same bet made twice.
