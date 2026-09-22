// SPDX-License-Identifier: Apache-2.0
// @vitest-environment jsdom
import { afterEach, describe, expect, it } from 'vitest'
import { cleanup, render, screen } from '@testing-library/react'
import { readFileSync } from 'node:fs'
import { SessionArea, shownName } from './SessionArea'
import type { SessionState } from '../api/types'

/**
 * ADR-0177 §3, asserted against the rendered area rather than a restatement of it: the sign-out is a
 * form, and what matters is the form's method, action and hidden field — which only exist in a DOM.
 */

const session = (name: string) => JSON.parse(readFileSync(`../fixtures/golden/${name}`, 'utf8')) as SessionState

afterEach(cleanup)

describe('the session area', () => {
  it('shows the name and a sign-out that posts the CSRF token to /logout', () => {
    const { container } = render(<SessionArea session={session('session-signed-in.json')} />)
    expect(screen.getByText('Ada Lovelace')).toBeTruthy()

    const form = container.querySelector('form')!
    expect(form.getAttribute('method')).toBe('post')
    expect(form.getAttribute('action')).toBe('/logout')
    const hidden = form.querySelector('input[type="hidden"]') as HTMLInputElement
    // The parameter name is the server's, not Spring's default hard-coded here.
    expect(hidden.name).toBe('_csrf')
    expect(hidden.value).toBe('<token>')
    expect(screen.getByRole('button', { name: 'Sign out' }).getAttribute('type')).toBe('submit')
  })

  it('names an open install as plain text, with nothing to press', () => {
    // ADR-0173 §2: an open install has no sign-out and no token to carry.
    const { container } = render(<SessionArea session={session('session-open.json')} />)
    expect(screen.getByText('Open — no sign-in')).toBeTruthy()
    expect(container.querySelector('form, button')).toBeNull()
  })

  it.each(['session-signed-out.json', 'session-not-configured.json'])('shows nothing for %s', (file) => {
    const { container } = render(<SessionArea session={session(file)} />)
    expect(container.textContent).toBe('')
  })

  it('shows nothing before the session state has been read', () => {
    const { container } = render(<SessionArea session={null} />)
    expect(container.textContent).toBe('')
  })

  it('truncates a long name and carries it whole in the title', () => {
    const long = 'Augusta Ada King, Countess of Lovelace'
    render(<SessionArea session={{ ...session('session-signed-in.json'), name: long } as SessionState} />)
    const shown = screen.getByTitle(long)
    expect(shown.textContent).toBe(shownName(long))
  })
})

describe('the shown name', () => {
  it('leaves eighteen characters alone', () => {
    expect(shownName('Ada Lovelace')).toBe('Ada Lovelace')
    expect(shownName('x'.repeat(18))).toBe('x'.repeat(18))
  })

  it('truncates past eighteen with an ellipsis, to eighteen in all', () => {
    const shown = shownName('Augusta Ada King, Countess of Lovelace')
    expect(shown).toBe('Augusta Ada King,…')
    expect([...shown]).toHaveLength(18)
  })

  it('counts characters, not UTF-16 units, so it never splits one', () => {
    const name = '𝒜'.repeat(20)
    expect([...shownName(name)]).toEqual([...'𝒜'.repeat(17), '…'])
  })

  it('shows an opaque sub as it is', () => {
    // ADR-0176 chose it over an empty space; the fix is the operator's name-claim, not a guess here.
    expect(shownName('f81d4fae-7dec')).toBe('f81d4fae-7dec')
  })
})
