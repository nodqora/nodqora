import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { Rank, RESULT_LIMIT, noResults, search } from './search'
import type { Graph } from '../api/types'

const golden = (name: string) => JSON.parse(readFileSync(`../fixtures/golden/${name}`, 'utf8')) as Graph
const production = golden('graph-production.json')
const staging = golden('graph-staging.json')

const keys = (query: string, graph: Graph = production) =>
  search(query, graph.nodes).map((result) => result.node.key)

describe('the brief was wrong and both hits are correct', () => {
  it('returns payments-enricher and payments-events-v1 for `payments-e`', () => {
    // The reference pipeline's own list of hard cases says "search must disambiguate, not just
    // substring-match". ADR-0067 corrects it: the user has typed a prefix that two nodes genuinely
    // share, so there is nothing to separate. Disambiguation is the result list's job.
    expect(keys('payments-e')).toEqual(
      expect.arrayContaining(['payments-enricher', 'payments-events-v1']),
    )
  })

  it('ranks them deterministically rather than returning fewer of them', () => {
    const results = search('payments-e', production.nodes)

    // Both are prefix hits on the key, so the tier is shared and the tiebreak is alphabetical.
    expect(results.every((result) => result.rank === Rank.PrefixOfName)).toBe(true)
    expect(results.map((r) => r.node.key)).toEqual([...results.map((r) => r.node.key)].sort())
  })
})

describe('the rank order is total', () => {
  it('puts an exact key above every other tier', () => {
    const results = search('payments-api', production.nodes)

    expect(results[0]!.node.key).toBe('payments-api')
    expect(results[0]!.rank).toBe(Rank.ExactKey)
  })

  it('ranks a prefix above a mid-string substring', () => {
    const results = search('enricher', production.nodes)
    const enricher = results.find((result) => result.node.key === 'payments-enricher')!

    // `payments-enricher` contains `enricher` but does not start with it, so it is a substring hit
    // on the name — below any prefix hit and above any backing hit.
    expect(enricher.rank).toBe(Rank.SubstringOfName)
  })

  it('is stable under a reordered input, because an unstable order flaps the top hit', () => {
    // ADR-0050 forced canonical collection ordering one layer down for the same reason: the same
    // query must not surface a different top hit on different renders.
    expect(keys('payments')).toEqual(
      search('payments', [...production.nodes].reverse()).map((result) => result.node.key),
    )
  })
})

describe('backings are searchable, whole and by segment', () => {
  it('finds a node by a Kubernetes name the node does not display', () => {
    // The SRE's clipboard holds `enricher-v2`, not `payments-prod/enricher-v2` — the string arrived
    // from a Kubernetes alert. ADR-0023's alternate-name index, reached through search.
    const results = search('enricher-v2', production.nodes)

    expect(results).toHaveLength(1)
    expect(results[0]!.node.key).toBe('payments-enricher')
    expect(results[0]!.rank).toBe(Rank.BackingSegment)
  })

  it('reports what matched, so a hit the node does not display is not a bug', () => {
    // ADR-0068's "matched via" line, shown *only* when the hit came from a backing.
    const viaBacking = search('enricher-v2', production.nodes)[0]!
    const viaKey = search('payments-api', production.nodes)[0]!

    expect(viaBacking.matchedVia).toEqual({ kind: 'deployment', reference: 'payments-prod/enricher-v2' })
    expect(viaKey.matchedVia).toBeNull()
  })

  it('matches the whole reference too, for a paste from a kubectl context', () => {
    const results = search('payments-prod/payments-api', production.nodes)

    expect(results.map((result) => result.node.key)).toEqual(['payments-api'])
    expect(results[0]!.rank).toBe(Rank.WholeBackingReference)
  })

  it('leaks the namespace through the segment rule, and that is accepted rather than fixed', () => {
    // ADR-0066 records this as a known leak: splitting makes `payments-prod` a token on every
    // Kubernetes-backed node, so typing it returns the whole namespace — owner-filtering by the back
    // door. Suppressing it would require the frontend to know which segment of a plugin's reference
    // is a namespace, which ADR-0015 forbids. ADR-0101 says to feel it, not to guard against it.
    expect(keys('payments-prod').length).toBeGreaterThan(1)
  })
})

describe('the search set is exactly the identifying strings', () => {
  it('does not match an owner key, because that is the filter set ADR-0018 deferred', () => {
    // `payments-platform` owns four fixture nodes and `data-platform` four more. Widening to
    // `ownerKey` is not search; it is filter-by-owner arriving through the search box without being
    // designed, and it produces result rows with no distinguishing string to display.
    expect(keys('data-platform')).toEqual([])
  })

  it('does not match a type', () => {
    expect(keys('iceberg-table')).toEqual([])
  })

  it('does not match a link URL, even though the client already holds one', () => {
    expect(keys('grafana.acme.io')).toEqual([])
  })
})

describe('case folding, because the identity model is case-insensitive', () => {
  it('finds a node by a string that *is* its key in the wrong case', () => {
    // ADR-0067: a case-sensitive search over a case-insensitive identity model would let a user type
    // a string that is the node key and get nothing.
    expect(keys('PAYMENTS-API')).toContain('payments-api')
    expect(search('PAYMENTS-API', production.nodes)[0]!.rank).toBe(Rank.ExactKey)
  })

  it('trims, for the same reason the server does', () => {
    expect(keys('  payments-api  ')).toContain('payments-api')
  })
})

describe('the empty query and the empty result', () => {
  it('matches nothing on an empty query rather than everything', () => {
    // There is no minimum length (ADR-0068), but an empty box is the control's resting state rather
    // than a request for the whole environment.
    expect(search('', production.nodes)).toEqual([])
    expect(search('   ', production.nodes)).toEqual([])
  })

  it('names the environment when nothing matches', () => {
    // ADR-0070: zero results is not an edge case here. Staging is missing three of production's ten
    // nodes, so an SRE scoped to staging who searches `trino` correctly concludes "no such node"
    // about a node that exists one environment over. Naming the scope is the whole mitigation.
    expect(keys('trino', staging)).toEqual([])
    expect(keys('trino', production)).toEqual(['trino-analytics'])
    expect(noResults('Staging', 'trino')).toBe('No node in Staging matches “trino”.')
  })
})

describe('the cap keeps the ranking honest', () => {
  it('returns every match and leaves the truncation to the caller', () => {
    // The list is capped at ten with a count line, because a total order over thirty substring hits
    // is deterministic but only *informative* at the top. Returning everything here is what lets the
    // count line say "10 of 34" rather than the list silently stopping.
    const all = search('payments', production.nodes)

    expect(all.length).toBeGreaterThan(0)
    expect(all.slice(0, RESULT_LIMIT).length).toBeLessThanOrEqual(RESULT_LIMIT)
  })
})
