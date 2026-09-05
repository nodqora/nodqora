// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import { defaultEnvironmentKey, environmentIsKnown, formatRoute, parseRoute } from './route'

const ROSTER = [
  { key: 'production', displayName: 'Production' },
  { key: 'staging', displayName: 'Staging' },
]

describe('the grammar is an environment path plus an optional node parameter', () => {
  it('reads an environment out of the path', () => {
    expect(parseRoute('/environments/production', '')).toEqual({
      environmentKey: 'production',
      nodeKey: null,
    })
  })

  it('reads the selected node out of the query', () => {
    expect(parseRoute('/environments/staging', '?node=payments-api')).toEqual({
      environmentKey: 'staging',
      nodeKey: 'payments-api',
    })
  })

  it('round-trips a key with characters no path segment could carry', () => {
    // ADR-0092: the node is a parameter partly because ADR-0020 fixes the key as a flat, free-form
    // string with **no pinned character class**. A path would force an answer to what a `/` inside a
    // key means; a query parameter percent-encodes any string and the router never sees it.
    const awkward = 'payments/events?v=1 & more'
    const url = new URL(formatRoute('production', awkward), 'https://nodqora.example')

    expect(parseRoute(url.pathname, url.search)).toEqual({
      environmentKey: 'production',
      nodeKey: awkward,
    })
  })

  it('treats a hand-trimmed empty parameter as no selection', () => {
    expect(parseRoute('/environments/production', '?node=').nodeKey).toBeNull()
  })

  it('carries nothing else, because nothing else is in the grammar', () => {
    // The search query is excluded deliberately: ADR-0069 already made it exclusive with selection,
    // so a `?node=&q=` URL is hand-craftable and unreachable, and the app would then arbitrate
    // between two states designed never to coexist.
    expect(parseRoute('/environments/production', '?node=payments-api&q=enricher')).toEqual({
      environmentKey: 'production',
      nodeKey: 'payments-api',
    })
  })

  it('formats what it parses', () => {
    expect(formatRoute('production', null)).toBe('/environments/production')
    expect(formatRoute('production', 'payments.events.raw.v1')).toBe(
      '/environments/production?node=payments.events.raw.v1',
    )
  })
})

describe('the two URLs that name no usable scope', () => {
  it('gives bare `/` no environment, so the app can redirect it', () => {
    // ADR-0093: bare `/` asserts no scope, so resolving it is not the leak ADR-0092 guarded against.
    // What matters is that the address bar is explicit immediately afterwards, which a redirect
    // guarantees and a silently-defaulted render does not.
    expect(parseRoute('/', '').environmentKey).toBeNull()
    expect(parseRoute('/environments', '').environmentKey).toBeNull()
    expect(parseRoute('/environments/production/nodes/payments-api', '').environmentKey).toBeNull()
  })

  it('redirects the root to the first configured environment rather than the last used one', () => {
    // Last-used was rejected even though it is friendlier: it makes a bookmarked `/` mean different
    // things to different people and drift over time, at odds with ADR-0018's scope-as-frame. First
    // in roster also makes config order meaningful — operators put `production` first.
    expect(defaultEnvironmentKey(ROSTER)).toBe('production')
    expect(defaultEnvironmentKey([])).toBeNull()
  })

  it('matches the environment key exactly, with no case-folding and no redirect', () => {
    // ADR-0052 matches the environment key exactly because it is operator-authored config, not
    // discovered data — in deliberate contrast to node keys, which are case-folded. Folding here
    // would give one key two comparison rules, for a hand-typed URL the not-found repairs in a click.
    expect(environmentIsKnown(ROSTER, 'production')).toBe(true)
    expect(environmentIsKnown(ROSTER, 'Production')).toBe(false)
    expect(environmentIsKnown(ROSTER, 'prod')).toBe(false)
  })
})
