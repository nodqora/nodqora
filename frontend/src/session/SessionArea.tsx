// SPDX-License-Identifier: Apache-2.0
import type { SessionState } from '../api/types'

const LONGEST_NAME = 18

/**
 * ADR-0177 §3: a name longer than eighteen characters is cut to eighteen, ellipsis included, and
 * carried whole in `title`. Counted in characters rather than UTF-16 units, so a name outside the
 * Basic Multilingual Plane is never split into a lone surrogate.
 *
 * In code rather than a CSS `max-width`, because `18ch` is the width of eighteen zeros and a
 * proportional name does not truncate at eighteen of anything.
 */
export function shownName(name: string): string {
  const characters = [...name]
  return characters.length <= LONGEST_NAME ? name : `${characters.slice(0, LONGEST_NAME - 1).join('')}…`
}

/**
 * ADR-0177 §3: **the session at the trailing edge of the top bar, as plain text.**
 *
 * Signed in, it is the name and *Sign out* — a link-styled submit in a form `POST /logout` carrying
 * the CSRF token under the parameter name the server sent (ADR-0175), so Spring's default is not
 * hard-coded here. An open install says so in the same place, as text rather than a button, because
 * a button reads as something to press and there is nothing to press (ADR-0173). Signed out and not
 * configured render nothing: the shell is not on screen in either.
 *
 * An account menu holding the name, sign-out and the theme picker was prototyped and rejected: a
 * second idiom in a bar of plain labels and selects, and sign-out a click deep.
 */
export function SessionArea({ session }: { session: SessionState | null }) {
  if (session?.state === 'OPEN') return <span className="session">Open — no sign-in</span>
  if (session?.state !== 'SIGNED_IN') return null
  const shown = shownName(session.name)
  return (
    <span className="session">
      <span className="session-name" title={shown === session.name ? undefined : session.name}>
        {shown}
      </span>
      <form method="post" action="/logout" className="session-sign-out">
        <input type="hidden" name={session.csrf.parameterName} value={session.csrf.token} />
        <button type="submit">Sign out</button>
      </form>
    </span>
  )
}
