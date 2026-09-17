// SPDX-License-Identifier: Apache-2.0
import type { NodeState } from '../api/types'

/**
 * The node card's optional figure strip, composed from ADR-0006's plugin-namespaced map (ADR-0168).
 *
 * The core never looks inside `metrics`. This does, but only by **key**: a phrase table says what
 * `rate` or `maxConsumerLag` means and where it ranks, and nothing here branches on a plugin or a
 * node type. A new plugin that reuses `rate` needs no frontend change, and a key nobody has given a
 * row still renders, generically and last.
 *
 * The table is presentation, so it lives here rather than on the wire. Shipping a unit and a label
 * per key would grow ADR-0028's contract and ADR-0055's roster for a concern only the card has.
 */

export interface Figure {
  value: string
  caption: string
}

export interface FigureStrip {
  /** At most {@link FIGURE_CAP}, in rank order. */
  figures: Figure[]
  /** How many ranked figures the cap dropped; the card shows `+N more` when this is not zero. */
  more: number
  /** Every figure, dropped ones included, for the strip's `title`. */
  title: string
  /**
   * ADR-0165's measured-only node: nothing voted, but something was read at this time. Null on a
   * node with voters, whose `observedAt` is a verdict's age and belongs to the drawer.
   */
  measuredAt: string | null
}

export const FIGURE_CAP = 3

type Flat = Map<string, unknown>

interface Row {
  keys: string[]
  figure: (flat: Flat) => Figure
}

/** ADR-0168's ranks. A pair collapses only when both keys arrived; otherwise each renders generically. */
const PHRASES: Row[] = [
  {
    keys: ['readyReplicas', 'desiredReplicas'],
    figure: (flat) => ({ value: `${flat.get('readyReplicas')}/${flat.get('desiredReplicas')}`, caption: 'ready' }),
  },
  // ADR-0167 puts records and requests under one key, so the caption says neither.
  { keys: ['rate'], figure: (flat) => ({ value: `${numeric(flat.get('rate'), compact)}/s`, caption: 'rate' }) },
  { keys: ['maxConsumerLag'], figure: (flat) => ({ value: numeric(flat.get('maxConsumerLag'), compact), caption: 'lag' }) },
  { keys: ['latency'], figure: (flat) => ({ value: numeric(flat.get('latency'), milliseconds), caption: 'mean' }) },
  {
    keys: ['tasksRunning', 'tasksTotal'],
    figure: (flat) => ({ value: `${flat.get('tasksRunning')}/${flat.get('tasksTotal')}`, caption: 'tasks' }),
  },
]

export function summarizeMetrics(node: Pick<NodeState, 'health' | 'metrics' | 'observedAt'>): FigureStrip | null {
  // Alphabetical by plugin, so two plugins sharing a key resolve the same way on every render.
  const flat: Flat = new Map(
    Object.entries(node.metrics)
      .sort(([a], [b]) => a.localeCompare(b))
      .flatMap(([, values]) => Object.entries(values)),
  )
  if (flat.size === 0) return null

  const ranked: Figure[] = []
  const used = new Set<string>()
  for (const row of PHRASES) {
    if (!row.keys.every((key) => flat.has(key))) continue
    ranked.push(row.figure(flat))
    row.keys.forEach((key) => used.add(key))
  }
  ;[...flat.keys()]
    .filter((key) => !used.has(key))
    .sort((a, b) => a.localeCompare(b))
    .forEach((key) => ranked.push({ value: String(flat.get(key)), caption: humanize(key) }))

  return {
    figures: ranked.slice(0, FIGURE_CAP),
    more: Math.max(0, ranked.length - FIGURE_CAP),
    title: ranked.map((figure) => `${figure.value} ${figure.caption}`).join(' · '),
    measuredAt: node.health === 'UNKNOWN' ? node.observedAt : null,
  }
}

/** `1.2k`, `40k`, `2.1M`. Integers never gain a decimal. */
export function compact(n: number): string {
  if (Math.abs(n) >= 999_950) return `${trim((n / 1e6).toFixed(1))}M`
  if (Math.abs(n) >= 9_999.5) return `${Math.round(n / 1e3)}k`
  if (Math.abs(n) >= 1_000) return `${trim((n / 1e3).toFixed(1))}k`
  if (Math.abs(n) >= 10 || Number.isInteger(n)) return `${Math.round(n)}`
  return `${Number(n.toPrecision(2))}`
}

/** A mean latency in ms. Sub-millisecond keeps two significant figures. */
export function milliseconds(n: number): string {
  if (Number.isInteger(n) || Math.abs(n) >= 10) return `${Math.round(n)} ms`
  if (Math.abs(n) >= 1) return `${n.toFixed(1)} ms`
  return `${Number(n.toPrecision(2))} ms`
}

/** How long ago a measurement was taken, as the strip's trailing `measured 45s ago` says it. */
export function ageOf(measuredAt: string, now: number): string {
  const seconds = Math.max(0, Math.round((now - Date.parse(measuredAt)) / 1000))
  if (seconds < 90) return `${seconds}s`
  const minutes = Math.round(seconds / 60)
  if (minutes < 90) return `${minutes}m`
  return `${Math.round(minutes / 60)}h`
}

/** The wire should carry numbers, but a figure must not render `NaN` if one arrives as anything else. */
function numeric(value: unknown, format: (n: number) => string) {
  return typeof value === 'number' && Number.isFinite(value) ? format(value) : String(value)
}

const trim = (fixed: string) => fixed.replace(/\.0$/, '')

/**
 * `queueDepth` reads as "queue depth": the caption of a key with no row. The allow-list is camelCase
 * because it is a wire contract; a human-readable label is presentation, so it is derived here.
 */
function humanize(name: string) {
  return name
    .replace(/([A-Z])/g, ' $1')
    .toLowerCase()
    .trim()
}
