import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { emptyStateOf, emptyStateSentence } from './emptyState'
import type { Graph, PluginOutcome } from '../api/types'

const production = JSON.parse(readFileSync('../fixtures/golden/graph-production.json', 'utf8')) as Graph

const cold = (pairs: PluginOutcome[]) => pairs.map((pair) => ({ ...pair, outcome: null, recordedAt: null }))

describe('the canvas has three empty states, selected by plugins[] alone', () => {
  it('names a config error rather than rendering it as a transient wait', () => {
    // ADR-0087: folding this into "not read yet" was the one genuinely wrong option — the operator
    // waits instead of fixing. A startup refusal was tempting and also wrong: ADR-0074 makes *a
    // rename is a delete plus a create*, so declaring an environment before wiring its plugins is a
    // normal intermediate edit, and refusing to boot turns a half-finished config into an outage.
    expect(emptyStateOf([])).toBe('NO_PLUGINS')
    expect(emptyStateSentence('NO_PLUGINS', 'Production')).toBe('No plugins are configured for Production.')
  })

  it('says a cold environment has not been read yet', () => {
    expect(emptyStateOf(cold(production.plugins))).toBe('NOT_READ_YET')
    expect(emptyStateSentence('NOT_READ_YET', 'Production')).toBe('Production has not been read yet.')
  })

  it('says a read environment holding nothing is empty', () => {
    expect(emptyStateOf(production.plugins)).toBe('NO_NODES')
    expect(emptyStateSentence('NO_NODES', 'Production')).toBe('No nodes in Production.')
  })

  it('leaves the cold state the moment one plugin reports, even a failing one', () => {
    // ADR-0086 gave the store three readings where it had one, and this is where the third pays:
    // "we tried and could not look" is a `FAILED` header, and telling an operator with a typo'd
    // kubeconfig to wait five minutes for a poll that will never succeed is the failure this avoids.
    const oneFailed = [{ ...production.plugins[0]!, outcome: 'FAILED' as const }, ...cold(production.plugins.slice(1))]

    expect(emptyStateOf(oneFailed)).toBe('NO_NODES')
  })

  it('never states or implies a time', () => {
    // No countdown, for ADR-0084's reason. ADR-0059 publishes `refresh.graphSeconds` as the
    // **minimum** over configured cadences, so a slower plugin arrives long after it elapses;
    // promising a time from that number is the dishonest threshold ADR-0084 refused when it declined
    // a staleness TTL, restated as a broken promise instead of a false mark.
    const sentences = (['NO_PLUGINS', 'NOT_READ_YET', 'NO_NODES'] as const).map((state) =>
      emptyStateSentence(state, 'Production'),
    )

    for (const sentence of sentences) {
      expect(sentence).not.toMatch(/minute|second|soon|shortly|wait|refresh|\d/i)
    }
  })

  it('renders a payload_version discard identically to a fresh deployment', () => {
    // ADR-0080 empties the store on a version bump and ADR-0087 accepts that the two converge:
    // distinguishing them would need a marker that survives its own version bump, which is tolerant
    // deserialization forever, smuggled back as one field. The remedy is identical — wait one
    // cadence — so the distinction would change no action.
    const freshInstall = cold(production.plugins)
    const afterDiscard = cold(production.plugins)

    expect(emptyStateOf(afterDiscard)).toBe(emptyStateOf(freshInstall))
  })
})
