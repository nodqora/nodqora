# ADR-0108: Four categories are Enterprise by standing rule; everything else is Community by default

- **Status**: Accepted
- **Date**: 2026-09-05
- **Ticket**: [The Community/Enterprise feature ledger](https://github.com/fredskor/nodqora/issues/42)

## Context

A ledger enumerating the roadmap as it stands today is stale the first time
someone adds to the roadmap. Capabilities arrive continuously, and without a
stated default they are placed by whoever builds them — which in practice means
Community, because this repo is where code gets written and
[the map](https://github.com/fredskor/nodqora/issues/40) fixed that this repo
never contains proprietary files. **A capability can therefore reach Community
by nobody deciding.**

That matters most for the one category the survey found reliably paid across all
ten products and that Nodqora has no plan for yet: **state flowing outward to
source systems.** Product plan §5.6 defers write actions on safety grounds and
§48 lists them as an MVP non-goal, so write-back has no phase, no spec, and no
tier. The cost of placing it now is nothing. The cost of placing it after
someone has shipped a *"restart this connector"* button in Community is a
demotion, and the survey found that **no vendor in it has ever demoted a free
feature to paid** — Grafana archived OnCall rather than move its line.

The alternative to a standing rule is a per-capability gate: every new feature
requires an explicit tier decision recorded before merge. That is more
deliberate and it stalls, because the decision has no natural owner and no
deadline.

## Decision

**Four categories are Enterprise by standing rule. Everything else is Community
by default.**

| Category | Nodqora analogue |
|---|---|
| State flowing **outward** to source systems | any write-back to Kafka, Connect, Kubernetes or a cloud API |
| Approval and request workflow | anything where one person's action waits on another's consent |
| Continuous monitoring and alerting | ADR-0107's standing query, in every form |
| Fleet-scale rollups **across installs** | aggregation over more than one Nodqora deployment |

The four are the categories the survey found reliably paid across products in
three different segments, which is the strongest evidence available that they
are where organizations expect to pay.

**Everything not named in the ledger and not falling into those four is
Community.** A capability moves out of Community only by an ADR that says so
before it ships.

This rule places write-back without inventing a specification for it. When
write actions are designed, they are designed as Enterprise, and §5.6's safety
argument and this ADR's commercial one point the same way.

## Consequences

- **The ledger keeps working after this ticket closes.** A capability arriving in
  2027 is placed by reading four rows rather than by reopening the tiering
  question, and the reading is one a contributor can do without the map.
- **Write-back is placed before it is specified**, which is the whole point.
  The design freedom given up is real but narrow: a future decision that some
  specific write action belongs in Community is still available, and costs one
  amending ADR rather than a demotion.
- **The default direction is generous, and that is the asymmetry being bought.**
  A capability that should have been Enterprise and lands in Community can be
  moved only by demotion, which the survey says nobody does. A capability that
  should have been Community and lands in Enterprise is released free with an
  ADR and costs nothing. The default therefore leans the way that is cheap to
  correct — except within the four categories, where the correction is the
  expensive direction and an explicit rule is worth its rigidity.
- **Cost: the four categories are described, not defined.** *"Approval and
  request workflow"* has no boundary that survives an adversarial reading, and
  a contributor arguing that some feature is or is not one of these will
  sometimes be right either way. This is a rule for good-faith placement, not an
  enforceable specification, and it fails the same way ADR-0107's standing-query
  line fails.
- **Cost: this rule contradicts nothing in §43 and extends well past it.**
  §43 was a list of features; this is a list of shapes. Anyone reading §43 as the
  authoritative Enterprise scope will be surprised by write-back appearing there,
  and `docs/editions.md` is where that surprise is answered.
- **Revisit trigger.** A capability arriving that plainly belongs in Community
  and plainly falls in one of the four categories. One such case is a naming
  problem; two is evidence the categories are drawn from the wrong survey.
