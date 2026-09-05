// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { needsCanonicalizing, resolveNode, unresolvedSentence, unresolvedStateOf } from './resolve'
import type { Graph, PluginOutcome } from '../api/types'

const golden = (name: string) => JSON.parse(readFileSync(`../fixtures/golden/${name}`, 'utf8')) as Graph
const production = golden('graph-production.json')
const staging = golden('graph-staging.json')

const header = (plugin: string, outcome: PluginOutcome['outcome']): PluginOutcome => ({
  plugin,
  capability: 'DISCOVERY',
  outcome,
  reasons: [],
  recordedAt: outcome === null ? null : '2026-09-05T10:00:00Z',
})

describe('the parameter resolves by the folded key', () => {
  it('finds a node whose spelling the user got wrong', () => {
    // ADR-0094: exact matching was rejected because it would give one string two meanings. A user
    // pasting `Payments-API` finds the node through search (ADR-0067) and would fail through a deep
    // link — and the deep link is the half more likely to have arrived from a Kubernetes alert.
    expect(resolveNode('Payments-API', production.nodes)?.key).toBe('payments-api')
    expect(resolveNode('  payments-api  ', production.nodes)?.key).toBe('payments-api')
  })

  it('returns nothing for a node this environment does not carry', () => {
    // Drift is absence: staging is missing three of production's ten nodes.
    expect(resolveNode('trino-analytics', production.nodes)?.key).toBe('trino-analytics')
    expect(resolveNode('trino-analytics', staging.nodes)).toBeNull()
  })
})

describe('a non-canonical hit rewrites the URL, and a miss does not', () => {
  it('canonicalizes a spelling difference on a resolved hit', () => {
    // This keeps ADR-0095's retained parameter meaningful: if the same node had many spellings, the
    // retained string would be one of several and comparisons against it would be spelling-sensitive.
    // One node, one URL.
    expect(needsCanonicalizing('Payments-API', resolveNode('Payments-API', production.nodes))).toBe(true)
    expect(needsCanonicalizing('payments-api', resolveNode('payments-api', production.nodes))).toBe(false)
  })

  it('leaves a miss exactly as given, because there is no canonical form to rewrite it to', () => {
    expect(needsCanonicalizing('Trino-Analytics', resolveNode('Trino-Analytics', staging.nodes))).toBe(false)
  })
})

describe('an unresolved parameter has three states, chosen from plugins[]', () => {
  const allComplete = production.plugins

  it('says nothing over a cold empty canvas, because the canvas already said it', () => {
    // ADR-0095, transposing ADR-0088's reasoning: the canvas empty state is unmissable, and a drawer
    // stacked on it is a second copy of one sentence.
    expect(unresolvedStateOf([], [header('yaml', null), header('kubernetes', null)])).toBe('SILENT')
    expect(unresolvedStateOf([], [])).toBe('SILENT')
  })

  it('asserts the absence outright when every plugin reported COMPLETE', () => {
    expect(unresolvedStateOf(staging.nodes, allComplete)).toBe('NO_SUCH_NODE')
    expect(unresolvedSentence('NO_SUCH_NODE', 'Staging', 'trino-analytics')).toBe(
      'No node in Staging matches “trino-analytics”.',
    )
  })

  it('makes a weaker claim when anything is null, PARTIAL or FAILED', () => {
    // ADR-0095's sharp case. With `yaml` unreported the fixture's four declared-only nodes are
    // silently missing and `trino-analytics` is one of them, so "no node matches trino-analytics" is
    // a confident false negative printed directly beneath a banner reading "this graph may be
    // incomplete." The third sentence is a **different claim, not a hedged first one**.
    for (const outcome of [null, 'PARTIAL', 'FAILED'] as const) {
      expect(unresolvedStateOf(production.nodes, [header('yaml', outcome), ...allComplete.slice(1)])).toBe(
        'NOT_IN_WHAT_WAS_READ',
      )
    }
    expect(unresolvedSentence('NOT_IN_WHAT_WAS_READ', 'Production', 'trino-analytics')).toBe(
      '“trino-analytics” is not in what has been read of Production.',
    )
  })

  it('still speaks over a non-empty graph whose environment is merely stale', () => {
    // The empty-and-cold branch is the only silent one. A drawn graph always gets a sentence,
    // because a silently-ignored `?node=` renders identically to a link that never carried one.
    expect(unresolvedStateOf(production.nodes, [header('yaml', null)])).toBe('NOT_IN_WHAT_WAS_READ')
  })

  it('speaks over an empty graph that was genuinely read and is genuinely empty', () => {
    // ADR-0087's third state is "No nodes in production", which is a *finding* rather than a wait —
    // so the drawer is not redundant with it and the parameter still deserves an answer.
    expect(unresolvedStateOf([], allComplete)).toBe('NO_SUCH_NODE')
  })
})
