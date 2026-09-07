# ADR-0113: The AI assistant is Enterprise by §5.5, not by the buyer rule

- **Status**: Accepted
- **Date**: 2026-09-05
- **Ticket**: [The Community/Enterprise feature ledger](https://github.com/fredskor/nodqora/issues/42)

## Context

Product plan §43 lists the **AI assistant** as a possible Enterprise feature and
§49 phase 11 specifies it: explaining architecture, identifying likely failures,
summarizing incidents, querying dependencies, generating troubleshooting
guidance.

Neither rule from ADR-0107 places it cleanly. The **buyer rule** points the wrong
way: *"explain this architecture to me"* is a single-engineer need, and plan
§6.5 names new team members as a primary user — onboarding is the most
individual use the product has. The **standing-query rule** splits phase 11 down
the middle, leaving explanation and dependency querying free and incident
summarisation paid, which is a defensible line that happens to give away the
half people would actually pay for.

What does settle it is a principle the plan already committed to. **§5.5 —
"Useful without AI"** — is a promise that the product is complete without this
capability. That makes phase 11 the only item on the roadmap whose absence, by
the plan's own statement, does not compromise what Community offers. Every other
Enterprise placement in this ledger removes something from a Community user;
this one removes something the plan already declared a supplement.

The complication is compute. [The map](https://github.com/fredskor/nodqora/issues/40)
puts SaaS out of scope, so there is no Nodqora-operated inference — the model
endpoint and the bill are the customer's either way. Gating a feature that runs
on the customer's own compute against the customer's own API key invites a
distinct objection: not *"that costs money"* but *"you will not let me use what I
already pay for"*. That objection is real and is not answered by this ADR, only
acknowledged.

## Decision

**Phase 11 is Enterprise in full, and the ledger records that it is placed by
§5.5 rather than by the buyer rule.**

The justification is recorded explicitly because ADR-0107 requires it: where
neither rule places a capability, the placing ADR names its own reason rather
than claiming the rule settled it. Reading `docs/editions.md` should not leave
the impression that the buyer rule produced this row, because it did not — and a
future reader re-deriving the ledger from the rule would get a different answer
here and only here.

Community remains fully usable without it, which is §5.5 restated as a
constraint on Enterprise rather than as an aspiration: **no Community capability
may come to depend on the assistant.**

## Consequences

- **Enterprise gets one capability that demonstrates well.** ADR-0107 noted the
  cost that every demo-friendly feature had landed in Community; this is the
  exception, and it is the only row in the ledger that a prospective buyer can be
  *shown* rather than told about.
- **§5.5 becomes load-bearing commercially, not just architecturally.** It was
  written as a design principle — do not build a product that requires a model —
  and it is now also the argument for a price. If the assistant ever becomes
  necessary to use Nodqora well, this ADR's justification collapses along with
  the principle, and both fall together.
- **Cost: the compute objection is unanswered.** A customer with their own model
  endpoint is being charged for orchestration, not inference, and some will find
  that hard to accept. The honest framing in `docs/editions.md` is that
  Enterprise sells the topology-aware assistant, not the model — and that framing
  should not be dressed up.
- **Cost: this is the row most exposed to the category moving.** Natural-language
  querying is becoming an expectation rather than a differentiator across
  developer tooling generally, and a §5.5 justification does not survive the
  feature becoming table stakes — at that point the product *is not* useful
  without it, by the market's judgement rather than the plan's.
- **Revisit trigger.** Comparable products shipping free natural-language
  querying over their graph, or adoption evidence that Community users describe
  the product as incomplete without the assistant. Either falsifies §5.5, and
  this ADR falls with it rather than being defended separately.
