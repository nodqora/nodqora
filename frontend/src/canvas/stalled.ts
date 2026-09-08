// SPDX-License-Identifier: Apache-2.0
import { foldKey } from '../api/keys'
import type { GraphEdge, Health } from '../api/types'

/**
 * ADR-0149: **an edge known to be carrying nothing is drawn as stalled.** A pure function over
 * `(edges, health)`, client-side for the same reason `highlight.ts` is (ADR-0053, ADR-0054).
 *
 * **Only the negative claim is available.** Nothing in this system measures flow — throughput is a
 * sampled derivative and sampling is diffing, which ADR-0012 forbids plugins outright, which is why
 * ADR-0038 rules throughput out structurally rather than on cost. So a live edge means *"nothing
 * observed says otherwise"* and stays plain; it never becomes a positive signal, and it is never
 * animated, because motion asserts data is moving more forcefully than any legend can walk back.
 *
 * This infers a property of an **edge** from its neighbours, which ADR-0027 forbids for **health**.
 * The boundary is real and worth stating: a node's health is an observation with a `rawSignal`
 * behind it, and inventing one would leave the inspector unable to show its work. Whether data
 * moves between two nodes was never observed by anybody — no plugin reports it and no field carries
 * it — so an edge has nothing to overwrite, and every input here is already someone's observation.
 */

/** The stalled edges of one graph, addressed by the same identity `edgeIsStalled` recomputes. */
export type StalledEdges = ReadonlySet<string>

/**
 * `STOPPED` is `DISABLED` or `UNHEALTHY` — a human wrote `replicas: 0`, or nothing is ready.
 *
 * `DEGRADED` is deliberately absent: some replicas serve, so data still moves, just less of it. So
 * is `UNKNOWN` — nobody looked, and calling that stalled would invent the observation this whole
 * module exists to avoid inventing.
 */
const STOPPED: ReadonlySet<Health> = new Set<Health>(['DISABLED', 'UNHEALTHY'])

export function stalledEdges(edges: GraphEdge[], health: ReadonlyMap<string, Health>): StalledEdges {
  // A node with no state entry cannot happen — /state carries every key (ADR-0057) — but the canvas
  // draws before the first /state response lands, and `UNKNOWN` is the honest reading until it does.
  const stopped = (key: string) => STOPPED.has(health.get(foldKey(key)) ?? 'UNKNOWN')

  // Only nodes with at least one inbound edge appear here, which is what makes "nodes with no
  // inbound edges never starve" structural rather than a case to remember.
  const inbound = new Map<string, GraphEdge[]>()
  for (const edge of edges) {
    const to = foldKey(edge.toKey)
    inbound.set(to, [...(inbound.get(to) ?? []), edge])
  }

  const starved = new Set<string>()
  const stalls = (edge: GraphEdge) =>
    // Rule 1: nothing is being produced onto it. Rule 2: nothing is consuming from it — an edge
    // carries data only if both ends work, so a live arrow into a dead node draws a thing that is
    // not happening. Rule 3: the source is starved, which is the transitive half.
    stopped(edge.fromKey) || stopped(edge.toKey) || starved.has(foldKey(edge.fromKey))

  // Rule 3 as a least fixed point over the seed set rules 1 and 2 produce. `stalls` is monotone in
  // `starved` and `starved` only grows, bounded by the node count, so this terminates — and
  // starting from the empty set is what keeps a cycle live unless something outside it seeds one.
  for (let grew = true; grew; ) {
    grew = false
    for (const [key, arriving] of inbound) {
      // **Every** inbound edge, not any: a node with two producers, one stopped and one running, is
      // still receiving data and must not starve.
      if (!starved.has(key) && arriving.every(stalls)) {
        starved.add(key)
        grew = true
      }
    }
  }

  return new Set(edges.filter(stalls).map(edgeIdentity))
}

export function edgeIsStalled(edge: GraphEdge, stalled: StalledEdges): boolean {
  return stalled.has(edgeIdentity(edge))
}

/** Folded like every other comparison (ADR-0020), and carrying the relation so parallel edges of
 * different relations between one pair stay two edges. */
const edgeIdentity = (edge: GraphEdge) =>
  `${foldKey(edge.fromKey)}|${foldKey(edge.relation)}|${foldKey(edge.toKey)}`
