import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { summarizeMetrics } from './metrics'
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

describe('the metric overlay renders what a plugin allow-listed', () => {
  it('reads the golden document rather than a hand-written shape', () => {
    // The same fixture the backend asserts against, so the two cannot drift into disagreeing about
    // the wire shape — which is the whole reason ADR-0099 chose real documents over restatements.
    // The enricher carries two namespaces now that `kafka` observes it too, and the card flattens
    // them into one line without knowing that anything changed.
    expect(summarizeMetrics(stateOf(baseline, 'payments-enricher').metrics)).toBe(
      'max consumer lag 40000 · desired replicas 3 · ready replicas 2',
    )
  })

  it('says nothing at all when nothing observed the node', () => {
    // ADR-0028: a node nobody observes has no row, so `metrics` is `{}`. Null rather than an empty
    // string, because the card omits the line entirely and reserves no height for it — the correct
    // rendering of "nothing is watching this", not a blank waiting to be filled.
    expect(summarizeMetrics(stateOf(baseline, 'trino-analytics').metrics)).toBeNull()
  })

  it('is blind to which plugin supplied a key', () => {
    // ADR-0006 namespaces the map so two plugins may use the same key name; the card flattens it,
    // because a node's operator does not care which process read the number. Nothing here branches
    // on a plugin id, which is what keeps `kafka` and `connect` from needing a frontend change.
    expect(
      summarizeMetrics({ kafka: { maxConsumerLag: 40000 }, connect: { tasksRunning: 2, tasksTotal: 3 } }),
    ).toBe('tasks running 2 · tasks total 3 · max consumer lag 40000')
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
