# ADR-0120: An unlicensed install freezes the control path and keeps the data path

- **Status**: Accepted
- **Date**: 2026-09-05
- **Ticket**: [License-key and gating mechanism](https://github.com/fredskor/nodqora/issues/44)

## Context

ADR-0119 makes expiry the only claim the code acts on. What *acting on it* means
is this decision, and it is the one with a security trap in it.

[The survey](https://github.com/fredskor/nodqora/issues/41) found every product
fails soft and converges on a single rule: **freeze the control path, preserve
the data path.** Kong goes read-only on expiry — the Admin API is frozen while
the proxy keeps forwarding traffic. Grafana keeps existing dashboards working
and blocks only the creation of new roles and reports. Nobody hard-stops.

Two Nodqora specifics push the same way, harder:

- §77 names the product's main risk as **a diagram engineers stop using**. A
  hard stop routes a billing event into an incident, taking the map away at the
  moment it is most wanted.
- ADR-0116 puts the journal's write inside the fold's atomic commit. A journal
  that switches itself off must degrade to a **clean pass-through and never to
  an error**, because a failing journal write rolls Community's fold back.

## Decision

**Missing, malformed and expired are one state — *unlicensed* — and the
application always boots.**

**Frozen:** creating or changing roles, group mappings and view scopes; creating
or changing monitoring rules and policies; changing retention configuration; the
AI assistant.

**Alive:** the canvas, health refresh, the fold, journal writes and reads, audit
capture and aggregation, and **every scope already configured, still enforced.**

That last item is where Nodqora inverts the survey's rule. For Kong and Grafana,
failing soft means restricting less. Here view-scoping *is* the thing sold as
security, so relaxing it on expiry means the install quietly widens what every
user can see. **Fail soft never means fail open.**

There is **no exception for loosening**. An operator locked out by an
over-restrictive scope on a lapsed licence recovers with a new key, or by
deploying the Community image — which has no view-scoping by design (ADR-0109)
and restores everyone's sight. ADR-0114 already makes upgrading a redeploy;
downgrading being one too is symmetric and costs no code.

Rejected, and why:

- **Refusing to boot without a key.** Unambiguous during installation, but it
  makes a misplaced Kubernetes Secret an outage, and it needs a second state
  that ADR-0121's grace window would then have to reason about.
- **Degrading to Community behaviour.** The tidy story — stop paying, get the
  free edition — and it fails open on view-scoping, the one Enterprise feature
  whose failure is a disclosure.
- **A directional predicate permitting loosening.** Every configuration type
  would need its own definition of *looser*, and one wrong definition is a
  silent privilege change in an install sold on RBAC.
- **A break-glass flag disabling scoping in place.** It recovers without a
  redeploy, at the price of shipping a documented switch that turns the security
  feature off to anyone who reads the manual.

## Consequences

- **A lapsed install keeps journalling**, so re-licensing leaves no hole in a
  history sold as durable. That is more generous than the commercial instinct
  and it is the safe reading of ADR-0116, where the alternative failure mode is
  rolling back Community's fold.
- **Every Enterprise configuration surface needs a frozen mode**, which is work
  spread across each feature rather than one check in one place. Each feature
  owns its own freeze, and nothing centrally verifies that it has one.
- **The recovery path is a redeploy**, so an install whose licence lapses
  mid-incident is one image swap away from sight — but that swap is an act
  nobody rehearses. The Enterprise runbook owns it.
- **None of this is testable from this repository.** It lives entirely in the
  private assembly, which means the guarantee is prose here and a test there.
- **Revisit trigger.** A renewal that lapses past ADR-0121's grace window with
  the install still in daily use. That is evidence the frozen set is drawn
  around things nobody changes, and the answer is to move an item from *alive*
  to *frozen* — not to start hard-stopping.
