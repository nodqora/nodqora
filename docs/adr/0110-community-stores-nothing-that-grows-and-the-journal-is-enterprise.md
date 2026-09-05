# ADR-0110: Community stores nothing that grows with time; the journal is Enterprise

- **Status**: Accepted
- **Date**: 2026-09-05
- **Ticket**: [The Community/Enterprise feature ledger](https://github.com/fredskor/nodqora/issues/42)

## Context

Product plan §43 lists **topology history** as a possible Enterprise feature and
[the map](https://github.com/fredskor/nodqora/issues/40) named it in the buyer
rule's Enterprise parenthetical — *"across many teams or over time"*. But the
buyer rule genuinely does not settle it: *"the graph looked different
yesterday"* is a single-engineer debugging question, and it is one of the most
common ones there is.

[The survey](https://github.com/fredskor/nodqora/issues/41) found a consistent
shape and then a gap. Wherever history appears in a comparable product it is
**metered rather than gated** — Temporal caps hosted retention at 30–90 days
while self-hosted is unlimited, Cerbos ladders audit retention across four
tiers, and Collate prices *freshness* rather than removing the feature. The
survey's own note is that retention monetizes cleanly *because it maps to a real
vendor cost*, which is exactly the property it loses when the customer hosts the
storage. Nodqora's commercial vector is self-hosted first, so **the lever every
comparable product pulls is unavailable here**. A duration boundary in a
customer-hosted product is a constant a forker edits, and it reads as mean while
being trivially defeated.

The repository supplies the boundary the survey could not. The snapshot store is
*"the engine's retained copy of the **latest** accepted `DiscoveryResult` per
(plugin, environment)"* — `CONTEXT.md`, ADR-0043 and ADR-0046. **There is no
retained past.** History is not existing data waiting behind a gate; it is an
append-only journal somebody has to build. That makes the line architectural
rather than numeric.

## Decision

**Community stores nothing that grows with time. The journal is Enterprise.**

- **Community — drift against the previous fold.** The fold already recomputes an
  environment's Node, Edge and Owner rows wholesale and commits atomically
  (ADR-0043), so what it changed is knowable in-transaction, before the previous
  rows are replaced. Community surfaces that: nodes appeared, nodes vanished,
  health flipped, since the last refresh. It is retained for one cadence
  (ADR-0103) and replaced by the next fold.
- **Enterprise — the journal.** A durable append-only record of graph state over
  time: time-travel to an arbitrary point, deployment history, configuration
  changes, GitOps events, and the incident timeline ADR-0107 decomposed out of
  phase 10. The rest of phase 9.

The line is **the shape of the storage, not its duration**. Community's retained
delta is bounded by the refresh cycle and does not accumulate; Enterprise's
journal accumulates by design. No number appears anywhere in this decision, so
there is no constant to edit and nothing to describe as a limit.

## Consequences

- **The single-team debugging question is answered free**, in the case where it
  is most often asked. *"Did this node just vanish?"* and *"did this go
  unhealthy in the last refresh?"* are Community. *"What did this look like on
  Tuesday?"* is not.
- **The free half costs almost nothing to build**, because the fold's wholesale
  recomputation already produces the comparison. This is the rare place where the
  generous answer is also the cheap one.
- **Enterprise's history feature has to justify itself on retention alone**, with
  no free-tier version to upsell from. That is a harder sale than a metered
  ladder and it is the price of not having a defensible meter.
- **Cost: "one cadence" is a duration wearing a different hat.** A user who
  looks five minutes after a change sees it; one who looks eleven minutes after
  does not, and the difference is ADR-0103's global refresh interval — which is
  configurable. A determined reader will call this a retention window with an
  indirect knob, and they are not wrong. The defence is that the knob exists for
  polling load rather than for tiering, and that turning it up to buy history
  degrades the product's freshness in exchange — the trade is real and
  self-limiting, which a bare retention constant is not.
- **Cost: nothing prevents a forker adding a journal.** The Enterprise value here
  is a feature that does not exist in the open tree, which is the
  possession-of-artifact gate the survey found is what most vendors actually
  rely on. It deters rather than enforces, and ADR-0107 already accepted that
  for the standing-query line.
- **Revisit trigger.** Adoption evidence that Community installs are building
  their own journals — scraping `/state` into a time-series store, or asking for
  an export hook to do so. That would show the free line is drawn short of where
  the need is, and the response is to widen Community rather than to block the
  scraping.
