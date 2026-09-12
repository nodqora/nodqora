// SPDX-License-Identifier: Apache-2.0

/**
 * The reader's theme: what they asked for, and what that resolves to.
 *
 * Three choices rather than two. `system` is a *state*, not the absence of one — a reader who has
 * never touched the control and a reader who deliberately pinned Dark are answering different
 * questions, and collapsing them loses the first reader's OS preference the moment they experiment
 * with the second. It is also the only choice that keeps tracking: an operator whose laptop flips
 * at sunset gets the flip, which is the behaviour nodqora.com already has.
 *
 * **Nothing here resolves `system` to a theme**, deliberately. The stylesheet resolves it with
 * `prefers-color-scheme` and XYFlow resolves it inside `colorMode`, both without JavaScript and
 * both before the first paint. A third resolution in this module would be a copy of a rule that is
 * already in two places that cannot drift, and it would only be consulted after mount — which is
 * exactly when a theme is too late. What this module owns is the *choice*: reading it, storing it,
 * and saying what the document should carry because of it.
 */
export type ThemeChoice = 'system' | 'light' | 'dark'

/** What `system` collapses to once the OS has been asked. The palette is drawn for these two. */
export type Theme = 'light' | 'dark'

/** In control order: the default first, then the two pins. */
export const THEME_CHOICES: readonly ThemeChoice[] = ['system', 'light', 'dark'] as const

export const THEME_STORAGE_KEY = 'nodqora.theme'

export function isThemeChoice(value: unknown): value is ThemeChoice {
  return value === 'system' || value === 'light' || value === 'dark'
}

/**
 * `system` is the default for *every* unreadable case, not only for an empty slot: private
 * browsing, cleared site data and a storage quota all throw on access rather than returning null,
 * and a theme control is never worth a blank screen.
 */
export function readChoice(storage: Pick<Storage, 'getItem'> | null | undefined): ThemeChoice {
  if (!storage) return 'system'
  try {
    const stored = storage.getItem(THEME_STORAGE_KEY)
    return isThemeChoice(stored) ? stored : 'system'
  } catch {
    return 'system'
  }
}

/** Best-effort, by the same reasoning: a write that throws costs the persistence, not the session. */
export function writeChoice(
  storage: Pick<Storage, 'setItem'> | null | undefined,
  choice: ThemeChoice,
): void {
  if (!storage) return
  try {
    storage.setItem(THEME_STORAGE_KEY, choice)
  } catch {
    /* The choice still applies to this tab; only its persistence is lost. */
  }
}

/**
 * What `<html>` should carry for a choice — and `null` means *remove the attribute*.
 *
 * Deliberately not the resolved theme. Under `system` the stylesheet's `prefers-color-scheme` block
 * is already correct before a line of JavaScript runs, so stamping the resolved value would only
 * duplicate the media query in a second place that can disagree with it. An unstamped root is how
 * the first paint of a cold load is already right: no inline script, and nothing to flash.
 *
 * A pin has to be stamped, because it is the one thing the media query cannot know.
 */
export function themeAttribute(choice: ThemeChoice): Theme | null {
  return choice === 'system' ? null : choice
}

/**
 * `color-scheme` is what tells the browser to draw *its* surfaces — form controls, scrollbars, the
 * canvas behind the page — in the matching theme. It takes the resolved value rather than the
 * choice, and `light dark` under `system` lets the browser follow the OS itself.
 */
export function colorSchemeProperty(choice: ThemeChoice): string {
  return choice === 'system' ? 'light dark' : choice
}

/** The control's label. Short, because it sits in a 48px bar beside four other controls. */
export function themeLabel(choice: ThemeChoice): string {
  switch (choice) {
    case 'system':
      return 'System'
    case 'light':
      return 'Light'
    case 'dark':
      return 'Dark'
  }
}
