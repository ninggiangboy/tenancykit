import type { Locale, Theme } from '@suite/shell-sdk'

const THEME_KEY = 'suite.theme'
const LOCALE_KEY = 'suite.locale'

/** A browser's own preference, when storage is unavailable or holds nothing. */
export function preferredTheme(): Theme {
  const stored = read(THEME_KEY)
  if (stored === 'light' || stored === 'dark' || stored === 'system') {
    return stored
  }
  return 'system'
}

export function preferredLocale(navigatorLanguage = globalThis.navigator?.language ?? 'en'): Locale {
  const stored = read(LOCALE_KEY)
  if (stored === 'en' || stored === 'vi') {
    return stored
  }
  return navigatorLanguage.toLowerCase().startsWith('vi') ? 'vi' : 'en'
}

export function saveTheme(theme: Theme): void {
  write(THEME_KEY, theme)
}

export function saveLocale(locale: Locale): void {
  write(LOCALE_KEY, locale)
}

/** The theme a document should show: `system` follows the viewer, and a dark preference wins only in that case. */
export function resolveTheme(theme: Theme, prefersDark: boolean): 'light' | 'dark' {
  return theme === 'system' ? (prefersDark ? 'dark' : 'light') : theme
}

/**
 * Applies the theme to the document; the tokens live on `.dark`, exactly as the design system defines them.
 *
 * Every colour transition is switched off for the moment of the change: otherwise each button, row and badge fades on
 * its own for 120 ms, so the page is a mix of both themes (and unreadable to a contrast check) while it settles.
 */
export function applyTheme(theme: Theme): 'light' | 'dark' {
  const prefersDark = globalThis.matchMedia?.('(prefers-color-scheme: dark)').matches ?? false
  const resolved = resolveTheme(theme, prefersDark)
  const root = globalThis.document?.documentElement
  if (!root || root.classList.contains('dark') === (resolved === 'dark')) {
    return resolved
  }
  const freeze = globalThis.document.createElement('style')
  freeze.textContent = '*,*::before,*::after{transition:none!important}'
  globalThis.document.head.append(freeze)
  root.classList.toggle('dark', resolved === 'dark')
  // Reading a computed style makes the browser apply the new tokens while the freeze is still in place.
  void globalThis.getComputedStyle(root).color
  globalThis.setTimeout(() => freeze.remove(), 0)
  return resolved
}

function read(key: string): string | null {
  try {
    return globalThis.localStorage?.getItem(key) ?? null
  } catch {
    return null
  }
}

function write(key: string, value: string): void {
  try {
    globalThis.localStorage?.setItem(key, value)
  } catch {
    // A viewer that refuses storage still gets the choice for this page; it just is not remembered.
  }
}
