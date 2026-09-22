// SPDX-License-Identifier: Apache-2.0
import type { SessionState } from '../api/types'

/**
 * ADR-0177 §4: what the shell does once a `401` has made it read `/session`.
 *
 * - `NO_IDENTITY_PROVIDER` — the first-run screen, in place. No navigation, so nothing to loop.
 * - `RENAVIGATE` — *"Your session has ended. Signing in again…"*, then the current URL again; the
 *   server sends it to the provider and back, `?node=` included.
 * - `DID_NOT_SUCCEED` — the loop guard refused a second re-navigation.
 * - `CONTRADICTION` — `OPEN` or `SIGNED_IN` behind a `401`: an ordinary error in the top bar.
 */
export type Answer = 'NO_IDENTITY_PROVIDER' | 'RENAVIGATE' | 'DID_NOT_SUCCEED' | 'CONTRADICTION'

/**
 * ADR-0177 §4's loop guard. It stops a server that completes sign-in and still answers `/api` with
 * `401` from reloading the page forever; a misbehaving provider is already stopped at the provider.
 */
export const LOOP_GUARD_MS = 30_000

const STAMP = 'nodqora.renavigatedAt'

/** The two methods of `sessionStorage` the guard uses, so a test can hand it a map. */
export type Stamps = Pick<Storage, 'getItem' | 'setItem'>

/**
 * Writes the stamp before answering `RENAVIGATE`, because the navigation unloads the page and there
 * is no after. A refusal does not move the stamp, so thirty seconds is measured from the navigation
 * that might have looped rather than from the latest reload.
 *
 * **A storage that cannot hold the stamp does not navigate.** A navigation the guard cannot count is
 * exactly the loop it exists to stop, and the refusal's sentence tells the reader to reload, which
 * reaches the provider anyway.
 */
export function answerUnauthorized(session: SessionState, stamps: Stamps | null): Answer {
  switch (session.state) {
    case 'NOT_CONFIGURED':
      return 'NO_IDENTITY_PROVIDER'
    case 'OPEN':
    case 'SIGNED_IN':
      return 'CONTRADICTION'
    case 'SIGNED_OUT':
      try {
        if (stamps === null) return 'DID_NOT_SUCCEED'
        const last = Number(stamps.getItem(STAMP))
        const now = Date.now()
        // `Number(null)` is 0 and `Number('yesterday')` is NaN, and both are no stamp.
        if (Number.isFinite(last) && last > 0 && now - last < LOOP_GUARD_MS) return 'DID_NOT_SUCCEED'
        stamps.setItem(STAMP, String(now))
        return 'RENAVIGATE'
      } catch {
        return 'DID_NOT_SUCCEED'
      }
  }
}
