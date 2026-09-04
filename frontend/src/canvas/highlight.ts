import type { GraphEdge } from '../api/types'

/**
 * Upstream/downstream highlighting: a client-side BFS over the loaded graph, unbounded (ADR-0054).
 *
 * There is no traversal endpoint and no `?depth` parameter. A round trip to compute reachability
 * over edges the client is already holding is pure latency, and ADR-0053 puts the whole environment
 * in the client's hands anyway.
 *
 * **Unbounded is the deciding half, not an afterthought.** The fixture's longest path is six edges
 * and `trino-analytics` sits two hops past the last discovered node, so any default depth would hide
 * precisely the declared tail that the permanently-mixed graph exists to prove — and a depth control
 * left on `1` gives no sign that the map is truncated.
 *
 * No per-relation branching, because edges are stored flow-directed (ADR-0002): downstream is always
 * "follow outgoing", upstream is always "follow incoming".
 */

const CASE_FOLD = (key: string) => key.trim().toLowerCase()

export interface Highlight {
  /** The selection itself. */
  selected: string
  upstream: Set<string>
  downstream: Set<string>
}

export function highlightFrom(selectedKey: string, edges: GraphEdge[]): Highlight {
  return {
    selected: CASE_FOLD(selectedKey),
    upstream: reach(selectedKey, edges, 'upstream'),
    downstream: reach(selectedKey, edges, 'downstream'),
  }
}

function reach(startKey: string, edges: GraphEdge[], direction: 'upstream' | 'downstream'): Set<string> {
  const adjacency = new Map<string, string[]>()
  for (const edge of edges) {
    const from = CASE_FOLD(edge.fromKey)
    const to = CASE_FOLD(edge.toKey)
    const [tail, head] = direction === 'downstream' ? [from, to] : [to, from]
    adjacency.set(tail, [...(adjacency.get(tail) ?? []), head])
  }

  const reached = new Set<string>()
  const queue = [CASE_FOLD(startKey)]
  while (queue.length > 0) {
    for (const next of adjacency.get(queue.shift()!) ?? []) {
      // Visited-guard doubles as the cycle guard; the model permits one even if the fixture has none.
      if (!reached.has(next)) {
        reached.add(next)
        queue.push(next)
      }
    }
  }
  reached.delete(CASE_FOLD(startKey))
  return reached
}

/** An edge is on the highlighted path when both of its ends are. */
export function edgeIsHighlighted(edge: GraphEdge, highlight: Highlight | null): boolean {
  if (!highlight) return false
  const inPath = (key: string) => {
    const folded = CASE_FOLD(key)
    return folded === highlight.selected || highlight.upstream.has(folded) || highlight.downstream.has(folded)
  }
  return inPath(edge.fromKey) && inPath(edge.toKey)
}
