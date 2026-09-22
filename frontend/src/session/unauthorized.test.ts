// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { readFileSync } from 'node:fs'
import { LOOP_GUARD_MS, answerUnauthorized, type Stamps } from './unauthorized'
import type { SessionState } from '../api/types'

const session = (name: string) => JSON.parse(readFileSync(`../fixtures/golden/${name}`, 'utf8')) as SessionState

/** `sessionStorage`'s two methods the guard uses, over a plain map. */
function stamps(): Stamps & { entries: Map<string, string> } {
  const entries = new Map<string, string>()
  return {
    entries,
    getItem: (key) => entries.get(key) ?? null,
    setItem: (key, value) => void entries.set(key, value),
  }
}

beforeEach(() => {
  vi.useFakeTimers()
  vi.setSystemTime(new Date('2026-09-22T12:00:00Z'))
})

afterEach(() => {
  vi.useRealTimers()
})

describe('a 401 asks the session state, and does what it says', () => {
  it('shows the no-identity-provider screen in place when sign-in is not configured', () => {
    // No navigation, so nothing to loop.
    const storage = stamps()
    expect(answerUnauthorized(session('session-not-configured.json'), storage)).toBe('NO_IDENTITY_PROVIDER')
    expect(storage.entries.size).toBe(0)
  })

  it('re-navigates to the current URL when the session has ended', () => {
    expect(answerUnauthorized(session('session-signed-out.json'), stamps())).toBe('RENAVIGATE')
  })

  it.each(['session-open.json', 'session-signed-in.json'])(
    'treats %s behind a 401 as the server contradicting itself',
    (file) => {
      const storage = stamps()
      expect(answerUnauthorized(session(file), storage)).toBe('CONTRADICTION')
      expect(storage.entries.size).toBe(0)
    },
  )
})

describe('the loop guard', () => {
  it('stops a second re-navigation inside thirty seconds', () => {
    // A server that completes sign-in and still answers /api with 401 would otherwise reload forever.
    const storage = stamps()
    expect(answerUnauthorized(session('session-signed-out.json'), storage)).toBe('RENAVIGATE')
    vi.advanceTimersByTime(LOOP_GUARD_MS - 1)
    expect(answerUnauthorized(session('session-signed-out.json'), storage)).toBe('DID_NOT_SUCCEED')
  })

  it('lets a session that ends later re-navigate again', () => {
    // Thirty seconds is the loop, not a limit on how often a session may expire.
    const storage = stamps()
    answerUnauthorized(session('session-signed-out.json'), storage)
    vi.advanceTimersByTime(LOOP_GUARD_MS)
    expect(answerUnauthorized(session('session-signed-out.json'), storage)).toBe('RENAVIGATE')
  })

  it('does not reset its clock when it refuses', () => {
    // Otherwise a reader who reloads every twenty seconds would never be let through again.
    const storage = stamps()
    answerUnauthorized(session('session-signed-out.json'), storage)
    vi.advanceTimersByTime(20_000)
    answerUnauthorized(session('session-signed-out.json'), storage)
    vi.advanceTimersByTime(10_000)
    expect(answerUnauthorized(session('session-signed-out.json'), storage)).toBe('RENAVIGATE')
  })

  it('does not navigate when it cannot remember that it did', () => {
    // A storage that throws cannot hold the stamp, and a navigation that cannot be counted is the loop.
    const refusing: Stamps = {
      getItem: () => {
        throw new Error('SecurityError')
      },
      setItem: () => {
        throw new Error('SecurityError')
      },
    }
    expect(answerUnauthorized(session('session-signed-out.json'), refusing)).toBe('DID_NOT_SUCCEED')
    expect(answerUnauthorized(session('session-signed-out.json'), null)).toBe('DID_NOT_SUCCEED')
  })

  it('reads a stamp it cannot parse as no stamp', () => {
    const storage = stamps()
    storage.entries.set('nodqora.renavigatedAt', 'yesterday')
    expect(answerUnauthorized(session('session-signed-out.json'), storage)).toBe('RENAVIGATE')
  })
})
