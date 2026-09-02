# ADR-0064: The environment is `yaml`'s unit of failure, and a contested key is a bug

- **Status**: Accepted
- **Date**: 2026-09-02
- **Ticket**: [YAML topology format](https://github.com/fredskor/nodqora/issues/14)

## Context

`yaml` wins every contested scalar (ADR-0044) and owns seven of the fixture's
nine edges (ADR-0041). ADR-0046 makes `outcome` the snapshot store's transition
function: `COMPLETE` replaces wholesale, `PARTIAL` upserts and retains whole
nodes, `FAILED` changes nothing. ADR-0047 makes deletion immediate and puts the
zero-output guard inside the plugin, because only the plugin can tell an empty
scope from an empty result.

ADR-0061 splits an environment across several files, so both questions arrive
together: what does a broken file do, and what does the same key in two files
mean?

## Decision

**The whole environment is the unit. Anything invalid anywhere in the directory
makes the snapshot `FAILED`, and `FAILED` changes nothing.**

Invalid means: a file that will not parse; an unknown top-level or node key; an
`environment:` that disagrees with the directory (ADR-0061); a missing or
unreadable directory; **or a key declared twice**.

A YAML directory is a single versioned artifact edited as a unit. A half-applied
one is worse than an unapplied one: with `yaml` at the top of merge precedence
and holding four declared nodes and seven edges, a partially-read directory
would let an indentation slip **delete** them. Per-file `PARTIAL` was rejected
for a second reason as well — knowing which keys a broken file owned requires
remembering the previous read, and ADR-0012 made plugins stateless.

**A key declared in two files is an error, not a merge.** Node keys, owner keys
and type names all follow the same rule, and node keys are compared case-folded
(ADR-0020).

ADR-0021's contested-key rule — one node, backings unioned, scalars from the
newest object by `creationTimestamp` — exists because the Kubernetes API is
*not authored*: two objects claiming one key is a situation you observe and must
survive. A YAML directory is authored, so the contest is always a bug, and no
tiebreak is available anyway: files have no `creationTimestamp`, and ordering by
filename would let **renaming a file change a node's type**.

Nor is a merge needed. ADR-0062 puts every edge in the stanza of the node
*doing* the thing, so a cross-team edge never needs the other team's key. The
fixture splits across two team files with **no key appearing in both**.

**A directory that parses but yields no nodes is `PARTIAL`, not `COMPLETE`.**
That is ADR-0047's zero-output guard, with the directory as the scope unit —
symmetric with a namespace holding no workloads and a prefix matching no topics.
Files holding only `owners:` or only `types:` are fine; a directory holding no
node anywhere is an empty scope, not an emptied topology. Deletion still works
by removing a stanza; it does not work by emptying the directory.

Dangling references are **not** errors: an `owner:` with no owner block and a
verb target with no stanza are both ordinary (ADR-0048).

## Consequences

- **One team's typo freezes the other team's changes** for that environment,
  and the graph keeps serving the last good snapshot meanwhile. Accepted: the
  alternative is a typo that deletes nodes, and ADR-0046 already makes `FAILED`
  mean "retain", which is the safe direction for the one plugin whose absence
  is not evidence of anything.
- `FAILED` here is genuinely different from `FAILED` in the observing plugins.
  For `kubernetes` it means the cluster did not answer; for `yaml` it means a
  human wrote something wrong, and the fix is a commit, not a retry.
- Failure messages name the file and the colliding files by name — cheap here,
  because unlike ADR-0051's merge diagnostics the plugin has the filenames in
  hand and nothing needs to reach the model to say it.
- Both halves of the guard are exercised by the prototype: duplicate key, wrong
  environment, unparseable file and missing directory all produce `FAILED`; an
  owners-only directory produces `PARTIAL`.
