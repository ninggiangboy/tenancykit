import AxeBuilder from '@axe-core/playwright'
import { expect, type Page } from '@playwright/test'
import { waitForShell } from './console'

export const VIEWPORTS = {
  desktop: { width: 1280, height: 800 },
  tablet: { width: 900, height: 800 },
  phone: { width: 390, height: 844 },
} as const

export type ViewportName = keyof typeof VIEWPORTS

export const THEMES = ['light', 'dark'] as const
export type LookTheme = (typeof THEMES)[number]

export const DENSITIES = ['comfortable', 'compact'] as const
export type LookDensity = (typeof DENSITIES)[number]

/** The look a person chose is stored per browser; reloading is what applies it to `<html>`. */
export async function applyLook(page: Page, theme: LookTheme, density: LookDensity): Promise<void> {
  await page.evaluate(
    ([nextTheme, nextDensity]) => {
      localStorage.setItem('suite.theme', nextTheme)
      localStorage.setItem('suite.density', nextDensity)
    },
    [theme, density] as [LookTheme, LookDensity],
  )
  await page.reload()
  await waitForShell(page)
}

/** A full-page capture, plus the two rules every phone screen has to keep: no sideways scroll and no error page. */
export async function capture(page: Page, name: string): Promise<void> {
  await page.screenshot({ path: `test-results/visual/${name}.png`, fullPage: true })
  if (page.viewportSize()?.width === 390) {
    const fits = await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)
    expect(fits, `${name} does not scroll sideways`).toBe(true)
  }
  await expect(page.getByText('That did not work')).toHaveCount(0)
}

/** The contrast and touch-target rules only; returns the violation ids with their first target. */
export async function axeLook(page: Page): Promise<string[]> {
  const results = await new AxeBuilder({ page }).withRules(['color-contrast', 'target-size']).analyze()
  return results.violations.map(
    (violation) => `${violation.id}: ${violation.nodes[0]?.target?.join(' ') ?? 'unknown target'}`,
  )
}
