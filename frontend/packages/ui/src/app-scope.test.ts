import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'

const here = dirname(fileURLToPath(import.meta.url))
/** The stylesheet with every run of whitespace collapsed, so the checks do not depend on how the formatter wraps a rule. */
const theme = readFileSync(resolve(here, 'styles/theme.css'), 'utf8').replace(/\s+/g, ' ')

const apps = [
  'platform',
  'billing',
  'customer',
  'mailroom',
  'notification',
  'survey',
  'loyalty',
  'booking',
  'attribution',
  'workflow',
  'helpdesk',
]

describe('app scope in theme.css', () => {
  it('re-declares every alias that must follow the accent inside [data-app]', () => {
    const rule = theme.slice(theme.indexOf('[data-app] {'), theme.indexOf('}', theme.indexOf('[data-app] {')))
    for (const declaration of [
      '--primary: var(--app-accent)',
      '--primary-foreground: var(--app-accent-foreground)',
      '--selection: var(--app-accent-soft)',
      '--ring: var(--app-accent)',
      '--sidebar-ring: var(--app-accent)',
      '--link: var(--app-accent)',
    ]) {
      expect(rule, declaration).toContain(declaration)
    }
  })

  it.each(apps)('maps data-app="%s" to its accent', (code) => {
    expect(theme).toContain(
      `[data-app="${code}"] { --app-accent: var(--app-${code}); --app-accent-soft: var(--app-${code}-soft); }`,
    )
  })

  it('swaps the compact sizes in under data-density="compact"', () => {
    expect(theme).toContain('[data-density="compact"]')
    expect(theme).toContain('--size-control-md: var(--size-control-md-compact)')
  })
})
