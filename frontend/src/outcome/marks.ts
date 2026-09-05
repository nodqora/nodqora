// SPDX-License-Identifier: Apache-2.0
import type { GraphNode, PluginOutcome, PluginRef, Source } from '../api/types'
import type { Rosters } from './plugins'

/**
 * ADR-0083's two canvas marks. **Retained and blind are not degrees of the same problem**, so they
 * are not the same mark.
 *
 * - **Retained** — a discovery poll came back non-`COMPLETE` and this node's topology was carried
 *   forward rather than confirmed. Health is untouched: nothing on the health channel changes at
 *   all, so freshness is the only signal that exists.
 * - **Blind** — a health-capable plugin backing this node did not observe it this cycle. ADR-0024
 *   discards abstentions, so the node still shows the surviving observers' verdict — which is
 *   exactly the case ADR-0026 was written about: green because we stopped looking.
 *
 * A node's *topology* being old and its health being under-observed are different statements, and
 * the same mark for both would say neither. A node can carry both; on ADR-0083's own `blind`
 * fixture both connectors do.
 */

/**
 * ADR-0084: **retention is a comparison, not a timeout.**
 *
 * ```text
 * retained(source) := source.confirmedAt < plugins[source.plugin].recordedAt
 * ```
 *
 * No threshold, no TTL, no constant — the frontend holds no staleness number at all. The comparison
 * is exact because the fold makes it exact (ADR-0046): a `COMPLETE` poll replaces its snapshot, so
 * every key it carries was confirmed at that poll and the two timestamps agree; a `PARTIAL` retains
 * whole keys, so exactly the retained ones are older; a `FAILED` moves the header alone (ADR-0086),
 * so every key is older.
 *
 * The obvious repair — *stale after N × the plugin's cadence* — does not survive contact with the
 * API. ADR-0059 publishes `refresh.graphSeconds` as the **minimum** over the configured cadences,
 * so a threshold could only be applied at the tightest plugin's rate and a plugin polling at fifteen
 * minutes would be marked stale at ten, on a healthy poll.
 *
 * Both timestamps are produced by the same server and travel in the same document, so **there is no
 * clock skew**: the browser never compares a server time to its own. And on a cold store the
 * comparison is *unreachable* rather than undefined — a plugin that has never reported appears in
 * zero nodes' `sources[]` (ADR-0085).
 */
export function retained(source: Source, discovery: PluginOutcome[]): boolean {
  const recordedAt = discovery.find((pair) => pair.plugin === source.plugin)?.recordedAt
  if (!source.confirmedAt || !recordedAt) return false
  // Parsed rather than compared as strings: `…:02Z` sorts *after* `…:02.5Z` lexicographically, so a
  // server that ever emits fractional seconds would invert the comparison on sub-second polls.
  const confirmed = Date.parse(source.confirmedAt)
  const recorded = Date.parse(recordedAt)
  return !Number.isNaN(confirmed) && !Number.isNaN(recorded) && confirmed < recorded
}

/** The plugins whose view of this node's topology was carried forward rather than confirmed. */
export const retainedSources = (node: GraphNode, discovery: PluginOutcome[]): string[] =>
  node.sources.filter((source) => retained(source, discovery)).map((source) => source.plugin)

/**
 * ADR-0083 and ADR-0088: a health-capable plugin that **backs** this node and did not report a
 * `COMPLETE` health poll for this environment.
 *
 * *Backs* is read off `backings[]` rather than off `sources[]`, because ADR-0013 routes health
 * through backings: a plugin that merely discovered the node has nothing to abstain from. That is
 * also what keeps the four permanently-`UNKNOWN` nodes unmarked — they have no backings at all, so
 * the cry-wolf constraint holds by construction rather than by a special case.
 *
 * **A `null` counts as blind, on the same mark** (ADR-0088). A node whose `connect` backing has
 * never been read is missing precisely the same contribution as one whose `connect` abstained this
 * cycle, and a third encoding was rejected against ADR-0081's own conceded cost. It also breaks the
 * "never marked" symmetry in the useful direction: `UNKNOWN`-because-nobody-has-looked-yet stops
 * rendering identically to `UNKNOWN`-because-nothing-watches-this.
 *
 * **A `PARTIAL` counts too, and that is a reading rather than a restatement.** A `PARTIAL` says the
 * plugin was blind *somewhere* and ADR-0057 exposes no contributions, so nothing on the wire says
 * where. Marking every node it backs over-marks; not marking under-marks. The tie goes to the mark,
 * because the question ADR-0083 says this answers is *is the health value on this node
 * trustworthy?* — and a plugin that has told us it could not see everything has not answered it.
 */
export function blindPlugins(node: GraphNode, rosters: Rosters, registry: PluginRef[]): string[] {
  const healthCapable = new Set(
    registry.filter((plugin) => plugin.capabilities.includes('HEALTH')).map((plugin) => plugin.id),
  )
  const abstained = new Set(
    rosters.health.filter((pair) => pair.outcome !== 'COMPLETE').map((pair) => pair.plugin),
  )

  const backing = new Set(node.backings.map((each) => each.plugin))
  // Registry order, because that is the order `rawSignal` segments join in (ADR-0028) and the
  // caveat below names the same plugins in the same breath as the signal it is qualifying. `/api/meta`
  // serves the roster already sorted by registry rank, so this needs no second ordering rule.
  return registry
    .map((plugin) => plugin.id)
    .filter((id) => backing.has(id) && healthCapable.has(id) && abstained.has(id))
}

/** What the canvas and the drawer both need to know about one node's honesty. */
export interface NodeMarks {
  retained: string[]
  blind: string[]
}

export function marksOf(node: GraphNode, rosters: Rosters, registry: PluginRef[]): NodeMarks {
  return { retained: retainedSources(node, rosters.discovery), blind: blindPlugins(node, rosters, registry) }
}

/**
 * ADR-0083's inspector caveat, which sits **inside Health — section 2** rather than in a section of
 * its own. It is a statement *about that health value*: putting it elsewhere makes the reader
 * correlate two places to find out whether the number above is trustworthy, and ADR-0019's whole
 * argument for a fixed section order is that the reader should not have to hunt.
 *
 * **ADR-0106 splits it in two**, because ADR-0083's original sentence is true of only one of the two
 * cases that share this mark.
 *
 * A `FAILED` or `PARTIAL` health run is a **no-op on the contribution store** — ADR-0072 retains
 * contributions per plugin by the same rules ADR-0046 gives discovery — so a plugin that has
 * reported before is still contributing its *last* reading. "Composed from the others only" would be
 * a false statement, and a worse one than saying nothing, because the node reads calm partly on
 * evidence nobody has re-checked.
 *
 * A plugin that has **nothing stored** has nothing to carry forward, so there the original sentence
 * is exactly right and is an arithmetic certainty.
 *
 * This reads no new field: `recordedAt: null` separates "never polled" from "polled", and the node's
 * own `observedAt` separates "there is a reading here" from "there is none". It cannot say *how*
 * stale a carried reading is, or which plugin's it was — that would need per-plugin `observedAt`,
 * which is `contributions[]` by another name and is refused by ADR-0057. The composed `observedAt`
 * rendered directly above is the available number, and ADR-0072's `min` makes it a lower bound.
 *
 * Returns `null` when nothing could not look, which is the ordinary day.
 */
export function healthCaveat(
  node: GraphNode,
  rosters: Rosters,
  registry: PluginRef[],
  label: (pluginId: string) => string,
  /** Whether this node has any observation at all — `NodeState.observedAt !== null`. */
  hasObservation: boolean,
): string | null {
  const blind = blindPlugins(node, rosters, registry)
  if (blind.length === 0) return null

  const names = blind.map(label).join(' and ')
  // `recordedAt` says the pair has *polled*, not that it ever succeeded — ADR-0086 writes the header
  // on a failed poll too. So this separates "never asked" from "asked and did not get an answer this
  // cycle", which are different sentences, and neither of them is about success.
  const everPolled = blind.some(
    (plugin) => rosters.health.find((pair) => pair.plugin === plugin)?.recordedAt != null,
  )
  const opening = everPolled
    ? `${names} could not observe this node this cycle.`
    : `${names} has not observed this node yet.`

  // A reading can only have been carried forward if there is a reading here at all. With no
  // observation the drawer directly above this reads "Raw signal: nothing observes this node" and
  // "Observed: never", and a sentence about a retained value would have no referent — the exact
  // failure ADR-0104 removed from `observedAt` itself.
  if (everPolled && hasObservation) {
    return `${opening} A failed poll does not clear what it last reported, so the value above may include a reading nobody has re-checked.`
  }

  const backing = new Set(node.backings.map((each) => each.plugin))
  const observing = registry
    .filter((plugin) => plugin.capabilities.includes('HEALTH'))
    .map((plugin) => plugin.id)
    .filter((id) => backing.has(id) && !blind.includes(id))
    .map(label)

  const composed =
    observing.length === 0
      ? 'Nothing else observes this node.'
      : `The value above is composed from ${observing.join(' + ')} only.`
  return `${opening} ${composed}`
}
