import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { connectionsOf } from './connections'
import { highlightFrom } from '../canvas/highlight'
import type { Graph } from '../api/types'

const graph = JSON.parse(readFileSync('../fixtures/golden/graph-production.json', 'utf8')) as Graph

describe('connections read naturally from either end', () => {
  it('phrases a reversed relation against the flow', () => {
    const enricher = connectionsOf('payments-enricher', graph.edges, graph.relationDescriptors)
    const topic = connectionsOf('payments.events.raw.v1', graph.edges, graph.relationDescriptors)

    // One stored edge, `payments.events.raw.v1 -> payments-enricher`, read from both ends.
    expect(enricher.upstream).toContainEqual({
      peerKey: 'payments.events.raw.v1',
      phrasing: 'consumes from',
      relation: 'CONSUMES_FROM',
    })
    expect(topic.downstream).toContainEqual({
      peerKey: 'payments-enricher',
      phrasing: 'delivers to',
      relation: 'CONSUMES_FROM',
    })
  })

  it('phrases a forward relation with the flow', () => {
    const api = connectionsOf('payments-api', graph.edges, graph.relationDescriptors)

    expect(api.upstream).toContainEqual({
      peerKey: 'stripe-webhooks',
      phrasing: 'is called by',
      relation: 'CALLS',
    })
    expect(api.downstream.map((connection) => connection.phrasing)).toContain('produces to')
  })

  it('falls back to the bare relation when no descriptor is registered', () => {
    const connections = connectionsOf('payments-api', graph.edges, [])

    // ADR-0001: unknown is a display state, never an error.
    expect(connections.downstream[0]!.phrasing).toBe('PRODUCES_TO')
  })
})

describe('traversal is uniform because edges are stored flow-directed', () => {
  it('follows the declared tail two hops past the last discovered node', () => {
    const highlight = highlightFrom('payments-enricher', graph.edges)

    // The test's own name, finally assertable. Until `connect` supplied the two SOURCES_FROM edges
    // the sinks were disconnected from `enriched.v1`, so the tail stopped there; now the walk runs
    // enricher → enriched.v1 → iceberg-sink → analytics.payments_events → trino-analytics, and the
    // last two of those are declared nodes no plugin observes. Traversal counts them like any other
    // (§10), and it is unbounded, so reaching them costs no depth setting.
    expect([...highlight.downstream]).toContain('payments.events.enriched.v1')
    expect([...highlight.downstream]).toContain('trino-analytics')
    expect([...highlight.downstream]).toContain('payments-events-v1')
    expect([...highlight.upstream]).toEqual(
      expect.arrayContaining(['payments.events.raw.v1', 'payments-api', 'stripe-webhooks']),
    )
  })

  it('is unbounded, so a long declared chain is fully reached', () => {
    const highlight = highlightFrom('payments-iceberg-sink', graph.edges)

    // payments-iceberg-sink -> analytics.payments_events -> trino-analytics, three hops of nodes
    // that no plugin observes. Any default depth would hide exactly this.
    expect([...highlight.downstream].sort()).toEqual(['analytics.payments_events', 'trino-analytics'])
  })

  it('excludes the selection itself from both directions', () => {
    const highlight = highlightFrom('payments-enricher', graph.edges)

    expect(highlight.upstream.has('payments-enricher')).toBe(false)
    expect(highlight.downstream.has('payments-enricher')).toBe(false)
  })
})
