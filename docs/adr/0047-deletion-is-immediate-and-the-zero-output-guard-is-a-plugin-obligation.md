# ADR-0047: Deletion is immediate; the zero-output guard is a plugin obligation

- **Status**: Accepted
- **Date**: 2026-09-02
- **Ticket**: [Discovery-engine merge semantics](https://github.com/fredskor/nodqora/issues/12)
- **Amends**: ADR-0035, ADR-0042

## Context

The ticket asks whether N consecutive absences should be required before
deletion, and research [#4](https://github.com/fredskor/nodqora/issues/4) offers
DataHub's three portable fail-safes: refuse deletions when the source reported a
failure, when it produced nothing, or when the delta exceeds a threshold.

Four routes to a snapshot legitimately or illegitimately missing nodes are on
record: research [#5](https://github.com/fredskor/nodqora/issues/5)'s Kafka ACL
tightening, ADR-0031's over-broad `ignore`, ADR-0032's typo'd `topology.io/node`,
and ADR-0037's deliberately edited prefix list.

## Decision

**Absence from a `COMPLETE` snapshot deletes immediately. There is no
N-consecutive rule and no delta threshold in the engine.**

**The zero-output guard is a plugin obligation, generalized: an enumerated scope
unit that yields zero nodes ⇒ `PARTIAL`.**

- `kafka` already has it — ADR-0042's "a configured include-prefix matching zero
  topics".
- **`kubernetes` gains it** (amending ADR-0035): an enumerated namespace that
  produced zero node-producing workloads.
- **`connect` gains it** (extending ADR-0042): a configured cluster with zero
  connectors.

## Consequences

- **Deletion under ADR-0043 is non-destructive and self-healing.** This is the
  fact that changes the calculus. In DataHub and Backstage a delete destroys
  *authored* metadata, which is why they all carry fail-safes. Here the fold holds
  no accumulated state, so the next clean poll restores a node **exactly**. The
  cost of an over-eager delete is a graph that is wrong for one discovery
  interval, visibly.
- Against that, over-**retention** is permanent and lands on the product's
  headline feature. ADR-0004 makes drift-is-absence the mechanism by which
  staging's missing Iceberg branch reads off the canvas; a phantom node is a wrong
  answer to the one question the environment switcher exists to answer.
- **N consecutive absences guards against transient blindness, and every named
  threat is persistent.** None of the four clears itself. N polls delay the
  identical deletion while making the legitimate case — someone deleted a
  Deployment — take N intervals to appear.
- **A delta threshold fires hardest on ADR-0037**, which explicitly says editing
  the prefix list is ordinary operator work producing a snapshot legitimately
  missing nodes. A guard whose main effect is to block routine configuration
  changes is worse than no guard.
- **The engine cannot tell an empty scope from an empty result; the plugin can.**
  That is why the guard belongs in the plugin, and why the symmetry had to be
  fixed — `kafka` had the rule and the other two did not, for no reason beyond
  which ticket wrote them.
- **Residual, unchanged and not hidden:** an ACL tightening that hides *some*
  topics under a prefix that still matches others is indistinguishable from a
  decommissioned pipeline. No guard at any layer detects it. Those nodes leave the
  graph and return when the ACLs are fixed.
