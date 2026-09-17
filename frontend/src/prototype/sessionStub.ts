// SPDX-License-Identifier: Apache-2.0
/*
 * PROTOTYPE — throwaway, for "What the shell shows of a session" (nodqora#149). Never merge.
 *
 * Stubs the three /api GETs from the golden documents and a proposed session state, so every
 * session state can be seen in the real shell with no backend and no identity provider running.
 * Dev builds only: `api/client.ts` consults this only under `import.meta.env.DEV`.
 */
import graphProduction from '../../../fixtures/golden/graph-production.json'
import graphStaging from '../../../fixtures/golden/graph-staging.json'
import stateProduction from '../../../fixtures/golden/state-production-baseline.json'
import stateStaging from '../../../fixtures/golden/state-staging-baseline.json'
import type { Graph, Meta, State } from '../api/types'

export const SESSIONS = [
  'signed-in',
  'nameless',
  'opaque-sub',
  'open',
  'not-configured',
  'expires',
  'idp-down',
] as const
export type SessionKey = (typeof SESSIONS)[number]

export const VARIANTS = ['A', 'B', 'C'] as const
export type Variant = (typeof VARIANTS)[number]

export const VARIANT_NAMES: Record<Variant, string> = {
  A: 'Top bar, plain text at the far end',
  B: 'Account menu (session + settings)',
  C: 'Status line along the bottom',
}

export const SESSION_NAMES: Record<SessionKey, string> = {
  'signed-in': 'Signed in (name claim)',
  nameless: 'Signed in, no name → preferred_username',
  'opaque-sub': 'Signed in, only sub',
  open: 'Open install (none)',
  'not-configured': 'Sign-in not configured',
  expires: 'Signed in, session expires in 6s',
  'idp-down': 'Provider unreachable (server 503)',
}

/** The proposed session state — the JSON `GET /session` would return. */
export type SessionState =
  | { state: 'NOT_CONFIGURED' }
  | { state: 'OPEN' }
  | { state: 'SIGNED_OUT' }
  | { state: 'SIGNED_IN'; name: string; csrf: { parameterName: string; headerName: string; token: string } }

const csrf = { parameterName: '_csrf', headerName: 'X-XSRF-TOKEN', token: '3f1c…e9a2' }

const read = <T>(key: string, all: readonly T[], fallback: T): T => {
  const fromUrl = new URLSearchParams(location.search).get(key) as T | null
  if (fromUrl && all.includes(fromUrl)) {
    sessionStorage.setItem(`proto-${key}`, String(fromUrl))
    return fromUrl
  }
  const stored = sessionStorage.getItem(`proto-${key}`) as T | null
  return stored && all.includes(stored) ? stored : fallback
}

// The route grammar (ADR-0092) rewrites the query on every navigation, so the prototype's two
// knobs live in sessionStorage, seeded from `?session=` / `?variant=` when present.
export const currentSession = (): SessionKey => read('session', SESSIONS, 'signed-in')
export const currentVariant = (): Variant => read('variant', VARIANTS, 'A')

export const choose = (key: 'session' | 'variant', value: string) => {
  sessionStorage.setItem(`proto-${key}`, value)
  sessionStorage.removeItem('proto-expired')
  sessionStorage.removeItem('proto-signed-out')
  location.reload()
}

const loadedAt = Date.now()
export const expired = () =>
  sessionStorage.getItem('proto-expired') === '1' ||
  (currentSession() === 'expires' && Date.now() - loadedAt > 6000)
export const signedOut = () => sessionStorage.getItem('proto-signed-out') === '1'

export function sessionState(): SessionState {
  const key = currentSession()
  if (key === 'not-configured') return { state: 'NOT_CONFIGURED' }
  if (key === 'open') return { state: 'OPEN' }
  if (signedOut() || expired()) return { state: 'SIGNED_OUT' }
  const name = key === 'nameless' ? 'nameless' : key === 'opaque-sub' ? 'f47ac10b-58cc-4372-a567-0e02b2c3d479' : 'Ada Lovelace'
  return { state: 'SIGNED_IN', name, csrf }
}

/** What a `401` looks like — ADR-0060 problem+json, identical for never-signed-in and expired. */
export class Unauthorized extends Error {
  readonly status = 401
  constructor() {
    super('Sign in to read this install.')
  }
}

const timestamps = <T>(document: unknown): T =>
  JSON.parse(JSON.stringify(document).replaceAll('<timestamp>', new Date().toISOString())) as T

const gate = async <T>(value: () => T): Promise<T> => {
  await new Promise((resolve) => setTimeout(resolve, 120))
  const s = sessionState().state
  if (s === 'NOT_CONFIGURED' || s === 'SIGNED_OUT') throw new Unauthorized()
  return value()
}

const meta: Meta = {
  environments: [
    { key: 'production', displayName: 'Production' },
    { key: 'staging', displayName: 'Staging' },
  ],
  plugins: [
    { id: 'yaml', displayLabel: 'YAML', capabilities: ['DISCOVERY'] },
    { id: 'kafka', displayLabel: 'Kafka', capabilities: ['DISCOVERY', 'HEALTH'] },
    { id: 'connect', displayLabel: 'Kafka Connect', capabilities: ['DISCOVERY', 'HEALTH'] },
    { id: 'kubernetes', displayLabel: 'Kubernetes', capabilities: ['DISCOVERY', 'HEALTH'] },
  ],
  refresh: { graphSeconds: 30, stateSeconds: 2 },
}

export const stubApi = {
  meta: () => gate(() => meta),
  graph: (key: string) => gate(() => timestamps<Graph>(key === 'staging' ? graphStaging : graphProduction)),
  state: (key: string) => gate(() => timestamps<State>(key === 'staging' ? stateStaging : stateProduction)),
}
