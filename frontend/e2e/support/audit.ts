import { mkdirSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import type { Page } from '@playwright/test'

/** Where screenshots and the report land; a run can point it elsewhere so Playwright's output cleanup never wipes it. */
export const AUDIT_DIR = process.env.AUDIT_DIR ?? resolve(import.meta.dirname, '../test-results/audit')

export interface Finding {
  /** The look: viewport, theme and density. */
  look: string
  screen: string
  /** The acceptance id or topic the finding breaks, such as `M2` or `motion`. */
  rule: string
  target: string
  detail: string
}

export interface Measure {
  rule: string
  target: string
  detail: string
}

export interface MeasureOptions {
  phone: boolean
  compact: boolean
  /** A CSS selector to limit the measure to, such as the open dialog. The page-wide rules run only without one. */
  scope?: string
}

/**
 * Measures what the acceptance criteria say can be measured, on the page as it is now: tap targets, control heights,
 * input size on phones, sideways scroll, a second highlighted nav item, a fixed button covering content, raw ids and
 * message keys, tiny text, clipped text, the h1, tile counts, amounts and table checkboxes.
 */
export async function measure(page: Page, options: MeasureOptions): Promise<Measure[]> {
  return page.evaluate((opts) => {
    const out: Measure[] = []
    const root = (opts.scope ? document.querySelector(opts.scope) : document.body) as HTMLElement | null
    if (!root) return out
    const wide = !opts.scope

    const rectOf = (element: Element) => element.getBoundingClientRect()
    const visible = (element: Element): boolean => {
      const rect = rectOf(element)
      if (rect.width <= 1.5 || rect.height <= 1.5) return false
      if (rect.right <= 0 || rect.left >= window.innerWidth) return false
      const style = getComputedStyle(element)
      return style.visibility !== 'hidden' && style.display !== 'none' && style.opacity !== '0'
    }
    const label = (element: Element): string => {
      const text =
        element.getAttribute('aria-label') ??
        element.getAttribute('placeholder') ??
        (element.textContent ?? '').replace(/\s+/g, ' ')
      return `${element.tagName.toLowerCase()}[${text.trim().slice(0, 40)}]`
    }
    const add = (rule: string, element: Element | string, detail: string) =>
      out.push({ rule, target: typeof element === 'string' ? element : label(element), detail })
    const size = (element: Element) => {
      const rect = rectOf(element)
      return `${Math.round(rect.height * 10) / 10}x${Math.round(rect.width * 10) / 10}`
    }

    // M2 / L1 / M8: controls.
    const textual = 'input:not([type=checkbox]):not([type=radio]), select, textarea, [role=combobox]'
    for (const element of root.querySelectorAll(
      `button, a[href], [role=tab], [role=switch], [role=checkbox], ${textual}`,
    )) {
      if (!visible(element)) continue
      if (element.closest('[aria-hidden=true]')) continue
      const rect = rectOf(element)
      const style = getComputedStyle(element)
      if (element.tagName === 'A' && style.display === 'inline') continue
      const isText = element.matches(textual)
      if (opts.phone) {
        // A checkbox or switch may draw small and still reach 44px through a ::before that extends its hit area.
        const reach = getComputedStyle(element, '::before')
        const outset = (value: string) => Math.max(0, -(Number.parseFloat(value) || 0))
        const extended =
          reach.position === 'absolute' &&
          rect.height + outset(reach.top) + outset(reach.bottom) >= 43.5 &&
          rect.width + outset(reach.left) + outset(reach.right) >= 43.5
        const small = extended ? false : isText ? rect.height < 43.5 : rect.height < 43.5 || rect.width < 43.5
        if (small) add('M2 tap target below 44px', element, size(element))
        if (isText && Number.parseFloat(style.fontSize) < 16) {
          add('M8 input text below 16px', element, `${style.fontSize}`)
        }
      } else if (isText) {
        const least = opts.compact ? 28 : 32
        if (rect.height < least - 0.5)
          add('L1 text control below the density height', element, `${size(element)}, wants ${least}`)
      } else if (!element.matches('[role=checkbox], [role=switch], input')) {
        const least = opts.compact ? 24 : 28
        if (rect.height < least - 0.5)
          add('L1 control below the density height', element, `${size(element)}, wants ${least}`)
      }
    }

    // Text rules.
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT)
    const mono = /mono|menlo|courier/i
    const uuid = /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-/i
    const rawKey = /^[a-z][a-z0-9]*(\.[a-zA-Z][a-zA-Z0-9]*){1,3}$/
    const domain = /\.(test|example|com|org|io|localhost|dev|net)$/i
    for (let node = walker.nextNode(); node; node = walker.nextNode()) {
      const text = (node.textContent ?? '').trim()
      const parent = node.parentElement
      if (!text || !parent || !visible(parent)) continue
      if (parent.closest('script, style, noscript, kbd')) continue
      const style = getComputedStyle(parent)
      if (Number.parseFloat(style.fontSize) < 11.5)
        add('C small text below 11.5px', parent, `${style.fontSize}: ${text.slice(0, 30)}`)
      if (!wide) continue
      if (parent.closest('aside, [role=dialog]')) continue
      if (uuid.test(text)) add('L5 a raw id on the list', parent, text.slice(0, 40))
      if (rawKey.test(text) && !text.includes('@') && !domain.test(text) && !mono.test(style.fontFamily)) {
        add('C5 raw message key', parent, text)
      }
    }

    // Money: the currency code is the quieter second part.
    for (const element of root.querySelectorAll('[data-currency]')) {
      if (!visible(element)) continue
      const code = (element.getAttribute('data-currency') ?? '').toUpperCase()
      const quiet = [...element.querySelectorAll('*')].some((child) => (child.textContent ?? '').trim() === code)
      if (!quiet) add('C3 currency code is not styled as the quieter part', element, (element.textContent ?? '').trim())
    }

    // Clipped text.
    for (const element of root.querySelectorAll('h1, h2, th, button, [role=tab], label')) {
      if (!visible(element)) continue
      const style = getComputedStyle(element)
      if (
        element.scrollWidth > element.clientWidth + 1 &&
        /hidden|clip/.test(style.overflowX) &&
        style.textOverflow !== 'ellipsis'
      ) {
        add('clipped text', element, `${element.scrollWidth} > ${element.clientWidth}`)
      }
    }

    if (!wide) return out

    // M1.
    if (opts.phone && document.documentElement.scrollWidth > window.innerWidth) {
      add('M1 sideways scroll', 'document', `${document.documentElement.scrollWidth} > ${window.innerWidth}`)
    }

    // C1: one h1, 24px (22px on phones).
    const headings = [...document.querySelectorAll('h1')].filter(visible)
    if (headings.length !== 1) add('C1 h1 count', 'document', `${headings.length} visible h1`)
    for (const heading of headings.slice(0, 1)) {
      const want = opts.phone ? 22 : 24
      const got = Number.parseFloat(getComputedStyle(heading).fontSize)
      if (Math.abs(got - want) > 0.6) add('C1 h1 size', heading, `${got}px, wants ${want}`)
    }

    // L6: only the open nav item is highlighted. The app row is a heading for its screens, not a second selection.
    const filled = (element: Element): boolean => {
      const background = getComputedStyle(element).backgroundColor
      if (background === 'transparent' || /rgba\(0, 0, 0, 0\)/.test(background)) return false
      const alpha = /\/\s*([\d.]+)\)$/.exec(background) ?? /rgba\([^)]*,\s*([\d.]+)\)$/.exec(background)
      return alpha ? Number(alpha[1]) > 0.04 : true
    }
    const lit = [...document.querySelectorAll('aside a')].filter((element) => visible(element) && filled(element))
    if (lit.length > 1) add('L6 more than one highlighted nav item', 'aside', lit.map(label).join(' + '))

    // L5: a checkbox column only with bulk actions, and no list here has any.
    if (document.querySelector('table input[type=checkbox], table [role=checkbox]')) {
      add('L5 checkbox column without bulk actions', 'table', 'a checkbox is rendered in a table')
    }

    // Tiles: the saved-view count is a figure.
    for (const list of document.querySelectorAll('[role=tablist]')) {
      const tabs = [...list.querySelectorAll('[role=tab]')].filter(visible)
      if (tabs.length < 2 || rectOf(tabs[0] as Element).height < 50) continue
      const count = [...(tabs[0] as Element).querySelectorAll('span')].find((span) =>
        /^[\d,]+$/.test((span.textContent ?? '').trim()),
      )
      if (count && Number.parseFloat(getComputedStyle(count).fontSize) < 18) {
        add('saved-view count is not a figure', count, `${getComputedStyle(count).fontSize}, wants 20px`)
      }
    }

    // M6: a fixed button must not cover what is last on the page once it is scrolled to the end.
    const fixed = [...document.querySelectorAll('body *')].filter(
      (element) =>
        getComputedStyle(element).position === 'fixed' &&
        visible(element) &&
        element.querySelector('button') &&
        rectOf(element).width < 300 &&
        !element.closest('aside, [role=dialog], [role=status], [aria-live]'),
    )
    if (fixed.length > 0) {
      const scrollers = [document.scrollingElement as Element]
      for (let up = document.querySelector('h1')?.parentElement; up; up = up.parentElement) {
        if (up.scrollHeight > up.clientHeight + 1 && /auto|scroll/.test(getComputedStyle(up).overflowY))
          scrollers.push(up)
      }
      for (const scroller of scrollers) scroller.scrollTop = scroller.scrollHeight
      for (const button of fixed) {
        const box = rectOf(button)
        for (const other of document.querySelectorAll('button, a[href], input, [role=tab]')) {
          if (button.contains(other) || !visible(other) || other.closest('aside')) continue
          if ([...fixed].some((entry) => entry.contains(other))) continue
          const rect = rectOf(other)
          const overlap =
            Math.min(box.right, rect.right) - Math.max(box.left, rect.left) > 2 &&
            Math.min(box.bottom, rect.bottom) - Math.max(box.top, rect.top) > 2
          if (overlap) add('M6 a fixed button covers content at the end of the page', other, `under ${label(button)}`)
        }
      }
      for (const scroller of scrollers) scroller.scrollTop = 0
    }

    return out
  }, options)
}

/** Waits until the screen has no skeleton left, so a capture shows data (or its empty state), not the loading frame. */
export async function settle(page: Page): Promise<void> {
  await page.locator('h1').first().waitFor({ state: 'visible', timeout: 30_000 })
  // A remote's stylesheet is linked when it mounts; measure only once every stylesheet and font has arrived.
  await page
    .waitForFunction(
      () =>
        [...document.querySelectorAll('link[rel=stylesheet]')].every((link) => (link as HTMLLinkElement).sheet) &&
        document.fonts.status === 'loaded',
      undefined,
      { timeout: 10_000 },
    )
    .catch(() => undefined)
  await page
    .waitForFunction(() => document.querySelectorAll('.animate-shimmer').length === 0, undefined, { timeout: 10_000 })
    .catch(() => undefined)
  await page.waitForTimeout(250)
}

export async function shot(page: Page, name: string): Promise<void> {
  mkdirSync(AUDIT_DIR, { recursive: true })
  await page.screenshot({ path: resolve(AUDIT_DIR, `${name}.png`) })
}

interface OverlayProbe {
  /** Running CSS animations or transitions on the panel and its scrim a frame or two after it opened. */
  entering: number
  box: { x: number; y: number; width: number; height: number }
  viewport: { width: number; height: number }
  handle: boolean
  focusInside: boolean
  closeBox: { width: number; height: number } | null
}

/** What a modal looks like right after it opened: size, placement, grab handle, motion, focus and the close button. */
export async function probeOverlay(page: Page): Promise<OverlayProbe | null> {
  return page.evaluate(() => {
    const panels = [...document.querySelectorAll('[role=dialog], [role=alertdialog]')]
    const panel = panels.at(-1) as HTMLElement | undefined
    if (!panel) return null
    // The topmost modal is tagged so a measure can be scoped to it and a later check can tell when it has left.
    for (const other of document.querySelectorAll('[data-audit-panel]')) other.removeAttribute('data-audit-panel')
    panel.setAttribute('data-audit-panel', '')
    const scrim = document.querySelector('[data-state=open].bg-overlay')
    const rect = panel.getBoundingClientRect()
    const close = panel.querySelector('button[aria-label="Close"]')
    const closeRect = close?.getBoundingClientRect()
    return {
      entering: panel.getAnimations().length + (scrim?.getAnimations().length ?? 0),
      box: { x: rect.x, y: rect.y, width: rect.width, height: rect.height },
      viewport: { width: window.innerWidth, height: window.innerHeight },
      handle: Boolean(panel.querySelector('.touch-none')),
      focusInside: panel.contains(document.activeElement),
      closeBox: closeRect ? { width: closeRect.width, height: closeRect.height } : null,
    }
  })
}

/** Writes the findings as JSON and as a short Markdown table grouped by rule. */
export function writeReport(findings: Finding[]): string {
  mkdirSync(AUDIT_DIR, { recursive: true })
  writeFileSync(resolve(AUDIT_DIR, 'findings.json'), JSON.stringify(findings, null, 2))
  const byRule = new Map<string, Finding[]>()
  for (const finding of findings) byRule.set(finding.rule, [...(byRule.get(finding.rule) ?? []), finding])
  const lines = ['# Screen audit', '', `${findings.length} findings in ${byRule.size} rules.`, '']
  for (const [rule, items] of [...byRule].sort((a, b) => b[1].length - a[1].length)) {
    const screens = new Set(items.map((item) => item.screen))
    lines.push(`## ${rule}`, '', `${items.length} findings on ${screens.size} screens: ${[...screens].join(', ')}`, '')
    for (const item of items.slice(0, 6))
      lines.push(`- \`${item.look}\` ${item.screen}: ${item.target}: ${item.detail}`)
    lines.push('')
  }
  const path = resolve(AUDIT_DIR, 'report.md')
  writeFileSync(path, lines.join('\n'))
  return path
}
