// SPDX-License-Identifier: Apache-2.0
import { foldKey, sameKey } from '../api/keys'
import type { GraphEdge, GraphNode } from '../api/types'

/**
 * ADR-0016: **the cross-team boundary is an edge treatment**, applied where
 * `owner(from) ≠ owner(to)` — not a layout axis.
 *
 * This is the clause that decided the layout. Swimlanes by owning team lost on three structural
 * grounds, and the third was that the insight lanes deliver is *one edge predicate*: the hand-off is
 * `owner(from) ≠ owner(to)`, which does not need the Y axis to express. Marking the edge is that
 * argument cashed, and the pipeline crossing from `payments-platform` to `data-platform` at the
 * connectors is the entire reason the product exists.
 *
 * An edge with an unowned end is **not** a crossing. `ownerKey` is optional and sparsely populated
 * (ADR-0016) and a dangling one is ordinary (ADR-0048), so treating absence as difference would mark
 * most of a real graph and mark nothing usefully.
 */
export function crossesTeams(edge: GraphEdge, ownerByFoldedKey: Map<string, string | null>): boolean {
  const from = ownerByFoldedKey.get(foldKey(edge.fromKey))
  const to = ownerByFoldedKey.get(foldKey(edge.toKey))
  if (from == null || to == null) return false
  return !sameKey(from, to)
}

export function ownersByNodeKey(nodes: GraphNode[]): Map<string, string | null> {
  return new Map(nodes.map((node) => [foldKey(node.key), node.ownerKey]))
}
