# ADR-0140: Support diagnoses to the boundary and indemnifies nothing

- **Status**: Accepted
- **Date**: 2026-09-07
- **Ticket**: [Support, SLA and what Community gets](https://github.com/fredskor/nodqora/issues/49)

## Context

Nodqora's four first-party plugins — `yaml`, `kubernetes`, `kafka`, `connect` —
all reach into infrastructure the maintainer cannot see. The realistic support
ticket is therefore not "Nodqora has a defect" but *"your Kafka plugin shows
blind against my cluster"*, where the cause is usually the customer's RBAC,
network policy or credentials. A scope that does not anticipate this is a scope
that fails on the first ticket.

There is a structural asset here that most products lack. **Nodqora is designed
to say when it could not look**: ADR-0104 makes an abstention an omission,
ADR-0106 carries a blind observer's last reading forward, ADR-0083 makes
retained and blind separate marks, and ADR-0088 banners the unreported. The
honesty layer already answers a large class of these tickets before they are
filed, and answers them reusably.

Separately, Grafana bundles **indemnification** with support, and it is the one
item on this list that appears on enterprise procurement checklists as a hard
gate rather than a preference.

## Decision

**Support covers Nodqora's own behaviour and its four first-party plugins. It
includes diagnosing which side of the line a problem falls on. It stops at
remediating the customer's estate, it excludes third-party and private plugins,
and neither edition carries indemnification of any kind.**

**Diagnosis to the boundary** is the clause a naively drafted scope gets wrong.
*You cannot disclaim what you had to diagnose in order to disclaim it* — telling
someone the cause is their cluster's RBAC means reading their logs and their
configuration first. The work happens either way, so a scope that excludes
diagnosis saves nothing and only makes the answer sound like a brush-off at the
exact moment the customer has been done a favour. Committing to diagnosis and
declining remediation costs nothing already being spent, and buys the tone.

**Private and third-party plugins are excluded** on firmer ground. §1690
anticipates private organization-specific plugins converging on the same
contract, and ADR-0112 placed every discovery plugin in Community. Someone
else's code written against a published contract is not something one person can
support, and saying so protects the contract rather than the customer.

**No indemnification**, and this is where ADR-0129 bites. That ADR chose DCO
sign-off over a CLA, correctly, because Apache-2.0 §2 already grants the
sublicensable copyright licence a CLA would be bought for. But indemnity is the
*other* thing a CLA is sometimes bought for: a warranty chain. With DCO the
maintainer never owns a contributed patch — ADR-0130 puts the holder at The
Nodqora Authors — and the only assurance about a contributor's right to submit
is that contributor's own personal certification. An indemnity would mean
warranting to a bank the provenance of code the warrantor does not own, backed
by a volunteer's sign-off line.

The load-bearing reason is not the payout. **An IP indemnity is primarily a duty
to defend, and defence is unbounded.** A cap at fees paid limits the cheque at
the end and does nothing about the eighteen months of counsel before it.
Accepting a duty one cannot perform is worse than declining one one cannot
afford. Apache-2.0 §§7–8 already disclaims warranty and liability for Community;
Enterprise does not silently take on more than the free edition does, and the
operative text belongs in the Enterprise EULA, which this map holds out of
scope.

Rejected, and why:

- **Best-effort remediation on the customer's side** for first-party plugin
  configuration. More valuable to the customer, and the natural way a
  relationship drifts into unpaid consulting one favour at a time.
- **Reproducible defects in Nodqora only.** Cleanest to honour, and it reads as
  hostile the first time someone's Kafka plugin goes blind.
- **Indemnification capped at fees paid in the preceding twelve months.** The
  standard commercial shape, and it would unlock some procurement. It still
  carries the uncapped duty to defend, which is the part that cannot be borne.

## Consequences

- **The honesty layer is now support infrastructure.** Every mark that says *we
  could not look* is a ticket not filed, or a ticket answered in one line.
  Degrading ADR-0083, ADR-0088, ADR-0104 or ADR-0106 raises support cost
  directly, which gives those ADRs a second constituency.
- **Together with ADR-0139, this loses the regulated enterprise buyer.** Latest
  version only fails the change window, no remediation fails the team that wants
  a vendor's hands on their cluster, and no indemnity fails procurement
  outright. Each is individually correct at one maintainer; jointly they say
  **Nodqora Enterprise is not yet sellable into a regulated enterprise's
  procurement process.** That is one deliberate posture rather than three
  accidents, and it is recorded as one so it can be reversed as one.
- **"Diagnosis to the boundary" is unmeasurable**, so it is a statement of
  practice rather than a term with an edge. That is accepted: the alternative
  is a measurable term that would be wrong.
- **Revisit trigger.** The first deal lost explicitly on these terms — named as
  such by the buyer, not inferred. One reopens nothing; a second one reopens
  ADR-0139 and this ADR together, and the honest response is an entity with
  insurance behind it rather than a redrafted clause.
