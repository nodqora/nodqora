// SPDX-License-Identifier: Apache-2.0
import type { Graph } from '../api/types'
import { docUrl } from '../docs/link'

/** The plugins that can put an edge on the canvas. ADR-0033 leaves `kubernetes` out on purpose. */
export const EDGE_PRODUCERS: ReadonlySet<string> = new Set(['yaml', 'kafka', 'connect'])

/**
 * ADR-0166: **a canvas with nodes, no edges, and no plugin that could draw one says where edges come
 * from.**
 *
 * ADR-0033 has `kubernetes` emit no edges, so a cluster-only environment renders every workload on
 * its own row. That is correct output and reads as broken: nothing on the canvas says why, and the
 * inspector's *"Nothing connects to this node."* describes the node without pointing anywhere.
 *
 * **Keyed on the roster, not on `edges.length` alone.** An environment that declares `yaml` and has
 * written no edges yet has already taken the step this sentence points at, so the sentence would be
 * wrong about the cause there. The same holds for `kafka` and `connect`, which observe edges.
 *
 * **Not a fourth `CanvasEmptyState`.** The canvas is not empty, and `routing/resolve.ts` reads those
 * states as no-node states. It renders beside the graph rather than instead of it, and an empty
 * canvas never shows it — ADR-0087's sentence is already the whole answer there.
 */
export function isEdgelessByRoster(graph: Pick<Graph, 'nodes' | 'edges' | 'plugins'>): boolean {
  return (
    graph.nodes.length > 0 &&
    graph.edges.length === 0 &&
    !graph.plugins.some((pair) => EDGE_PRODUCERS.has(pair.plugin))
  )
}

/**
 * The sentence. **It does not promise a time**, per ADR-0087's rule: the fix is a file the operator
 * writes, not a wait. It does not name `kubernetes` either — the roster may hold any plugin that
 * emits no edges, and the claim is about all of them. It says *topology directory*, never *topology
 * file*: the directory is many files read as one snapshot (ADR-0061, ADR-0064).
 */
export function edgelessSentence(environmentDisplayName: string): string {
  return `No plugin reading ${environmentDisplayName} reports what connects its nodes. Edges come from the environment's topology directory.`
}

/**
 * ADR-0161: the link ships only when its page resolves. Step 5 of the install route landed in #114;
 * the test reads the heading out of this file, so renaming the step fails there rather than in a
 * reader's browser.
 */
export const DRAW_EDGES_DOC = 'docs/install.md'
const DRAW_EDGES_ANCHOR = '5-draw-the-edges'

export const drawEdgesDocUrl = (version: string | null): string =>
  `${docUrl(DRAW_EDGES_DOC, version)}#${DRAW_EDGES_ANCHOR}`
