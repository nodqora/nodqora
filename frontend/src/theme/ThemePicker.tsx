// SPDX-License-Identifier: Apache-2.0
import { useCallback, useEffect, useState } from 'react'
import {
  THEME_CHOICES,
  type ThemeChoice,
  colorSchemeProperty,
  readChoice,
  themeAttribute,
  themeLabel,
  writeChoice,
} from './theme'

/**
 * Holds the reader's choice and puts it on `<html>`.
 *
 * The initial read is lazy so it happens once per mount rather than on every render, and it reads
 * from `localStorage` directly rather than taking it as a prop: there is one document and one
 * storage, and threading them through the shell would be ceremony around a browser global that the
 * pure half of this module already isolates for the tests.
 */
export function useTheme(): [ThemeChoice, (next: ThemeChoice) => void] {
  const [choice, setChoice] = useState<ThemeChoice>(() =>
    readChoice(typeof localStorage === 'undefined' ? null : localStorage),
  )

  useEffect(() => {
    const root = document.documentElement
    const attribute = themeAttribute(choice)
    if (attribute === null) {
      // `system` removes the stamp rather than writing one, so the stylesheet's media query is the
      // only rule in play and the two can never disagree.
      root.removeAttribute('data-theme')
    } else {
      root.setAttribute('data-theme', attribute)
    }
    root.style.colorScheme = colorSchemeProperty(choice)
  }, [choice])

  const choose = useCallback((next: ThemeChoice) => {
    setChoice(next)
    writeChoice(typeof localStorage === 'undefined' ? null : localStorage, next)
  }, [])

  return [choice, choose]
}

/**
 * A `<select>`, matching the environment switcher immediately to its left rather than inventing a
 * second idiom for the same shape of control — one closed list, one current value. A sun/moon
 * toggle was rejected for the reason ADR-0017 gives about health: an icon that cycles cannot show
 * that `System` is a third state, and the reader cannot tell a pinned Light from a System that
 * happens to be light.
 */
export function ThemePicker({
  choice,
  onChoose,
}: {
  choice: ThemeChoice
  onChoose: (next: ThemeChoice) => void
}) {
  return (
    <label className="theme-picker">
      Theme
      <select
        value={choice}
        aria-label="Theme"
        onChange={(event) => onChoose(event.target.value as ThemeChoice)}
      >
        {THEME_CHOICES.map((value) => (
          <option key={value} value={value}>
            {themeLabel(value)}
          </option>
        ))}
      </select>
    </label>
  )
}
