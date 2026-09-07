// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { bannerSentence, bannersOf, chipText, labeller, popoverRows } from './plugins'
import type { Graph, PluginOutcome, State } from '../api/types'

const golden = (name: string) => JSON.parse(readFileSync(`../fixtures/golden/${name}`, 'utf8'))
const shipped = {
  discovery: (golden('graph-production.json') as Graph).plugins,
  health: (golden('state-production-baseline.json') as State).plugins,
}

const pair = (
  plugin: string,
  capability: 'DISCOVERY' | 'HEALTH',
  outcome: PluginOutcome['outcome'],
  reasons: string[] = [],
): PluginOutcome => ({
  plugin,
  capability,
  outcome,
  reasons,
  recordedAt: outcome === null ? null : '2026-09-05T10:00:00Z',
})

describe('the chip counts states rather than ranking them', () => {
  it('reads `all complete` on the shipped fixture', () => {
    // Seven pairs over four plugins: four discovery, three health. `yaml` declares no Health
    // capability, so it is configured for one capability and not the other — and the denominator is
    // still four, because it counts plugins rather than pairs.
    expect(chipText(shipped)).toBe('4 plugins · all complete')
  })

  it('names every state present, with counts, and drops none of them', () => {
    // ADR-0089: a worst-first phrase requires a ranking that does not exist. Under ADR-0086 a FAILED
    // is actionable *now* — fix the kubeconfig — while unreported is *wait*. Neither dominates, and
    // a ladder makes the chip quietly drop whichever it ranked second, which on a fresh install is
    // the one you most need to see.
    const mixed = {
      discovery: [
        pair('yaml', 'DISCOVERY', 'COMPLETE'),
        pair('kubernetes', 'DISCOVERY', 'FAILED', ['connection refused']),
        pair('kafka', 'DISCOVERY', null),
        pair('connect', 'DISCOVERY', null),
      ],
      health: [],
    }

    expect(chipText(mixed)).toBe('4 plugins · 1 failed, 2 not reported')
  })

  it('reads `none reported` on a cold store', () => {
    const cold = {
      discovery: [pair('yaml', 'DISCOVERY', null), pair('kubernetes', 'DISCOVERY', null)],
      health: [pair('kubernetes', 'HEALTH', null)],
    }

    expect(chipText(cold)).toBe('2 plugins · none reported')
  })

  it('reads `no plugins configured` on an empty roster', () => {
    // ADR-0087's first canvas state has a chip to match: a zero-plugin environment is a config
    // error, and folding it into "not read yet" would render it as a transient wait.
    expect(chipText({ discovery: [], health: [] })).toBe('0 plugins · no plugins configured')
  })

  it('keeps the denominator a config count, so it never shrinks because nothing reported', () => {
    // ADR-0085: `plugins[]` is a config roster left-joined with the store. Read as a store
    // projection, a cold environment would have no entries at all and the chip would read
    // `0 plugins` — a fresh deployment indistinguishable from an unconfigured one.
    const cold = { discovery: shipped.discovery.map((p) => ({ ...p, outcome: null, recordedAt: null })), health: [] }

    expect(chipText(cold)).toBe('4 plugins · none reported')
  })

  it('reads as progress rather than as a fault during repopulation', () => {
    // The mitigation ADR-0087 could point at but did not design: the plugins repopulate
    // independently, so a roster moves `none reported` → `2 not reported` → `all complete`.
    const halfway = {
      discovery: [
        pair('yaml', 'DISCOVERY', 'COMPLETE'),
        pair('kubernetes', 'DISCOVERY', 'COMPLETE'),
        pair('kafka', 'DISCOVERY', null),
        pair('connect', 'DISCOVERY', null),
      ],
      health: [],
    }

    expect(chipText(halfway)).toBe('4 plugins · 2 not reported')
  })
})

describe('banners are an exception surface', () => {
  const label = labeller([])

  it('renders nothing while every outcome is COMPLETE', () => {
    // ADR-0081: the chip is the only standing cost. Nothing else renders on an ordinary day.
    expect(bannersOf(shipped, () => 0, false)).toEqual([])
  })

  it('names the plugin, the capability, the cause and the affected node count', () => {
    const rosters = { discovery: [], health: [pair('connect', 'HEALTH', 'FAILED', ['GET /connectors failed'])] }

    const [banner] = bannersOf(rosters, () => 2, false)

    expect(banner!.affected).toBe(2)
    expect(bannerSentence(banner!, label)).toBe(
      'connect could not read health for this environment — GET /connectors failed. 2 nodes affected.',
    )
  })

  it('gives an unreported pair no cause and no count, because neither exists', () => {
    // ADR-0088: an unreported plugin has said nothing about what it would have carried, so there is
    // no computable affected count — and fabricating one is what ADR-0058 forbids in general terms
    // and ADR-0079 already refused in this exact situation.
    const rosters = { discovery: [pair('yaml', 'DISCOVERY', null)], health: [] }

    const [banner] = bannersOf(rosters, () => 99, false)

    expect(banner!.cause).toBeNull()
    expect(banner!.affected).toBeNull()
    expect(bannerSentence(banner!, label)).toBe(
      'yaml has not reported discovery yet. This graph may be incomplete.',
    )
  })

  it('suppresses the unreported banner over an empty graph and keeps it over a drawn one', () => {
    // ADR-0088. The all-cold case is already covered by ADR-0087's empty state, and a banner stacked
    // over an empty canvas is a second copy of one sentence. The mixed case has no other surface at
    // all: the canvas draws a graph that looks whole, and a node that is not there cannot be marked.
    const rosters = {
      discovery: [pair('yaml', 'DISCOVERY', null), pair('kubernetes', 'DISCOVERY', 'COMPLETE')],
      health: [],
    }

    expect(bannersOf(rosters, () => 0, true)).toEqual([])
    expect(bannersOf(rosters, () => 0, false)).toHaveLength(1)
  })

  it('still banners a FAILED over an empty graph, because that one has a cause to give', () => {
    // Only the *unreported* variant is suppressed. A FAILED over an empty graph is ADR-0086's
    // actionable case — `kubernetes · failed · connection refused` — and the empty state's "has not
    // been read yet" does not say it.
    const rosters = { discovery: [pair('kubernetes', 'DISCOVERY', 'FAILED', ['connection refused'])], health: [] }

    expect(bannersOf(rosters, () => 0, true)).toHaveLength(1)
  })
})

describe('the popover carries every configured pair', () => {
  it('lists both capabilities of every plugin, reported or not', () => {
    const rows = popoverRows(shipped)

    expect(rows).toHaveLength(7)
    expect(rows.filter((row) => row.capability === 'DISCOVERY')).toHaveLength(4)
    expect(rows.filter((row) => row.capability === 'HEALTH')).toHaveLength(3)
    // `yaml` declares no Health capability (ADR-0010), so it has one row rather than two.
    expect(rows.filter((row) => row.plugin === 'yaml')).toHaveLength(1)
  })

  it('carries a null recordedAt through rather than inventing a time', () => {
    const rows = popoverRows({ discovery: [pair('kafka', 'DISCOVERY', null)], health: [] })

    expect(rows[0]!.recordedAt).toBeNull()
    expect(rows[0]!.outcome).toBeNull()
  })
})
