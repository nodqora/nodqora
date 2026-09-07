// SPDX-License-Identifier: Apache-2.0
import type { PluginOutcome } from '../api/types'

/**
 * ADR-0087: **the canvas has three empty states, and none of them promises a time.**
 *
 * They are selected by `plugins[]` alone — the roster ADR-0085 put on the wire precisely so that a
 * cold environment stops being indistinguishable from an empty one.
 *
 * | condition | rendering |
 * |---|---|
 * | roster is empty | "No plugins are configured for `production`." |
 * | no discovery-capable pair has reported | "`production` has not been read yet." |
 * | otherwise | "No nodes in `production`." |
 *
 * **A single state with an explanatory subline was the runner-up and is dishonest.** "No nodes in
 * production" is a *false statement* during a cold read, and a subline does not retract a headline.
 *
 * **A zero-plugin environment gets its own state rather than a startup refusal.** Failing fast was
 * tempting, but ADR-0074 makes *a rename is a delete plus a create*, so declaring an environment
 * before wiring its plugins is a normal intermediate edit and refusing to boot turns a half-finished
 * config into an outage. Folding it into "not read yet" was the one genuinely wrong option: it
 * renders a config error as a transient wait, so the operator waits instead of fixing.
 */
export type CanvasEmptyState = 'NO_PLUGINS' | 'NOT_READ_YET' | 'NO_NODES'

export function emptyStateOf(discovery: PluginOutcome[]): CanvasEmptyState {
  if (discovery.length === 0) return 'NO_PLUGINS'
  if (discovery.every((pair) => pair.outcome === null)) return 'NOT_READ_YET'
  return 'NO_NODES'
}

/**
 * The three sentences.
 *
 * **None of them states or implies a time. No countdown, no "back in about five minutes."**
 * ADR-0059 publishes `refresh.graphSeconds` as the *minimum* over configured plugin cadences, so a
 * slower plugin arrives long after it elapses; promising a time from that number is the dishonest
 * threshold ADR-0084 refused when it declined a staleness TTL, restated as a broken promise instead
 * of a false mark.
 *
 * A `payload_version` discard (ADR-0080) renders identically to a fresh deployment. Nothing
 * distinguishes them and nothing tries to: telling them apart would need a marker that survives its
 * own version bump, and the remedy is identical in both cases — wait one cadence — so the
 * distinction would change no action.
 */
export function emptyStateSentence(state: CanvasEmptyState, environmentDisplayName: string): string {
  switch (state) {
    case 'NO_PLUGINS':
      return `No plugins are configured for ${environmentDisplayName}.`
    case 'NOT_READ_YET':
      return `${environmentDisplayName} has not been read yet.`
    case 'NO_NODES':
      return `No nodes in ${environmentDisplayName}.`
  }
}
