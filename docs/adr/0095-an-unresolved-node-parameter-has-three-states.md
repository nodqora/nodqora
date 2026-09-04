# ADR-0095: An unresolved `?node=` has three states, chosen like ADR-0087's, and the parameter is retained

- **Status**: Accepted
- **Date**: 2026-09-04
- **Ticket**: [Frontend routing and deep-linking](https://github.com/fredskor/nodqora/issues/21)

## Context

A deep link pointing at a node that is not in the loaded graph is the **expected**
case, not the edge case. Staging is missing three of production's ten fixture
nodes, and ADR-0047 deletes immediately, so a link can name a node that vanished
one poll ago.

ADR-0070 answered the same question for search — *"No node in **staging** matches
`trino`"* — on the grounds that "no results" and "no such node anywhere" otherwise
render as **the same pixels**. A silently-ignored `?node=` is worse: it renders
identically to a link that never carried a node at all, so the recipient cannot
tell a dead link from a plain graph link.

But that sentence is not always true. ADR-0088 exists because a mixed-cold
environment *"draws a graph that looks whole"* — with `yaml` unreported, the
fixture's four declared-only nodes are silently missing, and `trino-analytics` is
one of them. Asserting "no node matches `trino-analytics`" there is a confident
false negative, printed directly beneath a banner reading *"this graph may be
incomplete."*

## Decision

The drawer's response to an unresolved parameter is chosen from `plugins[]`, by
the same method ADR-0087 used for the canvas — **distinct states, never a headline
with a retracting subline**:

| environment condition | drawer |
|---|---|
| empty graph and cold (ADR-0087 states 1–2) | **no drawer**; the canvas empty state is the whole answer |
| every `outcome` is `COMPLETE` | **"No node in `staging` matches `trino-analytics`."** (ADR-0070's sentence) |
| any `outcome` is `null`, `PARTIAL` or `FAILED` | **"`trino-analytics` is not in what has been read of `production`."** |

**The parameter is retained in the URL in all three cases.**

## Consequences

- **The third sentence is a different claim, not a hedged first one.** ADR-0087
  rejected a single state with an explanatory subline because *"a subline does not
  retract a headline"*; the same discipline applies here, and it is what stops the
  drawer contradicting the ADR-0088 banner directly above it.
- **The cold case opens no drawer because the canvas is already unmissable.** This
  is ADR-0088's reasoning transposed — it declined a banner over an empty canvas
  as *"a second copy of one sentence"*.
- **Retaining the parameter is ADR-0047's self-healing fold cashed in.** That ADR
  records deletion as non-destructive because *"the next clean poll restores a node
  **exactly**"*. Stripping the parameter would permanently discard the reader's
  request in order to tidy an address bar; keeping it means the very next poll that
  brings the node back selects it, with no second click.
- **Retention also makes the state live in both directions.** A selected node that
  leaves a snapshot mid-session transitions to the matching sentence above rather
  than the drawer silently closing — the same fact, told rather than hidden.
- The cost is a URL that can persist a request nothing will ever satisfy — a link
  to a node deleted for good keeps its parameter forever. It is the same cost
  ADR-0070 accepted at the point of use: the honest report of an absence is worth
  more than a tidy URL, and the sentence names the scope so the reader can act on it.
