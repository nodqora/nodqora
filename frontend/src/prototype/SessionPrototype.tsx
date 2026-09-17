// SPDX-License-Identifier: Apache-2.0
/*
 * PROTOTYPE — throwaway, for "What the shell shows of a session" (nodqora#149). Never merge.
 *
 * Three structurally different homes for the session area (?variant=A|B|C), the no-identity-provider
 * first-run screen, the re-navigation on a 401, and stand-ins for the two pages the *server* renders
 * (the identity provider, and ADR-0176's 503) so the whole round-trip can be walked in one tab.
 */
import { useEffect, useState, type ReactNode } from 'react'
import {
  SESSIONS,
  SESSION_NAMES,
  VARIANTS,
  VARIANT_NAMES,
  choose,
  currentSession,
  currentVariant,
  sessionState,
  type SessionState,
} from './sessionStub'

/* ------------------------------------------------------------------ the session area, three ways */

/** Sign-out is a form `POST /logout` carrying the CSRF token (ADR-0175). Stubbed: no request leaves. */
function SignOutForm({ session, children, className }: { session: SessionState; children: ReactNode; className?: string }) {
  if (session.state !== 'SIGNED_IN') return null
  return (
    <form
      method="post"
      action="/logout"
      className={className}
      onSubmit={(event) => {
        event.preventDefault()
        sessionStorage.setItem('proto-signed-out', '1')
        location.reload()
      }}
    >
      <input type="hidden" name={session.csrf.parameterName} value={session.csrf.token} />
      {children}
    </form>
  )
}

/** A: plain text at the trailing edge of the top bar, after the theme picker. No control but the button. */
export function SessionAreaA({ session }: { session: SessionState }) {
  if (session.state === 'OPEN') return <span className="proto-a">Open — no sign-in</span>
  if (session.state !== 'SIGNED_IN') return null
  return (
    <span className="proto-a">
      <span className="proto-a-name" title={session.name}>
        {session.name}
      </span>
      <SignOutForm session={session}>
        <button type="submit" className="proto-link">
          Sign out
        </button>
      </SignOutForm>
    </span>
  )
}

/**
 * B: one trailing button naming the session, opening a popover that holds the session *and* the
 * settings (the theme picker moves in). ADR-0132 names "session, settings" as one shell region.
 */
export function SessionAreaB({ session, settings }: { session: SessionState; settings: ReactNode }) {
  const [open, setOpen] = useState(false)
  const label = session.state === 'SIGNED_IN' ? session.name : session.state === 'OPEN' ? 'Open — no sign-in' : null
  if (label === null) return null
  return (
    <div className="proto-b">
      <button type="button" className="proto-b-button" aria-expanded={open} onClick={() => setOpen(!open)}>
        <span className="proto-b-initial" aria-hidden>
          {session.state === 'SIGNED_IN' ? label.slice(0, 1).toUpperCase() : '○'}
        </span>
        <span className="proto-b-label" title={label}>{label}</span>
        <span aria-hidden>▾</span>
      </button>
      {open && (
        <div className="proto-b-popover">
          {session.state === 'SIGNED_IN' ? (
            <>
              <p className="proto-b-heading">Signed in as</p>
              <p className="proto-b-name">{session.name}</p>
            </>
          ) : (
            <>
              <p className="proto-b-heading">Open — no sign-in</p>
              <p className="proto-b-sub">This install serves everyone who can reach it.</p>
            </>
          )}
          <div className="proto-b-row">{settings}</div>
          <SignOutForm session={session} className="proto-b-row">
            <button type="submit" className="proto-b-signout">
              Sign out
            </button>
          </SignOutForm>
        </div>
      )}
    </div>
  )
}

/** C: the top bar is left alone; a thin status line under the workspace carries the session. */
export function SessionAreaC({ session }: { session: SessionState }) {
  if (session.state !== 'SIGNED_IN' && session.state !== 'OPEN') return null
  return (
    <footer className="proto-c">
      {session.state === 'SIGNED_IN' ? (
        <>
          <span>
            Signed in as <strong title={session.name}>{session.name}</strong>
          </span>
          <SignOutForm session={session}>
            <button type="submit" className="proto-link">
              Sign out
            </button>
          </SignOutForm>
        </>
      ) : (
        <span>Open — no sign-in</span>
      )}
    </footer>
  )
}

/* ------------------------------------------------------------------ screens */

/** ADR-0173 §1, in ADR-0156's grammar. The honesty layer's sixth noun: no Enterprise, no retry. */
export function NoIdentityProvider() {
  return (
    <div className="app">
      <div className="first-run">
        <p className="first-run-headline">No identity provider is configured.</p>
        <p className="first-run-route">
          Declare <code>nodqora.authentication</code> in <code>/app/config/application.yaml</code> — the file
          that declares <code>nodqora.environments</code>. Name your provider under <code>oidc</code>, or set it
          to <code>none</code> to serve this install to anyone who can reach it. Then restart, and reload this
          page.
        </p>
        <p className="first-run-doc">
          <a href="#" onClick={(e) => e.preventDefault()}>
            How to configure sign-in
          </a>
          <span className="first-run-version"> · Nodqora 0.3.0</span>
        </p>
      </div>
    </div>
  )
}

/**
 * What the canvas becomes in the moment between a `401` and the page unloading. In production this
 * lasts as long as the navigation takes; the prototype holds it for 1.5s so it can be judged.
 */
export function Renavigating({ onDone }: { onDone: () => void }) {
  useEffect(() => {
    const timer = setTimeout(onDone, 1500)
    return () => clearTimeout(timer)
  }, []) // eslint-disable-line react-hooks/exhaustive-deps
  return (
    <div className="app">
      <div className="first-run">
        <p className="first-run-headline">Your session has ended. Signing in again…</p>
      </div>
    </div>
  )
}

/** Stand-in for Keycloak. Not Nodqora's page, and deliberately not styled like it. */
export function FakeIdentityProvider() {
  return (
    <div className="proto-idp">
      <div className="proto-idp-card">
        <p className="proto-idp-realm">NODQORA</p>
        <h1>Sign in to your account</h1>
        <label>Username<input defaultValue="ada" /></label>
        <label>Password<input type="password" defaultValue="••••••" /></label>
        <button
          type="button"
          onClick={() => {
            sessionStorage.removeItem('proto-signed-out')
            sessionStorage.removeItem('proto-expired')
            location.reload()
          }}
        >
          Sign In
        </button>
        <p className="proto-idp-note">
          PROTOTYPE: stands in for <code>192.168.0.222:8080/realms/nodqora</code>. Returns to{' '}
          <code>{location.pathname + location.search}</code>.
        </p>
      </div>
    </div>
  )
}

/** Stand-in for ADR-0176's server-rendered 503 — outside the SPA, so no shell, no stylesheet of ours. */
export function FakeProviderDown() {
  return (
    <div className="proto-503">
      <h1>Sign-in is unavailable</h1>
      <p>
        Nodqora could not reach its identity provider at <code>https://idp.example.com/realms/acme</code>.
      </p>
      <p>People already signed in can keep reading. Try again shortly.</p>
      <p className="proto-idp-note">PROTOTYPE: server-rendered page, HTTP 503. Reload to retry.</p>
    </div>
  )
}

/* ------------------------------------------------------------------ switcher + state panel */

export function PrototypeBar({ log }: { log: string[] }) {
  const variant = currentVariant()
  const session = currentSession()
  const [showState, setShowState] = useState(true)
  const cycle = (dir: 1 | -1) => {
    const i = VARIANTS.indexOf(variant)
    choose('variant', VARIANTS[(i + dir + VARIANTS.length) % VARIANTS.length]!)
  }
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      const target = event.target as HTMLElement
      if (target.closest('input, textarea, select, [contenteditable]')) return
      if (event.key === 'ArrowLeft') cycle(-1)
      if (event.key === 'ArrowRight') cycle(1)
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  })
  return (
    <div className="proto-bar">
      {showState && (
        <pre className="proto-state">
          {'GET /session → 200\n' + JSON.stringify(sessionState(), null, 2) + '\n\n' + log.slice(-6).join('\n')}
        </pre>
      )}
      <div className="proto-pill">
        <button type="button" onClick={() => cycle(-1)}>←</button>
        <span>
          {variant} — {VARIANT_NAMES[variant]}
        </span>
        <button type="button" onClick={() => cycle(1)}>→</button>
        <select value={session} onChange={(e) => choose('session', e.target.value)}>
          {SESSIONS.map((key) => (
            <option key={key} value={key}>
              {SESSION_NAMES[key]}
            </option>
          ))}
        </select>
        <button type="button" onClick={() => setShowState(!showState)}>{showState ? 'hide' : 'state'}</button>
      </div>
    </div>
  )
}
