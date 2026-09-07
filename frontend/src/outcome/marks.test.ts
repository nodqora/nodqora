// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { blindPlugins, healthCaveat, marksOf, retained, retainedSources } from './marks'
import { labeller } from './plugins'
import type { Graph, GraphNode, PluginOutcome, PluginRef, Source } from '../api/types'

const graph = JSON.parse(readFileSync('../fixtures/golden/graph-production.json', 'utf8')) as Graph
const node = (key: string): GraphNode => graph.nodes.find((candidate) => candidate.key === key)!

const REGISTRY: PluginRef[] = [
  { id: 'yaml', displayLabel: 'yaml', capabilities: ['DISCOVERY'] },
  { id: 'kubernetes', displayLabel: 'kubernetes', capabilities: ['DISCOVERY', 'HEALTH'] },
  { id: 'kafka', displayLabel: 'kafka', capabilities: ['DISCOVERY', 'HEALTH'] },
  { id: 'connect', displayLabel: 'connect', capabilities: ['DISCOVERY', 'HEALTH'] },
]

const header = (
  plugin: string,
  capability: 'DISCOVERY' | 'HEALTH',
  outcome: PluginOutcome['outcome'],
  recordedAt: string | null,
): PluginOutcome => ({ plugin, capability, outcome, reasons: [], recordedAt })

const source = (plugin: string, confirmedAt: string | null): Source => ({ plugin, confirmedAt })

describe('retention is a comparison, not a timeout', () => {
  const discovery = [header('connect', 'DISCOVERY', 'PARTIAL', '2026-09-05T10:23:00Z')]

  it('marks a key the poll did not confirm', () => {
    // ADR-0084: a PARTIAL upserts the keys present and retains the keys absent, so the retained
    // ones keep an older `confirmed_at` while the header advances.
    expect(retained(source('connect', '2026-09-05T10:00:00Z'), discovery)).toBe(true)
  })

  it('leaves a key the same poll did confirm unmarked', () => {
    // A COMPLETE replaces its snapshot, so every key it carries was confirmed at that poll and the
    // two timestamps agree. That is what makes this per key rather than per plugin: a PARTIAL that
    // confirmed eight nodes and retained two marks exactly two.
    expect(retained(source('connect', '2026-09-05T10:23:00Z'), discovery)).toBe(false)
  })

  it('holds no staleness constant at all', () => {
    // The frontend never decides what counts as old. A plugin polling at fifteen minutes shows a
    // large elapsed time with no tag, which is correct and was the failure mode of every threshold
    // considered — ADR-0059 publishes only the *minimum* over cadences, so a threshold could be
    // applied at the tightest plugin's rate and nowhere else.
    const yesterday = source('connect', '2026-09-04T10:00:00Z')
    const stillCurrent = [header('connect', 'DISCOVERY', 'COMPLETE', '2026-09-04T10:00:00Z')]

    expect(retained(yesterday, stillCurrent)).toBe(false)
  })

  it('is unreachable rather than undefined on a cold store', () => {
    // ADR-0085: a plugin that has never reported appears in zero nodes' `sources[]`, so the
    // comparison never meets a null on either side. Defended anyway, because the shipped documents
    // normalize timestamps and a parse failure must not invent a mark.
    expect(retained(source('connect', null), [header('connect', 'DISCOVERY', null, null)])).toBe(false)
    expect(retained(source('connect', '2026-09-05T10:00:00Z'), [])).toBe(false)
  })

  it('marks every source through a total outage, because the header still advances', () => {
    // ADR-0086's whole point: a FAILED writes the header and touches no entry, so every key is
    // older. Under ADR-0071's literal reading both timestamps would sit still and nothing would be
    // marked — retention would silently stop working during exactly the outage it is for.
    const failed = [header('connect', 'DISCOVERY', 'FAILED', '2026-09-05T10:30:00Z')]

    expect(retained(source('connect', '2026-09-05T10:00:00Z'), failed)).toBe(true)
  })

  it('reports which plugins were retained on a real node', () => {
    const enricher = node('payments-enricher')
    const discoveryHeaders = [
      header('yaml', 'DISCOVERY', 'COMPLETE', '2026-09-05T10:30:00Z'),
      header('kubernetes', 'DISCOVERY', 'FAILED', '2026-09-05T10:30:00Z'),
    ]
    const withTimes: GraphNode = {
      ...enricher,
      sources: [source('yaml', '2026-09-05T10:30:00Z'), source('kubernetes', '2026-09-05T10:00:00Z')],
    }

    expect(retainedSources(withTimes, discoveryHeaders)).toEqual(['kubernetes'])
  })
})

describe('blind is a different mark, because it is a different problem', () => {
  const unreachable = { discovery: graph.plugins, health: [header('connect', 'HEALTH', 'FAILED', 'now')] }

  it('marks a connector whose connect backing could not look', () => {
    // ADR-0026's own worked example. With `connect` unreachable, ADR-0024 discards the abstention
    // and `payments-es-sink` composes down to kubernetes at 2/2 and kafka at lag 120, rendering
    // HEALTHY while nobody knows what its tasks are doing. Green because we stopped looking.
    expect(blindPlugins(node('payments-es-sink'), unreachable, REGISTRY)).toEqual(['connect'])
    expect(blindPlugins(node('payments-iceberg-sink'), unreachable, REGISTRY)).toEqual(['connect'])
  })

  it('leaves an honestly DISABLED node calm and unmarked', () => {
    // ADR-0083's question four, answered without opening a node: the enricher is DISABLED because
    // somebody scaled it to zero, and it has no `connect` backing, so it carries no mark while the
    // two dishonestly-calm connectors do. Under ADR-0017 alone all three render quiet.
    expect(blindPlugins(node('payments-enricher'), unreachable, REGISTRY)).toEqual([])
  })

  it('never marks the four permanently-UNKNOWN nodes, by construction', () => {
    // ADR-0083: they have no health-capable backings to abstain, so the cry-wolf constraint holds
    // without a special case. Asserted here because the guarantee is structural — if `blind` ever
    // read `sources[]` instead of `backings[]`, all four would light up.
    for (const key of ['stripe-webhooks', 'payments-events-v1', 'analytics.payments_events', 'trino-analytics']) {
      expect(blindPlugins(node(key), unreachable, REGISTRY)).toEqual([])
    }
  })

  it('counts a plugin that has never reported as blind, on the same mark', () => {
    // ADR-0088: a node whose `connect` backing has never been read is missing precisely the same
    // contribution as one whose `connect` abstained this cycle, so `UNKNOWN`-because-nobody-has-
    // looked-yet stops rendering identically to `UNKNOWN`-because-nothing-watches-this.
    const cold = { discovery: graph.plugins, health: [header('connect', 'HEALTH', null, null)] }

    expect(blindPlugins(node('payments-es-sink'), cold, REGISTRY)).toEqual(['connect'])
  })

  it('does not mark a plugin that reported COMPLETE', () => {
    const healthy = { discovery: graph.plugins, health: [header('connect', 'HEALTH', 'COMPLETE', 'now')] }

    expect(blindPlugins(node('payments-es-sink'), healthy, REGISTRY)).toEqual([])
  })

  it('ignores a discovery-only plugin, which has no observation to abstain from', () => {
    // `yaml` declares no Health capability (ADR-0010), so it can never be blind however its
    // discovery poll went — that is the retained mark's business, not this one's.
    const yamlOut = { discovery: graph.plugins, health: [header('yaml', 'HEALTH', 'FAILED', 'now')] }

    expect(blindPlugins(node('payments-api'), yamlOut, REGISTRY)).toEqual([])
  })
})

describe('a node can carry both marks, and they say different things', () => {
  it('separates a retained topology from an under-observed health', () => {
    const rosters = {
      discovery: [header('connect', 'DISCOVERY', 'PARTIAL', '2026-09-05T10:30:00Z')],
      health: [header('connect', 'HEALTH', 'FAILED', '2026-09-05T10:30:00Z')],
    }
    const sink: GraphNode = { ...node('payments-es-sink'), sources: [source('connect', '2026-09-05T10:00:00Z')] }

    // A retained node's topology is old and its health is current; a blind node's topology is
    // current and its health is under-observed. The same mark for both would say neither.
    expect(marksOf(sink, rosters, REGISTRY)).toEqual({ retained: ['connect'], blind: ['connect'] })
  })
})

describe('the caveat names what the value was actually composed from', () => {
  const label = labeller(REGISTRY)

  it('states what a failed poll does, rather than asserting a reading it cannot see', () => {
    // ADR-0106. ADR-0072 retains contributions per plugin by ADR-0046's rules, so a FAILED health run
    // is a no-op on the contribution store and `connect`'s last reading may still be in the collapse.
    // "Composed from kubernetes + kafka only" would be false — and worse than saying nothing, because
    // the node reads calm partly on evidence nobody has re-checked. The frontend cannot know whether
    // *this* plugin has a stored contribution (ADR-0057 exposes none), so it names the rule.
    const rosters = {
      discovery: graph.plugins,
      health: [header('connect', 'HEALTH', 'FAILED', '2026-09-05T10:00:00Z')],
    }

    expect(healthCaveat(node('payments-es-sink'), rosters, REGISTRY, label, true)).toBe(
      'connect could not observe this node this cycle. A failed poll does not clear what it last reported, so the value above may include a reading nobody has re-checked.',
    )
  })

  it('names absence when the plugin has never polled, because there is nothing to carry', () => {
    // ADR-0088's case, and the one ADR-0083's original sentence is exactly right about. A pair with
    // no `recordedAt` has written no contribution, so "composed from the others only" is an
    // arithmetic certainty rather than an assumption.
    const rosters = { discovery: graph.plugins, health: [header('connect', 'HEALTH', null, null)] }

    expect(healthCaveat(node('payments-es-sink'), rosters, REGISTRY, label, false)).toBe(
      'connect has not observed this node yet. The value above is composed from kubernetes + kafka only.',
    )
  })

  it('never claims a carried reading on a node with no observation at all', () => {
    // The bug the running application caught. `recordedAt` says the pair *polled*, not that it ever
    // succeeded — ADR-0086 writes the header on a failed poll — so a plugin that has only ever failed
    // still has one. Reading it as "has a stored reading" printed a sentence about a retained value
    // directly beneath "Raw signal: nothing observes this node" and "Observed: never".
    const rosters = {
      discovery: graph.plugins,
      health: [header('kafka', 'HEALTH', 'FAILED', '2026-09-05T10:00:00Z')],
    }

    expect(healthCaveat(node('payments-enricher'), rosters, REGISTRY, label, false)).toBe(
      'kafka could not observe this node this cycle. The value above is composed from kubernetes only.',
    )
  })

  it('says nothing on an ordinary day', () => {
    const rosters = { discovery: graph.plugins, health: [header('connect', 'HEALTH', 'COMPLETE', 'now')] }

    expect(healthCaveat(node('payments-es-sink'), rosters, REGISTRY, label, true)).toBeNull()
  })
})
