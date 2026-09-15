// SPDX-License-Identifier: Apache-2.0
/**
 * PROTOTYPE — throwaway. Lives only on `prototype/metric-line-two-plugins`, for
 * "The metric line when two plugins have something to say" (issue #107).
 *
 * Four variants of the node card's metric block, on the real canvas, switchable via `?variant=`:
 *
 *   A — today: one flattened line, alphabetical, clamped.
 *   B — a row per plugin: still blind to what a key means, but the card grows a row per namespace.
 *   C — composed headline: §14's `8/8 pods · 1.2k req/s · p95 210 ms` — a per-KEY phrase table in
 *       the frontend, one line, no plugin names.
 *   D — figures, capped: at most three big numbers with captions, ranked by a fixed key priority,
 *       the rest counted as `+N` and left to the drawer.
 *
 * Each also renders ADR-0165's new state — UNKNOWN with metrics — its own way, so the freshness
 * never reads as the age of a health verdict.
 */
import { useEffect, useSyncExternalStore } from 'react'
import { summarizeMetrics } from '../metrics'
import type { Health } from '../../api/types'
import './prototype.css'

export const VARIANTS = [
  { key: 'A', name: 'Today — one flattened line' },
  { key: 'B', name: 'A row per plugin' },
  { key: 'C', name: 'Composed headline' },
  { key: 'D', name: 'Figures, capped at three' },
] as const
export type VariantKey = (typeof VARIANTS)[number]['key']

type Metrics = Record<string, Record<string, unknown>>

const EVENT = 'prototype:params'

function subscribe(callback: () => void) {
  window.addEventListener('popstate', callback)
  window.addEventListener(EVENT, callback)
  return () => {
    window.removeEventListener('popstate', callback)
    window.removeEventListener(EVENT, callback)
  }
}

const param = (name: string) => new URLSearchParams(location.search).get(name)

export function useVariant(): VariantKey | null {
  return useSyncExternalStore(subscribe, () => {
    const v = param('variant')
    return VARIANTS.some((variant) => variant.key === v) ? (v as VariantKey) : null
  })
}

function setParam(name: string, value: string) {
  const url = new URL(location.href)
  url.searchParams.set(name, value)
  history.replaceState(null, '', url)
  window.dispatchEvent(new Event(EVENT))
}

// ------------------------------------------------------------------ formatting

function compact(n: number): string {
  if (n === 0) return '0'
  if (n >= 1e6) return `${(n / 1e6).toFixed(1)}M`
  if (n >= 1e4) return `${Math.round(n / 1e3)}k`
  if (n >= 1e3) return `${(n / 1e3).toFixed(1)}k`
  if (n >= 10 || Number.isInteger(n)) return `${Math.round(n)}`
  if (n >= 1) return n.toFixed(1)
  return n.toPrecision(2)
}

function ms(n: number): string {
  if (n === 0) return '0 ms'
  if (n < 1) return `${n.toPrecision(2)} ms`
  if (n < 10) return `${n.toFixed(1)} ms`
  return `${Math.round(n)} ms`
}

function age(observedAt: string): string {
  const seconds = Math.max(0, Math.round((Date.now() - new Date(observedAt).getTime()) / 1000))
  return seconds < 90 ? `${seconds}s` : `${Math.round(seconds / 60)}m`
}

const humanize = (name: string) => name.replace(/([A-Z])/g, ' $1').toLowerCase().trim()

const num = (value: unknown) => (typeof value === 'number' ? value : Number(value))

/** ADR-0165's new state: nothing voted, but something measured. */
const measuredOnly = (health: Health, observedAt: string | null) => health === 'UNKNOWN' && observedAt !== null

// ------------------------------------------------------------------ C and D: a per-key phrase table

interface Fact {
  value: string
  caption: string
  /** Lower ranks first. */
  rank: number
}

/**
 * The thing A and B refuse to have: knowledge of what a key means. It is keyed by KEY, not by plugin,
 * so a new plugin reusing `rate` needs nothing — but a new key renders generically until added.
 */
function factsOf(metrics: Metrics): Fact[] {
  const flat = new Map<string, unknown>()
  for (const values of Object.values(metrics)) for (const [k, v] of Object.entries(values)) flat.set(k, v)
  const facts: Fact[] = []
  const used = new Set<string>()
  const take = (...keys: string[]) => keys.forEach((k) => used.add(k))

  if (flat.has('readyReplicas') && flat.has('desiredReplicas')) {
    facts.push({ value: `${flat.get('readyReplicas')}/${flat.get('desiredReplicas')}`, caption: 'ready', rank: 0 })
    take('readyReplicas', 'desiredReplicas')
  }
  if (flat.has('rate')) {
    facts.push({ value: `${compact(num(flat.get('rate')))}/s`, caption: 'rate', rank: 1 })
    take('rate')
  }
  if (flat.has('maxConsumerLag')) {
    facts.push({ value: compact(num(flat.get('maxConsumerLag'))), caption: 'lag', rank: 2 })
    take('maxConsumerLag')
  }
  if (flat.has('latency')) {
    facts.push({ value: ms(num(flat.get('latency'))), caption: 'mean', rank: 3 })
    take('latency')
  }
  if (flat.has('tasksRunning') && flat.has('tasksTotal')) {
    facts.push({ value: `${flat.get('tasksRunning')}/${flat.get('tasksTotal')}`, caption: 'tasks', rank: 4 })
    take('tasksRunning', 'tasksTotal')
  }
  for (const [k, v] of flat) {
    if (!used.has(k)) facts.push({ value: String(v), caption: humanize(k), rank: 9 })
  }
  return facts.sort((a, b) => a.rank - b.rank)
}

function phraseOf(fact: Fact) {
  switch (fact.caption) {
    case 'ready':
      return `${fact.value} ready`
    case 'lag':
      return `lag ${fact.value}`
    case 'tasks':
      return `${fact.value} tasks`
    case 'rate':
    case 'mean':
      return fact.value
    default:
      return `${fact.caption} ${fact.value}`
  }
}

// ------------------------------------------------------------------ heights (declared to XYFlow)

const B_ROW = 17
const D_CAP = 3

export function metricHeight(variant: VariantKey, metrics: Metrics, health: Health, observedAt: string | null): number {
  const namespaces = Object.keys(metrics).length
  if (namespaces === 0) return 0
  switch (variant) {
    case 'A':
    case 'C':
      return 22
    case 'B':
      return 8 + B_ROW * (namespaces + (measuredOnly(health, observedAt) ? 1 : 0))
    case 'D':
      return 42
  }
}

// ------------------------------------------------------------------ the block

export function MetricBlock({
  variant,
  metrics,
  health,
  observedAt,
}: {
  variant: VariantKey
  metrics: Metrics
  health: Health
  observedAt: string | null
}) {
  const full = summarizeMetrics(metrics)
  if (full === null) return null
  const measured = measuredOnly(health, observedAt) && observedAt !== null

  if (variant === 'A') {
    return (
      <div className="node-card-metric" title={full}>
        {full}
      </div>
    )
  }

  if (variant === 'B') {
    return (
      <div className="proto-b" title={full}>
        {Object.entries(metrics)
          .sort(([a], [b]) => a.localeCompare(b))
          .map(([plugin, values]) => (
            <div className="proto-b-row" key={plugin}>
              <span className="proto-b-plugin">{plugin}</span>
              <span className="proto-b-values">
                {Object.entries(values)
                  .sort(([a], [b]) => a.localeCompare(b))
                  .map(([k, v]) => `${humanize(k)} ${typeof v === 'number' ? compact(v) : String(v)}`)
                  .join(' · ')}
              </span>
            </div>
          ))}
        {measured && (
          <div className="proto-b-row proto-faint">
            <span className="proto-b-plugin">measured</span>
            <span className="proto-b-values">{age(observedAt)} ago · no health verdict</span>
          </div>
        )}
      </div>
    )
  }

  const facts = factsOf(metrics)

  if (variant === 'C') {
    return (
      <div className="node-card-metric proto-c" title={full}>
        {facts.map(phraseOf).join(' · ')}
        {measured && <span className="proto-faint"> · measured {age(observedAt)} ago</span>}
      </div>
    )
  }

  const shown = facts.slice(0, D_CAP)
  const hidden = facts.slice(D_CAP)
  return (
    <div className="proto-d" title={full}>
      {shown.map((fact) => (
        <div className="proto-d-fact" key={fact.caption}>
          <span className="proto-d-value">{fact.value}</span>
          <span className="proto-d-caption">{fact.caption}</span>
        </div>
      ))}
      {hidden.length > 0 && (
        <div className="proto-d-fact proto-faint" title={hidden.map(phraseOf).join(' · ')}>
          <span className="proto-d-value">+{hidden.length}</span>
          <span className="proto-d-caption">more</span>
        </div>
      )}
      {measured && (
        <div className="proto-d-fact proto-d-age proto-faint">
          <span className="proto-d-value">{age(observedAt)}</span>
          <span className="proto-d-caption">measured</span>
        </div>
      )}
    </div>
  )
}

// ------------------------------------------------------------------ the switcher

export function PrototypeSwitcher() {
  const variant = useVariant()
  const incident = useSyncExternalStore(subscribe, () => param('scenario') === 'incident')
  const index = Math.max(0, VARIANTS.findIndex((v) => v.key === variant))
  const current = VARIANTS[index] ?? VARIANTS[0]
  const cycle = (step: number) =>
    setParam('variant', (VARIANTS[(index + step + VARIANTS.length) % VARIANTS.length] ?? VARIANTS[0]).key)

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      const target = event.target as HTMLElement | null
      if (target && target.closest('input, textarea, select, [contenteditable]') !== null) return
      if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') return
      cycle(event.key === 'ArrowLeft' ? -1 : 1)
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  })

  // Hidden in production builds, so a stray merge cannot ship the bar.
  const dev = (import.meta as { env?: { DEV?: boolean } }).env?.DEV === true
  if (!dev || variant === null) return null

  return (
    <div className="proto-switcher" role="toolbar" aria-label="Prototype variant switcher">
      <button type="button" onClick={() => cycle(-1)} aria-label="Previous variant">
        ←
      </button>
      <span className="proto-switcher-label">
        {current.key} — {current.name}
      </span>
      <button type="button" onClick={() => cycle(1)} aria-label="Next variant">
        →
      </button>
      <button type="button" className="proto-switcher-scenario" onClick={() => setParam('scenario', incident ? 'baseline' : 'incident')}>
        {incident ? 'incident' : 'baseline'}
      </button>
    </div>
  )
}
