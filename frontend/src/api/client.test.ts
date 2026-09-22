// SPDX-License-Identifier: Apache-2.0
import { afterEach, describe, expect, it, vi } from 'vitest'
import { readFileSync } from 'node:fs'
import { Unauthorized, api } from './client'

const golden = (name: string) => readFileSync(`../fixtures/golden/${name}`, 'utf8')

/** One response for every request, and a record of what was asked for. */
function serve(status: number, body: string) {
  const fetch = vi.fn(async () => new Response(body, { status, headers: { 'Content-Type': 'application/json' } }))
  vi.stubGlobal('fetch', fetch)
  return fetch
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('a 401 is a question, not an error string', () => {
  it('is thrown as Unauthorized from each of the three GETs', async () => {
    // ADR-0177 §4: a 401 from any of the three GETs makes the shell read /session, so it has to be
    // told apart from every other failure — which keeps ADR-0060's path.
    serve(401, golden('api-401.json'))
    await expect(api.meta()).rejects.toBeInstanceOf(Unauthorized)
    await expect(api.graph('production')).rejects.toBeInstanceOf(Unauthorized)
    await expect(api.state('production')).rejects.toBeInstanceOf(Unauthorized)
  })

  it('still carries the problem detail, for the case where the server contradicts itself', async () => {
    // OPEN or SIGNED_IN behind a 401 is an ordinary error in the top bar, so the message is ADR-0060's.
    serve(401, golden('api-401.json'))
    await expect(api.meta()).rejects.toThrow('Not signed in.')
  })

  it('leaves every other failure as it was', async () => {
    serve(503, JSON.stringify({ status: 503, detail: 'Discovery is down.' }))
    const failure = api.meta()
    await expect(failure).rejects.toThrow('Discovery is down.')
    await expect(failure).rejects.not.toBeInstanceOf(Unauthorized)
  })
})

describe('the session state', () => {
  it.each([
    ['session-not-configured.json', 'NOT_CONFIGURED'],
    ['session-open.json', 'OPEN'],
    ['session-signed-out.json', 'SIGNED_OUT'],
    ['session-signed-in.json', 'SIGNED_IN'],
  ])('reads %s from /session, outside /api', async (file, state) => {
    // ADR-0177 §1: `/session`, not `/api/session` — ADR-0173 keeps `/api` to three GETs.
    const fetch = serve(200, golden(file))
    await expect(api.session()).resolves.toMatchObject({ state })
    expect(fetch).toHaveBeenCalledWith('/session', expect.anything())
  })

  it('carries the name and both CSRF names only when signed in', async () => {
    serve(200, golden('session-signed-in.json'))
    const session = await api.session()
    expect(session).toEqual({
      state: 'SIGNED_IN',
      name: 'Ada Lovelace',
      csrf: { headerName: 'X-CSRF-TOKEN', parameterName: '_csrf', token: '<token>' },
    })
  })
})
