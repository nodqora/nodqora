# ADR-0051: Merge diagnostics are logs and metrics, not model fields

- **Status**: Amended by [ADR-0091](0091-kubernetes-emits-no-type-default.md)
- **Date**: 2026-09-02
- **Ticket**: [Discovery-engine merge semantics](https://github.com/fredskor/nodqora/issues/12)
- **Amends**: ADR-0008, ADR-0012

> **Amendment (ADR-0091).** The decision below stands; one sentence of its
> rationale does not. It rejected "two snapshots disagree about `type`" as a
> collision detector by citing a **kind-derived default** that ADR-0091 has since
> established never existed. A `kubernetes` type is always an explicit human
> annotation, so such a disagreement is two people contradicting each other on
> purpose, not a human correcting a machine's guess. The conclusion holds by the
> shorter route this ADR already gives: `yaml` plus one code plugin is the
> *designed* case.

## Context

ADR-0020 chose a flat key space **knowing** a Kafka topic named `payments-api`
would merge into the service, and left the reporting decision to this ticket.
Product plan §56 wants manual overrides to survive discovery; §35 wants
relationship confidence.

## Decision

**A cross-plugin key collision is never blocked.** ADR-0020's own argument was
that every qualifier considered turns a mismatch into *two nodes and no error* on
the fixture's most important node; rejecting a contribution reintroduces that by
another route.

**A key carried by two or more *code* plugins' snapshots is logged as a warning
and counted as a platform metric (§59). No model change, no new field, no
`PARTIAL`.**

**§56 is discharged** by ADR-0011 plus ADR-0044: `yaml` is a plugin with top
precedence, so a manual correction wins every scalar on every subsequent poll.
This retires ADR-0008's note that manual overrides *"will need their own
mechanism (a pinned-fields list, or promotion to per-field provenance)"* without
building either.

**§35 has no MVP referent.** ADR-0009 dropped `Edge.confidence` because every MVP
edge is asserted, and ADR-0041 then had `connect` parse the `topics` key verbatim
rather than infer per connector class. There is no inferred edge for a confidence
score to describe. Confirmed dead, not reopened.

**There is no YAML suppression in the MVP.** Suppression stays per-plugin and
subtractive — ADR-0031's exact `kind/name` deny-list, ADR-0037's prefix list,
and ADR-0090's connector prefix list, which closes the `connect` gap this ADR
left open.

**Descriptors come from the stored snapshot, whatever put it there** (clarifying
ADR-0012's *"latest **successful** result"*, written before `PARTIAL` had a
defined effect): a `PARTIAL` contributes its descriptors, a `FAILED` contributes
nothing new. Register-if-absent, first-wins, code plugins before `yaml`, global
scope — all unchanged.

## Consequences

- **The detector is narrower than it first looks.** "Two snapshots disagree about
  `type`" does not work: under ADR-0091 a `kubernetes` type is an explicit
  `topology.io/type` annotation, so YAML overriding it is a **legitimate**
  disagreement between two authors, settled by precedence. What is clean is that `yaml` + any code plugin is the
  *designed* case — literally the fixture's `sources: [yaml, kubernetes]` — while
  two code plugins on one key has no legitimate MVP instance. `kubernetes` makes
  workloads, `kafka` topics, `connect` connectors; the one near-overlap, the
  `kafka-connect` StatefulSet hosting two connectors, ADR-0031 resolved by
  suppression precisely because annotating it onto a connector cannot work for a
  one-to-two mapping.
- **`PARTIAL` is ruled out for reporting a contest** for the reason ADR-0021 gave
  when it sent intra-plugin contests to `metadata` and logs: `PARTIAL` now gates
  deletion (ADR-0046), so routing a *contest* through it would freeze deletions
  across an entire plugin's scope because two keys clashed.
- **No new field is needed, because the collision is already visible.** Backings
  are additive (ADR-0022) and ADR-0023 makes them the alternate-name index the
  inspector renders, so a collided node shows `{kafka, topic, payments-api}` beside
  `{kubernetes, Deployment, payments-api}` in its own backings list. A human sees a
  topic-shaped thing wearing a Deployment. Carrying it in a field would un-trim
  ADR-0009.
- §56 is research #4's finding realized: three of the four surveyed tools make
  overrides survive rediscovery without reading provenance at merge time.
- **YAML suppression would introduce the one thing the fold stays free of: a
  negative assertion.** Every snapshot says only "here is what I found", which is
  why ADR-0043's fold is additive and order-independent. An `ignore:` entry would
  say "and delete what someone else found", which `DiscoveryResult` has no shape
  for and which makes the fold's output depend on a cross-plugin veto — paid for a
  need with **no fixture instance**.
- **Known gap, recorded:** `connect` has no suppression mechanism at all; its scope
  is one whole cluster (ADR-0036), so a shared cluster with 200 connectors puts 200
  nodes on the canvas. That is a *scope* gap belonging to `connect`, and the fix is
  a connector-name filter in its config alongside ADR-0037's — not a veto in the
  engine.
- ADR-0012's provision that "a failed poll must retain the last known set rather
  than clearing it" is no longer a rule to implement; under ADR-0043 it is what the
  store does.
