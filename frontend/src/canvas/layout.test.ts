import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { layout } from './layout'
import type { Graph } from '../api/types'

const golden = (name: string) => JSON.parse(readFileSync(`../fixtures/golden/${name}`, 'utf8'))

/*
 * Slice 1 wrote this suite against §3's complete edge set while `yaml` owned only seven of the nine,
 * supplying the two ADR-0041 `SOURCES_FROM` edges by hand — because ADR-0016's claim is about the
 * fixture's documented shape rather than about how much of it one slice has built, and ADR-0098 put
 * the claim first so that the cost of it being wrong was paid while it was lowest.
 *
 * `connect` closed the set, so the hand-supplied edges are gone and every test below reads the graph
 * golden as it ships. The bet paid: the layering the goldens have asserted since slice 1 is what the
 * application now produces from its own document, with nothing here adjusted to meet it.
 */

describe.each(['production', 'staging'])('%s', (environment) => {
  const graph = golden(`graph-${environment}.json`) as Graph
  const expected = golden(`layout-${environment}.json`)
  const edges = graph.edges

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
  const placements = layout(graph.nodes, graph.edges)
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
    const shuffled = layout([...graph.nodes].reverse(), [...graph.edges].reverse())

    expect(new Map(shuffled.map((p) => [p.key, `${p.column},${p.row}`]))).toEqual(
      new Map(placements.map((p) => [p.key, `${p.column},${p.row}`])),
    )
  })
})

describe('what the slice actually renders', () => {
  it('lays out the shipped graph with the connectors downstream of the topic they read', () => {
    const graph = golden('graph-production.json') as Graph
    const columns = Object.fromEntries(
      layout(graph.nodes, graph.edges).map((placement) => [placement.key, placement.column]),
    )

    // What the two SOURCES_FROM edges changed. Until slice 4 both connectors had no incoming edge
    // and sat at column 0 as sources of their own short chains — the pipeline drew as three
    // disconnected fragments. One edge each puts them behind the topic they read, and §1's single
    // left-to-right flow is a consequence of the graph rather than of anything laid out by hand.
    expect(columns['stripe-webhooks']).toBe(0)
    expect(columns['payments.events.enriched.v1']).toBe(4)
    expect(columns['payments-es-sink']).toBe(5)
    expect(columns['payments-iceberg-sink']).toBe(5)

    // The layering is now the golden's outright, with nothing supplied to it.
    expect(columns).toEqual(golden('layout-production.json').layers)
  })
})
