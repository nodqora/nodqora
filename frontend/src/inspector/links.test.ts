// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import { navigable } from './links'

describe('a link opens http and https and nothing else', () => {
  it('keeps the two schemes a browser navigates to', () => {
    expect(navigable('https://github.com/acme/payments-api')).toBe('https://github.com/acme/payments-api')
    expect(navigable('http://grafana.internal:3000/d/abc?var-env=prod')).toBe(
      'http://grafana.internal:3000/d/abc?var-env=prod',
    )
    expect(navigable('HTTPS://docs.acme.io/x')).toBe('https://docs.acme.io/x')
  })

  it('gives script no href, however the scheme is spelled', () => {
    expect(navigable('javascript:alert(1)')).toBeNull()
    expect(navigable('javascript://%0aalert(document.domain)')).toBeNull()
    expect(navigable('JaVaScRiPt:alert(1)')).toBeNull()
    expect(navigable(' javascript:alert(1)')).toBeNull()
    expect(navigable('java\tscript:alert(1)')).toBeNull()
    expect(navigable('data:text/html,<script>alert(1)</script>')).toBeNull()
    expect(navigable('vbscript:msgbox(1)')).toBeNull()
  })

  it('gives no href to what is not an absolute URL', () => {
    expect(navigable('')).toBeNull()
    expect(navigable('acme/payments-api')).toBeNull()
    expect(navigable('//evil.example/x')).toBeNull()
  })
})
