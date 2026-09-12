// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import {
  THEME_CHOICES,
  THEME_STORAGE_KEY,
  colorSchemeProperty,
  isThemeChoice,
  readChoice,
  themeAttribute,
  themeLabel,
  writeChoice,
} from './theme'

/** A storage that behaves, and two that do not. */
const workingStorage = (initial: Record<string, string> = {}) => {
  const backing = new Map(Object.entries(initial))
  return {
    getItem: (key: string) => backing.get(key) ?? null,
    setItem: (key: string, value: string) => void backing.set(key, value),
    read: (key: string) => backing.get(key) ?? null,
  }
}

const throwingStorage = {
  getItem: () => {
    throw new DOMException('The operation is insecure.', 'SecurityError')
  },
  setItem: () => {
    throw new DOMException('QuotaExceededError')
  },
}

describe('system is a state, not the absence of one', () => {
  it('offers the default first and the two pins after it', () => {
    expect(THEME_CHOICES).toEqual(['system', 'light', 'dark'])
  })
})

describe('the root attribute is the pin, never the resolved theme', () => {
  it('stamps nothing under system, so the media query is the only rule', () => {
    // Stamping the resolved value would put the same fact in two places that can disagree, and
    // would need JavaScript to have run before the first paint is correct.
    expect(themeAttribute('system')).toBeNull()
  })

  it('stamps a pin, because it is the one thing the media query cannot know', () => {
    expect(themeAttribute('light')).toBe('light')
    expect(themeAttribute('dark')).toBe('dark')
  })

  it('hands the browser both schemes under system so it follows the OS too', () => {
    expect(colorSchemeProperty('system')).toBe('light dark')
    expect(colorSchemeProperty('dark')).toBe('dark')
    expect(colorSchemeProperty('light')).toBe('light')
  })
})

describe('an unreadable storage costs the persistence, never the session', () => {
  it('reads a stored pin back', () => {
    expect(readChoice(workingStorage({ [THEME_STORAGE_KEY]: 'dark' }))).toBe('dark')
  })

  it('falls back to system when nothing is stored', () => {
    expect(readChoice(workingStorage())).toBe('system')
  })

  it('falls back to system when the slot holds something that is not a choice', () => {
    // A hand-edited value, or a key this app used to mean something else.
    expect(readChoice(workingStorage({ [THEME_STORAGE_KEY]: 'midnight' }))).toBe('system')
  })

  it('falls back to system when the accessor throws', () => {
    // Private browsing and blocked site data throw on access rather than returning null.
    expect(readChoice(throwingStorage)).toBe('system')
  })

  it('falls back to system when there is no storage at all', () => {
    expect(readChoice(null)).toBe('system')
    expect(readChoice(undefined)).toBe('system')
  })

  it('writes a choice through', () => {
    const storage = workingStorage()
    writeChoice(storage, 'light')
    expect(storage.read(THEME_STORAGE_KEY)).toBe('light')
  })

  it('swallows a write that throws rather than taking the session down with it', () => {
    expect(() => writeChoice(throwingStorage, 'dark')).not.toThrow()
    expect(() => writeChoice(null, 'dark')).not.toThrow()
  })
})

describe('the guard', () => {
  it('accepts exactly the three choices', () => {
    expect(THEME_CHOICES.every(isThemeChoice)).toBe(true)
  })

  it('rejects everything else, including the shapes storage can hand back', () => {
    for (const value of [null, undefined, '', 'System', 'auto', 0, {}, ['dark']]) {
      expect(isThemeChoice(value)).toBe(false)
    }
  })
})

describe('the control labels', () => {
  it('names every choice', () => {
    expect(THEME_CHOICES.map(themeLabel)).toEqual(['System', 'Light', 'Dark'])
  })
})
