# ADR-0123: The unit is the deployment, and nothing observed is counted

- **Status**: Accepted
- **Date**: 2026-09-05
- **Ticket**: [Pricing unit and trial](https://github.com/fredskor/nodqora/issues/45)

## Context

ADR-0112 forecloses per-connector pricing and ADR-0118 forecloses anything
technology-specific, which removes *per cluster* — a Kubernetes noun — from the
options this ticket inherited. ADR-0119 then declined to enforce any count, so
picking a countable unit buys reporting rather than leverage.

What remained: per seat, per connected environment, flat per installation.

[The open-core survey](https://github.com/fredskor/nodqora/issues/41) found the
seat is the category default and that the exception explains it. Roadie — the
closest published precedent in product shape — charges **$24 per developer with
entity count explicitly unlimited**, but Roadie is a developer portal, a
mandatory tool everyone must log into. Kong, the survey's one infrastructure
tool, charges **per gateway control plane** rather than per seat or per node
because its value scales with the deployment, and it *retired* its per-plugin
axis exactly as ADR-0112 forecloses per-connector here.

Nodqora is not mandatory. §77 names the product's main risk as a diagram
engineers stop using.

## Decision

**An Enterprise customer pays a flat annual subscription per installation. The
unit is a deployment of Nodqora, and nothing Nodqora observes is ever counted.**

Nodes, environments, plugins, connected systems and authenticated users are
unlimited on both editions, permanently. There is exactly one countable object
and it is the thing the customer deploys, not the estate they own.

An **installation** is one deployment serving one set of users:

- **Replicas are one installation.** Charging for replicas taxes availability
  and gives an operator a licensing reason to run a single pod.
- **Non-production copies are free and unlimited** — upgrade rehearsal,
  sandboxes, the instance someone runs to test a config change. This is not the
  environments question: prod and staging as environments *inside* one Nodqora
  are already free above; this is a second deployment of Nodqora itself.
- **A DR standby is free while passive**, and an installation once it serves
  users.

Annual subscription rather than a perpetual licence: ADR-0119 built a clock, and
a key with no expiry gates nothing. The customer's benefit accrues daily, as
does the cost of keeping six plugin families working against upstream APIs.

Rejected, and why:

- **Per seat.** It taxes the read-only viewer — the casual looker whose habit is
  the difference between a live map and §77's dead diagram. The survey's
  infrastructure tools have abandoned seats for the same reason.
- **Per connected environment.** It tracks organisational scale, which is what
  the buyer rule says Enterprise is for, but it taxes adding staging and dev and
  so prices the environment comparison ADR-0107 already gives away.
- **Per node.** The instinct the survey argues hardest against: a unit that
  grows with the customer's topology penalises discovery, which is the thing
  Nodqora exists to do.
- **Perpetual licence with annual maintenance.** The classic self-hosted shape
  and preferred by some air-gapped buyers, but it misprices a product whose
  value is continuously refreshed truth about a live estate.

## Consequences

- **There is no in-install expansion revenue.** A twenty-person startup and a
  ten-thousand-engineer bank pay for the same object. Expansion comes from
  genuinely separate estates — regions, air-gapped enclaves, acquired
  subsidiaries — which is a weaker curve than seats, and it means the first
  large customer is underpriced unless the band is negotiated at contract time.
- **The model is one sentence long**, which is the payoff. A platform team can
  be told that adding staging costs nothing and discovering another 400 services
  costs nothing, without qualification.
- **"Serves users" is a soft edge.** ADR-0121 forbids phoning home, so a
  customer running six regional installations and calling five of them standbys
  is undetectable. This is a contract term, not a control — the posture
  ADR-0119 already took.
- **Revisit trigger.** A customer whose Enterprise value plainly scales with
  something inside the install rather than with the install itself — most
  plausibly a consultancy or managed-service provider running many clients'
  estates in one deployment. That is an argument the unit is measuring the wrong
  object, and it reopens this ADR together with ADR-0107.
