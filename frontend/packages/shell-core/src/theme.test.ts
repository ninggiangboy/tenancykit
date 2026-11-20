import { afterEach, describe, expect, it } from 'vitest'
import { createShellI18n, shellResources } from './i18n'
import {
  applyDensity,
  applyTheme,
  preferredDensity,
  preferredLocale,
  preferredTheme,
  resolveTheme,
  saveDensity,
  saveLocale,
  saveTheme,
} from './theme'

afterEach(() => {
  globalThis.localStorage?.clear()
  globalThis.document?.documentElement.classList.remove('dark')
  delete (globalThis.document?.documentElement as unknown as { className?: string })?.className
  delete globalThis.document?.documentElement.dataset.density
})

describe('theme and language', () => {
  it('freezes transitions while the theme changes and releases them straight after', async () => {
    applyTheme('dark')
    expect(document.head.textContent).toContain('transition:none')
    await new Promise((resolve) => setTimeout(resolve, 5))
    expect(document.head.textContent ?? '').not.toContain('transition:none')
    expect(document.documentElement.classList.contains('dark')).toBe(true)
  })

  it('resolves system to the viewer’s preference and keeps an explicit choice', () => {
    expect(resolveTheme('system', true)).toBe('dark')
    expect(resolveTheme('system', false)).toBe('light')
    expect(resolveTheme('light', true)).toBe('light')
    expect(resolveTheme('dark', false)).toBe('dark')
  })

  it('remembers both choices, and falls back when storage holds nothing', () => {
    saveTheme('dark')
    saveLocale('vi')
    expect(preferredTheme()).toBe('dark')
    expect(preferredLocale('en-US')).toBe('vi')

    globalThis.localStorage.clear()
    expect(preferredTheme()).toBe('system')
    expect(preferredLocale('vi-VN')).toBe('vi')
    expect(preferredLocale('en-GB')).toBe('en')
  })

  it('puts the design system’s dark class on the document', () => {
    applyTheme('dark')
    expect(globalThis.document.documentElement.classList.contains('dark')).toBe(true)

    applyTheme('light')
    expect(globalThis.document.documentElement.classList.contains('dark')).toBe(false)
  })

  it('remembers the density choice and writes it on the document', () => {
    expect(preferredDensity()).toBe('comfortable')

    saveDensity('compact')
    expect(preferredDensity()).toBe('compact')

    applyDensity('compact')
    expect(globalThis.document.documentElement.dataset.density).toBe('compact')
  })
})

describe('the shell catalogs', () => {
  it('ships the same keys in both languages', () => {
    expect(Object.keys(shellResources.vi.translation).sort()).toEqual(Object.keys(shellResources.en.translation).sort())
  })

  it('translates through the instance', () => {
    const i18n = createShellI18n('vi')
    expect(i18n.t('shell.platformScope')).toBe('Phạm vi nền tảng')

    const english = createShellI18n('en')
    expect(english.t('shell.platformScope')).toBe('Platform scope')
  })
})
