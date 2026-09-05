// SPDX-License-Identifier: Apache-2.0
import type { PluginOutcome, PluginRef } from '../api/types'

/**
 * ADR-0026's second fact, read off the wire: **`health` says what we found; `outcome` says how well
 * we looked.** Everything in this file projects the `plugins[]` block of the two documents, and
 * nothing in it touches health.
 *
 * `plugins[]` is a **config roster left-joined with the store** (ADR-0085), not a store projection:
 * it carries an entry for every configured `(plugin, capability)` pair whether or not that pair has
 * ever reported, and an unreported pair ships `outcome: null` with `recordedAt: null`. That is what
 * makes a cold environment distinguishable from an empty one, and it is why every function here
 * branches on `null` rather than switching over three values.
 */

/** The two documents each carry one capability's roster; the chip and popover read both. */
export interface Rosters {
  /** `/graph`'s block — every configured DISCOVERY pair. */
  discovery: PluginOutcome[]
  /** `/state`'s block — every configured HEALTH pair. Empty until the first `/state` lands. */
  health: PluginOutcome[]
}

export const allPairs = (rosters: Rosters): PluginOutcome[] => [...rosters.discovery, ...rosters.health]

/** Distinct plugin ids across both capabilities — a **config** count, so it never shrinks. */
export const pluginCount = (rosters: Rosters): number =>
  new Set(allPairs(rosters).map((pair) => pair.plugin)).size

/**
 * ADR-0089: **the chip names the states that are present, with counts, and ranks nothing.**
 *
 * A worst-first phrase would need a ranking that does not exist. Under ADR-0086 a `FAILED` is
 * actionable now — fix the kubeconfig — while unreported is *wait*; neither dominates, and a ladder
 * makes the chip quietly drop whichever it ranked second, which on a fresh install is the one you
 * most need to see. `outcome` is no more a ladder than `health` is.
 *
 * ```text
 * 4 plugins · all complete
 * 4 plugins · 1 failed, 2 not reported
 * 4 plugins · none reported
 * 0 plugins · no plugins configured
 * ```
 *
 * `COMPLETE` is named only by the `all complete` clause. The mixed clause names what has departed
 * from rest, which is the reading ADR-0089's own example takes — `4 plugins · 1 failed, 2 not
 * reported` does not account for the pairs that are fine, because a count of things that are fine is
 * not what the reader is scanning the chip for. The listed order is the `outcome` enum's own
 * declaration order with `null` last: a stable way to write a sentence, not a severity.
 */
export function chipClause(rosters: Rosters): string {
  const pairs = allPairs(rosters)
  if (pairs.length === 0) return 'no plugins configured'

  const count = (predicate: (pair: PluginOutcome) => boolean) => pairs.filter(predicate).length
  const partial = count((pair) => pair.outcome === 'PARTIAL')
  const failed = count((pair) => pair.outcome === 'FAILED')
  const unreported = count((pair) => pair.outcome === null)

  if (partial + failed + unreported === 0) return 'all complete'
  if (unreported === pairs.length) return 'none reported'

  return [
    partial > 0 ? `${partial} partial` : null,
    failed > 0 ? `${failed} failed` : null,
    unreported > 0 ? `${unreported} not reported` : null,
  ]
    .filter((clause): clause is string => clause !== null)
    .join(', ')
}

export const chipText = (rosters: Rosters): string =>
  `${pluginCount(rosters)} ${pluginCount(rosters) === 1 ? 'plugin' : 'plugins'} · ${chipClause(rosters)}`

/**
 * ADR-0081's banner, as data. Nothing renders while every outcome is `COMPLETE`; a non-`COMPLETE`
 * poll raises one banner per affected pair.
 *
 * Two shapes, because ADR-0088 found that the `null` case demands two facts that do not exist.
 * There is no **cause** — nothing failed, nothing was reported at all — and no computable **affected
 * node count**, because an unreported plugin has said nothing about what it would have carried.
 * Fabricating either is what ADR-0058 forbids in general terms.
 */
export interface Banner {
  plugin: string
  capability: string
  /** `null` for an unreported pair, which has no cause to name. */
  cause: string | null
  /** `null` for an unreported pair, which has nothing to count. */
  affected: number | null
  outcome: 'PARTIAL' | 'FAILED' | null
}

/**
 * @param affectedBy how many nodes this pair has marked — retained for DISCOVERY, blind for HEALTH.
 * @param graphIsEmpty ADR-0088: an unreported pair banners **only over a non-empty graph**. The
 *   all-cold case is already carried by ADR-0087's empty state, and a banner stacked over an empty
 *   canvas is a second copy of one sentence. The mixed case has no other surface at all — the canvas
 *   draws a graph that looks whole, and a node that is not there cannot be marked.
 */
export function bannersOf(
  rosters: Rosters,
  affectedBy: (pair: PluginOutcome) => number,
  graphIsEmpty: boolean,
): Banner[] {
  return allPairs(rosters)
    .filter((pair) => pair.outcome !== 'COMPLETE')
    .filter((pair) => pair.outcome !== null || !graphIsEmpty)
    .map((pair) => ({
      plugin: pair.plugin,
      capability: pair.capability,
      cause: pair.outcome === null ? null : (pair.reasons.join('; ') || null),
      affected: pair.outcome === null ? null : affectedBy(pair),
      outcome: pair.outcome === 'PARTIAL' || pair.outcome === 'FAILED' ? pair.outcome : null,
    }))
}

/** The sentence a banner reads. Kept beside `bannersOf` so the two variants cannot drift apart. */
export function bannerSentence(banner: Banner, label: (pluginId: string) => string): string {
  const capability = banner.capability.toLowerCase()
  if (banner.outcome === null) {
    // ADR-0088: plugin and capability named; no count and no cause, because neither exists. The
    // reader has to notice that this is weaker than a named cause — accepted, against a fabrication.
    return `${label(banner.plugin)} has not reported ${capability} yet. This graph may be incomplete.`
  }
  const verb = banner.outcome === 'FAILED' ? 'could not read' : 'partly read'
  const cause = banner.cause ? ` — ${banner.cause}` : ''
  const nodes = banner.affected === 1 ? '1 node' : `${banner.affected} nodes`
  return `${label(banner.plugin)} ${verb} ${capability} for this environment${cause}. ${nodes} affected.`
}

/** One popover row per configured pair, in roster order, discovery first. */
export interface PopoverRow {
  plugin: string
  capability: string
  outcome: PluginOutcome['outcome']
  detail: string | null
  recordedAt: string | null
}

export function popoverRows(rosters: Rosters): PopoverRow[] {
  return allPairs(rosters).map((pair) => ({
    plugin: pair.plugin,
    capability: pair.capability,
    outcome: pair.outcome,
    detail: pair.reasons.length > 0 ? pair.reasons.join('; ') : null,
    recordedAt: pair.recordedAt,
  }))
}

/** ADR-0055: the frontend resolves plugin ids to labels through `/api/meta`'s roster. */
export const labeller = (plugins: PluginRef[]) => (pluginId: string) =>
  plugins.find((plugin) => plugin.id === pluginId)?.displayLabel ?? pluginId
