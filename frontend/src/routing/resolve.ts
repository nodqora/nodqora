// SPDX-License-Identifier: Apache-2.0
import { foldKey } from '../api/keys'
import { emptyStateOf } from '../canvas/emptyState'
import type { GraphNode, PluginOutcome } from '../api/types'

/**
 * ADR-0094: **`?node=` resolves by ADR-0020's own comparison — trimmed and case-folded** — against
 * the `/graph` document the client already holds. ADR-0053 left no per-node route, so resolution
 * happens entirely here.
 *
 * Resolution is unambiguous by construction: ADR-0020's `UNIQUE (environmentKey, lower(trim(key)))`
 * guarantees at most one folded match per environment, so folding introduces no tie to break.
 *
 * **Exact matching was rejected because it would give one string two meanings.** A user pasting
 * `Payments-API` finds the node through search (ADR-0067) and would fail through a deep link — two
 * rules for the same identity model, and the deep link is the half more likely to have arrived from
 * a Kubernetes alert or a hand-typed message.
 */
export const resolveNode = (parameter: string, nodes: GraphNode[]): GraphNode | null =>
  nodes.find((node) => foldKey(node.key) === foldKey(parameter)) ?? null

/**
 * ADR-0094: **on a hit whose parameter differs from the stored key, the URL is rewritten to the
 * stored key** via `replaceState`.
 *
 * `replaceState`, not push, deliberately: ADR-0096 makes selection changes push, and a
 * canonicalization is not a selection change — it must not appear in the Back trail as a second
 * visit to the same node.
 *
 * Canonicalizing is also what keeps ADR-0095's retained parameter meaningful. If the same node had
 * many spellings, the retained string would be one of several and comparisons against it would be
 * spelling-sensitive. One node, one URL.
 *
 * A **miss is left exactly as given**, because there is no canonical form to rewrite it to. This is
 * the one place the app edits a URL the user supplied, and it is confined to a spelling change on a
 * resolved hit.
 */
export const needsCanonicalizing = (parameter: string, resolved: GraphNode | null): boolean =>
  resolved !== null && resolved.key !== parameter

/**
 * ADR-0095: an unresolved `?node=` has **three states, chosen from `plugins[]` by the same method
 * ADR-0087 used for the canvas** — distinct states, never a headline with a retracting subline.
 *
 * A deep link pointing at a node that is not in the loaded graph is the **expected** case, not the
 * edge case: staging is missing three of production's ten fixture nodes, and ADR-0047 deletes
 * immediately, so a link can name a node that vanished one poll ago. A silently-ignored `?node=` is
 * worse than a bad search — it renders identically to a link that never carried a node at all, so
 * the recipient cannot tell a dead link from a plain graph link.
 *
 * | environment condition | drawer |
 * |---|---|
 * | empty graph and cold | **no drawer**; the canvas empty state is the whole answer |
 * | every `outcome` is `COMPLETE` | "No node in `staging` matches `trino-analytics`." |
 * | any `outcome` is `null`, `PARTIAL` or `FAILED` | "`trino-analytics` is not in what has been read of `production`." |
 *
 * The third sentence is a **different claim, not a hedged first one**, and it is what stops the
 * drawer contradicting the ADR-0088 banner directly above it. Asserting "no node matches
 * `trino-analytics`" over a mixed-cold environment is a confident false negative printed beneath a
 * banner reading *"this graph may be incomplete."*
 */
export type UnresolvedState = 'SILENT' | 'NO_SUCH_NODE' | 'NOT_IN_WHAT_WAS_READ'

export function unresolvedStateOf(nodes: GraphNode[], discovery: PluginOutcome[]): UnresolvedState {
  // The cold case opens no drawer because the canvas is already unmissable — ADR-0088's reasoning
  // transposed, which declined a banner over an empty canvas as "a second copy of one sentence".
  if (nodes.length === 0 && emptyStateOf(discovery) !== 'NO_NODES') return 'SILENT'
  return discovery.every((pair) => pair.outcome === 'COMPLETE') ? 'NO_SUCH_NODE' : 'NOT_IN_WHAT_WAS_READ'
}

export function unresolvedSentence(
  state: Exclude<UnresolvedState, 'SILENT'>,
  environmentDisplayName: string,
  parameter: string,
): string {
  return state === 'NO_SUCH_NODE'
    ? `No node in ${environmentDisplayName} matches “${parameter}”.`
    : `“${parameter}” is not in what has been read of ${environmentDisplayName}.`
}
