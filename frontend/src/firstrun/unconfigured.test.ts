// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import { existsSync } from 'node:fs'
import { CONFIGURING_DOC, configuringDocUrl, isFirstRun } from './unconfigured'
import type { Meta } from '../api/types'

const meta = (environments: { key: string; displayName: string }[]): Meta => ({
  environments,
  plugins: [],
  refresh: { graphSeconds: 300, stateSeconds: 30 },
})

describe('a known-empty roster is the first-run screen', () => {
  it('fires on the roster ADR-0152 ships, which has no environments at all', () => {
    expect(isFirstRun(meta([]))).toBe(true)
  })

  it('does not fire once an operator has declared one', () => {
    expect(isFirstRun(meta([{ key: 'production', displayName: 'Production' }]))).toBe(false)
  })

  it('does not fire on a roster that is unknown rather than empty', () => {
    // ADR-0156, and the one case that must not be got wrong. An unreachable server and an
    // unconfigured one both leave the roster unusable, and firing on the wrong one tells the reader
    // "No environments are configured" — a confident false statement about their config made from
    // no evidence. ADR-0085's argument (absence is already load-bearing) one level up.
    expect(isFirstRun(null)).toBe(false)
  })
})

describe('the documentation link is pinned to the version that is running', () => {
  it('points a released build at its own tag', () => {
    // ADR-0153: a minor version may want a config edit, so an unpinned link teaches a v0.1.0 reader
    // a grammar their install does not have, undetectably — the failure invisible to the only
    // person harmed, which ADR-0155 refused when it verified GHCR visibility rather than assuming.
    expect(configuringDocUrl('0.1.0')).toBe(
      'https://github.com/nodqora/nodqora/blob/v0.1.0/docs/running-against-your-own-cluster.md',
    )
  })

  it('falls back to main for a working tree, which has no build argument', () => {
    expect(configuringDocUrl(null)).toContain('/blob/main/')
  })

  it('falls back to main for a snapshot, because no such tag will ever exist', () => {
    // ADR-0143 makes a published version immutable; `v0.1.0-SNAPSHOT` names nothing, and a link to
    // nothing is the 404 this constant exists to avoid.
    expect(configuringDocUrl('0.1.0-SNAPSHOT')).toContain('/blob/main/')
  })

  it('names a document that is actually in the tree', () => {
    // ADR-0156 hands the install documentation a constraint, not a request: whatever it becomes,
    // this path keeps answering "how do I declare an environment", or the commit that moves it
    // updates this constant. This assertion is that constraint, enforced — a 404 from the first-run
    // screen is the worst failure available on the install route, so the link is checked against
    // the filesystem rather than against a string.
    expect(existsSync(`../${CONFIGURING_DOC}`)).toBe(true)
  })
})
