// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { foldKey } from '../api/keys'
import { edgeIsStalled, stalledEdges } from './stalled'
import type { Graph, GraphEdge, Health, State } from '../api/types'

const graph = JSON.parse(readFileSync('../fixtures/golden/graph-production.json', 'utf8')) as Graph

const healthOf = (fixture: string) => {
  const state = JSON.parse(readFileSync(`../fixtures/golden/${fixture}`, 'utf8')) as State
  return new Map<string, Health>(state.nodes.map((node) => [foldKey(node.nodeKey), node.health]))
}

const incident = healthOf('state-production-incident.json')
const baseline = healthOf('state-production-baseline.json')

const stalledIn = (health: Map<string, Health>, edges: GraphEdge[] = graph.edges) => {
  const stalled = stalledEdges(edges, health)
  return edges.filter((edge) => edgeIsStalled(edge, stalled)).map((edge) => `${edge.fromKey} -> ${edge.toKey}`)
}

const edge = (fromKey: string, toKey: string): GraphEdge => ({
  fromKey,
  toKey,
  relation: 'PRODUCES_TO',
  metadata: {},
  sources: ['yaml'],
  discoveredAt: '',
  updatedAt: '',
})

const health = (entries: Record<string, Health>) =>
  new Map<string, Health>(Object.entries(entries).map(([key, value]) => [foldKey(key), value]))

describe('the incident stalls seven of the shipped graph\'s nine edges', () => {
  it('names exactly those seven', () => {
    // Three nodes are stopped: `payments-enricher` (UNHEALTHY, 3 desired / 0 ready) and both sinks
    // (DISABLED, paused). Seven edges follow, and the two that do not are the assertion that
    // matters — ingest is still running and the canvas must keep saying so.
    expect(stalledIn(incident)).toEqual([
      'analytics.payments_events -> trino-analytics', // rule 3 — its only inbound is stalled
      'payments-enricher -> payments.events.enriched.v1', // rule 1
      'payments-es-sink -> payments-events-v1', // rule 1
      'payments-iceberg-sink -> analytics.payments_events', // rule 1
      'payments.events.enriched.v1 -> payments-es-sink', // rules 2 and 3
      'payments.events.enriched.v1 -> payments-iceberg-sink', // rules 2 and 3
      'payments.events.raw.v1 -> payments-enricher', // rule 2, and the 2,100,000 lag is what it looks like
    ])
  })

  it('leaves the live ingest edges alone', () => {
    const stalled = stalledEdges(graph.edges, incident)
    // Neither end stopped.
    expect(edgeIsStalled(graph.edges.find((e) => e.fromKey === 'stripe-webhooks')!, stalled)).toBe(false)
    // Source healthy, target a topic nobody calls stopped, and its own inbound is live.
    expect(edgeIsStalled(graph.edges.find((e) => e.fromKey === 'payments-api')!, stalled)).toBe(false)
  })

  it('stalls nothing on the baseline', () => {
    // `payments-enricher` and `payments-iceberg-sink` are DEGRADED there and four nodes are UNKNOWN.
    // Neither is stopped, so the whole pipeline is drawn as carrying data — which is the default,
    // not a claim that it is.
    expect(stalledIn(baseline)).toEqual([])
  })
})

describe('what counts as stopped', () => {
  it('does not stall on DEGRADED — some replicas serve, so data still moves', () => {
    expect(stalledIn(health({ a: 'DEGRADED', b: 'HEALTHY' }), [edge('a', 'b')])).toEqual([])
    expect(stalledIn(health({ a: 'HEALTHY', b: 'DEGRADED' }), [edge('a', 'b')])).toEqual([])
  })

  it('does not stall on UNKNOWN — nobody looked, and stalled would invent the observation', () => {
    expect(stalledIn(health({ a: 'UNKNOWN', b: 'UNKNOWN' }), [edge('a', 'b')])).toEqual([])
  })

  it('stalls on either stopped value at either end', () => {
    expect(stalledIn(health({ a: 'DISABLED', b: 'HEALTHY' }), [edge('a', 'b')])).toEqual(['a -> b'])
    expect(stalledIn(health({ a: 'UNHEALTHY', b: 'HEALTHY' }), [edge('a', 'b')])).toEqual(['a -> b'])
    expect(stalledIn(health({ a: 'HEALTHY', b: 'DISABLED' }), [edge('a', 'b')])).toEqual(['a -> b'])
    expect(stalledIn(health({ a: 'HEALTHY', b: 'UNHEALTHY' }), [edge('a', 'b')])).toEqual(['a -> b'])
  })

  it('reads a node with no state entry as UNKNOWN, which is not stopped', () => {
    // The canvas draws before the first /state response lands (ADR-0057).
    expect(stalledIn(new Map(), [edge('a', 'b')])).toEqual([])
  })
})

describe('starvation is every inbound edge, not any of them', () => {
  it('keeps a node fed by one live producer out of two producing', () => {
    // `stopped -> sink` stalls by rule 1 and `live -> sink` does not, so `sink` is not starved and
    // what it produces onward keeps flowing. "Any" here would stall half a healthy graph.
    const edges = [edge('stopped', 'sink'), edge('live', 'sink'), edge('sink', 'out')]
    expect(stalledIn(health({ stopped: 'DISABLED', live: 'HEALTHY', sink: 'HEALTHY', out: 'HEALTHY' }), edges)).toEqual([
      'stopped -> sink',
    ])
  })

  it('starves a node once its last live producer stops', () => {
    const edges = [edge('one', 'sink'), edge('two', 'sink'), edge('sink', 'out')]
    expect(stalledIn(health({ one: 'DISABLED', two: 'UNHEALTHY', sink: 'HEALTHY', out: 'HEALTHY' }), edges)).toEqual([
      'one -> sink',
      'two -> sink',
      'sink -> out',
    ])
  })

  it('never starves a node with no inbound edges — it stalls only on its own health', () => {
    expect(stalledIn(health({ source: 'HEALTHY', out: 'HEALTHY' }), [edge('source', 'out')])).toEqual([])
  })

  it('carries the blast radius the whole length of a chain', () => {
    const edges = [edge('a', 'b'), edge('b', 'c'), edge('c', 'd'), edge('d', 'e')]
    const allHealthy = { a: 'HEALTHY', b: 'HEALTHY', c: 'HEALTHY', d: 'HEALTHY', e: 'HEALTHY' } as const
    expect(stalledIn(health({ ...allHealthy, a: 'DISABLED' }), edges)).toEqual([
      'a -> b',
      'b -> c',
      'c -> d',
      'd -> e',
    ])
  })
})

describe('the fixed point terminates and a cycle stays live', () => {
  it('leaves a cycle nothing seeds alone', () => {
    // The relation vocabulary permits `service -> topic -> service`, and any pipeline with a retry
    // topic closes the loop. Least fixed point from the empty set: every node in the cycle is
    // waiting on the others and none of them ever starves.
    const edges = [edge('a', 'b'), edge('b', 'c'), edge('c', 'a')]
    expect(stalledIn(health({ a: 'HEALTHY', b: 'HEALTHY', c: 'HEALTHY' }), edges)).toEqual([])
  })

  it('keeps a retry loop live when only its external feed stops', () => {
    const edges = [edge('feed', 'a'), edge('a', 'b'), edge('b', 'a')]
    // `a`'s inbound is `feed -> a` and `b -> a`. The first stalls by rule 1; the second waits on
    // `b`, which starves only if `a` does. Nothing resolves, so the loop is its own live producer as
    // far as anything here can tell — which is the right answer, because data already circulating in
    // a retry loop is real data and stalling it would be a claim on no evidence.
    expect(stalledIn(health({ feed: 'DISABLED', a: 'HEALTHY', b: 'HEALTHY' }), edges)).toEqual(['feed -> a'])
  })

  it('stalls the whole loop when a node inside it stops', () => {
    const edges = [edge('a', 'b'), edge('b', 'c'), edge('c', 'a')]
    // Rule 1 on `a -> b`, then rule 3 carries it the rest of the way round.
    expect(stalledIn(health({ a: 'DISABLED', b: 'HEALTHY', c: 'HEALTHY' }), edges)).toEqual([
      'a -> b',
      'b -> c',
      'c -> a',
    ])
  })
})

describe('edge identity', () => {
  it('keeps two relations between one pair as two edges', () => {
    const edges: GraphEdge[] = [
      { ...edge('a', 'b'), relation: 'PRODUCES_TO' },
      { ...edge('a', 'b'), relation: 'CALLS' },
    ]
    const stalled = stalledEdges(edges, health({ a: 'HEALTHY', b: 'HEALTHY' }))
    expect(stalled.size).toBe(0)
  })

  it('compares keys case-folded, like every other comparison (ADR-0020)', () => {
    const stalled = stalledEdges([edge('Payments-API', 'raw')], health({ 'payments-api': 'DISABLED', raw: 'HEALTHY' }))
    expect(edgeIsStalled(edge('Payments-API', 'raw'), stalled)).toBe(true)
    expect(edgeIsStalled(edge('payments-api', 'RAW'), stalled)).toBe(true)
  })
})
