# ADR-0111: Audit captures free and aggregates paid

- **Status**: Accepted
- **Date**: 2026-09-05
- **Ticket**: [The Community/Enterprise feature ledger](https://github.com/fredskor/nodqora/issues/42)

## Context

Product plan §43 lists **audit logging** as a possible Enterprise feature.
[The survey](https://github.com/fredskor/nodqora/issues/41) found the category
splits the same way in three unrelated products and called it the cheapest
concession on the board: Cerbos gives away complete decision logging to local,
file and Kafka backends and sells only the fleet-collection backend; Backstage's
Auditor core service captures free while Spotify sells query and retention;
GitLab ships audit events at every tier with only sign-in events free.

The survey's argument for why this is cheap transfers exactly. **The self-hoster
who wants audit logs already owns the disk they would be written to.** Gating
capture does not create a sale, it creates a customer who pipes application logs
through their existing log stack and resents having been asked.

Nodqora's complication is that there is currently very little to audit. The API
is entirely read-only (`CONTEXT.md`, the read API section), so an audit trail
here is a record of who *looked* at what — which is thin while every
authenticated user sees everything (ADR-0109) and becomes meaningful only once
view-scoping exists. Deferring the decision until then was available and
rejected: the shape is already known from three products, and deciding it now
costs one ADR while deciding it later risks capture landing behind the line by
default.

## Decision

**Capture is Community. Aggregation is Enterprise.**

- **Community** emits structured audit events — complete, not sampled — to
  stdout, to a file, or to a log backend of the operator's choosing. Whatever
  Nodqora records, a Community install can read.
- **Enterprise** provides the queryable surface: search over audit events,
  retention policy, export, and collection across more than one install.

Cross-install collection is Enterprise by ADR-0108's fleet-rollup category as
well as by this ADR, and the two agree.

## Consequences

- **The concession is real and costs nothing.** A Community operator can satisfy
  a compliance question with `grep` and the log stack they already run. That is
  the outcome in every product the survey examined, whether or not the vendor
  intended it.
- **What Enterprise sells is not the record but the ability to ask it
  questions**, which is ADR-0107's rule arriving from a different direction:
  the events are observation and free; searching them over time is the
  accumulating thing and paid.
- **Audit is nearly empty until ADR-0109's view-scoping ships.** The ledger
  records the split now, and `docs/editions.md` must not present a substantial
  audit capability on either side until there is one.
- **Cost: "complete, not sampled" is a promise that constrains later
  optimisation.** If audit volume becomes a performance problem, the obvious
  remedy — sample in Community, complete in Enterprise — is closed by this ADR.
  The remedy left open is to reduce what is audited for everyone, which is the
  honest one.
- **Cost: a Community operator with a log stack has most of what Enterprise
  sells.** Search and retention over shipped logs is what Loki or Elasticsearch
  already do. Enterprise's audit surface therefore competes with tools the buyer
  owns, and its case is integration with view-scoping rather than storage — a
  narrower case than §43 implied.
- **Revisit trigger.** Audit volume becoming a material cost to emit, or
  adoption evidence that buyers want the audit surface independently of RBAC —
  which would mean it is a product rather than a feature of authorization.
