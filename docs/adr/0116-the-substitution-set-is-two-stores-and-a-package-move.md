# ADR-0116: The substitution set is two store interfaces and one package move

- **Status**: Accepted
- **Date**: 2026-09-05
- **Ticket**: [The extension contract Enterprise plugs into](https://github.com/fredskor/nodqora/issues/43)

## Context

ADR-0115 says Enterprise extends by substitution. Substitution needs something
substitutable, and today there is nothing: **every component in
`nodqora-core` is a concrete `@Component` class and not one of them is an
interface** — `SnapshotStore`, `GraphStore`, `NodeStateStore`, `GraphDocuments`,
and the two commit points `DiscoveryFoldRunner` and `StateFoldRunner`, which are
two because ADR-0003's wall runs between them.

Walking the ledger's Enterprise column, most items need nothing. Continuous
monitoring, the policy engine and the AI assistant are pure addition — new
beans, new schedulers, new routes, which a separate assembly declares for
itself. Group synchronisation reads claims off the principal Community already
establishes. Audit aggregation consumes events Community emits anyway, because
ADR-0111 made capture free.

Two items need something. **The journal** must see what the fold committed, and
**view-scoping** must sit in the read path.

## Decision

**The backend extension contract is three changes, and no more.**

1. **`GraphStore` becomes an interface**, with `JdbcGraphStore` as Community's
   only implementation.
2. **`NodeStateStore` becomes an interface**, likewise. Enterprise binds
   delegating implementations that write through and journal; both halves of the
   read model are covered because the journal is topology history *and* the
   incident timeline.
3. **The read-model projection moves out of the HTTP layer.**
   `GraphDocuments`, `ApiDocuments` and the document records move to
   `io.nodqora.core.read`. `io.nodqora.core.api` is left holding only
   `GraphController` and `ApiExceptionHandler`.

Enterprise omits `io.nodqora.core.api` from `scanBasePackages` and ships its own
controller, which calls the concrete `GraphDocuments` and filters the document
against the principal's scope before serving it.

Rejected, and why:

- **`GraphDocuments` as a fourth interface.** The argument for it is
  defence-in-depth — scope low, so hidden rows are never materialized in the
  process serving the request — and it does not survive the code. **The stores
  also serve the fold, which reads every row in the environment by definition**
  (ADR-0043), so scoping cannot live at the store layer without breaking the
  fold; and `GraphDocuments` reads the whole environment regardless. An
  interface there buys a wrapping point, not a narrower read.
- **`SnapshotStore` and the fold runners**, so Enterprise could journal inputs
  and recompute past graphs by re-folding. The fold's *output* is the history
  the ledger sells; re-folding inputs is a promise that breaks the first time
  the fold's code changes or an ADR-0080 `payload_version` discard lands.
- **Excluding `GraphController` by type filter**, which costs Community nothing
  and is therefore tempting under ADR-0115. Refused because the exclusion
  boundary should be a package: a type filter breaks silently when core gains a
  second controller, and the failure mode of *Enterprise forgot to exclude the
  new controller* is an unscoped route serving every node in every environment,
  in an install sold on RBAC.
- **Enterprise mounting its scoped API on a different path** and leaving
  Community's controller running. Same leak, by construction rather than by
  omission.

The package move is defensible without mentioning Enterprise, which is
ADR-0115's test: **the read-model projection is not the HTTP layer.**
`GraphDocuments` computes ADR-0056's three read-time projections; that it
currently sits beside the controller is an accident of a three-endpoint API.

## Consequences

- **Enterprise can stop Community's graph updating.** `GraphStore.write` runs
  inside the fold's atomic commit (ADR-0043), so a delegating journal writes in
  that transaction — which is the correct atomicity, and it means a slow journal
  write extends the fold's transaction and a failing one rolls the fold back.
- **Enterprise re-derives document consistency from prose.** Filtering a node out
  of a `GraphDocument` dangles edges, dangles `ownerKey`, orphans
  `typeDescriptors`, and invalidates all three read-time projections. Those rules
  are Community's, written in ADR-0047, ADR-0053 and ADR-0077, and Enterprise
  implements them again from the outside.
- **Full documents materialize before filtering**, so a view-scoping bug is a
  data leak rather than a missing row. That follows from the read model rather
  than from this decision, but Enterprise owns the consequence.
- **Two interfaces with one implementation each are a smell**, and no test can
  distinguish them from the Enterprise holes ADR-0115 refused.
- **Core is now packaged so its layers are separately scannable**, a constraint
  on future core code that cannot be written as an ArchUnit rule.
- **Revisit trigger.** A third component needing an interface, or a Community
  feature that wants server-side drift — either reopens the fold-commit event
  that ADR-0115 refused for want of a Community consumer.
