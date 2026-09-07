// SPDX-License-Identifier: Apache-2.0
import { foldKey } from '../api/keys'
import type { GraphEdge, GraphNode } from '../api/types'

/**
 * ADR-0016's central claim, made executable: **plain longest-path layering over flow-directed edges
 * reproduces the reference pipeline's documented shape, with no hand-placed positions.**
 *
 * Because ADR-0002 stores every edge flow-directed, this needs no per-relation branching — the
 * consumer-subject relations (`CONSUMES_FROM`, `SOURCES_FROM`, `QUERIES`) are already the right way
 * round by the time they reach here. ADR-0002 paying off a second time.
 *
 * Ownership was the contested alternative axis and lost on three structural grounds, the load-bearing
 * one being that `ownerKey` is optional and sparsely populated: a layout whose primary axis is
 * frequently null degrades into one large "Unowned" lane, and degrades *silently* — looking correct
 * on a curated fixture and wrong in production. Nothing here reads `ownerKey`.
 */

export interface Placement {
  key: string
  /** Layer index. This, not the pixel position, is what the golden documents assert. */
  column: number
  row: number
}


/**
 * Longest path from any source. A node with no incoming edge sits at column 0; every other node
 * sits one past its deepest predecessor, which is what puts `trino-analytics` two hops past the
 * last discovered node rather than beside it.
 */
export function columnsOf(nodes: Pick<GraphNode, 'key'>[], edges: Pick<GraphEdge, 'fromKey' | 'toKey'>[]) {
  const predecessors = new Map<string, string[]>()
  nodes.forEach((node) => predecessors.set(foldKey(node.key), []))
  edges.forEach((edge) => {
    const to = foldKey(edge.toKey)
    const from = foldKey(edge.fromKey)
    if (predecessors.has(to) && predecessors.has(from)) predecessors.get(to)!.push(from)
  })

  const columns = new Map<string, number>()
  // `visiting` makes a cycle terminate rather than recurse forever. The fixture has none, and the
  // model permits one — a node in a cycle simply stops contributing depth to itself.
  const visiting = new Set<string>()

  const depth = (key: string): number => {
    const known = columns.get(key)
    if (known !== undefined) return known
    if (visiting.has(key)) return 0
    visiting.add(key)
    const incoming = predecessors.get(key) ?? []
    const column = incoming.length === 0 ? 0 : Math.max(...incoming.map(depth)) + 1
    visiting.delete(key)
    columns.set(key, column)
    return column
  }

  nodes.forEach((node) => depth(foldKey(node.key)))
  return { columns, predecessors }
}

/**
 * Greedy slot assignment, so a branch keeps its predecessor's row.
 *
 * A node prefers the topmost row any predecessor occupies and takes the first free row at or below
 * it. On the fixture that keeps the main line straight and drops the Iceberg branch one row at the
 * fan-out, and staging — which has no fan-out — re-flows to a single straight line with no layout
 * special-casing at all. Drift as absence needs none.
 */
export function layout(
  nodes: Pick<GraphNode, 'key'>[],
  edges: Pick<GraphEdge, 'fromKey' | 'toKey'>[],
): Placement[] {
  const { columns, predecessors } = columnsOf(nodes, edges)
  const spelling = new Map(nodes.map((node) => [foldKey(node.key), node.key]))

  const byColumn = new Map<number, string[]>()
  columns.forEach((column, key) => {
    const bucket = byColumn.get(column) ?? []
    bucket.push(key)
    byColumn.set(column, bucket)
  })

  const rows = new Map<string, number>()
  const placements: Placement[] = []

  for (const column of [...byColumn.keys()].sort((a, b) => a - b)) {
    const taken = new Set<number>()
    const preferred = (key: string) => {
      const known = (predecessors.get(key) ?? [])
        .map((predecessor) => rows.get(predecessor))
        .filter((row): row is number => row !== undefined)
      return known.length === 0 ? 0 : Math.min(...known)
    }

    // Ordering by preference first keeps placement independent of the order nodes arrived in; the
    // key is the tiebreak, so the result is a function of the graph and nothing else.
    const inColumn = [...(byColumn.get(column) ?? [])].sort(
      (a, b) => preferred(a) - preferred(b) || a.localeCompare(b),
    )

    for (const key of inColumn) {
      let row = preferred(key)
      while (taken.has(row)) row += 1
      taken.add(row)
      rows.set(key, row)
      placements.push({ key: spelling.get(key) ?? key, column, row })
    }
  }

  return placements
}
