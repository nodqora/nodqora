// SPDX-License-Identifier: Apache-2.0
// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import { readFileSync } from 'node:fs'
import { App } from '../App'
import { renavigate } from './navigate'
import { installMeasurement, silenceActEnvironment } from '../canvas/measurement.testkit'

/**
 * ADR-0177 §4 wired into the shell: which screen a 401 ends on, whether the page navigates, and that
 * polling stops. The answer itself is `unauthorized.test.ts`'s; this file proves the shell asks it,
 * once, and does what it says.
 */

vi.mock('./navigate', () => ({ renavigate: vi.fn() }))

// Two of these mount the real canvas under a signed-in session.
installMeasurement()

silenceActEnvironment()

const golden = (name: string) => readFileSync(`../fixtures/golden/${name}`, 'utf8')

const META = JSON.stringify({
  environments: [{ key: 'production', displayName: 'Production' }],
  plugins: [],
  refresh: { graphSeconds: 300, stateSeconds: 30 },
})

/** Answers by path; anything unlisted is a 404, so an unexpected request fails loudly. */
function serve(routes: Record<string, [number, string] | (() => [number, string])>) {
  const fetch = vi.fn(async (path: string) => {
    const route = routes[new URL(path, 'http://nodqora').pathname]
    const [status, body] = typeof route === 'function' ? route() : (route ?? [404, '{}'])
    return new Response(body, { status, headers: { 'Content-Type': 'application/json' } })
  })
  vi.stubGlobal('fetch', fetch)
  return fetch
}

const asked = (fetch: ReturnType<typeof serve>, path: string) =>
  fetch.mock.calls.filter(([requested]) => requested === path).length

beforeEach(() => {
  history.replaceState(null, '', '/environments/production?node=payments-api')
  sessionStorage.clear()
})

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  vi.mocked(renavigate).mockClear()
  vi.useRealTimers()
})

describe('the shell reads the session state at startup', () => {
  it('shows the no-identity-provider screen without waiting for meta to fail', async () => {
    serve({ '/session': [200, golden('session-not-configured.json')], '/api/meta': [401, golden('api-401.json')] })
    render(<App />)
    await screen.findByText('No identity provider is configured.')
    expect(renavigate).not.toHaveBeenCalled()
  })

  it('puts the signed-in name at the trailing edge of the top bar, after the theme picker', async () => {
    serve({
      '/session': [200, golden('session-signed-in.json')],
      '/api/meta': [200, META],
      '/api/environments/production/graph': [200, golden('graph-production.json')],
      '/api/environments/production/state': [200, golden('state-production-baseline.json')],
    })
    const { container } = render(<App />)
    await screen.findByText('Ada Lovelace')
    const bar = container.querySelector('.top-bar')!
    expect(bar.lastElementChild!.textContent).toContain('Sign out')
    expect(bar.lastElementChild!.previousElementSibling!.classList).toContain('theme-picker')
  })
})

describe('a 401 asks the session state why', () => {
  it('re-navigates to the current URL once the session has ended, with the canvas gone', async () => {
    let graphStatus = 200
    const fetch = serve({
      '/session': () => [200, golden(graphStatus === 200 ? 'session-signed-in.json' : 'session-signed-out.json')],
      '/api/meta': [200, META],
      '/api/environments/production/graph': () =>
        graphStatus === 200 ? [200, golden('graph-production.json')] : [401, golden('api-401.json')],
      '/api/environments/production/state': () =>
        graphStatus === 200 ? [200, golden('state-production-baseline.json')] : [401, golden('api-401.json')],
    })
    vi.useFakeTimers({ shouldAdvanceTime: true })
    render(<App />)
    await screen.findByText('Ada Lovelace')

    graphStatus = 401
    await vi.advanceTimersByTimeAsync(30_000)

    await screen.findByText('Your session has ended. Signing in again…')
    expect(renavigate).toHaveBeenCalledTimes(1)
    expect(document.querySelector('.canvas')).toBeNull()

    // Polling has stopped: nothing more is fetched, however long the page stays up.
    const before = fetch.mock.calls.length
    await vi.advanceTimersByTimeAsync(600_000)
    expect(fetch.mock.calls.length).toBe(before)
  })

  it('reads the session state once, however many GETs answered 401 together', async () => {
    const fetch = serve({ '/session': [200, golden('session-signed-out.json')], '/api/meta': [401, golden('api-401.json')] })
    render(<App />)
    await screen.findByText('Your session has ended. Signing in again…')
    // One at startup, one for the 401.
    expect(asked(fetch, '/session')).toBe(2)
    expect(renavigate).toHaveBeenCalledTimes(1)
  })

  it('does not navigate a second time inside thirty seconds', async () => {
    serve({ '/session': [200, golden('session-signed-out.json')], '/api/meta': [401, golden('api-401.json')] })
    render(<App />)
    await screen.findByText('Your session has ended. Signing in again…')
    cleanup()

    // The page came back from the provider and the server still answers 401.
    render(<App />)
    await screen.findByText('Signing in did not succeed. Reload to try again.')
    expect(renavigate).toHaveBeenCalledTimes(1)
  })

  it('treats a 401 behind an open install as an ordinary error in the top bar', async () => {
    serve({ '/session': [200, golden('session-open.json')], '/api/meta': [401, golden('api-401.json')] })
    const { container } = render(<App />)
    await screen.findByText('Not signed in.')
    expect(container.querySelector('.top-bar')).not.toBeNull()
    expect(renavigate).not.toHaveBeenCalled()
  })

  it('shows the no-identity-provider screen in place when a 401 says sign-in is not configured', async () => {
    // Startup read OPEN (or failed), then the install was restarted without a declaration.
    let configured = true
    serve({
      '/session': () => [200, golden(configured ? 'session-open.json' : 'session-not-configured.json')],
      '/api/meta': () => {
        configured = false
        return [401, golden('api-401.json')]
      },
    })
    render(<App />)
    await waitFor(() => expect(screen.getByText('No identity provider is configured.')).toBeTruthy())
    expect(renavigate).not.toHaveBeenCalled()
  })
})
