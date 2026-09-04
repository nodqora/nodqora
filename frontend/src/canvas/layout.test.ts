import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { layout } from './layout'
import type { Graph, GraphEdge } from '../api/types'

const golden = (name: string) => JSON.parse(readFileSync(`../fixtures/golden/${name}`, 'utf8'))

/**
 * The two `SOURCES_FROM` edges `connect` infers from its `topics` key (ADR-0041). They are the only
 * two of the fixture's nine that `yaml` does not own, and they arrive in slice 4.
 *
 * They are supplied here rather than waited for, because ADR-0016's claim is about the fixture's
 * documented shape and not about how much of it one slice has built — and ADR-0098 puts this claim
 * first precisely so the cost of it being wrong is paid while it is lowest, with ADR-0018, ADR-0019
 * and ADR-0069 all resting on it.
 */
const AWAITING_CONNECT: Record<string, GraphEdge[]> = {
  production: [
    edge('payments.events.enriched.v1', 'payments-es-sink'),
    edge('payments.events.enriched.v1', 'payments-iceberg-sink'),
  ],
  staging: [edge('payments.events.enriched.v1', 'payments-es-sink')],
}

function edge(fromKey: string, toKey: string): GraphEdge {
  return {
    fromKey,
    toKey,
    relation: 'SOURCES_FROM',
    metadata: {},
    sources: ['connect'],
    discoveredAt: '',
    updatedAt: '',
  }
}

describe.each(['production', 'staging'])('%s', (environment) => {
  const graph = golden(`graph-${environment}.json`) as Graph
  const expected = golden(`layout-${environment}.json`)
  const edges = [...graph.edges, ...(AWAITING_CONNECT[environment] ?? [])]

  it('layers exactly as docs/reference-pipeline.md §1 draws it', () => {
    const columns = Object.fromEntries(
      layout(graph.nodes, edges).map((placement) => [placement.key, placement.column]),
    )

    expect(columns).toEqual(expected.layers)
  })

  it('places every node exactly once, with no two sharing a slot', () => {
    const placements = layout(graph.nodes, edges)
    const slots = placements.map((placement) => `${placement.column},${placement.row}`)

    expect(placements).toHaveLength(graph.nodes.length)
    expect(new Set(slots).size).toBe(slots.length)
  })
})

describe('slot assignment', () => {
  const graph = golden('graph-production.json') as Graph
  const placements = layout(graph.nodes, [...graph.edges, ...AWAITING_CONNECT.production!])
  const rowOf = (key: string) => placements.find((placement) => placement.key === key)!.row

  it('keeps a branch on its predecessor row', () => {
    // The pipeline runs straight along one row until the fan-out at `enriched.v1`, where the first
    // sink keeps the row and the second takes the next free one. This is what makes the Iceberg
    // branch read as a branch rather than as a second unrelated pipeline.
    expect(rowOf('stripe-webhooks')).toBe(0)
    expect(rowOf('payments.events.enriched.v1')).toBe(0)
    expect(rowOf('payments-es-sink')).toBe(0)
    expect(rowOf('payments-events-v1')).toBe(0)

    expect(rowOf('payments-iceberg-sink')).toBe(1)
    expect(rowOf('analytics.payments_events')).toBe(1)
    expect(rowOf('trino-analytics')).toBe(1)
  })

  it('is a function of the graph, not of the order it arrived in', () => {
    const shuffled = layout([...graph.nodes].reverse(), [...graph.edges, ...AWAITING_CONNECT.production!].reverse())

    expect(new Map(shuffled.map((p) => [p.key, `${p.column},${p.row}`]))).toEqual(
      new Map(placements.map((p) => [p.key, `${p.column},${p.row}`])),
    )
  })
})

describe('what the slice actually renders', () => {
  it('lays out the yaml-only graph without the connectors having an inbound edge yet', () => {
    const graph = golden('graph-production.json') as Graph
    const columns = Object.fromEntries(
      layout(graph.nodes, graph.edges).map((placement) => [placement.key, placement.column]),
    )

    // Honest about today: with the two SOURCES_FROM edges still to come, both connectors have no
    // incoming edge and therefore sit at column 0 as sources of their own short chains. Slice 4
    // closes the graph and this becomes the golden above.
    expect(columns['payments-es-sink']).toBe(0)
    expect(columns['payments-iceberg-sink']).toBe(0)
    expect(columns['stripe-webhooks']).toBe(0)
    expect(columns['payments.events.enriched.v1']).toBe(4)
  })
})
