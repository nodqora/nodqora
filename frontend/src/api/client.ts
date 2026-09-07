// SPDX-License-Identifier: Apache-2.0
import type { Graph, Meta, State } from './types'

/**
 * ADR-0059: polling, with the intervals published by the server rather than held as client-side
 * constants that disagree, silently, the first time anyone tunes a plugin's cadence.
 *
 * No conditional fetch. `/graph` and `/state` are symmetric — both always return 200, each carrying
 * its own freshness (ADR-0076). `confirmedAt` advances for every node on every healthy poll, so a
 * content-hash ETag would fire 304 roughly never; and hashing only the folded content would return
 * 304 through a plugin going blind, delivering "we could not look" to the client as "nothing is
 * wrong", implemented as a status code.
 */

async function get<T>(path: string): Promise<T> {
  const response = await fetch(path, { headers: { Accept: 'application/json' } })
  if (!response.ok) {
    // ADR-0060: errors are problem+json, so there is a `detail` worth surfacing.
    const problem = await response.json().catch(() => null)
    throw new Error(problem?.detail ?? `${response.status} from ${path}`)
  }
  return response.json() as Promise<T>
}

export const api = {
  meta: () => get<Meta>('/api/meta'),
  graph: (environmentKey: string) => get<Graph>(`/api/environments/${encodeURIComponent(environmentKey)}/graph`),
  state: (environmentKey: string) => get<State>(`/api/environments/${encodeURIComponent(environmentKey)}/state`),
}
