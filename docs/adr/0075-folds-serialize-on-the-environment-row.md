# ADR-0075: Folds serialize on the environment row

- **Status**: Accepted
- **Date**: 2026-09-03
- **Ticket**: [PostgreSQL schema and persistence](https://github.com/fredskor/nodqora/issues/16)

## Context

ADR-0043 defines the write path as **two atomic units**: the snapshot lands,
then a fold is triggered. Nothing serializes the folds against each other, and
that permits a lost update:

```text
t1  kafka snapshot commits
t2  fold(kafka)   reads store -> {yaml, kubernetes, kafka}
t3  connect snapshot commits
t4  fold(connect) reads store -> {yaml, kubernetes, kafka, connect}
t5  fold(connect) commits          <- correct graph
t6  fold(kafka)   commits          <- overwrites it; connect's nodes gone
```

Both folds are individually correct and order-independent exactly as ADR-0043
promises — **order-independence is a property of the inputs, not of the
commits.** The result is `connect`'s two connector nodes missing for a full five
minutes and self-healing on the next poll. Under ADR-0004 that renders as drift:
two nodes that exist in production, absent from production.

## Decision

**The discovery fold's first statement is
`SELECT 1 FROM environment WHERE key = ? FOR UPDATE`, held to commit.**
`READ COMMITTED` throughout.

**The state fold takes `pg_advisory_xact_lock` on `(environment, 'state')`** — a
separate keyspace, so a thirty-second loop never blocks a five-minute one.

**Both write through `INSERT … SELECT … FROM node WHERE …`** rather than a bare
insert.

## Consequences

- **In-process serialization was rejected because it encodes an assumption nobody
  has made.** Nothing on this map says Nodqora runs as a single instance. A fold
  executor per environment is correct until someone sets `replicas: 2`, and then
  fails silently and intermittently in the shape above — the hardest class of bug
  this system can produce, because ADR-0004 makes a missing node a *legitimate
  reading of the data*.
- **Holding the lock across read and commit is the fix, not the write conflict.**
  The bug is a stale read; a lock taken only at write time would not prevent it.
- Optimistic versioning and `SERIALIZABLE` both work and both cost retry logic —
  a version bump to thread through every fold path, or an exception at commit
  after the whole fold has been computed. Neither buys concurrency worth having:
  the contending folds are for *the same environment*, so serializing them is the
  requirement rather than a lost opportunity.
- **ADR-0050's drop rule becomes structural.** `INSERT … SELECT FROM node` yields
  zero rows for a node a concurrent discovery fold has deleted — the rule enforced
  by the shape of the statement instead of by remembering to catch a foreign-key
  violation.
- Under contention the loser waits for a fold that takes milliseconds at fixture
  scale.
