// SPDX-License-Identifier: Apache-2.0
/**
 * PROTOTYPE — throwaway. Lives only on `prototype/metric-line-two-plugins`, for
 * "The metric line when two plugins have something to say" (issue #107).
 *
 * Serves ADR-0053's three GETs from the golden documents, with a `prometheus` namespace poured in
 * the way ADR-0165 and ADR-0167 say it will arrive: exactly `rate` (per second) and `latency` (mean
 * ms), both or neither. Numbers are the demo's where the demo has them — the aggregator's 38/s and
 * 0.036 ms — and plausible otherwise.
 *
 * Active only when the URL carries `?variant=`; `?scenario=incident` swaps in the incident state.
 */
import graphJson from './fixtures/graph-production.json'
import baselineJson from './fixtures/state-production-baseline.json'
import incidentJson from './fixtures/state-production-incident.json'
import type { Graph, Meta, State } from '../../api/types'

export const prototypeActive = () => new URLSearchParams(location.search).has('variant')

const scenario = () => (new URLSearchParams(location.search).get('scenario') === 'incident' ? 'incident' : 'baseline')

const iso = (ageSeconds: number) => new Date(Date.now() - ageSeconds * 1000).toISOString()

function stamp<T>(value: unknown, ageSeconds: number): T {
  return JSON.parse(JSON.stringify(value).split('"<timestamp>"').join(JSON.stringify(iso(ageSeconds)))) as T
}

/** Per scenario: node key → the two keys. `stripe-webhooks` is the new card state — only Prometheus observes it. */
const PROMETHEUS: Record<'baseline' | 'incident', Record<string, { rate: number; latency: number }>> = {
  baseline: {
    'payments-api': { rate: 1240.6, latency: 18.4 }, // micrometer-http, requests/s
    'payments-enricher': { rate: 37.65, latency: 0.036 }, // kafka-streams, records/s — the demo's reading
    'stripe-webhooks': { rate: 12.1, latency: 212 }, // Prometheus-only: UNKNOWN with metrics
  },
  incident: {
    'payments-api': { rate: 1302.2, latency: 19.1 },
    'payments-enricher': { rate: 0, latency: 0 }, // series exist, idle: `rate 0` is a real value
    'stripe-webhooks': { rate: 11.8, latency: 640 },
  },
}

const PROMETHEUS_ONLY_AGE_SECONDS = 45

function state(): State {
  const which = scenario()
  const document = stamp<State>(which === 'incident' ? incidentJson : baselineJson, 8)
  for (const node of document.nodes) {
    const reading = PROMETHEUS[which][node.nodeKey]
    if (!reading) continue
    node.metrics = { ...node.metrics, prometheus: { ...reading } }
    // ADR-0165: a node only Prometheus observes reads UNKNOWN with metrics and an observedAt.
    if (node.observedAt === null) node.observedAt = iso(PROMETHEUS_ONLY_AGE_SECONDS)
  }
  document.plugins = [
    ...document.plugins,
    { plugin: 'prometheus', capability: 'HEALTH', outcome: 'COMPLETE', reasons: [], recordedAt: iso(8) },
  ]
  return document
}

function graph(): Graph {
  return stamp<Graph>(graphJson, 60)
}

function meta(): Meta {
  const g = graph()
  const s = state()
  const capabilities = new Map<string, Set<string>>()
  for (const pair of [...g.plugins, ...s.plugins]) {
    capabilities.set(pair.plugin, (capabilities.get(pair.plugin) ?? new Set()).add(pair.capability))
  }
  return {
    environments: [g.environment],
    plugins: [...capabilities].map(([id, caps]) => ({ id, displayLabel: id, capabilities: [...caps] })),
    refresh: { graphSeconds: 60, stateSeconds: 2 },
  }
}

export const fixtureApi = {
  meta: async () => meta(),
  graph: async (_environmentKey: string) => graph(),
  state: async (_environmentKey: string) => state(),
}
