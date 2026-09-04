import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { crossesTeams, ownersByNodeKey } from './crossTeam'
import type { Graph, GraphEdge } from '../api/types'

const graph = JSON.parse(readFileSync('../fixtures/golden/graph-production.json', 'utf8')) as Graph
const owners = ownersByNodeKey(graph.nodes)

const edge = (fromKey: string, toKey: string): GraphEdge => ({
  fromKey,
  toKey,
  relation: 'SOURCES_FROM',
  metadata: {},
  sources: ['connect'],
  discoveredAt: '',
  updatedAt: '',
})

describe('the cross-team boundary is an edge predicate', () => {
  it('marks the hand-off the pipeline crosses at the connectors', () => {
    // `payments.events.enriched.v1` is payments-platform; `payments-es-sink` is data-platform. This
    // is the crossing the product exists to make visible, and it needs no Y axis to express.
    expect(crossesTeams(edge('payments.events.enriched.v1', 'payments-es-sink'), owners)).toBe(true)
    expect(crossesTeams(edge('payments.events.enriched.v1', 'payments-iceberg-sink'), owners)).toBe(true)
  })

  it('leaves an edge within one team alone', () => {
    expect(crossesTeams(edge('payments-es-sink', 'payments-events-v1'), owners)).toBe(false)
  })

  it('does not treat an unowned end as a crossing', () => {
    // `stripe-webhooks` and `payments-api` both have no owner in this slice: ownerKey is optional
    // and sparsely populated (ADR-0016), and a dangling one is ordinary (ADR-0048). Treating absence
    // as difference would mark most of a real graph and mark nothing usefully.
    expect(crossesTeams(edge('stripe-webhooks', 'payments-api'), owners)).toBe(false)
    expect(crossesTeams(edge('payments-api', 'payments.events.raw.v1'), owners)).toBe(false)
  })

  it('marks nothing the slice currently renders', () => {
    // Honest about today: both real crossings are the ADR-0041 SOURCES_FROM edges, which arrive with
    // `connect` in slice 4. Until then the treatment is built and correct with nothing to draw.
    expect(graph.edges.filter((candidate) => crossesTeams(candidate, owners))).toHaveLength(0)
  })
})
