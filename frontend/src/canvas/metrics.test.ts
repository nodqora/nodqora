// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { ageOf, compact, milliseconds, summarizeMetrics } from './metrics'
import { healthLabel } from './HealthGlyph'
import type { Health, State } from '../api/types'

const baseline = JSON.parse(
  readFileSync('../fixtures/golden/state-production-baseline.json', 'utf8'),
) as State
const incident = JSON.parse(
  readFileSync('../fixtures/golden/state-production-incident.json', 'utf8'),
) as State

const stateOf = (document: State, nodeKey: string) => {
  const node = document.nodes.find((candidate) => candidate.nodeKey === nodeKey)
  if (!node) throw new Error(`no state for ${nodeKey}`)
  return node
}

/**
 * ADR-0165 and ADR-0167 as the wire will carry them: a `prometheus` namespace with `rate` and
 * `latency`, poured into the golden document here rather than into the golden itself, which the
 * backend writes and asserts against and which has no Prometheus in it.
 */
const withPrometheus = (document: State, nodeKey: string, reading: Record<string, unknown>, observedAt?: string) => {
  const node = stateOf(document, nodeKey)
  return {
    ...node,
    metrics: { ...node.metrics, prometheus: reading },
    observedAt: observedAt ?? node.observedAt,
  }
}

const NOW = Date.parse('2026-09-17T10:00:45Z')

describe('ADR-0168: a card shows three figures and counts the rest', () => {
  it('ranks the golden enricher and counts what it drops', () => {
    // Kafka also observes the enricher, so latency is the first figure to go: lag is the voting
    // signal. The golden supplies two plugins' keys, the test pours the third in.
    const enricher = withPrometheus(baseline, 'payments-enricher', { rate: 38, latency: 0.036 })
    const strip = summarizeMetrics(enricher)

    expect(strip?.figures).toEqual([
      { value: '2/3', caption: 'ready' },
      { value: '38/s', caption: 'rate' },
      { value: '40k', caption: 'lag' },
    ])
    expect(strip?.more).toBe(1)
    expect(strip?.title).toBe('2/3 ready · 38/s rate · 40k lag · 0.036 ms mean')
    expect(strip?.measuredAt).toBeNull()
  })

  it('shows every figure when three or fewer arrive, and says nothing more', () => {
    const sink = summarizeMetrics(stateOf(baseline, 'payments-es-sink'))

    expect(sink?.figures).toEqual([
      { value: '2/2', caption: 'ready' },
      { value: '120', caption: 'lag' },
      { value: '3/3', caption: 'tasks' },
    ])
    expect(sink?.more).toBe(0)
  })

  it('gives a node only Prometheus observes the age of its measurement', () => {
    // ADR-0165: nothing voted, so the health is UNKNOWN, but a reading was stored with its time.
    // That time is a measurement's age, never a verdict's.
    const measured = withPrometheus(
      baseline,
      'analytics.payments_events',
      { rate: 12.1, latency: 212 },
      '2026-09-17T10:00:00Z',
    )
    const strip = summarizeMetrics(measured)

    expect(strip?.figures).toEqual([
      { value: '12/s', caption: 'rate' },
      { value: '212 ms', caption: 'mean' },
    ])
    expect(strip?.measuredAt).toBe('2026-09-17T10:00:00Z')
    expect(ageOf(strip!.measuredAt!, NOW)).toBe('45s')
  })

  it('shows no age on a node that has voters', () => {
    const api = { ...stateOf(baseline, 'payments-api'), observedAt: '2026-09-17T10:00:00Z' }
    expect(summarizeMetrics(api)?.measuredAt).toBeNull()
  })

  it('says nothing at all when nothing observed the node', () => {
    // ADR-0028: a node nobody observes has no row, so `metrics` is `{}`. Null, because the card
    // omits the strip entirely and reserves no height for it.
    expect(summarizeMetrics(stateOf(baseline, 'trino-analytics'))).toBeNull()
  })

  it('treats a rate of zero as a figure and an absent key as none', () => {
    // ADR-0167's `rate 0` is an idle series, which is a fact; a missing key is the absence of one.
    const idle = summarizeMetrics({ health: 'HEALTHY', observedAt: null, metrics: { prometheus: { rate: 0 } } })
    expect(idle?.figures).toEqual([{ value: '0/s', caption: 'rate' }])
  })

  it('collapses a pair only when both of its keys arrived', () => {
    const half = summarizeMetrics({ health: 'HEALTHY', observedAt: null, metrics: { connect: { tasksRunning: 2 } } })
    expect(half?.figures).toEqual([{ value: '2', caption: 'tasks running' }])
  })

  it('is keyed by metric, blind to which plugin supplied it', () => {
    // A new plugin that reuses `rate` needs no frontend change; an unknown key still renders,
    // generically and last.
    const strip = summarizeMetrics({
      health: 'HEALTHY',
      observedAt: null,
      metrics: { someday: { queueDepth: 7 }, other: { rate: 1200 } },
    })
    expect(strip?.figures).toEqual([
      { value: '1.2k/s', caption: 'rate' },
      { value: '7', caption: 'queue depth' },
    ])
  })
})

describe('ADR-0168: numbers are compact and integers never gain a decimal', () => {
  it.each([
    [0, '0'],
    [7, '7'],
    [120, '120'],
    [999, '999'],
    [1000, '1k'],
    [1200, '1.2k'],
    [40000, '40k'],
    [2_100_000, '2.1M'],
    [3_000_000, '3M'],
    [12.1, '12'],
    [2.5, '2.5'],
    [0.5, '0.5'],
  ])('%s reads %s', (n, expected) => {
    expect(compact(n)).toBe(expected)
  })

  it.each([
    [0.036, '0.036 ms'],
    [0.0412, '0.041 ms'],
    [4, '4 ms'],
    [4.25, '4.3 ms'],
    [212, '212 ms'],
    [212.4, '212 ms'],
  ])('a latency of %s reads %s', (n, expected) => {
    expect(milliseconds(n)).toBe(expected)
  })

  it.each([
    [0, '0s'],
    [45, '45s'],
    [89, '89s'],
    [90, '2m'],
    [59 * 60, '59m'],
    [90 * 60, '2h'],
  ])('a measurement %s seconds old reads %s', (seconds, expected) => {
    expect(ageOf(new Date(NOW - seconds * 1000).toISOString(), NOW)).toBe(expected)
  })
})

describe('ADR-0017: health is encoded by shape as well as colour', () => {
  it('has a distinct glyph for every value the wire can carry', () => {
    // Colour alone fails twice, and the second failure is the one this asserts: UNKNOWN and DISABLED
    // have no natural hue — one because nothing observes the node, the other because someone turned
    // it off on purpose — so any honest palette renders both as muted greys and they collapse into
    // each other. The label is the accessible name that rides the shape, and no two may coincide.
    const values: Health[] = ['HEALTHY', 'DEGRADED', 'UNHEALTHY', 'UNKNOWN', 'DISABLED']
    const labels = values.map(healthLabel)

    expect(new Set(labels).size).toBe(values.length)
    expect(healthLabel('UNKNOWN')).not.toBe(healthLabel('DISABLED'))
  })

  it('covers every health the goldens actually contain', () => {
    // The canvas must not have an unmapped value: a missing glyph entry throws at render time, on
    // the one screen whose entire job is being readable at a glance.
    for (const document of [baseline, incident]) {
      for (const node of document.nodes) {
        expect(healthLabel(node.health)).toBeTruthy()
      }
    }
  })

  it('the incident is legible as a local failure rather than a wave of red', () => {
    // ADR-0027: health is local. The enricher fails and the node immediately upstream of it is
    // untouched, which is what lets the canvas show the incident's origin instead of its blast
    // radius. A propagated value would paint both and have no raw signal behind either.
    expect(stateOf(incident, 'payments-enricher').health).toBe('UNHEALTHY')
    expect(stateOf(incident, 'payments-api').health).toBe('HEALTHY')
  })
})
