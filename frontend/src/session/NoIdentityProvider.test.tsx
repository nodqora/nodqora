// SPDX-License-Identifier: Apache-2.0
// @vitest-environment jsdom
import { afterEach, describe, expect, it } from 'vitest'
import { cleanup, render, screen } from '@testing-library/react'
import { existsSync, readFileSync } from 'node:fs'
import { NoIdentityProvider, SIGNING_IN_DOC, signingInDocUrl } from './NoIdentityProvider'

afterEach(cleanup)

describe('the no-identity-provider screen', () => {
  it('says what ADR-0177 §5 says, and nothing else', () => {
    const { container } = render(<NoIdentityProvider />)
    expect(screen.getByText('No identity provider is configured.')).toBeTruthy()
    expect(container.querySelector('.first-run-route')!.textContent).toBe(
      'Declare nodqora.authentication in /app/config/application.yaml — the same file that declares ' +
        'nodqora.environments. Name your provider under oidc, or set it to none to let anyone who can ' +
        'reach this install read it without signing in. Then restart, and reload this page.',
    )
    // ADR-0156's grammar: one route, one link, no retry, no spinner.
    expect(container.querySelectorAll('a')).toHaveLength(1)
    expect(container.querySelector('button')).toBeNull()
    // ADR-0132's sixth noun.
    expect(container.textContent).not.toMatch(/enterprise/i)
  })

  it('links how to configure sign-in', () => {
    render(<NoIdentityProvider />)
    const link = screen.getByRole('link', { name: 'How to configure sign-in' })
    expect(link.getAttribute('href')).toBe(signingInDocUrl(null))
  })
})

describe('the sign-in documentation link', () => {
  it('is pinned to the tag a released build was cut from', () => {
    expect(signingInDocUrl('0.3.0')).toBe('https://github.com/nodqora/nodqora/blob/v0.3.0/docs/install.md#signing-in')
  })

  it('falls back to main for a working tree and a snapshot', () => {
    expect(signingInDocUrl(null)).toContain('/blob/main/docs/install.md#signing-in')
    expect(signingInDocUrl('0.3.0-SNAPSHOT')).toContain('/blob/main/')
  })

  it('names a document that is actually in the tree', () => {
    expect(existsSync(`../${SIGNING_IN_DOC}`)).toBe(true)
  })

  it('lands on a section titled exactly Signing in', () => {
    // GitHub derives `#signing-in` from the heading's text, so a reworded heading breaks every
    // pinned link from that tag on, silently.
    const headings = readFileSync(`../${SIGNING_IN_DOC}`, 'utf8').match(/^#+ .*$/gm) ?? []
    expect(headings).toContain('## Signing in')
  })
})
