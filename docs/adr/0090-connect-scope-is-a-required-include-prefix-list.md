# ADR-0090: Connector scope is a required include-prefix list with exact-name suppression, and `connect` gets no in-band route

- **Status**: Accepted
- **Date**: 2026-09-04
- **Ticket**: [Unowned plugin-scope gaps: `connect` suppression and the Kubernetes type default](https://github.com/fredskor/nodqora/issues/20)
- **Amends**: ADR-0042

## Context

ADR-0036 makes every connector a node. ADR-0037's prefix list scopes **topics**
only, and ADR-0042's `connect` config has `url`, `auth`, `workload`, `links` and
two cadences — no filter of any kind. `connect`'s scope is therefore one whole
cluster, and a shared Connect cluster running two hundred connectors puts two
hundred nodes on the payments canvas.

[#12](https://github.com/fredskor/nodqora/issues/12) surfaced this and did not
own it. ADR-0051 already fixed **where** the fix lives: suppression stays
per-plugin and subtractive, and a cross-plugin veto was declined because it
would be the fold's only negative assertion. So this is a `connect` config
question, not a merge question.

What was undecided is **which of two precedents governs**, because the map has
answered this same question twice in opposite directions, both times from the
same principle:

- **ADR-0031 (Kubernetes) chose subtraction.** Narrowing fails toward a
  *missing* node, which under ADR-0004 is manufactured drift; subtraction fails
  toward a visibly-wrong *extra*.
- **ADR-0037 (Kafka topics) chose a required include-prefix** — narrowing,
  adopted anyway — because a Kafka cluster has no namespace to subtract from at
  a countable size, and "all topics" is exactly the cluster-wide mode ADR-0035
  rejected.

Connect sits between them: no namespace (like Kafka), but frequently a dedicated
cluster (like a namespace).

## Decision

**Mirror ADR-0037. Connector scope is a required, non-empty include-prefix list,
with an exact-name `ignore` as the escape hatch.**

```yaml
connect:
  url: https://connect-prod.internal:8083
  connectors:
    include: [payments-]              # required, non-empty, no empty-string element
    ignore: [payments-debug-reprocessor]   # exact connector names (ADR-0031)
  # ... auth, workload, links, cadences unchanged (ADR-0042)
```

Matching is exact leading-substring, as in ADR-0037. **No regex, no glob, no
wildcard, no default.** Startup fails on an empty or absent `include`.

**A zero-match include prefix is a named `PARTIAL` reason.** This replaces
ADR-0042's coarser "a configured cluster with zero connectors", which under
ADR-0047's generalization was the only zero-output reason `connect` had: the
prefix names *which* part of the scope went dark, where the cluster-level reason
could only say that all of it did.

**There is no in-band route.** Nothing is read out of a connector's own config
to control node emission — no `topology.io.ignore` key, no annotation analogue.
`connect` remains a single-route plugin.

### The tie-breaker

ADR-0037 rejected enumerated names for topics on one stated ground: *"topics are
created constantly, so a new pipeline topic is silently missing. Discovery that
requires you to name what you are discovering is not discovery."*

**That reason does not hold for connectors.** A connector is a rare, deliberate,
operator-authored deployment, not a thing pipelines spawn. So the option ADR-0037
had to reject is, here, harmless — which is what lets one shape cover both the
shared and the dedicated cluster. On a dedicated cluster with no naming
convention the prefix list gracefully degenerates into an enumeration of
connector names, and that degenerate form is acceptable *because of the rarity
property*, in a way it was not for topics.

### Why no in-band route

ADR-0031 gave Kubernetes two routes and was explicit that neither alone
sufficed. Its argument does not transfer, on two counts.

**ADR-0041's line, crossed from the other side.** ADR-0041 had `connect` parse
the `topics` key **alone**, refusing `iceberg.tables`, because *"`topics` is
Connect's vocabulary and `iceberg.tables` is a third party's."* A
`topology.io.ignore` key inside a connector config is *Nodqora's* vocabulary
inside a third party's document — the same line, and worse, because it is a key
that must be **invented** rather than one that already exists.

**Default-out needs no escape hatch from the object.** ADR-0031's second route
exists for objects you *cannot edit* — Helm- and operator-installed
infrastructure that reverts your annotation. There is no equivalent class here:
`kubernetes` was default-in, so an un-editable object had to be subtractable
from somewhere; `connect` is default-out, and a connector you did not deploy is
excluded by simply not naming its prefix.

## Consequences

- **All three code plugins now have a required, wildcard-free scope**:
  enumerated namespaces (ADR-0035), topic prefixes (ADR-0037), connector
  prefixes. `connect` was the last plugin whose scope had a default, and the
  default was "everything". `CONTEXT.md`'s claim that every scope knob is
  required, enumerated and wildcard-free becomes true rather than nearly true.
- **The `ignore` list has a narrower job here than in ADR-0031.** There it
  removed infrastructure you did not want as a node; here it removes an
  *accidental prefix match* — a connector that shares your prefix and is not
  yours. That is a nodqora-config mistake being corrected in the nodqora config,
  which is why the PR-friction cost below is acceptable.
- **The cost, recorded:** a connector inside an owned prefix that should not be
  a node can only be removed from the nodqora config repo. That is precisely the
  "PR against another team's repo" friction ADR-0031 called out and solved with
  a second route. We accept it because the class of thing being removed is
  different — see above — and because the alternative was inventing a key.
- **A connector named outside the convention is silently absent**, the same
  residual ADR-0037 accepted for topics, and mitigated the same way: the
  zero-match prefix `PARTIAL` catches the case where the *whole* prefix is
  wrong, not the case where one connector was misnamed.
- **A scope-by-topics route was considered and rejected.** Scoping connectors by
  the topics they touch — `connect` reading the same `payments.` prefix from its
  own config, so no cross-plugin read and no ADR-0012 violation — is
  semantically the most honest option available: a connector on payments topics
  *is* a payments connector. It fails on ADR-0041: `connect` parses the `topics`
  key alone, and **source connectors have no `topics` key**, so every source
  connector would fall out of scope. That is a missing-node failure, ADR-0031's
  forbidden direction, arrived at through the most attractive door on offer.
- `docs/reference-pipeline.md` §7 is amended with the scope config. Both fixture
  connectors match `payments-`, so the inventory is unchanged.
